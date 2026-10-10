package dev.monaka.tracking

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.tracking.hil.*
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CorePocHilTests {
	private class Fixture(correction: Boolean = true, hil: Boolean = true) : AutoCloseable {
		val config = CorePocHilConfig.synthetic().copy(correctionEnabled = correction)
		val session = CorePocHilSession(config, hil)
		var sequence = 0L
		var now = 1_000_000_000L
		var lastInput: CorePocInputFrame? = null
		fun tick(valid: Boolean? = null, drift: Float = 0f, offset: Float = 0f, angle: Float = 0f): Map<String, Any?> {
			valid?.let(session.gate::setAvailable)
			now += 20_000_000; sequence++
			fun p(epoch: String) = ObservationSampleProvenance(sequence, now, epoch, "cal:1", 0, config.space, "world:1")
			val q = Quaternion.rotationAroundYAxis(drift)
			val input = CorePocInputFrame(now, sequence,
				PoseObservation(config.mainSourceId, TrackerPosition.HIP, now, position = Vector3(.1f + offset, 1.1f, 0f),
					rotation = Quaternion.rotationAroundYAxis(angle), provenance = p("main:1"), correctionRotation = Quaternion.rotationAroundYAxis(angle)),
				PoseObservation(config.imuSourceId, TrackerPosition.HIP, now, rotation = q, provenance = p("imu:1"), correctionRotation = q),
				RawHmdPoseInput(RawSourceIdentity(config.hmdSourceId, RawSourceKind.RAW_HMD, isHmd = true),
					Vector3(drift * .25f, 1.7f, 0f), Quaternion.IDENTITY, config.space, p("hmd:1")))
			lastInput = input
			return session.accept(input)
		}
		fun trained(): Map<String, Any?> { repeat(100) { tick(drift = it * .004f) }; return session.latest!! }
		fun lost(): Map<String, Any?> { trained(); return tick(false, drift = .4f) }
		fun dwell(): Map<String, Any?> { lost(); repeat(10) { tick(drift = .4f) }; return tick(true, .4f, .2f, .5f) }
		fun blend(): Map<String, Any?> { dwell(); repeat(8) { tick(drift = .4f, offset = .2f, angle = .5f) }; return session.latest!! }
		override fun close() = session.close()
	}
	private fun number(f: Map<String, Any?>, key: String) = (f[key] as Number).toDouble()
	@Suppress("UNCHECKED_CAST") private fun position(f: Map<String, Any?>, key: String): List<Number> =
		(f[key] as Map<String, Any?>)["positionMeters"] as List<Number>
	private fun states(f: Fixture, count: Int, offset: Float = .2f, angle: Float = .5f) =
		(1..count).map { f.tick(drift = .4f, offset = offset, angle = angle) }

	@Test fun validIsFullAndPrimaryReference() = Fixture().use { f ->
		val v = f.tick(); assertEquals("FULL_6DOF", v["state"]); assertEquals(v["raw6dofPose"], v["finalOutputPose"])
	}
	@Test fun lossEntersFallback() = Fixture().use { assertEquals("FALLBACK_IK", it.lost()["state"]) }
	@Test fun recoveryEntersDwell() = Fixture().use { assertEquals("RECOVERY_DWELL", it.dwell()["state"]) }
	@Test fun satisfiedPhysicalDwellEntersBlend() = Fixture().use { f ->
		val v = f.blend(); assertEquals("RECOVERY_BLEND", v["state"])
		assertTrue(number(v, "dwellElapsedNanos") >= number(v, "dwellThresholdNanos"))
	}
	@Test fun completedBlendReturnsFullWithConvergence() = Fixture().use { f ->
		f.blend(); val v = states(f, 20).last(); assertEquals("FULL_6DOF", v["state"])
		assertEquals(0.0, number(v, "finalPositionResidualMeters")); assertTrue(number(v, "finalAngularResidualRadians") < .001)
	}
	@Test fun relossInDwellCancelsWindow() = Fixture().use { f ->
		f.dwell(); states(f, 4); val v = f.tick(false, .4f); assertEquals("FALLBACK_IK", v["state"])
		assertEquals(0.0, number(v, "dwellElapsedNanos")); assertTrue(f.session.events.any { it["event"] == "dwell_reset" })
		assertEquals(0.0, number(v, "positionStepMeters"))
		assertTrue(number(v, "angularStepRadians") < .001)
	}
	@Test fun relossInBlendReturnsSafeFallback() = Fixture().use { f ->
		f.blend(); val v = f.tick(false, .4f); assertEquals("FALLBACK_IK", v["state"])
		assertNotNull(v["solvedIkPose"]); assertTrue(number(v, "positionStepMeters").isFinite())
		assertTrue(f.session.events.any { it["event"] == "recovery_blend_cancel" })
	}
	@Test fun shortValidPulseCannotRestoreFull() = Fixture().use { f ->
		f.dwell(); assertTrue(states(f, 3).none { it["state"] in listOf("FULL_6DOF", "RECOVERY_BLEND") })
		assertEquals("FALLBACK_IK", f.tick(false, .4f)["state"])
	}
	@Test fun chatterNeverOscillatesIntoFull() = Fixture().use { f ->
		f.lost(); val v = (1..30).map { f.tick(it % 2 == 0, .4f, .2f, .5f) }
		assertTrue(v.none { it["state"] == "FULL_6DOF" || it["state"] == "RECOVERY_BLEND" })
	}
	@Test fun repeatedRecoveryAttemptsAreDeterministic() {
		fun replay() = Fixture().use { f -> f.lost(); (1..40).map { f.tick(it % 4 != 0, .4f, .2f, .5f) } }
		assertEquals(replay(), replay())
	}
	@Test fun sameDriftCorrectionReducesPositionAndAngularResidual() {
		Fixture(false).use { a -> Fixture(true).use { b ->
			var raw: Map<String, Any?> = emptyMap(); var corrected: Map<String, Any?> = emptyMap()
			repeat(160) { raw = a.tick(drift = it * .004f); corrected = b.tick(drift = it * .004f) }
			assertEquals(raw["rawIkPose"], corrected["rawIkPose"])
			assertTrue(number(corrected, "positionResidualMeters") < number(raw, "positionResidualMeters") * .25)
			assertTrue(number(corrected, "angularResidualRadians") < number(raw, "angularResidualRadians") * .25)
			assertTrue(number(corrected, "solvedPositionResidualMeters") < number(raw, "solvedPositionResidualMeters") * .25)
		} }
	}
	@Test fun fallbackSolverStartsFromRetainedCorrectedState() = Fixture().use { f ->
		val before = f.trained(); val v = f.tick(false, .396f)
		assertEquals(before["correctionOffsetMeters"], v["correctionOffsetMeters"])
		assertEquals(position(v, "correctedIkPose"), v["solverFallbackPosition"])
		assertNotEquals(position(v, "rawIkPose"), v["solverFallbackPosition"])
	}
	@Test fun continuousCorrectionCannotMoveFinalFullPose() = Fixture().use { f ->
		repeat(100) { val v = f.tick(drift = it * .004f); assertEquals(v["raw6dofPose"], v["finalOutputPose"]) }
	}
	@Test fun fullToFallbackPositionContinuity() = Fixture().use { f ->
		f.trained(); assertEquals(0.0, number(f.tick(false, .4f), "positionStepMeters"))
	}
	@Test fun fullToFallbackAngularContinuity() = Fixture().use { f ->
		f.trained(); assertTrue(number(f.tick(false, .4f), "angularStepRadians") < .001)
	}
	@Test fun staticRecoveryBlendPositionApproachesTargetMonotonically() = Fixture().use { f ->
		f.blend(); val samples = states(f, 18).filter { it["state"] == "RECOVERY_BLEND" }
		assertTrue(samples.size > 5)
		assertTrue(samples.zipWithNext().all { (a,b) -> number(b, "finalPositionResidualMeters") <= number(a, "finalPositionResidualMeters") + 1e-6 })
		assertTrue(samples.zipWithNext().all { (a,b) -> number(b, "blendProgress") >= number(a, "blendProgress") })
	}
	@Test fun recoveryQuaternionConverges() = Fixture().use { f ->
		f.blend(); val samples = states(f, 18)
		assertTrue(number(samples.last(), "finalAngularResidualRadians") < number(samples.first(), "finalAngularResidualRadians"))
	}
	@Test fun nonzeroRecoveryResidualDoesNotSnap() = Fixture().use { f ->
		val v = f.dwell(); assertTrue(number(v, "finalPositionResidualMeters") > .05)
		assertEquals(0.0, number(v, "positionStepMeters")); assertTrue(number(v, "angularStepRadians") < .001)
	}
	@Test fun maskRetainsIdentityRawValuesAndProvenance() = Fixture().use { f ->
		f.tick(); val raw = f.lastInput!!.main; f.session.gate.setAvailable(false)
		val masked = f.session.gate.apply(raw)
		assertEquals(raw.copy(positionQuality = ObservationQuality.UNAVAILABLE, rotationQuality = ObservationQuality.UNAVAILABLE,
			modality = TrackingModality.NONE), masked)
		assertSame(raw.provenance, masked.provenance)
	}
	@Test fun unmaskRestoresSameSourceAndEpochs() = Fixture().use { f ->
		f.tick(); val raw = f.lastInput!!.main; f.session.gate.setAvailable(false); f.session.gate.setAvailable(true)
		assertSame(raw, f.session.gate.apply(raw))
	}
	@Test fun controlHasNoCoreTransitionCommand(@TempDir dir: Path) = Fixture().use { f ->
		val control = CorePocHilControl(f.session, dir)
		assertFailsWith<IllegalArgumentException> { control.execute(ObjectMapper().readTree("{\"op\":\"enter-fallback\"}")) }
		f.tick(); val before = f.session.latest
		control.execute(ObjectMapper().readTree("{\"op\":\"6dof-valid\",\"value\":\"off\"}"))
		assertSame(before, f.session.latest) // Decision happens only when the next source frame is consumed.
	}
	@Test fun inactiveGateIsInertAndCannotBeControlled() = Fixture(hil = false).use { f ->
		f.tick(); val raw = f.lastInput!!.main
		assertSame(raw, f.session.gate.apply(raw)); assertFailsWith<IllegalStateException> { f.session.gate.setAvailable(false) }
		Unit
	}
	@Test fun captureRecordsRequiredFrameFields(@TempDir dir: Path) = Fixture().use { f ->
		val path = dir.resolve("capture.jsonl"); f.session.captureStart(path); f.trained(); f.session.captureStop()
		val frame = Files.readAllLines(path).map { ObjectMapper().readTree(it) }.last { it["type"].asText() == "frame" }
		for (key in listOf("sourceProvenance", "raw6dofPose", "rawImuOrientationXyzw", "rawIkPose", "correctedIkPose", "solvedIkPose",
			"correctionOffsetMeters", "state", "transitionReason", "transitionTimestampNanos", "dwellElapsedNanos", "dwellThresholdNanos",
			"hysteresisState", "blendProgress", "finalOutputPose", "positionResidualMeters", "angularResidualRadians")) assertTrue(frame.has(key), key)
	}
	@Test fun captureRecordsDiscreteEvents(@TempDir dir: Path) = Fixture().use { f ->
		f.session.captureStart(dir.resolve("capture.jsonl")); f.blend(); f.tick(false, .4f); f.dwell(); states(f, 30); f.session.captureStop()
		val names = f.session.events.map { it["event"] }.toSet()
		assertTrue(names.containsAll(setOf("6dof_lost", "fallback_entered", "recovery_candidate", "dwell_start", "recovery_blend_start", "recovery_blend_cancel", "full_restored", "correction_updated")))
	}
	@Test fun captureIncludesFiniteResidualAndContinuityMetrics() = Fixture().use { f ->
		f.blend(); val v = f.tick(drift = .4f, offset = .2f, angle = .5f)
		for (key in listOf("positionResidualMeters", "angularResidualRadians", "positionStepMeters", "angularStepRadians", "outputVelocityMetersPerSecond", "outputAngularVelocityRadiansPerSecond")) assertTrue(number(v, key).isFinite(), key)
	}
	@Test fun captureStartStopIsDeterministicAndDoesNotReuseFiles(@TempDir dir: Path) = Fixture().use { f ->
		val path = dir.resolve("capture.jsonl"); f.session.captureStart(path); f.tick(); f.session.captureStop()
		val before = Files.readAllBytes(path); f.tick(); assertContentEquals(before, Files.readAllBytes(path))
		assertFails { f.session.captureStart(path) }
		Unit
	}
	@Test fun captureDoesNotAlterCoreDecisions(@TempDir dir: Path) {
		Fixture().use { a -> Fixture().use { b ->
			b.session.captureStart(dir.resolve("capture.jsonl"))
			repeat(150) { i -> assertEquals(a.tick(i !in 100..115, i * .004f), b.tick(i !in 100..115, i * .004f)) }
		} }
	}
	@Test fun largeAndSmallResidualRecoverWithoutSkippingDwell() {
		for (offset in listOf(.01f, 1f)) Fixture().use { f ->
			f.lost(); val start = f.tick(true, .4f, offset, .5f)
			assertEquals("RECOVERY_DWELL", start["state"])
			assertEquals("FULL_6DOF", states(f, 30, offset).last()["state"])
		}
	}
	@Test fun shortDropoutStillUsesFullRecoveryWindow() = Fixture().use { f ->
		f.lost(); assertEquals("RECOVERY_DWELL", f.tick(true, .4f)["state"])
		assertTrue((1..6).map { f.tick(drift = .4f) }.none { it["state"] == "FULL_6DOF" || it["state"] == "RECOVERY_BLEND" })
	}
	@Test fun stalePhysicalFullSampleCannotAccumulateDwell() {
		val target = TrackerPosition.HIP; val space = CorePocHilConfig.synthetic().space
		val c = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK)
		fun main(at: Long, full: Boolean) = ResolvedTrackingPose(target, space,
			if (full) ResolvedComponent(Vector3.NULL, "main", ObservationQuality.TRACKED, at) else null,
			ResolvedComponent(Quaternion.IDENTITY, if (full) "main" else "imu", ObservationQuality.TRACKED, at),
			MainSampleState(if (full) TrackingModality.FULL else TrackingModality.NONE, full, full))
		fun update(now: Long, full: Boolean, sample: Long = now) = c.update(main(sample, full), BackgroundIkResult(OutputPose(target, space,
			ResolvedComponent(Vector3.NULL, "solver", ObservationQuality.TRACKED, now),
			ResolvedComponent(Quaternion.IDENTITY, "solver", ObservationQuality.TRACKED, now), OutputPositionSource.BACKGROUND_IK), "fixture"), now)
		update(0, true); update(1, false); update(2, true); update(1_000_000_000, true, 2)
		assertEquals(FusionPhase.RECOVERY_DWELL, c.fusionPhase); assertEquals(0, c.recoveryDwellElapsedNanos)
	}
	@Test fun staleMainCannotRemainLatchedAsLive() = Fixture().use { f ->
		f.trained(); val old = f.lastInput!!; val now = old.timestampNanos + 600_000_000
		val freshImu = old.imu.copy(observedAtNanos = now, provenance = old.imu.provenance!!.copy(sequence = old.sequence + 1, sampleAtNanos = now))
		val freshHmd = old.hmd.copy(provenance = old.hmd.provenance.copy(sequence = old.sequence + 1, sampleAtNanos = now))
		val v = f.session.accept(old.copy(timestampNanos = now, sequence = old.sequence + 1, imu = freshImu, hmd = freshHmd))
		assertEquals(false, v["sixDofValid"]); assertEquals("FALLBACK_IK", v["state"]); assertNull(v["positionOwner"])
	}
	@Test fun staleHmdCannotManufactureUsableFallback() = Fixture().use { f ->
		f.trained(); val old = f.lastInput!!; f.session.gate.setAvailable(false)
		val now = old.timestampNanos + 200_000_000
		val freshImu = old.imu.copy(observedAtNanos = now, provenance = old.imu.provenance!!.copy(sequence = old.sequence + 1, sampleAtNanos = now))
		val v = f.session.accept(old.copy(timestampNanos = now, sequence = old.sequence + 1, imu = freshImu))
		assertEquals("UNAVAILABLE", v["state"])
		assertNull(positionOrNull(v, "solvedIkPose")); assertNull(positionOrNull(v, "finalOutputPose"))
	}
	@Suppress("UNCHECKED_CAST") private fun positionOrNull(f: Map<String, Any?>, key: String) = (f[key] as Map<String, Any?>)["positionMeters"]
	@Test fun configuredSourceAndPhysicalTimestampsAreValidated() = Fixture().use { f ->
		f.tick(); val raw = f.lastInput!!
		assertFailsWith<IllegalArgumentException> { f.session.accept(raw.copy(timestampNanos = raw.timestampNanos + 1,
			sequence = raw.sequence + 1, main = raw.main.copy(sourceId = "other"))) }
		assertFailsWith<IllegalArgumentException> { f.session.accept(raw.copy(timestampNanos = raw.timestampNanos + 1,
			sequence = raw.sequence + 1, main = raw.main.copy(observedAtNanos = raw.timestampNanos + 1))) }
		Unit
	}
	@Test fun realMtpInputUsesPinnedCodecAndPreservesNativeEnvelope() {
		val bytes = Files.readAllBytes(Path.of(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json"))
		val decoded = (MonakaCodec.decodeEnvelope(bytes) as DecodeResult.Success).value as MtpPose
		val config = CorePocHilConfig.synthetic().copy(space = decoded.coordinate_space,
			mainSourceId = LogicalTracker(decoded.source_id, decoded.tracker_id, decoded.publisher_id).observationId)
		val now = 1_000_000_000L
		fun sample(id: String, position: List<Double>?) = mapOf("sourceId" to id, "sourceEpoch" to "$id:1",
			"calibrationEpoch" to "cal:1", "sequence" to 1, "sampleAtNanos" to now, "positionMeters" to position,
			"quaternionXyzw" to listOf(0.0,0.0,0.0,1.0), "valid" to true)
		val json = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf("timestampNanos" to now,
			"sequence" to 1, "mainMtp" to ObjectMapper().readTree(bytes), "mainReceivedAtNanos" to now,
			"imu" to sample(config.imuSourceId,null), "hmd" to sample(config.hmdSourceId,listOf(0.0,1.7,0.0))))
		val frame = CorePocHilJson.frame(json,config)
		assertEquals(config.mainSourceId,frame.main.sourceId)
		assertEquals(decoded.sequence,frame.main.provenance!!.sequence)
		assertEquals(now-(decoded.sent_at_ns-decoded.timestamp_ns),frame.main.provenance!!.sampleAtNanos)
		CorePocHilSession(config,true).use { assertNotNull(it.accept(frame)["sourceNativeEnvelope"]) }
	}
	@Test fun gateDoesNotMaskOtherSources() = Fixture().use { f ->
		f.tick(false); val imu = f.lastInput!!.imu; assertSame(imu,f.session.gate.apply(imu))
	}
}
