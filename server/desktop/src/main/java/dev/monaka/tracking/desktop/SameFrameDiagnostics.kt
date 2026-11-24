package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.tracking.MonakaResolvedTickSnapshot
import dev.monaka.tracking.MonakaRuntime
import dev.monaka.tracking.MonakaSolverCommitResult
import dev.monaka.tracking.ResolvedTrackingPose
import dev.slimevr.desktop.platform.ProtobufMessages.ProtobufMessage
import dev.slimevr.desktop.platform.ProtobufMessages.Position
import dev.slimevr.desktop.platform.TransportSessionHandle
import dev.slimevr.tracking.processor.BoneType
import dev.slimevr.tracking.processor.FullResetDiagnostics
import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Opt-in local evidence only. Admission never waits for disk, and failures never own tracking.
 * All maps contain detached scalar/list snapshots captured at their stated boundary.
 */
class SameFrameDiagnostics internal constructor(
	private val output: OutputStream,
	capacity: Int = 128,
	private val maxFrames: Long = 4096,
	private val maxBytes: Long = 16 * 1024 * 1024,
	internal val resetWindow: Boolean = false,
	private val systemClock: () -> Long = System::nanoTime,
) : AutoCloseable {
	init {
		require(capacity in 1..1024)
		require(maxFrames in 1..65536)
		require(maxBytes in 1024..64 * 1024 * 1024)
		require(!resetWindow || capacity >= 104) { "Reset window requires bounded pre-buffer/event queue space" }
	}
	private val queue = ArrayBlockingQueue<Map<String, Any?>>(capacity)
	private val admissionLock = Any()
	private val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
	private val admitted = AtomicLong()
	val dropped = AtomicLong()
	val written = AtomicLong()
	val bytesWritten = AtomicLong()
	@Volatile var terminationReason: String? = null
		private set
	@Volatile var captureIncomplete = false
		private set
	private val preFrames = ArrayDeque<Pair<Long, Map<String, Any?>>>()
	private var lastSampleSystemNanos: Long? = null
	private var captureStartedSystemNanos: Long? = null
	private var captureCompletedSystemNanos: Long? = null
	private var earliestPreSystemNanos: Long? = null
	private var latestFrameSystemNanos: Long? = null
	private var captureGeneration = 0L
	private var forceNextFrame = false
	internal val bufferedPreFrames get() = preFrames.size

	@Volatile private var closed = false

	@Volatile var failure: String? = null
		private set
	val accepting get() = !closed && admitted.get() < maxFrames
	private val worker = Thread({ writeLoop() }, "monaka-frame-diagnostics").also {
		it.isDaemon = true
		it.start()
	}

	internal fun identity(value: String?): String? = value?.let {
		val digest = MessageDigest.getInstance("SHA-256")
		digest.update(salt)
		HexFormat.of().formatHex(digest.digest(it.toByteArray(Charsets.UTF_8)))
	}
	internal fun offer(record: Map<String, Any?>): Boolean = synchronized(admissionLock) {
		if (!accepting) {
			dropped.incrementAndGet()
			if (resetWindow) captureIncomplete = true
			return false
		}
		if (!queue.offer(record)) {
			dropped.incrementAndGet()
			if (resetWindow) captureIncomplete = true
			return false
		}
		admitted.incrementAndGet()
		if (admitted.get() == maxFrames) {
			terminationReason = "MAX_RECORDS"
			if (resetWindow) { captureIncomplete = true; closed = true }
		}
		true
	}

	/** Sampling clock is the same System.nanoTime domain as the real reset boundary. */
	internal fun shouldRecordFrame(): Boolean {
		if (!accepting) return false
		if (!resetWindow) return true
		val now = systemClock()
		if (forceNextFrame || lastSampleSystemNanos?.let { now - it >= SAMPLE_INTERVAL_NS } != false) {
			forceNextFrame = false
			lastSampleSystemNanos = now
			return true
		}
		return false
	}

	internal fun recordReset(state: FullResetDiagnostics.Snapshot) {
		if (!accepting) return
		if (resetWindow && state.phase == "STARTED" && captureStartedSystemNanos == null) {
			captureStartedSystemNanos = state.startedSystemNanos
			captureGeneration = state.generation
			trimPreFrames(state.startedSystemNanos!!)
			earliestPreSystemNanos = preFrames.firstOrNull()?.first
			while (preFrames.isNotEmpty()) offer(preFrames.removeFirst().second)
		}
		if (resetWindow && state.generation == captureGeneration && state.phase == "COMPLETED") captureCompletedSystemNanos = state.completedSystemNanos
		if (resetWindow && state.phase == "COMPLETED") forceNextFrame = true
		if (state.phase == "FAILED") captureIncomplete = true
		offer(
			mapOf(
				"schema" to "monaka.full_reset.v1", "event" to "FULL_RESET", "reset_type" to "FULL",
				"reset_generation" to state.generation.toString(), "phase" to state.phase,
				"system_ns" to (state.completedSystemNanos ?: state.startedSystemNanos)?.toString(),
				"clock_domain" to "system_nanotime", "owner" to "HUMAN_SKELETON_FULL_RESET",
				"started_system_ns" to state.startedSystemNanos?.toString(),
				"completed_system_ns" to state.completedSystemNanos?.toString(),
			),
		)
		if (resetWindow && state.phase == "FAILED") { terminationReason = "RESET_FAILED"; close() }
	}

	private fun trimPreFrames(now: Long) {
		while (preFrames.isNotEmpty() && (now - preFrames.first().first > PRE_WINDOW_NS || preFrames.size > 101)) preFrames.removeFirst()
	}

	internal fun recordFrame(record: Map<String, Any?>) {
		if (!resetWindow) { offer(record); return }
		val now = requireNotNull(record["system_ns"] as? String).toLong()
		val started = captureStartedSystemNanos
		if (started == null) {
			preFrames.addLast(now to record)
			trimPreFrames(now)
		} else {
			latestFrameSystemNanos = now
			// Reserve two queue slots for actual reset event boundaries, never wait for I/O.
			if (queue.remainingCapacity() > 2) offer(record) else { dropped.incrementAndGet(); captureIncomplete = true }
			if (captureCompletedSystemNanos?.let { now - it >= POST_WINDOW_NS } == true) {
				terminationReason = "POST_WINDOW_COMPLETE"
				close()
			}
		}
	}

	/** Called only after sendBuffer reports a complete write. No native buffers are retained.
	 * Position has optional XYZ and dataSource, but quaternion fields have no wire presence bits.
	 * Missing dataSource is legacy/unknown validity, not the enum's default NONE.
	 */
	fun recordCompletedPipeWrite(
		message: ProtobufMessage,
		bridge: String,
		pipe: String,
		session: TransportSessionHandle?,
		framedBytes: Int,
	) {
		if (!accepting) return
		runCatching {
			val observedAtSystemNanos = System.nanoTime()
			val detail: Map<String, Any?> = when {
				message.hasPosition() -> message.position.let { p ->
					mapOf(
						"message" to "position", "tracker_id" to p.trackerId,
						"position" to listOf(if (p.hasX()) finite(p.x) else null, if (p.hasY()) finite(p.y) else null, if (p.hasZ()) finite(p.z) else null),
						"position_presence" to listOf(p.hasX(), p.hasY(), p.hasZ()),
						"rotation_xyzw" to listOf(finite(p.qx), finite(p.qy), finite(p.qz), finite(p.qw)),
						"rotation_presence" to "NOT_ENCODED_BY_SCHEMA",
						"data_source_present" to p.hasDataSource(),
						"data_source" to if (p.hasDataSource()) p.dataSource.name else null,
						"data_source_value" to if (p.hasDataSource()) p.dataSourceValue else null,
						"semantic_validity" to if (p.hasDataSource()) "EXPLICIT_DATA_SOURCE" else "UNKNOWN_LEGACY",
						"position_validity" to if (!p.hasDataSource()) null else when (p.dataSource) {
							Position.DataSource.FULL -> true
							Position.DataSource.IMU, Position.DataSource.NONE -> false
							else -> null
						},
						"orientation_validity" to if (!p.hasDataSource()) null else when (p.dataSource) {
							Position.DataSource.FULL, Position.DataSource.IMU -> true
							Position.DataSource.NONE -> false
							else -> null
						},
						"validity_basis" to "DECLARED_DATA_SOURCE_NOT_PHYSICAL",
					)
				}
				message.hasTrackerAdded() -> message.trackerAdded.let { t ->
					mapOf(
						"message" to "tracker_added", "tracker_id" to t.trackerId,
						"tracker_serial" to t.trackerSerial, "tracker_name" to t.trackerName,
						"tracker_role" to t.trackerRole,
					)
				}
				else -> return
			}
			offer(
				mapOf(
					"schema" to "monaka.pipe_output.v1",
					"output_boundary" to "COMPLETED_PIPE_WRITE",
					"system_ns" to observedAtSystemNanos.toString(), "clock_domain" to "system_nanotime",
					"bridge" to bridge, "pipe" to pipe, "transport_epoch" to session?.epoch,
					"framed_bytes" to framedBytes, "steamvr_consumed" to null,
					"dropped_before" to dropped.get().toString(),
				) + detail,
			)
		}
	}
	private fun abandonPending() = synchronized(admissionLock) {
		closed = true
		dropped.addAndGet(1L + queue.size)
		queue.clear()
		if (resetWindow) captureIncomplete = true
	}
	private fun writeLoop() {
		try {
			output.use { stream ->
				val mapper = ObjectMapper()
				var bytes = 0L
				while (!closed || queue.isNotEmpty()) {
					val next = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
					val encoded = mapper.writeValueAsBytes(next)
					if (encoded.size > 256 * 1024 || bytes + encoded.size + 1 > maxBytes - if (resetWindow) 1024 else 0) {
						terminationReason = if (encoded.size > 256 * 1024) "MAX_RECORD_BYTES" else "MAX_BYTES"
						abandonPending()
						break
					}
					stream.write(encoded)
					stream.write(10)
					bytes += encoded.size + 1
					bytesWritten.set(bytes)
					written.incrementAndGet()
				}
				if (resetWindow) {
					val summary = mapper.writeValueAsBytes(
						mapOf(
							"schema" to "monaka.capture_end.v1", "capture_profile" to "full_reset_window",
							"termination_reason" to terminationReason, "records_written" to written.get().toString(),
							"bytes_written" to bytes.toString(), "dropped" to dropped.get().toString(),
							"capture_incomplete" to (captureIncomplete || terminationReason != "POST_WINDOW_COMPLETE"),
							"failure" to failure, "reset_generation" to captureGeneration.toString(),
							"pre_window_ns" to PRE_WINDOW_NS.toString(), "post_window_ns" to POST_WINDOW_NS.toString(),
							"sample_interval_ns" to SAMPLE_INTERVAL_NS.toString(),
							"actual_pre_ns" to earliestPreSystemNanos?.let { captureStartedSystemNanos!! - it }?.toString(),
							"actual_post_ns" to captureCompletedSystemNanos?.let { completed -> latestFrameSystemNanos?.minus(completed) }?.toString(),
						),
					)
					if (bytes + summary.size + 1 <= maxBytes) { stream.write(summary); stream.write(10) }
				}
				stream.flush()
			}
		} catch (e: Exception) {
			failure = e.javaClass.simpleName // Never leak path/identity from exception messages.
			terminationReason = "WRITER_FAILURE"
			abandonPending()
		} finally {
			close()
		}
	}

	/** No join on the tracking/server thread. A permanently blocked OS write is daemon-only. */
	override fun close() {
		synchronized(admissionLock) {
			if (terminationReason == null) terminationReason = "EXPLICIT_CLOSE"
			closed = true
		}
	}
	fun awaitTermination(timeoutMillis: Long): Boolean {
		require(timeoutMillis in 1..5000)
		worker.join(timeoutMillis)
		return !worker.isAlive
	}

	internal fun before(
		tick: MonakaResolvedTickSnapshot,
		runtime: MonakaRuntime,
		poses: Map<TrackerPosition, ResolvedTrackingPose>,
		commit: MonakaSolverCommitResult,
		skeleton: HumanSkeleton,
		positionCorrection: dev.monaka.tracking.NormalPositionCorrectionDiagnostics? = null,
		armInputFrame: Boolean = true,
	): Map<String, Any?> {
		val selected = tick.assignment.targets.values.flatMap { listOfNotNull(it.mainTracker?.observationId, it.rotationFallbackTracker?.observationId) }.toSet()
		if (resetWindow) {
			return linkedMapOf<String, Any?>(
				"schema" to "monaka.same_frame.v1", "capture_profile" to "full_reset_window",
				"tick" to tick.tickSequence.toString(), "now_ns" to tick.nowNanos.toString(),
				"system_ns" to systemClock().toString(), "system_clock_domain" to "system_nanotime",
				"clock_domain" to "consumer_monotonic", "assignment_generation" to tick.assignment.generation.toString(),
				"input_trackers" to inputTrackers(tick, runtime, skeleton),
				"bones_before" to bones(skeleton, true),
			).also {
				it.putAll(resetFields(skeleton))
				if (armInputFrame) skeleton.beginDiagnosticInputFrame(tick.tickSequence)
			}
		}
		return linkedMapOf(
			"schema" to "monaka.same_frame.v1",
			"tick" to tick.tickSequence.toString(), "now_ns" to tick.nowNanos.toString(),
			"system_ns" to System.nanoTime().toString(),
			"system_clock_domain" to "system_nanotime",
			"clock_domain" to "consumer_monotonic", "assignment_generation" to tick.assignment.generation.toString(),
			"paused" to tick.paused, "writeback_owner" to commit.owner.name,
			"writeback_attempted" to commit.writebackAttempted, "writeback_tick" to commit.tickSequence.toString(),
			"position_correction" to if (positionCorrection == null) "INPUTS_UNAVAILABLE_OR_NO_PAIR" else "NORMAL_RUNTIME",
			"position_correction_detail" to positionCorrection?.let { p ->
				mapOf(
					"hmd_available" to p.hmdAvailable, "geometry" to p.geometry,
					"prediction" to vector(p.prediction), "measured_teacher" to vector(p.measuredTeacher),
					"correction_world" to vector(p.correctionWorld), "applied_position" to vector(p.appliedPosition),
					"solver_position" to vector(p.solverPosition), "solver_position_reference" to p.solverPositionReference,
					"learning" to p.learning, "continuity" to p.continuity,
				)
			},
			"body_rotation_guidance" to runtime.bodyRotationResults.map { (target, r) ->
				mapOf(
					"target" to target.name,
					"ready" to r.state.ready,
					"phase" to r.state.phase.name,
					"learning" to r.learning.name,
					"learning_reason" to r.learningReason.name,
					"correction_xyzw" to quaternion(r.state.correction),
					"fallback_xyzw" to quaternion(r.fallbackCandidate?.orientation),
				)
			},
			"observations" to runtime.pipeline.observations(tick.nowNanos, tick.assignment).filter { it.sourceId in selected }.map { o ->
				mapOf(
					"identity" to identity(o.sourceId), "target" to o.target.name, "modality" to o.modality.name,
					"position" to vector(o.position), "rotation_xyzw" to quaternion(o.rotation),
					"position_quality" to o.positionQuality.name, "rotation_quality" to o.rotationQuality.name,
					"sequence" to o.provenance?.sequence?.toString(), "sample_ns" to o.provenance?.sampleAtNanos?.toString(),
					"age_ns" to o.provenance?.sampleAtNanos?.let { (tick.nowNanos - it).toString() },
					"source_epoch" to identity(o.provenance?.sourceEpoch), "calibration_epoch" to identity(o.provenance?.calibrationEpoch),
				)
			},
			"accepted_mvip" to runtime.mtp.samples().filterKeys { it.observationId in selected }.map { (key, sample) ->
				val p = sample.pose
				mapOf(
					"identity" to identity(key.observationId), "publisher" to identity(p.publisher_id),
					"source" to identity(p.source_id), "tracker" to identity(p.tracker_id), "session" to identity(p.session_id),
					"clock" to identity(p.clock_id), "sequence" to p.sequence.toString(), "remote_timestamp_ns" to p.timestamp_ns.toString(),
					"remote_sent_at_ns" to p.sent_at_ns.toString(), "local_sample_ns" to sample.sampleTime.toString(),
					"convention" to p.convention,
					"input_source" to identity(p.input.source_id), "input_device" to identity(p.input.device_id),
					"input_sequence" to p.input.sequence.toString(), "orientation_evidence" to p.input.orientation_evidence,
					"timestamp_kind" to p.timestamp_kind,
					"input_session" to identity(p.input.session_id), "modality" to p.modality,
					"validity" to mapOf("position" to p.positionValid, "orientation" to p.orientationValid),
				) + sample.legacyPose?.let {
					mapOf("mapping_revision" to it.mapping_revision.toString(), "space_id" to identity(it.coordinate_space.id),
						"space_revision" to it.coordinate_space.revision.toString())
				}.orEmpty()
			},
			"resolved" to poses.values.map { p ->
				mapOf(
					"target" to p.target.name,
					"position_owner" to identity(p.positionOwner),
					"rotation_owner" to identity(p.rotationOwner),
					"position" to vector(p.position?.value),
					"rotation_xyzw" to quaternion(p.rotation?.value),
				)
			},
			"ik_targets_before" to targets(skeleton), "bones_before" to bones(skeleton),
			"input_trackers" to inputTrackers(tick, runtime, skeleton),
		).also { it.putAll(resetFields(skeleton)); if (armInputFrame) skeleton.beginDiagnosticInputFrame(tick.tickSequence) }
	}
	internal fun after(before: Map<String, Any?>, skeleton: HumanSkeleton, direct: Iterable<Tracker>, postIk: Boolean,
		consumedInputs: List<HumanSkeleton.ConsumedRotationInput>? = null) {
		recordFrame(
			before +
				mapOf(
					"post_ik_hook" to postIk,
					"solver_enabled" to skeleton.ikSolver.enabled,
					"consumed_rotation_inputs" to (consumedInputs ?: skeleton.finishDiagnosticInputFrame()).let { if (resetWindow) it.filter { input -> input.target == TrackerPosition.CHEST }.take(16) else it }.map { input ->
						mapOf(
							"boundary" to "HUMAN_SKELETON_FK_ROTATION_READ",
							"tick" to input.tick.toString(), "target" to input.target?.name,
							"read_site" to input.readSite,
							"identity" to identity(input.trackerIdentity),
							"has_position" to input.hasPosition, "has_rotation" to input.hasRotation,
							"position" to if (input.hasPosition) listOf(input.px, input.py, input.pz).map { it?.let(::finite) } else null,
							"rotation_xyzw" to if (input.hasRotation) listOf(input.qx, input.qy, input.qz, input.qw).map { it?.let(::finite) } else null,
						)
					},
					"ik_targets_after" to if (resetWindow) emptyList<Any>() else targets(skeleton),
					"bones_after" to bones(skeleton, resetWindow),
					"computed_trackers" to skeleton.humanPoseManager.computedTrackers.filter { !resetWindow || it === skeleton.computedChestTracker }.map { tracker(it, false) },
					"direct_outputs" to if (resetWindow) emptyList<Any>() else direct.map { tracker(it, true) },
					"output_boundary" to "PRE_BRIDGE_DATA_WRITE",
					"steamvr_observed" to false,
				),
		)
	}
	private fun resetFields(skeleton: HumanSkeleton): Map<String, Any?> = skeleton.humanPoseManager.fullResetDiagnostics.snapshot.let { state ->
		mapOf(
			"reset_generation" to state.generation.toString(), "last_reset_type" to if (state.generation == 0L) null else "FULL",
			"last_reset_system_ns" to state.startedSystemNanos?.toString(),
			"last_reset_completed_system_ns" to state.completedSystemNanos?.toString(), "reset_phase" to state.phase,
		)
	}

	private fun inputTrackers(tick: MonakaResolvedTickSnapshot, runtime: MonakaRuntime, skeleton: HumanSkeleton): List<Map<String, Any?>> {
		val target = TrackerPosition.CHEST
		val relation = tick.assignment.targets[target]
		val selected = listOfNotNull(relation?.mainTracker?.observationId, relation?.rotationFallbackTracker?.observationId).toSet()
		// Raw store snapshot only. The freshness/eligibility overload updates replay cursors.
		val observations = runtime.pipeline.observations().associateBy { it.sourceId }
		val mvip = runtime.mtp.samples().asSequence().filter { it.key.observationId in selected }.take(16).map { (key, sample) ->
			val p = sample.pose
			val observation = observations[key.observationId]
			val selectedRotation = tick.constraints[target]?.rotation?.takeIf { it.sourceId == key.observationId }
			mapOf(
				"identity" to identity(key.observationId), "target" to target.name,
				"source_domain" to "MVIP_ADMITTED_INPUT", "orientation_source" to "MVIP_ACCEPTED_SAMPLE_ORIENTATION",
				"gui_orientation_available" to false, "gui_orientation_unavailable_reason" to "CATALOG_ONLY_NO_ORIENTATION_DISPLAY",
				"rotation_xyzw" to p.orientation?.map { it.takeIf(Double::isFinite) },
				"selected_rotation_xyzw" to quaternion(selectedRotation?.value),
				"has_position" to p.positionValid, "has_rotation" to p.orientationValid,
				"rotation_selected" to (selectedRotation != null), "ingested_rotation_quality" to observation?.rotationQuality?.name,
				"selection_basis" to "PINNED_TICK_CONSTRAINT", "freshness_evaluation" to "NOT_REEVALUATED_DIAGNOSTIC",
				"modality" to p.modality, "sequence" to p.sequence.toString(),
				"sample_ns" to sample.sampleTime.toString(), "age_ns" to (tick.nowNanos - sample.sampleTime).toString(),
				"reference_adjusted_rotation_xyzw" to null, "identity_adjusted_rotation_xyzw" to null,
			)
		}.toList()
		val names = selected.filter { it.startsWith("slime:") }.map { it.removePrefix("slime:") }.toSet()
		val legacy = skeleton.diagnosticInputOrientations(target, names).map { input ->
			mapOf(
				"identity" to identity("slime:${input.name}"), "target" to target.name, "tracker_target" to input.target?.name,
				"source_domain" to "SLIME_TRACKER_OBJECT", "orientation_source" to "EXISTING_GUI_DATA_FEED_TRACKER",
				"gui_orientation_available" to true, "has_position" to input.hasPosition, "has_rotation" to input.hasRotation,
				"rotation_xyzw" to quaternion(input.referenceAdjusted), "raw_rotation_xyzw" to quaternion(input.raw),
				"reference_adjusted_rotation_xyzw" to quaternion(input.referenceAdjusted),
				"identity_adjusted_rotation_xyzw" to quaternion(input.identityAdjusted),
				"reference_adjusted_available" to input.referenceAdjustedAvailable, "status" to input.status,
				"sending_data" to input.sendingData,
				"gui_mapping" to "TABLE_RAW_OR_REFERENCE_ADJUSTED;IMU_WIDGET_IDENTITY_ADJUSTED",
			)
		}
		return mvip + legacy
	}
	private fun tracker(t: Tracker, direct: Boolean): Map<String, Any?> {
		val pose = t.monakaOutputPose
		return mapOf(
			"tracker_id" to t.id,
			"identity" to identity(t.name),
			"target" to t.trackerPosition?.name,
			"status" to t.status.name,
			"position" to vector(if (direct) pose?.position?.value else t.position.takeIf { t.hasPosition }),
			"rotation_xyzw" to quaternion(if (direct) pose?.rotation?.value else t.getRotation().takeIf { t.hasRotation }),
			"semantics" to if (direct) "nullable_output_components" else "computed_candidate_not_physical_teacher",
		)
	}
	private fun targets(s: HumanSkeleton) = s.ikSolver.diagnosticPositionTargets().map {
		mapOf("identity" to identity(it.trackerIdentity), "target" to it.target?.name, "position" to vector(it.position))
	}
	private fun bones(s: HumanSkeleton, chestOnly: Boolean = false) = BoneType.values().filter { !chestOnly || it in CHEST_BONES }.map { type ->
		val b = s.getBone(type)
		mapOf(
			"bone" to type.name,
			"head" to vector(b.getPosition()),
			"tail" to vector(b.getTailPosition()),
			"rotation_xyzw" to quaternion(b.getGlobalRotation()),
			"length" to finite(b.length),
		)
	}
	companion object {
		internal const val SAMPLE_INTERVAL_NS = 50_000_000L
		internal const val PRE_WINDOW_NS = 5_000_000_000L
		internal const val POST_WINDOW_NS = 5_000_000_000L
		private val CHEST_BONES = setOf(BoneType.CHEST, BoneType.UPPER_CHEST, BoneType.CHEST_TRACKER)
		internal fun finite(v: Float): Float? = v.takeIf { it.isFinite() }
		internal fun vector(v: Vector3?): List<Float?>? = v?.let { listOf(finite(it.x), finite(it.y), finite(it.z)) }
		internal fun quaternion(q: Quaternion?): List<Float?>? = q?.let { listOf(finite(it.x), finite(it.y), finite(it.z), finite(it.w)) }
		fun open(path: Path, resetWindow: Boolean = false): SameFrameDiagnostics {
			require(path.isAbsolute) { "Diagnostic path must be absolute" }
			return SameFrameDiagnostics(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), resetWindow = resetWindow)
		}
		internal fun configuredPath(property: String?, environment: String?): String? = property ?: environment
		fun fromSystemProperty(): SameFrameDiagnostics? = configuredPath(
			System.getProperty("monaka.diagnostics.frames.path"), System.getenv("MONAKA_DIAGNOSTICS_FRAMES_PATH"),
		)?.let {
			val profile = configuredPath(System.getProperty("monaka.diagnostics.frames.profile"), System.getenv("MONAKA_DIAGNOSTICS_FRAMES_PROFILE")) ?: "detailed"
			require(profile in setOf("detailed", "full_reset_window")) { "Unknown frame diagnostic profile" }
			open(Path.of(it), resetWindow = profile == "full_reset_window")
		}

		/** Bind only the existing final output adapter; the input/feeder bridge never opens this file. */
		@JvmStatic
		fun outputForBridge(bridge: String, pipe: String): SameFrameDiagnostics? = outputForBridge(
			bridge, pipe, System.getProperty("monaka.diagnostics.output.path"), System.getenv("MONAKA_DIAGNOSTICS_OUTPUT_PATH"),
		)
		internal fun outputForBridge(bridge: String, pipe: String, property: String?, environment: String?): SameFrameDiagnostics? {
			if (bridge != "steamvr" || pipe != "\\\\.\\pipe\\SlimeVRDriver") return null
			return runCatching { configuredPath(property, environment)?.let { open(Path.of(it)) } }.getOrNull()
		}
	}
}
