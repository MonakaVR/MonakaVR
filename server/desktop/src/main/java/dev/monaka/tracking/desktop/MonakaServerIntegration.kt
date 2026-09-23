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
) : AutoCloseable {
	private var closed = false
	private var transportReported = false
	fun tick() {
		if (closed) return
		try {
			check(runtime.assignments.snapshot().targets.filterValues { it.outputMode == OutputMode.DIRECT }.keys == directOutput.trackers.keys) {
				"Changing Direct output roles/modes requires a server restart"
			}
			if (receiver.failure != null && !transportReported) {
				runtime.inbox.clear(); runtime.mtp.invalidateSamples(); runtime.runner.remove("mtp")
				transportReported = true; onFailure(receiver.failure!!)
			}
			val constraints = runtime.tick(skeleton.getPauseTracking())
			writeback.apply(constraints, runtime.assignments.snapshot(), runtime.mtp.historyGeneration)
			directOutput.apply(constraints, skeleton.getPauseTracking())
		} catch (e: Exception) { close(); onFailure(e) }
	}
	override fun close() {
		if (closed) return
		closed = true; registerBeforePose(null)
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
		): MonakaServerIntegration? {
			if (!enabled) return null
			val config = configuration()
			val runtime = MonakaRuntime(trackers, config.space, config.assignments, clock = clock, timeoutNanos = config.timeoutNanos)
			val receiver = try { MtpUdpReceiver(runtime.inbox, clock, config.port) } catch (e: Exception) { runtime.close(); throw e }
			val writeback = try { ConstraintIkWriteback(skeleton) } catch (e: Exception) { receiver.close(); runtime.close(); throw e }
			val direct = DirectConstraintOutput(config.assignments.snapshot(), nextTrackerId)
			try { configureDirectOutputs(direct.trackers.values.toList()) } catch (e: Exception) {
				direct.close(); writeback.close(); receiver.close(); runtime.close(); throw e
			}
			return MonakaServerIntegration(runtime, receiver, writeback, direct, skeleton, registerBeforePose, onFailure).also {
				registerBeforePose(Runnable(it::tick))
			}
		}
	}
}
