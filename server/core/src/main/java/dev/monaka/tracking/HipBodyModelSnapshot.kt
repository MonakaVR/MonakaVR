package dev.monaka.tracking

import dev.slimevr.tracking.processor.config.SkeletonConfigManager
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import java.util.EnumMap

/** Configuration geometry only: HEAD shifts +Z; the other center-chain offsets extend -Y.
 * No tracker attachment, solved transform, source pose, or mutable runtime object is retained.
 */
class HipBodyModelSnapshot private constructor(
	val headShift: Float,
	val neckLength: Float,
	val upperChestLength: Float,
	val chestLength: Float,
	val waistLength: Float,
	val hipLength: Float,
	val identity: BodyModelIdentity,
) {
	companion object {
		const val MODEL_ID = "monaka:slimevr:hip-body-model:v1"
		const val EPOCH_SCHEMA = "hip-body-v1"

		fun create(
			headShift: Float,
			neckLength: Float,
			upperChestLength: Float,
			chestLength: Float,
			waistLength: Float,
			hipLength: Float,
		): HipBodyModelSnapshotResult {
			val fields = listOf(
				"headShift" to headShift,
				"neckLength" to neckLength,
				"upperChestLength" to upperChestLength,
				"chestLength" to chestLength,
				"waistLength" to waistLength,
				"hipLength" to hipLength,
			)
			val invalid = fields.firstOrNull { !it.second.isFinite() }
			if (invalid != null) return HipBodyModelSnapshotResult.Unavailable("nonfinite_${invalid.first}")
			// Fixed schema/order, lowercase 8-digit IEEE-754 raw hex. Signed zero is distinct.
			val epoch = EPOCH_SCHEMA + fields.joinToString(separator = "", prefix = ";") {
				"${it.first}=${it.second.toRawBits().toUInt().toString(16).padStart(8, '0')};"
			}
			return HipBodyModelSnapshotResult.Available(
				HipBodyModelSnapshot(headShift, neckLength, upperChestLength, chestLength,
					waistLength, hipLength, BodyModelIdentity(MODEL_ID, epoch)),
			)
		}
	}
}

sealed interface HipBodyModelSnapshotResult {
	data class Available(val snapshot: HipBodyModelSnapshot) : HipBodyModelSnapshotResult
	data class Unavailable(val reason: String) : HipBodyModelSnapshotResult
}

/** Dormant, offline-only adapter. The caller must exclusively own the detached configuration,
 * including all mutation, on its construction thread; it must not share it with RPC/AutoBone.
 * Thread checks enforce capture ownership, not global synchronization of existing setters.
 * Live readers use SkeletonConfigManager.currentHipBodyModelSnapshot() instead.
 */
object SlimeHipBodyModelSnapshotSource {
	fun captureOffline(config: SkeletonConfigManager): HipBodyModelSnapshotResult {
		val owner = config.offlineSnapshotOwnerThread
			?: return HipBodyModelSnapshotResult.Unavailable("offline_capture_requires_detached_config")
		if (owner !== Thread.currentThread()) {
			return HipBodyModelSnapshotResult.Unavailable("offline_capture_wrong_thread")
		}
		return HipBodyModelSnapshot.create(
			config.getOffset(SkeletonConfigOffsets.HEAD),
			config.getOffset(SkeletonConfigOffsets.NECK),
			config.getOffset(SkeletonConfigOffsets.UPPER_CHEST),
			config.getOffset(SkeletonConfigOffsets.CHEST),
			config.getOffset(SkeletonConfigOffsets.WAIST),
			config.getOffset(SkeletonConfigOffsets.HIP),
		)
	}
}

/** Committed configuration mirror, not a lock for legacy storage or skeleton callbacks.
 * Each outer logical operation records its requested effective offsets on its own thread.
 * Completed journals linearize under the commit lock; no mutable config is read at commit.
 */
internal class HipBodyModelPublication {
	private class Journal {
		val offsets = EnumMap<SkeletonConfigOffsets, Float>(SkeletonConfigOffsets::class.java)
		var failed = false
	}
	private data class Published(val sequence: Long, val result: HipBodyModelSnapshotResult)
	private val commitLock = Any()
	private val journal = ThreadLocal<Journal>()
	private val committed = EnumMap<SkeletonConfigOffsets, Float>(SkeletonConfigOffsets::class.java).also {
		for (offset in CENTER_CHAIN) it[offset] = offset.defaultValue
	}
	private var requiresCompleteRevalidation = false
	@Volatile private var published = Published(0, snapshot())

	fun current(): HipBodyModelSnapshotResult = published.result
	val sequence: Long get() = published.sequence

	fun mutate(action: () -> Unit) {
		val existing = journal.get()
		if (existing != null) {
			try {
				action()
			} catch (failure: Throwable) {
				existing.failed = true
				throw failure
			}
			return
		}
		val pending = Journal()
		journal.set(pending)
		var completed = false
		try {
			action()
			completed = true
		} finally {
			journal.remove()
			commit(pending, completed && !pending.failed)
		}
	}

	fun record(offset: SkeletonConfigOffsets, requested: Float?) {
		if (offset in CENTER_CHAIN) {
			checkNotNull(journal.get()).offsets[offset] = requested ?: offset.defaultValue
		}
	}

	fun recordDefaults() {
		for (offset in CENTER_CHAIN) record(offset, null)
	}

	private fun commit(pending: Journal, completed: Boolean) {
		if (pending.offsets.isEmpty()) return
		synchronized(commitLock) {
			committed.putAll(pending.offsets)
			if (!completed) requiresCompleteRevalidation = true
			else if (pending.offsets.keys.containsAll(CENTER_CHAIN)) requiresCompleteRevalidation = false
			val result = if (requiresCompleteRevalidation) {
				HipBodyModelSnapshotResult.Unavailable("offset_mutation_incomplete")
			} else snapshot()
			published = Published(published.sequence + 1, result)
		}
	}

	private fun snapshot(): HipBodyModelSnapshotResult = HipBodyModelSnapshot.create(
		committed.getValue(SkeletonConfigOffsets.HEAD),
		committed.getValue(SkeletonConfigOffsets.NECK),
		committed.getValue(SkeletonConfigOffsets.UPPER_CHEST),
		committed.getValue(SkeletonConfigOffsets.CHEST),
		committed.getValue(SkeletonConfigOffsets.WAIST),
		committed.getValue(SkeletonConfigOffsets.HIP),
	)

	companion object {
		private val CENTER_CHAIN = setOf(SkeletonConfigOffsets.HEAD, SkeletonConfigOffsets.NECK,
			SkeletonConfigOffsets.UPPER_CHEST, SkeletonConfigOffsets.CHEST,
			SkeletonConfigOffsets.WAIST, SkeletonConfigOffsets.HIP)
	}
}
