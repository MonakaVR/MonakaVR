package dev.monaka.tracking.desktop

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import dev.monaka.protocol.v2.*
import dev.monaka.tracking.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Diagnostic process only. No Slime/HMD inputs, IK, SteamVR, auto-approval or config writes. */
object MtpHilCapture {
	@JvmStatic fun main(args: Array<String>) {
		require(args.size == 4) { "Usage: MtpHilCapture CONFIG NEW_OUTPUT_DIRECTORY SECONDS MIRROR_PORT" }
		val configPath = Path.of(args[0])
		val config = MonakaConfiguration.load(configPath)
		val seconds = args[2].toLong().also { require(it in 1..3600) }
		val mirrorPort = args[3].toInt().also { require(it in 0..65535) }
		fun bind(port: Int) = DatagramSocket(null).apply {
			try {
				reuseAddress = false
				bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
				soTimeout = 1
			} catch (e: Exception) { close(); throw e }
		}
		// Refuse occupied ports; never stop or replace another receiver.
		bind(config.port).use { mtp ->
			bind(mirrorPort).use { observation ->
				val directory = Path.of(args[1]).toAbsolutePath()
				Files.createDirectories(directory.parent)
				Files.createDirectory(directory) // Never overwrite an earlier capture.
				val clock = MonakaRuntime.monotonicClock()
				MtpHilCaptureSession(config, directory, clock).use { capture ->
					val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(configPath)).joinToString("") { "%02x".format(it) }
					Files.writeString(directory.resolve("capture.json"), GsonBuilder().serializeNulls().create().toJson(mapOf(
						"mode" to "diagnostic MonakaRuntime; no IK/SteamVR", "hardware_verdict" to "NOT EVALUATED",
						"config" to configPath.toAbsolutePath().toString(), "config_sha256" to hash,
						"mtp_port" to mtp.localPort, "observation_mirror_port" to observation.localPort,
						"timeout_ns" to config.timeoutNanos.toString(), "started_utc" to java.time.Instant.now().toString(),
					)))
					println("READY MTP=${mtp.localPort} Observation-mirror=${observation.localPort} logs=$directory")
					val deadline = seconds * 1_000_000_000
					while (clock() < deadline) {
						for ((socket, isMtp) in listOf(mtp to true, observation to false)) {
							val packet = DatagramPacket(ByteArray(4097), 4097)
							try {
								socket.receive(packet)
								val bytes = packet.data.copyOf(packet.length)
								if (isMtp) capture.mtp(bytes, packet.socketAddress.toString()) else capture.observation(bytes)
							} catch (_: SocketTimeoutException) { }
						}
						capture.tick() // Also record loss when no packets arrive.
					}
					println("CAPTURED logs=$directory; hardware verdict NOT EVALUATED; IK/SteamVR NOT RUN")
				}
			}
		}
	}
}

/** All admission/resolution remains in the real server-owned runtime on this one diagnostic thread. */
internal class MtpHilCaptureSession(config: MonakaConfiguration, directory: Path, private val clock: () -> Long) : AutoCloseable {
	private val json = GsonBuilder().serializeNulls().create()
	private val observationLog = Files.newBufferedWriter(directory.resolve("observation.jsonl"))
	private val mtpLog = Files.newBufferedWriter(directory.resolve("mtp.jsonl"))
	private val constraintsLog = Files.newBufferedWriter(directory.resolve("constraints.jsonl"))
	private val runtime = MonakaRuntime({ emptyList() }, config.space, config.assignments, clock = clock, timeoutNanos = config.timeoutNanos)
	private var lastConstraints: String? = null
	private fun document(bytes: ByteArray): Any = try { JsonParser.parseString(bytes.toString(Charsets.UTF_8)) } catch (_: Exception) { mapOf("malformed_utf8" to bytes.toString(Charsets.UTF_8)) }
	private fun write(log: java.io.BufferedWriter, row: Any) { log.write(json.toJson(row)); log.newLine() }
	fun observation(bytes: ByteArray) {
		val decoded = MonakaCodec.decodeEnvelope(bytes)
		val valid = decoded is DecodeResult.Success && (decoded.value is TrackerObservation || decoded.value is ObservationDeviceState)
		write(observationLog, mapOf("local_ns" to clock().toString(), "point" to "29810 admitted mirror (29813)", "codec_valid" to valid, "wire" to document(bytes)))
	}
	fun mtp(bytes: ByteArray, peer: String) {
		val now = clock()
		val queued = runtime.inbox.receive(bytes, now, peer)
		write(mtpLog, mapOf("local_ns" to now.toString(), "peer" to peer, "queued_not_yet_admitted" to queued, "wire" to document(bytes)))
		tick()
	}
	fun tick() {
		val effective = runtime.tick()
		val payload = mapOf(
			"constraints" to runtime.assignments.snapshot().targets.map { (body, relation) ->
				val c = effective[body]
				mapOf(
					"body" to body.name, "main" to relation.mainTracker.observationId,
					"external_fallback" to relation.rotationFallbackTracker?.observationId,
					"position" to c?.position?.value?.let { listOf(it.x, it.y, it.z) },
					"orientation_xyzw" to c?.rotation?.value?.let { listOf(it.x, it.y, it.z, it.w) },
					"position_source" to c?.position?.sourceId, "rotation_source" to c?.rotation?.sourceId,
					"rotation_sample_ns" to c?.rotation?.observedAtNanos?.toString(),
				)
			},
			"accepted_samples" to runtime.mtp.samples().values.map { sample -> mapOf(
				"sample_ns" to sample.sampleTime.toString(),
				"wire" to document((MonakaCodec.encodeEnvelope(sample.pose) as EncodeResult.Success).value),
			) },
			"diagnostics" to runtime.inbox.diagnostics(),
		)
		val serialized = json.toJson(payload)
		if (serialized != lastConstraints) {
			write(constraintsLog, mapOf("local_ns" to clock().toString(), "point" to "MonakaRuntime effective constraint (not SteamVR)", "state" to payload))
			lastConstraints = serialized
		}
	}
	override fun close() {
		runtime.close()
		try { observationLog.close() } finally { try { mtpLog.close() } finally { constraintsLog.close() } }
	}
}
