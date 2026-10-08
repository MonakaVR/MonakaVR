package dev.monaka.tracking

import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition

enum class ProductionPositionCorrectionGate { NOT_CONFIGURED, CONFIGURED_RAW_HMD_BLOCKED }
enum class MonakaSolverCommitOwner { GENERIC_EXISTING, POSITION_CORRECTION }
enum class MonakaSolverCommitRejection { CLOSED, DUPLICATE_TICK, TICK_SEQUENCE_ROLLBACK, INCOHERENT_TICK, PAUSED, NOT_CONFIGURED }

sealed interface MonakaSolverCommitResult {
	val tickSequence: Long
	val owner: MonakaSolverCommitOwner
	val writebackAttempted: Boolean
	data class GenericCommitted(override val tickSequence: Long) : MonakaSolverCommitResult {
		override val owner = MonakaSolverCommitOwner.GENERIC_EXISTING
		override val writebackAttempted = true
	}
	data class Rejected(override val tickSequence: Long, override val owner: MonakaSolverCommitOwner,
		val reason: MonakaSolverCommitRejection) : MonakaSolverCommitResult {
		override val writebackAttempted = false
	}
}

internal data class PositionCorrectionCommitted(override val tickSequence: Long,
	val result: PositionCorrectionRuntimeTickResult) : MonakaSolverCommitResult {
	override val owner = MonakaSolverCommitOwner.POSITION_CORRECTION
	override val writebackAttempted = result is PositionCorrectionRuntimeTickResult.Processed
}

/** Dropping this session drops both learner and continuity state. No state is exported for reuse. */
internal interface MonakaPositionCorrectionSession : AutoCloseable {
	fun process(tick: PositionCorrectionRuntimeTick): PositionCorrectionRuntimeTickResult
	override fun close() {}
}

/** Server-thread owner of one solver boundary. Production stays generic until Raw HMD admission
 * exists. Configuration is construction-time state; changing it requires rebuilding this owner.
 */
class MonakaSolverComposition internal constructor(
	private val writeback: ConstraintIkWriteback,
	val positionCorrectionGate: ProductionPositionCorrectionGate,
	private val sessionFactory: ((ConstraintIkWriteback) -> MonakaPositionCorrectionSession)? = null,
	@Suppress("unused") private val nonHmdAdapter: PositionCorrectionNonHmdProductionAdapter? = null,
) : AutoCloseable {
	constructor(skeleton: HumanSkeleton, positionCorrection: PositionCorrectionConfig?,
		trackers: () -> Iterable<Tracker>, bodyModelSource: () -> HipBodyModelSnapshotResult) :
		this(skeleton, prepare(positionCorrection, trackers, bodyModelSource))

	private constructor(skeleton: HumanSkeleton, prepared: Prepared) : this(ConstraintIkWriteback(skeleton),
		prepared.gate, prepared.factory, prepared.adapter)

	private var closed = false
	private var lastReservedTickSequence: Long? = null
	private var wasPaused = false
	private var session: MonakaPositionCorrectionSession? = null

	fun commitGeneric(tick: MonakaResolvedTickSnapshot, constraints: Map<TrackerPosition, EffectiveConstraint>,
		historyRevision: Long = 0): MonakaSolverCommitResult {
		reserve(tick, MonakaSolverCommitOwner.GENERIC_EXISTING)?.let { return it }
		// Keep the original adapter and numerical behavior, including paused skeleton semantics.
		writeback.apply(constraints, tick.assignment, historyRevision)
		return MonakaSolverCommitResult.GenericCommitted(tick.tickSequence)
	}

	/** Future/test only: caller supplies coherent 6G fixture semantics, never production HMD trust.
	 * Owner selection consumes this sequence even if process rejects, throws, or partially applies.
	 */
	internal fun commitPositionCorrection(productionTick: MonakaResolvedTickSnapshot,
		runtimeTick: PositionCorrectionRuntimeTick): MonakaSolverCommitResult {
		val owner = MonakaSolverCommitOwner.POSITION_CORRECTION
		preflight(productionTick, owner)?.let { return it }
		if (runtimeTick.tickSequence != productionTick.tickSequence || runtimeTick.nowNanos != productionTick.nowNanos ||
			runtimeTick.resolvedAtNanos != productionTick.resolvedAtNanos || runtimeTick.assignment != productionTick.assignment ||
			runtimeTick.resolvedConstraints != productionTick.constraints)
			return MonakaSolverCommitResult.Rejected(productionTick.tickSequence, owner, MonakaSolverCommitRejection.INCOHERENT_TICK)
		reserve(productionTick, owner)?.let { return it }
		if (productionTick.paused) return MonakaSolverCommitResult.Rejected(productionTick.tickSequence, owner, MonakaSolverCommitRejection.PAUSED)
		val factory = sessionFactory ?: return MonakaSolverCommitResult.Rejected(productionTick.tickSequence, owner, MonakaSolverCommitRejection.NOT_CONFIGURED)
		val current = session ?: factory(writeback).also { session = it }
		return PositionCorrectionCommitted(productionTick.tickSequence, current.process(runtimeTick))
	}

	private fun preflight(tick: MonakaResolvedTickSnapshot, owner: MonakaSolverCommitOwner): MonakaSolverCommitResult.Rejected? {
		val reason = when {
			closed -> MonakaSolverCommitRejection.CLOSED
			tick.tickSequence == lastReservedTickSequence -> MonakaSolverCommitRejection.DUPLICATE_TICK
			lastReservedTickSequence?.let { tick.tickSequence < it } == true -> MonakaSolverCommitRejection.TICK_SEQUENCE_ROLLBACK
			else -> return null
		}
		return MonakaSolverCommitResult.Rejected(tick.tickSequence, owner, reason)
	}

	private fun reserve(tick: MonakaResolvedTickSnapshot, owner: MonakaSolverCommitOwner): MonakaSolverCommitResult.Rejected? {
		preflight(tick, owner)?.let { return it }
		lastReservedTickSequence = tick.tickSequence
		val enteringPause = tick.paused && !wasPaused
		wasPaused = tick.paused
		if (enteringPause) dropSession()
		return null
	}

	private fun dropSession() {
		val old = session
		session = null
		old?.close()
	}

	override fun close() {
		if (closed) return
		closed = true
		try { dropSession() } finally { writeback.close() }
	}

	private data class Prepared(val gate: ProductionPositionCorrectionGate,
		val adapter: PositionCorrectionNonHmdProductionAdapter?,
		val factory: ((ConstraintIkWriteback) -> MonakaPositionCorrectionSession)?)
	companion object {
		private fun prepare(config: PositionCorrectionConfig?, trackers: () -> Iterable<Tracker>,
			body: () -> HipBodyModelSnapshotResult): Prepared {
			val foundation = config?.toFoundation() ?: return Prepared(ProductionPositionCorrectionGate.NOT_CONFIGURED, null, null)
			// Materialize without capturing live providers or constructing an active session.
			return Prepared(ProductionPositionCorrectionGate.CONFIGURED_RAW_HMD_BLOCKED,
				PositionCorrectionNonHmdProductionAdapter(foundation, trackers, body)) { shared ->
				val orchestrator = PositionCorrectionRuntimeOrchestrator(foundation.predictorPolicy, foundation.pairingPolicy,
					foundation.learningTuning, foundation.reacquisitionTuning, shared)
				object : MonakaPositionCorrectionSession {
					override fun process(tick: PositionCorrectionRuntimeTick) = orchestrator.process(tick)
				}
			}
		}
	}
}
