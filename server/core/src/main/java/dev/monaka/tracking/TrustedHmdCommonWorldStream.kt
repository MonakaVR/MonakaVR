package dev.monaka.tracking

import dev.monaka.protocol.v2.*
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** Structural dormant result; contains no production ingress capability or backend registration. */
internal data class TrustedHmdCommonCandidate(
	val pose: CommonWorldMappedHmdPose,
	val sourceLocateTimeNs: Long,
	val sourceTimeDomainId: String,
	val validity: HmdValidityEvidence,
	val receivedAtNanos: Long,
)

/** Caller-serialized ordered state for one explicitly authorized Bridge installation.
 * Healthy session replacement requires a full snapshot while retaining 5Z history. Loss/conflict
 * invalidates the long-lived foundation itself; that world can never be resurrected by reconnect.
 */
internal class TrustedHmdCommonWorldStream(private val expectedPublisherId: String, private val capacity: Int = 128) {
	init { require(expectedPublisherId.isNotBlank() && capacity > 0) }
	private val foundation = CommonWorldAuthorityState(expectedPublisherId, capacity, worldScopedMappingHighWater = true)
	private val sessions = mutableSetOf<String>() // includes current; no eviction
	private var session: String? = null
	private var clock: String? = null
	private var valid = false
	private var exhausted = false
	private var last: Envelope? = null
	private var lastSequence: Long? = null
	private var worldBootstrapped = false
	private var mappingBootstrapped = false
	private var initialMappingRejected = false
	private var lastWireMapping: CommonWorldMappingPublication? = null
	private var poseCandidate: TrustedHmdCommonCandidate? = null
	private data class SourceHistory(var space: SourceSpaceAuthorityEpoch, var retired: Boolean = false,
		var generationRetired: Boolean = false, var observation: TrustedHmdCommonPose? = null, var receivedAt: Long = 0,
		var lastMapped: SourceToCommonMappingSnapshot? = null)
	private val sources = mutableMapOf<Pair<String, String>, SourceHistory>()

	fun streamValid() = valid && !exhausted
	fun candidate(): TrustedHmdCommonCandidate? = if (streamValid() && mappingBootstrapped) poseCandidate else null
	fun currentWorld(): CommonWorldAuthorityHandle? = if (streamValid() && worldBootstrapped) foundation.currentWorld() else null
	fun currentMapping(): SourceToCommonMappingHandle? = if (streamValid() && mappingBootstrapped) foundation.currentMapping() else null
	fun lastRevocation() = foundation.lastRevocation()
	fun sequenceHighWater() = lastSequence

	fun beginAuthorizedPublisherSession(publisherId: String, sessionId: String, clockId: String): Boolean {
		if (exhausted || publisherId != expectedPublisherId || sessionId.isBlank() || clockId.isBlank() || sessionId in sessions) return false
		if (sessions.size >= capacity) { close(CommonWorldRevocationReason.CAPACITY_EXHAUSTED); exhausted = true; return false }
		sessions += sessionId
		session = sessionId; clock = clockId; last = null; lastSequence = null
		valid = true; worldBootstrapped = false; mappingBootstrapped = false; initialMappingRejected = false; poseCandidate = null
		return true
	}

	fun onTransportDisconnected() = close(CommonWorldRevocationReason.EVENT_STREAM_LOST)
	private fun close(reason: CommonWorldRevocationReason = CommonWorldRevocationReason.EVENT_STREAM_LOST) {
		valid = false; worldBootstrapped = false; mappingBootstrapped = false; poseCandidate = null
		foundation.revokeWorld(reason)
	}

	/** Only bytes already associated by the caller with this authorized ordered channel. */
	fun receiveBytes(bytes: ByteArray, receivedAtNanos: Long): String = when (val d = MonakaCodec.decodeEnvelope(bytes)) {
		is DecodeResult.Failure -> { close(); "malformed" }
		is DecodeResult.Success -> receive(d.value, receivedAtNanos)
	}

	fun receive(decoded: Envelope, receivedAtNanos: Long): String {
		val header = header(decoded) ?: return "wrong-protocol"
		if (header.publisher_id != expectedPublisherId) return "unauthorized"
		if (header.session_id != session) return if (header.session_id in sessions) "retired" else "unauthorized"
		if (!streamValid()) return "closed"
		if (MonakaCodec.encodeEnvelope(decoded) is EncodeResult.Failure) { close(); return "malformed" }
		val message = freeze(decoded)
		lastSequence?.let { high ->
			if (header.sequence < high) return "old"
			if (header.sequence == high) {
				if (immutable(message) == immutable(last!!)) return "duplicate"
				close(); return "conflict"
			}
			if (high == Long.MAX_VALUE || header.sequence != high + 1) { close(); return "gap" }
		} ?: run { if (header.sequence != 0L) { close(); return "gap" } }
		if (header.clock_id != clock) { close(); return "conflict" }
		lastSequence = header.sequence; last = message
		if (header.sequence == 0L && message !is CommonWorldAuthorityPublication) { close(); return "bootstrap-failed" }
		// Seq1 has to be the mapping snapshot, even when a pose/control packet is otherwise valid wire.
		if (header.sequence == 1L && message !is CommonWorldMappingPublication) {
			initialMappingRejected = true; poseCandidate = null; return "untrusted"
		}
		if (initialMappingRejected) return "untrusted"
		return try {
			val result = apply(message, receivedAtNanos)
			if (header.sequence == 1L && result != "mapping") initialMappingRejected = true
			if (foundation.lastRevocation()?.reason == CommonWorldRevocationReason.CAPACITY_EXHAUSTED) {
				exhausted = true; close(CommonWorldRevocationReason.CAPACITY_EXHAUSTED)
			}
			result
		} catch (_: IllegalArgumentException) {
			if (header.sequence == 1L) initialMappingRejected = true
			poseCandidate = null; "untrusted" // Float overflow/unit contract and projection failure
		}
	}

	private fun source(s: SourceSpaceAuthority) = SourceSpaceAuthorityEpoch(s.source_id,
		s.source_authority_session_epoch, s.source_space_id, s.source_space_generation)
	private fun key(s: SourceSpaceAuthorityEpoch) = s.sourceId to s.sourceSessionEpoch
	private fun sourceCurrent(s: SourceSpaceAuthorityEpoch): Boolean {
		val h = sources[key(s)] ?: return false
		return !h.retired && !h.generationRetired && h.space == s
	}
	private fun establishSource(s: SourceSpaceAuthorityEpoch): Boolean {
		val h = sources[key(s)]
		if (h == null) {
			if (sources.size >= capacity) { exhausted = true; close(CommonWorldRevocationReason.CAPACITY_EXHAUSTED); return false }
			sources[key(s)] = SourceHistory(s); return true
		}
		if (h.retired || s.generation < h.space.generation || (s.generation == h.space.generation && h.generationRetired)) return false
		if (s.generation == h.space.generation && s != h.space) { h.generationRetired = true; close(); return false }
		h.space = s; h.generationRetired = false; return true
	}
	private fun worldMatches(w: CommonWorldReference): Boolean = currentWorld()?.authority?.let {
		it.ownerId == w.owner_id && it.epoch.value == w.world_epoch && it.space == w.coordinate_space
	} ?: false
	private fun mappingMatches(m: CommonMappingReference): Boolean = currentMapping()?.snapshot?.let {
		worldMatches(m.world) && it.sourceSpace == source(m.source_space) && sourceCurrent(it.sourceSpace) &&
			it.calibrationEpoch.value == m.calibration_epoch && it.mappingRevision.value == m.mapping_revision
	} ?: false

	private fun apply(m: Envelope, at: Long): String = when (m) {
		is CommonWorldAuthorityPublication -> {
			poseCandidate = null; mappingBootstrapped = false
			val s = source(m.anchor_source)
			val w = CommonWorldAuthority(m.world.owner_id, m.world.coordinate_space, CommonWorldEpoch(m.world.world_epoch), s)
			val prior = foundation.currentWorld()?.authority
			// C2.1 world replacement must be ordered after old-world revocation, never implicit.
			if (prior != null && prior.epoch != w.epoch) "untrusted"
			else if (!establishSource(s) || foundation.establishWorld(w) == null) {
				worldBootstrapped = false; "untrusted"
			} else { worldBootstrapped = true; "world" }
		}
		is CommonWorldMappingPublication -> {
			poseCandidate = null
			val w = currentWorld()?.authority
			val s = source(m.mapping.source_space)
			if (w == null || !worldMatches(m.mapping.world) || s != w.anchor || !sourceCurrent(s)) "untrusted"
			else if (wireMappingConflict(m)) "untrusted"
			else {
				val snapshot = SourceToCommonMappingSnapshot(m.mapping.world.owner_id, s, w,
					CommonMappingCalibrationEpoch(m.mapping.calibration_epoch), CommonMappingRevision(m.mapping.mapping_revision),
					SourceToCommonRigidTransform(quaternion(m.transform.rotation_xyzw), vector(m.transform.translation_xyz)))
				val handle = foundation.publishMapping(snapshot)
				if (handle == null) { mappingBootstrapped = foundation.currentMapping() != null && mappingBootstrapped; "untrusted" }
				else { mappingBootstrapped = true; lastWireMapping = m; "mapping" }
			}
		}
		is TrustedHmdCommonPose -> acceptPose(m, at)
		is TrustedHmdCommonUnavailable -> if (!mappingMatches(m.mapping)) "untrusted" else { poseCandidate = null; "unavailable" }
		is CommonWorldMappingRevocation -> if (!mappingMatches(m.mapping)) "untrusted" else {
			foundation.revokeMapping(when (m.reason) {
				"mapping_conflict" -> CommonWorldRevocationReason.MAPPING_CONFLICT
				"source_space_changed" -> CommonWorldRevocationReason.SOURCE_SPACE_CHANGED
				else -> CommonWorldRevocationReason.MAPPING_WITHDRAWN
			}); mappingBootstrapped = false; poseCandidate = null; "revoked"
		}
		is CommonWorldRevocation -> if (!worldMatches(m.world)) "untrusted" else {
			val anchor = currentWorld()!!.authority.anchor
			// A world-level anchor loss alone is not a source-generation revocation (5AA world-replace fixture).
			if (m.reason in listOf("source_space_changed", "source_session_changed")) {
				sources.getValue(key(anchor)).generationRetired = true
				if (m.reason == "source_session_changed") sources.getValue(key(anchor)).retired = true
			}
			foundation.revokeWorld(CommonWorldRevocationReason.valueOf(m.reason.uppercase()))
			worldBootstrapped = false; mappingBootstrapped = false; poseCandidate = null; "revoked"
		}
		else -> "wrong-protocol"
	}

	private fun wireMappingConflict(m: CommonWorldMappingPublication): Boolean {
		val old = lastWireMapping ?: return false
		if (old.mapping.world != m.mapping.world) return false
		if (old.mapping.mapping_revision != m.mapping.mapping_revision) return false
		if (old.mapping == m.mapping && old.transform == m.transform) return false
		// Double facts that round to equal Float values are still different immutable wire facts.
		foundation.revokeMapping(CommonWorldRevocationReason.MAPPING_CONFLICT)
		mappingBootstrapped = false; poseCandidate = null
		return true
	}

	private fun acceptPose(m: TrustedHmdCommonPose, at: Long): String {
		if (!mappingMatches(m.mapping) || source(m.source.source_space) != source(m.mapping.source_space)) { poseCandidate = null; return "untrusted" }
		val s = source(m.source.source_space)
		val h = sources.getValue(key(s))
		val old = h.observation
		if (old != null) {
			if (m.source.observation_id < old.source.observation_id) return "old-observation"
			if (m.source.observation_id == old.source.observation_id && sourceObservation(m) != sourceObservation(old)) {
				h.generationRetired = true; close(CommonWorldRevocationReason.ANCHOR_AUTHORITY_LOST); return "observation-conflict"
			}
			if (m.source.source_time_domain_id != old.source.source_time_domain_id) { h.retired = true; close(); return "untrusted" }
		}
		val snapshot = TrustedHmdSourcePoseSnapshot(TrustedHmdSourceObservationIdentity(s, m.source.observation_id),
			vector(m.source_position), quaternion(m.source_orientation))
		val computed = foundation.mapSourcePose(snapshot) ?: run { poseCandidate = null; return "untrusted" }
		val p = vector(m.common_position); val q = quaternion(m.common_orientation)
		if (vectorBits(p) != vectorBits(computed.position) || quaternionBits(q) != quaternionBits(computed.orientation)) {
			poseCandidate = null; return "untrusted"
		}
		val repeated = old != null && m.source.observation_id == old.source.observation_id
		if (!repeated) { h.observation = m; h.receivedAt = at }
		// Remapping a known observation preserves its first receipt; unavailable cannot refresh it.
		if (repeated && poseCandidate?.pose?.mapping == computed.mapping) return "duplicate-observation"
		if (repeated && poseCandidate == null && h.lastMapped == computed.mapping) return "duplicate-observation"
		poseCandidate = TrustedHmdCommonCandidate(computed, m.source.source_locate_time_ns,
			m.source.source_time_domain_id, m.validity, h.receivedAt)
		h.lastMapped = computed.mapping
		return "pose"
	}

	private fun number(d: Double): Float { require(d.isFinite()); return d.toFloat().also { require(it.isFinite()) } }
	private fun vector(v: List<Double>): Vector3 { require(v.size == 3); return Vector3(number(v[0]), number(v[1]), number(v[2])) }
	private fun quaternion(q: List<Double>): Quaternion {
		require(q.size == 4)
		return Quaternion(number(q[3]), number(q[0]), number(q[1]), number(q[2])).also {
			// 5Z independently enforces its stricter norm-squared contract; no normalization.
			SourceToCommonRigidTransform(it, Vector3(0f, 0f, 0f))
		}
	}
	private fun vectorBits(v: Vector3) = listOf(v.x.toRawBits(), v.y.toRawBits(), v.z.toRawBits())
	private fun quaternionBits(q: Quaternion) = listOf(q.x.toRawBits(), q.y.toRawBits(), q.z.toRawBits(), q.w.toRawBits())
	private fun sourceObservation(m: TrustedHmdCommonPose) = listOf(m.source, m.source_position, m.source_orientation, m.validity)
	private fun freeze(m: Envelope): Envelope = when (m) {
		is CommonWorldMappingPublication -> m.copy(transform = m.transform.copy(rotation_xyzw = m.transform.rotation_xyzw.toList(), translation_xyz = m.transform.translation_xyz.toList()))
		is TrustedHmdCommonPose -> m.copy(source_position = m.source_position.toList(), source_orientation = m.source_orientation.toList(), common_position = m.common_position.toList(), common_orientation = m.common_orientation.toList())
		else -> m
	}
	private fun immutable(m: Envelope): Envelope = when (m) {
		is CommonWorldAuthorityPublication -> m.copy(sent_at_ns = 0)
		is CommonWorldMappingPublication -> m.copy(sent_at_ns = 0)
		is TrustedHmdCommonPose -> m.copy(sent_at_ns = 0)
		is TrustedHmdCommonUnavailable -> m.copy(sent_at_ns = 0)
		is CommonWorldMappingRevocation -> m.copy(sent_at_ns = 0)
		is CommonWorldRevocation -> m.copy(sent_at_ns = 0)
		else -> m
	}
	private fun header(m: Envelope): AuthorityHeader? = when (m) {
		is CommonWorldAuthorityPublication -> AuthorityHeader(m.version, m.publisher_id, m.session_id, m.clock_id, m.sequence, m.timestamp_ns, m.sent_at_ns, m.timestamp_kind)
		is CommonWorldMappingPublication -> AuthorityHeader(m.version, m.publisher_id, m.session_id, m.clock_id, m.sequence, m.timestamp_ns, m.sent_at_ns, m.timestamp_kind)
		is TrustedHmdCommonPose -> AuthorityHeader(m.version, m.publisher_id, m.session_id, m.clock_id, m.sequence, m.timestamp_ns, m.sent_at_ns, m.timestamp_kind)
		is TrustedHmdCommonUnavailable -> AuthorityHeader(m.version, m.publisher_id, m.session_id, m.clock_id, m.sequence, m.timestamp_ns, m.sent_at_ns, m.timestamp_kind)
		is CommonWorldMappingRevocation -> AuthorityHeader(m.version, m.publisher_id, m.session_id, m.clock_id, m.sequence, m.timestamp_ns, m.sent_at_ns, m.timestamp_kind)
		is CommonWorldRevocation -> AuthorityHeader(m.version, m.publisher_id, m.session_id, m.clock_id, m.sequence, m.timestamp_ns, m.sent_at_ns, m.timestamp_kind)
		else -> null
	}
}
