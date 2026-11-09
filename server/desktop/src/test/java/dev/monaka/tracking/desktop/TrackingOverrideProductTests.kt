package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v2.Confidence
import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.protocol.v2.Validity
import dev.monaka.tracking.LogicalTracker
import dev.monaka.tracking.MonakaConfiguration
import dev.monaka.tracking.TrackerBodyAssignments
import dev.monaka.tracking.TrackerReference
import dev.monaka.tracking.TrackingOverrideGripOffset
import dev.monaka.tracking.TrackingOverrideHand
import dev.monaka.tracking.TrackingOverrideSelection
import dev.slimevr.desktop.platform.ProtobufBridge
import dev.slimevr.desktop.platform.ProtobufMessages.Position
import dev.slimevr.desktop.platform.ProtobufMessages.ProtobufMessage
import dev.slimevr.desktop.platform.ProtobufMessages.TrackerAdded
import dev.slimevr.desktop.platform.ProtobufMessages.UserAction
import dev.slimevr.desktop.platform.TrackingOverrideTransport
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerRole
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@EnabledOnOs(OS.WINDOWS)
class TrackingOverrideProductTests {
	@Test
	fun persistedHandsUseAdmittedUdpSamplesThroughTheProductionTickAndBridge(@TempDir dir: Path) {
		val fixture = Files.readAllBytes(Path.of(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json"))
		val left = assertIs<MtpPose>(assertIs<DecodeResult.Success>(MonakaCodec.decodeEnvelope(fixture)).value)
		val right = left.copy(publisher_id = "opaque-second-publisher")
		fun identity(pose: MtpPose) = LogicalTracker(pose.source_id, pose.tracker_id, pose.publisher_id)
		val half = sqrt(0.5)
		val config = dir.resolve("input.json")
		val assignments = TrackerBodyAssignments().also {
			it.configureSources(TrackingOverrideHand.LEFT.body, null, TrackerReference.mtp(identity(left)), true)
			it.configureSources(TrackingOverrideHand.RIGHT.body, null, TrackerReference.mtp(identity(right)), true)
		}
		MonakaConfiguration(
			left.coordinate_space,
			assignments,
			port = 0,
			trackingOverride = mapOf(
				TrackingOverrideHand.LEFT to TrackingOverrideSelection.confirmed(
					true, TrackingOverrideHand.LEFT, TrackerReference.mtp(identity(left)), left.coordinate_space,
					TrackingOverrideGripOffset(Vector3(1f, 2f, 3f)),
				),
				TrackingOverrideHand.RIGHT to TrackingOverrideSelection.confirmed(
					true, TrackingOverrideHand.RIGHT, TrackerReference.mtp(identity(right)), right.coordinate_space,
					TrackingOverrideGripOffset(Vector3(1f, 0f, 0f), Quaternion(half.toFloat(), half.toFloat(), 0f, 0f)),
				),
			),
		).save(config)
		val skeleton = HumanPoseManager(emptyList()).skeleton
		val bridge = Capture()
		var hook: Runnable? = null
		var now = 1_000_000_000L
		val errors = mutableListOf<Exception>()
		val integration = assertNotNull(
			MonakaServerIntegration.startIfEnabled(
				true, { MonakaConfiguration.load(config) }, { emptyList() }, skeleton, { hook = it },
				onFailure = { errors += it }, clock = { now }, configurationPath = config,
				publishTrackingOverride = bridge::publishTrackingOverride,
				trackingOverrideDriverReady = bridge::trackingOverrideDriverReady,
				trackingOverrideRegisteredHands = bridge::trackingOverrideRegisteredHands,
			),
		)
		integration.use {
			fun tick() {
				assertNotNull(hook).run()
				assertTrue(errors.isEmpty(), errors.joinToString())
				bridge.flush()
			}
			bridge.connect()
			tick()
			assertTrue(bridge.messages.none { it.hasTrackerAdded() || it.hasPosition() })
			val challenge = bridge.messages.mapNotNull { if (it.hasUserAction()) it.userAction else null }
				.single { it.name == "${TrackingOverrideTransport.CAPABILITY}?" }
			bridge.answer(challenge.toBuilder().setName(TrackingOverrideTransport.CAPABILITY).build())
			bridge.messages.clear()
			tick()
			val registered = bridge.messages.filter { it.hasTrackerAdded() }.associate { it.trackerAdded.trackerSerial to it.trackerAdded }
			assertEquals(TrackingOverrideHand.entries.map { it.serial }.toSet(), registered.keys)
			assertTrue(registered.values.all { it.trackerRole == TrackerRole.NONE.id })
			fun last(hand: TrackingOverrideHand) = bridge.messages.last {
				it.hasPosition() && it.position.trackerId == registered.getValue(hand.serial).trackerId
			}.position
			fun assertPosition(hand: TrackingOverrideHand, x: Double, y: Double, z: Double) {
				val packet = last(hand)
				assertEquals(Position.DataSource.FULL, packet.dataSource)
				assertEquals(x, packet.x.toDouble(), 1e-5)
				assertEquals(y, packet.y.toDouble(), 1e-5)
				assertEquals(z, packet.z.toDouble(), 1e-5)
				assertFalse(packet.hasVx())
			}
			fun assertUnavailable(hand: TrackingOverrideHand) {
				val packet = last(hand)
				assertEquals(Position.DataSource.NONE, packet.dataSource)
				assertFalse(packet.hasX() || packet.hasY() || packet.hasZ())
			}
			DatagramSocket().use { sender ->
				fun send(bytes: ByteArray) {
					sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), integration.receiver.port))
				}
				fun feed(pose: MtpPose, sequence: Long) {
					val sample = pose.copy(
						sequence = sequence,
						timestamp_ns = pose.timestamp_ns + sequence,
						sent_at_ns = pose.sent_at_ns + sequence,
					)
					val bytes = assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(sample)).value
					send(bytes)
					val deadline = System.nanoTime() + 5_000_000_000
					while (integration.runtime.mtp.sampleFor(identity(sample))?.pose != sample && System.nanoTime() < deadline) {
						tick()
						Thread.sleep(1)
					}
					assertEquals(sample, integration.runtime.mtp.sampleFor(identity(sample))?.pose)
				}
				val unsupportedConvention = fixture.toString(Charsets.UTF_8)
					.replace(left.coordinate_space.convention, "unsupported-convention").toByteArray(Charsets.UTF_8)
				val rejection = assertIs<DecodeResult.Failure>(MonakaCodec.decodeEnvelope(unsupportedConvention)).code.name
				assertFalse(integration.runtime.inbox.diagnostics().containsKey(rejection))
				send(unsupportedConvention)
				val deadline = System.nanoTime() + 5_000_000_000
				while (!integration.runtime.inbox.diagnostics().containsKey(rejection) && System.nanoTime() < deadline) {
					Thread.sleep(1)
				}
				assertEquals(1L, integration.runtime.inbox.diagnostics()[rejection])
				tick()
				assertNull(integration.runtime.mtp.sampleFor(identity(left)))
				assertUnavailable(TrackingOverrideHand.LEFT)

				bridge.messages.clear()
				feed(left, 0)
				assertPosition(TrackingOverrideHand.LEFT, 2.0, 4.0, 6.0)
				assertFalse(bridge.messages.any { it.hasPosition() && it.position.trackerId == registered.getValue(TrackingOverrideHand.RIGHT.serial).trackerId })
				feed(right.copy(position = listOf(10.0, 20.0, 30.0), orientation = listOf(0.0, half, 0.0, half)), 0)
				assertPosition(TrackingOverrideHand.RIGHT, 10.0, 20.0, 29.0)
				val q = last(TrackingOverrideHand.RIGHT)
				val dot = (q.qw + q.qx + q.qy - q.qz) * 0.5
				assertEquals(1.0, abs(dot), 1e-5)

				val axes = listOf(
					listOf(half, 0.0, 0.0, half) to listOf(2.0, -1.0, 5.0),
					listOf(0.0, half, 0.0, half) to listOf(4.0, 4.0, 2.0),
					listOf(0.0, 0.0, half, half) to listOf(-1.0, 3.0, 6.0),
				)
				for ((index, axis) in axes.withIndex()) {
					feed(left.copy(orientation = axis.first), index + 1L)
					assertPosition(TrackingOverrideHand.LEFT, axis.second[0], axis.second[1], axis.second[2])
				}
				feed(left.copy(
					modality = "rotation_only", tracking_state = "degraded", position = null,
					validity = Validity(false, true), confidence = Confidence(0.0, 1.0),
				), 4)
				assertUnavailable(TrackingOverrideHand.LEFT)
				feed(left.copy(
					modality = "none", tracking_state = "lost", position = null, orientation = null,
					validity = Validity(false, false), confidence = Confidence(0.0, 0.0),
				), 5)
				assertUnavailable(TrackingOverrideHand.LEFT)
				feed(left.copy(position = listOf(9.0, 8.0, 7.0)), 6)
				assertPosition(TrackingOverrideHand.LEFT, 10.0, 10.0, 10.0)
				feed(left.copy(
					coordinate_space = left.coordinate_space.copy(id = "other-room", revision = left.coordinate_space.revision + 1),
					position = listOf(12.0, 13.0, 14.0),
				), 7)
				assertPosition(TrackingOverrideHand.LEFT, 13.0, 15.0, 17.0)
				val status = ObjectMapper().readTree(integration.inputRequest(
					"""{"type":"monaka_inputs_v1","id":"status","action":"snapshot"}""",
				))
				assertEquals("FULL", status.at("/trackingOverride/hands/left/sourceStatus").asText())
				assertTrue(status.at("/trackingOverride/hands/left/calibrationCurrent").asBoolean())
				now += 500_000_000L
				tick()
				assertUnavailable(TrackingOverrideHand.LEFT)
				assertUnavailable(TrackingOverrideHand.RIGHT)
				val reconnected = right.copy(session_id = "33333333-3333-4333-8333-333333333333")
				feed(reconnected, 0)
				assertPosition(TrackingOverrideHand.RIGHT, 2.0, 2.0, 3.0)
				feed(reconnected.copy(orientation = listOf(0.0, 0.0, 0.0, 1.000009)), 1)
				assertPosition(TrackingOverrideHand.RIGHT, 2.0, 2.0, 3.0)
				val nearUnit = last(TrackingOverrideHand.RIGHT)
				assertEquals(1.0, abs((nearUnit.qw + nearUnit.qx) * half), 1e-5)
				feed(left, 8)
				assertPosition(TrackingOverrideHand.LEFT, 2.0, 4.0, 6.0)
				val replacement = left.copy(publisher_id = "third-opaque-publisher", position = listOf(40.0, 50.0, 60.0))
				feed(replacement, 0)
				val mapper = ObjectMapper()
				val active = mapper.readTree(integration.inputRequest(
					"""{"type":"monaka_inputs_v1","id":"before-change","action":"snapshot"}""",
				))
				val changed = mapper.readTree(integration.inputRequest(mapper.writeValueAsString(mapOf(
					"type" to "monaka_inputs_v1", "id" to "change-left", "action" to "save", "body" to "LEFT_HAND",
					"generation" to active["generation"].asText(), "manualSix" to true,
					"qSource" to identity(left).observationId, "sixDofSource" to identity(replacement).observationId,
				))))
				assertTrue(changed["ok"].asBoolean(), changed.toString())
				assertEquals(identity(replacement).observationId, changed.at("/trackingOverride/hands/left/source").asText())
				assertFalse(changed.at("/trackingOverride/hands/left/calibrationCurrent").asBoolean())
				tick()
				assertUnavailable(TrackingOverrideHand.LEFT)
				assertPosition(TrackingOverrideHand.RIGHT, 2.0, 2.0, 3.0)
				assertTrue(bridge.messages.none { it.hasTrackerAdded() })
				assertEquals(setOf(TrackingOverrideHand.LEFT.body, TrackingOverrideHand.RIGHT.body), integration.runtime.assignments.snapshot().targets.keys)
			}
		}
	}

	private class Capture : ProtobufBridge("tracking-override-test") {
		val messages = mutableListOf<ProtobufMessage>()
		fun connect() { openInboundTransportSession() }
		fun flush() = updateMessageQueue()
		fun answer(action: UserAction) {
			messageReceived(ProtobufMessage.newBuilder().setUserAction(action).build(), currentInboundTransportSession())
			dataRead()
		}
		override fun signalSend() = Unit
		override fun sendMessageReal(message: ProtobufMessage?): Boolean {
			messages += ProtobufMessage.parseFrom(requireNotNull(message).toByteArray())
			return true
		}
		override fun createNewTracker(trackerAdded: TrackerAdded): Tracker = error("No driver fixture input")
		override fun startBridge() = Unit
		override fun stopBridge() = Unit
		override fun isConnected() = true
		override fun getShareSetting(role: TrackerRole) = false
		override fun changeShareSettings(role: TrackerRole?, share: Boolean) = Unit
		override fun updateShareSettingsAutomatically() = false
		override fun getAutomaticSharedTrackers() = false
		override fun setAutomaticSharedTrackers(value: Boolean) = Unit
		override fun getBridgeConfigKey() = "tracking-override-test"
	}
}
