package dev.monaka.tracking

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicInteger

/** Text namespace on the existing GUI WebSocket; tracking codecs remain untouched. */
class MonakaInputRpcDispatcher(private val queue: (Runnable) -> Unit, private val handler: () -> ((String) -> String)?) {
 private val pending = AtomicInteger()
 private val mapper = ObjectMapper()
 fun receive(raw: String, loopback: Boolean, connected: () -> Boolean, reply: (String) -> Unit): Boolean {
  val root = runCatching { if (raw.length <= 65536) mapper.readTree(raw) else null }.getOrNull()
  if (root?.get("type")?.asText() != "monaka_inputs_v1") return false
  val id = root["id"]?.takeIf { it.isTextual }?.asText()?.take(64) ?: ""
  fun failed(reason: String) = mapper.writeValueAsString(mapOf("type" to "monaka_inputs_v1", "id" to id, "ok" to false, "error" to reason))
  if (!loopback) { reply(failed("Input configuration is available only on the local server")); return true }
  if (pending.incrementAndGet() > 16) { pending.decrementAndGet(); reply(failed("Input request queue is busy")); return true }
  try {
   queue(Runnable {
    try {
     if (connected()) reply(handler()?.invoke(raw) ?: failed("Monaka input is disabled or unavailable"))
    } catch (_: Exception) {
     if (connected()) runCatching { reply(failed("Input request failed; reload before retrying")) }
    } finally { pending.decrementAndGet() }
   })
  } catch (_: Exception) { pending.decrementAndGet(); reply(failed("Server is unavailable")) }
  return true
 }
}
