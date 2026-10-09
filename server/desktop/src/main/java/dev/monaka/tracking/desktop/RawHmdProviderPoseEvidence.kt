package dev.monaka.tracking.desktop

import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.tracking.TrackingModality
import dev.slimevr.desktop.platform.ProtobufMessages.Position
import dev.monaka.tracking.desktop.RawHmdPoseInputRejectionReason.*

/** Actual source-to-output mapping, frozen with the final P/Q. Identity must be explicit.
 * Epoch covers output-space incarnation/change; revision is NOT a raw-space generation.
 */
data class RawHmdAppliedMapping(
	val outputSpace: CoordinateSpace,
	val outputSpaceEpoch: String,
	val calibrationEpoch: String,
	val revision: Long?,
	val identity: Boolean,
)

/** Provider facts for ONE immutable observation. Null means unavailable, never a host default.
 * observationId is an ordered, nonnegative counter LOCAL to this source/provider session.
 * Gaps are allowed; exhaustion requires a fresh provider incarnation before another capture.
 * Raw owner/incarnation/generation must cover every mutation of the public tracking basis.
 * Host sequence, transport UUID, sourceEpoch, Universe ID and receipt time are separate facts.
 */
data class RawHmdProviderPoseEvidence(
	val sourceIdentity: String?,
	val providerSessionEpoch: String?,
	val observationId: Long?,
	val rawSpaceOwner: String?,
	val rawSpaceIncarnation: String?,
	val rawSpaceGeneration: String?,
	val appliedMapping: RawHmdAppliedMapping?,
	val sourceValid: Boolean?,
)

/** Reviewed acquisition contract/endpoint association, supplied by internal registration only.
 * No production OpenVR registration supplies this. Strings in a message cannot register a backend.
 * Future activation must prove mutation coverage, atomic capture and lossless forwarding separately.
 */
internal data class ReviewedHmdBackendContract(
	val contractId: String,
	val sourceIdentity: String,
	val rawSpaceOwner: String,
	val outputSpace: CoordinateSpace,
	val proofSource: String,
)

/** Receiver's explicitly established current provider/raw/output context; never inferred from transport. */
internal data class RawHmdProviderSession(
	val backend: ReviewedHmdBackendContract,
	val providerSessionEpoch: String,
	val rawSpaceIncarnation: String,
	val rawSpaceGeneration: String,
	val appliedMapping: RawHmdAppliedMapping,
)

internal fun providerEvidenceRejections(
	sample: HmdAcceptedPoseMessageSample,
	context: RawHmdProviderSession?,
): Set<RawHmdPoseInputRejectionReason> = buildSet {
	val evidence = sample.providerEvidence
	if (evidence?.providerSessionEpoch.isNullOrBlank()) add(PROVIDER_SESSION_UNAVAILABLE)
	if (evidence?.observationId == null) add(OBSERVATION_ID_UNAVAILABLE)
	else if (evidence.observationId < 0) add(PROVIDER_EVIDENCE_INVALID)
	if (evidence?.rawSpaceOwner.isNullOrBlank() || evidence?.rawSpaceIncarnation.isNullOrBlank() ||
		evidence?.rawSpaceGeneration.isNullOrBlank()) add(RAW_SPACE_GENERATION_UNAVAILABLE)
	val mapping = evidence?.appliedMapping
	if (mapping == null) {
		add(FRAME_SPACE_UNAVAILABLE); add(FRAME_EPOCH_UNAVAILABLE)
	} else {
		if (mapping.outputSpace.id.isBlank() || mapping.outputSpace.convention.isBlank() || mapping.outputSpace.revision < 0)
			add(FRAME_SPACE_INVALID)
		if (mapping.outputSpaceEpoch.isBlank() || mapping.calibrationEpoch.isBlank()) add(FRAME_EPOCH_UNAVAILABLE)
		if (mapping.revision != null && mapping.revision < 0) add(MAPPING_REVISION_INVALID)
		if (!mapping.identity && mapping.revision == null) add(PROVIDER_EVIDENCE_INVALID)
	}
	if (evidence?.sourceValid != true) add(PROVIDER_EVIDENCE_INVALID)
	if (evidence?.sourceIdentity.isNullOrBlank() || evidence?.sourceIdentity != sample.ingressIdentity.sourceId)
		add(SOURCE_IDENTITY_MISMATCH)
	if (context == null || context.backend.contractId.isBlank() || context.backend.proofSource.isBlank())
		add(FRAME_REFERENCE_UNAVAILABLE)
	if (context != null) {
		if (context.backend.sourceIdentity != sample.ingressIdentity.sourceId) add(SOURCE_IDENTITY_MISMATCH)
		if (context.providerSessionEpoch.isBlank() || context.rawSpaceIncarnation.isBlank() ||
			context.rawSpaceGeneration.isBlank() || context.backend.rawSpaceOwner.isBlank() ||
			evidence?.providerSessionEpoch != context.providerSessionEpoch ||
			evidence.rawSpaceOwner != context.backend.rawSpaceOwner ||
			evidence.rawSpaceIncarnation != context.rawSpaceIncarnation ||
			evidence.rawSpaceGeneration != context.rawSpaceGeneration ||
			mapping != context.appliedMapping || mapping?.outputSpace != context.backend.outputSpace)
			add(PROVIDER_EVIDENCE_INVALID)
	}
}

/** The sole Ready factory: projections come from the exact sample, not parallel hand-entered facts. */
internal fun deriveRawHmdPoseInputCapability(
	sample: HmdAcceptedPoseMessageSample?,
	context: RawHmdProviderSession?,
	freshnessPolicy: RawHmdPoseFreshnessPolicy,
): RawHmdPoseInputCapability {
	if (sample == null) return RawHmdPoseInputCapability.Unavailable(setOf(POSITION_UNAVAILABLE))
	val reasons = providerEvidenceRejections(sample, context).toMutableSet()
	if (freshnessPolicy.maxReceiptAgeNanos <= 0) reasons += FRESHNESS_POLICY_INVALID
	if (!sample.ingressIdentity.isRawHmd()) reasons += FEEDBACK_SOURCE_NOT_ALLOWED
	if (sample.transportSessionEpoch.isNullOrBlank()) reasons += SESSION_EPOCH_UNAVAILABLE
	if (sample.sequence < 0 || sample.sourceEpoch.isBlank() || sample.receivedAtSystemNanos < 0)
		reasons += SAMPLE_PROVENANCE_INVALID
	if (sample.structuralRejectionReasons.isNotEmpty()) reasons += SAMPLE_PROVENANCE_INVALID
	if (!sample.dataSourcePresent || sample.modality != TrackingModality.FULL || sample.dataSourceValue != Position.DataSource.FULL.number)
		reasons += POSITION_MODALITY_NOT_FULL
	if (reasons.isNotEmpty()) return RawHmdPoseInputCapability.Unavailable(reasons)
	val mapping = requireNotNull(sample.providerEvidence?.appliedMapping)
	return RawHmdPoseInputCapability.Ready(sample.ingressIdentity.sourceId, sample.sourceEpoch,
		requireNotNull(sample.transportSessionEpoch), sample.sequence, mapping.outputSpace,
		mapping.calibrationEpoch, mapping.revision, freshnessPolicy,
		RawHmdFrameProofKind.EXPLICIT_POSE_BOUND_FRAME_PROOF, requireNotNull(context).backend.proofSource,
		sample, context)
}

/** Server-thread observation high-water, not a global/persistent replay cache.
 * Retired epochs are bounded; capacity exhaustion fails closed for this source lifetime.
 * Polling never calls observe. Duplicate delivery cannot renew the first receipt or revive a loss.
 */
internal class HmdProviderObservationState {
	private val retired = mutableSetOf<String>()
	private var exhausted = false
	private var highWater: Long? = null
	private var lastObservation: HmdAcceptedPoseMessageSample? = null
	private val retiredRawSpaces = mutableSetOf<Pair<String, String>>()
	private val retiredOutputEpochs = mutableSetOf<String>()
	private val retiredCalibrationEpochs = mutableSetOf<String>()
	var session: RawHmdProviderSession? = null
		private set
	var candidate: HmdAcceptedPoseMessageSample? = null
		private set

	fun establish(next: RawHmdProviderSession): Boolean {
		if (session == next) return true
		if (exhausted || next.providerSessionEpoch in retired) return false
		if (next.appliedMapping.outputSpaceEpoch in retiredOutputEpochs ||
			next.appliedMapping.calibrationEpoch in retiredCalibrationEpochs) { retire(); return false }
		val old = session
		if (old?.providerSessionEpoch == next.providerSessionEpoch) {
			val raw = next.rawSpaceIncarnation to next.rawSpaceGeneration
			val oldRaw = old.rawSpaceIncarnation to old.rawSpaceGeneration
			if (raw in retiredRawSpaces) {
				retire(); return false
			}
			val before = old.appliedMapping
			val after = next.appliedMapping
			if (before != after && before.outputSpaceEpoch == after.outputSpaceEpoch) {
				// Mapping content may change within one world. Physical observation progression stays independent.
				if (before.revision != null && after.revision != null && after.revision < before.revision) {
					candidate = null; return false
				}
				if (before.outputSpace != after.outputSpace || before.revision == null || after.revision == null ||
					after.revision <= before.revision) { retire(); return false }
			}
			if (raw != oldRaw) retiredRawSpaces += oldRaw
		}
		if (old != null) {
			if (next.appliedMapping.outputSpaceEpoch != old.appliedMapping.outputSpaceEpoch)
				retiredOutputEpochs += old.appliedMapping.outputSpaceEpoch
			if (next.appliedMapping.calibrationEpoch != old.appliedMapping.calibrationEpoch)
				retiredCalibrationEpochs += old.appliedMapping.calibrationEpoch
		}
		if (retiredRawSpaces.size > 128 || retiredOutputEpochs.size > 128 || retiredCalibrationEpochs.size > 128) {
			exhausted = true; retire(); return false
		}
		// Changing raw/mapping context in one provider epoch keeps the observation high-water.
		if (session?.providerSessionEpoch != next.providerSessionEpoch) {
			retire(); highWater = null; lastObservation = null
			retiredRawSpaces.clear()
			if (exhausted) return false
		}
		session = next; candidate = null
		return true
	}

	fun invalidate() { candidate = null }
	fun retire() {
		session?.let {
			if (retired.size >= 128) exhausted = true else retired += it.providerSessionEpoch
		}
		session = null; candidate = null
	}

	fun observe(sample: HmdAcceptedPoseMessageSample, policy: RawHmdPoseFreshnessPolicy) {
		val id = sample.providerEvidence?.observationId
		if (sample.providerEvidence?.providerSessionEpoch != session?.providerSessionEpoch) {
			// Retired/foreign provider traffic cannot replace or invalidate the current provider.
			if (sample.providerEvidence?.providerSessionEpoch in retired) return
			invalidate(); return
		}
		if (id == null || id < 0) { invalidate(); return }
		val previous = highWater
		if (previous != null && id < previous) return
		if (previous == id) {
			val old = lastObservation
			if (old == null || sample.copy(sequence = old.sequence, receivedAtSystemNanos = old.receivedAtSystemNanos) != old)
				retire()
			return
		}
		highWater = id; lastObservation = sample
		candidate = if (deriveRawHmdPoseInputCapability(sample, session, policy) is RawHmdPoseInputCapability.Ready) sample else null
	}
}
