package dev.monaka.tracking.desktop

import dev.monaka.tracking.TrackingOverrideFrame
import dev.monaka.tracking.TrackingOverrideHand
import dev.monaka.tracking.TrackingOverrideHand.LEFT
import dev.monaka.tracking.TrackingOverrideHand.RIGHT
import dev.monaka.tracking.TrackingOverridePose
import dev.monaka.tracking.TrackingOverrideSourceStatus
import dev.slimevr.desktop.platform.ProtobufMessages.Position
import dev.slimevr.desktop.platform.ProtobufMessages.ProtobufMessage
import dev.slimevr.desktop.platform.ProtobufMessages.UserAction
import dev.slimevr.desktop.platform.TrackingOverrideTransport
import dev.slimevr.desktop.platform.TransportSessionHandle
import dev.slimevr.tracking.trackers.TrackerRole
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingOverrideTransportTests {
	private class Capture {
		var session: TransportSessionHandle? = TransportSessionHandle("first")
		var now = 500L
		val queue = mutableListOf<ProtobufMessage>()
		val written = mutableListOf<ProtobufMessage>()
		val transport = TrackingOverrideTransport({ session }, { queue += it }, {}, { now }, { now to 1_000_000_000L })
		fun publish(vararg frames: Pair<TrackingOverrideHand, TrackingOverrideFrame>) = transport.publish(mapOf(*frames), now)
		fun flush(): List<ProtobufMessage> {
			written.clear()
			transport.flush {
				written += it
				true
			}
			return written.toList()
		}
		fun reply(query: UserAction, from: TransportSessionHandle? = session) =
			transport.accept(query.toBuilder().setName(query.name.removeSuffix("?")).build(), from)
	}
	private fun full(x: Float) = TrackingOverrideFrame(
		TrackingOverrideSourceStatus.FULL,
		TrackingOverridePose(Vector3(x, 2f, 3f), Quaternion.IDENTITY, remainingFreshNanos = 10L),
	)
	private val off = TrackingOverrideFrame(TrackingOverrideSourceStatus.OFF)

	@Test fun noTrafficWhenOffAndOnlyCurrentNegotiationCanRegister() {
		val c = Capture()
		c.publish(LEFT to off, RIGHT to off)
		assertTrue(c.queue.isEmpty())
		assertTrue(c.flush().isEmpty())
		c.publish(LEFT to full(1f))
		val query = c.queue.single().userAction
		assertEquals("monaka-tracking-override-v1?", query.name)
		c.reply(query, TransportSessionHandle("first"))
		assertFalse(c.transport.ready(), "Equal epoch text cannot authenticate an old session object")
		c.reply(query)
		assertTrue(c.transport.ready())
		c.publish(LEFT to full(1f), RIGHT to off)
		val initial = c.flush()
		assertEquals(LEFT.serial, initial.first().trackerAdded.trackerSerial)
		assertEquals(TrackerRole.NONE.id, initial.first().trackerAdded.trackerRole)
		val activation = initial.single { it.hasUserAction() && it.userAction.name == "${TrackingOverrideTransport.ACTIVATED}?" }.userAction.toBuilder()
			.setName("monaka-tracking-override-activated-v1").putActionArguments("driver", "slimevr").build()
		c.transport.accept(activation, c.session)
		assertEquals(setOf(LEFT), c.transport.registeredHands)
		c.publish(LEFT to full(2f))
		val oldSession = c.session
		c.session = TransportSessionHandle("second")
		assertFalse(c.transport.ready())
		assertTrue(c.flush().isEmpty(), "Queued prior-transport frame must be discarded")
		c.transport.accept(activation, oldSession)
		assertEquals(setOf(LEFT), c.transport.registeredHands, "Evidence survives intentional SteamVR stop in Core memory")
		c.publish(LEFT to full(4f))
		c.reply(query)
		assertFalse(c.transport.ready(), "New session needs a fresh challenge")
		c.reply(c.queue.last().userAction)
		c.publish(LEFT to full(4f))
		assertEquals(LEFT.serial, c.flush().first().trackerAdded.trackerSerial)
	}

	@Test fun coalescesAndRechecksAgeAtPipeWriteThenRecoversWithStableHandIds() {
		val c = Capture()
		c.publish(LEFT to full(99f))
		c.reply(c.queue.single().userAction)
		c.publish(LEFT to full(99f))
		c.publish(LEFT to full(1f), RIGHT to full(2f))
		val initial = c.flush()
		val added = initial.filter { it.hasTrackerAdded() }.map { it.trackerAdded }
		assertEquals(listOf(LEFT.serial, RIGHT.serial), added.map { it.trackerSerial })
		assertEquals(listOf(-1, -2), added.map { it.trackerId })
		val captured = initial.filter { it.hasPosition() }.toMutableList()
		val leases = initial.filter { it.hasUserAction() && it.userAction.name == "${TrackingOverrideTransport.LEASE}?" }
		assertEquals(listOf(LEFT.serial, RIGHT.serial), leases.map { it.userAction.actionArgumentsMap["serial"] })
		assertTrue(leases.all { it.userAction.actionArgumentsMap["deadline"] == "509" })
		assertEquals(listOf(1f, 2f), captured.map { it.position.x })
		c.publish(LEFT to full(99f), RIGHT to off)
		c.now += 10
		val expired = c.flush().filter { it.hasPosition() }
		assertEquals(2, expired.size)
		for (packet in expired) {
			assertEquals(Position.DataSource.NONE, packet.position.dataSource)
			assertFalse(packet.position.hasX() || packet.position.hasY() || packet.position.hasZ())
		}
		captured += expired.first()
		c.publish(LEFT to full(4f), RIGHT to off)
		val recovered = c.flush().single { it.hasPosition() }
		assertEquals(-1, recovered.position.trackerId)
		assertEquals(Position.DataSource.FULL, recovered.position.dataSource)
		assertEquals(4f, recovered.position.x)
		assertFalse(recovered.position.hasVx())
		captured += recovered
		val directory = File(System.getProperty("monaka.direct.captures"), "tracking-override").apply { mkdirs() }
		captured.forEachIndexed { index, packet -> directory.resolve("$index.pb").writeBytes(packet.toByteArray()) }
	}
}
