package dev.slimevr.tracking.processor

/** Server-owner diagnostic state only. No tracking decision reads this timeline. */
class FullResetDiagnostics(private val clock: () -> Long = System::nanoTime) {
	data class Snapshot(
		val generation: Long = 0,
		val phase: String? = null,
		val startedSystemNanos: Long? = null,
		val completedSystemNanos: Long? = null,
	)
	var snapshot = Snapshot()
		private set
	private var observer: ((Snapshot) -> Unit)? = null

	fun observe(observer: ((Snapshot) -> Unit)?) { this.observer = observer }

	fun started(): Snapshot {
		val next = Snapshot(snapshot.generation + 1, "STARTED", clock())
		snapshot = next
		runCatching { observer?.invoke(next) }
		return next
	}

	fun finished(started: Snapshot, completed: Boolean) {
		val next = started.copy(phase = if (completed) "COMPLETED" else "FAILED", completedSystemNanos = clock())
		snapshot = next
		runCatching { observer?.invoke(next) }
	}
}
