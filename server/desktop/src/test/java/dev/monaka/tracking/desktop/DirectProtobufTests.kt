package dev.monaka.tracking.desktop

import dev.monaka.protocol.v2.*
import dev.monaka.tracking.*
import dev.slimevr.desktop.platform.ProtobufBridge
import dev.slimevr.desktop.platform.ProtobufMessages.*
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerRole
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import dev.slimevr.tracking.processor.HumanPoseManager
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.*

class DirectProtobufTests {
	private val body = TrackerPosition.HIP
	private fun assignments() = TrackerBodyAssignments().also {
		it.configure(body, TrackerReference.slime("main"), outputMode = OutputMode.DIRECT)
	}
	private fun full() = EffectiveConstraint(body,
		ResolvedComponent(Vector3(3f, 2f, 1f), "main", ObservationQuality.TRACKED, 10),
		ResolvedComponent(Quaternion.IDENTITY, "main", ObservationQuality.TRACKED, 10))

	@Test fun directSerializationKeepsIdentityAndOmitsPositionOnRotationOnlyAndNone() {
		val bridge = Capture()
		DirectConstraintOutput(assignments().snapshot()) { 100 }.use { output ->
			val tracker = output.trackers.getValue(body)
			bridge.configureDirectOutputs(listOf(tracker)); bridge.addSharedTracker(tracker)
			bridge.connect(); bridge.flush()
			val query = bridge.messages.single { it.hasUserAction() }.userAction
			assertEquals("${ProtobufBridge.DIRECT_CAPABILITY}?", query.name)
			assertTrue(bridge.messages.none { it.hasTrackerAdded() })
			bridge.ack(query); bridge.flush()
			val registration = bridge.messages.single { it.hasTrackerAdded() }.trackerAdded
			assertEquals(tracker.name, registration.trackerSerial)
			assertEquals(TrackerRole.WAIST.id, registration.trackerRole)
			val all = full()
			val fallback = all.copy(position = null, rotation = all.rotation!!.copy(value = Quaternion(0f, 1f, 0f, 0f), sourceId = "fallback"))
			for ((index, constraint) in listOf(all, fallback, EffectiveConstraint(body), all).withIndex()) {
				output.apply(mapOf(body to constraint))
				bridge.messages.clear(); bridge.frame()
				assertTrue(bridge.messages.none { it.hasTrackerAdded() || it.hasTrackerStatus() })
				val packet = bridge.messages.single().position
				val captures = File(System.getProperty("monaka.direct.captures")).also { it.mkdirs() }
				File(captures, "$index.pb").writeBytes(bridge.messages.single().toByteArray())
				assertEquals(registration.trackerId, packet.trackerId)
				assertEquals(constraint.position != null, packet.hasX())
				assertEquals(packet.hasX(), packet.hasY()); assertEquals(packet.hasX(), packet.hasZ())
				assertTrue(packet.hasDataSource())
				assertEquals(when { constraint.rotation == null -> Position.DataSource.NONE; constraint.position != null -> Position.DataSource.FULL; else -> Position.DataSource.IMU }, packet.dataSource)
				constraint.position?.let { assertEquals(it.value, Vector3(packet.x, packet.y, packet.z)) }
				constraint.rotation?.let { assertEquals(it.value, Quaternion(packet.qw, packet.qx, packet.qy, packet.qz)) }
				assertFalse(packet.hasVx())
			}
		}
	}

	@Test fun udpAdmissionToProductionHookToDirectProtobufWithoutIkPosition() {
		val p = assertIs<MtpPose>(assertIs<DecodeResult.Success>(MonakaCodec.decodeEnvelope(
			File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes(),
		)).value)
		val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		val assignment = TrackerBodyAssignments().also { it.configure(body, TrackerReference.mtp(key), outputMode = OutputMode.DIRECT) }
		val skeleton = HumanPoseManager(emptyList()).skeleton
		val bridge = Capture()
		var hook: Runnable? = null
		var now = 1_000_000_000L
		val failures = mutableListOf<Exception>()
		val integration = MonakaServerIntegration.startIfEnabled(
			true, { MonakaConfiguration(p.coordinate_space, assignment, port = 0) }, { emptyList() }, skeleton,
			{ hook = it }, { failures += it }, { now },
			configureDirectOutputs = { trackers -> bridge.configureDirectOutputs(trackers); trackers.forEach(bridge::addSharedTracker) },
			nextTrackerId = { 100 },
		)!!
		integration.use {
			bridge.connect(); bridge.flush(); bridge.ack(bridge.messages.single().userAction); bridge.flush()
			DatagramSocket().use { socket ->
				for ((index, mode) in listOf("full", "rotation_only", "none", "full").withIndex()) {
					val pose = p.copy(sequence = index.toLong(), modality = mode,
						position = if (mode == "full") listOf(4.0, 5.0, 6.0) else null,
						orientation = if (mode == "none") null else p.orientation,
						validity = Validity(mode == "full", mode != "none"),
						confidence = Confidence(if (mode == "full") 1.0 else 0.0, if (mode != "none") 1.0 else 0.0),
						tracking_state = when(mode) { "full" -> "tracked"; "rotation_only" -> "degraded"; else -> "lost" })
					val bytes = assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(pose)).value
					socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), integration.receiver.port))
					val deadline = System.nanoTime() + 5_000_000_000
					while (integration.runtime.mtp.samples()[key]?.pose?.sequence != index.toLong() && System.nanoTime() < deadline) {
						hook!!.run(); Thread.sleep(2)
					}
					assertEquals(index.toLong(), integration.runtime.mtp.samples()[key]?.pose?.sequence)
					assertNull(skeleton.hipTracker)
					bridge.messages.clear(); bridge.frame()
					val packet = bridge.messages.single().position
					assertEquals(100, packet.trackerId)
					assertEquals(mode == "full", packet.hasX())
					if (mode == "full") assertEquals(Vector3(4f, 5f, 6f), Vector3(packet.x, packet.y, packet.z))
				}
			}
			now += 500_000_001; hook!!.run(); bridge.messages.clear(); bridge.frame()
			assertEquals(Position.DataSource.NONE, bridge.messages.single().position.dataSource)
			assertTrue(failures.isEmpty())
		}
		assertNull(hook); assertFalse(integration.receiver.isAlive)
		assertNull(integration.directOutput.trackers.getValue(body).resolvedDirectConstraint!!.position)
	}

	@Test fun oldDriverAndReconnectFailClosedWhileLegacySerializationIsUnchanged() {
		val bridge = Capture()
		val legacy = Tracker(null, 2, "human://Hip", trackerPosition = body, hasPosition = true, hasRotation = true,
			allowFiltering = false, allowReset = false).also { it.position = Vector3(9f, 8f, 7f) }
		DirectConstraintOutput(assignments().snapshot()) { 100 }.use { output ->
			val tracker = output.trackers.getValue(body); output.apply(mapOf(body to full()))
			bridge.configureDirectOutputs(listOf(tracker)); bridge.addSharedTracker(legacy); bridge.addSharedTracker(tracker)
			bridge.connect(); bridge.flush()
			val query = bridge.messages.last { it.hasUserAction() }.userAction
			bridge.messages.clear(); bridge.frame()
			val old = bridge.messages.single().position
			assertEquals(legacy.id, old.trackerId); assertTrue(old.hasX()); assertFalse(old.hasDataSource())
			assertNull(legacy.sampleModality)
			assertEquals(legacy.position, Vector3(old.x, old.y, old.z))
			bridge.ack(query); bridge.ack(query); bridge.flush()
			assertEquals(1, bridge.messages.count { it.hasTrackerAdded() })
			bridge.queueFrame() // deliberately leave Direct pose queued at disconnect
			bridge.disconnect(); bridge.connect(); bridge.flush()
			val nextQuery = bridge.messages.last { it.hasUserAction() }.userAction
			assertNotEquals(query.actionArgumentsMap, nextQuery.actionArgumentsMap)
			bridge.messages.clear(); bridge.ack(query); bridge.frame() // stale acknowledgement cannot reopen gate
			assertTrue(bridge.messages.all { it.hasPosition() && it.position.trackerId == legacy.id })
			bridge.messages.clear(); bridge.ack(nextQuery); bridge.frame()
			assertEquals(1, bridge.messages.count { it.hasTrackerAdded() })
			assertEquals(1, bridge.messages.count { it.hasPosition() && it.position.trackerId == tracker.id })
		}
	}

	private class Capture : ProtobufBridge("direct-test") {
		val messages = mutableListOf<ProtobufMessage>()
		override fun signalSend() = Unit
		override fun sendMessageReal(message: ProtobufMessage?): Boolean {
			messages += ProtobufMessage.parseFrom(requireNotNull(message).toByteArray()); return true
		}
		fun connect() = reconnected()
		fun disconnect() = disconnected()
		fun flush() = updateMessageQueue()
		fun ack(query: UserAction) {
			messageReceived(ProtobufMessage.newBuilder().setUserAction(query.toBuilder().setName(DIRECT_CAPABILITY)).build()); dataRead()
		}
		fun queueFrame() { messageReceived(ProtobufMessage.getDefaultInstance()); dataRead(); dataWrite() }
		fun frame() { queueFrame(); flush() }
		override fun createNewTracker(trackerAdded: TrackerAdded): Tracker = error("No runtime driver input")
		override fun startBridge() = Unit
		override fun stopBridge() = Unit
		override fun isConnected() = false
		override fun getShareSetting(role: TrackerRole) = false
		override fun changeShareSettings(role: TrackerRole?, share: Boolean) = Unit
		override fun updateShareSettingsAutomatically() = false
		override fun getAutomaticSharedTrackers() = false
		override fun setAutomaticSharedTrackers(value: Boolean) = Unit
		override fun getBridgeConfigKey() = "direct-test"
	}
}
