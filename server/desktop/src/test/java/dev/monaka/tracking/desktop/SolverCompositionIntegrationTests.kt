package dev.monaka.tracking.desktop

import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.tracking.ConstraintIkWriteback
import dev.monaka.tracking.LogicalTracker
import dev.monaka.tracking.MonakaConfiguration
import dev.monaka.tracking.MonakaSolverComposition
import dev.monaka.tracking.OutputMode
import dev.monaka.tracking.PositionCorrectionConfig
import dev.monaka.tracking.PositionCorrectionFixedCalibrationConfig
import dev.monaka.tracking.PositionCorrectionLearningTuningConfig
import dev.monaka.tracking.PositionCorrectionMainMountCalibrationConfig
import dev.monaka.tracking.PositionCorrectionPairingPolicyConfig
import dev.monaka.tracking.PositionCorrectionPredictorPolicyConfig
import dev.monaka.tracking.PositionCorrectionRawImuSpaceConfig
import dev.monaka.tracking.PositionCorrectionReacquisitionTuningConfig
import dev.monaka.tracking.ProductionPositionCorrectionGate
import dev.monaka.tracking.RotationCorrectionConfig
import dev.monaka.tracking.RotationCorrectionFrames
import dev.monaka.tracking.RotationCorrectionTuning
import dev.monaka.tracking.TrackerBodyAssignments
import dev.monaka.tracking.TrackerReference
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SolverCompositionIntegrationTests {
	private val hip = TrackerPosition.HIP
	private fun pose() = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
	private fun key(p: MtpPose) = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
	private fun tracker(id: Int, body: TrackerPosition, position: Boolean) = Tracker(
		null, id, "composition:$id",
		trackerPosition = body, hasPosition = position, hasRotation = true, isHmd = body == TrackerPosition.HEAD,
		allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false,
	).also { it.status = TrackerStatus.OK }
	private fun position(p: MtpPose) = PositionCorrectionConfig(
		PositionCorrectionRawImuSpaceConfig("slime:composition:2", p.coordinate_space, true),
		PositionCorrectionMainMountCalibrationConfig("mount", "mount:1", key(p).observationId, Vector3.NULL),
		PositionCorrectionFixedCalibrationConfig("fit", "fit:1", "hmd", "model", Vector3.NULL, Quaternion.IDENTITY),
		PositionCorrectionPredictorPolicyConfig(100, 100, 100),
		PositionCorrectionPairingPolicyConfig(100, 100, 100, 0),
		PositionCorrectionLearningTuningConfig(.1, .2, 5.0, .6, .3, .05, 10_000_000_000, 300_000_000, .5, .3, .2, 0, 2, .0001),
		PositionCorrectionReacquisitionTuningConfig(400_000_000),
	)
	private inner class Fixture(
		configured: Boolean = false,
		hybrid: Boolean = true,
		correction: Boolean = false,
		frames: SameFrameDiagnostics? = null,
		configureAssignments: (TrackerBodyAssignments) -> Unit = {},
		val mutate: (TrackerBodyAssignments) -> Unit = {},
	) : AutoCloseable {
		val p = pose()
		val head = tracker(1, TrackerPosition.HEAD, true).also { it.position = Vector3(0f, 1.7f, 0f) }
		val imu = if (!correction) {
			tracker(2, hip, false)
		} else {
			Tracker(
				UDPDevice(InetSocketAddress("127.0.0.1", 20000), InetAddress.getLoopbackAddress(), "composition-test"),
				2, "composition:2", trackerPosition = hip, hasRotation = true, imuType = IMUType.UNKNOWN,
				allowReset = true, allowMounting = true, trackRotDirection = false,
			).also {
				it.status = TrackerStatus.OK
				it.setRotation(Quaternion.IDENTITY)
			}
		}
		val manager = HumanPoseManager(listOf(head, imu))
		val assignments = TrackerBodyAssignments().also {
			it.configure(hip, TrackerReference.mtp(key(p)), TrackerReference.slime(imu.name), if (hybrid) OutputMode.HYBRID else OutputMode.IK)
			configureAssignments(it)
		}
		var before: Runnable? = null
		var after: Runnable? = null
		var now = 1_000_000_000L
		var polls = 0
		var transitions = 0
		val failures = mutableListOf<Exception>()
		val integration: MonakaServerIntegration
		init {
			manager.setLegTweaksEnabled(false)
			manager.skeleton.ikSolver.enabled = true
			manager.update()
			integration = MonakaServerIntegration.startIfEnabled(
				true,
				{
					MonakaConfiguration(
						p.coordinate_space,
						assignments,
						port = 0,
						backgroundIkSharedSpace = p.coordinate_space,
						positionCorrection = if (configured) position(p) else null,
						rotationCorrection = if (correction) {
							RotationCorrectionConfig(
								RotationCorrectionFrames(Quaternion.IDENTITY, Quaternion.IDENTITY, p.coordinate_space, true),
								RotationCorrectionTuning(1, 100_000_000, .1, .2, 3.2, .1, 1000.0, 1, 100_000_000, 500_000_000),
							)
						} else {
							null
						},
					)
				},
				{
					polls++
					mutate(assignments)
					listOf(head, imu)
				}, manager.skeleton,
				{ before = it }, { failures += it }, { now }, configureDirectOutputs = {}, nextTrackerId = { 300 },
				registerAfterPose = { after = it }, onTransition = { transitions++ },
				frameDiagnostics = { frames },
			)!!
		}
		fun send(sequence: Long = 1) {
			assertTrue(integration.runtime.inbox.receive((MonakaCodec.encodeEnvelope(p.copy(sequence = sequence)) as EncodeResult.Success).value, now))
		}
		fun finish() {
			manager.update()
			after!!.run()
			assertTrue(failures.isEmpty(), failures.joinToString())
		}
		override fun close() = integration.close()
	}

	@Test fun numericCapturePinsAcceptedSampleAndMatchesActualComputedPose() {
		val bytes = java.io.ByteArrayOutputStream()
		val frames = SameFrameDiagnostics(bytes)
		Fixture(frames = frames).use { f ->
			f.send(1); f.before!!.run()
			f.send(2) // queued after the captured tick: must not appear in this frame.
			f.finish()
			val computed = f.manager.computedTrackers.first { it.trackerPosition == hip }
			val actualPosition = listOf(computed.position.x, computed.position.y, computed.position.z)
			val actualRotation = computed.getRotation().let { listOf(it.x, it.y, it.z, it.w) }
			f.after!!.run() // one post-hook emits once.
			frames.close(); assertTrue(frames.awaitTermination(2000))
			val lines = bytes.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
			assertEquals(1, lines.size)
			val record = com.fasterxml.jackson.databind.ObjectMapper().readTree(lines.single())
			assertEquals("0", record["tick"].asText())
			assertEquals(record["tick"], record["writeback_tick"])
			assertEquals("1", record["accepted_mvip"].single()["sequence"].asText())
			assertEquals(1, f.polls)
			assertEquals(0L, f.integration.frameDiagnosticFailures)
			val result = record["computed_trackers"].first { it["target"].asText() == "HIP" }
			assertEquals(actualPosition, result["position"].map { it.floatValue() })
			assertEquals(actualRotation, result["rotation_xyzw"].map { it.floatValue() })
			assertTrue(record["ik_targets_before"].any { it["target"].asText() == "HIP" })
			val before = record["bones_before"].associate { it["bone"].asText() to it["length"].floatValue() }
			val after = record["bones_after"].associate { it["bone"].asText() to it["length"].floatValue() }
			assertEquals(before, after)
			assertFalse(record["steamvr_observed"].booleanValue())
			assertTrue(record["post_ik_hook"].booleanValue())
			for (identity in listOf(f.p.publisher_id, f.p.source_id, f.p.tracker_id, f.p.session_id, f.imu.name)) assertFalse(lines.single().contains(identity), identity)
			val dir = Path.of("build/same-frame-diagnostic-fixtures")
			Files.createDirectories(dir)
			Files.writeString(dir.resolve("pinned-frame.jsonl"), bytes.toString(Charsets.UTF_8))
		}
	}

	@Test fun fullRotationNoneFullRecordsNullableComponentsWithoutRepolling() {
		val bytes = java.io.ByteArrayOutputStream()
		val frames = SameFrameDiagnostics(bytes)
		Fixture(frames = frames).use { f ->
			for ((index, mode) in listOf("full", "rotation_only", "none", "full").withIndex()) {
				f.now += 10_000_000
				val pose = f.p.copy(sequence = (index + 1).toLong(), modality = mode,
					position = if (mode == "full") f.p.position else null,
					orientation = if (mode == "none") null else f.p.orientation,
					validity = dev.monaka.protocol.v2.Validity(mode == "full", mode != "none"),
					confidence = dev.monaka.protocol.v2.Confidence(if (mode == "full") 1.0 else 0.0, if (mode == "none") 0.0 else 1.0),
					tracking_state = when (mode) { "full" -> "tracked"; "none" -> "lost"; else -> "degraded" })
				assertTrue(f.integration.runtime.inbox.receive((MonakaCodec.encodeEnvelope(pose) as EncodeResult.Success).value, f.now))
				f.before!!.run(); f.finish()
			}
			frames.close(); assertTrue(frames.awaitTermination(2000))
			val records = bytes.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { com.fasterxml.jackson.databind.ObjectMapper().readTree(it) }
			assertEquals(4, records.size)
			assertEquals(4, f.polls)
			assertEquals(0L, f.integration.frameDiagnosticFailures)
			assertEquals(listOf("1", "2", "3", "4"), records.map { it["accepted_mvip"].single()["sequence"].asText() })
			assertEquals(listOf(true, false, false, true), records.map { it["accepted_mvip"].single()["validity"]["position"].booleanValue() })
			assertEquals(listOf(true, true, false, true), records.map { it["accepted_mvip"].single()["validity"]["orientation"].booleanValue() })
			assertEquals(listOf(false, true, true, false), records.map { r -> r["resolved"].first { it["target"].asText() == "HIP" }["position"].isNull })
			assertTrue(records.all { r -> r["resolved"].first { it["target"].asText() == "HIP" }["rotation_xyzw"].isArray }) // explicit IMU fallback
			val dir = Path.of("build/same-frame-diagnostic-fixtures")
			Files.createDirectories(dir); Files.writeString(dir.resolve("loss-recovery.jsonl"), bytes.toString(Charsets.UTF_8))
		}
	}

	@ParameterizedTest @ValueSource(booleans = [false, true])
	fun diagnosticsDoNotChangeSolverOrCalibrationWithCorrectionEnabledOrDisabled(correction: Boolean) {
		val frames = SameFrameDiagnostics(java.io.ByteArrayOutputStream())
		Fixture(correction = correction).use { off ->
			Fixture(correction = correction, frames = frames).use { on ->
				repeat(3) { index ->
					for (f in listOf(off, on)) { f.send((index + 1).toLong()); f.before!!.run(); f.finish(); f.now += 10_000_000 }
					assertEquals(off.manager.computedTrackers.map { it.position }, on.manager.computedTrackers.map { it.position })
					assertEquals(off.manager.computedTrackers.map { it.getRotation() }, on.manager.computedTrackers.map { it.getRotation() })
					assertEquals(off.manager.skeleton.ikSolver.calibrationSnapshot(), on.manager.skeleton.ikSolver.calibrationSnapshot())
				}
				assertEquals(3, off.polls); assertEquals(3, on.polls)
				assertEquals(0L, on.integration.frameDiagnosticFailures)
			}
		}
		frames.close(); assertTrue(frames.awaitTermination(2000)); assertEquals(3L, frames.written.get())
	}

	@Test fun diagnosticIoFailureCannotStopSubsequentSolverTicks() {
		val frames = SameFrameDiagnostics(object : java.io.OutputStream() { override fun write(b: Int): Unit = throw java.io.IOException("blocked device") })
		Fixture(frames = frames).use { f ->
			f.send(); f.before!!.run(); f.finish()
			assertTrue(frames.awaitTermination(2000))
			assertEquals("IOException", frames.failure)
			f.now += 10_000_000; f.send(2); f.before!!.run(); f.finish()
			assertEquals(2, f.polls); assertTrue(f.failures.isEmpty())
			assertNotNull(f.manager.skeleton.hipTracker)
		}
	}

	@Test fun eightOpaqueSourcesReachSameIkFrameAndRejectOnlyMismatchedSpace() {
		val targets = listOf(hip, TrackerPosition.UPPER_CHEST, TrackerPosition.LEFT_UPPER_LEG,
			TrackerPosition.RIGHT_UPPER_LEG, TrackerPosition.LEFT_FOOT, TrackerPosition.RIGHT_FOOT,
			TrackerPosition.LEFT_UPPER_ARM, TrackerPosition.RIGHT_UPPER_ARM)
		val seed = pose()
		val packets = targets.mapIndexed { index, _ -> seed.copy(
			publisher_id = "opaque-publisher-${index / 5}", source_id = "opaque-source-$index",
			tracker_id = "opaque-tracker-$index", sequence = 1,
			position = listOf(index * .05, 1.0 + index * .02, -.1),
			input = seed.input.copy(source_id = "opaque-source-$index", device_id = "opaque-device-$index")) }
		val bytes = java.io.ByteArrayOutputStream()
		val frames = SameFrameDiagnostics(bytes)
		Fixture(hybrid = false, frames = frames, configureAssignments = { a ->
			for ((index, target) in targets.withIndex()) a.configure(target, TrackerReference.mtp(key(packets[index])))
		}).use { f ->
			fun submit(p: MtpPose) {
				val encoded = MonakaCodec.encodeEnvelope(p)
				assertTrue(encoded is EncodeResult.Success, encoded.toString())
				assertTrue(f.integration.runtime.inbox.receive(encoded.value, f.now))
			}
			packets.forEach(::submit); f.before!!.run(); f.finish()
			f.now += 10_000_000
			submit(packets[0].copy(sequence = 2, coordinate_space = seed.coordinate_space.copy(revision = seed.coordinate_space.revision + 1)))
			f.before!!.run(); f.finish()
			assertEquals(1, f.integration.runtime.inbox.diagnostics().getValue("SpaceMismatch"))
			f.now += 10_000_000
			submit(packets[0].copy(sequence = 3)); f.before!!.run(); f.finish()
			frames.close(); assertTrue(frames.awaitTermination(2000))
			assertEquals(3, f.polls); assertEquals(0L, f.integration.frameDiagnosticFailures)
			val records = bytes.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { com.fasterxml.jackson.databind.ObjectMapper().readTree(it) }
			assertEquals(3, records.size)
			assertEquals(listOf(8, 7, 8), records.map { it["accepted_mvip"].size() })
			for ((index, target) in targets.withIndex()) {
				val resolved = records[0]["resolved"].first { it["target"].asText() == target.name }
				assertEquals(frames.identity(key(packets[index]).observationId), resolved["position_owner"].asText())
				assertEquals(resolved["position_owner"], resolved["rotation_owner"])
				assertEquals(packets[index].position!!.map { it.toFloat() }, resolved["position"].map { it.floatValue() })
				assertTrue(records[0]["ik_targets_before"].any { it["target"].asText() == target.name }, target.name)
				val second = records[1]["resolved"].first { it["target"].asText() == target.name }
				assertEquals(index == 0, second["position"].isNull)
				assertEquals(index == 0, second["rotation_xyzw"].isNull)
				assertTrue(records[2]["resolved"].first { it["target"].asText() == target.name }["position"].isArray)
			}
			for (record in records) {
				assertEquals(record["tick"], record["writeback_tick"])
				assertEquals(record["bones_before"].map { it["length"] }, record["bones_after"].map { it["length"] })
				assertTrue(record["computed_trackers"].all { t -> t["position"].isNull || t["position"].all { it.isNumber && it.doubleValue().isFinite() } })
			}
			val dir = Path.of("build/same-frame-diagnostic-fixtures")
			Files.createDirectories(dir); Files.writeString(dir.resolve("eight-source-space-recovery.jsonl"), bytes.toString(Charsets.UTF_8))
		}
	}

	@Test fun currentWritebackUsesAssignmentAButNextTickUsesB() {
		var change = true
		Fixture(hybrid = false, mutate = { a ->
			if (change) {
				change = false
				a.configure(hip, TrackerReference("missing-next-main"), TrackerReference("missing-next-imu"))
			}
		}).use { f ->
			val a = f.assignments.snapshot()
			f.send()
			f.before!!.run()
			assertEquals("monaka-private:HIP:${key(f.p).observationId}", f.manager.skeleton.hipTracker!!.name)
			assertNotEquals(a, f.assignments.snapshot())
			f.finish()
			f.now++
			f.before!!.run()
			f.finish()
			assertNull(f.manager.skeleton.hipTracker)
		}
	}

	@Test fun rotationCorrectionAndDiagnosticsPinAssignmentCapturedBeforePolling() {
		var change = true
		Fixture(correction = true, mutate = { a ->
			if (change) {
				change = false
				a.configure(hip, TrackerReference("missing-next-main"), TrackerReference("missing-next-imu"), OutputMode.HYBRID)
			}
		}).use { f ->
			val a = f.assignments.snapshot()
			f.send()
			f.before!!.run()
			f.finish()
			assertNotEquals(a, f.assignments.snapshot())
			val generation = f.integration.rotationCorrection!!.javaClass.getDeclaredField("activeAssignmentGeneration").also { it.isAccessible = true }
			assertEquals(a.generation, generation.get(f.integration.rotationCorrection))
			val diagnostic = f.integration.lastDiagnosticSnapshot!!
			assertEquals(key(f.p).observationId, diagnostic.mainSource)
			assertEquals("slime:${f.imu.name}", diagnostic.imuSource)
		}
	}

	@Test fun pendingFramePinsNowAssignmentAndPauseAndIsConsumedOnce(): Unit = Fixture().use { f ->
		f.send()
		f.before!!.run()
		val pinnedNow = f.now
		// Advance runtime independently after before hook, simulating a later clock/assignment.
		f.now += 500_000_000
		f.assignments.configure(TrackerPosition.LEFT_FOOT, TrackerReference("later"))
		f.integration.runtime.tickSnapshot(true)
		f.manager.skeleton.setPauseTracking(true, "later live state")
		f.after!!.run()
		val output = f.integration.directOutput.trackers.getValue(hip).monakaOutputPose!!
		assertNotNull(output.rotation)
		val transitionCount = f.transitions
		val frameField = f.integration.javaClass.getDeclaredField("pendingFrame").also { it.isAccessible = true }
		assertNull(frameField.get(f.integration))
		f.after!!.run()
		assertEquals(transitionCount, f.transitions)
		assertEquals(pinnedNow + 500_000_000, f.integration.runtime.lastTickNanos)
		// Diagnostics age must use the pending frame's now, not the newer runtime now.
		assertTrue(f.integration.lastDiagnosticSnapshot!!.mainAgeNanos!! < 500_000_000)
		assertTrue(f.failures.isEmpty())
	}

	@Test fun pendingOverwriteFailsClosedAndClearsOutputsAndHooks(): Unit = Fixture().use { f ->
		f.send()
		f.before!!.run()
		f.before!!.run()
		assertEquals(1, f.failures.size)
		assertTrue(f.failures.single().message!!.contains("not been consumed"))
		assertNull(f.before)
		assertNull(f.after)
		assertNull(f.integration.directOutput.trackers.getValue(hip).monakaOutputPose!!.position)
		assertFailsWith<IllegalStateException> { f.integration.runtime.tickSnapshot() }
	}

	@ParameterizedTest
	@ValueSource(booleans = [true, false])
	fun configOnlyChangesGateAndKeepsGenericNumericsAndDormantProviders(configured: Boolean) {
		Fixture(configured).use { f ->
			assertEquals(
				if (configured) ProductionPositionCorrectionGate.CONFIGURED_RAW_HMD_BLOCKED else ProductionPositionCorrectionGate.NOT_CONFIGURED,
				f.integration.positionCorrectionGate,
			)
			f.send()
			f.before!!.run()
			f.finish()
			assertEquals(1, f.polls)
			val proxy = f.manager.skeleton.hipTracker!!
			val visible = f.integration.directOutput.trackers.getValue(hip).monakaOutputPose!!
			assertEquals(key(f.p).observationId, visible.rotation!!.sourceId)
			assertEquals(f.p.position!![1].toFloat(), proxy.position.y, 1e-5f)
			val ownerField = f.integration.javaClass.getDeclaredField("solverComposition").also { it.isAccessible = true }
			val owner = ownerField.get(f.integration) as MonakaSolverComposition
			val sessionField = owner.javaClass.getDeclaredField("session").also { it.isAccessible = true }
			val sequenceField = owner.javaClass.getDeclaredField("lastReservedTickSequence").also { it.isAccessible = true }
			assertEquals(0L, sequenceField.get(owner))
			assertNull(sessionField.get(owner))
			f.manager.skeleton.setPauseTracking(true, "test")
			f.before!!.run()
			f.finish()
			f.manager.skeleton.setPauseTracking(false, "test")
			f.before!!.run()
			f.finish()
			assertEquals(3, f.polls)
			assertNull(sessionField.get(owner))
			assertEquals(2L, sequenceField.get(owner))
		}
	}

	@ParameterizedTest
	@ValueSource(ints = [0, 1, 2, 3])
	fun startupFailureClosesReceiverWritebackOutputsAndRegisteredHooks(which: Int) {
		val p = pose()
		val head = tracker(1, TrackerPosition.HEAD, true)
		val manager = HumanPoseManager(listOf(head))
		val a = TrackerBodyAssignments().also { it.configure(hip, TrackerReference.mtp(key(p)), outputMode = OutputMode.HYBRID) }
		val port = DatagramSocket(0).use { it.localPort }
		var before: Runnable? = null
		var after: Runnable? = null
		var output: Tracker? = null
		assertFailsWith<IllegalStateException> {
			MonakaServerIntegration.startIfEnabled(
				true,
				{ MonakaConfiguration(p.coordinate_space, a, port = port) },
				{ listOf(head) },
				manager.skeleton,
				{
					before = it
					if (which == 2 && it != null) error("before registration")
				},
				configureDirectOutputs = {
					output = it.single()
					if (which == 1) error("output registration")
				},
				nextTrackerId = { if (which == 0) error("id allocation") else 100 },
				registerAfterPose = {
					after = it
					if (which == 3 && it != null) error("after registration")
				},
			)
		}
		DatagramSocket(port).use { assertEquals(port, it.localPort) }
		assertNull(before)
		assertNull(after)
		output?.let { assertNull(it.monakaOutputPose!!.rotation) }
		// A leaked writeback input view would still manage/filter this HIP input.
		val raw = tracker(55, hip, true)
		manager.skeleton.setTrackersFromList(listOf(head, raw))
		assertSame(raw, manager.skeleton.hipTracker)
	}

	@Test fun productionSourceUsesPinnedSnapshotAndNeverCallsFutureCapture() {
		val s = Files.readString(Path.of("src/main/java/dev/monaka/tracking/desktop/MonakaServerIntegration.kt"))
		val tick = s.substringAfter("fun tick()").substringBefore("fun finishPoseUpdate()")
		for (forbidden in listOf("runtime.tick(", "runtime.assignments.snapshot()", "runtime.lastTickNanos", "ConstraintIkWriteback", "captureSourceSnapshot", "commitPositionCorrection", ".capture(")) {
			assertFalse(tick.contains(forbidden), forbidden)
		}
		assertTrue(tick.contains("runtime.tickSnapshot("))
		assertTrue(tick.contains("runtime.resolvedTrackingPoses(tick)"))
		assertTrue(tick.contains("observations(tick.nowNanos, tick.assignment)"))
		assertEquals(1, Regex("solverComposition.commitGeneric").findAll(tick).count())
		assertFalse(s.contains("runtime.lastTickNanos"))
		assertFalse(s.contains("ConstraintIkWriteback"))
	}
}
