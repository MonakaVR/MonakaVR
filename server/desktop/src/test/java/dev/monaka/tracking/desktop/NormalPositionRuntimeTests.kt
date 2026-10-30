package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v2.*
import dev.monaka.tracking.*
import dev.slimevr.desktop.platform.ProtobufBridge
import dev.slimevr.desktop.platform.ProtobufMessages.*
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import dev.slimevr.tracking.trackers.udp.IMUType
import dev.slimevr.tracking.trackers.udp.UDPDevice
import io.github.axisangles.ktmath.Quaternion
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.test.*
import dev.slimevr.tracking.trackers.TrackerStatus as LocalStatus

/** Normal RPC/CAS save -> production server tick -> existing position algorithm ->
 * existing IK -> actual serialized virtual tracker messages. Transport is captured.
 */
class NormalPositionRuntimeTests {
	@ParameterizedTest
	@ValueSource(booleans = [false, true])
	fun ordinaryAssignmentSaveReachesPositionLearningFallbackAndSolverReacquisition(legacyGeometry: Boolean, @TempDir directory: Path) {
		val p = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
		val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		val path = directory.resolve("monaka-mtp.json")
		val bridge = NormalHmdCapture()
		val imu = Tracker(UDPDevice(InetSocketAddress("127.0.0.1", 20001), InetAddress.getLoopbackAddress(), "position-q"), 1, "position-q",
			trackerPosition = TrackerPosition.HIP, trackerNum = 1, hasRotation = true, imuType = IMUType.UNKNOWN, allowFiltering = false,
			allowReset = true, allowMounting = true, trackRotDirection = false).also { it.status = LocalStatus.OK; it.setRotation(Quaternion.IDENTITY) }
		val legacy = if (legacyGeometry) RotationCorrectionConfig(
			RotationCorrectionFrames(Quaternion.IDENTITY, imu.resetsHandler.getCorrectionReferenceRotationFrom(Quaternion.IDENTITY).inv(), p.coordinate_space, false),
			RotationCorrectionTuning.normalRuntimeDefaults()) else null
		MonakaConfiguration(p.coordinate_space, port = 0, rotationCorrection = legacy).save(path)
		val hpm = HumanPoseManager(listOf(bridge.hmd, imu)).also { it.setLegTweaksEnabled(false); it.skeleton.ikSolver.enabled = true; it.update() }
		var before: Runnable? = null
		var after: Runnable? = null
		var now = 1_000_000_000L
		val failures = mutableListOf<Exception>()
		val integration = MonakaServerIntegration.startIfEnabled(true, { MonakaConfiguration.load(path) }, { listOf(bridge.hmd, imu) }, hpm.skeleton,
			{ before = it }, { failures += it }, { now }, registerAfterPose = { after = it }, configurationPath = path,
			rawHmdSource = { tick, space, age, clockDomain -> bridge.currentNormalRawHmdPoseInput(space, tick.nowNanos, tick.trackerReceiptCutoffSystemNanos, age, clockDomain) })!!
		integration.use {
			for (tracker in hpm.computedTrackers) bridge.addSharedTracker(tracker)
			var sequence = 0L
			fun step(mode: String = "full", x: Double = .2, hmd: Boolean = true) {
				now += 20_000_000
				imu.setRotation(Quaternion.IDENTITY)
				if (hmd) bridge.receiveHead()
				val pose = p.copy(sequence = ++sequence, modality = mode, position = if (mode == "full") listOf(x, 1.0, .1) else null,
					orientation = if (mode == "none") null else listOf(0.0, 0.0, 0.0, 1.0), validity = Validity(mode == "full", mode != "none"),
					confidence = Confidence(if (mode == "full") 1.0 else 0.0, if (mode == "none") 0.0 else 1.0),
					tracking_state = if (mode == "full") "tracked" else if (mode == "none") "lost" else "degraded")
				assertTrue(it.runtime.inbox.receive((MonakaCodec.encodeEnvelope(pose) as EncodeResult.Success).value, now))
				before!!.run(); hpm.update(); after!!.run(); bridge.dataWrite(); bridge.flush()
				assertTrue(failures.isEmpty(), failures.joinToString())
			}
			step()
			val mapper = ObjectMapper()
			fun rpc(action: String, extra: Map<String, Any?> = emptyMap()) = mapper.readTree(it.inputRequest(mapper.writeValueAsString(mapOf("type" to "monaka_inputs_v1", "id" to "normal-test", "action" to action) + extra)))
			val snapshot = rpc("snapshot")
			val saved = rpc("save", mapOf("generation" to snapshot["generation"].asText(), "body" to "HIP", "qSource" to "slime:${imu.name}", "sixDofSource" to key.observationId, "manualSix" to true))
			assertTrue(saved["ok"].asBoolean(), saved.toString())
			assertEquals(key, MonakaConfiguration.load(path).assignments.snapshot().targets.getValue(TrackerPosition.HIP).sourceSelection!!.sixDofSource!!.mtp)
			assertNull(MonakaConfiguration.load(path).positionCorrection, "No hand-authored position DTO/activation needed")
			repeat(200) { step() }
			val full = assertNotNull(it.lastPositionCorrection)
			assertNotNull(full.prediction)
			assertNotNull(full.measuredTeacher)
			assertEquals("UPDATED", full.learning)
			assertEquals("uncalibrated_identity", full.geometry)
			step("rotation_only")
			val lost = assertNotNull(it.lastPositionCorrection)
			assertNull(lost.measuredTeacher)
			assertNotNull(lost.prediction)
			assertEquals("IK_EFFECTIVE_TARGET", lost.solverPositionReference)
			assertEquals("FALLBACK_ACTIVE", lost.continuity)
			assertNull(it.runtime.pipeline.resolve(TrackerPosition.HIP, now).position, "Prediction remains derived; source has no measured P")
			val fallback = assertNotNull(lost.solverPosition)
			val fallbackComputed = hpm.skeleton.computedHipTracker!!.position
			step(x = .8)
			val reacquire = assertNotNull(it.lastPositionCorrection)
			assertEquals("REACQUIRING", reacquire.continuity)
			assertTrue((assertNotNull(reacquire.solverPosition) - fallback).len() < .001f, "Solver starts at its committed fallback")
			repeat(20) { step(x = .8) }
			assertEquals("MAIN_DIRECT", it.lastPositionCorrection!!.continuity)
			val computed = hpm.skeleton.computedHipTracker!!
			val wire = bridge.sent.last { message -> message.hasPosition() && message.position.trackerId == computed.id }.position
			assertEquals(computed.position, io.github.axisangles.ktmath.Vector3(wire.x, wire.y, wire.z))
			assertTrue((computed.position - fallbackComputed).len() > .005f, "Existing IK changes the virtual tracker numerically")
			if (legacyGeometry) {
				assertTrue(it.rotationCorrection!!.ready, "Saved learner is selected after a pair is added through the normal API")
				assertFalse(it.runtime.bodyRotationResults.containsKey(TrackerPosition.HIP), "No second HIP learner")
				val removed = rpc("save", mapOf("generation" to rpc("snapshot")["generation"].asText(), "body" to "HIP", "qSource" to null, "sixDofSource" to null, "manualSix" to true))
				assertTrue(removed["ok"].asBoolean(), removed.toString())
				step()
				assertFalse(it.rotationCorrection!!.ready)
				assertNull(it.lastPositionCorrection)
				val readded = rpc("save", mapOf("generation" to rpc("snapshot")["generation"].asText(), "body" to "HIP", "qSource" to "slime:${imu.name}", "sixDofSource" to key.observationId, "manualSix" to true))
				assertTrue(readded["ok"].asBoolean(), readded.toString())
				step()
				assertFalse(it.rotationCorrection!!.ready, "Old pairing was discarded on reassignment")
				repeat(100) { step() }
				assertTrue(it.rotationCorrection!!.ready)
				assertEquals(legacy, MonakaConfiguration.load(path).rotationCorrection)
			}
			bridge.loseHead()
			step("none", hmd = false)
			assertFalse(it.lastPositionCorrection!!.hmdAvailable)
			assertNull(it.lastPositionCorrection!!.prediction)
			assertNull(it.lastPositionCorrection!!.solverPosition, "Q alone cannot supply a position")
		}
	}
}

private class NormalHmdCapture : ProtobufBridge("normal-position-test") {
	val hmd = Tracker(Device(DeviceOrigin.STEAMVR), 0, "normal-head", trackerPosition = TrackerPosition.HEAD, trackerNum = 0,
		hasPosition = true, hasRotation = true, isHmd = true, isComputed = true, allowFiltering = false, allowReset = false, allowMounting = false,
		trackRotDirection = false).also { it.status = LocalStatus.OK }
	private val handle = openInboundTransportSession()
	val sent = mutableListOf<ProtobufMessage>()
	init {
		@Suppress("UNCHECKED_CAST")
		val registry = ProtobufBridge::class.java.getDeclaredField("remoteTrackersByTrackerId").apply { isAccessible = true }.get(this) as MutableMap<Int, Tracker>
		registry[0] = hmd
		registerTrustedSteamVrHmd(hmd)
		receiveHead()
	}
	fun receiveHead() {
		val p = Position.newBuilder().setTrackerId(0).setX(0f).setY(1.7f).setZ(0f).setQw(1f).setDataSource(Position.DataSource.FULL)
		messageReceived(ProtobufMessage.parseFrom(ProtobufMessage.newBuilder().setPosition(p).build().toByteArray()), handle)
		dataRead()
	}
	fun loseHead() { messageReceived(ProtobufMessage.newBuilder().setTrackerStatus(dev.slimevr.desktop.platform.ProtobufMessages.TrackerStatus.newBuilder().setTrackerId(0).setStatus(dev.slimevr.desktop.platform.ProtobufMessages.TrackerStatus.Status.DISCONNECTED)).build(), handle); dataRead() }
	fun flush() = updateMessageQueue()
	override fun signalSend() = Unit
	override fun sendMessageReal(message: ProtobufMessage?): Boolean { sent += ProtobufMessage.parseFrom(requireNotNull(message).toByteArray()); return true }
	override fun createNewTracker(trackerAdded: TrackerAdded): Tracker = error("Owned HMD fixture registry")
	override fun startBridge() = Unit
	override fun stopBridge() = Unit
	override fun isConnected() = false
	override fun getShareSetting(role: TrackerRole) = false
	override fun changeShareSettings(role: TrackerRole?, share: Boolean) = Unit
	override fun updateShareSettingsAutomatically() = false
	override fun getAutomaticSharedTrackers() = false
	override fun setAutomaticSharedTrackers(value: Boolean) = Unit
	override fun getBridgeConfigKey() = "normal-position-test"
}
