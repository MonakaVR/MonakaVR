package dev.monaka.tracking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.flatbuffers.FlatBufferBuilder
import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.slimevr.protocol.datafeed.createTrackerId
import dev.slimevr.tracking.trackers.Device
import dev.slimevr.tracking.trackers.DeviceOrigin
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import solarxr_protocol.datatypes.TrackerId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlimeCandidateLocatorTests {
	private val mapper = ObjectMapper()
	private val now = 1_000_000_000L
	private fun pose() = (MonakaCodec.decodeEnvelope(Path.of(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").toFile().readBytes()) as DecodeResult.Success).value as MtpPose
	private fun device(identifier: Int) = object : Device(DeviceOrigin.SOLARXR) {
		override val id = identifier
	}
	private fun tracker(name: String, number: Int, device: Device? = null, rotation: Boolean = true) = Tracker(
		device,
		number,
		name,
		displayName = "Same visible label",
		trackerPosition = null,
		trackerNum = number,
		hasRotation = rotation,
	)
	private fun snapshot(api: MonakaInputConfigurationApi): JsonNode = mapper.readTree(
		api.handleJson("""{"type":"monaka_inputs_v1","id":"snapshot","action":"snapshot"}""", now),
	).also { assertTrue(it["ok"].asBoolean(), it.toString()) }
	private fun withApi(dir: Path, trackers: () -> List<Tracker>, action: (MonakaInputConfigurationApi, MonakaRuntime, Path) -> Unit) {
		val p = pose()
		val config = MonakaConfiguration(p.coordinate_space)
		val path = dir.resolve("inputs.json")
		config.save(path)
		MonakaRuntime(trackers, p.coordinate_space, config.assignments, clock = { now }).use { runtime ->
			action(MonakaInputConfigurationApi(config, runtime.mtp, trackers, path), runtime, path)
		}
	}

	@Test fun locatorMatchesRealSolarXrEncodingAndDoesNotUseDisplayNames(@TempDir dir: Path) {
		val inputs = listOf(tracker("persistent-a", 3, device(12)), tracker("persistent-b", 3), tracker("persistent-c", 3, device(0)))
		withApi(dir, { inputs }) { api, runtime, _ ->
			val p = pose()
			assertTrue(runtime.inbox.receive((MonakaCodec.encodeEnvelope(p) as EncodeResult.Success).value, now))
			runtime.tick()
			val rows = snapshot(api)["candidates"]
			for (tracker in inputs) {
				val row = rows.single { it["id"].asText() == "slime:${tracker.name}" }
				val locator = row["slimeTrackerId"]
				val buffer = FlatBufferBuilder(64)
				buffer.finish(createTrackerId(buffer, tracker))
				val wire = TrackerId.getRootAsTrackerId(buffer.dataBuffer())
				assertEquals(wire.trackerNum(), locator["trackerNum"].asInt())
				if (wire.deviceId() == null) {
					assertTrue(locator["deviceId"].isNull)
				} else {
					assertEquals(wire.deviceId()!!.id(), locator["deviceId"]["id"].asInt())
				}
				assertEquals(tracker.name, row["tracker"].asText())
			}
			assertTrue(rows.single { it["origin"].asText() == "mvip_pose" }["slimeTrackerId"].isNull)
			assertEquals(3, rows.filter { it["origin"].asText() == "slime_orientation_stream" }.map { it["slimeTrackerId"] }.distinct().size)
		}
	}

	@Test fun ambiguousNamesAndLocatorsNeverResolveToAChosenTracker(@TempDir dir: Path) {
		val inputs = listOf(
			tracker("duplicate-name", 1),
			tracker("duplicate-name", 2),
			tracker("visible", 3),
			tracker("not-in-catalog", 3, rotation = false),
			tracker("monaka-private:hidden", 4),
			tracker("same-as-excluded-locator", 4),
		)
		withApi(dir, { inputs }) { api, _, _ ->
			val rows = snapshot(api)["candidates"]
			assertEquals(3, rows.size())
			assertTrue(rows.all { it["slimeTrackerId"].isNull })
			val duplicate = rows.single { it["tracker"].asText() == "duplicate-name" }
			assertTrue(duplicate["conflict"].asBoolean())
			assertFalse(duplicate["qSelectable"].asBoolean())
		}
	}

	@ParameterizedTest
	@ValueSource(ints = [-1, 256, 65536])
	fun nonRepresentableIdsAreNotTruncated(invalid: Int, @TempDir dir: Path) {
		val inputs = listOf(tracker("bad-number", invalid), tracker("bad-device", 1, device(invalid)))
		withApi(dir, { inputs }) { api, _, _ ->
			assertTrue(snapshot(api)["candidates"].all { it["slimeTrackerId"].isNull })
		}
	}

	@Test fun oldSaveRequestStillPersistsIdentityAndManualClearAcrossLocatorChange(@TempDir dir: Path) {
		var inputs = listOf(tracker("persistent", 7, device(8)))
		withApi(dir, { inputs }) { api, runtime, path ->
			val result = mapper.readTree(
				api.handleJson("""{"type":"monaka_inputs_v1","id":"save","action":"save","body":"HIP","qSource":"slime:persistent","sixDofSource":null,"manualSix":true,"generation":"0"}""", now),
			)
			assertTrue(result["ok"].asBoolean(), result.toString())
			assertEquals("slime:persistent", result["assignments"].single()["qSource"].asText())
			assertTrue(result["assignments"].single()["manualSix"].asBoolean())
			assertEquals(TrackerReference.slime("persistent"), runtime.assignments.snapshot().targets.getValue(TrackerPosition.HIP).sourceSelection!!.qSource)
			assertFalse(Files.readString(path).contains("slimeTrackerId"))
			inputs = listOf(tracker("persistent", 9, device(10)))
			val restored = MonakaConfiguration.load(path)
			MonakaRuntime({ inputs }, restored.space, restored.assignments, clock = { now }).use { restarted ->
				val after = snapshot(MonakaInputConfigurationApi(restored, restarted.mtp, { inputs }, path))
				assertEquals(result["assignments"], after["assignments"])
				assertEquals(9, after["candidates"].single()["slimeTrackerId"]["trackerNum"].asInt())
				assertEquals(10, after["candidates"].single()["slimeTrackerId"]["deviceId"]["id"].asInt())
				assertNull(restored.assignments.snapshot().targets.getValue(TrackerPosition.HIP).sourceSelection!!.sixDofSource)
			}
		}
	}
}
