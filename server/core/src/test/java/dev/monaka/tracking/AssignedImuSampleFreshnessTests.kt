package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AssignedImuSampleFreshnessTests {
	private val mainId = "mtp:main"
	private val imuId = "slime:imu"
	private val assignments = TrackerBodyAssignments().also {
		it.configure(TrackerPosition.HIP, TrackerReference(mainId), TrackerReference(imuId))
	}
	private val filter = AssignedImuSampleFreshness(assignments, 10)
	private fun pipeline() = ConstraintPipeline(resolver = ConstraintResolver { assignments.snapshot().targets }, eligibility = filter::apply)
	private fun main(modality: TrackingModality, at: Long) = PoseObservation(
		mainId,
		TrackerPosition.HIP,
		at,
		position = if (modality == TrackingModality.FULL) Vector3(1f, 2f, 3f) else null,
		rotation = if (modality == TrackingModality.NONE) null else Quaternion.rotationAroundYAxis(.5f),
		modality = modality,
	)
	private fun imu(polledAt: Long, sampleAt: Long?, sequence: Long = 1) = PoseObservation(
		imuId,
		TrackerPosition.HIP,
		polledAt,
		rotation = Quaternion.IDENTITY,
		provenance = sampleAt?.let { ObservationSampleProvenance(sequence, it, "session", "mount") },
		correctionRotation = Quaternion.IDENTITY,
	)

	@Test fun physicalAgeChangesEligibilityBeforePolicyWithoutChangingOtherSources() {
		val p = pipeline()
		p.ingest(main(TrackingModality.FULL, 10))
		p.ingest(imu(10, 10))
		assertEquals(mainId, p.resolve(TrackerPosition.HIP, 20).rotation?.sourceId)
		p.ingest(main(TrackingModality.ROTATION_ONLY, 20))
		assertEquals(imuId, p.resolve(TrackerPosition.HIP, 20).rotation?.sourceId)
		// A new poll/heartbeat changes observedAt, not the physical sample identity/time.
		p.ingest(imu(21, 10))
		assertEquals(mainId, p.resolve(TrackerPosition.HIP, 21).rotation?.sourceId)
		assertEquals(ObservationQuality.STALE, p.observations(21).single { it.sourceId == imuId }.rotationQuality)
		p.ingest(main(TrackingModality.NONE, 22))
		assertNull(p.resolve(TrackerPosition.HIP, 22).rotation)
		p.ingest(imu(23, 23, 2))
		assertEquals(imuId, p.resolve(TrackerPosition.HIP, 23).rotation?.sourceId)
	}

	@Test fun futureOrUnknownPhysicalSampleFailsClosedButLegacyUnassignedIsUnchanged() {
		assertEquals(ObservationQuality.STALE, filter.apply(imu(20, 21), 20).rotationQuality)
		assertEquals(ObservationQuality.STALE, filter.apply(imu(20, null), 20).rotationQuality)
		assertEquals(ObservationQuality.TRACKED, filter.apply(imu(20, 20).copy(correctionRotation = Quaternion(Float.NaN, 0f, 0f, 0f)), 20).rotationQuality)
		assertEquals(ObservationQuality.STALE, filter.apply(imu(20, 20).copy(rotation = Quaternion(Float.NaN, 0f, 0f, 0f)), 20).rotationQuality)
		val unrelated = imu(20, null).copy(target = TrackerPosition.LEFT_FOOT)
		assertEquals(ObservationQuality.TRACKED, filter.apply(unrelated, 100).rotationQuality)
	}

	@Test fun physicalImuRollbackCannotLeakAsRawFallbackBeforePolicy() {
		val p = pipeline()
		p.ingest(main(TrackingModality.ROTATION_ONLY, 10))
		p.ingest(imu(10, 10, 1))
		assertEquals(imuId, p.resolve(TrackerPosition.HIP, 10).rotation?.sourceId)
		p.ingest(imu(20, 20, 2))
		assertEquals(imuId, p.resolve(TrackerPosition.HIP, 20).rotation?.sourceId)
		p.ingest(imu(21, 21, 1))
		assertEquals(mainId, p.resolve(TrackerPosition.HIP, 21).rotation?.sourceId)
		p.ingest(main(TrackingModality.NONE, 22))
		assertNull(p.resolve(TrackerPosition.HIP, 22).rotation)
		p.ingest(imu(23, 23, 3))
		assertEquals(imuId, p.resolve(TrackerPosition.HIP, 23).rotation?.sourceId)
	}

	@Test fun explicitQOnlyUsesNormalAgeAndReceiptCursorIncludingZeroTimeout() {
		val a = TrackerBodyAssignments().also { it.configureSources(TrackerPosition.HIP, TrackerReference.slime("imu"), null, true) }
		val normal = AssignedImuSampleFreshness(a, 500, onlyExplicitPairs = true, legacyHipMaxAgeNanos = 100)
		assertEquals(ObservationQuality.TRACKED, normal.apply(imu(100, 100), 100).rotationQuality)
		assertEquals(ObservationQuality.TRACKED, normal.apply(imu(400, 100), 400).rotationQuality, "Q-only does not inherit legacy HIP paired age")
		assertEquals(ObservationQuality.STALE, normal.apply(imu(601, 100), 601).rotationQuality)
		assertEquals(ObservationQuality.STALE, normal.apply(imu(601, 602, 2), 601).rotationQuality)
		assertEquals(ObservationQuality.STALE, normal.apply(imu(601, 601), 601).rotationQuality, "Same sequence cannot regenerate sample time")
		assertEquals(ObservationQuality.TRACKED, normal.apply(imu(602, 602, 2), 602).rotationQuality)
		val zero = AssignedImuSampleFreshness(a, 0, onlyExplicitPairs = true)
		assertEquals(ObservationQuality.TRACKED, zero.apply(imu(10, 10), 10).rotationQuality)
		assertEquals(ObservationQuality.STALE, zero.apply(imu(11, 10), 11).rotationQuality)
		MonakaRuntime({ emptyList() }, dev.monaka.protocol.v2.CoordinateSpace("zero", "rh_y_up_neg_z_forward", 0), a, timeoutNanos = 0).use { it.tickSnapshot() }
	}

	@Test fun legacyHipAgeDoesNotNarrowOtherBodyOrMtpQ() {
		val a = TrackerBodyAssignments().also {
			it.configureSources(TrackerPosition.HIP, TrackerReference.slime("imu"), TrackerReference.mtp(LogicalTracker("s", "hip", "p")), true)
			it.configureSources(TrackerPosition.UPPER_CHEST, TrackerReference.slime("chest"), TrackerReference.mtp(LogicalTracker("s", "chest", "p")), true)
		}
		val normal = AssignedImuSampleFreshness(a, 500, onlyExplicitPairs = true, legacyHipMaxAgeNanos = 100)
		assertEquals(ObservationQuality.STALE, normal.apply(imu(300, 100), 300).rotationQuality)
		assertEquals(ObservationQuality.TRACKED, normal.apply(imu(300, 100).copy(sourceId = "slime:chest", target = TrackerPosition.UPPER_CHEST), 300).rotationQuality)
		val mtp = TrackerReference.mtp(LogicalTracker("s", "q", "p"))
		a.configureSources(TrackerPosition.LEFT_FOOT, mtp, null, true)
		assertEquals(ObservationQuality.TRACKED, normal.apply(imu(1000, null).copy(sourceId = mtp.observationId, target = TrackerPosition.LEFT_FOOT), 1000).rotationQuality, "MTP retains its existing source profile freshness")
	}
}
