package dev.monaka.tracking.desktop

import dev.monaka.protocol.v2.*
import dev.monaka.tracking.*
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import dev.slimevr.tracking.trackers.udp.IMUType
import dev.slimevr.tracking.trackers.udp.UDPDevice
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SolverCompositionIntegrationTests {
	private val hip = TrackerPosition.HIP
	private fun pose() = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
	private fun key(p: MtpPose) = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
	private fun tracker(id: Int, body: TrackerPosition, position: Boolean) = Tracker(null, id, "composition:$id",
		trackerPosition = body, hasPosition = position, hasRotation = true, isHmd = body == TrackerPosition.HEAD,
		allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false).also { it.status = TrackerStatus.OK }
	private fun position(p: MtpPose) = PositionCorrectionConfig(
		PositionCorrectionRawImuSpaceConfig("slime:composition:2", p.coordinate_space, true),
		PositionCorrectionMainMountCalibrationConfig("mount", "mount:1", key(p).observationId, Vector3.NULL),
		PositionCorrectionFixedCalibrationConfig("fit", "fit:1", "hmd", "model", Vector3.NULL, Quaternion.IDENTITY),
		PositionCorrectionPredictorPolicyConfig(100, 100, 100), PositionCorrectionPairingPolicyConfig(100, 100, 100, 0),
		PositionCorrectionLearningTuningConfig(.1, .2, 5.0, .6, .3, .05, 10_000_000_000, 300_000_000, .5, .3, .2, 0, 2, .0001),
		PositionCorrectionReacquisitionTuningConfig(400_000_000))
	private inner class Fixture(configured: Boolean = false, hybrid: Boolean = true, correction: Boolean = false,
		val mutate: (TrackerBodyAssignments) -> Unit = {}) : AutoCloseable {
		val p = pose()
		val head = tracker(1, TrackerPosition.HEAD, true).also { it.position = Vector3(0f, 1.7f, 0f) }
		val imu = if (!correction) tracker(2, hip, false) else Tracker(
			UDPDevice(InetSocketAddress("127.0.0.1", 20000), InetAddress.getLoopbackAddress(), "composition-test"),
			2, "composition:2", trackerPosition = hip, hasRotation = true, imuType = IMUType.UNKNOWN,
			allowReset = true, allowMounting = true, trackRotDirection = false).also {
			it.status = TrackerStatus.OK; it.setRotation(Quaternion.IDENTITY)
		}
		val manager = HumanPoseManager(listOf(head, imu))
		val assignments = TrackerBodyAssignments().also {
			it.configure(hip, TrackerReference.mtp(key(p)), TrackerReference.slime(imu.name), if (hybrid) OutputMode.HYBRID else OutputMode.IK)
		}
		var before: Runnable? = null; var after: Runnable? = null; var now = 1_000_000_000L
		var polls = 0; var transitions = 0
		val failures = mutableListOf<Exception>()
		val integration: MonakaServerIntegration
		init {
			manager.setLegTweaksEnabled(false); manager.skeleton.ikSolver.enabled = true; manager.update()
			integration = MonakaServerIntegration.startIfEnabled(true,
				{ MonakaConfiguration(p.coordinate_space, assignments, port = 0, backgroundIkSharedSpace = p.coordinate_space,
					positionCorrection = if (configured) position(p) else null,
					rotationCorrection = if (correction) RotationCorrectionConfig(
						RotationCorrectionFrames(Quaternion.IDENTITY, Quaternion.IDENTITY, p.coordinate_space, true),
						RotationCorrectionTuning(1, 100_000_000, .1, .2, 3.2, .1, 1000.0, 1, 100_000_000, 500_000_000)) else null) },
				{ polls++; mutate(assignments); listOf(head, imu) }, manager.skeleton,
				{ before = it }, { failures += it }, { now }, configureDirectOutputs = {}, nextTrackerId = { 300 },
				registerAfterPose = { after = it }, onTransition = { transitions++ })!!
		}
		fun send(sequence: Long = 1) {
			assertTrue(integration.runtime.inbox.receive((MonakaCodec.encodeEnvelope(p.copy(sequence = sequence)) as EncodeResult.Success).value, now))
		}
		fun finish() { manager.update(); after!!.run(); assertTrue(failures.isEmpty(), failures.joinToString()) }
		override fun close() = integration.close()
	}

	@Test fun currentWritebackUsesAssignmentAButNextTickUsesB() {
		var change = true
		Fixture(hybrid = false, mutate = { a -> if (change) {
			change = false; a.configure(hip, TrackerReference("missing-next-main"), TrackerReference("missing-next-imu"))
		} }).use { f ->
			val a = f.assignments.snapshot(); f.send(); f.before!!.run()
			assertEquals("monaka-private:HIP:${key(f.p).observationId}", f.manager.skeleton.hipTracker!!.name)
			assertNotEquals(a, f.assignments.snapshot()); f.finish()
			f.now++; f.before!!.run(); f.finish(); assertNull(f.manager.skeleton.hipTracker)
		}
	}
	@Test fun rotationCorrectionAndDiagnosticsPinAssignmentCapturedBeforePolling() {
		var change = true
		Fixture(correction = true, mutate = { a -> if (change) {
			change = false; a.configure(hip, TrackerReference("missing-next-main"), TrackerReference("missing-next-imu"), OutputMode.HYBRID)
		} }).use { f ->
			val a = f.assignments.snapshot(); f.send(); f.before!!.run(); f.finish()
			assertNotEquals(a, f.assignments.snapshot())
			val generation = f.integration.rotationCorrection!!.javaClass.getDeclaredField("activeAssignmentGeneration").also { it.isAccessible = true }
			assertEquals(a.generation, generation.get(f.integration.rotationCorrection))
			val diagnostic = f.integration.lastDiagnosticSnapshot!!
			assertEquals(key(f.p).observationId, diagnostic.mainSource)
			assertEquals("slime:${f.imu.name}", diagnostic.imuSource)
		}
	}
	@Test fun pendingFramePinsNowAssignmentAndPauseAndIsConsumedOnce(): Unit = Fixture().use { f ->
		f.send(); f.before!!.run()
		val pinnedNow = f.now
		// Advance runtime independently after before hook, simulating a later clock/assignment.
		f.now += 500_000_000; f.assignments.configure(TrackerPosition.LEFT_FOOT, TrackerReference("later"))
		f.integration.runtime.tickSnapshot(true)
		f.manager.skeleton.setPauseTracking(true, "later live state")
		f.after!!.run()
		val output = f.integration.directOutput.trackers.getValue(hip).monakaOutputPose!!
		assertNotNull(output.rotation)
		val transitionCount = f.transitions
		val frameField = f.integration.javaClass.getDeclaredField("pendingFrame").also { it.isAccessible = true }
		assertNull(frameField.get(f.integration)); f.after!!.run(); assertEquals(transitionCount, f.transitions)
		assertEquals(pinnedNow + 500_000_000, f.integration.runtime.lastTickNanos)
		// Diagnostics age must use the pending frame's now, not the newer runtime now.
		assertTrue(f.integration.lastDiagnosticSnapshot!!.mainAgeNanos!! < 500_000_000)
		assertTrue(f.failures.isEmpty())
	}
	@Test fun pendingOverwriteFailsClosedAndClearsOutputsAndHooks(): Unit = Fixture().use { f ->
		f.send(); f.before!!.run(); f.before!!.run()
		assertEquals(1, f.failures.size); assertTrue(f.failures.single().message!!.contains("not been consumed"))
		assertNull(f.before); assertNull(f.after)
		assertNull(f.integration.directOutput.trackers.getValue(hip).monakaOutputPose!!.position)
		assertFailsWith<IllegalStateException> { f.integration.runtime.tickSnapshot() }
	}
	@ParameterizedTest @ValueSource(booleans = [true, false])
	fun configOnlyChangesGateAndKeepsGenericNumericsAndDormantProviders(configured: Boolean) {
		Fixture(configured).use { f ->
			assertEquals(if (configured) ProductionPositionCorrectionGate.CONFIGURED_RAW_HMD_BLOCKED else ProductionPositionCorrectionGate.NOT_CONFIGURED,
				f.integration.positionCorrectionGate)
			f.send(); f.before!!.run(); f.finish()
			assertEquals(1, f.polls)
			val proxy = f.manager.skeleton.hipTracker!!; val visible = f.integration.directOutput.trackers.getValue(hip).monakaOutputPose!!
			assertEquals(key(f.p).observationId, visible.rotation!!.sourceId)
			assertEquals(f.p.position!![1].toFloat(), proxy.position.y, 1e-5f)
			val ownerField = f.integration.javaClass.getDeclaredField("solverComposition").also { it.isAccessible = true }
			val owner = ownerField.get(f.integration) as MonakaSolverComposition
			val sessionField = owner.javaClass.getDeclaredField("session").also { it.isAccessible = true }
			val sequenceField = owner.javaClass.getDeclaredField("lastReservedTickSequence").also { it.isAccessible = true }
			assertEquals(0L, sequenceField.get(owner)); assertNull(sessionField.get(owner))
			f.manager.skeleton.setPauseTracking(true, "test"); f.before!!.run(); f.finish()
			f.manager.skeleton.setPauseTracking(false, "test"); f.before!!.run(); f.finish()
			assertEquals(3, f.polls); assertNull(sessionField.get(owner)); assertEquals(2L, sequenceField.get(owner))
		}
	}
	@ParameterizedTest @ValueSource(ints = [0, 1, 2, 3])
	fun startupFailureClosesReceiverWritebackOutputsAndRegisteredHooks(which: Int) {
		val p = pose(); val head = tracker(1, TrackerPosition.HEAD, true)
		val manager = HumanPoseManager(listOf(head))
		val a = TrackerBodyAssignments().also { it.configure(hip, TrackerReference.mtp(key(p)), outputMode = OutputMode.HYBRID) }
		val port = DatagramSocket(0).use { it.localPort }
		var before: Runnable? = null; var after: Runnable? = null; var output: Tracker? = null
		assertFailsWith<IllegalStateException> {
			MonakaServerIntegration.startIfEnabled(true, { MonakaConfiguration(p.coordinate_space, a, port = port) },
				{ listOf(head) }, manager.skeleton,
				{ before = it; if (which == 2 && it != null) error("before registration") },
				configureDirectOutputs = { output = it.single(); if (which == 1) error("output registration") },
				nextTrackerId = { if (which == 0) error("id allocation") else 100 },
				registerAfterPose = { after = it; if (which == 3 && it != null) error("after registration") })
		}
		DatagramSocket(port).use { assertEquals(port, it.localPort) }
		assertNull(before); assertNull(after); output?.let { assertNull(it.monakaOutputPose!!.rotation) }
		// A leaked writeback input view would still manage/filter this HIP input.
		val raw = tracker(55, hip, true)
		manager.skeleton.setTrackersFromList(listOf(head, raw)); assertSame(raw, manager.skeleton.hipTracker)
	}
	@Test fun productionSourceUsesPinnedSnapshotAndNeverCallsFutureCapture() {
		val s = Files.readString(Path.of("src/main/java/dev/monaka/tracking/desktop/MonakaServerIntegration.kt"))
		val tick = s.substringAfter("fun tick()").substringBefore("fun finishPoseUpdate()")
		for (forbidden in listOf("runtime.tick(", "runtime.assignments.snapshot()", "runtime.lastTickNanos", "ConstraintIkWriteback", "captureSourceSnapshot", "commitPositionCorrection", ".capture("))
			assertFalse(tick.contains(forbidden), forbidden)
		assertTrue(tick.contains("runtime.tickSnapshot(")); assertTrue(tick.contains("runtime.resolvedTrackingPoses(tick)"))
		assertTrue(tick.contains("observations(tick.nowNanos, tick.assignment)"))
		assertEquals(1, Regex("solverComposition.commitGeneric").findAll(tick).count())
		assertFalse(s.contains("runtime.lastTickNanos")); assertFalse(s.contains("ConstraintIkWriteback"))
	}
}
