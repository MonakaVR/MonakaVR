package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.ObjectMapper
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
}
