package dev.monaka.tracking.desktop

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import dev.monaka.protocol.v2.*
import dev.monaka.tracking.*
import dev.slimevr.tracking.trackers.TrackerPosition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MtpHilCaptureTests {
	@TempDir lateinit var temporary: Path
	private fun parse(text: String): JsonElement = JsonParser.parseString(text)
	private operator fun JsonElement.get(name: String): JsonElement = asJsonObject.get(name)
	private operator fun JsonElement.get(index: Int): JsonElement = asJsonArray.get(index)
	private fun fixture(name: String) = assertIs<DecodeResult.Success>(
		MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/$name.json").readBytes()),
	).value
	private fun bytes(p: Envelope) = assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(p)).value
	private fun config(p: MtpPose, port: Int = 0) = MonakaConfiguration(
		p.coordinate_space,
		TrackerBodyAssignments(mapOf(LogicalTracker(p.source_id, p.tracker_id, p.publisher_id) to TrackerPosition.HIP)),
		port = port,
	)

	@Test fun captureLogsActualMainRotationAndTimeoutWithoutChangingWireOrRefreshingDuplicate() {
		val p = assertIs<MtpPose>(fixture("mtp-pose"))
		var now = 1_000_000_000L
		MtpHilCaptureSession(config(p), temporary, { now }).use { capture ->
			capture.observation(bytes(fixture("observation")))
			capture.mtp(bytes(p), "peer")
			now += 10_000_000
			val rotation = p.copy(sequence = 1, modality = "rotation_only", position = null,
				validity = Validity(false, true), confidence = Confidence(0.0, 1.0), tracking_state = "degraded")
			capture.mtp(bytes(rotation), "peer")
			now += 400_000_000
			capture.mtp(bytes(rotation), "peer")
			now += 100_000_001
			capture.tick()
		}
		val states = Files.readAllLines(temporary.resolve("constraints.jsonl")).map { parse(it)["state"] }
		assertFalse(states.first()["constraints"][0]["position"].isJsonNull)
		val rotation = states[1]["constraints"][0]
		assertTrue(rotation["position"].isJsonNull)
		assertFalse(rotation["orientation_xyzw"].isJsonNull)
		assertEquals(rotation["main"], rotation["rotation_source"])
		assertTrue(rotation["external_fallback"].isJsonNull)
		assertTrue(states.last()["constraints"][0]["orientation_xyzw"].isJsonNull)
		assertEquals(1, states.last()["diagnostics"]["DuplicateOrOldSequence"].asInt)
		assertEquals(parse(bytes(p).toString(Charsets.UTF_8)), parse(Files.readAllLines(temporary.resolve("mtp.jsonl")).first())["wire"])
		assertTrue(parse(Files.readAllLines(temporary.resolve("observation.jsonl")).single())["codec_valid"].asBoolean)
	}

	@Test fun diagnosticProcessCapturesTwoUdpPortsAndRefusesExistingOutputDirectory() {
		val p = assertIs<MtpPose>(fixture("mtp-pose"))
		val cfg = temporary.resolve("config.json"); config(p).save(cfg)
		val original = Files.readAllBytes(cfg)
		val output = temporary.resolve("capture")
		fun process(log: String) = ProcessBuilder(
			File(System.getProperty("java.home"), "bin/java.exe").absolutePath,
			"-cp", System.getProperty("monaka.test.classpath"), "dev.monaka.tracking.desktop.MtpHilCapture",
			cfg.toString(), output.toString(), "2", "0",
		).redirectErrorStream(true).redirectOutput(temporary.resolve(log).toFile()).start()
		val child = process("capture.log")
		try {
			val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
			val manifest = output.resolve("capture.json")
			var ports: JsonElement? = null
			while (ports == null) {
				assertTrue(child.isAlive && System.nanoTime() < deadline, "Capture failed: ${temporary.resolve("capture.log").toFile().readText()}")
				ports = try { parse(Files.readString(manifest)).takeIf { it.isJsonObject && it.asJsonObject.has("mtp_port") } } catch (_: Exception) { null }
				Thread.sleep(5)
			}
			DatagramSocket().use { sender ->
				for ((message, port) in listOf(p to ports["mtp_port"].asInt, fixture("observation") to ports["observation_mirror_port"].asInt)) {
					val wire = bytes(message)
					sender.send(DatagramPacket(wire, wire.size, InetAddress.getLoopbackAddress(), port))
				}
			}
			assertTrue(child.waitFor(10, TimeUnit.SECONDS))
			assertEquals(0, child.exitValue(), temporary.resolve("capture.log").toFile().readText())
			assertEquals(1, Files.readAllLines(output.resolve("mtp.jsonl")).size)
			assertEquals(1, Files.readAllLines(output.resolve("observation.jsonl")).size)
			assertContentEquals(original, Files.readAllBytes(cfg))
			val before = Files.readAllBytes(output.resolve("mtp.jsonl"))
			val duplicate = process("duplicate.log")
			try {
				assertTrue(duplicate.waitFor(10, TimeUnit.SECONDS)); assertNotEquals(0, duplicate.exitValue())
			} finally { if (duplicate.isAlive) duplicate.destroyForcibly().waitFor() }
			assertContentEquals(before, Files.readAllBytes(output.resolve("mtp.jsonl")))
		} finally { if (child.isAlive) child.destroyForcibly().waitFor() }
	}

	@Test fun mismatchedLogicalTrackerIsDiagnosticOnlyAndCannotDriveHip() {
		val p = assertIs<MtpPose>(fixture("mtp-pose"))
		MtpHilCaptureSession(config(p), temporary, { 1_000_000_000 }).use { capture ->
			capture.mtp(bytes(p.copy(tracker_id = "different-logical-tracker")), "peer")
		}
		val state = parse(Files.readAllLines(temporary.resolve("constraints.jsonl")).single())["state"]
		assertEquals(1, state["diagnostics"]["UnassignedPose"].asInt)
		assertTrue(state["constraints"][0]["position"].isJsonNull)
		assertTrue(state["constraints"][0]["orientation_xyzw"].isJsonNull)
	}
}
