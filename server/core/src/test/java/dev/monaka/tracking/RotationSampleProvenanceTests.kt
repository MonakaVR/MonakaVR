package dev.monaka.tracking

import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.slimevr.tracking.trackers.udp.IMUType
import dev.slimevr.tracking.trackers.udp.UDPDevice
import io.github.axisangles.ktmath.Quaternion
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.*

class RotationSampleProvenanceTests {
	@Test fun onlyPhysicalOrientationAcceptanceAdvancesSlimeSequenceNotPollHeartbeatOrTick() {
		val device = UDPDevice(InetSocketAddress("127.0.0.1", 20000), InetAddress.getLoopbackAddress(), "test-hwid")
		val tracker = Tracker(device, 101, "test-imu", trackerPosition = TrackerPosition.HIP,
			hasRotation = true, imuType = IMUType.UNKNOWN, allowReset = true, allowMounting = true,
			trackRotDirection = false)
		tracker.status = TrackerStatus.OK
		tracker.setRotation(Quaternion.IDENTITY)
		val sample = tracker.correctionOrientationSample()!!
		val adapter = SlimeTrackerPoseObservationAdapter(receiptClock = { sample.receivedAtSystemNanos + 1_000_000 })
		val first = adapter.adapt(tracker, 3_000_000)!!.provenance!!
		tracker.heartbeat()
		tracker.dataTick() // Ping/heartbeat may call dataTick without a rotation packet.
		val second = adapter.adapt(tracker, 4_000_000)!!.provenance!!
		assertEquals(first, second)
		assertEquals(2_000_000, first.sampleAtNanos)
		tracker.setRotation(Quaternion.rotationAroundYAxis(.2f))
		val next = tracker.correctionOrientationSample()!!
		val nextAdapter = SlimeTrackerPoseObservationAdapter(receiptClock = { next.receivedAtSystemNanos + 1_000_000 })
		val third = nextAdapter.adapt(tracker, 5_000_000)!!.provenance!!
		assertEquals(first.sequence + 1, third.sequence)
		val beforeReconnect = third.sourceEpoch
		tracker.status = TrackerStatus.DISCONNECTED
		tracker.status = TrackerStatus.OK
		assertNotEquals(beforeReconnect, nextAdapter.adapt(tracker, 6_000_000)!!.provenance!!.sourceEpoch)
		val beforeHandshakeReuse = tracker.correctionSourceEpoch
		tracker.markObservationReconnect() // Same UDPDevice/Tracker may be reused while status remains OK.
		assertNotEquals(beforeHandshakeReuse, tracker.correctionSourceEpoch)
		val oldCalibration = tracker.resetsHandler.correctionCalibrationEpoch()
		tracker.resetsHandler.mountingOrientation = Quaternion.IDENTITY
		assertNotEquals(oldCalibration, nextAdapter.adapt(tracker, 7_000_000)!!.provenance!!.calibrationEpoch)
		val beforeReset = tracker.resetsHandler.correctionCalibrationEpoch()
		tracker.resetsHandler.resetYaw(Quaternion.IDENTITY)
		assertNotEquals(beforeReset, tracker.resetsHandler.correctionCalibrationEpoch())
	}

	@Test fun internalAndNonImuTrackerCannotClaimPhysicalImuProvenance() {
		val tracker = Tracker(null, 102, "virtual", trackerPosition = TrackerPosition.HIP,
			hasRotation = true, isInternal = true, trackRotDirection = false)
		tracker.status = TrackerStatus.OK
		tracker.setRotation(Quaternion.IDENTITY)
		assertNull(SlimeTrackerPoseObservationAdapter().adapt(tracker, 1_000_000_000)!!.provenance)
	}
}
