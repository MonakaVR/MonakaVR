package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.tracking.MonakaResolvedTickSnapshot
import dev.monaka.tracking.MonakaRuntime
import dev.monaka.tracking.MonakaSolverCommitResult
import dev.monaka.tracking.ResolvedTrackingPose
import dev.slimevr.tracking.processor.BoneType
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
 * All maps contain detached scalar/list snapshots captured on the server thread.
 */
class SameFrameDiagnostics internal constructor(
	private val output: OutputStream,
	capacity: Int = 128,
	private val maxFrames: Long = 4096,
	private val maxBytes: Long = 16 * 1024 * 1024,
) : AutoCloseable {
	init {
		require(capacity in 1..1024)
		require(maxFrames in 1..65536)
		require(maxBytes in 1024..64 * 1024 * 1024)
	}
	private val queue = ArrayBlockingQueue<Map<String, Any?>>(capacity)
	private val admissionLock = Any()
	private val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
	private val admitted = AtomicLong()
	val dropped = AtomicLong()
	val written = AtomicLong()
	@Volatile private var closed = false
	@Volatile var failure: String? = null
		private set
	val accepting get() = !closed && admitted.get() < maxFrames
	private val worker = Thread({ writeLoop() }, "monaka-frame-diagnostics").also { it.isDaemon = true; it.start() }

	internal fun identity(value: String?): String? = value?.let {
		val digest = MessageDigest.getInstance("SHA-256")
		digest.update(salt)
		HexFormat.of().formatHex(digest.digest(it.toByteArray(Charsets.UTF_8)))
	}
	internal fun offer(record: Map<String, Any?>): Boolean = synchronized(admissionLock) {
		if (!accepting) { dropped.incrementAndGet(); return false }
		if (!queue.offer(record)) { dropped.incrementAndGet(); return false }
		admitted.incrementAndGet()
		true
	}
	private fun abandonPending() = synchronized(admissionLock) {
		closed = true
		dropped.addAndGet(1L + queue.size)
		queue.clear()
	}
	private fun writeLoop() {
		try {
			output.use { stream ->
				val mapper = ObjectMapper()
				var bytes = 0L
				while (!closed || queue.isNotEmpty()) {
					val next = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
					val encoded = mapper.writeValueAsBytes(next)
					if (encoded.size > 256 * 1024 || bytes + encoded.size + 1 > maxBytes) {
						abandonPending()
						break
					}
					stream.write(encoded)
					stream.write(10)
					bytes += encoded.size + 1
					written.incrementAndGet()
				}
				stream.flush()
			}
		} catch (e: Exception) {
			failure = e.javaClass.simpleName // Never leak path/identity from exception messages.
			abandonPending()
		} finally { close() }
	}
	/** No join on the tracking/server thread. A permanently blocked OS write is daemon-only. */
	override fun close() { synchronized(admissionLock) { closed = true } }
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
	): Map<String, Any?> {
		val selected = tick.assignment.targets.values.flatMap { listOfNotNull(it.mainTracker?.observationId, it.rotationFallbackTracker?.observationId) }.toSet()
		return linkedMapOf(
			"schema" to "monaka.same_frame.v1",
			"tick" to tick.tickSequence.toString(), "now_ns" to tick.nowNanos.toString(),
			"clock_domain" to "consumer_monotonic", "assignment_generation" to tick.assignment.generation.toString(),
			"paused" to tick.paused, "writeback_owner" to commit.owner.name,
			"writeback_attempted" to commit.writebackAttempted, "writeback_tick" to commit.tickSequence.toString(),
			"position_correction" to if (positionCorrection == null) "INPUTS_UNAVAILABLE_OR_NO_PAIR" else "NORMAL_RUNTIME",
			"position_correction_detail" to positionCorrection?.let { p -> mapOf(
				"hmd_available" to p.hmdAvailable, "geometry" to p.geometry,
				"prediction" to vector(p.prediction), "measured_teacher" to vector(p.measuredTeacher),
				"correction_world" to vector(p.correctionWorld), "applied_position" to vector(p.appliedPosition),
				"solver_position" to vector(p.solverPosition), "solver_position_reference" to p.solverPositionReference,
				"learning" to p.learning, "continuity" to p.continuity) },
			"body_rotation_guidance" to runtime.bodyRotationResults.map { (target, r) -> mapOf(
				"target" to target.name, "ready" to r.state.ready, "phase" to r.state.phase.name,
				"learning" to r.learning.name, "learning_reason" to r.learningReason.name,
				"correction_xyzw" to quaternion(r.state.correction),
				"fallback_xyzw" to quaternion(r.fallbackCandidate?.orientation)) },
			"observations" to runtime.pipeline.observations(tick.nowNanos, tick.assignment).filter { it.sourceId in selected }.map { o ->
				mapOf("identity" to identity(o.sourceId), "target" to o.target.name, "modality" to o.modality.name,
					"position" to vector(o.position), "rotation_xyzw" to quaternion(o.rotation),
					"position_quality" to o.positionQuality.name, "rotation_quality" to o.rotationQuality.name,
					"sequence" to o.provenance?.sequence?.toString(), "sample_ns" to o.provenance?.sampleAtNanos?.toString(),
					"age_ns" to o.provenance?.sampleAtNanos?.let { (tick.nowNanos - it).toString() },
					"source_epoch" to identity(o.provenance?.sourceEpoch), "calibration_epoch" to identity(o.provenance?.calibrationEpoch))
			},
			"accepted_mvip" to runtime.mtp.samples().filterKeys { it.observationId in selected }.map { (key, sample) ->
				val p = sample.pose
				mapOf("identity" to identity(key.observationId), "publisher" to identity(p.publisher_id),
					"source" to identity(p.source_id), "tracker" to identity(p.tracker_id), "session" to identity(p.session_id),
					"clock" to identity(p.clock_id), "sequence" to p.sequence.toString(), "remote_timestamp_ns" to p.timestamp_ns.toString(),
					"remote_sent_at_ns" to p.sent_at_ns.toString(), "local_sample_ns" to sample.sampleTime.toString(),
					"mapping_revision" to p.mapping_revision.toString(), "space_id" to identity(p.coordinate_space.id),
					"space_revision" to p.coordinate_space.revision.toString(), "convention" to p.coordinate_space.convention,
					"input_source" to identity(p.input.source_id), "input_device" to identity(p.input.device_id),
					"input_sequence" to p.input.sequence.toString(), "orientation_evidence" to p.input.orientation_evidence,
					"timestamp_kind" to p.timestamp_kind,
					"input_session" to identity(p.input.session_id), "modality" to p.modality,
					"validity" to mapOf("position" to p.validity.position, "orientation" to p.validity.orientation))
			},
			"resolved" to poses.values.map { p -> mapOf("target" to p.target.name,
				"position_owner" to identity(p.positionOwner), "rotation_owner" to identity(p.rotationOwner),
				"position" to vector(p.position?.value), "rotation_xyzw" to quaternion(p.rotation?.value)) },
			"ik_targets_before" to targets(skeleton), "bones_before" to bones(skeleton),
		)
	}
	internal fun after(before: Map<String, Any?>, skeleton: HumanSkeleton, direct: Iterable<Tracker>, postIk: Boolean) {
		offer(before + mapOf(
			"post_ik_hook" to postIk, "solver_enabled" to skeleton.ikSolver.enabled,
			"ik_targets_after" to targets(skeleton), "bones_after" to bones(skeleton),
			"computed_trackers" to skeleton.humanPoseManager.computedTrackers.map { tracker(it, false) },
			"direct_outputs" to direct.map { tracker(it, true) },
			"output_boundary" to "PRE_BRIDGE_DATA_WRITE", "steamvr_observed" to false,
		))
	}
	private fun tracker(t: Tracker, direct: Boolean): Map<String, Any?> {
		val pose = t.monakaOutputPose
		return mapOf("identity" to identity(t.name), "target" to t.trackerPosition?.name, "status" to t.status.name,
			"position" to vector(if (direct) pose?.position?.value else t.position.takeIf { t.hasPosition }),
			"rotation_xyzw" to quaternion(if (direct) pose?.rotation?.value else t.getRotation().takeIf { t.hasRotation }),
			"semantics" to if (direct) "nullable_output_components" else "computed_candidate_not_physical_teacher")
	}
	private fun targets(s: HumanSkeleton) = s.ikSolver.diagnosticPositionTargets().map {
		mapOf("identity" to identity(it.trackerIdentity), "target" to it.target?.name, "position" to vector(it.position))
	}
	private fun bones(s: HumanSkeleton) = BoneType.values().map { type ->
		val b = s.getBone(type)
		mapOf("bone" to type.name, "head" to vector(b.getPosition()), "tail" to vector(b.getTailPosition()),
			"rotation_xyzw" to quaternion(b.getGlobalRotation()), "length" to finite(b.length))
	}
	companion object {
		internal fun finite(v: Float): Float? = v.takeIf { it.isFinite() }
		internal fun vector(v: Vector3?): List<Float?>? = v?.let { listOf(finite(it.x), finite(it.y), finite(it.z)) }
		internal fun quaternion(q: Quaternion?): List<Float?>? = q?.let { listOf(finite(it.x), finite(it.y), finite(it.z), finite(it.w)) }
		fun open(path: Path): SameFrameDiagnostics {
			require(path.isAbsolute) { "Diagnostic path must be absolute" }
			return SameFrameDiagnostics(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
		}
		fun fromSystemProperty(): SameFrameDiagnostics? = System.getProperty("monaka.diagnostics.frames.path")?.let { open(Path.of(it)) }
	}
}
