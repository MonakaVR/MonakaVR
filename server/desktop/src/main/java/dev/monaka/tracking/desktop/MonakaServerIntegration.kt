package dev.monaka.tracking.desktop

import dev.monaka.tracking.*
import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.trackers.Tracker

/** Lifecycle methods run on the server thread, or before start / after join. */
class MonakaServerIntegration private constructor(
	val runtime: MonakaRuntime,
	val receiver: MtpUdpReceiver,
	private val writeback: ConstraintIkWriteback,
	private val skeleton: HumanSkeleton,
	private val registerBeforePose: (Runnable?) -> Unit,
	private val onFailure: (Exception) -> Unit,
) : AutoCloseable {
	private var closed = false
	private var transportReported = false
	fun tick() {
		if (closed) return
		try {
			if (receiver.failure != null && !transportReported) {
				runtime.inbox.clear(); runtime.mtp.invalidateSamples(); runtime.runner.remove("mtp")
				transportReported = true; onFailure(receiver.failure!!)
			}
			val constraints = runtime.tick(skeleton.getPauseTracking())
			writeback.apply(constraints, runtime.assignments.snapshot(), runtime.mtp.historyGeneration)
		} catch (e: Exception) { close(); onFailure(e) }
	}
	override fun close() {
		if (closed) return
		closed = true; registerBeforePose(null)
		try { receiver.close() } finally { runtime.close(); writeback.close() }
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
		): MonakaServerIntegration? {
			if (!enabled) return null
			val config = configuration()
			val runtime = MonakaRuntime(trackers, config.space, config.assignments, clock = clock, timeoutNanos = config.timeoutNanos)
			val receiver = try { MtpUdpReceiver(runtime.inbox, clock, config.port) } catch (e: Exception) { runtime.close(); throw e }
			val writeback = try { ConstraintIkWriteback(skeleton) } catch (e: Exception) { receiver.close(); runtime.close(); throw e }
			return MonakaServerIntegration(runtime, receiver, writeback, skeleton, registerBeforePose, onFailure).also {
				registerBeforePose(Runnable(it::tick))
			}
		}
	}
}
