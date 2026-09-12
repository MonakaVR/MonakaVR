package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PicoOtCoordinateAndWireTests {
	@Test
	fun monakaCoordinateConventionPassesPoseThroughUnchanged() {
		val sample = PicoOtTrackerSample(
			trackerId = "tracker",
			position = Vector3(1f, 2f, 3f),
			rotation = Quaternion(0.5f, 0.1f, 0.2f, 0.3f),
		)

		val converted = PicoOtCoordinateConverter.toMonaka(
			sample,
			PicoOtCoordinateConvention.MONAKA_RH_Y_UP_NEG_Z_FORWARD_METERS,
		)

		assertEquals(sample, converted)
	}

	@Test
	fun unityLeftHandedPoseIsReflectedIntoMonakaRightHandedSpace() {
		val rotation = Quaternion.rotationAroundYAxis(1f)
		val sample = PicoOtTrackerSample(
			trackerId = "tracker",
			position = Vector3(1f, 2f, 3f),
			rotation = rotation,
		)

		val converted = PicoOtCoordinateConverter.toMonaka(
			sample,
			PicoOtCoordinateConvention.UNITY_LH_Y_UP_POS_Z_FORWARD_METERS,
		)

		assertEquals(Vector3(1f, 2f, -3f), converted.position)
		assertEquals(
			Quaternion(rotation.w, -rotation.x, -rotation.y, rotation.z),
			converted.rotation,
		)
	}

	@Test
	fun transportDataSourceNormalizesUnityCoordinatesBeforePublishingSnapshot() {
		val rotation = Quaternion.rotationAroundXAxis(0.5f)
		val frame = PicoOtTransportFrame(
			coordinateConvention = PicoOtCoordinateConvention.UNITY_LH_Y_UP_POS_Z_FORWARD_METERS,
			sessionId = "session",
			trackingSpaceId = "space",
			sequence = 1L,
			trackers = listOf(
				PicoOtTrackerSample(
					trackerId = "tracker",
					position = Vector3(0.1f, 1f, 0.2f),
					rotation = rotation,
				),
			),
		)
		val dataSource = PicoOtTransportDataSource(PicoOtTransportFrameProvider { frame })

		val sample = dataSource.snapshot(10L).trackers.single()

		assertEquals(Vector3(0.1f, 1f, -0.2f), sample.position)
		assertEquals(Quaternion(rotation.w, -rotation.x, -rotation.y, rotation.z), sample.rotation)
		assertEquals(
			PicoOtCoordinateConvention.UNITY_LH_Y_UP_POS_Z_FORWARD_METERS,
			dataSource.coordinateConvention,
		)
	}

	@Test
	fun jsonWireCodecRoundTripsCompleteFrameAsSingleNdjsonRecord() {
		val frame = PicoOtTransportFrame(
			coordinateConvention = PicoOtCoordinateConvention.UNITY_LH_Y_UP_POS_Z_FORWARD_METERS,
			sessionId = "session-a",
			trackingSpaceId = "space-1",
			sequence = 42L,
			trackers = listOf(
				PicoOtTrackerSample(
					trackerId = "tracker-1",
					position = Vector3(0.1f, 1f, 0.2f),
					rotation = Quaternion(1f, 0f, 0f, 0f),
					positionState = PicoOtComponentTrackingState.DEGRADED,
					rotationState = PicoOtComponentTrackingState.TRACKED,
					batteryPercent = 73,
				),
			),
		)

		val line = PicoOtJsonWireCodec.encodeLine(frame)

		assertTrue(line.endsWith("\n"))
		assertEquals(1, line.count { it == '\n' })
		assertTrue(line.contains("\"coordinateConvention\""))
		assertTrue(line.contains("\"rotation\""))
		assertEquals(frame, PicoOtJsonWireCodec.decodeLine(line))
		assertEquals(frame, PicoOtJsonWireCodec.decodeLine(line.dropLast(1) + "\r\n"))
	}

	@Test
	fun jsonWireCodecRejectsUnknownCoordinateConventionAndMultipleRecords() {
		val unknownConvention = """
			{
			  "protocolVersion": 1,
			  "coordinateConvention": "UNKNOWN_SPACE",
			  "sessionId": "session",
			  "trackingSpaceId": "space",
			  "sequence": 0,
			  "trackers": []
			}
		""".trimIndent()

		assertFailsWith<IllegalArgumentException> {
			PicoOtJsonWireCodec.decode(unknownConvention)
		}

		val validLine = PicoOtJsonWireCodec.encodeLine(
			PicoOtTransportFrame(
				sessionId = "session",
				trackingSpaceId = "space",
				sequence = 0L,
				trackers = emptyList(),
			),
		)
		assertFailsWith<IllegalArgumentException> {
			PicoOtJsonWireCodec.decodeLine(validLine + validLine)
		}
	}
}
