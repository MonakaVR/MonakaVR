package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import dev.slimevr.tracking.processor.FullResetDiagnostics
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SameFrameDiagnosticsTests {
	@TempDir lateinit var dir: Path
	@Test fun fullQueueAndBlockedWriterCannotBlockAdmissionOrClose() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val sink = SameFrameDiagnostics(object : OutputStream() {
			override fun write(b: Int) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
		}, capacity = 1)
		try {
			assertTrue(sink.offer(mapOf("tick" to "1")))
			assertTrue(entered.await(2, TimeUnit.SECONDS))
			assertTrue(sink.offer(mapOf("tick" to "2")))
			val start = System.nanoTime()
			assertFalse(sink.offer(mapOf("tick" to "3")))
			sink.close()
			assertTrue(System.nanoTime() - start < 250_000_000)
			assertEquals(1L, sink.dropped.get())
		} finally { release.countDown(); sink.close(); assertTrue(sink.awaitTermination(2000)) }
	}
	@Test fun writerFailureIsContainedAndDoesNotLeakMessage() {
		val sink = SameFrameDiagnostics(object : OutputStream() {
			override fun write(b: Int): Unit = throw IOException("private path and raw identity")
		})
		assertTrue(sink.offer(mapOf("tick" to "1")))
		assertTrue(sink.awaitTermination(2000))
		assertEquals("IOException", sink.failure)
		assertFalse(sink.offer(mapOf("tick" to "2")))
	}
	@Test fun frameAndByteLimitsProduceOnlyCompleteRecords() {
		val bytes = ByteArrayOutputStream()
		val sink = SameFrameDiagnostics(bytes, maxFrames = 2)
		assertTrue(sink.offer(mapOf("tick" to "1")))
		assertTrue(sink.offer(mapOf("tick" to "2")))
		assertFalse(sink.offer(mapOf("tick" to "3")))
		sink.close(); assertTrue(sink.awaitTermination(2000))
		assertEquals(2, bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.count())
		val limited = ByteArrayOutputStream()
		val second = SameFrameDiagnostics(limited, maxBytes = 1024)
		assertTrue(second.offer(mapOf("tick" to "1")))
		assertTrue(second.offer(mapOf("large" to "x".repeat(2000))))
		second.close(); assertTrue(second.awaitTermination(2000))
		val lines = limited.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
		assertEquals(1, lines.size)
		assertEquals("1", ObjectMapper().readTree(lines.single())["tick"].asText())
		assertEquals(1L, second.dropped.get())
	}
	@Test fun createNewPreservesExistingEvidenceAndNonFiniteCoordinatesBecomeNull() {
		val path = dir.resolve("frames.jsonl")
		Files.writeString(path, "sealed")
		assertFailsWith<java.nio.file.FileAlreadyExistsException> { SameFrameDiagnostics.open(path) }
		assertEquals("sealed", Files.readString(path))
		assertFailsWith<IllegalArgumentException> { SameFrameDiagnostics.open(Path.of("relative.jsonl")) }
		assertEquals(listOf(1f, null, null), SameFrameDiagnostics.vector(Vector3(1f, Float.NaN, Float.POSITIVE_INFINITY)))
		assertNull(SameFrameDiagnostics.vector(null))
	}
	@Test fun identitiesAreStableOnlyWithinOneCapture() {
		val a = SameFrameDiagnostics(ByteArrayOutputStream())
		val b = SameFrameDiagnostics(ByteArrayOutputStream())
		try {
			assertEquals(a.identity("opaque"), a.identity("opaque"))
			assertNotEquals(a.identity("opaque"), b.identity("opaque"))
			assertEquals(64, a.identity("opaque")!!.length)
			assertNull(a.identity(null))
		} finally { a.close(); b.close(); assertTrue(a.awaitTermination(2000)); assertTrue(b.awaitTermination(2000)) }
	}
	@Test fun resetWindowWaitsWithoutSpendingDiskBudgetAndClosesWithBoundedEvidence() {
		var now = 1_000_000_000L
		val bytes = ByteArrayOutputStream()
		val sink = SameFrameDiagnostics(bytes, resetWindow = true, systemClock = { now })
		val timeline = FullResetDiagnostics { now }
		timeline.observe(sink::recordReset)
		try {
			repeat(6000) { i ->
				assertTrue(sink.shouldRecordFrame())
				sink.recordFrame(mapOf("schema" to "monaka.same_frame.v1", "tick" to i.toString(), "system_ns" to now.toString(), "reset_generation" to "0"))
				now += SameFrameDiagnostics.SAMPLE_INTERVAL_NS
			}
			assertEquals(0L, sink.written.get())
			assertTrue(sink.bufferedPreFrames in 100..101)
			val start = timeline.started()
			timeline.finished(start, true)
			val deadline = System.nanoTime() + 2_000_000_000L
			while (sink.written.get() < 102 && System.nanoTime() < deadline) Thread.sleep(1)
			assertTrue(sink.written.get() >= 102)
			repeat(101) { i ->
				assertTrue(sink.shouldRecordFrame())
				sink.recordFrame(mapOf("schema" to "monaka.same_frame.v1", "tick" to (6000 + i).toString(), "system_ns" to now.toString(), "reset_generation" to "1"))
				now += SameFrameDiagnostics.SAMPLE_INTERVAL_NS
			}
			assertTrue(sink.awaitTermination(2000))
			val records = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.map(ObjectMapper()::readTree).toList()
			assertEquals(2, records.count { it["event"]?.asText() == "FULL_RESET" })
			val summary = records.last()
			assertEquals("monaka.capture_end.v1", summary["schema"].asText())
			assertEquals("POST_WINDOW_COMPLETE", summary["termination_reason"].asText())
			assertFalse(summary["capture_incomplete"].booleanValue())
			assertEquals("0", summary["dropped"].asText())
			assertEquals("5000000000", summary["actual_pre_ns"].asText())
			assertEquals("5000000000", summary["actual_post_ns"].asText())
			assertTrue(bytes.size() < 100_000)
			val fixtureDir = Path.of("build/same-frame-diagnostic-fixtures")
			Files.createDirectories(fixtureDir)
			Files.write(fixtureDir.resolve("bounded-window-clock-fixture.jsonl"), bytes.toByteArray())
		} finally { sink.close(); assertTrue(sink.awaitTermination(2000)) }
	}
	@Test fun completedResetForcesFirstPostFrameBetweenSampleSlots() {
		var now = 1_000_000_000L
		val sink = SameFrameDiagnostics(ByteArrayOutputStream(), resetWindow = true, systemClock = { now })
		val timeline = FullResetDiagnostics { now }
		timeline.observe(sink::recordReset)
		try {
			assertTrue(sink.shouldRecordFrame())
			now += 1
			assertFalse(sink.shouldRecordFrame())
			val start = timeline.started()
			timeline.finished(start, true)
			assertTrue(sink.shouldRecordFrame())
			assertFalse(sink.shouldRecordFrame())
		} finally { sink.close(); assertTrue(sink.awaitTermination(2000)) }
	}
	@Test fun resetWindowLimitsAndEarlyCloseAreExplicitlyIncomplete() {
		val bytes = ByteArrayOutputStream()
		val sink = SameFrameDiagnostics(bytes, resetWindow = true, maxBytes = 2048)
		assertTrue(sink.offer(mapOf("large" to "x".repeat(4096))))
		assertTrue(sink.awaitTermination(2000))
		val summary = ObjectMapper().readTree(bytes.toString(Charsets.UTF_8).trim())
		assertEquals("MAX_BYTES", summary["termination_reason"].asText())
		assertTrue(summary["capture_incomplete"].booleanValue())
		assertTrue(bytes.size() <= 2048)
		val earlyBytes = ByteArrayOutputStream()
		val early = SameFrameDiagnostics(earlyBytes, resetWindow = true)
		early.close(); assertTrue(early.awaitTermination(2000))
		val end = ObjectMapper().readTree(earlyBytes.toString(Charsets.UTF_8).trim())
		assertEquals("EXPLICIT_CLOSE", end["termination_reason"].asText())
		assertTrue(end["capture_incomplete"].booleanValue())
	}
}
