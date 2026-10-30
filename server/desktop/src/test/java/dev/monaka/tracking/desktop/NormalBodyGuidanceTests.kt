package dev.monaka.tracking.desktop

import dev.monaka.protocol.v2.*
import dev.monaka.tracking.*
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import dev.slimevr.tracking.trackers.udp.IMUType
import dev.slimevr.tracking.trackers.udp.UDPDevice
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.math.abs
import kotlin.test.*

/** Normal configuration, production tick/IK/output; physical input object with real
 * setRotation acceptance receipts. No correction opt-in or approval fixture.
 */
class NormalBodyGuidanceTests {
	@ParameterizedTest
	@EnumSource(value = TrackerPosition::class, names = ["HIP", "UPPER_CHEST"])
	fun explicitPhysicalQAndSixDofLearnFallbackAndConvergeAtSolverAndVisibleOutput(target: TrackerPosition) {
		val p = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
		val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		val head = Tracker(null, 700, "head", trackerPosition = TrackerPosition.HEAD, hasPosition = true, hasRotation = true, isHmd = true,
			allowFiltering = false, allowReset = false, allowMounting = false).also { it.status = TrackerStatus.OK; it.position = Vector3(0f, 1.7f, 0f) }
		val device = UDPDevice(InetSocketAddress("127.0.0.1", 20001), InetAddress.getLoopbackAddress(), "guidance-test")
		val imu = Tracker(device, 701, "normal-q", trackerPosition = target, hasRotation = true, imuType = IMUType.UNKNOWN,
			allowFiltering = false, allowReset = true, allowMounting = true, trackRotDirection = false).also { it.status = TrackerStatus.OK; it.setRotation(Quaternion.IDENTITY) }
		val assignment = TrackerBodyAssignments().also {
			it.configure(target, TrackerReference.mtp(key), TrackerReference.slime(imu.name), OutputMode.HYBRID)
			it.configureSources(target, TrackerReference.slime(imu.name), TrackerReference.mtp(key), true)
		}
		val hpm = HumanPoseManager(listOf(head, imu)).also { it.setLegTweaksEnabled(false); it.skeleton.ikSolver.enabled = true; it.update() }
		var before: Runnable? = null
		var after: Runnable? = null
		var now = 1_000_000_000L
		val failures = mutableListOf<Exception>()
		val outputs = mutableListOf<Tracker>()
		val integration = MonakaServerIntegration.startIfEnabled(true,
			{ MonakaConfiguration(p.coordinate_space, assignment, port = 0, backgroundIkSharedSpace = p.coordinate_space) },
			{ listOf(head, imu) }, hpm.skeleton, { before = it }, { failures += it }, { now },
			configureDirectOutputs = { outputs += it }, registerAfterPose = { after = it })!!
		integration.use {
			assertNull(it.rotationCorrection, "No legacy HIP learner is running")
			val rawReference = imu.resetsHandler.getCorrectionReferenceRotationFrom(Quaternion.IDENTITY)
			val teacher = (Quaternion.rotationAroundYAxis(.3f) * rawReference).unit()
			var sequence = 0L
			fun tick(mode: String = "full", q: Quaternion? = null, orientation: Quaternion? = null, revision: Long = p.coordinate_space.revision) {
				now += 20_000_000
				imu.setRotation(q ?: Quaternion.IDENTITY)
				val o = orientation ?: teacher
				val pose = p.copy(sequence = ++sequence, modality = mode, sent_at_ns = p.timestamp_ns + 5_000_000,
					coordinate_space = p.coordinate_space.copy(revision = revision),
					position = if (mode == "full") listOf(.1, 1.0, .1) else null,
					orientation = if (mode == "none") null else listOf(o.x.toDouble(), o.y.toDouble(), o.z.toDouble(), o.w.toDouble()),
					validity = Validity(mode == "full", mode != "none"), confidence = Confidence(if (mode == "full") 1.0 else 0.0, if (mode == "none") 0.0 else 1.0),
					tracking_state = if (mode == "full") "tracked" else if (mode == "none") "lost" else "degraded")
				val encoded = MonakaCodec.encodeEnvelope(pose)
				assertIs<EncodeResult.Success>(encoded)
				assertTrue(it.runtime.inbox.receive(encoded.value, now))
				before!!.run(); hpm.update(); after!!.run()
				assertTrue(failures.isEmpty(), failures.joinToString())
			}
			repeat(60) { tick() }
			val result = assertNotNull(it.runtime.bodyRotationResults[target])
			assertTrue(result.state.ready)
			val receipt = assertNotNull(result.state.mainCursor?.sample?.receipt)
			assertEquals(now, receipt.receivedAtNanos)
			assertEquals(now - 5_000_000, receipt.sampleAtNanos)
			assertEquals(LearningDecision.ACCEPTED, result.learning)
			val learnedAt = result.state.lastLearnedAtNanos
			tick()
			assertTrue(it.runtime.bodyRotationResults.getValue(target).state.lastLearnedAtNanos!! > learnedAt!!)
			tick("rotation_only", Quaternion.rotationAroundYAxis(.2f))
			repeat(9) { tick("rotation_only", Quaternion.rotationAroundYAxis(.2f)) }
			val fallback = assertNotNull(it.runtime.bodyRotationResults.getValue(target).fallbackCandidate)
			val visible = assertNotNull(outputs.single().monakaOutputPose)
			assertTrue(abs(fallback.orientation.dot(visible.rotation!!.value)) > .999f)
			assertEquals("slime:${imu.name}", visible.rotationOwner)
			assertNull(it.runtime.pipeline.resolve(target, now).position, "Q is not measured position")
			fun selectedInput() = if (target == TrackerPosition.HIP) hpm.skeleton.hipTracker else hpm.skeleton.upperChestTracker
			assertNotNull(selectedInput())
			assertTrue(abs(fallback.orientation.dot(selectedInput()!!.getRotation())) > .999f)
			val held = selectedInput()!!.getRotation()
			val recovered = (Quaternion.rotationAroundYAxis(1f) * rawReference).unit()
			tick(orientation = recovered)
			assertTrue(abs(held.dot(selectedInput()!!.getRotation())) > .999f, "First recovered solver rotation starts from corrected Q")
			repeat(20) { tick(orientation = recovered) }
			assertTrue(abs(recovered.dot(selectedInput()!!.getRotation())) > .999f, "Solver converges to fresh Main")
			repeat(20) { tick(orientation = recovered) }
			assertTrue(abs(recovered.dot(outputs.single().monakaOutputPose!!.rotation!!.value)) > .999f, "Visible virtual output converges")
			tick(revision = p.coordinate_space.revision + 1)
			assertEquals(1, it.runtime.inbox.diagnostics()["SpaceDescriptionChanged"])
			assertEquals(assignment.snapshot(), it.runtime.assignments.snapshot())
			assertFalse(it.runtime.bodyRotationResults.getValue(target).state.ready, "Description change clears transient pairing")
			assertTrue(abs(teacher.dot(selectedInput()!!.getRotation())) > .999f, "No old recovery blend crosses the changed description")
			imu.status = TrackerStatus.TIMED_OUT
			tick("none")
			assertNull(it.runtime.bodyRotationResults.getValue(target).fallbackCandidate)
			assertNull(selectedInput(), "Unusable Q does not retain a solver input")
		}
	}
}
