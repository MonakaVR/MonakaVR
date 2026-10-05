package dev.monaka.tracking.desktop

import dev.monaka.protocol.v2.*
import dev.monaka.tracking.*
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import io.github.axisangles.ktmath.Vector3
import io.github.axisangles.ktmath.Quaternion
import dev.slimevr.tracking.trackers.udp.IMUType
import dev.slimevr.tracking.trackers.udp.UDPDevice
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

class HybridServerIntegrationTests {
	private fun fixture() = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
	private fun tracker(id: Int, body: TrackerPosition, position: Boolean) = Tracker(null, id, "test-input:$id",
		trackerPosition = body, hasPosition = position, hasRotation = true, isHmd = body == TrackerPosition.HEAD,
		allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false).also { it.status = TrackerStatus.OK }

	@Test fun optedInCorrectionLearnsDuringDirectAndAppliesOnceToFallbackIkInput() {
		val p = fixture(); val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		val head = tracker(301, TrackerPosition.HEAD, true).also { it.position = Vector3(0f, 1.7f, 0f) }
		val device = UDPDevice(InetSocketAddress("127.0.0.1", 20001), InetAddress.getLoopbackAddress(), "correction-test")
		val imu = Tracker(device, 302, "correction-imu", trackerPosition = TrackerPosition.HIP,
			hasRotation = true, imuType = IMUType.UNKNOWN, allowReset = true, allowMounting = true,
			trackRotDirection = false).also { it.status = TrackerStatus.OK; it.setRotation(Quaternion.IDENTITY) }
		val mountToBody = imu.resetsHandler.getCorrectionReferenceRotationFrom(Quaternion.IDENTITY).inv()
		val assignments = TrackerBodyAssignments().also {
			it.configure(TrackerPosition.HIP, TrackerReference.mtp(key), TrackerReference.slime(imu.name), OutputMode.HYBRID)
		}
		val tuning = RotationCorrectionTuning(5_000_000, 100_000_000, .0001, .0001, 3.2, .1, 1000.0, 1, 100_000_000, 100_000_000)
		val correction = RotationCorrectionConfig(RotationCorrectionFrames(Quaternion.IDENTITY, mountToBody, p.coordinate_space, true), tuning)
		val hpm = HumanPoseManager(listOf(head, imu)); hpm.setLegTweaksEnabled(false)
		hpm.skeleton.ikSolver.enabled = true; hpm.update()
		var before: Runnable? = null; var after: Runnable? = null; var now = 1_000_000_000L
		val outputs = mutableListOf<Tracker>(); val failures = mutableListOf<Exception>()
		val integration = MonakaServerIntegration.startIfEnabled(true,
			{ MonakaConfiguration(p.coordinate_space, assignments, port = 0, backgroundIkSharedSpace = p.coordinate_space,
				continuityTuning = ContinuityTuning(5, 10, 1),
				rotationCorrection = correction) }, { listOf(head, imu) }, hpm.skeleton,
			{ before = it }, { failures += it }, { now }, configureDirectOutputs = { outputs += it },
			nextTrackerId = { 400 }, registerAfterPose = { after = it })!!
		integration.use {
			val output = outputs.single(); val identity = Triple(output.id, output.name, output.trackerPosition)
			fun send(sequence: Long, mode: String, imuRotation: Quaternion? = Quaternion.IDENTITY,
				advanceNanos: Long = 10_000_000, mainAgeOffsetNanos: Long = 0) {
				now += advanceNanos
				if (imuRotation != null) imu.setRotation(imuRotation) // null means heartbeat/poll only.
				val angle = .5
				val pose = p.copy(sequence = sequence, modality = mode,
					timestamp_ns = p.timestamp_ns - mainAgeOffsetNanos,
					orientation = if (mode == "none") null else listOf(0.0, sin(angle / 2), 0.0, cos(angle / 2)),
					position = if (mode == "full") listOf(.1, 1.0, .1) else null,
					validity = Validity(mode == "full", mode != "none"),
					confidence = Confidence(if (mode == "full") 1.0 else 0.0, if (mode == "none") 0.0 else 1.0),
					tracking_state = when (mode) { "full" -> "tracked"; "none" -> "lost"; else -> "degraded" })
				assertTrue(integration.runtime.inbox.receive((MonakaCodec.encodeEnvelope(pose) as EncodeResult.Success).value, now))
				before!!.run(); hpm.update(); after!!.run()
				assertTrue(failures.isEmpty(), failures.joinToString())
				assertEquals(identity, Triple(output.id, output.name, output.trackerPosition))
			}
			send(1, "full")
			val main = Quaternion.rotationAroundYAxis(.5f)
			assertTrue(kotlin.math.abs(output.monakaOutputPose!!.rotation!!.value.dot(main)) > .9999f)
			assertFalse(integration.rotationCorrection!!.ready)
			send(2, "full")
			assertTrue(integration.rotationCorrection!!.ready)
			assertTrue(kotlin.math.abs(output.monakaOutputPose!!.rotation!!.value.dot(main)) > .9999f)
			assertTrue(kotlin.math.abs(hpm.skeleton.hipTracker!!.getRotation().dot(main)) > .999f)
			val computed = hpm.skeleton.computedHipTracker
			send(3, "rotation_only")
			assertSame(computed, hpm.skeleton.computedHipTracker)
			assertTrue(kotlin.math.abs(hpm.skeleton.hipTracker!!.getRotation().dot(main)) > .999f)
			assertEquals(RotationCorrectionState.DEGRADED, integration.rotationCorrection!!.state)
			val roll = Quaternion.rotationAroundXAxis(.3f)
			send(4, "rotation_only", roll)
			val degradedRawImu = integration.runtime.pipeline.observations(now)
				.single { it.sourceId == "slime:${imu.name}" }.correctionRotation!!
			val degradedExpected = (integration.rotationCorrection!!.correction * (degradedRawImu * mountToBody).unit()).unit()
			assertEquals("slime:${imu.name}", output.monakaOutputPose!!.rotation!!.sourceId)
			assertTrue(kotlin.math.abs(output.monakaOutputPose!!.rotation!!.value.unit().dot(degradedExpected)) > .999f)
			assertTrue(kotlin.math.abs(hpm.skeleton.hipTracker!!.getRotation().unit().dot(degradedExpected)) > .999f)
			send(5, "none")
			assertEquals(RotationCorrectionState.IMU_ONLY, integration.rotationCorrection!!.state)
			assertTrue(kotlin.math.abs(hpm.skeleton.hipTracker!!.getRotation().unit().dot(
				output.monakaOutputPose!!.rotation!!.value.unit())) > .999f)
			send(6, "full")
			assertSame(computed, hpm.skeleton.computedHipTracker)
			assertTrue(kotlin.math.abs(hpm.skeleton.hipTracker!!.getRotation().dot(main)) > .999f)
			send(7, "none", roll)
			send(8, "none", roll) // fallback transition has completed.
			val rawFallback = integration.runtime.pipeline.observations(now)
				.single { it.sourceId == "slime:${imu.name}" }.correctionRotation!!
			val expected = (integration.rotationCorrection!!.correction * (rawFallback * mountToBody).unit()).unit()
			val doubled = (integration.rotationCorrection!!.correction * expected).unit()
			fun same(a: Quaternion, b: Quaternion) = kotlin.math.abs(a.unit().dot(b.unit())) > .999f
			assertTrue(same(expected, hpm.skeleton.hipTracker!!.getRotation()))
			assertTrue(same(expected, output.monakaOutputPose!!.rotation!!.value))
			assertFalse(same(doubled, output.monakaOutputPose!!.rotation!!.value))
			val learned = integration.rotationCorrection!!.correction
			val lastLearned = integration.rotationCorrection!!.lastLearnedAtNanos
			send(9, "rotation_only", null, 150_000_000)
			assertEquals(key.observationId, integration.runtime.resolvedTrackingPoses(
				integration.runtime.pipeline.resolveAll(now))[TrackerPosition.HIP]!!.rotationOwner)
			assertTrue(same(main, hpm.skeleton.hipTracker!!.getRotation()))
			assertTrue(same(main, output.monakaOutputPose!!.rotation!!.value))
			assertEquals(lastLearned, integration.rotationCorrection!!.lastLearnedAtNanos)
			assertTrue(same(learned, integration.rotationCorrection!!.correction))
			send(10, "none", null)
			assertNull(integration.runtime.resolvedTrackingPoses(
				integration.runtime.pipeline.resolveAll(now))[TrackerPosition.HIP]!!.rotation)
			assertNull(hpm.skeleton.hipTracker)
			assertNull(output.monakaOutputPose!!.rotation)
			send(11, "none", roll)
			assertTrue(integration.rotationCorrection!!.ready)
			assertTrue(same(expected, hpm.skeleton.hipTracker!!.getRotation()))
			assertTrue(same(expected, output.monakaOutputPose!!.rotation!!.value))
			send(12, "full")
			assertTrue(same(main, hpm.skeleton.hipTracker!!.getRotation()))
			send(13, "full")
			send(14, "full")
			assertTrue(same(main, output.monakaOutputPose!!.rotation!!.value))
			assertEquals(identity, Triple(output.id, output.name, output.trackerPosition))
			val heldCorrection = integration.rotationCorrection!!.correction
			val heldLearnedAt = integration.rotationCorrection!!.lastLearnedAtNanos
			val pairRejects = integration.rotationCorrection!!.rejections["pair_time_invalid"] ?: 0
			send(15, "rotation_only", roll, 210_000_000, 200_000_000)
			assertTrue((integration.rotationCorrection!!.rejections["pair_time_invalid"] ?: 0) > pairRejects)
			assertEquals(heldLearnedAt, integration.rotationCorrection!!.lastLearnedAtNanos)
			assertEquals(heldCorrection, integration.rotationCorrection!!.correction)
			val freshImu = integration.runtime.pipeline.observations(now)
				.single { it.sourceId == "slime:${imu.name}" }
			val expectedHeld = (heldCorrection * (freshImu.correctionRotation!! * mountToBody).unit()).unit()
			assertTrue(same(expectedHeld, hpm.skeleton.hipTracker!!.getRotation()))
			val physicalSequence = freshImu.provenance!!.sequence
			now += 10_000_000 // Re-poll the same accepted physical IMU and MTP samples.
			before!!.run(); hpm.update(); after!!.run()
			assertTrue(failures.isEmpty(), failures.joinToString())
			assertEquals(physicalSequence, integration.runtime.pipeline.observations(now)
				.single { it.sourceId == "slime:${imu.name}" }.provenance!!.sequence)
			assertEquals(heldLearnedAt, integration.rotationCorrection!!.lastLearnedAtNanos)
			assertTrue(same(expectedHeld, output.monakaOutputPose!!.rotation!!.value))
			send(16, "rotation_only", null, 150_000_000)
			assertEquals(key.observationId, integration.runtime.resolvedTrackingPoses(
				integration.runtime.pipeline.resolveAll(now))[TrackerPosition.HIP]!!.rotationOwner)
			assertTrue(same(main, output.monakaOutputPose!!.rotation!!.value))
			send(17, "rotation_only", roll)
			assertTrue(integration.rotationCorrection!!.ready)
			assertEquals(heldLearnedAt, integration.rotationCorrection!!.lastLearnedAtNanos)
			assertTrue(same(expectedHeld, hpm.skeleton.hipTracker!!.getRotation()))
			assertTrue(same(expectedHeld, output.monakaOutputPose!!.rotation!!.value))
			assertEquals(identity, Triple(output.id, output.name, output.trackerPosition))
		}
	}

	@Test fun productionHooksComposeSameTickBackgroundOnlyAfterExistingIkAndClearOnClose() {
		val p = fixture(); val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		val head = tracker(1, TrackerPosition.HEAD, true).also { it.position = Vector3(0f, 1.7f, 0f) }
		val imu = tracker(2, TrackerPosition.HIP, false)
		val hpm = HumanPoseManager(listOf(head, imu)); hpm.setLegTweaksEnabled(false)
		hpm.skeleton.ikSolver.enabled = true; hpm.update()
		val a = TrackerBodyAssignments().also { it.configure(TrackerPosition.HIP, TrackerReference.mtp(key), TrackerReference.slime(imu.name), OutputMode.HYBRID) }
		var before: Runnable? = null; var after: Runnable? = null
		var now = 1_000_000_000L
		val events = mutableListOf<OutputTransition>(); val failures = mutableListOf<Exception>()
		val outputs = mutableListOf<Tracker>()
		val integration = MonakaServerIntegration.startIfEnabled(true,
			{ MonakaConfiguration(p.coordinate_space, a, port = 0, backgroundIkSharedSpace = p.coordinate_space,
				continuityTuning = ContinuityTuning(10, 20, 10)) },
			{ listOf(head, imu) }, hpm.skeleton, { before = it }, { failures += it }, { now },
			configureDirectOutputs = { outputs += it }, nextTrackerId = { 100 }, registerAfterPose = { after = it }, onTransition = events::add,
		)!!
		integration.use {
			val output = outputs.single(); val identity = Triple(output.id, output.name, output.trackerPosition)
			var lastEmitted: OutputPose? = null
			var startOfReacquisition: OutputPose? = null
			for ((sequence, mode) in listOf("full", "full", "rotation_only", "rotation_only", "full", "full", "full", "full").withIndex()) {
				now += 10_000_000
				val pose = p.copy(sequence = sequence.toLong(), modality = mode,
					position = if (mode == "full") listOf(.1, 1.0, .1) else null,
					validity = Validity(mode == "full", true), confidence = Confidence(if (mode == "full") 1.0 else 0.0, 1.0),
					tracking_state = if (mode == "full") "tracked" else "degraded")
				assertTrue(integration.runtime.inbox.receive((MonakaCodec.encodeEnvelope(pose) as EncodeResult.Success).value, now))
				before!!.run()
				assertNull(output.monakaOutputPose!!.position) // no stale previous-tick output before solve
				hpm.update()
				after!!.run()
				assertEquals(identity, Triple(output.id, output.name, output.trackerPosition))
				assertNotNull(hpm.skeleton.hipTracker) // participates even while output is Direct
				if (sequence < 2) {
					assertEquals(Vector3(.1f, 1f, .1f), output.monakaOutputPose!!.position!!.value)
					assertEquals(OutputPositionSource.RESOLVED_MAIN, output.monakaOutputPose!!.positionSource)
				} else if (sequence == 2) {
					assertEquals(lastEmitted!!.position!!.value, output.monakaOutputPose!!.position!!.value)
				} else if (sequence == 3) {
					assertEquals(hpm.skeleton.computedHipTracker!!.position, output.monakaOutputPose!!.position!!.value)
					assertEquals(OutputPositionSource.BACKGROUND_IK, output.monakaOutputPose!!.positionSource)
				} else if (sequence == 4) {
					assertEquals(lastEmitted!!.position!!.value, output.monakaOutputPose!!.position!!.value)
				} else if (sequence == 5) {
					assertEquals(OutputPositionSource.CONVERGENCE, output.monakaOutputPose!!.positionSource)
					assertEquals(lastEmitted!!.position!!.value, output.monakaOutputPose!!.position!!.value)
					startOfReacquisition = output.monakaOutputPose
				} else if (sequence == 6) {
					assertEquals(OutputPositionSource.CONVERGENCE, output.monakaOutputPose!!.positionSource)
					val start = startOfReacquisition!!.position!!.value
					assertEquals(start * .5f + Vector3(.1f, 1f, .1f) * .5f, output.monakaOutputPose!!.position!!.value)
				} else if (sequence == 7) {
					assertEquals(Vector3(.1f, 1f, .1f), output.monakaOutputPose!!.position!!.value)
					assertEquals(OutputPositionSource.RESOLVED_MAIN, output.monakaOutputPose!!.positionSource)
				}
				lastEmitted = output.monakaOutputPose
				val eventCount = events.size; after!!.run(); assertEquals(eventCount, events.size) // frame consumed once
			}
			assertEquals(listOf(ContinuityState.MAIN_DIRECT, ContinuityState.FALLBACK_ACTIVE,
				ContinuityState.FALLBACK_ACTIVE, ContinuityState.REACQUIRING, ContinuityState.MAIN_DIRECT), events.map { it.state })
			assertTrue(failures.isEmpty())
		}
		assertNull(before); assertNull(after); assertFalse(integration.receiver.isAlive)
		assertFalse(outputs.single().monakaOutputPose!!.positionValid)
	}

	@Test fun hybridRequiresPostIkHookAndUnknownAlignmentCannotManufactureFallback() {
		val p = fixture(); val a = TrackerBodyAssignments().also { it.configure(TrackerPosition.HIP, TrackerReference.slime("main"), outputMode = OutputMode.HYBRID) }
		val hpm = HumanPoseManager(emptyList())
		assertFailsWith<IllegalArgumentException> {
			MonakaServerIntegration.startIfEnabled(true, { MonakaConfiguration(p.coordinate_space, a, port = 0) }, { emptyList() }, hpm.skeleton, {})
		}
		val reader = BackgroundIkPoseReader(hpm.skeleton, BackgroundIkAlignment.UNVERIFIED)
		assertNull(reader.read(TrackerPosition.HIP, p.coordinate_space, 0).pose)
	}

	@Test fun deterministicRealIkHybridDiagnosticStress() {
		val p = fixture(); val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		val generatedEvents = mutableSetOf<Int>()
		val continuityStates = mutableSetOf<ContinuityState>()
		for (seed in 0 until 5) {
			val random = Random(seed)
			val head = tracker(600 + seed * 2, TrackerPosition.HEAD, true).also { it.position = Vector3(0f, 1.7f, 0f) }
			val device = UDPDevice(InetSocketAddress("127.0.0.1", 21000 + seed), InetAddress.getLoopbackAddress(), "stress-$seed")
			val imu = Tracker(device, 601 + seed * 2, "stress-imu-$seed", trackerPosition = TrackerPosition.HIP,
				hasRotation = true, imuType = IMUType.UNKNOWN, allowReset = true, allowMounting = true,
				trackRotDirection = false).also { it.status = TrackerStatus.OK; it.setRotation(Quaternion.IDENTITY) }
			val mount = imu.resetsHandler.getCorrectionReferenceRotationFrom(Quaternion.IDENTITY).inv()
			val assignments = TrackerBodyAssignments().also {
				it.configure(TrackerPosition.HIP, TrackerReference.mtp(key), TrackerReference.slime(imu.name), OutputMode.HYBRID)
			}
			val tuning = RotationCorrectionTuning(5_000_000, 100_000_000, .001, .001,
				3.2, .2, 1000.0, 1, 100_000_000, 100_000_000)
			val correction = RotationCorrectionConfig(RotationCorrectionFrames(Quaternion.IDENTITY, mount,
				p.coordinate_space, true), tuning)
			val hpm = HumanPoseManager(listOf(head, imu)); hpm.setLegTweaksEnabled(false)
			hpm.skeleton.ikSolver.enabled = true; hpm.update()
			var before: Runnable? = null; var after: Runnable? = null; var now = 1_000_000_000L
			var sequence = 0L; var session = 0; val failures = mutableListOf<Exception>()
			val outputs = mutableListOf<Tracker>(); val events = mutableListOf<HybridTrackingDiagnosticEvent>()
			val integration = MonakaServerIntegration.startIfEnabled(true,
				{ MonakaConfiguration(p.coordinate_space, assignments, port = 0, backgroundIkSharedSpace = p.coordinate_space,
					continuityTuning = ContinuityTuning(5, 10, 1), rotationCorrection = correction) },
				{ listOf(head, imu) }, hpm.skeleton, { before = it }, { failures += it }, { now },
				configureDirectOutputs = { outputs += it }, nextTrackerId = { 700 + seed },
				registerAfterPose = { after = it }, onDiagnostic = events::add)!!
			integration.use {
				val output = outputs.single(); val identity = Triple(output.id, output.name, output.trackerPosition)
				for (step in 0 until 80) {
					val eventCount = events.size
					val generated = random.nextInt(12)
					generatedEvents += generated
					val mode = when (generated % 4) { 0, 1 -> "full"; 2 -> "rotation_only"; else -> "none" }
					now += if (generated == 0) 110_000_000 else 10_000_000
					if (generated == 1) session++
					if (generated !in setOf(2, 3)) {
						sequence++
						val timestamp = p.timestamp_ns + sequence * 1_000_000
						val sessionId = java.util.UUID.nameUUIDFromBytes("$seed/$session".toByteArray()).toString()
						val pose = p.copy(sequence = sequence, session_id = sessionId,
							timestamp_ns = timestamp, sent_at_ns = timestamp,
							input = p.input.copy(session_id = sessionId, sequence = sequence), modality = mode,
							position = if (mode == "full") listOf(.1, 1.0, .1) else null,
							orientation = if (mode == "none") null else listOf(0.0, sin(.25), 0.0, cos(.25)),
							validity = Validity(mode == "full", mode != "none"),
							confidence = Confidence(if (mode == "full") 1.0 else 0.0, if (mode == "none") 0.0 else 1.0),
							tracking_state = when (mode) { "full" -> "tracked"; "none" -> "lost"; else -> "degraded" })
						assertTrue(integration.runtime.inbox.receive((MonakaCodec.encodeEnvelope(pose) as EncodeResult.Success).value, now),
							"seed=$seed step=$step generated=$generated")
					}
					if (generated !in setOf(4, 5)) imu.setRotation((Quaternion.rotationAroundXAxis(.1f * (step % 4)) *
						Quaternion.rotationAroundYAxis(.2f)).unit())
					hpm.skeleton.ikSolver.enabled = generated != 6
					hpm.skeleton.setPauseTracking(generated == 7, "hybrid stress")
					before!!.run(); hpm.update(); after!!.run()
					val snapshot = integration.lastDiagnosticSnapshot!!
					snapshot.continuityState?.let(continuityStates::add)
					val visible = output.monakaOutputPose!!
					val context = "seed=$seed step=$step event=$generated main=$mode/$sequence/$now " +
						"imu=${snapshot.imuSequence}/${snapshot.imuAgeNanos} corr=${snapshot.correctionState}/${snapshot.correctionReady} " +
						"resolver=${snapshot.resolverRotationOwner} continuity=${snapshot.continuityState} visible=${snapshot.visibleRotationOwner}"
					assertTrue(failures.isEmpty(), "$context failures=$failures")
					assertEquals(identity, Triple(output.id, output.name, output.trackerPosition), context)
					assertEquals(visible.rotationOwner, snapshot.visibleRotationOwner, context)
					assertEquals(visible.positionSource, snapshot.visiblePositionSource, context)
					assertEquals(integration.rotationCorrection!!.state, snapshot.correctionState, context)
					assertEquals(integration.runtime.resolvedTrackingPoses(
						integration.runtime.pipeline.resolveAll(now))[TrackerPosition.HIP]?.rotationOwner,
						snapshot.resolverRotationOwner, context)
					if (events.size > eventCount) {
						assertEquals(snapshot.continuityState, events.last().snapshot.continuityState, context)
						assertEquals(visible.rotationOwner, events.last().snapshot.visibleRotationOwner, context)
					}
					val position = visible.position
					if (position != null) assertTrue(listOf(position.value.x, position.value.y,
						position.value.z).all { it.isFinite() }, context)
					val rotation = visible.rotation
					if (rotation != null) {
						val q = rotation.value
						assertTrue(listOf(q.w, q.x, q.y, q.z).all { it.isFinite() } && q.lenSq() > 1e-10f, context)
					}
					if (hpm.skeleton.getPauseTracking()) assertFalse(visible.rotationValid, context)
					if (snapshot.applicationDecision == ApplicationDecision.APPLIED)
						assertEquals("slime:${imu.name}", snapshot.resolverRotationOwner, context)
				}
				assertTrue(events.isNotEmpty(), "seed=$seed")
				assertTrue(events.size < 80, "seed=$seed diagnostic emitted every tick")
			}
		}
		assertEquals((0..11).toSet(), generatedEvents)
		assertTrue(ContinuityState.MAIN_DIRECT in continuityStates)
		assertTrue(ContinuityState.FALLBACK_ACTIVE in continuityStates)
	}
}
