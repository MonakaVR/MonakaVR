package dev.monaka.tracking

import com.google.gson.JsonParser
import com.google.gson.GsonBuilder
import dev.monaka.protocol.v2.*
import dev.monaka.tracking.mtp.*
import dev.monaka.tracking.diagnostic.*
import dev.slimevr.tracking.trackers.TrackerPosition
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RuntimeDiagnosticTests {
	private fun fixture() = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
	private fun bytes(value: Envelope) = (MonakaCodec.encodeEnvelope(value) as EncodeResult.Success).value
	private fun output(name: String) = File(System.getProperty("monaka.diagnostics.output"), name).also { it.parentFile.mkdirs() }

	@Test fun actualAdmissionPreservesIdentityRoleValidityDuplicatesAndReconnectWithoutChangingCache() {
		val events = mutableListOf<String>()
		val writer = DiagnosticWriter({ events.add(it) }, 4096)
		val observer = RuntimeDiagnosticObserver(writer)
		val pose = fixture()
		val key = LogicalTracker(pose.source_id, pose.tracker_id, pose.publisher_id)
		val assignments = TrackerBodyAssignments(mapOf(key to TrackerPosition.HIP))
		val inboxOn = MtpInbox(); val inboxOff = MtpInbox()
		val on = MtpObservationBackend(inboxOn, assignments, pose.coordinate_space, diagnostic = observer)
		val off = MtpObservationBackend(inboxOff, assignments, pose.coordinate_space, diagnostic = null)
		writer.emit("component_started"); writer.emit("component_ready", details = mapOf("inventory_complete" to false))
		fun submit(p: MtpPose, now: Long) {
			assertEquals(inboxOff.receive(bytes(p), now), inboxOn.receive(bytes(p), now))
			assertEquals(off.poll(now), on.poll(now))
			assertEquals(off.samples(), on.samples())
			assertEquals(inboxOff.diagnostics(), inboxOn.diagnostics())
			Thread.sleep(5)
		}
		val rotation = pose.copy(modality = "rotation_only", validity = Validity(false, true), confidence = Confidence(0.0, 0.5), tracking_state = "degraded")
		submit(rotation, 1_000_000_000); submit(rotation, 1_010_000_000)
		submit(pose.copy(sequence = pose.sequence + 1), 1_020_000_000)
		on.poll(1_600_000_000); off.poll(1_600_000_000); Thread.sleep(5)
		submit(pose.copy(sequence = 0, session_id = "55555555-5555-5555-5555-555555555555"), 1_610_000_000)
		on.close(); off.close(); writer.emit("fatal_error", details = mapOf("reason" to "mock")); writer.close()
		assertEquals(0, writer.criticalDropped.get())
		val parsed = events.map { JsonParser.parseString(it).asJsonObject }
		fun count(type: String) = parsed.count { it["event_type"].asString == type }
		assertEquals(2, count("first_pose")); assertEquals(2, count("first_position_valid"))
		assertEquals(1, count("session_reconnecting")); assertTrue(count("diagnostic_warning") >= 1)
		assertEquals(1, count("fatal_error"))
		val observed = parsed.first { it["event_type"].asString == "pose_observed" }
		assertEquals("protocol_tracker_id", observed["tracker_identity"].asJsonObject["domain"].asString)
		assertEquals(key.trackerId, observed["tracker_identity"].asJsonObject["value"].asString)
		assertEquals("configured_role", observed["role"].asJsonObject["role_source"].asString)
		assertEquals("HIP", observed["role"].asJsonObject["role_value"].asString)
		assertFalse(observed["validity"].asJsonObject["position_valid"].asBoolean)
		assertTrue(observed["validity"].asJsonObject["orientation_valid"].asBoolean)
		assertTrue(parsed.zipWithNext().all { (a, b) -> a["monotonic_ns"].asLong <= b["monotonic_ns"].asLong })
		output("events.jsonl").writeText(events.joinToString("\n", postfix = "\n"))
	}

	@Test fun slowSinkDoesNotBlockProducerAndRetainsFatalByEvictingPoses() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1); val events = mutableListOf<String>()
		val writer = DiagnosticWriter({ entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); events.add(it) }, 8)
		writer.emit("component_started"); assertTrue(entered.await(2, TimeUnit.SECONDS))
		val start = System.nanoTime()
		repeat(10000) { writer.emit("pose_observed", critical = false) }
		writer.emit("fatal_error")
		val elapsed = System.nanoTime() - start
		assertTrue(elapsed < 1_000_000_000, "Producer waited on stalled sink")
		assertTrue(writer.dropped.get() > 0); assertEquals(0, writer.criticalDropped.get())
		release.countDown(); writer.close()
		assertTrue(events.any { JsonParser.parseString(it).asJsonObject["event_type"].asString == "fatal_error" })
	}

	@Test fun provenanceAndOffIdleActivePerformanceSmoke() {
		val metadata = DiagnosticWriter.build()
		assertTrue((metadata["git_commit"] as String).matches(Regex("[0-9a-f]{40}")))
		assertNotNull(metadata["dirty_at_build"]); assertNotNull(metadata["build_timestamp_utc"])
		output("build.json").writeText(DiagnosticWriter.buildJson())
		val pose = fixture(); val assignments = TrackerBodyAssignments()
		val metrics = linkedMapOf<String, Any?>()
		for (mode in listOf("off_active", "on_idle", "on_active")) {
			val sink = output("perf-$mode.jsonl").bufferedWriter()
			var events = 0L; var maxWriteNs = 0L
			val writer = if (mode != "off_active") DiagnosticWriter({ line ->
				val start = System.nanoTime(); sink.write(line); sink.newLine(); sink.flush()
				maxWriteNs = maxOf(maxWriteNs, System.nanoTime() - start); events++
			}) else null
			val inbox = MtpInbox()
			val backend = MtpObservationBackend(inbox, assignments, pose.coordinate_space, diagnostic = writer?.let(::RuntimeDiagnosticObserver))
			val process = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
			val cpu = process.processCpuTime; val start = System.nanoTime(); var maxCallNs = 0L
			repeat(5000) { index ->
				val lap = System.nanoTime()
				if (mode != "on_idle") inbox.receive(bytes(pose.copy(sequence = index.toLong())), 1_000_000_000 + index * 1_000_000L)
				backend.poll(1_000_000_000 + index * 1_000_000L)
				maxCallNs = maxOf(maxCallNs, System.nanoTime() - lap)
			}
			val duration = System.nanoTime() - start
			val producerCpu = process.processCpuTime - cpu
			backend.close(); writer?.close(); sink.close()
			metrics[mode] = mapOf("iterations" to 5000, "producer_wall_ns" to duration, "process_cpu_ns" to producerCpu,
				"producer_max_ns" to maxCallNs, "heap_used_bytes" to ManagementFactory.getMemoryMXBean().heapMemoryUsage.used,
				"events" to events, "event_rate_hz" to events * 1e9 / duration, "write_max_ns" to maxWriteNs,
				"dropped_events" to (writer?.dropped?.get() ?: 0), "critical_dropped_events" to (writer?.criticalDropped?.get() ?: 0))
		}
		output("performance.json").writeText(GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(metrics))
	}
}
