package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import kotlin.math.abs

/** Source-owned session and combined reference-space generation, not a transport lifetime. */
internal data class SourceSpaceAuthorityEpoch(
	val sourceId: String,
	val sourceSessionEpoch: String,
	val sourceSpaceId: String,
	val generation: Long,
) {
	init {
		require(sourceId.isNotBlank() && sourceSessionEpoch.isNotBlank() && sourceSpaceId.isNotBlank())
		require(generation >= 0)
	}
}

/** Opaque publication supplied by the future Bridge owner. No local mint/allocator. */
internal data class CommonWorldEpoch(val value: String) {
	init { require(value.isNotBlank()) }
}

internal data class CommonMappingCalibrationEpoch(val value: String) {
	init { require(value.isNotBlank()) }
}

/** Bridge Config uint32 content revision, independent of the destination frame revision. */
internal data class CommonMappingRevision(val value: Long) {
	init { require(value in 0..4294967295L) }
}

internal data class CommonWorldAuthority(
	val ownerId: String,
	val space: CoordinateSpace,
	val epoch: CommonWorldEpoch,
	val anchor: SourceSpaceAuthorityEpoch,
) {
	init {
		require(ownerId.isNotBlank() && space.id.isNotBlank())
		require(space.convention == "rh_y_up_neg_z_forward" && space.revision in 0..4294967295L)
	}
}

/** Hamilton active rotation followed by translation. ktmath P/Q are immutable values.
 * The Bridge config unit-norm-squared tolerance is 1e-5; never normalize or repair here.
 */
internal data class SourceToCommonRigidTransform(val rotation: Quaternion, val translation: Vector3) {
	init { require(commonFinite(translation) && commonUnit(rotation)) }
	// Signed zero is a numerical identity; quaternion sign also represents the same rotation.
	val isIdentity: Boolean get() = abs(rotation.w) == 1f && rotation.x == 0f && rotation.y == 0f &&
		rotation.z == 0f && translation.x == 0f && translation.y == 0f && translation.z == 0f
}

internal data class SourceToCommonMappingSnapshot(
	val ownerId: String,
	val sourceSpace: SourceSpaceAuthorityEpoch,
	val commonWorld: CommonWorldAuthority,
	val calibrationEpoch: CommonMappingCalibrationEpoch,
	val mappingRevision: CommonMappingRevision,
	val transform: SourceToCommonRigidTransform,
) {
	init { require(ownerId == commonWorld.ownerId) }

	companion object {
		/** M2 HMD anchor only. Generic snapshots can represent calibrated nonidentity transforms. */
		fun openXrAnchor(
			sourceSpace: SourceSpaceAuthorityEpoch,
			commonWorld: CommonWorldAuthority,
			calibrationEpoch: CommonMappingCalibrationEpoch,
			mappingRevision: CommonMappingRevision,
			transform: SourceToCommonRigidTransform,
		): SourceToCommonMappingSnapshot {
			require(sourceSpace == commonWorld.anchor && transform.isIdentity)
			return SourceToCommonMappingSnapshot(commonWorld.ownerId, sourceSpace, commonWorld,
				calibrationEpoch, mappingRevision, transform)
		}
	}
}

/** Live capabilities deliberately have reference equality and no copy method/public constructor. */
internal class CommonWorldAuthorityHandle internal constructor(val authority: CommonWorldAuthority)
internal class SourceToCommonMappingHandle internal constructor(
	val snapshot: SourceToCommonMappingSnapshot,
	val worldHandle: CommonWorldAuthorityHandle,
)

internal enum class CommonWorldRevocationReason {
	ANCHOR_AUTHORITY_LOST, SOURCE_SPACE_CHANGED, SOURCE_SESSION_CHANGED, EVENT_STREAM_LOST,
	EXPLICIT_WORLD_REPLACEMENT, MAPPING_CONFLICT, MAPPING_WITHDRAWN, CAPACITY_EXHAUSTED,
}

/** Immutable local diagnostic/order receipt, never an epoch or a wire message. */
internal data class CommonWorldAuthorityRevocation(
	val ownerId: String,
	val worldEpoch: CommonWorldEpoch,
	val reason: CommonWorldRevocationReason,
	val sequence: Long,
) {
	init { require(ownerId.isNotBlank() && sequence >= 0) }
}

internal data class TrustedHmdSourceObservationIdentity(
	val sourceSpace: SourceSpaceAuthorityEpoch,
	val observationId: Long,
) {
	init { require(observationId >= 0) }
}

/** Structural source value only; source validity, timing and reviewed ingress remain future gates. */
internal data class TrustedHmdSourcePoseSnapshot(
	val identity: TrustedHmdSourceObservationIdentity,
	val position: Vector3,
	val orientation: Quaternion,
) {
	init { require(commonFinite(position) && commonUnit(orientation)) }
}

internal data class CommonWorldMappedHmdPose(
	val source: TrustedHmdSourcePoseSnapshot,
	val mapping: SourceToCommonMappingSnapshot,
	val position: Vector3,
	val orientation: Quaternion,
)

/** Receiver-side state for future Bridge-owned authority publication.
 * MonakaVR does not mint Common World authority. Caller explicitly selects one owner.
 * Mutations and final acceptance linearize on this monitor; arithmetic runs outside it.
 * Tombstones/high-waters survive revocation and replacement, with no eviction on saturation.
 */
internal class CommonWorldAuthorityState(private val expectedOwnerId: String, private val capacity: Int = 128) {
	init { require(expectedOwnerId.isNotBlank() && capacity > 0) }
	private var world: CommonWorldAuthorityHandle? = null
	private var mapping: SourceToCommonMappingHandle? = null
	private var lastMapping: SourceToCommonMappingSnapshot? = null
	private var mappingHighWater: Long? = null
	private val worldRevisions = mutableMapOf<String, Long>()
	private val retiredWorlds = mutableSetOf<CommonWorldEpoch>()
	private val retiredCalibrations = mutableSetOf<CommonMappingCalibrationEpoch>()
	private var exhausted = false
	private var revocationSequence = 0L
	private var revocation: CommonWorldAuthorityRevocation? = null

	@Synchronized fun currentWorld(): CommonWorldAuthorityHandle? = world
	@Synchronized fun currentMapping(): SourceToCommonMappingHandle? = mapping
	@Synchronized fun lastRevocation(): CommonWorldAuthorityRevocation? = revocation
	@Synchronized fun isCurrent(handle: CommonWorldAuthorityHandle): Boolean = !exhausted && world === handle
	@Synchronized fun isCurrent(handle: SourceToCommonMappingHandle): Boolean =
		!exhausted && mapping === handle && world === handle.worldHandle

	@Synchronized fun establishWorld(authority: CommonWorldAuthority): CommonWorldAuthorityHandle? {
		if (exhausted) return null
		if (authority.ownerId != expectedOwnerId) { revokeWorld(CommonWorldRevocationReason.MAPPING_CONFLICT); return null }
		val old = world
		if (old?.authority == authority) return old
		if (old?.authority?.epoch == authority.epoch) {
			revokeWorld(CommonWorldRevocationReason.MAPPING_CONFLICT); return null
		}
		if (authority.epoch in retiredWorlds) return null
		val highest = worldRevisions[authority.space.id]
		if (highest != null && authority.space.revision <= highest) return null
		if (highest == null && worldRevisions.size >= capacity) { exhaust(); return null }
		if (old != null) revokeWorld(CommonWorldRevocationReason.EXPLICIT_WORLD_REPLACEMENT)
		if (exhausted) return null
		worldRevisions[authority.space.id] = authority.space.revision
		return CommonWorldAuthorityHandle(authority).also { world = it }
	}

	@Synchronized fun revokeWorld(reason: CommonWorldRevocationReason) {
		val old = world ?: return
		world = null
		retireMapping()
		if (exhausted) return
		if (retiredWorlds.size >= capacity && old.authority.epoch !in retiredWorlds) { exhaust(old.authority); return }
		retiredWorlds += old.authority.epoch
		recordRevocation(old.authority, reason)
	}

	@Synchronized fun publishMapping(snapshot: SourceToCommonMappingSnapshot): SourceToCommonMappingHandle? {
		val liveWorld = world ?: return null
		if (exhausted || snapshot.commonWorld != liveWorld.authority || snapshot.ownerId != expectedOwnerId) return null
		val old = mapping
		if (old?.snapshot == snapshot) return old
		val previous = lastMapping
		val revision = snapshot.mappingRevision.value
		val high = mappingHighWater
		if (high != null && revision < high) return null // stale traffic cannot destroy a good mapping
		if (high == revision) {
			if (previous != snapshot) revokeMapping(CommonWorldRevocationReason.MAPPING_CONFLICT)
			return null // even identical content cannot revive a withdrawn capability
		}
		if (snapshot.calibrationEpoch in retiredCalibrations) return null
		if (old != null && snapshot.sourceSpace != old.snapshot.sourceSpace) {
			revokeMapping(CommonWorldRevocationReason.SOURCE_SPACE_CHANGED); return null
		}
		if (old != null && snapshot.calibrationEpoch != old.snapshot.calibrationEpoch) retireMapping()
		if (exhausted) return null
		mappingHighWater = revision
		lastMapping = snapshot
		return SourceToCommonMappingHandle(snapshot, liveWorld).also { mapping = it }
	}

	@Synchronized fun revokeMapping(reason: CommonWorldRevocationReason) {
		val authority = world?.authority ?: return
		retireMapping()
		if (!exhausted) recordRevocation(authority, reason)
	}

	private fun retireMapping() {
		val old = mapping ?: return
		mapping = null
		val calibration = old.snapshot.calibrationEpoch
		if (calibration !in retiredCalibrations && retiredCalibrations.size >= capacity) { exhaust(); return }
		retiredCalibrations += calibration
	}

	private fun recordRevocation(authority: CommonWorldAuthority, reason: CommonWorldRevocationReason) {
		if (revocationSequence == Long.MAX_VALUE) { exhaust(); return }
		revocation = CommonWorldAuthorityRevocation(authority.ownerId, authority.epoch, reason, revocationSequence++)
	}

	private fun exhaust(authority: CommonWorldAuthority? = world?.authority ?: lastMapping?.commonWorld) {
		exhausted = true; world = null; mapping = null
		if (authority != null) revocation = CommonWorldAuthorityRevocation(authority.ownerId, authority.epoch,
			CommonWorldRevocationReason.CAPACITY_EXHAUSTED, revocationSequence)
	}

	/** Exact source -> one frozen snapshot -> output. Callback enables deterministic in-flight races.
	 * This returns a structural value, never RawHmdPoseInput/Strong Ready or a production registration.
	 */
	fun mapSourcePose(source: TrustedHmdSourcePoseSnapshot, afterCompute: () -> Unit = {}): CommonWorldMappedHmdPose? {
		val before = currentMapping() ?: return null
		if (source.identity.sourceSpace != before.snapshot.sourceSpace || !isCurrent(before)) return null
		val transform = before.snapshot.transform
		// Keep the exact bits on an identity path, including signed zero and source quaternion sign.
		val position = if (transform.isIdentity) source.position else transform.rotation.sandwich(source.position) + transform.translation
		// A negative identity quaternion still negates Q in the specified Hamilton product.
		val orientation = if (transform.isIdentity && transform.rotation.w == 1f) source.orientation
			else transform.rotation * source.orientation
		if (!commonFinite(position) || !commonUnit(orientation)) return null
		afterCompute()
		return synchronized(this) {
			if (currentMapping() !== before || !isCurrent(before)) null
			else CommonWorldMappedHmdPose(source, before.snapshot, position, orientation)
		}
	}
}

private fun commonFinite(p: Vector3) = p.x.isFinite() && p.y.isFinite() && p.z.isFinite()
private fun commonUnit(q: Quaternion): Boolean {
	if (!listOf(q.w, q.x, q.y, q.z).all(Float::isFinite)) return false
	val norm = q.w.toDouble() * q.w + q.x.toDouble() * q.x + q.y.toDouble() * q.y + q.z.toDouble() * q.z
	return abs(norm - 1.0) <= 1e-5
}
