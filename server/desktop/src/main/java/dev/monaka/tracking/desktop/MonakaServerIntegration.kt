package dev.monaka.tracking.desktop

import dev.monaka.tracking.*
import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition

/** Lifecycle methods run on the server thread, or before start / after join. */
class MonakaServerIntegration private constructor(
	val runtime: MonakaRuntime,
	val receiver: MtpUdpReceiver,
	private val solverComposition: MonakaSolverComposition,
	val directOutput: DirectConstraintOutput,
	private val skeleton: HumanSkeleton,
	private val registerBeforePose: (Runnable?) -> Unit,
	private val onFailure: (Exception) -> Unit,
	private val registerAfterPose: ((Runnable?) -> Unit)?,
	private val backgroundIk: BackgroundIkPoseReader,
	private val tuning: ContinuityTuning,
	private val onTransition: (OutputTransition) -> Unit,
	val rotationCorrection: HipRotationCorrection?,
	private val diagnostics: HybridTrackingDiagnosticRecorder?,
) : AutoCloseable {
	private var closed = false
	private var transportReported = false
	private val outputAssignments = runtime.assignments.snapshot().targets.filterValues { it.outputMode != OutputMode.IK }
	private val controllers = outputAssignments.mapValues { (target, relation) ->
		OutputContinuityController(target, relation.continuity, tuning, onTransition)
	}
	private data class PendingPoseFrame(
		val tick: MonakaResolvedTickSnapshot,
		val poses: Map<TrackerPosition, ResolvedTrackingPose>,
		val main: PoseObservation?, val imu: PoseObservation?, val rawHip: ResolvedTrackingPose?,
		val mainSource: String?, val imuSource: String?,
	)
	private var pendingFrame: PendingPoseFrame? = null
	val positionCorrectionGate get() = solverComposition.positionCorrectionGate
	val lastDiagnosticSnapshot: HybridTrackingDiagnosticSnapshot? get() = diagnostics?.latest
	fun tick() {
		if (closed) return
		try {
			check(pendingFrame == null) { "Previous post-IK frame has not been consumed" }
			if (receiver.failure != null && !transportReported) {
				runtime.inbox.clear(); runtime.mtp.invalidateSamples(); runtime.runner.remove("mtp")
				transportReported = true; onFailure(receiver.failure!!)
			}
			val tick = runtime.tickSnapshot(skeleton.getPauseTracking())
			val assignment = tick.assignment
			check(assignment.targets.filterValues { it.outputMode != OutputMode.IK } == outputAssignments) {
				"Changing visible output assignment/strategy requires a server restart"
			}
			val resolvedRaw = runtime.resolvedTrackingPoses(tick)
			val hip = assignment.targets[TrackerPosition.HIP]
			val observations = if (rotationCorrection == null && diagnostics == null) emptyMap() else
				runtime.pipeline.observations(tick.nowNanos, tick.assignment).associateBy { it.sourceId }
			val main = hip?.mainTracker?.observationId?.let(observations::get)
			val imu = hip?.rotationFallbackTracker?.observationId?.let(observations::get)
			// Raw teacher observations remain separate from Resolver ownership.
			val corrected = if (tick.paused) null else rotationCorrection?.update(main, imu,
				resolvedRaw[TrackerPosition.HIP]?.rotationOwner, tick.nowNanos, assignment.generation, runtime.expectedSpace)
			val correctedRotation = corrected?.rotation
			val resolvedHip = resolvedRaw[TrackerPosition.HIP]
			// One derived component fans out to IK and visible continuity. Never alter Main.
			val resolved = if (correctedRotation != null &&
				hip?.rotationFallbackTracker?.observationId == correctedRotation.sourceId &&
				resolvedHip?.rotationOwner == correctedRotation.sourceId)
				resolvedRaw + (TrackerPosition.HIP to resolvedHip.copy(rotation = correctedRotation))
			else resolvedRaw
			val ik = resolved.mapValues { it.value.ikConstraint() }
			check(solverComposition.commitGeneric(tick, ik, runtime.mtp.historyGeneration) is MonakaSolverCommitResult.GenericCommitted)
			pendingFrame = PendingPoseFrame(tick, resolved, main, imu, resolvedRaw[TrackerPosition.HIP],
				hip?.mainTracker?.observationId, hip?.rotationFallbackTracker?.observationId)
			if (registerAfterPose == null) finishPoseUpdate() else directOutput.applyPoses(emptyMap())
		} catch (e: Exception) { close(); onFailure(e) }
	}

	/** Server thread, after HumanPoseManager.update and before any bridge dataWrite. */
	fun finishPoseUpdate() {
		if (closed) return
		val pending = pendingFrame ?: return
		pendingFrame = null
		val poses = pending.poses
		try {
			val now = pending.tick.nowNanos
			val output = controllers.mapValues { (target, controller) ->
				val background = if (registerAfterPose != null && outputAssignments.getValue(target).useAsIkConstraint)
					backgroundIk.read(target, runtime.expectedSpace, now)
				else BackgroundIkResult(null, "background_not_participating_or_no_post_ik_hook")
				controller.update(poses.getValue(target), background, now, pending.tick.paused)
			}
			directOutput.applyPoses(output, pending.tick.paused)
			if (diagnostics != null) {
				val main = pending.main; val imu = pending.imu; val raw = pending.rawHip
				val correction = rotationCorrection
				val transition = controllers[TrackerPosition.HIP]?.lastTransition
				val visible = output[TrackerPosition.HIP]
				val paused = pending.tick.paused
				fun age(sample: PoseObservation?) = sample?.provenance?.sampleAtNanos?.let { now - it }
				diagnostics.record(HybridTrackingDiagnosticSnapshot(
					main = raw?.main ?: MainSampleState.from(main), mainSource = pending.mainSource,
					mainSequence = main?.provenance?.sequence, mainAgeNanos = age(main),
					imuSource = pending.imuSource, imuProvenanceAvailable = imu?.provenance != null,
					imuSequence = imu?.provenance?.sequence, imuAgeNanos = age(imu),
					imuFresh = correction != null && !paused && imu?.rotationQuality?.usable == true,
					imuRotationUsable = imu?.rotationQuality?.usable == true,
					imuEpochCompatible = correction?.imuEpochCompatible,
					resolverPositionOwner = raw?.positionOwner, resolverRotationOwner = raw?.rotationOwner,
					correctionEnabled = correction != null, correctionState = correction?.state,
					correctionReady = correction?.ready == true, correctionAgeNanos = correction?.correctionAgeNanos(now),
					correctionResidualRadians = correction?.residualRadians,
					learningDecision = if (paused) null else correction?.learningDecision,
					learningReason = if (paused) "paused" else correction?.learningReason,
					applicationDecision = if (paused) null else correction?.applicationDecision,
					applicationReason = if (paused) "paused" else correction?.applicationReason,
					continuityState = controllers[TrackerPosition.HIP]?.state,
					backgroundAvailable = transition?.backgroundAvailable,
					visiblePositionSource = visible?.positionSource, visibleRotationOwner = visible?.rotationOwner,
					transitionReason = transition?.reason,
				))
			}
		} catch (e: Exception) { close(); onFailure(e) }
	}
	override fun close() {
		if (closed) return
		closed = true; pendingFrame = null
		try { registerBeforePose(null) } finally {
			try { registerAfterPose?.invoke(null) } finally {
				try { receiver.close() } finally {
					try { directOutput.close() } finally {
						try { runtime.close() } finally { solverComposition.close() }
					}
				}
			}
		}
	}
	companion object {
		fun startIfEnabled(
			enabled: Boolean,
			configuration: () -> MonakaConfiguration,
			trackers: () -> Iterable<Tracker>,
			skeleton: HumanSkeleton,
			registerBeforePose: (Runnable?) -> Unit,
			onFailure: (Exception) -> Unit = {},
			clock: () -> Long = MonakaRuntime.monotonicClock(),
			configureDirectOutputs: (List<Tracker>) -> Unit = { require(it.isEmpty()) { "Direct output requires a compatible SteamVR bridge" } },
			nextTrackerId: () -> Int = dev.slimevr.VRServer::getNextLocalTrackerId,
			registerAfterPose: ((Runnable?) -> Unit)? = null,
			onTransition: (OutputTransition) -> Unit = {},
			onDiagnostic: (HybridTrackingDiagnosticEvent) -> Unit = {},
		): MonakaServerIntegration? {
			if (!enabled) return null
			val config = configuration()
			require(config.assignments.snapshot().targets.values.none { it.outputMode == OutputMode.HYBRID } || registerAfterPose != null) {
				"Hybrid output requires the post-IK server hook"
			}
			val runtime = MonakaRuntime(trackers, config.space, config.assignments, clock = clock, timeoutNanos = config.timeoutNanos,
				maxImuSampleAgeNanos = config.rotationCorrection?.tuning?.maxImuSampleAgeNanos)
			val receiver = try { MtpUdpReceiver(runtime.inbox, clock, config.port) } catch (e: Exception) { runtime.close(); throw e }
			var composition: MonakaSolverComposition? = null
			var direct: DirectConstraintOutput? = null
			var integration: MonakaServerIntegration? = null
			try {
				val owner = MonakaSolverComposition(skeleton, config.positionCorrection, trackers,
					skeleton.humanPoseManager::currentHipBodyModelSnapshot).also { composition = it }
				val output = DirectConstraintOutput(config.assignments.snapshot(), config.space, nextTrackerId).also { direct = it }
				val background = BackgroundIkPoseReader(skeleton, config.backgroundIkSharedSpace?.let(BackgroundIkAlignment::confirmedSameSpace) ?: BackgroundIkAlignment.UNVERIFIED)
				configureDirectOutputs(output.trackers.values.toList())
				val diagnosticEnabled = config.rotationCorrection != null ||
					config.assignments.snapshot().targets.values.any { it.outputMode == OutputMode.HYBRID }
				val result = MonakaServerIntegration(runtime, receiver, owner, output, skeleton, registerBeforePose, onFailure, registerAfterPose, background,
					config.continuityTuning, onTransition, config.rotationCorrection?.let { HipRotationCorrection(it.frames, it.tuning) },
					if (diagnosticEnabled) HybridTrackingDiagnosticRecorder(onDiagnostic) else null).also { integration = it }
				registerBeforePose(Runnable(result::tick))
				registerAfterPose?.invoke(Runnable(result::finishPoseUpdate))
				return result
			} catch (e: Exception) {
				// Continue teardown even if a registration/output callback itself throws.
				fun cleanup(action: () -> Unit) { try { action() } catch (failure: Exception) { e.addSuppressed(failure) } }
				if (integration != null) cleanup { integration!!.close() } else {
					cleanup { receiver.close() }; cleanup { direct?.close() }
					cleanup { composition?.close() }; cleanup { runtime.close() }
				}
				throw e
			}
		}
	}
}
