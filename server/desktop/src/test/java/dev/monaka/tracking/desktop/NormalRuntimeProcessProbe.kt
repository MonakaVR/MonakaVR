package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.tracking.*
import dev.slimevr.desktop.platform.ProtobufBridge
import dev.slimevr.desktop.platform.ProtobufMessages.*
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import dev.slimevr.tracking.trackers.udp.IMUType
import dev.slimevr.tracking.trackers.udp.UDPDevice
import io.github.axisangles.ktmath.Quaternion
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import dev.slimevr.tracking.trackers.TrackerStatus as LocalStatus

/** Explicit software fixture, test classpath only. Replaces OS/device transports;
 * ordinary RPC, UDP intake, composition, learners, IK and output serialization are shipped code.
 * REPLAY is exact Bridge MVIP NDJSON (or '-'); no sample/revision/identity rewriting.
 */
object NormalRuntimeProcessProbe {
	@JvmStatic fun main(args: Array<String>) {
		require(args.size == 5) { "CONFIG READY EVIDENCE REPLAY_OR_DASH SECONDS" }
		val configPath = Path.of(args[0]).toAbsolutePath()
		val readyPath = Path.of(args[1]).toAbsolutePath()
		val evidence = Path.of(args[2]).toAbsolutePath()
		require(!Files.exists(readyPath) && !Files.exists(evidence))
		Files.createDirectories(evidence)
		val config = MonakaConfiguration.load(configPath)
		require(config.port == 0) { "Software fixture requires an ephemeral MVIP port" }
		val replay = if (args[3] == "-") emptyList() else Files.readAllLines(Path.of(args[3])).filter { it.isNotBlank() }.map {
			it.toByteArray(Charsets.UTF_8).also { bytes -> require(bytes.size in 1..4096) }
		}
		val duration = args[4].toLong().also { require(it in 1..1800) }
		val mapper = ObjectMapper()
		val done = CountDownLatch(1)
		val tasks = ConcurrentLinkedQueue<Runnable>()
		val executor = Executors.newSingleThreadScheduledExecutor()
		val virtualBytes = Files.newBufferedWriter(evidence.resolve("virtual-output.ndjson"), Charsets.UTF_8, StandardOpenOption.CREATE_NEW)
		val transport = NormalProbeTransport { bytes -> virtualBytes.write(java.util.Base64.getEncoder().encodeToString(bytes)); virtualBytes.newLine() }
		val inputs = listOf(TrackerPosition.HIP, TrackerPosition.UPPER_CHEST).mapIndexed { index, target ->
			Tracker(UDPDevice(InetSocketAddress("127.0.0.1", 0), InetAddress.getLoopbackAddress(), "explicit-software-imu-$index"), 700 + index,
				"fixture-imu-${target.name.lowercase()}", trackerPosition = target, trackerNum = index + 1, hasRotation = true,
				imuType = IMUType.UNKNOWN, allowFiltering = false, allowReset = true, allowMounting = true, trackRotDirection = false).also { it.status = LocalStatus.OK }
		}
		val hpm = HumanPoseManager(listOf(transport.hmd) + inputs).also { it.setLegTweaksEnabled(false); it.skeleton.ikSolver.enabled = true; it.update() }
		val frames = SameFrameDiagnostics(Files.newOutputStream(evidence.resolve("same-frame.jsonl"), StandardOpenOption.CREATE_NEW), maxFrames = 65536, maxBytes = 64L * 1024 * 1024)
		val clock = MonakaRuntime.monotonicClock()
		var before: Runnable? = null
		var after: Runnable? = null
		var hmdEnabled = true
		var imuEnabled = true
		var imuYaw = 0f
		var replayEnabled = replay.isNotEmpty()
		var replayIndex = 0
		var frame = 0L
		val sender = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
		val integration = MonakaServerIntegration.startIfEnabled(true, { config }, { listOf(transport.hmd) + inputs }, hpm.skeleton,
			{ before = it }, { failure -> failure.printStackTrace(); done.countDown() }, clock,
			configureDirectOutputs = transport::configureDirectOutputs, registerAfterPose = { after = it }, configurationPath = configPath,
			frameDiagnostics = { frames }, rawHmdSource = { tick, space, age, domain -> transport.currentNormalRawHmdPoseInput(space, tick.nowNanos, tick.trackerReceiptCutoffSystemNanos, age, domain) })!!
		for (tracker in hpm.computedTrackers) transport.addSharedTracker(tracker)
		fun proof(): Map<String, Any?> = mapOf(
			"frame" to frame, "config_path" to configPath.toString(),
			"config_sha256" to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(configPath)).joinToString("") { "%02x".format(it) },
			"generation" to config.assignments.snapshot().generation.toString(), "accepted" to integration.runtime.inbox.diagnostics(),
			"position" to integration.lastPositionCorrection?.let { p -> mapOf("geometry" to p.geometry, "hmd_available" to p.hmdAvailable,
				"prediction" to SameFrameDiagnostics.vector(p.prediction), "measured_teacher" to SameFrameDiagnostics.vector(p.measuredTeacher),
				"solver_position" to SameFrameDiagnostics.vector(p.solverPosition), "reference" to p.solverPositionReference, "continuity" to p.continuity, "learning" to p.learning) },
			"body_rotation" to integration.runtime.bodyRotationResults.map { (target, r) -> mapOf("target" to target.name, "ready" to r.state.ready, "learning" to r.learning.name) },
			"virtual_trackers" to hpm.computedTrackers.map { t -> mapOf("id" to t.id, "target" to t.trackerPosition?.name, "position" to SameFrameDiagnostics.vector(t.position), "rotation_xyzw" to SameFrameDiagnostics.quaternion(t.getRotation())) },
			"boundary" to "actual consumer/RPC/CAS/IK/output; explicit software HMD/physical-IMU category inputs; captured OS transport",
			"hardware" to "NOT_RUN", "steamvr_runtime" to "NOT_RUN")
		val dispatcher = MonakaInputRpcDispatcher({ tasks += it }, { integration::inputRequest })
		val server = object : WebSocketServer(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) {
			override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}
			override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}
			override fun onError(conn: WebSocket?, error: Exception) { error.printStackTrace() }
			override fun onStart() {
				Files.writeString(readyPath, mapper.writeValueAsString(mapOf("ws_port" to port, "udp_port" to integration.receiver.port,
					"imu_sources" to inputs.map { "slime:${it.name}" }, "config_path" to configPath.toString(), "evidence" to evidence.toString(),
					"runtime_artifact" to MonakaServerIntegration::class.java.protectionDomain.codeSource.location.toURI().toString())), StandardOpenOption.CREATE_NEW)
			}
			override fun onMessage(conn: WebSocket, raw: String) {
				if (dispatcher.receive(raw, conn.remoteSocketAddress.address.isLoopbackAddress, { conn.isOpen }, conn::send)) return
				val request = runCatching { mapper.readTree(raw) }.getOrNull() ?: return
				if (request["type"]?.asText() != "normal_fixture_control_v1") return
				tasks += Runnable {
					when (request["op"]?.asText()) {
						"proof" -> Unit
						"pause-replay" -> replayEnabled = false
						"resume-replay" -> replayEnabled = true
						"imu" -> { imuEnabled = request["enabled"]?.asBoolean() ?: true; imuYaw = request["yaw"]?.floatValue() ?: 0f }
						"hmd" -> { hmdEnabled = request["enabled"]?.asBoolean() ?: true; if (!hmdEnabled) transport.loseHmd() }
						"stop" -> Unit
						else -> error("Unknown explicit fixture operation")
					}
					conn.send(mapper.writeValueAsString(mapOf("type" to "normal_fixture_control_v1", "proof" to proof())))
					if (request["op"]?.asText() == "stop") done.countDown()
				}
			}
		}
		try {
			executor.scheduleAtFixedRate({
				try {
					repeat(16) { tasks.poll()?.run() ?: return@repeat }
					if (replayEnabled && replayIndex < replay.size) {
						val bytes = replay[replayIndex++]
						sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), integration.receiver.port))
					}
					if (imuEnabled) inputs.forEach { it.setRotation(Quaternion.rotationAroundYAxis(imuYaw)) }
					if (hmdEnabled) transport.feedHmd()
					before!!.run(); hpm.update(); after!!.run(); transport.dataWrite(); transport.flush(); frame++
				} catch (e: Exception) { e.printStackTrace(); done.countDown() }
			}, 0, 20, TimeUnit.MILLISECONDS)
			server.start()
			done.await(duration, TimeUnit.SECONDS)
		} finally {
			server.stop(1000); executor.shutdown(); check(executor.awaitTermination(5, TimeUnit.SECONDS))
			Files.writeString(evidence.resolve("final-proof.json"), mapper.writeValueAsString(proof()), StandardOpenOption.CREATE_NEW)
			integration.close(); frames.awaitTermination(5000); sender.close(); virtualBytes.close()
			Files.writeString(evidence.resolve("closed.json"), mapper.writeValueAsString(mapOf("receiver_alive" to integration.receiver.isAlive, "frames" to frame)), StandardOpenOption.CREATE_NEW)
		}
	}
}

private class NormalProbeTransport(private val capture: (ByteArray) -> Unit) : ProtobufBridge("normal-runtime-software-fixture") {
	val hmd = Tracker(Device(DeviceOrigin.STEAMVR), 0, "fixture-hmd", trackerPosition = TrackerPosition.HEAD, trackerNum = 0,
		hasPosition = true, hasRotation = true, isHmd = true, isComputed = true, allowFiltering = false, allowReset = false, allowMounting = false,
		trackRotDirection = false).also { it.status = LocalStatus.OK }
	private val handle = openInboundTransportSession()
	init {
		@Suppress("UNCHECKED_CAST")
		val registry = ProtobufBridge::class.java.getDeclaredField("remoteTrackersByTrackerId").apply { isAccessible = true }.get(this) as MutableMap<Int, Tracker>
		registry[0] = hmd; registerTrustedSteamVrHmd(hmd); reconnected()
	}
	fun feedHmd() {
		val p = Position.newBuilder().setTrackerId(0).setX(0f).setY(1.7f).setZ(0f).setQw(1f).setDataSource(Position.DataSource.FULL)
		messageReceived(ProtobufMessage.parseFrom(ProtobufMessage.newBuilder().setPosition(p).build().toByteArray()), handle); dataRead()
	}
	fun loseHmd() { messageReceived(ProtobufMessage.newBuilder().setTrackerStatus(dev.slimevr.desktop.platform.ProtobufMessages.TrackerStatus.newBuilder().setTrackerId(0).setStatus(dev.slimevr.desktop.platform.ProtobufMessages.TrackerStatus.Status.DISCONNECTED)).build(), handle); dataRead() }
	fun flush() = updateMessageQueue()
	override fun signalSend() = Unit
	override fun sendMessageReal(message: ProtobufMessage?): Boolean {
		val bytes = requireNotNull(message).toByteArray(); capture(bytes)
		val decoded = ProtobufMessage.parseFrom(bytes)
		if (decoded.hasUserAction() && decoded.userAction.name == "${DIRECT_CAPABILITY}?") {
			messageReceived(ProtobufMessage.newBuilder().setUserAction(decoded.userAction.toBuilder().setName(DIRECT_CAPABILITY)).build(), handle)
		}
		return true
	}
	override fun createNewTracker(trackerAdded: TrackerAdded): Tracker = error("Explicit software HMD registry")
	override fun startBridge() = Unit
	override fun stopBridge() = Unit
	override fun isConnected() = false
	override fun getShareSetting(role: TrackerRole) = false
	override fun changeShareSettings(role: TrackerRole?, share: Boolean) = Unit
	override fun updateShareSettingsAutomatically() = false
	override fun getAutomaticSharedTrackers() = false
	override fun setAutomaticSharedTrackers(value: Boolean) = Unit
	override fun getBridgeConfigKey() = "normal-runtime-software-fixture"
}
