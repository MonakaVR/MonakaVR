package dev.slimevr.desktop.platform

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary
import dev.monaka.tracking.TrackingOverrideFrame
import dev.monaka.tracking.TrackingOverrideHand
import dev.monaka.tracking.TrackingOverrideSourceStatus
import dev.slimevr.desktop.platform.ProtobufMessages.Position
import dev.slimevr.desktop.platform.ProtobufMessages.ProtobufMessage
import dev.slimevr.desktop.platform.ProtobufMessages.TrackerAdded
import dev.slimevr.desktop.platform.ProtobufMessages.UserAction
import dev.slimevr.tracking.trackers.TrackerRole
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Two coalesced output slots on the primary bridge, outside the body-role registry. */
internal class TrackingOverrideTransport(
	private val currentSession: () -> TransportSessionHandle?,
	private val queue: (ProtobufMessage) -> Unit,
	private val wake: () -> Unit,
	private val clock: () -> Long = System::nanoTime,
	private val counter: () -> Pair<Long, Long>? = WindowsOverrideCounter::sample,
) {
	private data class Negotiation(
		val session: TransportSessionHandle,
		val token: String,
		val ready: Boolean = false,
		val activated: Set<TrackingOverrideHand> = emptySet(),
	)
	private data class Pending(
		val session: TransportSessionHandle,
		val capturedAt: Long,
		val frames: Map<TrackingOverrideHand, TrackingOverrideFrame>,
	)

	// Only the VR server thread replaces this state. The bridge thread reads it.
	@Volatile private var negotiation: Negotiation? = null
	private val pending = AtomicReference<Pending?>(null)
	private var registered = emptySet<TrackingOverrideHand>()
	private var sentSession: TransportSessionHandle? = null
	private val lastProofRequest = mutableMapOf<TrackingOverrideHand, Long>()
	private val unavailableSent = mutableSetOf<TrackingOverrideHand>()
	// Server thread only. Retained through an intentional SteamVR stop for settings apply.
	var registeredHands: Set<TrackingOverrideHand> = emptySet()
		private set

	fun ready(): Boolean {
		val current = negotiation ?: return false
		return current.ready && current.session === currentSession()
	}

	fun publish(frames: Map<TrackingOverrideHand, TrackingOverrideFrame>, capturedAt: Long) {
		val session = currentSession() ?: return
		if (negotiation?.session !== session) {
			if (frames.values.all { it.status == TrackingOverrideSourceStatus.OFF } || counter() == null) return
			val challenge = Negotiation(session, UUID.randomUUID().toString())
			negotiation = challenge
			queue(action("$CAPABILITY?", challenge.token))
		}
		if (!ready()) return
		pending.set(Pending(session, capturedAt, frames.toMap()))
		wake()
	}

	fun accept(action: UserAction, session: TransportSessionHandle?): Boolean {
		if (action.name != CAPABILITY && action.name != ACTIVATED) return false
		val challenge = negotiation ?: return true
		if (session !== challenge.session || session !== currentSession() ||
			action.actionArgumentsMap["connection"] != challenge.token) return true
		if (action.name == CAPABILITY) {
			negotiation = challenge.copy(ready = true)
		} else if (challenge.ready && action.actionArgumentsMap["driver"] == "slimevr") {
			val hand = TrackingOverrideHand.entries.singleOrNull { it.serial == action.actionArgumentsMap["serial"] }
				?: return true
			negotiation = challenge.copy(activated = challenge.activated + hand)
			registeredHands = registeredHands + hand
		}
		return true
	}

	/** Bridge-thread write, after the ordinary queue. No buffered FULL can survive its deadline. */
	fun flush(send: (ProtobufMessage) -> Boolean) {
		val batch = pending.getAndSet(null) ?: return
		val challenge = negotiation ?: return
		if (!challenge.ready || batch.session !== challenge.session || batch.session !== currentSession()) return
		if (sentSession !== batch.session) {
			sentSession = batch.session
			registered = emptySet()
			lastProofRequest.clear()
			unavailableSent.clear()
		}
		for (hand in TrackingOverrideHand.entries) {
			val frame = batch.frames[hand]
			val enabled = frame != null && frame.status != TrackingOverrideSourceStatus.OFF
			if (hand !in registered) {
				if (!enabled) continue
				val added = TrackerAdded.newBuilder().setTrackerId(wireId(hand)).setTrackerRole(TrackerRole.NONE.id)
					.setTrackerSerial(hand.serial).setTrackerName(hand.serial)
				if (!send(ProtobufMessage.newBuilder().setTrackerAdded(added).build())) return
				registered = registered + hand
			}
			val now = clock()
			val lastRequest = lastProofRequest[hand]
			if (hand !in challenge.activated && enabled &&
				(lastRequest == null || now - lastRequest >= 250_000_000L)) {
				if (!send(action("$ACTIVATED?", challenge.token, hand))) return
				lastProofRequest[hand] = now
			}
			val pose = frame?.takeIf { it.status == TrackingOverrideSourceStatus.FULL }?.pose
			val deadline = pose?.let { expiryCounter(batch.capturedAt, it.remainingFreshNanos) }
			val position = Position.newBuilder().setTrackerId(wireId(hand))
			if (pose == null || deadline == null) {
				if (hand in unavailableSent) continue
				position.dataSource = Position.DataSource.NONE
			} else {
				if (!send(action("$LEASE?", challenge.token, hand, deadline))) return
				position.dataSource = Position.DataSource.FULL
				position.setX(pose.position.x).setY(pose.position.y).setZ(pose.position.z)
				position.setQw(pose.rotation.w).setQx(pose.rotation.x).setQy(pose.rotation.y).setQz(pose.rotation.z)
			}
			if (!send(ProtobufMessage.newBuilder().setPosition(position).build())) return
			if (position.dataSource == Position.DataSource.NONE) unavailableSent.add(hand) else unavailableSent.remove(hand)
		}
	}

	private fun expiryCounter(capturedAt: Long, remainingFreshNanos: Long): Long? {
		// Sample the shared counter first so conversion cannot lengthen the JVM lease.
		val (ticks, frequency) = counter() ?: return null
		val age = clock() - capturedAt
		if (age !in 0 until remainingFreshNanos) return null
		return runCatching {
			val remainingTicks = Math.multiplyExact(minOf(remainingFreshNanos - age, 100_000_000L), frequency) / 1_000_000_000L
			// Cross-process QPC readings within one tick have ambiguous order.
			Math.addExact(ticks, remainingTicks - 1).takeIf { it > ticks }
		}.getOrNull()
	}

	private fun action(name: String, token: String, hand: TrackingOverrideHand? = null, deadline: Long? = null): ProtobufMessage {
		val builder = UserAction.newBuilder().setName(name).putActionArguments("connection", token)
		hand?.let { builder.putActionArguments("serial", it.serial) }
		deadline?.let { builder.putActionArguments("deadline", it.toString()) }
		return ProtobufMessage.newBuilder().setUserAction(builder).build()
	}

	private fun wireId(hand: TrackingOverrideHand) = when (hand) {
		TrackingOverrideHand.LEFT -> -1
		TrackingOverrideHand.RIGHT -> -2
	}

	companion object {
		const val CAPABILITY = "monaka-tracking-override-v1"
		const val ACTIVATED = "monaka-tracking-override-activated-v1"
		const val LEASE = "monaka-tracking-override-lease-v1"
	}
}

internal interface TrackingOverrideKernelClock : StdCallLibrary {
	fun QueryPerformanceCounter(value: LongByReference): Boolean
	fun QueryPerformanceFrequency(value: LongByReference): Boolean
}

private object WindowsOverrideCounter {
	private val api by lazy {
		if (!Platform.isWindows()) null else runCatching {
			Native.load("kernel32", TrackingOverrideKernelClock::class.java)
		}.getOrNull()
	}
	private val frequency by lazy {
		val value = LongByReference()
		if (runCatching { api?.QueryPerformanceFrequency(value) }.getOrNull() == true) value.value.takeIf { it > 0 } else null
	}
	private val counter = ThreadLocal.withInitial { LongByReference() }

	fun sample(): Pair<Long, Long>? {
		val hertz = frequency ?: return null
		val value = counter.get()
		if (runCatching { api?.QueryPerformanceCounter(value) }.getOrNull() != true || value.value < 0) return null
		return value.value to hertz
	}
}
