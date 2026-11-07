package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class QJumpRecorderTests {
	@TempDir lateinit var dir: Path
	private val mapper = ObjectMapper()
	private fun await(condition: () -> Boolean) { val end = System.nanoTime() + 3_000_000_000; while (!condition() && System.nanoTime() < end) Thread.sleep(1); assertTrue(condition()) }
	private inner class Fixture(post: Long = 200_000_000, sink: ((Path) -> java.io.OutputStream)? = null) : AutoCloseable {
		val now = AtomicLong()
		val config = QJumpRecorder.Config(dir.resolve(UUID.randomUUID().toString()), dir.resolve("coord-${UUID.randomUUID()}"), preNs = 200_000_000, postNs = post)
		val recorder = QJumpRecorder(config, now::get, { 1_800_000_000_000_000_000 + now.get() }, sink, mapOf("commit" to "fixture"))
		fun sample(degrees: Double, sequence: String = "9007199254740993", sign: Double = 1.0, lifecycle: Map<String, Any?> = emptyMap(), session: String = "session") =
			mapOf("stage" to "admitted", "stream_id" to "admitted:opaque", "identity" to mapOf("publisher_id" to "opaque-A", "source_id" to "source", "tracker_id" to "tracker", "session_id" to session, "clock_id" to "clock", "sequence" to sequence),
				"q" to listOf(0.0, sign * sin(Math.toRadians(degrees) / 2), 0.0, sign * cos(Math.toRadians(degrees) / 2)), "validity" to mapOf("fresh" to true, "rotation" to true),
				"timestamps" to mapOf("source_timestamp_ns" to (9007199254740993 + now.get()).toString()), "links" to emptyMap<String, String>(), "context" to mapOf("lifecycle" to lifecycle))
		fun step(degrees: Double = 0.0, sign: Double = 1.0, life: Map<String, Any?> = emptyMap(), session: String = "session") {
			now.addAndGet(50_000_000)
			val before = recorder.processedFrames.get()
			assertTrue(recorder.offer(listOf(sample(degrees, sign = sign, lifecycle = life, session = session))))
			await { recorder.processedFrames.get() > before }
		}
		fun warm(life: Map<String, Any?> = emptyMap()) { repeat(7) { step(life = life) } }
		fun finish() { now.addAndGet(config.postNs + 50_000_000); await { recorder.writtenEvents.get() > 0 } }
		fun records() = Files.walk(config.output).use { paths -> paths.filter { it.toString().endsWith(".jsonl") }.toList() }.flatMap { path -> Files.readAllLines(path).map(mapper::readTree) }
		override fun close() { recorder.close(); assertTrue(recorder.awaitTermination(3000)) }
	}

	@Test fun normalAndQuaternionSignFlipNeverSpendDiskBudget() = Fixture().use { f ->
		repeat(50) { f.step((it % 10).toDouble()) }
		f.step(0.0, -1.0); f.step()
		assertEquals(0, f.recorder.writtenEvents.get())
		assertEquals(0, Files.walk(f.config.output).use { it.filter { p -> p.toString().endsWith(".jsonl") }.count() })
		assertTrue(f.recorder.ringBytes <= f.config.maxRingBytes)
		assertTrue(f.recorder.ringFrames <= f.config.maxFrames)
	}

	@Test fun angularStepsPreserveExactFastBeforeAfterAndCompletePrePost() {
		for (degrees in listOf(120.0, 180.0)) Fixture().use { f ->
			f.warm(); f.step(degrees); f.step(); repeat(4) { f.step() }
			await { f.recorder.writtenEvents.get() > 0 }
			val records = f.records()
			val trigger = records.first { it["kind"].asText() == "trigger" }
			assertEquals("T1", trigger["trigger_type"].asText())
			assertEquals("9007199254740993", trigger["after"]["identity"]["sequence"].asText())
			assertEquals("9007199654740993", trigger["after"]["timestamps"]["source_timestamp_ns"].asText())
			assertTrue(trigger["angular_degrees"].asDouble() > 119)
			assertTrue(records.last()["complete"].asBoolean())
			assertEquals("qjump.capture.v1", records.first()["schema"].asText())
		}
	}

	@Test fun persistentAnchorSurvivesSmallMotionButRecoveryNeverStartsReversePersistence() {
		Fixture(2_500_000_000).use { f ->
			f.warm(); f.step(120.0); repeat(44) {
				f.step(121.0)
				// The same accepted source can be re-consumed between fresh samples without losing its anchor.
				assertTrue(f.recorder.offer(listOf(f.sample(121.0))))
				assertTrue(f.recorder.offer(listOf(f.sample(121.0) + mapOf("timestamps" to mapOf("source_timestamp_ns" to (9007199254740993 + f.now.get() - 1).toString())))))
			}; f.finish()
			assertTrue(f.records().any { it["trigger_type"]?.asText() == "T2" })
		}
		Fixture(2_500_000_000).use { f ->
			f.warm(); f.step(120.0); f.step(); repeat(44) { f.step() }; f.finish()
			assertFalse(f.records().any { it["trigger_type"]?.asText() == "T2" })
		}
		Fixture(2_500_000_000).use { f -> f.warm(); f.step(120.0); f.now.addAndGet(2_100_000_000); f.step(120.0); f.finish(); assertFalse(f.records().any { it["trigger_type"]?.asText() == "T2" }) }
		Fixture(2_500_000_000).use { f ->
			f.warm(); f.step(120.0)
			repeat(44) {
				f.now.addAndGet(50_000_000)
				val before = f.recorder.processedFrames.get()
				f.recorder.offer(listOf(f.sample(120.0) + mapOf("timestamps" to mapOf("source_timestamp_ns" to "9007199654740993"))))
				await { f.recorder.processedFrames.get() > before }
			}
			f.finish(); assertFalse(f.records().any { it["trigger_type"]?.asText() == "T2" })
		}
	}

	@Test fun sourceHighWaterRejectsDuplicateRegressionAndStalledResumePersistence() {
		for (mode in listOf("regression", "resume", "duplicate_at_threshold")) Fixture(2_500_000_000).use { f ->
			f.warm(); f.step(120.0)
			if (mode == "duplicate_at_threshold") repeat(39) { f.step(120.0) }
			val frozenSource = 9007199254740993 + f.now.get()
			val count = if (mode == "duplicate_at_threshold") 1 else 44
			repeat(count) { index ->
				f.now.addAndGet(50_000_000)
				val value = if (mode == "regression") frozenSource - 1000 + index else frozenSource
				val processed = f.recorder.processedFrames.get()
				assertTrue(f.recorder.offer(listOf(f.sample(120.0) + mapOf("timestamps" to mapOf("source_timestamp_ns" to value.toString())))))
				await { f.recorder.processedFrames.get() > processed }
			}
			if (mode == "resume") repeat(3) { f.step(120.0) }
			f.finish()
			assertFalse(f.records().any { it["trigger_type"]?.asText() == "T2" }, mode)
		}
	}

	@Test fun lifecycleIsTypedAndInitialObservationSeedsRatherThanTriggers() {
		val fields = listOf("reset_epoch" to "T3", "assignment_generation" to "T4", "proxy_instance" to "T5")
		for ((field, expected) in fields) Fixture().use { f ->
			f.warm(mapOf(field to "0")); assertEquals(0, f.recorder.writtenEvents.get())
			f.step(life = mapOf(field to "1")); f.finish()
			assertTrue(f.records().any { it["trigger_type"]?.asText() == expected })
		}
		Fixture().use { f -> f.warm(); f.step(session = "new-session"); f.finish(); assertTrue(f.records().any { it["trigger_type"]?.asText() == "T6" }) }
		Fixture().use { f ->
			fun fullEpoch(epoch: String) {
				f.now.addAndGet(50_000_000)
				val before = f.recorder.processedFrames.get()
				assertTrue(f.recorder.offer(listOf(f.sample(0.0) + mapOf("stage" to "solver_constraint", "context" to mapOf("lifecycle" to mapOf("reset_epoch" to "7"), "reset_state" to mapOf("correction_epoch" to epoch))))))
				await { f.recorder.processedFrames.get() > before }
			}
			repeat(7) { fullEpoch("original-real-epoch") }
			assertEquals(0, f.recorder.writtenEvents.get())
			fullEpoch("changed-real-epoch-same-counter")
			f.finish()
			assertTrue(f.records().any { it["trigger_type"]?.asText() == "T3" })
		}
	}

	@Test fun commonRequestCapturesSilentParticipantWithoutInventingNewPose() = Fixture().use { f ->
		f.warm()
		val lastSampleMono = f.now.get()
		val id = UUID.randomUUID().toString()
		Files.createDirectories(f.config.coordination)
		Files.writeString(f.config.coordination.resolve("$id.json"), mapper.writeValueAsString(mapOf("schema" to "qjump.request.v1", "incident_id" to id, "origin_component" to "bridge", "origin_process_instance" to "other", "wall_clock_utc_ns" to (1_800_000_000_000_000_000 + f.now.get()).toString(), "trigger_type" to "T3_RESET")))
		f.now.addAndGet(50_000_000); Thread.sleep(80)
		f.finish()
		val records = f.records()
		assertTrue(records.first()["incident_ids"].any { it.asText() == id })
		assertTrue(records.any { it["trigger_type"]?.asText() == "T3" })
		assertTrue(records.filter { it["kind"].asText() == "sample" }.all { it["process_monotonic_ns"].asText().toLong() <= lastSampleMono })
		assertTrue(records.last()["silence_after_last_sample"].asBoolean())
	}

	@Test fun blockedWriterOversizeAndOverflowRemainBoundedAndNonblocking() {
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		val f = Fixture(sink = { object : java.io.OutputStream() { override fun write(b: Int) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } } })
		try {
			f.warm(); f.step(120.0); f.now.addAndGet(500_000_000)
			assertTrue(entered.await(2, TimeUnit.SECONDS))
			val started = System.nanoTime()
			repeat(1000) { f.recorder.offer(listOf(f.sample(if (it % 2 == 0) 0.0 else 120.0))) }
			assertTrue(f.recorder.dropped.get() > 0, "forced incident burst overflows the bounded queue, rather than normal gated offers")
			assertFalse(f.recorder.offer(listOf(mapOf("oversize" to "x".repeat(2048)))))
			f.recorder.close()
			assertTrue(System.nanoTime() - started < 500_000_000)
			assertTrue(f.recorder.dropped.get() > 0)
			assertTrue(f.recorder.ringBytes <= f.config.maxRingBytes)
		} finally { release.countDown(); f.close() }
	}

	@Test fun concurrentResetCanOvertakeRichSnapshotWithoutReversingOriginalCoverage() = Fixture().use { f ->
		f.warm()
		val entered = CountDownLatch(1); val release = CountDownLatch(1)
		f.now.addAndGet(50_000_000)
		val oldMono = f.now.get()
		val slow = Thread {
			f.recorder.offer(listOf(f.sample(0.0))) { selected ->
				entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); selected
			}
		}
		slow.start()
		try {
			assertTrue(entered.await(1, TimeUnit.SECONDS))
			f.step(120.0)
		} finally { release.countDown(); slow.join(2000) }
		repeat(5) { f.step(120.0) }
		f.finish()
		val records = f.records()
		val times = records.filter { it["kind"].asText() == "sample" }.map { it["process_monotonic_ns"].asText().toLong() }
		assertEquals(times.sorted(), times)
		assertTrue(oldMono in times)
		assertEquals(times.first().toString(), records.last()["coverage_start_process_ns"].asText())
		assertEquals(times.last().toString(), records.last()["coverage_end_process_ns"].asText())
		assertEquals("0", records.last()["dropped"].asText())
	}

	@Test fun actualCppRequestBytesAreAcceptedBySilentKotlinParticipant() {
		val source = System.getenv("QJUMP_CPP_REQUEST_FILE")
		assumeTrue(source != null, "Explicit actual C++ request artifact required")
		val bytes = Files.readAllBytes(Path.of(source!!))
		val request = mapper.readTree(bytes)
		val utc = request["wall_clock_utc_ns"].asText().toLong()
		val now = AtomicLong()
		val config = QJumpRecorder.Config(dir.resolve("actual-cpp-output"), dir.resolve("actual-cpp-coord"), preNs = 200_000_000, postNs = 200_000_000)
		val recorder = QJumpRecorder(config, now::get, { utc - 1_000_000 + now.get() })
		try {
			repeat(7) {
				now.addAndGet(50_000_000)
				val before = recorder.processedFrames.get()
				assertTrue(recorder.offer(listOf(mapOf("stream_id" to "actual-silent", "stage" to "admitted", "identity" to mapOf("source_id" to "test"), "q" to listOf(0.0,0.0,0.0,1.0), "validity" to mapOf("fresh" to true)))))
				await { recorder.processedFrames.get() > before }
			}
			Files.createDirectories(config.coordination)
			Files.write(config.coordination.resolve("${request["incident_id"].asText()}.json"), bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
			now.addAndGet(50_000_000); Thread.sleep(80); now.addAndGet(500_000_000)
			await { recorder.writtenEvents.get() > 0 }
			val records = Files.walk(config.output).use { ps -> ps.filter { it.toString().endsWith(".jsonl") }.toList() }.flatMap { Files.readAllLines(it).map(mapper::readTree) }
			assertTrue(records.first()["incident_ids"].any { it == request["incident_id"] })
			assertTrue(records.any { it["trigger_type"] == request["trigger_type"] })
			assertTrue(records.last()["no_new_post_samples"].asBoolean())
			assertFalse(records.last()["post_data_present"].asBoolean())
		} finally { recorder.close(); assertTrue(recorder.awaitTermination(3000)) }
	}
}
