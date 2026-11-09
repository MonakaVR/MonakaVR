package dev.monaka.tracking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.slimevr.tracking.trackers.TrackerPosition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingOverrideConfigurationTests {
	private val mapper = ObjectMapper()
	private val now = 1_000_000_000L
	private val editor = "tracking-override-test-editor"
	private val left = TrackingOverrideHand.LEFT
	private val right = TrackingOverrideHand.RIGHT

	private fun pose() = (
		MonakaCodec.decodeEnvelope(Files.readAllBytes(Path.of(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json"))) as DecodeResult.Success
		).value as MtpPose

	private fun request(api: MonakaInputConfigurationApi, vararg fields: Pair<String, Any?>): JsonNode = mapper.readTree(
		api.handleJson(mapper.writeValueAsString(mapOf("type" to "monaka_inputs_v1", "id" to "override-test") + fields), now),
	)

	private fun snapshot(api: MonakaInputConfigurationApi) = request(api, "action" to "snapshot")

	private fun save(
		api: MonakaInputConfigurationApi,
		hand: String,
		token: JsonNode,
		source: String?,
		enabled: Boolean = true,
		secret: String? = editor,
		confirmed: Boolean = true,
	): JsonNode = request(
		api,
		"action" to "save_override",
		"hand" to hand,
		"readbackToken" to token,
		"editorToken" to secret,
		"selection" to mapOf(
			"enabled" to enabled,
			"source" to source,
			"gripOffset" to mapOf("positionM" to listOf(0.05, -0.01, 0), "rotationWxyz" to listOf(1, 0, 0, 0)),
			"sameUniverseConfirmed" to confirmed,
		),
	)

	@Test
	fun catalogChoicesPersistIndependentlyOfBodyAssignmentsAndReload(@TempDir dir: Path) {
		val p = pose()
		val source = TrackerReference.mtp(LogicalTracker(p.source_id, p.tracker_id, p.publisher_id))
		val assignments = TrackerBodyAssignments().also { it.configureSources(TrackerPosition.HIP, source, source, true) }
		val path = dir.resolve("input.json")
		MonakaConfiguration(p.coordinate_space, assignments).save(path)
		val document = mapper.readTree(path.toFile()) as ObjectNode
		document.put("unrelated", true)
		Files.writeString(path, document.toString())
		val configuration = MonakaConfiguration.load(path)
		MonakaRuntime({ emptyList() }, p.coordinate_space, configuration.assignments, clock = { now }).use { runtime ->
			val api = MonakaInputConfigurationApi(
				configuration, runtime.mtp, { emptyList() }, path,
				trackingOverrideRegisteredHands = { setOf(left) },
				overrideEditorToken = editor,
			)
			val initial = snapshot(api)
			assertTrue(initial["ok"].asBoolean())
			assertFalse(initial.at("/trackingOverride/hands/left/saved/enabled").asBoolean())
			assertEquals(left.devicePath, initial.at("/trackingOverride/hands/left/registeredDevicePath").asText())
			assertTrue(initial.at("/trackingOverride/hands/right/registeredDevicePath").isNull)
			assertTrue(runtime.inbox.receive((MonakaCodec.encodeEnvelope(p) as EncodeResult.Success).value, now))
			runtime.tickSnapshot()
			val chosen = snapshot(api)
			assertTrue(chosen["candidates"].single()["sixDofSelectable"].asBoolean(), chosen.toString())
			val leftSaved = save(api, "left", chosen["readbackToken"], source.observationId)
			assertTrue(leftSaved["ok"].asBoolean(), leftSaved.toString())
			assertFalse(leftSaved.at("/trackingOverride/hands/right/saved/enabled").asBoolean())
			val rightSaved = save(api, "right", leftSaved["readbackToken"], source.observationId)
			assertTrue(rightSaved["ok"].asBoolean(), rightSaved.toString())
			assertEquals(setOf(left, right), api.trackingOverrideSelections().keys)
			assertTrue(api.trackingOverrideSelections().values.all { it.source == source && it.enabled })
			assertEquals(configuration.assignments.snapshot().targets, assignments.snapshot().targets)

			val reloaded = MonakaConfiguration.load(path)
			assertEquals(api.trackingOverrideSelections(), reloaded.trackingOverride)
			val after = mapper.readTree(path.toFile())
			assertEquals(document["assignments"], after["assignments"])
			assertEquals(document["unrelated"], after["unrelated"])
			val bodyCleared = request(
				api, "action" to "save", "body" to "HIP", "generation" to rightSaved["generation"],
				"qSource" to null, "sixDofSource" to null, "manualSix" to true,
			)
			assertTrue(bodyCleared["ok"].asBoolean(), bodyCleared.toString())
			assertTrue(MonakaConfiguration.load(path).assignments.snapshot().targets.isEmpty())
			assertEquals(api.trackingOverrideSelections(), MonakaConfiguration.load(path).trackingOverride)
			val released = save(api, "left", bodyCleared["readbackToken"], source.observationId, enabled = false)
			assertTrue(released["ok"].asBoolean(), released.toString())
			assertFalse(api.trackingOverrideSelections().getValue(left).enabled)
			assertTrue(api.trackingOverrideSelections().getValue(right).enabled)
		}
	}

	@Test
	fun invalidEditorStaleReviewAndUnavailableSourcesLeaveExactConfigBytes(@TempDir dir: Path) {
		val p = pose()
		val config = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("input.json")
		config.save(path)
		val before = Files.readAllBytes(path)
		MonakaRuntime({ emptyList() }, p.coordinate_space, clock = { now }).use { runtime ->
			val api = MonakaInputConfigurationApi(config, runtime.mtp, { emptyList() }, path, overrideEditorToken = editor)
			val candidate = "missing"
			assertFalse(save(api, "left", snapshot(api)["readbackToken"], candidate, secret = null)["ok"].asBoolean())
			assertFalse(save(api, "left", mapper.valueToTree("stale"), candidate)["ok"].asBoolean())
			assertFalse(save(api, "left", snapshot(api)["readbackToken"], candidate)["ok"].asBoolean())
			assertFalse(save(api, "head", snapshot(api)["readbackToken"], null, enabled = false)["ok"].asBoolean())
			assertContentEquals(before, Files.readAllBytes(path))
			assertTrue(api.trackingOverrideSelections().isEmpty())
		}
	}
}
