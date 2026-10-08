package dev.monaka.tracking.desktop

import dev.slimevr.desktop.platform.ProtobufMessages.Position

/** Partial software sample review only. Never a ReviewedHmdBackendContract. */
internal data class ReviewedHmdSampleTransportContract(
	val contractId: String,
	val proofSource: String,
	val evidenceVersion: Int,
)

internal val REVIEWED_HMD_SAMPLE_TRANSPORT_V1 = ReviewedHmdSampleTransportContract(
	"monaka-openvr-provider-sample-v1",
	"dcc0f56bcb2a3196d6f92b1ed1d029faa425b931 + reviewed Monaka provider overlay 5T/5U",
	1,
)

internal data class HmdProviderTransportAssociation(
	val transportSessionEpoch: String,
	val contract: ReviewedHmdSampleTransportContract,
)

/** Float values are copied without arithmetic, normalization or validity substitution. */
data class HmdTransportPose(val x: Float, val y: Float, val z: Float,
	val qx: Float, val qy: Float, val qz: Float, val qw: Float)

data class HmdProviderSampleTransportEvidenceV1(
	val providerSessionEpoch: String,
	val observationId: Long,
	val rawPose: HmdTransportPose,
	val wirePose: HmdTransportPose,
	val dataSourceValue: Int,
	val poseValid: Boolean,
	val deviceConnected: Boolean,
	val trackingResult: Int,
) {
	/** Sole projection. Source comes from registered ingress; missing authority remains null. */
	internal fun partialProviderEvidence(sourceIdentity: String) = RawHmdProviderPoseEvidence(
		sourceIdentity, providerSessionEpoch, observationId, null, null, null, null, null,
	)
}

/** Called on the same decoded Position only after current transport confirmation. */
internal fun decodeHmdProviderSampleTransport(position: Position): HmdProviderSampleTransportEvidenceV1? {
	if (position.trackerId != 0 || !position.hasHmdProviderEvidenceV1() ||
		!position.hasX() || !position.hasY() || !position.hasZ() || !position.hasDataSource() ||
		position.dataSource != Position.DataSource.FULL) return null
	val e = position.hmdProviderEvidenceV1
	if (!e.hasProviderSessionEpoch() || e.providerSessionEpoch.isBlank() ||
		!e.hasObservationId() || e.observationId < 0 ||
		!e.hasRawX() || !e.hasRawY() || !e.hasRawZ() || !e.hasRawQx() || !e.hasRawQy() || !e.hasRawQz() || !e.hasRawQw() ||
		!e.hasWireX() || !e.hasWireY() || !e.hasWireZ() || !e.hasWireQx() || !e.hasWireQy() || !e.hasWireQz() || !e.hasWireQw() ||
		!e.hasDataSource() || !e.hasPoseValid() || !e.hasDeviceConnected() || !e.hasTrackingResult() ||
		e.dataSource != position.dataSourceValue) return null
	fun same(a: Float, b: Float) = java.lang.Float.floatToRawIntBits(a) == java.lang.Float.floatToRawIntBits(b)
	if (!same(e.wireX, position.x) || !same(e.wireY, position.y) || !same(e.wireZ, position.z) ||
		!same(e.wireQx, position.qx) || !same(e.wireQy, position.qy) || !same(e.wireQz, position.qz) ||
		!same(e.wireQw, position.qw)) return null
	return HmdProviderSampleTransportEvidenceV1(e.providerSessionEpoch, e.observationId,
		HmdTransportPose(e.rawX, e.rawY, e.rawZ, e.rawQx, e.rawQy, e.rawQz, e.rawQw),
		HmdTransportPose(e.wireX, e.wireY, e.wireZ, e.wireQx, e.wireQy, e.wireQz, e.wireQw),
		e.dataSource, e.poseValid, e.deviceConnected, e.trackingResult)
}
