package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v2.*
import dev.monaka.tracking.*
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class QJumpIntegrationTests {
	private val mapper = ObjectMapper()
	private fun await(test: () -> Boolean) { val until = System.nanoTime() + 3_000_000_000; while (!test() && System.nanoTime() < until) Thread.sleep(1); assertTrue(test()) }
	private val targets = listOf(TrackerPosition.CHEST, TrackerPosition.LEFT_UPPER_LEG, TrackerPosition.LEFT_LOWER_LEG, TrackerPosition.RIGHT_UPPER_LEG, TrackerPosition.RIGHT_LOWER_LEG)
	private data class Result(val poses: List<List<Pair<Vector3, Quaternion>>>, val records: List<com.fasterxml.jackson.databind.JsonNode>, val oldReads: List<Int>, val resetEvents: Int)
	private fun run(enabled: Boolean, realtime: Boolean = false): Result {
		val fixture = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
		val assignments = TrackerBodyAssignments()
		val packets = targets.mapIndexed { i, target -> fixture.copy(publisher_id = if (i % 2 == 0) "opaque-publisher-A" else "opaque-publisher-B", tracker_id = "five-$i", input = fixture.input.copy(device_id = "body-$i")).also { assignments.configureSources(target, TrackerReference.mtp(LogicalTracker(it.source_id, it.tracker_id, it.publisher_id)), null, true) } }
		fun tracker(id: Int, target: TrackerPosition, p: Vector3) = Tracker(null, id, "qjump-test-$id", trackerPosition = target, hasPosition = true, hasRotation = true, isHmd = target == TrackerPosition.HEAD, trackRotDirection = false).also { it.position = p; it.status = TrackerStatus.OK; it.setRotation(Quaternion.rotationAroundYAxis(.3f)) }
		val raw = listOf(tracker(1, TrackerPosition.HEAD, Vector3(0f, 1.7f, 0f)), tracker(2, TrackerPosition.LEFT_HAND, Vector3(-.3f, 1.2f, -.3f)), tracker(3, TrackerPosition.RIGHT_HAND, Vector3(.3f, 1.2f, -.3f)))
		val manager = HumanPoseManager(raw).also { it.setLegTweaksEnabled(false); it.update() }
		val clock = AtomicLong(1_000_000_000)
		val realStart = System.nanoTime()
		val now: () -> Long = if (realtime) System::nanoTime else clock::get
		val root = File(System.getProperty("monaka.fixtures")).parentFile.parentFile.parentFile.toPath()
		val out = root.resolve("Build/diagnostics/qjump/five-target")
		val recorder = if (enabled) {
			val config = QJumpRecorder.Config(out, out.resolve("coord-${java.util.UUID.randomUUID()}"))
			if (realtime) QJumpRecorder(config, sourceIdentity = mapOf("test_fixture" to "five targets real clock, requested sleep 1ms, no actor await"))
			else QJumpRecorder(config, clock::get, { 1_800_000_000_000_000_000 + clock.get() }, sourceIdentity = mapOf("test_fixture" to true))
		} else null
		val oldBytes = ByteArrayOutputStream()
		val oldFrames = SameFrameDiagnostics(oldBytes, resetWindow = realtime)
		var before: Runnable? = null; var after: Runnable? = null
		val failures = mutableListOf<Exception>()
		val integration = MonakaServerIntegration.startIfEnabled(true, { MonakaConfiguration(fixture.coordinate_space, assignments, port = 0) }, { raw }, manager.skeleton,
			{ before = it }, { failures += it }, now, registerAfterPose = { after = it }, frameDiagnostics = { oldFrames }, qJumpDiagnostics = { recorder?.let(::QJumpDiagnostics) })!!
		val output = mutableListOf<List<Pair<Vector3, Quaternion>>>()
		var sequence = 0L
		fun frame(q: Quaternion) {
			if (!realtime) clock.addAndGet(50_000_000)
			val processed = recorder?.processedFrames?.get() ?: 0
			val sourceElapsed = if (realtime) now() - realStart else sequence * 50_000_000
			for (packet in packets) {
				val pose = packet.copy(sequence = sequence, timestamp_ns = fixture.timestamp_ns + sourceElapsed, sent_at_ns = fixture.sent_at_ns + sourceElapsed,
					orientation = listOf(q.x.toDouble(), q.y.toDouble(), q.z.toDouble(), q.w.toDouble()), input = packet.input.copy(sequence = sequence))
				assertTrue(integration.runtime.inbox.receive((MonakaCodec.encodeEnvelope(pose) as EncodeResult.Success).value, now()))
			}
			sequence++
			before!!.run(); manager.update(); after!!.run()
			assertTrue(failures.isEmpty(), failures.toString())
			if (recorder != null && !realtime) await { recorder.processedFrames.get() >= processed + 6 }
			if (realtime) Thread.sleep(1) else output += manager.computedTrackers.map { it.position to it.getRotation() }
		}
		if (realtime) while (now() - realStart < 10_300_000_000) frame(Quaternion.IDENTITY) else repeat(204) { frame(Quaternion.IDENTITY) }
		val processed = recorder?.processedFrames?.get() ?: 0
		manager.resetTrackersFull("qjump real five target")
		if (recorder != null && !realtime) await { recorder.processedFrames.get() >= processed + 2 }
		frame(Quaternion.IDENTITY); frame(Quaternion.rotationAroundXAxis(2.0943952f)); frame(Quaternion.IDENTITY)
		if (realtime) while (now() - realStart < 20_700_000_000) frame(Quaternion.IDENTITY) else repeat(208) { frame(Quaternion.IDENTITY) }
		if (recorder != null) { if (!realtime) clock.addAndGet(500_000_000); await { recorder.writtenEvents.get() > 0 } }
		integration.close()
		assertTrue(oldFrames.awaitTermination(3000))
		if (recorder != null) assertTrue(recorder.awaitTermination(3000))
		val records = if (recorder == null) emptyList() else Files.walk(out.resolve("monaka-${recorder.processInstance}")).use { paths -> paths.filter { it.fileName.toString().endsWith(".jsonl") }.toList() }.flatMap { path -> Files.readAllLines(path).map(mapper::readTree) }
		if (realtime) Files.writeString(out.resolve("latest-realtime.json"), mapper.writeValueAsString(mapOf("process_instance" to recorder!!.processInstance, "input_frames" to sequence.toString(), "elapsed_ns" to (now() - realStart).toString(), "requested_sleep_ms" to "1", "actor_await_per_frame" to false, "achieved_frames_per_second" to sequence * 1e9 / (now() - realStart))))
		val oldRecords = oldBytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).map(mapper::readTree).toList()
		return Result(output, records, oldRecords.filter { it["schema"].asText() == "monaka.same_frame.v1" }.map { it["consumed_rotation_inputs"].size() }, oldRecords.count { it["event"]?.asText() == "FULL_RESET" })
	}

	@Test fun actualFiveTargetPathPreservesOutputAndResetObserverAndExportsEveryBoundary() {
		val off = run(false)
		val on = run(true)
		assertEquals(off.poses, on.poses, "diagnostics never changes model values")
		assertEquals(off.oldReads, on.oldReads, "single actual-read fanout preserves old capture counts")
		assertEquals(2, on.resetEvents)
		assertTrue(on.records.last()["complete"].asBoolean())
		assertTrue(on.records.last()["prewindow_complete"].asBoolean())
		assertTrue(on.records.last()["post_data_present"].asBoolean())
		assertEquals("0", on.records.last()["dropped"].asText())
		assertEquals("0", on.records.last()["missed_events"].asText())
		assertEquals("0", on.records.last()["writer_errors"].asText())
		val samples = on.records.filter { it["kind"].asText() == "sample" }
		for (target in targets) for (stage in listOf("admitted", "resolved", "solver_constraint", "consumed", "bone")) {
			assertTrue(samples.any { it["stage"].asText() == stage && it["context"]["target"]?.asText() == target.name }, "$stage/$target missing from actual exported bundle")
		}
		assertTrue(samples.any { it["stage"].asText() == "computed" })
		for (computed in samples.filter { it["stage"].asText() == "computed" }) {
			val tick = computed["links"]["consumer_frame"]["tick"].asText()
			val solverTargets = samples.filter { it["stage"].asText() == "solver_constraint" && it["context"]["tick"].asText() == tick }.map { it["context"]["target"].asText() }.toSet()
			assertEquals(targets.map { it.name }.toSet(), solverTargets, "every compact frame reference resolves inside the event")
		}
		assertTrue(samples.filter { it["stage"].asText() == "consumed" }.all { !it["validity"]["position"].asBoolean() && it["validity"]["rotation"].asBoolean() })
		assertTrue(samples.filter { it["stage"].asText() == "admitted" }.any { it["timestamps"]["source_timestamp_ns"].asText().toLong() > 9_007_199_254_740_992 })
		assertTrue(on.records.any { it["trigger_type"]?.asText() == "T3" })
	}

	@Test fun fiveTargetsRealClockNoActorAwaitRetainsDefaultWindowAndExactFastSpike() {
		val on = run(true, realtime = true)
		val footer = on.records.last()
		assertTrue(footer["complete"].asBoolean(), footer.toString())
		assertTrue(footer["prewindow_complete"].asBoolean(), footer.toString())
		assertTrue(footer["post_data_present"].asBoolean(), footer.toString())
		for (field in listOf("dropped", "missed_events", "writer_errors")) assertEquals("0", footer[field].asText(), footer.toString())
		val samples = on.records.filter { it["kind"].asText() == "sample" }
		for (target in targets) for (stage in listOf("admitted", "resolved", "solver_constraint", "consumed", "bone"))
			assertTrue(samples.any { it["stage"].asText() == stage && it["context"]["target"]?.asText() == target.name }, "$stage/$target")
		for (computed in samples.filter { it["stage"].asText() == "computed" }) {
			val tick = computed["links"]["consumer_frame"]["tick"].asText()
			assertEquals(targets.map { it.name }.toSet(), samples.filter { it["stage"].asText() == "solver_constraint" && it["context"]["tick"].asText() == tick }.map { it["context"]["target"].asText() }.toSet())
		}
		val fast = on.records.filter { it["trigger_type"]?.asText() == "T1" && it["stage"]?.asText() == "consumed" }
		assertTrue(fast.isNotEmpty(), "actual FK spike between regular sample slots must be captured")
		assertTrue(fast.all { it["before"]["process_monotonic_ns"].asText().toLong() < it["after"]["process_monotonic_ns"].asText().toLong() })
		assertEquals(2, on.resetEvents)
	}

	@Test fun actualBridgeWireReplayExportsConsumerFormatWithoutRewritingLineageOrUtc() {
		val input = System.getenv("QJUMP_CONNECTED_MVIP_FILE")
		assumeTrue(input != null, "Explicit upstream software artifact required")
		val packets = Files.readAllLines(Path.of(input!!)).filter(String::isNotBlank).map { line -> (MonakaCodec.decodeEnvelope(line.toByteArray()) as DecodeResult.Success).value as MtpPose }
		assertTrue(packets.size > 10)
		val p = packets.first()
		val assignment = TrackerBodyAssignments().also { it.configureSources(TrackerPosition.CHEST, TrackerReference.mtp(LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)), null, true) }
		val head = Tracker(null, 700, "connected-head", trackerPosition = TrackerPosition.HEAD, hasPosition = true, hasRotation = true, isHmd = true, trackRotDirection = false).also { it.position = Vector3(0f, 1.7f, 0f); it.status = TrackerStatus.OK }
		val manager = HumanPoseManager(listOf(head)).also { it.setLegTweaksEnabled(false); it.update() }
		val root = File(System.getProperty("monaka.fixtures")).parentFile.parentFile.parentFile.toPath()
		val out = root.resolve("Build/diagnostics/qjump/connected")
		val coord = out.resolve("coord-${java.util.UUID.randomUUID()}")
		val recorder = QJumpRecorder(QJumpRecorder.Config(out, coord, preNs = 20_000_000, postNs = 200_000_000, intervalNs = 10_000_000), sourceIdentity = mapOf("test_fixture" to "actual public MVIP replay"))
		var before: Runnable? = null; var after: Runnable? = null
		val failures = mutableListOf<Exception>()
		val integration = MonakaServerIntegration.startIfEnabled(true, { MonakaConfiguration(p.coordinate_space, assignment, port = 0) }, { listOf(head) }, manager.skeleton,
			{ before = it }, { failures += it }, clock = System::nanoTime, registerAfterPose = { after = it }, qJumpDiagnostics = { QJumpDiagnostics(recorder) })!!
		Files.createDirectories(coord)
		for ((index, packet) in packets.withIndex()) {
			if (index == 20) Files.writeString(coord.resolve("7a9ec380-55f1-4a85-9b9a-645ab15a8d01.json"), mapper.writeValueAsString(mapOf("schema" to "qjump.request.v1", "incident_id" to "7a9ec380-55f1-4a85-9b9a-645ab15a8d01", "origin_component" to "bridge", "origin_process_instance" to "connected-software-fixture", "wall_clock_utc_ns" to QJumpRecorder.utcNanos().toString(), "trigger_type" to "T1")), java.nio.file.StandardOpenOption.CREATE_NEW)
			assertTrue(integration.runtime.inbox.receive((MonakaCodec.encodeEnvelope(packet) as EncodeResult.Success).value, System.nanoTime()))
			before!!.run(); manager.update(); after!!.run(); Thread.sleep(2)
		}
		Thread.sleep(250)
		integration.close(); assertTrue(recorder.awaitTermination(3000)); assertTrue(failures.isEmpty(), failures.toString())
		val paths = Files.walk(out).use { stream -> stream.filter { it.fileName.toString().endsWith(".jsonl") && it.parent.fileName.toString() == "monaka-${recorder.processInstance}" }.toList() }
		assertTrue(paths.isNotEmpty())
		val records = paths.flatMap { Files.readAllLines(it).map(mapper::readTree) }
		assertTrue(records.filter { it["kind"].asText() == "sample" && it["stage"].asText() == "admitted" }.all { it["identity"]["publisher_id"].asText() == p.publisher_id })
		Files.writeString(out.resolve("latest-replay.json"), mapper.writeValueAsString(mapOf("input" to input, "bundles" to paths.map(Path::toString), "sequence_and_timestamp_rewritten" to false, "utc_rewritten" to false)))
	}
}
