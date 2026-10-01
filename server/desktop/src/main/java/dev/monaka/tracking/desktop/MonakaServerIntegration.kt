package dev.monaka.tracking.desktop

import dev.monaka.tracking.*
import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition

/** Lifecycle methods run on the server thread, or before start / after join. */
class MonakaServerIntegration private constructor(
	val runtime: MonakaRuntime,
	val receiver: MtpUdpReceiver,
	private val writeback: ConstraintIkWriteback,
	val directOutput: DirectConstraintOutput,
	private val skeleton: HumanSkeleton,
	private val registerBeforePose: (Runnable?) -> Unit,
	private val onFailure: (Exception) -> Unit,
	private val registerAfterPose: ((Runnable?) -> Unit)?,
	private val backgroundIk: BackgroundIkPoseReader,
	private val tuning: ContinuityTuning,
	private val onTransition: (OutputTransition) -> Unit,
	val rotationCorrection: HipRotationCorrection?,
) : AutoCloseable {
	private var closed = false
	private var transportReported = false
	private val outputAssignments = runtime.assignments.snapshot().targets.filterValues { it.outputMode != OutputMode.IK }
	private val controllers = outputAssignments.mapValues { (target, relation) ->
		OutputContinuityController(target, relation.continuity, tuning, onTransition)
	}
	private var pendingPoses: Map<dev.slimevr.tracking.trackers.TrackerPosition, ResolvedTrackingPose>? = null
	fun tick() {
		if (closed) return
		try {
			check(runtime.assignments.snapshot().targets.filterValues { it.outputMode != OutputMode.IK } == outputAssignments) {
				"Changing visible output assignment/strategy requires a server restart"
			}
			if (receiver.failure != null && !transportReported) {
				runtime.inbox.clear(); runtime.mtp.invalidateSamples(); runtime.runner.remove("mtp")
				transportReported = true; onFailure(receiver.failure!!)
			}
			val constraints = runtime.tick(skeleton.getPauseTracking())
			val resolvedRaw = runtime.resolvedTrackingPoses(constraints)
			val assignment = runtime.assignments.snapshot()
			val hip = assignment.targets[TrackerPosition.HIP]
			val observations = if (rotationCorrection == null) emptyMap() else
				runtime.pipeline.observations(runtime.lastTickNanos).associateBy { it.sourceId }
			val main = hip?.mainTracker?.observationId?.let(observations::get)
			val imu = hip?.rotationFallbackTracker?.observationId?.let(observations::get)
			// Raw teacher observations remain separate from Resolver ownership.
			val corrected = if (skeleton.getPauseTracking()) null else rotationCorrection?.update(main, imu,
				resolvedRaw[TrackerPosition.HIP]?.rotationOwner, runtime.lastTickNanos, assignment.generation, runtime.expectedSpace)
			val correctedRotation = corrected?.rotation
			val resolvedHip = resolvedRaw[TrackerPosition.HIP]
			// One derived component fans out to IK and visible continuity. Never alter Main.
			val resolved = if (correctedRotation != null &&
				hip?.rotationFallbackTracker?.observationId == correctedRotation.sourceId &&
				resolvedHip?.rotationOwner == correctedRotation.sourceId)
				resolvedRaw + (TrackerPosition.HIP to resolvedHip.copy(rotation = correctedRotation))
			else resolvedRaw
			val ik = resolved.mapValues { it.value.ikConstraint() }
			writeback.apply(ik, assignment, runtime.mtp.historyGeneration)
			pendingPoses = resolved
			if (registerAfterPose == null) finishPoseUpdate() else directOutput.applyPoses(emptyMap())
		} catch (e: Exception) { close(); onFailure(e) }
	}

	/** Server thread, after HumanPoseManager.update and before any bridge dataWrite. */
	fun finishPoseUpdate() {
		if (closed) return
		val poses = pendingPoses ?: return
		pendingPoses = null
		try {
			val now = runtime.lastTickNanos
			val output = controllers.mapValues { (target, controller) ->
				val background = if (registerAfterPose != null && outputAssignments.getValue(target).useAsIkConstraint)
					backgroundIk.read(target, runtime.expectedSpace, now)
				else BackgroundIkResult(null, "background_not_participating_or_no_post_ik_hook")
				controller.update(poses.getValue(target), background, now, skeleton.getPauseTracking())
			}
			directOutput.applyPoses(output, skeleton.getPauseTracking())
		} catch (e: Exception) { close(); onFailure(e) }
	}
	override fun close() {
		if (closed) return
		closed = true; pendingPoses = null; registerBeforePose(null); registerAfterPose?.invoke(null)
		try { receiver.close() } finally { directOutput.close(); runtime.close(); writeback.close() }
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
		): MonakaServerIntegration? {
			if (!enabled) return null
			val config = configuration()
			require(config.assignments.snapshot().targets.values.none { it.outputMode == OutputMode.HYBRID } || registerAfterPose != null) {
				"Hybrid output requires the post-IK server hook"
			}
			val runtime = MonakaRuntime(trackers, config.space, config.assignments, clock = clock, timeoutNanos = config.timeoutNanos,
				maxImuSampleAgeNanos = config.rotationCorrection?.tuning?.maxImuSampleAgeNanos)
			val receiver = try { MtpUdpReceiver(runtime.inbox, clock, config.port) } catch (e: Exception) { runtime.close(); throw e }
			val writeback = try { ConstraintIkWriteback(skeleton) } catch (e: Exception) { receiver.close(); runtime.close(); throw e }
			val direct = DirectConstraintOutput(config.assignments.snapshot(), config.space, nextTrackerId)
			val background = BackgroundIkPoseReader(skeleton, config.backgroundIkSharedSpace?.let(BackgroundIkAlignment::confirmedSameSpace) ?: BackgroundIkAlignment.UNVERIFIED)
			try { configureDirectOutputs(direct.trackers.values.toList()) } catch (e: Exception) {
				direct.close(); writeback.close(); receiver.close(); runtime.close(); throw e
			}
			return MonakaServerIntegration(runtime, receiver, writeback, direct, skeleton, registerBeforePose, onFailure, registerAfterPose, background,
				config.continuityTuning, onTransition, config.rotationCorrection?.let { HipRotationCorrection(it.frames, it.tuning) }).also {
				registerBeforePose(Runnable(it::tick))
				registerAfterPose?.invoke(Runnable(it::finishPoseUpdate))
			}
		}
	}
}
