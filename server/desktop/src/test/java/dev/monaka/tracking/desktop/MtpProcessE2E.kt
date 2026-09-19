package dev.monaka.tracking.desktop

import com.google.gson.GsonBuilder
import dev.monaka.protocol.v2.*
import dev.monaka.tracking.*
import dev.slimevr.desktop.platform.ProtobufBridge
import dev.slimevr.desktop.platform.ProtobufMessages.*
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.io.File
import java.io.PrintStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Standalone software composition runner, deliberately outside the JUnit/default test task. */
object MtpProcessE2E {
	@JvmStatic fun main(args: Array<String>) {
		val directory = File(args.single()).absoluteFile
		check(directory.mkdirs()) { "Evidence directory must be new: $directory" }
		val console = System.out
		val errors = System.err
		var failed: Throwable? = null
		val run = Scenario(directory)
		PrintStream(File(directory, "e2e.log"), Charsets.UTF_8).use { log ->
			System.setOut(log); System.setErr(log)
			try { run.execute() } catch (t: Throwable) { failed = t; t.printStackTrace() }
			finally { System.setOut(console); System.setErr(errors) }
		}
		File(directory, "result.json").writeText(GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(mapOf(
			"result" to if (failed == null) "PASS" else "FAIL",
			"failed_stage" to if (failed == null) null else run.stage,
			"error" to failed?.toString(), "passed" to run.passed,
			"consumer_pid" to ProcessHandle.current().pid(), "publisher_pids" to run.publisherPids,
			"wire" to 2, "steamvr_runtime" to "NOT RUN", "hardware" to "NOT RUN",
			"boundary" to "Production intake/private IK input/computed tracker/ProtobufBridge bytes; synthetic external publisher and captured transport",
		)))
		console.println("RESULT=${if (failed == null) "PASS" else "FAIL"} mtp-process-e2e stopped_at=${if (failed == null) "complete" else run.stage} evidence=$directory")
		if (failed != null) exitProcess(1)
	}
}

private class Scenario(private val directory: File) {
	var stage = "startup"
	val passed = mutableListOf<String>()
	val publisherPids = mutableListOf<Long>()
	private val base = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
	private val key = LogicalTracker(base.source_id, base.tracker_id, base.publisher_id)
	private val head = tracker(0, TrackerPosition.HEAD, true).also { it.position = Vector3(0f, 1.7f, 0f) }
	private val raw = listOf(head)
	private val hpm = HumanPoseManager(raw).also { it.setLegTweaksEnabled(false) }
	private val output = CapturedOutput()
	private var hook: Runnable? = null
	private lateinit var integration: MonakaServerIntegration
	private val runtime get() = integration.runtime
	private var timestamp = base.timestamp_ns
	private var command = 0

	private fun step(name: String, body: () -> Unit) {
		stage = name; println("START $name")
		body(); passed += name; println("PASS $name")
	}
	private fun tick() {
		// Same relevant order as VRServer: bridge read -> beforePoseUpdate -> existing pose update -> bridge write.
		output.readHeartbeat(); hook!!.run(); hpm.update(); output.dataWrite(); output.flush()
		check(integration.receiver.failure == null && integration.receiver.isAlive)
	}
	private fun await(description: String, condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
		do {
			tick()
			if (condition()) return
			check(System.nanoTime() < deadline) { "$description timed out; diagnostics=${runtime.inbox.diagnostics()} samples=${runtime.mtp.samples()}" }
			Thread.sleep(5)
		} while (true)
	}
	private fun constraint() = runtime.pipeline.resolve(TrackerPosition.HIP, runtime.clock())
	private fun absent() = constraint().let { it.position == null && it.rotation == null } && hpm.skeleton.hipTracker == null
	private fun count(name: String) = runtime.inbox.diagnostics()[name] ?: 0L
	private fun pose(sequence: Long, x: Double = 0.0, modality: String = "full", session: String = base.session_id): MtpPose {
		timestamp += 1_000_000
		return base.copy(sequence = sequence, session_id = session, timestamp_ns = timestamp, sent_at_ns = timestamp,
			position = listOf(origin.x + x, origin.y.toDouble(), origin.z.toDouble()),
			orientation = listOf(0.0, kotlin.math.sin(x), 0.0, kotlin.math.cos(x)),
			modality = modality, validity = Validity(modality == "full", modality != "none"),
			confidence = Confidence(if (modality == "full") 1.0 else 0.0, if (modality == "none") 0.0 else 1.0),
			tracking_state = when (modality) { "none" -> "lost"; "rotation_only" -> "degraded"; else -> "tracked" },
			input = base.input.copy(sequence = sequence),
		)
	}
	private var origin = Vector3(0f, 0f, 0f)
	private fun state(p: MtpPose, sequence: Long, presence: String) = MtpTrackerState(
		p.version, p.source_id, p.session_id, p.clock_id, sequence, p.timestamp_ns, p.sent_at_ns,
		p.timestamp_kind, p.tracker_id, presence, if (presence == "absent") "lost" else "tracked",
		p.coordinate_space, p.capabilities, if (presence == "absent") "none" else "full", p.publisher_id, null, p.mapping_revision,
	)
	private fun send(publisher: Publisher, message: Envelope) {
		val file = File(directory, "packet-${command++}.json")
		val encoded = MonakaCodec.encodeEnvelope(message)
		check(encoded is EncodeResult.Success) { "Scenario packet rejected by pinned codec: $encoded" }
		file.writeBytes(encoded.value)
		println("SEND ${file.name} ${file.readText()}")
		publisher.send(file)
	}
	private fun accept(publisher: Publisher, p: MtpPose) {
		send(publisher, p)
		val identity = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		await("accept ${p.session_id}/${p.sequence}") { runtime.mtp.samples()[identity]?.pose == p }
	}
	private fun reject(publisher: Publisher, p: MtpPose, reason: String) {
		val before = runtime.mtp.samples()
		val counter = count(reason)
		send(publisher, p)
		await(reason) { count(reason) == counter + 1 }
		check(runtime.mtp.samples() == before) { "Rejected packet changed samples/age" }
	}
	private fun publisher(port: Int) = Publisher(directory, port, publisherPids.size).also { publisherPids += it.pid }

	fun execute() {
		step("feature-off-slime-equivalence") { featureOff() }
		hpm.skeleton.ikSolver.enabled = false; hpm.update()
		origin = hpm.skeleton.computedHipTracker!!.position
		integration = MonakaServerIntegration.startIfEnabled(
			true, { MonakaConfiguration(base.coordinate_space, TrackerBodyAssignments(mapOf(key to TrackerPosition.HIP)), port = 0) },
			{ raw }, hpm.skeleton, { hook = it }, { throw it },
		)!!
		val port = integration.receiver.port
		println("consumer=${ProcessHandle.current().pid()} loopback_port=$port key=$key")
		integration.use {
			for (computed in hpm.computedTrackers) output.addSharedTracker(computed)
			output.flush()
			var verifyCalibration: () -> Unit = { error("Calibration not captured") }
			publisher(port).use { primary ->
				step("full-registration-existing-ik-protobuf") {
					accept(primary, pose(0))
					check(runtime.runner.snapshot().keys == setOf("slime", "mtp"))
					check(runtime.mtp.samples().keys == setOf(key))
					check(hpm.skeleton.hipTracker!!.name == "monaka-private:HIP:${key.observationId}")
					check(hpm.skeleton.hipTracker!!.hasPosition && hpm.skeleton.hipTracker!!.hasRotation)
					check(raw.none { it.name.startsWith("monaka-private:") })
					hpm.skeleton.ikSolver.resetOffsets(); hpm.skeleton.ikSolver.enabled = true; tick()
					val initial = hpm.skeleton.computedHipTracker!!.position
					val registration = output.registrations.toMap()
					for (seq in 1L..30L) {
						accept(primary, pose(seq, seq * 0.003))
						check(constraint().position!!.sourceId == key.observationId)
						check(constraint().position!!.value == hpm.skeleton.hipTracker!!.position)
						output.verify(hpm.computedTrackers)
					}
					val moved = hpm.skeleton.computedHipTracker!!.position
					println("IK hip initial=$initial moved=$moved")
					check(moved.x - initial.x > 0.005f) { "Existing IK did not move computed hip" }
					check(registration == output.registrations && registration.size == hpm.computedTrackers.size)
				}
				val calibration = hpm.skeleton.ikSolver.calibrationSnapshot()
				check(calibration.keys.any { it.first.startsWith("monaka-private:HIP:") })
				verifyCalibration = { check(hpm.skeleton.ikSolver.calibrationSnapshot() == calibration) }
				step("rotation-only-none-full-calibration") {
					val fullRotation = hpm.skeleton.computedHipTracker!!.getRotation()
					accept(primary, pose(31, 0.25, "rotation_only"))
					check(constraint().position == null && constraint().rotation != null)
					check(!hpm.skeleton.hipTracker!!.hasPosition && hpm.skeleton.hipTracker!!.hasRotation)
					check(hpm.skeleton.hipTracker!!.getRotation() == constraint().rotation!!.value)
					check(hpm.skeleton.computedHipTracker!!.getRotation() != fullRotation)
					output.verify(hpm.computedTrackers)
					accept(primary, pose(32, 99.0, "none")); check(absent())
					accept(primary, pose(33, 0.05)); check(constraint().position != null)
					verifyCalibration()
					output.verify(hpm.computedTrackers)
				}
				step("duplicate-reorder-age-unchanged") {
					reject(primary, pose(33, 99.0), "DuplicateOrOldSequence")
					reject(primary, pose(20, 99.0), "DuplicateOrOldSequence")
				}
				step("publisher-source-tracker-identity-isolation") {
					val p = pose(0)
					accept(primary, p.copy(tracker_id = "sibling"))
					accept(primary, p.copy(source_id = "other-source", input = p.input.copy(source_id = "other-source")))
					accept(primary, p.copy(publisher_id = "other-publisher"))
					check(runtime.mtp.samples().keys.size == 4)
					check(runtime.mtp.samples().getValue(key).pose.sequence == 33L)
					check(runtime.assignments.snapshot().entries == mapOf(key to TrackerPosition.HIP))
				}
				step("active-session-and-peer-rejection") {
					accept(primary, pose(34))
					publisher(port).use { competitor ->
						// Refresh after process startup; startup latency must not expire the lease under test.
						accept(primary, pose(35))
						reject(competitor, pose(36), "PeerMismatch")
						reject(competitor, pose(0, session = UUID.randomUUID().toString()), "ActiveLease")
					}
				}
				step("state-loss-timestamp-watermark-metadata-no-revival") {
					val lost = pose(36)
					val validated = count("Validated")
					send(primary, state(lost, 0, "absent"))
					await("absent state") { count("Validated") > validated && key !in runtime.mtp.samples() && absent() }
					val next = count("Validated")
					send(primary, state(lost, 1, "present")); send(primary, lost)
					await("metadata and pre-loss pose drained") { count("Validated") >= next + 2 }
					// Validated is worker-owned; observe the sequence rejection to ensure server admission finished.
					reject(primary, lost, "DuplicateOrOldSequence"); check(absent())
					accept(primary, pose(37)); check(constraint().position != null)
				}
				step("stale-age-and-metadata-do-not-refresh") {
					await("local clock age range") { runtime.clock() > 900_000_000 }
					val stale = pose(38).let { it.copy(sent_at_ns = it.timestamp_ns + 800_000_000) }
					accept(primary, stale); check(absent()) // Stored diagnostic sample is NOT a usable constraint.
					val sample = runtime.mtp.samples().getValue(key)
					send(primary, state(stale, 2, "present"))
					reject(primary, stale, "DuplicateOrOldSequence")
					check(runtime.mtp.samples().getValue(key) == sample && absent())
					accept(primary, pose(39)); check(constraint().position != null)
				}
				step("source-disappearance-timeout-loss") {
					primary.close() // Real process exits. No fabricated lost packet.
					await("500ms source timeout") { absent() }
					output.verify(hpm.computedTrackers)
				}
			}
			step("restart-session-recovery-and-retired-replay") {
				publisher(port).use { restarted ->
					val session = UUID.randomUUID().toString()
					accept(restarted, pose(0, 0.04, session = session))
					check(count("SessionChanged") == 1L && constraint().position != null)
					check(runtime.mtp.samples().keys.none { it.lifetimeId == key.lifetimeId && it != key })
					check(runtime.mtp.samples().keys.size == 3) // Other source/publisher lifetimes survived.
					check(hpm.skeleton.hipTracker!!.name == "monaka-private:HIP:${key.observationId}")
					reject(restarted, pose(1000, 99.0), "RetiredSession")
					for (seq in 1L..10L) accept(restarted, pose(seq, 0.04 + seq * 0.002, session = session))
					verifyCalibration()
					output.verify(hpm.computedTrackers)
				}
			}
			println("final diagnostics=${runtime.inbox.diagnostics()}")
			check(count("QueueFull") == 0L && count("BackendFailure:mtp") == 0L)
		}
		step("receiver-close-unregister-and-port-release") {
			check(hook == null && !integration.receiver.isAlive && runtime.mtp.samples().isEmpty())
			check(runtime.pipeline.observationCount == 0)
			DatagramSocket(port, InetAddress.getLoopbackAddress()).use { }
		}
	}

	private fun featureOff() {
		val a = listOf(tracker(10, TrackerPosition.HEAD, true), tracker(11, TrackerPosition.HIP))
		val b = listOf(tracker(10, TrackerPosition.HEAD, true), tracker(11, TrackerPosition.HIP))
		val expected = HumanPoseManager(a); val actual = HumanPoseManager(b)
		expected.setLegTweaksEnabled(false); actual.setLegTweaksEnabled(false)
		check(MonakaServerIntegration.startIfEnabled(false, { error("OFF read config/opened UDP") }, { b }, actual.skeleton,
			{ error("OFF registered a hook") }) == null)
		// Reserve an isolated sink while a real publisher sends. OFF never consumes MTP or opens its own socket.
		DatagramSocket(0, InetAddress.getLoopbackAddress()).use { sink ->
			publisher(sink.localPort).use { publisher ->
				repeat(30) { frame ->
					send(publisher, pose(frame.toLong(), 99.0))
					for (trackers in listOf(a, b)) {
						trackers[0].position = Vector3(frame * 0.001f, 1.7f, 0f)
						trackers[1].setRotation(Quaternion(kotlin.math.cos(frame * 0.01f), 0f, kotlin.math.sin(frame * 0.01f), 0f))
					}
					expected.update(); actual.update()
					check(expected.computedTrackers.size == actual.computedTrackers.size)
					for ((left, right) in expected.computedTrackers.zip(actual.computedTrackers)) {
						check(left.position == right.position && left.getRotation() == right.getRotation())
					}
				}
			}
		}
	}
}

private fun tracker(id: Int, body: TrackerPosition, position: Boolean = false) = Tracker(
	null, id, "slime-input:$id", trackerPosition = body, hasPosition = position, hasRotation = true,
	allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false, isHmd = body == TrackerPosition.HEAD,
).also { it.status = TrackerStatus.OK }

/** Only the OS transport is replaced. Registration, dataRead/dataWrite and protobuf encoding are production. */
private class CapturedOutput : ProtobufBridge("mtp-process-e2e-capture") {
	val registrations = linkedMapOf<Int, String>()
	private val positions = mutableMapOf<Int, Position>()
	override fun signalSend() = Unit
	override fun sendMessageReal(message: ProtobufMessage?): Boolean {
		val decoded = ProtobufMessage.parseFrom(requireNotNull(message).toByteArray())
		if (decoded.hasTrackerAdded()) {
			val added = decoded.trackerAdded
			check(added.trackerSerial.startsWith("human://"))
			check(registrations.put(added.trackerId, added.trackerSerial) == null)
			println("OUTPUT registration=$added")
		}
		if (decoded.hasPosition()) positions[decoded.position.trackerId] = decoded.position
		return true
	}
	fun readHeartbeat() { positions.clear(); messageReceived(ProtobufMessage.getDefaultInstance()); dataRead() }
	fun flush() = updateMessageQueue()
	fun verify(trackers: List<Tracker>) {
		for (tracker in trackers) {
			val p = positions.getValue(tracker.id); val q = tracker.getRotation()
			check(p.hasX())
			check(Vector3(p.x, p.y, p.z) == tracker.position)
			check(Quaternion(p.qw, p.qx, p.qy, p.qz) == q)
		}
		println("OUTPUT verified ${trackers.size} computed tracker messages; hip=${trackers.firstOrNull { it.trackerPosition == TrackerPosition.HIP }?.position}")
	}
	override fun createNewTracker(trackerAdded: TrackerAdded): Tracker = error("No simulated driver input trackers")
	override fun startBridge() = Unit
	override fun stopBridge() = Unit
	override fun isConnected() = false // Never claim an actual SteamVR connection.
	override fun getShareSetting(role: TrackerRole) = false
	override fun changeShareSettings(role: TrackerRole?, share: Boolean) = Unit
	override fun updateShareSettingsAutomatically() = false
	override fun getAutomaticSharedTrackers() = false
	override fun setAutomaticSharedTrackers(value: Boolean) = Unit
	override fun getBridgeConfigKey() = "e2e-captured-transport"
}

private class Publisher(directory: File, port: Int, index: Int) : AutoCloseable {
	private val acknowledgements = LinkedBlockingQueue<String>()
	private val process = ProcessBuilder(
		File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").absolutePath,
		"-cp", System.getProperty("java.class.path"), MtpScenarioPublisher::class.java.name, port.toString(),
	).redirectError(File(directory, "publisher-$index.stderr.log")).start()
	val pid = process.pid()
	private val writer = process.outputStream.bufferedWriter()
	private val reader = thread(name = "publisher-$index-stdout", isDaemon = true) {
		process.inputStream.bufferedReader().useLines { lines ->
			lines.forEach { println("publisher[$pid] $it"); acknowledgements.put(it) }
		}
	}
	private var closed = false
	init {
		try { check(acknowledgements.poll(10, TimeUnit.SECONDS) == "READY $pid") { "Publisher startup failed; pid=$pid" } }
		catch (t: Throwable) { close(); throw t }
	}
	fun send(file: File) {
		writer.write(file.absolutePath); writer.newLine(); writer.flush()
		check(acknowledgements.poll(5, TimeUnit.SECONDS) == "SENT ${file.name}") { "Publisher failed/stalled pid=$pid file=$file" }
	}
	override fun close() {
		if (closed) return
		closed = true
		try {
			writer.close()
			check(process.waitFor(5, TimeUnit.SECONDS)) { "Publisher did not exit pid=$pid" }
			check(process.exitValue() == 0) { "Publisher exit=${process.exitValue()} pid=$pid" }
		} finally {
			if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
			reader.join(2000)
		}
	}
}

/** Persistent external JVM/socket: validates and encodes every scenario packet with the pinned v2 codec. */
object MtpScenarioPublisher {
	@JvmStatic fun main(args: Array<String>) {
		DatagramSocket(0, InetAddress.getLoopbackAddress()).use { socket ->
			println("READY ${ProcessHandle.current().pid()}")
			System.`in`.bufferedReader().forEachLine { path ->
				val file = File(path)
				val envelope = (MonakaCodec.decodeEnvelope(file.readBytes()) as DecodeResult.Success).value
				val data = (MonakaCodec.encodeEnvelope(envelope) as EncodeResult.Success).value
				socket.send(DatagramPacket(data, data.size, InetAddress.getLoopbackAddress(), args.single().toInt()))
				println("SENT ${file.name}")
			}
		}
	}
}
