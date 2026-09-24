package dev.monaka.tracking.desktop

import dev.monaka.tracking.*
import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.trackers.Tracker

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
			val resolved = runtime.resolvedTrackingPoses(constraints)
			// The same selected values feed both consumers; this remains the 6DoF correction seam.
			writeback.apply(resolved.mapValues { it.value.ikConstraint() }, runtime.assignments.snapshot(), runtime.mtp.historyGeneration)
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
			val runtime = MonakaRuntime(trackers, config.space, config.assignments, clock = clock, timeoutNanos = config.timeoutNanos)
			val receiver = try { MtpUdpReceiver(runtime.inbox, clock, config.port) } catch (e: Exception) { runtime.close(); throw e }
			val writeback = try { ConstraintIkWriteback(skeleton) } catch (e: Exception) { receiver.close(); runtime.close(); throw e }
			val direct = DirectConstraintOutput(config.assignments.snapshot(), config.space, nextTrackerId)
			val background = BackgroundIkPoseReader(skeleton, config.backgroundIkSharedSpace?.let(BackgroundIkAlignment::confirmedSameSpace) ?: BackgroundIkAlignment.UNVERIFIED)
			try { configureDirectOutputs(direct.trackers.values.toList()) } catch (e: Exception) {
				direct.close(); writeback.close(); receiver.close(); runtime.close(); throw e
			}
			return MonakaServerIntegration(runtime, receiver, writeback, direct, skeleton, registerBeforePose, onFailure, registerAfterPose, background, config.continuityTuning, onTransition).also {
				registerBeforePose(Runnable(it::tick))
				registerAfterPose?.invoke(Runnable(it::finishPoseUpdate))
			}
		}
	}
}
