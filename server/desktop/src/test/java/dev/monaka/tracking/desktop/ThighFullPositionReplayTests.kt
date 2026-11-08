package dev.monaka.tracking.desktop

import dev.monaka.protocol.v2.Confidence
import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.EncodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.protocol.v2.Validity
import dev.monaka.tracking.LogicalTracker
import dev.monaka.tracking.MonakaRuntime
import dev.monaka.tracking.MonakaSolverCommitResult
import dev.monaka.tracking.MonakaSolverComposition
import dev.monaka.tracking.OutputMode
import dev.monaka.tracking.TrackerBodyAssignments
import dev.monaka.tracking.TrackerReference
import dev.slimevr.desktop.platform.ProtobufBridge
import dev.slimevr.desktop.platform.ProtobufMessages.Position
import dev.slimevr.desktop.platform.ProtobufMessages.ProtobufMessage
import dev.slimevr.desktop.platform.ProtobufMessages.TrackerAdded
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.config.LegTweaksConfig
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerRole
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.EulerOrder
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A diagnostic of desired behavior, intentionally allowed to fail on the bad solver baseline. */
class ThighFullPositionReplayTests {

	@Test
	fun fixedBodyAlignedThighRotationStillAllowsReachableFiveCentimetrePositionMotion() = replay(Quaternion.IDENTITY, "body-aligned-control")

	@Test
	fun knownTwentyDegreeSensorMountKeepsAnatomicalKneeAndPositionMotionAfterFullReset() =
		replay(Quaternion.rotationAroundXAxis((20.0 * PI / 180.0).toFloat()), "known-20deg-mount")

	@Test
	fun simultaneousYawMountedThighsStartNeutralAtFirstFullResetAndLeanMountReset() = BilateralReplay(false).use { replay ->
		// Both FULL inputs exist before the very first Full Reset. The device
		// headings are +90/-60 degrees while the worn anatomical thighs are neutral.
		val first = replay.step(Quaternion.IDENTITY, Quaternion.IDENTITY, Vector3.NULL, 1, "first-full-reset")
		replay.assertAnatomical(first, true)
		val lean = Quaternion.rotationAroundXAxis(.52f)
		val mounting = replay.step(lean, lean, Vector3.NULL, 2, "valid-forward-lean-mount-reset")
		replay.assertAnatomical(mounting, true)
		val neutral = replay.step(Quaternion.IDENTITY, Quaternion.IDENTITY, Vector3.NULL, 0, "return-neutral")
		replay.assertAnatomical(neutral, true)
	}

	@Test
	fun yawMountedThighsFollowReachableArcsAndKeepMountAcrossQSixAndRepeatedReset() = BilateralReplay(false).use { replay ->
		replay.assertAnatomical(replay.step(Quaternion.IDENTITY, Quaternion.IDENTITY, Vector3.NULL, 1, "full"), true)
		val lean = Quaternion.rotationAroundXAxis(.52f)
		replay.assertAnatomical(replay.step(lean, lean, Vector3.NULL, 2, "mount"), true)
		// Rotate around the known hip joints, with fixed lengths and a fixed
		// sensor offset/heading on each thigh. This is actual reachable pose motion.
		val left = Quaternion.rotationAroundXAxis(.31f)
		val right = Quaternion.rotationAroundXAxis(.23f)
		val arc = replay.step(left, right, Vector3.NULL, 0, "reachable-arc")
		replay.assertAnatomical(arc, true)
		replay.selectFull(false)
		val qOnly = replay.step(left, right, Vector3.NULL, 0, "same-physical-Q")
		replay.assertAnatomical(qOnly, true)
		replay.selectFull(true)
		val full = replay.step(left, right, Vector3.NULL, 0, "same-physical-six")
		replay.assertAnatomical(full, true)
		replay.assertNoJump(arc, full, "Q/six body calibration and raw position anchor")
		// A new Full Reset changes interpretation while the raw physical point
		// remains fixed. Returning from Mount Reset must not orbit that point.
		val before = replay.step(Quaternion.IDENTITY, Quaternion.IDENTITY, Vector3.NULL, 0, "before-repeat-full")
		val reset = replay.step(Quaternion.IDENTITY, Quaternion.IDENTITY, Vector3.NULL, 1, "repeat-full-same-physical-point")
		replay.assertAnatomical(reset, true)
		replay.assertNoJump(before, reset, "Full Reset keeps calibrated raw-position orbit")
		replay.assertAnatomical(replay.step(lean, lean, Vector3.NULL, 2, "repeat-mount-in-lean"), true)
		val after = replay.step(Quaternion.IDENTITY, Quaternion.IDENTITY, Vector3.NULL, 0, "after-repeat-mount")
		replay.assertAnatomical(after, true)
		replay.assertNoJump(before, after, "Mount Reset keeps calibrated raw-position orbit")
	}

	@Test
	fun ordinaryLegTweaksKeepBilateralMountedResetAndSmallReachableMotionInVirtualOutput() = BilateralReplay(true).use { replay ->
		val first = replay.step(Quaternion.IDENTITY, Quaternion.IDENTITY, Vector3.NULL, 1, "postprocessing-first-full")
		replay.assertAnatomical(first, true)
		val lean = Quaternion.rotationAroundXAxis(.52f)
		replay.assertAnatomical(replay.step(lean, lean, Vector3.NULL, 2, "postprocessing-lean-mount"), true)
		for (angle in listOf(.04f, .08f, .12f, .16f)) {
			val pose = replay.step(Quaternion.rotationAroundXAxis(angle), Quaternion.rotationAroundXAxis(angle), Vector3.NULL, 0, "postprocessing-small-reachable-arc")
			replay.assertAnatomical(pose, true)
		}
		val neutral = replay.step(Quaternion.IDENTITY, Quaternion.IDENTITY, Vector3.NULL, 0, "postprocessing-neutral")
		replay.assertAnatomical(neutral, true)
		replay.assertNoJump(first, neutral, "ordinary postprocessing reset/motion return")
	}

	private data class BilateralFrame(
		val desired: Map<TrackerPosition, Vector3>,
		val expectedRotation: Map<TrackerPosition, Quaternion>,
		val effective: Map<TrackerPosition, Vector3>,
		val joints: Map<TrackerPosition, Vector3>,
		val computed: Map<TrackerPosition, Vector3>,
		val rotations: Map<TrackerPosition, Quaternion>,
		val torso: Map<String, Quaternion>,
	)

	private class BilateralReplay(private val postprocessing: Boolean) : AutoCloseable {
		private val seed = assertIs<MtpPose>(assertIs<DecodeResult.Success>(MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes())).value)
		private val targets = listOf(TrackerPosition.LEFT_UPPER_LEG, TrackerPosition.RIGHT_UPPER_LEG)
		private val packets = targets.associateWith { seed.copy(tracker_id = "synthetic-${it.name}", input = seed.input.copy(device_id = "physical-${it.name}")) }
		private val keys = packets.mapValues { (_, p) -> LogicalTracker(p.source_id, p.tracker_id, p.publisher_id) }
		private val assignments = TrackerBodyAssignments().also { a -> targets.forEach { a.configureSources(it, null, TrackerReference.mtp(keys.getValue(it)), true) } }
		private val head = Tracker(null, 450, "synthetic-bilateral-head", trackerPosition = TrackerPosition.HEAD, hasPosition = true, hasRotation = true, isHmd = true, isComputed = true, trackRotDirection = false).also {
			it.status = TrackerStatus.OK; it.position = Vector3(0f, 1.7f, 0f); it.setRotation(Quaternion.IDENTITY)
		}
		private val manager = HumanPoseManager(listOf(head))
		private val skeleton = manager.skeleton
		init {
			manager.setLegTweaksEnabled(postprocessing)
			if (postprocessing) skeleton.legTweaks.setConfig(LegTweaksConfig())
			assertTrue(skeleton.ikSolver.enabled)
			assertTrue(manager.getToggle(dev.slimevr.tracking.processor.config.SkeletonConfigToggles.ENFORCE_CONSTRAINTS))
			manager.update()
		}
		private val bones = mapOf(targets[0] to skeleton.leftUpperLegBone, targets[1] to skeleton.rightUpperLegBone)
		private val knees = mapOf(targets[0] to assertNotNull(skeleton.computedLeftKneeTracker), targets[1] to assertNotNull(skeleton.computedRightKneeTracker))
		private val hipJoints = bones.mapValues { it.value.getPosition() }
		private val lengths = skeleton.allHumanBones.associate { it.boneType to it.length }
		private val mounts = mapOf(targets[0] to Quaternion.rotationAroundYAxis((PI / 2).toFloat()), targets[1] to Quaternion.rotationAroundYAxis((-PI / 3).toFloat()))
		private val offsets = mapOf(targets[0] to Vector3(0f, .14f, -.025f), targets[1] to Vector3(0f, .11f, .03f))
		private var now = 1_000_000_000L
		private var sequence = 0L
		private var full = true
		private val runtime = MonakaRuntime({ listOf(head) }, seed.coordinate_space, assignments, clock = { now })
		private val composition = MonakaSolverComposition(skeleton, null, { listOf(head) }, manager::currentHipBodyModelSnapshot)
		private val output = KneeOutputCapture().also { out -> knees.values.forEach(out::addSharedTracker); out.flush() }

		fun selectFull(value: Boolean) {
			full = value
			for (target in targets) assignments.configureSources(target, if (value) null else TrackerReference.mtp(keys.getValue(target)), if (value) TrackerReference.mtp(keys.getValue(target)) else null, true)
		}

		fun step(left: Quaternion, right: Quaternion, translation: Vector3, reset: Int, stage: String): BilateralFrame {
			if (postprocessing) Thread.sleep(10) // Actual LegTweaks buffer uses a wall clock.
			now += 10_000_000; sequence++
			val expectedRotations = mapOf(targets[0] to left, targets[1] to right)
			val desired = targets.associateWith { hipJoints.getValue(it) + translation + expectedRotations.getValue(it).sandwich(Vector3(0f, -bones.getValue(it).length, 0f)) }
			val rawPositions = targets.associateWith { desired.getValue(it) + expectedRotations.getValue(it).sandwich(offsets.getValue(it)) }
			val rawRotations = targets.associateWith { (expectedRotations.getValue(it) * mounts.getValue(it)).unit() }
			for (target in targets) {
				val p = rawPositions.getValue(target); val q = rawRotations.getValue(target); val packet = packets.getValue(target)
				val next = packet.copy(sequence = sequence, timestamp_ns = seed.timestamp_ns + sequence * 10_000_000, sent_at_ns = seed.timestamp_ns + sequence * 10_000_000,
					position = listOf(p.x.toDouble(), p.y.toDouble(), p.z.toDouble()), orientation = listOf(q.x.toDouble(), q.y.toDouble(), q.z.toDouble(), q.w.toDouble()),
					modality = "full", validity = Validity(true, true), confidence = Confidence(1.0, 1.0), tracking_state = "tracked", input = packet.input.copy(sequence = sequence))
				assertTrue(runtime.inbox.receive(assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(next)).value, now))
			}
			val tick = runtime.tickSnapshot()
			for (target in targets) {
				val resolved = tick.constraints.getValue(target)
				assertEquals(keys.getValue(target).observationId, assertNotNull(resolved.rotation).sourceId)
				if (full) { assertEquals(keys.getValue(target).observationId, assertNotNull(resolved.position).sourceId); assertEquals(rawPositions.getValue(target), resolved.position!!.value) } else assertNull(resolved.position)
			}
			assertIs<MonakaSolverCommitResult.GenericCommitted>(composition.commitNormal(tick, tick.constraints, runtime, null))
			if (reset == 1) manager.resetTrackersFull("synthetic bilateral Full Reset")
			if (reset == 2) manager.resetTrackersMounting("synthetic bilateral lean Mount Reset", targets.map { it.bodyPart })
			val jointBefore = bones.mapValues { it.value.getTailPosition() }
			manager.update()
			assertEquals(lengths, skeleton.allHumanBones.associate { it.boneType to it.length })
			val effective = skeleton.ikSolver.diagnosticPositionTargets().filter { it.target in targets }.associate { it.target!! to it.position }
			if (!full) assertTrue(effective.isEmpty(), "Q-only must not retain positional IK authority")
			val joints = bones.mapValues { it.value.getTailPosition() }
			val computed = knees.mapValues { it.value.position }
			val rotations = bones.mapValues { it.value.getGlobalRotation() }
			val torso = mapOf("upperChest" to skeleton.upperChestBone.getGlobalRotation(), "chest" to skeleton.chestBone.getGlobalRotation(), "hip" to skeleton.hipBone.getGlobalRotation())
			output.frame(knees.getValue(targets[0]).id)
			for (target in targets) {
				val knee = knees.getValue(target)
				assertEquals(knee.position, output.position(knee.id)); assertEquals(knee.getRotation(), output.rotation(knee.id))
				println("BILATERAL_THIGH stage=$stage target=$target full=$full reset=$reset postprocessing=$postprocessing rawP=${rawPositions[target]} rawQ=${rawRotations[target]} intendedQ=${expectedRotations[target]} actualBodyQ=${rotations[target]} desiredJoint=${desired[target]} effectiveIK=${effective[target]} boneJointBeforeFrame=${jointBefore[target]} boneJointAfterIK=${joints[target]} computedAfterTweaks=${computed[target]} wireP=${output.position(knee.id)} wireQ=${output.rotation(knee.id)} torso=$torso tweakState=${skeleton.legTweaksState.contentToString()}")
			}
			if (postprocessing) assertTrue(skeleton.legTweaks.enabled && skeleton.legTweaks.floorClipEnabled && skeleton.legTweaks.skatingCorrectionEnabled)
			return BilateralFrame(desired, expectedRotations, effective, joints, computed, rotations, torso)
		}

		fun assertAnatomical(frame: BilateralFrame, neutralTorso: Boolean) {
			for (target in targets) {
				val q = frame.rotations.getValue(target); val expected = frame.expectedRotation.getValue(target)
				assertTrue(q.w.isFinite() && q.x.isFinite() && q.y.isFinite() && q.z.isFinite() && abs(q.lenSq() - 1f) < .001f)
				assertTrue((q * expected.inv()).angleR() < .001f, "$target body heading/bending plane differs from physical geometry: expected=$expected actual=$q")
				frame.effective[target]?.let { assertTrue((it - frame.desired.getValue(target)).len() < .001f, "$target raw mount anchor changed the anatomical IK target") }
				assertTrue((frame.joints.getValue(target) - frame.desired.getValue(target)).len() < .01f, "$target bone joint did not follow physical geometry")
				assertTrue((frame.computed.getValue(target) - frame.desired.getValue(target)).len() < .01f, "$target computed output differs after ordinary postprocessing")
			}
			if (neutralTorso) for ((name, q) in frame.torso) assertTrue(q.angleR() < .001f, "Neutral $name acquired a spurious twist: $q")
		}

		fun assertNoJump(before: BilateralFrame, after: BilateralFrame, label: String) {
			for (target in targets) {
				before.effective[target]?.let { prior -> after.effective[target]?.let { assertTrue((it - prior).len() < .001f, "$label $target effective target jumped") } }
				assertTrue((after.computed.getValue(target) - before.computed.getValue(target)).len() < .01f, "$label $target output jumped")
			}
		}

		override fun close() { composition.close(); runtime.close() }
	}

	private fun replay(sensorFromBoneRotation: Quaternion, label: String) {
		val source = assertIs<MtpPose>(
			assertIs<DecodeResult.Success>(MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes())).value,
		)
		val target = TrackerPosition.LEFT_UPPER_LEG
		val key = LogicalTracker(source.source_id, source.tracker_id, source.publisher_id)
		val assignments = TrackerBodyAssignments().also { it.configureSources(target, null, TrackerReference.mtp(key), true) }
		assertEquals(OutputMode.IK, assignments.snapshot().targets.getValue(target).outputMode)
		val head = Tracker(
			null, 400, "synthetic-thigh-replay-head", trackerPosition = TrackerPosition.HEAD,
			hasPosition = true, hasRotation = true, isHmd = true, isComputed = true,
			allowReset = false, allowMounting = false, trackRotDirection = false,
		).also { it.status = TrackerStatus.OK; it.position = Vector3(0f, 1.7f, 0f); it.setRotation(Quaternion.IDENTITY) }
		val manager = HumanPoseManager(listOf(head)).also { it.setLegTweaksEnabled(false) }
		val skeleton = manager.skeleton
		assertTrue(skeleton.ikSolver.enabled)
		assertTrue(manager.getToggle(dev.slimevr.tracking.processor.config.SkeletonConfigToggles.ENFORCE_CONSTRAINTS))
		manager.update() // Default IK is active; only the ordinary HMD anchor exists yet.
		val knee = assertNotNull(skeleton.computedLeftKneeTracker)
		val intendedBodyRotation = skeleton.leftUpperLegBone.getGlobalRotation()
		val intendedJoint = skeleton.leftUpperLegBone.getTailPosition()
		assertTrue((knee.position - intendedJoint).len() < .00001f, "Fixture knee output must identify the upper-leg tail, not the hip/thigh origin")
		val lengths = skeleton.allHumanBones.associate { it.boneType to it.length }
		// The physical sensor sits 14 cm up the thigh and 2.5 cm forward of the
		// anatomical knee. Its local axes can also have the fixed known mount.
		val sensorOffsetFromJoint = Vector3(0f, .14f, -.025f)
		val sensorOrigin = intendedJoint + intendedBodyRotation.sandwich(sensorOffsetFromJoint)
		val sensorRotation = (intendedBodyRotation * sensorFromBoneRotation).unit()
		var now = 1_000_000_000L
		var sequence = 0L
		val failures = mutableListOf<String>()
		val output = KneeOutputCapture()
		output.addSharedTracker(knee)
		output.flush()
		MonakaRuntime({ listOf(head) }, source.coordinate_space, assignments, clock = { now }).use { runtime ->
			MonakaSolverComposition(skeleton, null, { listOf(head) }, manager::currentHipBodyModelSnapshot).use { composition ->
				data class Frame(val input: Vector3, val effective: Vector3, val knee: Vector3, val joint: Vector3, val wire: Vector3, val bodyRotation: Quaternion)
				fun frame(delta: Vector3, reset: Boolean): Frame {
					now += 10_000_000
					sequence++
					val input = sensorOrigin + delta
					val packet = source.copy(
						sequence = sequence, timestamp_ns = source.timestamp_ns + sequence * 10_000_000, sent_at_ns = source.timestamp_ns + sequence * 10_000_000,
						position = listOf(input.x.toDouble(), input.y.toDouble(), input.z.toDouble()),
						orientation = listOf(sensorRotation.x.toDouble(), sensorRotation.y.toDouble(), sensorRotation.z.toDouble(), sensorRotation.w.toDouble()),
						modality = "full", validity = Validity(true, true), confidence = Confidence(1.0, 1.0), tracking_state = "tracked",
						input = source.input.copy(sequence = sequence),
					)
					assertTrue(runtime.inbox.receive(assertIs<EncodeResult.Success>(MonakaCodec.encodeEnvelope(packet)).value, now))
					val tick = runtime.tickSnapshot()
					val resolved = tick.constraints.getValue(target)
					assertEquals(key.observationId, assertNotNull(resolved.position).sourceId)
					assertEquals(key.observationId, assertNotNull(resolved.rotation).sourceId)
					assertEquals(input, resolved.position!!.value)
					assertIs<MonakaSolverCommitResult.GenericCommitted>(composition.commitNormal(tick, tick.constraints, runtime, null))
					if (reset) manager.resetTrackersFull("synthetic offline thigh replay")
					manager.update()
					assertEquals(lengths, skeleton.allHumanBones.associate { it.boneType to it.length }, "IK must retain the configured body geometry")
					val proxy = assertNotNull(skeleton.leftUpperLegTracker)
					assertTrue(proxy.hasPosition && proxy.hasRotation)
					assertEquals(input, proxy.position)
					val actualTargets = skeleton.ikSolver.diagnosticPositionTargets().filter { it.trackerIdentity == proxy.name && it.target == target }
					val effective = actualTargets.firstOrNull()?.position ?: error("No actual upper-leg IK target")
					val wire = output.frame(knee.id)
					assertEquals(knee.position, wire, "Production computed-knee protobuf bytes must match the actual computed output")
					val wireRotation = output.rotation(knee.id)
					assertEquals(knee.getRotation(), wireRotation, "Production computed-knee quaternion must reach the serialized output unchanged")
					val bodyRotation = skeleton.leftUpperLegBone.getGlobalRotation()
					val joint = skeleton.leftUpperLegBone.getTailPosition()
					val desired = intendedJoint + delta
					val sample = Frame(input, effective, knee.position, joint, wire, bodyRotation)
					val torso = listOf(skeleton.upperChestBone, skeleton.chestBone, skeleton.hipBone).joinToString { "${it.boneType}:${it.getGlobalRotation().toEulerAngles(EulerOrder.YZX)}" }
					println(
						"THIGH_REPLAY label=$label seq=$sequence reset=$reset inputDelta=$delta rawP=$input rawQ=$sensorRotation " +
							"intendedBodyQ=$intendedBodyRotation actualBodyQ=$bodyRotation bodyAngleError=${angle(intendedBodyRotation, bodyRotation)} " +
							"intendedKnee=$desired effectiveIK=$effective allIKTargets=$actualTargets computedKnee=${knee.position} computedKneeQ=${knee.getRotation()} boneJoint=$joint wireKnee=$wire wireQ=$wireRotation " +
							"effectiveToDesired=${(effective - desired).len()} jointToEffective=${(joint - effective).len()} kneeToDesired=${(knee.position - desired).len()} " +
							"upperLegLength=${skeleton.leftUpperLegBone.length} lowerLegLength=${skeleton.leftLowerLegBone.length} torsoAngles=$torso",
					)
					if (!validRotation(bodyRotation) || !validRotation(wireRotation)) failures += "$label frame $sequence produced an invalid body/output quaternion"
					return sample
				}
				val baseline = frame(Vector3.NULL, reset = true)
				println("THIGH_CALIBRATION label=$label desiredJoint=$intendedJoint resetEffective=${baseline.effective} physicalOffset=$sensorOffsetFromJoint calibration=${skeleton.ikSolver.calibrationSnapshot()}")
				if ((baseline.effective - intendedJoint).len() > .001f) failures += "$label Full Reset targets a non-anatomical baseline: ${(baseline.effective - intendedJoint).len()} m"
				if (angle(intendedBodyRotation, baseline.bodyRotation) > .001f) failures += "$label known mount did not preserve the intended anatomical thigh rotation: ${angle(intendedBodyRotation, baseline.bodyRotation)} rad"
				for ((name, delta) in listOf("forward" to Vector3(0f, 0f, -.05f), "lateral" to Vector3(.05f, 0f, 0f))) {
					var moved = baseline
					repeat(5) { moved = frame(delta, false) }
					val inputDelta = moved.input - baseline.input
					val effectiveDelta = moved.effective - baseline.effective
					val kneeDelta = moved.knee - baseline.knee
					val wireDelta = moved.wire - baseline.wire
					println("THIGH_DELTA label=$label motion=$name rawDelta=$inputDelta effectiveDelta=$effectiveDelta kneeDelta=$kneeDelta wireDelta=$wireDelta targetDeltaResidual=${(effectiveDelta - inputDelta).len()} kneeDeltaResidual=${(kneeDelta - inputDelta).len()} bodyAngleDelta=${angle(baseline.bodyRotation, moved.bodyRotation)}")
					if ((effectiveDelta - inputDelta).len() > .001f) failures += "$label $name position delta changed before the actual IK target"
					// A reachable 5 cm fixture motion should not disappear into the solver.
					// This is a software reproduction bound, not a physical accuracy claim.
					if (!(kneeDelta.x.isFinite() && kneeDelta.y.isFinite() && kneeDelta.z.isFinite()) || (kneeDelta - inputDelta).len() > .01f) failures += "$label $name computed knee failed the 5 cm anatomical target motion: input=$inputDelta output=$kneeDelta"
				}
			}
		}
		println("THIGH_REPLAY_RESULT label=$label failures=$failures; synthetic codec/runtime/IK/captured protobuf only; SDK/HIL NOT_RUN")
		assertTrue(failures.isEmpty(), failures.joinToString("\n"))
	}

	private fun angle(a: Quaternion, b: Quaternion): Float {
		val delta = a * b.inv()
		return delta.angleR().let { abs(it) }
	}

	private fun validRotation(q: Quaternion) = q.w.isFinite() && q.x.isFinite() && q.y.isFinite() && q.z.isFinite() && abs(q.lenSq() - 1f) < .001f

	private class KneeOutputCapture : ProtobufBridge("synthetic-thigh-replay-capture") {
		private val positions = mutableMapOf<Int, Position>()
		override fun signalSend() = Unit
		override fun sendMessageReal(message: ProtobufMessage?): Boolean {
			val decoded = ProtobufMessage.parseFrom(requireNotNull(message).toByteArray())
			if (decoded.hasPosition()) positions[decoded.position.trackerId] = decoded.position
			return true
		}
		fun flush() = updateMessageQueue()
		fun rotation(id: Int) = positions.getValue(id).let { Quaternion(it.qw, it.qx, it.qy, it.qz) }
		fun position(id: Int) = positions.getValue(id).let { Vector3(it.x, it.y, it.z) }
		fun frame(id: Int): Vector3 {
			positions.clear()
			messageReceived(ProtobufMessage.getDefaultInstance())
			dataRead()
			dataWrite()
			flush()
			val position = positions.getValue(id)
			assertTrue(position.hasX())
			return Vector3(position.x, position.y, position.z)
		}
		override fun createNewTracker(trackerAdded: TrackerAdded): Tracker = error("No external process or driver input")
		override fun startBridge() = Unit
		override fun stopBridge() = Unit
		override fun isConnected() = false
		override fun getShareSetting(role: TrackerRole) = false
		override fun changeShareSettings(role: TrackerRole?, share: Boolean) = Unit
		override fun updateShareSettingsAutomatically() = false
		override fun getAutomaticSharedTrackers() = false
		override fun setAutomaticSharedTrackers(value: Boolean) = Unit
		override fun getBridgeConfigKey() = "synthetic-thigh-replay-capture"
	}
}
