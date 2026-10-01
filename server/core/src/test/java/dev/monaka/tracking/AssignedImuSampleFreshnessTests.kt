package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.*

class AssignedImuSampleFreshnessTests {
	private val mainId = "mtp:main"
	private val imuId = "slime:imu"
	private val assignments = TrackerBodyAssignments().also {
		it.configure(TrackerPosition.HIP, TrackerReference(mainId), TrackerReference(imuId))
	}
	private val filter = AssignedImuSampleFreshness(assignments, 10)
	private fun pipeline() = ConstraintPipeline(
		resolver = ConstraintResolver { assignments.snapshot().targets }, eligibility = filter::apply)
	private fun main(modality: TrackingModality, at: Long) = PoseObservation(mainId, TrackerPosition.HIP, at,
		position = if (modality == TrackingModality.FULL) Vector3(1f, 2f, 3f) else null,
		rotation = if (modality == TrackingModality.NONE) null else Quaternion.rotationAroundYAxis(.5f),
		modality = modality)
	private fun imu(polledAt: Long, sampleAt: Long?, sequence: Long = 1) = PoseObservation(
		imuId, TrackerPosition.HIP, polledAt, rotation = Quaternion.IDENTITY,
		provenance = sampleAt?.let { ObservationSampleProvenance(sequence, it, "session", "mount") })

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
		val unrelated = imu(20, null).copy(target = TrackerPosition.LEFT_FOOT)
		assertEquals(ObservationQuality.TRACKED, filter.apply(unrelated, 100).rotationQuality)
	}
}
