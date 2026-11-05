package dev.monaka.tracking

import dev.monaka.protocol.v2.Confidence
import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.protocol.v2.Validity
import dev.slimevr.config.ResetsConfig
import dev.slimevr.tracking.processor.BoneType
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Public codec/runtime/composition and actual FK, compared with ordinary reset-aware input. */
class MvipQOnlyResetTests {

	private fun assertRotation(expected: Quaternion, actual: Quaternion, label: String = "rotation") {
		val dot = expected.w * actual.w + expected.x * actual.x + expected.y * actual.y + expected.z * actual.z
		assertTrue(abs(dot) > .99999f, "$label expected=$expected actual=$actual dot=$dot")
	}

	private inner class Fixture(val target: TrackerPosition = TrackerPosition.CHEST, val settings: ResetsConfig = ResetsConfig()) : AutoCloseable {
		val source = assertIs<MtpPose>(assertIs<DecodeResult.Success>(MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes())).value)
		val key = LogicalTracker(source.source_id, source.tracker_id, source.publisher_id)
		var packetSource = source
		val assignments = TrackerBodyAssignments().also { it.configureSources(target, TrackerReference.mtp(key), null, true) }
		val headQ = Quaternion.rotationAroundYAxis(.61f)
		val rawQ = (Quaternion.rotationAroundYAxis(1.13f) * Quaternion(.9689124f, .247404f, 0f, 0f)).unit()
		private fun head(id: Int) = Tracker(null, id, "reset-head-$id", trackerPosition = TrackerPosition.HEAD, hasPosition = true, hasRotation = true, isHmd = true, isComputed = true, trackRotDirection = false).also {
			it.position = Vector3(0f, 1.7f, 0f)
			it.setRotation(headQ)
			it.status = TrackerStatus.OK
		}
		val inputHead = head(100)
		val normal = Tracker(null, 102, "ordinary-reset-input", trackerPosition = target, hasPosition = false, hasRotation = true, allowReset = true, allowMounting = true, trackRotDirection = false).also {
			it.resetsHandler.mountingOrientation = Quaternion.IDENTITY
			it.status = TrackerStatus.OK
		}
		val manager = HumanPoseManager(listOf(inputHead)).also { it.setLegTweaksEnabled(false); it.skeleton.ikSolver.enabled = true }
		val baselineHead = head(101)
		val baseline = HumanPoseManager(listOf(baselineHead, normal)).also { it.setLegTweaksEnabled(false); it.skeleton.ikSolver.enabled = true }
		var now = 1_000_000_000L
		var sequence = 0L
		val runtime = MonakaRuntime({ listOf(inputHead) }, source.coordinate_space, assignments, clock = { now })
		val writeback = ConstraintIkWriteback(manager.skeleton) { settings }
		val composition = MonakaSolverComposition(writeback, ProductionPositionCorrectionGate.NOT_CONFIGURED)
		var lastTick: MonakaResolvedTickSnapshot? = null
		private var previousNormalNanos: Long? = null
		val proxy get() = when (target) {
			TrackerPosition.CHEST -> manager.skeleton.chestTracker
			TrackerPosition.LEFT_UPPER_LEG -> manager.skeleton.leftUpperLegTracker
			TrackerPosition.LEFT_UPPER_ARM -> manager.skeleton.leftUpperArmTracker
			else -> error("Unsupported test body")
		}
		val boneType get() = when (target) {
			TrackerPosition.CHEST -> BoneType.CHEST
			TrackerPosition.LEFT_UPPER_LEG -> BoneType.LEFT_UPPER_LEG
			TrackerPosition.LEFT_UPPER_ARM -> BoneType.LEFT_UPPER_ARM
			else -> error("Unsupported test body")
		}

		fun step(q: Quaternion, p: List<Double> = listOf(1.0, 2.0, 3.0), mode: String = "full"): List<HumanSkeleton.ConsumedRotationInput> {
			sequence++
			val valid = mode != "none"
			val pose = packetSource.copy(sequence = sequence, timestamp_ns = source.timestamp_ns + sequence * 10_000_000, sent_at_ns = source.sent_at_ns + sequence * 10_000_000,
				position = p.takeIf { mode == "full" }, orientation = listOf(q.x.toDouble(), q.y.toDouble(), q.z.toDouble(), q.w.toDouble()).takeIf { valid },
				modality = mode, validity = Validity(mode == "full", valid), confidence = Confidence(if (mode == "full") 1.0 else 0.0, if (valid) 1.0 else 0.0), tracking_state = when (mode) { "full" -> "tracked"; "none" -> "lost"; else -> "degraded" }, input = source.input.copy(sequence = sequence))
			assertTrue(runtime.inbox.receive(assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(pose)).value, now))
			return tick(valid, q)
		}
		fun tick(valid: Boolean, q: Quaternion = rawQ): List<HumanSkeleton.ConsumedRotationInput> {
			val tick = runtime.tickSnapshot()
			lastTick = tick
			val resolved = tick.constraints[target]
			assertNull(resolved?.position)
			if (valid) assertRotation(q, assertNotNull(resolved?.rotation).value, "admitted Q unchanged") else assertNull(resolved?.rotation)
			assertIs<MonakaSolverCommitResult.GenericCommitted>(composition.commitGeneric(tick, tick.constraints))
			manager.skeleton.beginDiagnosticInputFrame(tick.tickSequence)
			manager.update()
			previousNormalNanos?.let { normal.yawResetSmoothing.tick(((now - it).coerceAtLeast(0).toDouble() / 1_000_000_000).toFloat()) }
			previousNormalNanos = maxOf(previousNormalNanos ?: now, now)
			normal.resetsHandler.readResetConfig(settings)
			normal.resetsHandler.saveMountingReset = false
			normal.setRotation(q)
			baseline.update()
			now += 10_000_000
			return manager.skeleton.finishDiagnosticInputFrame().filter { it.target == target }.also { reads ->
				assertEquals(valid && assignments.snapshot().targets[target]?.useAsIkConstraint == true, reads.isNotEmpty())
				reads.forEach { assertFalse(it.hasPosition); assertTrue(it.hasRotation); assertNull(it.px); assertNull(it.py); assertNull(it.pz) }
			}
		}
		fun consumed(reads: List<HumanSkeleton.ConsumedRotationInput>) = reads.first().let { Quaternion(it.qw!!, it.qx!!, it.qy!!, it.qz!!) }
		fun assertStep(q: Quaternion, label: String) {
			val consumed = consumed(step(q))
			assertRotation(normal.getRotation(), consumed, label)
		}
		fun fullReset() { manager.resetTrackersFull("public Q-only test"); baseline.resetTrackersFull("ordinary baseline") }
		fun assertOutputs() {
			assertRotation(baseline.skeleton.getBone(boneType).getGlobalRotation(), manager.skeleton.getBone(boneType).getGlobalRotation(), "bone")
			baseline.computedTrackers.zip(manager.computedTrackers).forEach { (ordinary, actual) -> assertRotation(ordinary.getRotation(), actual.getRotation(), "computed ${ordinary.trackerPosition}") }
		}
		override fun close() { composition.close(); runtime.close() }
	}

	@Test
	fun publicQOnlyFullResetMatchesOrdinaryTrackerAndHeadYaw() = Fixture().use { f ->
		assertRotation(f.rawQ, f.consumed(f.step(f.rawQ)), "pre-reset preserves normalized input")
		f.fullReset()
		val after = f.consumed(f.step(f.rawQ))
		val expected = f.headQ.project(Vector3.POS_Y).unit()
		assertRotation(expected, f.normal.getRotation(), "ordinary Full Reset baseline")
		assertRotation(expected, after, "Q-only Full Reset consumed boundary")
		f.assertOutputs()
		val fixed = f.manager.computedTrackers.map { it.position to it.getRotation() }
		assertRotation(after, f.consumed(f.step(f.rawQ, listOf(100.0, -40.0, 75.0))), "P-only change after reset")
		assertEquals(fixed, f.manager.computedTrackers.map { it.position to it.getRotation() })
		val motion = (f.rawQ * Quaternion.rotationAroundYAxis(.37f) * Quaternion(.9800666f, 0f, 0f, .1986693f)).unit()
		val moved = f.consumed(f.step(motion))
		assertRotation(f.normal.getRotation(), moved, "noncommuting post-reset motion")
		assertTrue((moved * after.inv()).angleR() > .1f)
		f.assertOutputs()
		f.fullReset()
		assertRotation(expected, f.consumed(f.step(motion)), "second reset uses current Q")
		f.assertOutputs()
	}

	@ParameterizedTest
	@EnumSource(value = TrackerPosition::class, names = ["CHEST", "LEFT_UPPER_LEG", "LEFT_UPPER_ARM"])
	fun yawAndMountReuseOrdinarySemanticsAndPrivateSmoothingAdvances(target: TrackerPosition) = Fixture(target, ResetsConfig().also { it.yawResetSmoothTime = .2f; it.saveMountingReset = true }).use { f ->
		f.step(f.rawQ)
		f.fullReset()
		f.step(f.rawQ)
		val motion = (Quaternion.rotationAroundYAxis(.8f) * f.rawQ).unit()
		f.step(motion)
		f.manager.resetTrackersYaw("Q-only yaw"); f.baseline.resetTrackersYaw("ordinary yaw")
		assertTrue(f.proxy!!.yawResetSmoothing.remainingTime > 0f)
		f.assertStep(motion, "smoothed yaw")
		f.now += 300_000_000
		f.assertStep(motion, "completed yaw")
		assertEquals(0f, f.proxy!!.yawResetSmoothing.remainingTime)
		assertFalse(f.proxy!!.resetsHandler.saveMountingReset, "private settings never enable tracker-config persistence")
		val mountingPose = (motion * Quaternion(.9f, .25f, .1f, .2f)).unit()
		f.assertStep(mountingPose, "nondegenerate tilted mounting pose")
		val previousMount = f.proxy!!.resetsHandler.mountRotFix
		f.manager.resetTrackersMounting("Q-only mount"); f.baseline.resetTrackersMounting("ordinary mount")
		assertRotation(f.normal.resetsHandler.mountRotFix, f.proxy!!.resetsHandler.mountRotFix, "mount transform")
		assertTrue((f.proxy!!.resetsHandler.mountRotFix * previousMount.inv()).angleR() > .05f, "Mount must change the nondegenerate fixture")
		f.assertStep(mountingPose, "mount")
		f.assertOutputs()
		val postMount = (mountingPose * Quaternion.rotationAroundYAxis(.31f) * Quaternion(.9800666f, .1986693f, 0f, 0f)).unit()
		f.assertStep(postMount, "noncommuting post-mount motion")
		f.assertOutputs()
		f.fullReset()
		f.assertStep(postMount, "full after mount")
		f.assertOutputs()
	}

	@Test
	fun transientLossStaleGenerationAndReconnectRetainOnlyTheSameBinding() = Fixture(TrackerPosition.LEFT_UPPER_LEG).use { f ->
		f.step(f.rawQ); f.fullReset(); val reset = f.consumed(f.step(f.rawQ))
		val proxy = f.proxy!!
		val handler = proxy.resetsHandler
		f.step(f.rawQ, mode = "none")
		assertNull(f.proxy); assertNull(f.writeback.masks()[f.target])
		f.manager.resetTrackersFull("absent input cannot recalibrate stale Q")
		val generation = handler.correctionCalibrationEpoch()
		f.manager.resetTrackersFull("still absent")
		assertEquals(generation, handler.correctionCalibrationEpoch())
		assertRotation(reset, f.consumed(f.step(f.rawQ, mode = "rotation_only")), "loss recovery")
		assertSame(proxy, f.proxy); assertSame(handler, f.proxy!!.resetsHandler)
		val pinned = f.lastTick!!
		f.writeback.apply(pinned.constraints, pinned.assignment.copy(generation = pinned.assignment.generation + 1))
		assertSame(proxy, f.proxy)
		f.now += 600_000_000
		f.tick(false)
		assertNull(f.proxy)
		f.packetSource = f.source.copy(session_id = "33333333-3333-3333-3333-333333333333")
		assertRotation(reset, f.consumed(f.step(f.rawQ, mode = "rotation_only")), "same identity reconnect")
		assertEquals(1, f.runtime.inbox.diagnostics()["SessionChanged"])
		assertSame(proxy, f.proxy)
		// Explicitly replacing Q identity must never inherit the old consumer reset.
		f.packetSource = f.packetSource.copy(tracker_id = "other-Q")
		val other = LogicalTracker(f.packetSource.source_id, f.packetSource.tracker_id, f.packetSource.publisher_id)
		f.assignments.configureSources(f.target, TrackerReference.mtp(other), null, true)
		assertRotation(f.rawQ, f.consumed(f.step(f.rawQ)), "new Q binding is unreset")
		assertNotSame(proxy, f.proxy)
		val otherProxy = f.proxy!!
		f.assignments.configureSources(f.target, null, null, true)
		f.tick(false)
		f.assignments.configureSources(f.target, TrackerReference.mtp(other), null, true)
		f.step(f.rawQ)
		assertNotSame(otherProxy, f.proxy)
		val rebound = f.proxy!!
		f.assignments.replaceTargets(f.assignments.snapshot().targets.mapValues { it.value.copy(outputMode = OutputMode.DIRECT) })
		assertRotation(f.rawQ, f.consumed(f.step(f.rawQ)), "changed output mode is a fresh binding")
		assertNotSame(rebound, f.proxy)
		val directProxy = f.proxy!!
		f.assignments.replaceTargets(f.assignments.snapshot().targets.mapValues { it.value.copy(outputMode = OutputMode.IK, useAsIkConstraint = false) })
		assertTrue(f.step(f.rawQ).isEmpty()); assertNull(f.proxy)
		f.assignments.replaceTargets(f.assignments.snapshot().targets.mapValues { it.value.copy(useAsIkConstraint = true) })
		f.step(f.rawQ)
		assertNotSame(directProxy, f.proxy)
		assertFalse(FeedbackExclusion.accepts(f.proxy!!), "private input never becomes raw feedback")
	}

	@Test
	fun alreadyAdjustedSlimeImplicitAndSixDofFallbackRemainNoResetInputs() = Fixture().use { f ->
		val q = TrackerReference.mtp(f.key)
		val six = TrackerReference.mtp(f.key.copy(trackerId = "six-dof"))
		val slime = TrackerReference.slime("ordinary-adjusted-Q")
		val cases = listOf(
			MainTrackerAssignment(q),
			MainTrackerAssignment(slime, sourceSelection = QSixDofSourceSelection(slime)),
			MainTrackerAssignment(six, q, sourceSelection = QSixDofSourceSelection(q, six)),
		)
		for (relation in cases) {
			f.assignments.replaceTargets(mapOf(f.target to relation))
			// Rotation-only is also the shape of a FULL Main's rotation fallback: shape is not policy.
			val rotation = EffectiveConstraint(f.target, rotation = ResolvedComponent(f.rawQ, relation.rotationFallbackTracker?.observationId ?: relation.mainTracker.observationId, ObservationQuality.TRACKED, f.now))
			f.writeback.apply(mapOf(f.target to rotation), f.assignments.snapshot())
			assertFalse(f.proxy!!.allowReset); assertFalse(f.proxy!!.allowMounting)
			f.manager.resetTrackersFull("preserve existing non-Q-only interpretation")
			f.manager.skeleton.beginDiagnosticInputFrame(0)
			f.manager.update()
			val reads = f.manager.skeleton.finishDiagnosticInputFrame().filter { it.target == f.target }
			assertRotation(f.rawQ, f.consumed(reads), "non-Q-only no extra reset")
		}
	}

	@Test
	fun duplicateAndRollbackDoNotAdvancePrivateYawInterpolation() = Fixture(settings = ResetsConfig().also { it.yawResetSmoothTime = .2f }).use { f ->
		f.step(f.rawQ); f.fullReset(); f.step(f.rawQ)
		val motion = Quaternion.rotationAroundYAxis(.9f) * f.rawQ
		f.step(motion)
		f.manager.resetTrackersYaw("private interpolation reservation")
		val proxy = f.proxy!!
		val pinned = f.lastTick!!
		val remaining = proxy.yawResetSmoothing.remainingTime
		fun rejected(sequence: Long, now: Long, reason: MonakaSolverCommitRejection) {
			val tick = MonakaResolvedTickSnapshot(sequence, now, now, false, pinned.assignment, pinned.constraints, pinned.trackerReceiptCutoffSystemNanos)
			assertEquals(reason, assertIs<MonakaSolverCommitResult.Rejected>(f.composition.commitGeneric(tick, tick.constraints)).reason)
			assertEquals(remaining, proxy.yawResetSmoothing.remainingTime)
		}
		rejected(pinned.tickSequence, f.now + 1_000_000_000, MonakaSolverCommitRejection.DUPLICATE_TICK)
		rejected(pinned.tickSequence - 1, f.now + 2_000_000_000, MonakaSolverCommitRejection.TICK_SEQUENCE_ROLLBACK)
		f.step(motion)
		assertTrue(proxy.yawResetSmoothing.remainingTime < remaining)
	}
}
