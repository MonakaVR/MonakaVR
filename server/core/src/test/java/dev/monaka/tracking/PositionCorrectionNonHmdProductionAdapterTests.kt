package dev.monaka.tracking

import dev.monaka.protocol.v2.*
import dev.monaka.tracking.mtp.MtpSourceContextSnapshot
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.config.SkeletonConfigManager
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import dev.slimevr.tracking.trackers.*
import dev.slimevr.tracking.trackers.udp.IMUType
import dev.slimevr.tracking.trackers.udp.UDPDevice
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

internal class PositionCorrectionNonHmdProductionAdapterTests {
	@Test fun mtpWireHasNoWorldEpochAndRuntimeImuBindingCannotFabricateTeacherWorld() {
		val t = tracker(); val tick = tick(t); val f = foundation()
		val result = bundle(adapter(t, f.copy(rawImuBinding = f.rawImuBinding.copy(commonWorldEpoch = "W1"))), tick)
		assertEquals("W1", assertIs<PositionCorrectionProductionRawImuResult.Available>(result.rawImu).input.provenance.commonWorldEpoch)
		val teacher = assertIs<PositionCorrectionProductionTeacherResult.Available>(result.teacher).value
		assertNull(teacher.teacher.provenance.commonWorldEpoch); assertNull(teacher.expectedEpoch.commonWorldEpoch)
		val raw = observation().let { it.copy(provenance = it.provenance!!.copy(commonWorldEpoch = "W1")) }
		val spoofed = bundle(adapter(t), tick, sources(tick, raw = raw))
		assertEquals(PositionCorrectionProductionTeacherRejection.TEACHER_CONTEXT_MISMATCH,
			assertIs<PositionCorrectionProductionTeacherResult.Unavailable>(spoofed.teacher).reason)
	}

	private val hip = TrackerPosition.HIP
	private fun fixture() = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
	private val pose = fixture()
	private val space = pose.coordinate_space
	private val key = LogicalTracker(pose.source_id, pose.tracker_id, pose.publisher_id)
	private fun tracker(name: String = "physical-hip") = Tracker(
		UDPDevice(InetSocketAddress("127.0.0.1", 20000), InetAddress.getLoopbackAddress(), "fixture"),
		101, name, trackerPosition = hip, hasRotation = true, imuType = IMUType.UNKNOWN, trackRotDirection = false,
	).also { it.status = TrackerStatus.OK; it.setRotation(Quaternion.IDENTITY) }
	private fun assignment(main: TrackerReference = TrackerReference.mtp(key), fallback: TrackerReference? = TrackerReference.slime("physical-hip")) =
		TrackerBodyAssignments.Snapshot(3, mapOf(hip to MainTrackerAssignment(main, fallback)))
	private fun body() = HipBodyModelSnapshot.create(.1f, .2f, .3f, .4f, .5f, .6f)
	private fun foundation(modelId: String = HipBodyModelSnapshot.MODEL_ID) = ConfiguredPositionCorrectionFoundation(
		SlimeRawImuCoordinateSpaceBinding("slime:physical-hip", space, true),
		assertIs<MainTrackerMountCalibrationSnapshotResult.Available>(MainTrackerMountCalibrationSnapshot.create("mount", "mount-session", key.observationId, Vector3(.1f, -.2f, .3f))).snapshot,
		assertIs<PredictorFixedCalibrationSnapshotResult.Available>(PredictorFixedCalibrationSnapshot.create("fixed", "fit-session", "hmd:configured", modelId, Vector3.NULL, Quaternion.IDENTITY)).snapshot,
		MainDecoupledHipPredictorPolicy(100, 100, 100), PositionTemporalPairingPolicy(100, 100, 100, 100),
		PositionCorrectionTuning(.1, .2, 5.0, .6, .3, .05, 10_000_000_000, 300_000_000, .5, .3, .2, 0, 2, .0001),
		PositionCorrectionReacquisitionTuning(400_000_000))
	private fun tick(t: Tracker, a: TrackerBodyAssignments.Snapshot = assignment(), sequence: Long = 1, now: Long = 1000, paused: Boolean = false) =
		MonakaResolvedTickSnapshot(sequence, now, now, paused, a, emptyMap(), t.correctionOrientationSample()!!.receivedAtSystemNanos + 10)
	private fun observation() = PoseObservation(key.observationId, hip, 900, position = Vector3(1f, 2f, 3f), rotation = Quaternion.IDENTITY,
		provenance = ObservationSampleProvenance(1, 900, "source-session:clock", "input-session", 7, space))
	private fun context() = MtpSourceContextSnapshot(key, "source-session:clock", "input-session", 7, space)
	private fun sources(tick: MonakaResolvedTickSnapshot, raw: PoseObservation? = observation(), owner: String = "mtp", current: MtpSourceContextSnapshot? = context(),
		sequence: Long = tick.tickSequence, now: Long = tick.nowNanos, a: TrackerBodyAssignments.Snapshot = tick.assignment) =
		MonakaSourceTickSnapshot(sequence, now, a, if (raw == null) emptyMap() else mapOf(key.observationId to raw),
			mapOf(key.observationId to owner), if (current == null) emptyMap() else mapOf(key to current))
	private fun bundle(adapter: PositionCorrectionNonHmdProductionAdapter, tick: MonakaResolvedTickSnapshot, sources: MonakaSourceTickSnapshot = sources(tick)) =
		assertIs<PositionCorrectionNonHmdCaptureResult.Available>(adapter.capture(tick, sources)).tick
	private fun adapter(t: Tracker, f: ConfiguredPositionCorrectionFoundation = foundation(), body: () -> HipBodyModelSnapshotResult = ::body) =
		PositionCorrectionNonHmdProductionAdapter(f, { listOf(t) }, body,
			SlimeRawImuProductionBoundary(SlimeIndependentImuOrientationCapture { error("Clock read") }))

	@Test fun healthyBundleHasIndependentEpochAndGoldenMountNormalization() {
		val t = tracker(); val tick = tick(t); val f = foundation(); val result = bundle(adapter(t, f), tick)
		assertEquals(tick.tickSequence, result.tickSequence); assertEquals(tick.nowNanos, result.nowNanos)
		assertEquals(tick.assignment.generation, result.assignmentGeneration); assertFalse(result.paused)
		val imu = assertIs<PositionCorrectionProductionRawImuResult.Available>(result.rawImu).input
		assertEquals(990, imu.provenance.sampleAtNanos); assertEquals(f.rawImuBinding.sourceId, imu.source.sourceId)
		val teacher = assertIs<PositionCorrectionProductionTeacherResult.Available>(result.teacher).value
		assertEquals(Vector3(1.1f, 1.8f, 3.3f), teacher.teacher.position)
		assertEquals(RawSourceIdentity(key.observationId, RawSourceKind.RAW_BACKEND), teacher.teacher.rawOrigin)
		assertEquals(PositionTeacherEpoch(key.observationId, context().sourceEpoch, context().calibrationEpoch,
			7, space, tick.assignment.generation, PositionBodyReference.HIP_CENTER, f.mainMountCalibration.identity), teacher.expectedEpoch)
		assertIs<HipBodyModelSnapshotResult.Available>(result.bodyModel)
		assertSame(f.fixedCalibration, assertIs<PositionCorrectionProductionFixedCalibrationResult.Available>(result.fixedCalibration).calibration)
	}

	@ParameterizedTest @ValueSource(strings = ["source", "calibration", "mapping", "space"])
	fun backendContextIsIndependentAndRejectsOldTeacher(case: String) {
		val t = tracker(); val tick = tick(t); val old = context()
		val changed = when (case) {
			"source" -> old.copy(sourceEpoch = "new-session")
			"calibration" -> old.copy(calibrationEpoch = "new-calibration")
			"mapping" -> old.copy(mappingRevision = 8)
			else -> old.copy(coordinateSpace = space.copy(revision = space.revision + 1))
		}
		val result = bundle(adapter(t), tick, sources(tick, current = changed))
		assertEquals(if (case == "space") PositionCorrectionProductionTeacherRejection.MTP_CONTEXT_SPACE_MISMATCH
			else PositionCorrectionProductionTeacherRejection.TEACHER_CONTEXT_MISMATCH,
			assertIs<PositionCorrectionProductionTeacherResult.Unavailable>(result.teacher).reason)
		assertIs<PositionCorrectionProductionRawImuResult.Available>(result.rawImu)
	}

	@ParameterizedTest @ValueSource(strings = ["owner", "noContext", "identity", "noRaw", "wrongRaw", "stale", "teacherSpace"])
	fun rawAuthorityAndExactSourceFailClosed(case: String) {
		val t = tracker(); val tick = tick(t)
		val source = when (case) {
			"owner" -> sources(tick, owner = "spoof-backend")
			"noContext" -> sources(tick, current = null)
			"identity" -> sources(tick, current = context().copy(logicalTracker = key.copy(trackerId = "other")))
			"noRaw" -> MonakaSourceTickSnapshot(tick.tickSequence, tick.nowNanos, tick.assignment, mapOf("other" to observation()), emptyMap(), emptyMap())
			"wrongRaw" -> sources(tick, raw = observation().copy(sourceId = "other"))
			"stale" -> sources(tick, raw = observation().copy(positionQuality = ObservationQuality.STALE))
			else -> sources(tick, raw = observation().copy(provenance = observation().provenance!!.copy(space = space.copy(revision = space.revision + 1))))
		}
		val expected = when (case) {
			"owner" -> PositionCorrectionProductionTeacherRejection.SOURCE_NOT_OWNED_BY_MTP
			"noContext" -> PositionCorrectionProductionTeacherRejection.MTP_CONTEXT_UNAVAILABLE
			"identity" -> PositionCorrectionProductionTeacherRejection.MTP_CONTEXT_IDENTITY_MISMATCH
			"noRaw" -> PositionCorrectionProductionTeacherRejection.RAW_OBSERVATION_UNAVAILABLE
			"wrongRaw" -> PositionCorrectionProductionTeacherRejection.RAW_OBSERVATION_SOURCE_MISMATCH
			"stale" -> PositionCorrectionProductionTeacherRejection.NORMALIZATION_REJECTED
			else -> PositionCorrectionProductionTeacherRejection.TEACHER_CONTEXT_MISMATCH
		}
		val result = bundle(adapter(t), tick, source)
		assertEquals(expected, assertIs<PositionCorrectionProductionTeacherResult.Unavailable>(result.teacher).reason)
		assertIs<PositionCorrectionProductionRawImuResult.Available>(result.rawImu)
		assertIs<PositionCorrectionProductionFixedCalibrationResult.Available>(result.fixedCalibration)
	}

	@ParameterizedTest @ValueSource(strings = ["main", "fallback", "mainUntyped", "noHip", "noFallback"])
	fun assignmentDriftDoesNotRebindConfiguration(case: String) {
		val t = tracker(); val a = when (case) {
			"main" -> assignment(main = TrackerReference.mtp(key.copy(trackerId = "other")))
			"fallback" -> assignment(fallback = TrackerReference.slime("other"))
			"mainUntyped" -> assignment(main = TrackerReference(key.observationId))
			"noHip" -> TrackerBodyAssignments.Snapshot(4, emptyMap())
			else -> assignment(fallback = null)
		}
		val tick = tick(t, a); val result = bundle(adapter(t), tick)
		if (case in listOf("main", "mainUntyped", "noHip")) assertIs<PositionCorrectionProductionTeacherResult.Unavailable>(result.teacher)
		if (case in listOf("fallback", "noHip", "noFallback")) assertIs<PositionCorrectionProductionRawImuResult.Unavailable>(result.rawImu)
		assertIs<PositionCorrectionProductionFixedCalibrationResult.Available>(result.fixedCalibration)
	}

	@ParameterizedTest @ValueSource(ints = [0, 1, 2])
	fun physicalLookupIsExactAndAmbiguityRejected(count: Int) {
		val t = tracker(); val tick = tick(t)
		val list = listOf(tracker("physical-hip-extra")) + List(count) { t }
		val result = bundle(PositionCorrectionNonHmdProductionAdapter(foundation(), { list }, ::body), tick)
		if (count == 1) assertIs<PositionCorrectionProductionRawImuResult.Available>(result.rawImu)
		else assertEquals(if (count == 0) PositionCorrectionProductionRawImuRejection.FALLBACK_TRACKER_NOT_FOUND
			else PositionCorrectionProductionRawImuRejection.FALLBACK_TRACKER_AMBIGUOUS,
			assertIs<PositionCorrectionProductionRawImuResult.Unavailable>(result.rawImu).reason)
		assertIs<PositionCorrectionProductionTeacherResult.Available>(result.teacher)
	}

	@Test fun postTickReceiptPropagatesBoundaryRejectionThenNextTickAdmits() {
		val t = tracker(); val cutoff = t.correctionOrientationSample()!!.receivedAtSystemNanos
		val tick = MonakaResolvedTickSnapshot(0, 1000, 1000, false, assignment(), emptyMap(), cutoff)
		t.setRotation(Quaternion.rotationAroundYAxis(.5f))
		val adapter = adapter(t)
		val first = bundle(adapter, tick)
		assertEquals(SlimeRawImuInputRejectionReason.SAMPLE_AFTER_TICK_CUTOFF,
			assertIs<PositionCorrectionProductionRawImuResult.Unavailable>(first.rawImu).boundaryReason)
		assertIs<PositionCorrectionProductionTeacherResult.Available>(first.teacher)
		assertIs<PositionCorrectionProductionRawImuResult.Available>(bundle(adapter, tick(t, sequence = 1)).rawImu)
	}

	@Test fun imuLossPropagatesBoundaryReasonAndLeavesTeacherIndependent() {
		val t = tracker(); t.status = TrackerStatus.TIMED_OUT
		val result = bundle(adapter(t), tick(t))
		assertEquals(SlimeRawImuInputRejectionReason.STATUS_UNUSABLE,
			assertIs<PositionCorrectionProductionRawImuResult.Unavailable>(result.rawImu).boundaryReason)
		assertIs<PositionCorrectionProductionTeacherResult.Available>(result.teacher)
	}

	@Test fun bodyUnavailableAndFixedMismatchNeverRebindAndReadProvidersOnce() {
		val t = tracker(); val tick = tick(t); var bodies = 0; var trackers = 0
		var live: HipBodyModelSnapshotResult = body()
		val f = foundation("different-model")
		val adapter = PositionCorrectionNonHmdProductionAdapter(f, { trackers++; listOf(t) }, { bodies++; live })
		val first = bundle(adapter, tick)
		assertEquals(1, bodies); assertEquals(1, trackers)
		assertEquals(PositionCorrectionProductionFixedCalibrationRejection.BODY_MODEL_ID_MISMATCH,
			assertIs<PositionCorrectionProductionFixedCalibrationResult.Unavailable>(first.fixedCalibration).reason)
		assertEquals("different-model", f.fixedCalibration.bodyModelId)
		live = HipBodyModelSnapshotResult.Unavailable("invalid-publication")
		val next = bundle(adapter, tick)
		assertEquals(2, bodies); assertEquals(2, trackers)
		assertSame(live, next.bodyModel)
		assertEquals("invalid-publication", assertIs<PositionCorrectionProductionFixedCalibrationResult.Unavailable>(next.fixedCalibration).bodyModelReason)
		assertIs<HipBodyModelSnapshotResult.Available>(first.bodyModel)
		assertIs<PositionCorrectionProductionRawImuResult.Available>(next.rawImu)
		assertIs<PositionCorrectionProductionTeacherResult.Available>(next.teacher)
	}

	@ParameterizedTest @ValueSource(strings = ["sequence", "time", "generation", "content"])
	fun fatalCoherenceRejectsBeforeProviders(case: String) {
		val t = tracker(); val tick = tick(t)
		val sources = when (case) {
			"sequence" -> sources(tick, sequence = 2)
			"time" -> sources(tick, now = 1001)
			"generation" -> sources(tick, a = tick.assignment.copy(generation = 4))
			else -> sources(tick, a = assignment(fallback = null))
		}
		val adapter = PositionCorrectionNonHmdProductionAdapter(foundation(), { error("Tracker provider read") }, { error("Body provider read") })
		assertIs<PositionCorrectionNonHmdCaptureResult.Rejected>(adapter.capture(tick, sources))
	}

	@Test fun bodyFacadeReadsAtomicPublicationAndOldBundleRemainsUnchanged() {
		val hpm = HumanPoseManager(emptyList())
		val manager = HumanPoseManager::class.java.getDeclaredField("skeletonConfigManager").apply { isAccessible = true }.get(hpm) as SkeletonConfigManager
		assertSame(manager.currentHipBodyModelSnapshot(), hpm.currentHipBodyModelSnapshot())
		val t = tracker(); val adapter = adapter(t, body = hpm::currentHipBodyModelSnapshot)
		val first = bundle(adapter, tick(t, paused = true)); assertTrue(first.paused)
		val old = assertIs<HipBodyModelSnapshotResult.Available>(first.bodyModel).snapshot
		hpm.setOffset(SkeletonConfigOffsets.NECK, old.neckLength + .1f)
		val next = bundle(adapter, tick(t, sequence = 2))
		val changed = assertIs<HipBodyModelSnapshotResult.Available>(next.bodyModel).snapshot
		assertNotEquals(old.identity.epoch, changed.identity.epoch)
		assertEquals(old.identity, assertIs<HipBodyModelSnapshotResult.Available>(first.bodyModel).snapshot.identity)
		assertIs<PositionCorrectionProductionFixedCalibrationResult.Available>(next.fixedCalibration)
	}

	@Test fun actualRuntimeMtpPhysicalImuAndLiveBodyPublicationComposeWithoutSideEffects() {
		val t = tracker(); val assignments = TrackerBodyAssignments()
		assignments.configure(hip, TrackerReference.mtp(key), TrackerReference.slime(t.name))
		val hpm = HumanPoseManager(emptyList()); val f = foundation()
		MonakaRuntime({ listOf(t) }, space, assignments, clock = { 1_000_000_000 },
			trackerReceiptClock = { t.correctionOrientationSample()!!.receivedAtSystemNanos + 10 }).use { runtime ->
			assertTrue(runtime.inbox.receive((MonakaCodec.encodeEnvelope(pose) as EncodeResult.Success).value, 1_000_000_000))
			val tick = runtime.tickSnapshot(); val source = runtime.captureSourceSnapshot(tick)
			val result = bundle(adapter(t, f, hpm::currentHipBodyModelSnapshot), tick, source)
			assertIs<PositionCorrectionProductionRawImuResult.Available>(result.rawImu)
			assertIs<PositionCorrectionProductionTeacherResult.Available>(result.teacher)
			assertIs<HipBodyModelSnapshotResult.Available>(result.bodyModel)
			assertIs<PositionCorrectionProductionFixedCalibrationResult.Available>(result.fixedCalibration)
			runtime.mtp.invalidateSamples(); val next = runtime.tickSnapshot()
			val lost = bundle(adapter(t, f, hpm::currentHipBodyModelSnapshot), next, runtime.captureSourceSnapshot(next))
			assertIs<PositionCorrectionProductionTeacherResult.Unavailable>(lost.teacher)
			assertIs<PositionCorrectionProductionRawImuResult.Available>(lost.rawImu)
			assertIs<PositionCorrectionProductionTeacherResult.Available>(result.teacher)
		}
	}

	@Test fun adapterStaticAuditExcludesDownstreamAndClockDependencies() {
		val source = Files.readString(Path.of("src/main/java/dev/monaka/tracking/PositionCorrectionNonHmdProductionAdapter.kt"))
		for (forbidden in listOf("RawHmdPoseInput", "PositionCorrectionPredictorSources", "PositionCorrectionRuntimeOrchestrator", "ConstraintIkWriteback", "System.nanoTime", "VRServer", "getOffset(", "HipRotationCorrection", "OutputContinuity"))
			assertFalse(source.contains(forbidden), forbidden)
		assertTrue(source.contains("adaptAtTick("))
		assertFalse(source.contains("PositionTeacherEpoch.from("))
	}
}
