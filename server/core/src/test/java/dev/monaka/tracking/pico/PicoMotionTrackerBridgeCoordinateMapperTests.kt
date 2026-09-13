package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PicoMotionTrackerBridgeCoordinateMapperTests {
	private val halfSqrt2 = 0.70710677f

	private fun state(
		position: Vector3? = Vector3(0f, 0f, 0f),
		rotation: Quaternion? = Quaternion.IDENTITY,
		positionValid: Boolean = position != null,
		orientationSamplePresent: Boolean = rotation != null,
	) = PicoMotionTrackerBridgeState(
		serial = "PICO-COORD-001",
		connectedSnapshot = true,
		positionMeters = position,
		orientation = rotation,
		positionValid = positionValid,
		orientationSamplePresent = orientationSamplePresent,
		lastPoseReceivePcMonotonicNanos = 100L,
		referenceFrame = PicoMotionTrackerBridgeReferenceFrame.PICO_OUTPUT_A,
	)

	@Test
	fun outputAPositionUsesValidatedMeterBasisWithoutAdditionalReflection() {
		val origin = Vector3(0f, 0f, 0f)
		assertEquals(origin, PicoMotionTrackerBridgeCoordinateMapper.mapPositionFromPicoOutputA(origin))
		assertEquals(Vector3(1f, 0f, 0f), PicoMotionTrackerBridgeCoordinateMapper.mapPositionFromPicoOutputA(Vector3(1f, 0f, 0f)))
		assertEquals(Vector3(0f, 1f, 0f), PicoMotionTrackerBridgeCoordinateMapper.mapPositionFromPicoOutputA(Vector3(0f, 1f, 0f)))
		assertEquals(Vector3(0f, 0f, 1f), PicoMotionTrackerBridgeCoordinateMapper.mapPositionFromPicoOutputA(Vector3(0f, 0f, 1f)))
	}

	@Test
	fun outputAIdentityRotationRemainsIdentity() {
		assertEquals(
			Quaternion.IDENTITY,
			PicoMotionTrackerBridgeCoordinateMapper.mapRotationFromPicoOutputA(Quaternion.IDENTITY),
		)
	}

	@Test
	fun outputABasisRotationsMatchValidatedBridgeToRoomMapping() {
		val s = halfSqrt2

		assertEquals(
			Quaternion(s, 0f, 0f, -s),
			PicoMotionTrackerBridgeCoordinateMapper.mapRotationFromPicoOutputA(Quaternion(s, s, 0f, 0f)),
		)
		assertEquals(
			Quaternion(s, 0f, 0f, s),
			PicoMotionTrackerBridgeCoordinateMapper.mapRotationFromPicoOutputA(Quaternion(s, -s, 0f, 0f)),
		)

		assertEquals(
			Quaternion(s, 0f, s, 0f),
			PicoMotionTrackerBridgeCoordinateMapper.mapRotationFromPicoOutputA(Quaternion(s, 0f, s, 0f)),
		)
		assertEquals(
			Quaternion(s, 0f, -s, 0f),
			PicoMotionTrackerBridgeCoordinateMapper.mapRotationFromPicoOutputA(Quaternion(s, 0f, -s, 0f)),
		)

		assertEquals(
			Quaternion(s, -s, 0f, 0f),
			PicoMotionTrackerBridgeCoordinateMapper.mapRotationFromPicoOutputA(Quaternion(s, 0f, 0f, s)),
		)
		assertEquals(
			Quaternion(s, s, 0f, 0f),
			PicoMotionTrackerBridgeCoordinateMapper.mapRotationFromPicoOutputA(Quaternion(s, 0f, 0f, -s)),
		)
	}

	@Test
	fun mapperDoesNotInventMissingComponents() {
		val mapped = PicoMotionTrackerBridgeCoordinateMapper.toMonaka(
			state(
				position = null,
				rotation = null,
				positionValid = false,
				orientationSamplePresent = false,
			),
		)

		assertNull(mapped.position)
		assertNull(mapped.rotation)
	}

	@Test
	fun dataSourceKeepsComponentQualityIndependentWhileApplyingCoordinateMapping() {
		val source = PicoMotionTrackerBridgeDataSource(
			provider = PicoMotionTrackerBridgeStateProvider {
				listOf(
					state(
						position = Vector3(1f, 2f, 3f),
						rotation = Quaternion(halfSqrt2, halfSqrt2, 0f, 0f),
						positionValid = false,
						orientationSamplePresent = true,
					),
				)
			},
			poseMapper = PicoMotionTrackerBridgeCoordinateMapper,
		)

		val tracker = source.snapshot(200L).trackers.single()

		assertEquals(Vector3(1f, 2f, 3f), tracker.position)
		assertEquals(Quaternion(halfSqrt2, 0f, 0f, -halfSqrt2), tracker.rotation)
		assertEquals(PicoOtComponentTrackingState.LOST, tracker.positionState)
		assertEquals(PicoOtComponentTrackingState.DEGRADED, tracker.rotationState)
	}
}
