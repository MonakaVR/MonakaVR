package dev.slimevr.desktop.platform

import dev.slimevr.VRServer.Companion.instance
import dev.monaka.tracking.desktop.RawHmdPoseInputCapability
import dev.monaka.tracking.desktop.admitRawHmdPoseInput
import dev.slimevr.bridge.BridgeThread
import dev.slimevr.bridge.ISteamVRBridge
import dev.slimevr.desktop.platform.ProtobufMessages.*
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerStatus
import dev.slimevr.tracking.trackers.TrackerStatus.Companion.getById
import dev.slimevr.tracking.trackers.TrackerUtils
import dev.slimevr.util.ann.VRServerThread
import io.eiren.util.ann.Synchronize
import io.eiren.util.ann.ThreadSafe
import io.eiren.util.collections.FastList
import io.eiren.util.logging.LogManager
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.util.Queue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.HashMap

abstract class ProtobufBridge @JvmOverloads constructor(
	@JvmField protected val bridgeName: String,
	private val hmdPositionReceiptClock: () -> Long = System::nanoTime,
) : ISteamVRBridge {
	private val rawHmdPositions = dev.monaka.tracking.desktop.TrustedRawHmdPositionSource(bridgeName)

	/** Creation-boundary registration, not classification by HEAD role or isComputed alone. */
	@VRServerThread
	protected fun registerTrustedSteamVrHmd(tracker: Tracker) = rawHmdPositions.registerFromSteamVrIngress(tracker)

	/** Read-only historical ingress record; no coordinate-space or frame proof is implied. */
	fun acceptedHmdPositionSample() = rawHmdPositions.snapshot()
	/** Historical same-decoded-message copy; structural completeness does not establish tracking usability. */
	fun acceptedHmdPoseMessageSample() = rawHmdPositions.poseMessageSnapshot()
	/** Active-session acceptance candidate only; does not establish structural usability or freshness. */
	fun acceptedCurrentSessionHmdPositionSample() = currentSessionHmdSample(rawHmdPositions::currentPositionSnapshot)
	fun acceptedCurrentSessionHmdPoseMessageSample() = currentSessionHmdSample(rawHmdPositions::currentPoseMessageSnapshot)

	/** Generic production ingress has no authoritative frame reference. No operator override. */
	fun rawHmdPoseInputCapability(): RawHmdPoseInputCapability = rawHmdPositions.rawHmdPoseInputCapability

	/** Complete read-side trust gate. Admission is not a lease against a later disconnect. */
	@VRServerThread
	fun currentRawHmdPoseAdmission() = admitCurrentRawHmdPoseInput(rawHmdPoseInputCapability())

	/** Future internal evidence producer seam; Ready must be scoped to one exact accepted pose. */
	@VRServerThread
	internal fun admitCurrentRawHmdPoseInput(capability: RawHmdPoseInputCapability) = admitRawHmdPoseInput(
		capability, ::currentInboundTransportSession, rawHmdPositions::currentPoseMessageSnapshot,
		hmdPositionReceiptClock,
	)

	private fun <T> currentSessionHmdSample(read: (String) -> T?): T? {
		val before = currentInboundTransportSession() ?: return null
		val sample = read(before.epoch)
		// A transition during this read fails closed. A later disconnect can still follow any read.
		return if (currentInboundTransportSession() === before) sample else null
	}
	@JvmField
	@VRServerThread
	protected val sharedTrackers: MutableList<Tracker> = FastList()

	@ThreadSafe
	private val inputQueue: Queue<InboundProtobufEnvelope> = LinkedBlockingQueue()
	private val inboundTransportSession = AtomicReference<TransportSessionHandle?>(null)

	/** Transport accept boundary only; independent of VRServer-side output reconnected callbacks. */
	@ThreadSafe
	protected fun openInboundTransportSession(): TransportSessionHandle =
		TransportSessionHandle(java.util.UUID.randomUUID().toString()).also(inboundTransportSession::set)

	/** A delayed close for an older logical accept cannot clear a newer active session. */
	@ThreadSafe
	protected fun closeInboundTransportSession(handle: TransportSessionHandle): Boolean =
		inboundTransportSession.compareAndSet(handle, null)

	@ThreadSafe
	protected fun currentInboundTransportSession(): TransportSessionHandle? = inboundTransportSession.get()

	@ThreadSafe
	private val outputQueue: Queue<ProtobufMessage> = LinkedBlockingQueue()

	@Synchronize("self")
	private val remoteTrackersBySerial: MutableMap<String, Tracker> = HashMap()

	@Synchronize("self")
	private val remoteTrackersByTrackerId: MutableMap<Int, Tracker> = HashMap()
	private var hadNewData = false
	@Volatile private var directOutputSupported = false
	@Volatile private var directCapabilityToken: String? = null
	private val directCapabilityLock = Any()
	protected var directOutputTrackers: List<Tracker> = emptyList()
		private set

	/** Startup-only: explicit output objects never become raw tracker inputs. */
	fun configureDirectOutputs(trackers: List<Tracker>) {
		check(directOutputTrackers.isEmpty())
		require(trackers.all { it.monakaOutputPose != null })
		directOutputTrackers = trackers.toList()
	}

	/**
	 * Wakes the bridge thread, implementation is platform-specific.
	 */
	@ThreadSafe
	protected abstract fun signalSend()

	@BridgeThread
	protected abstract fun sendMessageReal(message: ProtobufMessage?): Boolean

	protected var remoteProtocolVersion: Int = 0

	@BridgeThread
	@JvmOverloads
	protected fun messageReceived(message: ProtobufMessage, transportSession: TransportSessionHandle? = null) {
		// First decoded-message ingress, before any queue wait or VRServer-side processing.
		val receivedAtSystemNanos = hmdPositionReceiptClock()
		inputQueue.add(InboundProtobufEnvelope(message, transportSession, receivedAtSystemNanos))
	}

	@ThreadSafe
	protected fun sendMessage(message: ProtobufMessage) {
		outputQueue.add(message)
		signalSend()
	}

	@BridgeThread
	protected fun updateMessageQueue() {
		var message: ProtobufMessage?
		while ((outputQueue.poll().also { message = it }) != null) {
			if (!directOutputSupported && isDirectMessage(message!!)) continue
			if (!sendMessageReal(message)) return
		}
	}

	private fun isDirectMessage(message: ProtobufMessage): Boolean =
		(message.hasPosition() && directOutputTrackers.any { it.id == message.position.trackerId }) ||
			(message.hasTrackerAdded() && directOutputTrackers.any { it.id == message.trackerAdded.trackerId })

	@VRServerThread
	override fun dataRead() {
		hadNewData = false
		var envelope: InboundProtobufEnvelope?
		while ((inputQueue.poll().also { envelope = it }) != null) {
			val accepted = envelope!!
			processMessageReceived(accepted.message, accepted.transportSession, accepted.receivedAtSystemNanos)
			hadNewData = true
		}
	}

	@VRServerThread
	protected fun trackerOverrideUpdate(source: Tracker, target: Tracker) {
		target.position = source.position
		target.sampleModality = source.sampleModality
		target.setRotation(source.getRotation())
		target.status = source.status
		target.setVelocity(source.getVelocity())
		target.batteryLevel = source.batteryLevel
		target.batteryVoltage = source.batteryVoltage
		target.dataTick()
	}

	@VRServerThread
	override fun dataWrite() {
		if (!hadNewData) {
			// Don't write anything if no message were received, we
			// always process at the
			// speed of the other side
			return
		}
		for (tracker in sharedTrackers) {
			writeTrackerUpdate(tracker)
			writeBatteryUpdate(tracker)
		}
	}

	@VRServerThread
	protected fun writeTrackerUpdate(localTracker: Tracker) {
		localTracker.monakaOutputPose?.let { constraint ->
			if (!directOutputSupported) return
			val builder = Position.newBuilder().setTrackerId(localTracker.id)
			val rotation = constraint.rotation
			val position = constraint.position
			builder.dataSource = when {
				rotation == null -> Position.DataSource.NONE
				position != null -> Position.DataSource.FULL
				else -> Position.DataSource.IMU
			}
			if (rotation != null) {
				val q = rotation.value
				builder.setQw(q.w).setQx(q.x).setQy(q.y).setQz(q.z)
				position?.value?.let { builder.setX(it.x).setY(it.y).setZ(it.z) }
			}
			sendMessage(ProtobufMessage.newBuilder().setPosition(builder).build())
			return
		}
		val builder = ProtobufMessages.Position.newBuilder()
			.setTrackerId(localTracker.id)

		if (localTracker.hasPosition) {
			val pos = localTracker.position
			builder.setX(pos.x)
			builder.setY(pos.y)
			builder.setZ(pos.z)
		}

		if (localTracker.hasRotation) {
			val rot = localTracker.getRotation()
			builder.setQx(rot.x)
			builder.setQy(rot.y)
			builder.setQz(rot.z)
			builder.setQw(rot.w)
		}

		if (localTracker.hasVelocity) {
			val vel = localTracker.getVelocity()
			builder.setVx(vel.x)
			builder.setVy(vel.y)
			builder.setVz(vel.z)
		}

		sendMessage(
			ProtobufMessage.newBuilder()
				.setPosition(builder)
				.build(),
		)
	}

	@VRServerThread
	protected open fun writeBatteryUpdate(localTracker: Tracker) {
		return
	}

	@VRServerThread
	@JvmOverloads
	protected open fun processMessageReceived(
		message: ProtobufMessage?, transportSession: TransportSessionHandle? = null,
		receivedAtSystemNanos: Long? = null,
	) {
		// if(!message.hasPosition())
		// LogManager.log.info("[" + bridgeName + "] MSG: " + message);
		if (message!!.hasPosition()) {
			positionReceived(message.position, transportSession, receivedAtSystemNanos)
		} else if (message.hasUserAction()) {
			userActionReceived(message.userAction)
		} else if (message.hasTrackerStatus()) {
			trackerStatusReceived(message.trackerStatus)
		} else if (message.hasTrackerAdded()) {
			trackerAddedReceived(message.trackerAdded)
		} else if (message.hasBattery()) {
			batteryReceived(message.battery)
		} else if (message.hasVersion()) {
			versionReceived(message.version)
		}
	}

	@VRServerThread
	@JvmOverloads
	protected open fun positionReceived(
		positionMessage: ProtobufMessages.Position, transportSession: TransportSessionHandle? = null,
		receivedAtSystemNanos: Long? = null,
	) {
		val tracker = getInternalRemoteTrackerById(positionMessage.trackerId)
		if (tracker != null) {
			val modality = when (positionMessage.dataSource) {
				Position.DataSource.FULL -> dev.monaka.tracking.TrackingModality.FULL
				Position.DataSource.IMU -> dev.monaka.tracking.TrackingModality.ROTATION_ONLY
				else -> dev.monaka.tracking.TrackingModality.NONE
			}
			tracker.sampleModality = modality
			if (positionMessage.hasX()) {
				val acceptedPosition = Vector3(
					positionMessage.x,
					positionMessage.y,
					positionMessage.z,
				)
				tracker.position = acceptedPosition
				val isCurrentSession = transportSession != null && transportSession == currentInboundTransportSession()
				rawHmdPositions.positionMessageAccepted(
					tracker, acceptedPosition, positionMessage, modality, transportSession?.epoch, isCurrentSession,
					receivedAtSystemNanos,
				)
			}

			tracker
				.setRotation(
					Quaternion(
						positionMessage.qw,
						positionMessage.qx,
						positionMessage.qy,
						positionMessage.qz,
					),

				)
			if (positionMessage.hasVx()) {
				tracker
					.setVelocity(
						Vector3(
							positionMessage.vx,
							positionMessage.vy,
							positionMessage.vz,
						),
					)
			}
			tracker.dataTick()
		}
	}

	@VRServerThread
	protected open fun versionReceived(versionMessage: Version) {
		remoteProtocolVersion = versionMessage.protocolVersion
		if (remoteProtocolVersion == PROTOCOL_VERSION) {
			LogManager.info("[ProtobufBridge] Driver protocol version matches the server protocol version: $remoteProtocolVersion")
		} else {
			LogManager.warning("[ProtobufBridge] Driver protocol version ($remoteProtocolVersion) doesn't match the server protocol version ($PROTOCOL_VERSION)")
		}
	}

	@VRServerThread
	protected open fun batteryReceived(batteryMessage: Battery) {
		return
	}

	@VRServerThread
	protected abstract fun createNewTracker(trackerAdded: TrackerAdded): Tracker

	@VRServerThread
	protected fun trackerAddedReceived(trackerAdded: TrackerAdded) {
		var tracker = getInternalRemoteTrackerById(trackerAdded.trackerId)
		if (tracker != null) {
			// TODO reinit?
			return
		}
		tracker = createNewTracker(trackerAdded)
		synchronized(remoteTrackersBySerial) {
			remoteTrackersBySerial.put(tracker!!.name, tracker)
		}
		synchronized(remoteTrackersByTrackerId) {
			remoteTrackersByTrackerId.put(tracker!!.trackerNum, tracker)
		}
		instance.registerTracker(tracker!!)
	}

	@VRServerThread
	protected fun userActionReceived(userAction: ProtobufMessages.UserAction) {
		if (userAction.name == DIRECT_CAPABILITY) {
			val accepted = synchronized(directCapabilityLock) {
				if (!directOutputSupported && directOutputTrackers.isNotEmpty() &&
					directCapabilityToken != null && userAction.actionArgumentsMap["connection"] == directCapabilityToken
				) {
					directOutputSupported = true
					true
				} else false
			}
			if (accepted) {
				LogManager.info("[$bridgeName] Compatible Direct output driver confirmed")
				sharedTrackers.filter { it.monakaOutputPose != null }.forEach(::announceTracker)
			}
			return
		}
		val resetSourceName = String.format("%s: %s", resetSourceNamePrefix, bridgeName)
		when (userAction.name) {
			"reset" -> // TODO : Check pose field
				instance.resetTrackersFull(resetSourceName)

			"fast_reset" -> instance.resetTrackersYaw(resetSourceName)

			"mounting_reset" -> instance.resetTrackersMounting(resetSourceName)

			"feet_mounting_reset" -> instance.resetTrackersMounting(
				resetSourceName,
				TrackerUtils.feetsBodyParts,
			)

			"pause_tracking" ->
				instance
					.togglePauseTracking(resetSourceName)
		}
	}

	@VRServerThread
	protected fun trackerStatusReceived(trackerStatus: ProtobufMessages.TrackerStatus) {
		val tracker = getInternalRemoteTrackerById(trackerStatus.trackerId)
		if (tracker != null) {
			tracker.status = getById(trackerStatus.statusValue)!!
		}
	}

	@ThreadSafe
	protected fun getInternalRemoteTrackerById(trackerId: Int): Tracker? {
		synchronized(remoteTrackersByTrackerId) {
			return remoteTrackersByTrackerId[trackerId]
		}
	}

	@VRServerThread
	protected fun reconnected() {
		val token = java.util.UUID.randomUUID().toString()
		synchronized(directCapabilityLock) {
			directOutputSupported = false
			directCapabilityToken = token
		}
		// Never replay a queued Direct pose/registration into a different driver connection.
		outputQueue.removeIf(::isDirectMessage)
		for (tracker in sharedTrackers) {
			announceTracker(tracker)
		}
		if (directOutputTrackers.isNotEmpty()) {
			sendMessage(ProtobufMessage.newBuilder().setUserAction(UserAction.newBuilder().setName("$DIRECT_CAPABILITY?").putActionArguments("connection", token)).build())
			LogManager.info("[$bridgeName] Direct output waiting for compatible driver capability: $DIRECT_CAPABILITY")
		}
	}

	@VRServerThread
	protected fun disconnected() {
		synchronized(directCapabilityLock) {
			directOutputSupported = false
			directCapabilityToken = null
		}
		synchronized(remoteTrackersByTrackerId) {
			for ((_, value) in remoteTrackersByTrackerId) {
				value.status = TrackerStatus.DISCONNECTED
			}
		}
	}

	@VRServerThread
	override fun addSharedTracker(tracker: Tracker?) {
		if (sharedTrackers.contains(tracker) || tracker == null) return
		sharedTrackers.add(tracker)
		announceTracker(tracker)
	}

	private fun announceTracker(tracker: Tracker) {
		if (tracker.monakaOutputPose != null && !directOutputSupported) return
		val builder = TrackerAdded
			.newBuilder()
			.setTrackerId(tracker.id)
			.setTrackerName(tracker.name)
			.setTrackerSerial(tracker.name)
			.setTrackerRole(tracker.trackerPosition!!.trackerRole!!.id)
		sendMessage(ProtobufMessage.newBuilder().setTrackerAdded(builder).build())
	}

	@VRServerThread
	override fun removeSharedTracker(tracker: Tracker?) {
		// Remove shared tracker
		sharedTrackers.remove(tracker)

		// Set the tracker's status as disconnected
		val statusBuilder = ProtobufMessages.TrackerStatus
			.newBuilder()
			.setTrackerId(tracker!!.id)
		statusBuilder.setStatus(ProtobufMessages.TrackerStatus.Status.DISCONNECTED)
		sendMessage(ProtobufMessage.newBuilder().setTrackerStatus(statusBuilder).build())
	}

	companion object {
		const val DIRECT_CAPABILITY = "monaka-direct-output-v1"
		private const val resetSourceNamePrefix = "ProtobufBridge"
		private const val PROTOCOL_VERSION = 2
	}
}
