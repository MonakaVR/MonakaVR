package dev.monaka.tracking

import dev.monaka.tracking.hil.CorePocHilJson
import dev.monaka.tracking.hil.CorePocHilStdioMain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

class CorePocHilStdioTests {
	@Test fun pipePreservesPayloadAndRequestOrder() {
		val raw = """{"id":"r0","op":"input","frame":{"source":"日本語","timestamp":"9223372036854775807","valid":false}}
{"id":"r1","op":"shutdown"}
{"id":"ignored","op":"input"}
"""
		val seen = mutableListOf<String>(); val output = ByteArrayOutputStream()
		CorePocHilStdioMain.process(ByteArrayInputStream(raw.toByteArray()), output) { request ->
			seen += request["id"].asText(); mapOf("original" to request)
		}
		val result = output.toString(Charsets.UTF_8).lines().filter(String::isNotEmpty).map(CorePocHilJson.mapper::readTree)
		assertEquals(listOf("r0", "r1"), seen)
		assertEquals("9223372036854775807", result[0]["response"]["original"]["frame"]["timestamp"].asText())
		assertEquals("日本語", result[0]["response"]["original"]["frame"]["source"].asText())
		assertFalse(result[0]["response"]["original"]["frame"]["valid"].asBoolean())
		assertEquals("r1", result[1]["id"].asText())
	}
	@Test fun invalidRequestIdentityStopsBeforeControl() {
		var calls = 0
		assertFails { CorePocHilStdioMain.process(ByteArrayInputStream("{\"id\":\"../foreign\",\"op\":\"input\"}\n".toByteArray()), ByteArrayOutputStream()) { calls++; emptyMap() } }
		assertEquals(0, calls)
	}
	@Test fun oversizedAndUnterminatedRequestsFailClosed() {
		for (bytes in listOf(ByteArray(1_048_577) { 65 }, "{\"id\":\"r0\",\"op\":\"input\"}".toByteArray())) {
			var calls = 0
			assertFails { CorePocHilStdioMain.process(ByteArrayInputStream(bytes), ByteArrayOutputStream()) { calls++; emptyMap() } }
			assertEquals(0, calls)
		}
	}
}
