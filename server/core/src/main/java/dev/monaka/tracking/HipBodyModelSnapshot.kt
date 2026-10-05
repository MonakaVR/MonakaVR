package dev.monaka.tracking

import dev.slimevr.tracking.processor.config.SkeletonConfigManager
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets

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
 * Live/attached configurations fail closed until a coherent runtime capture boundary exists.
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
