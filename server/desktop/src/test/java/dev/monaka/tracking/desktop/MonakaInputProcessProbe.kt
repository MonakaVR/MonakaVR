package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.tracking.*
import dev.slimevr.tracking.trackers.Tracker
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.*
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.*

/** Explicit software fixture. Uses shipped runtime classes; no VRServer/device/driver startup. */
object MonakaInputProcessProbe {
 @JvmStatic fun main(args: Array<String>) {
  require(args.size == 3) { "CONFIG BRIDGE_CASES READY" }
  val path = Path.of(args[0]); val casesFile = Path.of(args[1]); val ready = Path.of(args[2])
  require(!Files.exists(ready))
  val mapper = ObjectMapper(); val config = MonakaConfiguration.load(path)
  val cases = mapper.readTree(casesFile.toFile())["cases"]
  val clock = MonakaRuntime.monotonicClock()
  val slime = Tracker(null, 7001, "software-legacy-q", trackerPosition = null, hasRotation = true,
   allowFiltering = false, allowReset = false, allowMounting = false)
  val runtime = MonakaRuntime({ listOf(slime) }, config.space, config.assignments, clock = clock, timeoutNanos = config.timeoutNanos)
  val receiver = MtpUdpReceiver(runtime.inbox, clock, 0)
  val sender = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
  val api = MonakaInputConfigurationApi(config, runtime.mtp, { listOf(slime) }, path)
  val executor = Executors.newSingleThreadScheduledExecutor()
  val tasks = ConcurrentLinkedQueue<Runnable>()
  var constraints = emptyMap<dev.slimevr.tracking.trackers.TrackerPosition, EffectiveConstraint>()
  var heldChannel: FileChannel? = null; var heldLock: FileLock? = null
  var sent = 0
  fun proof(): Map<String, Any> = mapOf("sent_packets" to sent, "counters" to runtime.inbox.diagnostics(),
   "generation" to config.assignments.snapshot().generation.toString(),
   "config_sha256" to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) },
   "constraints" to constraints.mapValues { (_, c) -> mapOf("position" to (c.position != null), "rotation" to (c.rotation != null),
    "position_source" to c.position?.sourceId, "rotation_source" to c.rotation?.sourceId) })
  val dispatcher = MonakaInputRpcDispatcher({ tasks += it }, { { raw ->
   val response = mapper.readTree(api.handleJson(raw, clock())) as com.fasterxml.jackson.databind.node.ObjectNode
   response.set<com.fasterxml.jackson.databind.JsonNode>("proof", mapper.valueToTree(proof())); response.toString()
  } })
  val server = object : WebSocketServer(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) {
   override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}
   override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}
   override fun onError(conn: WebSocket?, error: Exception) { System.err.println(error.toString()) }
   override fun onStart() {
    val runtimeArtifact = MonakaRuntime::class.java.protectionDomain.codeSource.location.toURI().toString()
    Files.writeString(ready, mapper.writeValueAsString(mapOf("ws_port" to port, "udp_port" to receiver.port,
     "runtime_artifact" to runtimeArtifact,
     "api_artifact" to MonakaInputConfigurationApi::class.java.protectionDomain.codeSource.location.toURI().toString(),
     "receiver_artifact" to MtpUdpReceiver::class.java.protectionDomain.codeSource.location.toURI().toString())), StandardOpenOption.CREATE_NEW)
   }
   override fun onMessage(conn: WebSocket, raw: String) {
    if (dispatcher.receive(raw, conn.remoteSocketAddress.address.isLoopbackAddress, { conn.isOpen }, conn::send)) return
    require(raw.length <= 4096)
    val request = mapper.readTree(raw); if (request["type"]?.asText() != "probe_control") return
    tasks += Runnable {
     try {
      when (request["op"].asText()) {
       "feed" -> {
        val packets = requireNotNull(cases[request["case"].asText()]); require(packets.isArray)
        for (wire in packets) {
         val bytes = wire.asText().toByteArray(Charsets.UTF_8)
         sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), receiver.port)); sent++
        }
       }
       "hold-lock" -> {
        check(heldLock == null)
        heldChannel = FileChannel.open(path.resolveSibling(path.fileName.toString() + ".input-write.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        heldLock = heldChannel!!.lock()
       }
       "release-lock" -> { heldLock?.release(); heldChannel?.close(); heldLock = null; heldChannel = null }
       "disconnect" -> connections.filter { it !== conn }.forEach { it.close(1000, "owned software reconnect") }
       "proof" -> Unit
       else -> error("Unknown probe operation")
      }
      conn.send(mapper.writeValueAsString(mapOf("type" to "probe_control", "ok" to true, "proof" to proof())))
     } catch (e: Exception) { conn.send(mapper.writeValueAsString(mapOf("type" to "probe_control", "ok" to false, "error" to e.toString()))) }
    }
   }
  }
  try {
   executor.scheduleAtFixedRate({
    try {
     constraints = runtime.tick()
     repeat(16) { tasks.poll()?.run() ?: return@repeat }
    } catch (e: Exception) { e.printStackTrace(); throw e }
   }, 0, 5, TimeUnit.MILLISECONDS)
   server.start()
   while (readlnOrNull()?.let { it != "exit" } == true) {}
  } finally {
   server.stop(1000); executor.shutdown(); check(executor.awaitTermination(5, TimeUnit.SECONDS))
   heldLock?.release(); heldChannel?.close(); sender.close(); receiver.close(); runtime.close()
   Files.writeString(ready.resolveSibling(ready.fileName.toString() + ".closed.json"), mapper.writeValueAsString(mapOf("closed" to true, "receiver_alive" to receiver.isAlive, "sent_packets" to sent)))
  }
 }
}
