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
		enabled: Boolean = true,
		secret: String? = editor,
		confirmed: Boolean = true,
		staleSourceField: String? = null,
	): JsonNode = request(
		api,
		"action" to "save_override",
		"hand" to hand,
		"readbackToken" to token,
		"editorToken" to secret,
		"selection" to mapOf(
			"enabled" to enabled,
			"gripOffset" to if (confirmed) grip() else null,
			"sameUniverseConfirmed" to confirmed,
		) + (staleSourceField?.let { mapOf("source" to it) } ?: emptyMap()),
	)

	private fun grip() = mapOf("positionM" to listOf(0.05, -0.01, 0), "rotationWxyz" to listOf(1, 0, 0, 0))
	private fun MtpPose.reference() = TrackerReference.mtp(LogicalTracker(source_id, tracker_id, publisher_id))
	private fun MonakaRuntime.feed(pose: MtpPose) = assertTrue(inbox.receive((MonakaCodec.encodeEnvelope(pose) as EncodeResult.Success).value, now))
	private fun saveBody(api: MonakaInputConfigurationApi, state: JsonNode, hand: TrackingOverrideHand, q: TrackerReference?, six: TrackerReference?) =
		request(
			api, "action" to "save", "body" to hand.body.name, "generation" to state["generation"],
			"qSource" to q?.observationId, "sixDofSource" to six?.observationId, "manualSix" to true,
		)

	@Test
	fun usesOnlyNormalHandAssignmentAndRequiresFreshCalibrationOnReplacement(@TempDir dir: Path) {
		val p = pose()
		val other = p.copy(publisher_id = "right-publisher")
		val replacement = p.copy(publisher_id = "replacement-publisher", position = listOf(11.0, 12.0, 13.0))
		val source = p.reference()
		val assignments = TrackerBodyAssignments().also {
			it.configureSources(TrackerPosition.LEFT_HAND, source, source, true)
			it.configureSources(TrackerPosition.RIGHT_HAND, null, other.reference(), true)
		}
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
			assertEquals(source.observationId, initial.at("/trackingOverride/hands/left/source").asText())
			assertFalse(initial.at("/trackingOverride/hands/left/canEnable").asBoolean())
			assertEquals(left.devicePath, initial.at("/trackingOverride/hands/left/registeredDevicePath").asText())
			assertTrue(initial.at("/trackingOverride/hands/right/registeredDevicePath").isNull)
			runtime.feed(p)
			runtime.feed(other)
			runtime.feed(replacement)
			runtime.tickSnapshot()
			val chosen = snapshot(api)
			assertTrue(chosen.at("/trackingOverride/hands/left/canEnable").asBoolean())
			val leftSaved = save(api, "left", chosen["readbackToken"])
			assertTrue(leftSaved["ok"].asBoolean(), leftSaved.toString())
			assertTrue(leftSaved.at("/trackingOverride/hands/left/calibrationCurrent").asBoolean())
			assertTrue(leftSaved.at("/trackingOverride/hands/left/saved/source").isMissingNode)
			assertFalse(leftSaved.at("/trackingOverride/hands/right/saved/enabled").asBoolean())
			val rightSaved = save(api, "right", leftSaved["readbackToken"])
			assertTrue(rightSaved["ok"].asBoolean(), rightSaved.toString())
			assertEquals(setOf(left, right), api.trackingOverrideSelections().keys)
			assertTrue(api.trackingOverrideSelections().values.all { it.enabled && it.confirmedAssignment != null })
			assertEquals(configuration.assignments.snapshot().targets, assignments.snapshot().targets)

			val reloaded = MonakaConfiguration.load(path)
			assertEquals(api.trackingOverrideSelections(), reloaded.trackingOverride)
			val after = mapper.readTree(path.toFile())
			assertEquals(document["assignments"], after["assignments"])
			assertEquals(document["unrelated"], after["unrelated"])
			assertFalse(after.at("/trackingOverride/left").has("source"))
			fun frame(hand: TrackingOverrideHand) =
				runtime.trackingOverrideFrames(runtime.tickSnapshot(), api.trackingOverrideSelections()).getValue(hand)
			assertEquals(1.05f, frame(left).pose?.position?.x)

			val newSource = replacement.reference()
			val bodyChanged = saveBody(api, rightSaved, left, source, newSource)
			assertTrue(bodyChanged["ok"].asBoolean(), bodyChanged.toString())
			assertEquals(newSource.observationId, bodyChanged.at("/trackingOverride/hands/left/source").asText())
			assertTrue(bodyChanged.at("/trackingOverride/hands/left/saved/enabled").asBoolean())
			assertFalse(bodyChanged.at("/trackingOverride/hands/left/calibrationCurrent").asBoolean())
			assertEquals(TrackingOverrideSourceStatus.UNCONFIRMED_UNIVERSE, frame(left).status)
			assertEquals(TrackingOverrideSourceStatus.FULL, frame(right).status)
			val reconfirmed = save(api, "left", bodyChanged["readbackToken"])
			assertTrue(reconfirmed["ok"].asBoolean(), reconfirmed.toString())
			assertEquals(11.05f, frame(left).pose?.position?.x)

			val bodyCleared = saveBody(api, reconfirmed, left, source, null)
			assertTrue(bodyCleared["ok"].asBoolean(), bodyCleared.toString())
			assertTrue(bodyCleared.at("/trackingOverride/hands/left/source").isNull)
			assertEquals(TrackingOverrideSourceStatus.UNASSIGNED, frame(left).status)
			val released = save(api, "left", bodyCleared["readbackToken"], enabled = false, confirmed = false)
			assertTrue(released["ok"].asBoolean(), released.toString())
			assertFalse(api.trackingOverrideSelections().getValue(left).enabled)
			assertTrue(api.trackingOverrideSelections().getValue(right).enabled)
		}
	}

	@Test
	fun legacyCalibrationOnlyMigratesAgainstTheAuthoritativeLoadedAssignment(@TempDir dir: Path) {
		val p = pose()
		val source = p.reference()
		val leftPose = p.copy(publisher_id = "left-current-publisher")
		val assignments = TrackerBodyAssignments().also {
			it.configureSources(TrackerPosition.LEFT_HAND, null, leftPose.reference(), true)
		}
		val path = dir.resolve("input.json")
		MonakaConfiguration(p.coordinate_space, assignments).save(path)
		fun old(pose: MtpPose) = mapOf(
			"enabled" to true,
			"source" to mapOf("kind" to "mtp", "publisher_id" to pose.publisher_id, "source_id" to pose.source_id, "tracker_id" to pose.tracker_id),
			"gripOffset" to grip(),
			"sameUniverseConfirmed" to true,
		)
		val document = mapper.readTree(path.toFile()) as ObjectNode
		document.set<JsonNode>("trackingOverride", mapper.valueToTree(mapOf("left" to old(leftPose), "right" to old(p))))
		val original = mapper.writeValueAsBytes(document)
		Files.write(path, original)
		val config = MonakaConfiguration.load(path)
		assertContentEquals(original, Files.readAllBytes(path))
		assertTrue(config.trackingOverride.getValue(left).enabled)
		assertTrue(config.trackingOverride.getValue(left).calibrationCurrent(left, leftPose.reference(), config.space))
		assertTrue(config.trackingOverride.getValue(right).enabled)
		assertEquals(config.trackingOverride.getValue(left).gripOffset, config.trackingOverride.getValue(right).gripOffset)
		assertFalse(config.trackingOverride.getValue(right).calibrationCurrent(right, source, config.space))

		MonakaRuntime({ emptyList() }, config.space, config.assignments, clock = { now }).use { runtime ->
			runtime.feed(p)
			runtime.feed(leftPose)
			runtime.tickSnapshot()
			val api = MonakaInputConfigurationApi(config, runtime.mtp, { emptyList() }, path, overrideEditorToken = editor)
			val saved = saveBody(api, snapshot(api), right, null, source)
			assertTrue(saved["ok"].asBoolean(), saved.toString())
			val reloaded = MonakaConfiguration.load(path)
			assertTrue(reloaded.trackingOverride.getValue(right).enabled)
			assertFalse(reloaded.trackingOverride.getValue(right).calibrationCurrent(right, source, config.space))
			assertFalse(mapper.readTree(path.toFile()).at("/trackingOverride/right").has("source"))
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
			assertFalse(save(api, "left", snapshot(api)["readbackToken"], secret = null)["ok"].asBoolean())
			assertFalse(save(api, "left", mapper.valueToTree("stale"))["ok"].asBoolean())
			assertFalse(save(api, "left", snapshot(api)["readbackToken"])["ok"].asBoolean())
			assertFalse(save(api, "left", snapshot(api)["readbackToken"], staleSourceField = p.reference().observationId)["ok"].asBoolean())
			assertFalse(save(api, "head", snapshot(api)["readbackToken"], enabled = false)["ok"].asBoolean())
			assertContentEquals(before, Files.readAllBytes(path))
			assertTrue(api.trackingOverrideSelections().isEmpty())
		}
	}
}
