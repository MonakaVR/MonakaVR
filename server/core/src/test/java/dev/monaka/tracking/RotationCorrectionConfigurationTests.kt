package dev.monaka.tracking

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

class RotationCorrectionConfigurationTests {
	private val space = CoordinateSpace("config-world", "rh_y_up_neg_z_forward", 7)
	private fun assignments() = TrackerBodyAssignments().also {
		it.configure(TrackerPosition.HIP, TrackerReference.mtp(LogicalTracker("source", "tracker", "publisher")),
			TrackerReference.slime("imu"), OutputMode.HYBRID)
	}
	private fun tuning() = RotationCorrectionTuning(2_000_000, 1_000_000, .1, .2, 1.0, .1, 2.0, 2, 50_000_000)

	@Test fun omittedOrDisabledConfigKeepsCorrectionOff() {
		val path = Files.createTempFile("monaka-correction-off", ".json")
		try {
			MonakaConfiguration(space, assignments(), port = 0, backgroundIkSharedSpace = space).save(path)
			assertNull(MonakaConfiguration.load(path).rotationCorrection)
			val root = ObjectMapper().readTree(path.toFile()) as com.fasterxml.jackson.databind.node.ObjectNode
			root.putObject("rotationCorrection").put("enabled", false)
			ObjectMapper().writeValue(path.toFile(), root)
			assertNull(MonakaConfiguration.load(path).rotationCorrection)
		} finally { Files.deleteIfExists(path) }
	}

	@Test fun explicitFramesAndProvisionalTuningRoundTripWithoutGuessingMounting() {
		val path = Files.createTempFile("monaka-correction-on", ".json")
		try {
			val config = MonakaConfiguration(space, assignments(), port = 0, backgroundIkSharedSpace = space,
				rotationCorrection = RotationCorrectionConfig(RotationCorrectionFrames(
					Quaternion.rotationAroundYAxis(.2f), Quaternion.IDENTITY, space, true), tuning()))
			config.save(path)
			assertEquals(config.rotationCorrection, MonakaConfiguration.load(path).rotationCorrection)
			val root = ObjectMapper().readTree(path.toFile()) as com.fasterxml.jackson.databind.node.ObjectNode
			(root["rotationCorrection"] as com.fasterxml.jackson.databind.node.ObjectNode).remove("framesConfirmed")
			ObjectMapper().writeValue(path.toFile(), root)
			assertFails { MonakaConfiguration.load(path) }
			(root["rotationCorrection"] as com.fasterxml.jackson.databind.node.ObjectNode).put("framesConfirmed", true)
			(root["rotationCorrection"] as com.fasterxml.jackson.databind.node.ObjectNode).remove("mainTrackerToBodyWxyz")
			ObjectMapper().writeValue(path.toFile(), root)
			assertFails { MonakaConfiguration.load(path) }
		} finally { Files.deleteIfExists(path) }
	}

	@Test fun enabledCorrectionWithoutSharedSpaceAssertionFailsClosed() {
		assertFailsWith<IllegalArgumentException> {
			MonakaConfiguration(space, assignments(), rotationCorrection = RotationCorrectionConfig(
				RotationCorrectionFrames(Quaternion.IDENTITY, Quaternion.IDENTITY, space, true), tuning()))
		}
	}

	@Test fun enabledCorrectionRequiresConfirmedFramesAndPhysicalFallbackAssignment() {
		assertFailsWith<IllegalArgumentException> {
			RotationCorrectionFrames(Quaternion.IDENTITY, Quaternion.IDENTITY, space, false)
		}
		val wrongAssignment = TrackerBodyAssignments().also {
			it.configure(TrackerPosition.HIP, TrackerReference.mtp(LogicalTracker("source", "main", "publisher")),
				TrackerReference.mtp(LogicalTracker("source", "fallback", "publisher")), OutputMode.HYBRID)
		}
		assertFailsWith<IllegalArgumentException> {
			MonakaConfiguration(space, wrongAssignment, backgroundIkSharedSpace = space,
				rotationCorrection = RotationCorrectionConfig(
					RotationCorrectionFrames(Quaternion.IDENTITY, Quaternion.IDENTITY, space, true), tuning()))
		}
	}
}
