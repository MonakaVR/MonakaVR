package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.config.SkeletonConfigManager
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import kotlin.test.*

/** Synthetic raw inputs and test-only predictors; no HIP calculation or runtime assembly. */
class BodyModelPredictorHandoffTests {
	private val space = CoordinateSpace("canonical", "rh_y_up_neg_z_forward", 2)
	private val geometry = listOf(.1f, .2f, .25f, .3f, .35f, .15f)
	private val chain = listOf(SkeletonConfigOffsets.HEAD, SkeletonConfigOffsets.NECK,
		SkeletonConfigOffsets.UPPER_CHEST, SkeletonConfigOffsets.CHEST,
		SkeletonConfigOffsets.WAIST, SkeletonConfigOffsets.HIP)
	private val safe = setOf(PositionPredictionDependency.RAW_HMD, PositionPredictionDependency.RAW_IMU,
		PositionPredictionDependency.BODY_MODEL, PositionPredictionDependency.FIXED_CALIBRATION)
	private fun snapshot(values: List<Float> = geometry) =
		assertIs<HipBodyModelSnapshotResult.Available>(HipBodyModelSnapshot.create(
			values[0], values[1], values[2], values[3], values[4], values[5])).snapshot
	private fun fields(body: HipBodyModelSnapshot) = listOf(body.headShift, body.neckLength,
		body.upperChestLength, body.chestLength, body.waistLength, body.hipLength)
	private fun current(config: SkeletonConfigManager) =
		assertIs<HipBodyModelSnapshotResult.Available>(config.currentHipBodyModelSnapshot()).snapshot
	private fun attached() = SkeletonConfigManager(false, HumanPoseManager(emptyList())).also {
		it.setOffsets(chain.zip(geometry).toMap(), false)
	}
	private fun input(body: HipBodyModelSnapshot = snapshot()) = MainDecoupledHipInput(
		RawHmdPoseInput(RawSourceIdentity("synthetic:hmd", RawSourceKind.RAW_HMD, isHmd = true),
			Vector3(0f, 1.7f, 0f), Quaternion.IDENTITY, space,
			ObservationSampleProvenance(4, 80, "hmd:1", "hmd-cal:1", null, space)),
		RawImuOrientationInput(RawSourceIdentity("synthetic:imu", RawSourceKind.RAW_IMU),
			Quaternion.IDENTITY, space,
			ObservationSampleProvenance(4, 90, "imu:1", "imu-cal:1", 1, space)),
		body, syntheticHeadAnchorCalibration("synthetic:hmd", body.identity.modelId), space, 3, 100, predictionSequence = 7)
	private fun prediction(source: MainDecoupledHipInput, position: Vector3) = PositionPrediction.available(
		TrackerPosition.HIP, position, space, PositionBodyReference.HIP_CENTER,
		PositionPredictionProvenance(7, source.nowNanos, source.rawHmd.provenance.sampleAtNanos,
			source.rawImu.provenance.sampleAtNanos, source.epoch()), safe)

	@Test fun inputCarriesAllGeometryAndExactlyTheFactoryIdentity() {
		val body = snapshot()
		val source = input(body)
		assertSame(body, source.bodyModel)
		assertEquals(geometry, fields(source.bodyModel))
		assertEquals(body.identity.modelId, source.epoch().bodyModelId)
		assertEquals(body.identity.epoch, source.epoch().bodyModelEpoch)
	}

	@Test fun fakePredictorReadsGeometryDirectlyThroughInput() {
		// Copy three configured values into a diagnostic vector, not an HMD-to-HIP algorithm.
		val fake = MainDecoupledHipPredictor {
			prediction(it, Vector3(it.bodyModel.headShift, it.bodyModel.waistLength, it.bodyModel.hipLength))
		}
		val before = input()
		val after = before.copy(bodyModel = snapshot(geometry.toMutableList().also { it[4] = .75f }))
		assertEquals(Vector3(.1f, .35f, .15f), fake.predict(before).position)
		assertEquals(Vector3(.1f, .75f, .15f), fake.predict(after).position)
		assertNotEquals(fake.predict(before).provenance!!.epoch, fake.predict(after).provenance!!.epoch)
	}

	@ParameterizedTest
	@ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun everyContentFieldChangesPredictionLineageWithIdenticalRawPose(index: Int) {
		val before = input()
		val after = before.copy(bodyModel = snapshot(geometry.toMutableList().also { it[index] += .125f }))
		assertEquals(before.rawHmd, after.rawHmd)
		assertEquals(before.rawImu, after.rawImu)
		assertNotEquals(before.bodyModel.identity, after.bodyModel.identity)
		assertNotEquals(before.epoch(), after.epoch())
	}

	@Test fun fullIdentityDistinguishesModelsWithEqualEpochString() {
		// The private v1 snapshot constructor cannot fabricate another model ID.
		val epoch = input().epoch()
		val other = epoch.copy(bodyModelId = "synthetic:other-body-model")
		assertEquals(epoch.bodyModelEpoch, other.bodyModelEpoch)
		assertNotEquals(epoch, other)
		assertFailsWith<IllegalArgumentException> { epoch.copy(bodyModelId = " ") }
		assertFailsWith<IllegalArgumentException> { epoch.copy(bodyModelEpoch = "") }
	}

	@Test fun separateObjectsWithSameContentHaveSamePredictionEpoch() {
		val a = snapshot()
		val b = snapshot()
		assertNotSame(a, b)
		assertEquals(a.identity, b.identity)
		assertEquals(input(a).epoch(), input(b).epoch())
	}

	@Test fun repeatedOfflineCaptureAndLivePublicationHaveSameLineage() {
		val offline = SkeletonConfigManager(false)
		offline.setOffsets(chain.zip(geometry).toMap(), false)
		val a = assertIs<HipBodyModelSnapshotResult.Available>(
			SlimeHipBodyModelSnapshotSource.captureOffline(offline)).snapshot
		val b = assertIs<HipBodyModelSnapshotResult.Available>(
			SlimeHipBodyModelSnapshotSource.captureOffline(offline)).snapshot
		val live = current(attached())
		assertNotSame(a, b)
		assertEquals(fields(a), fields(live))
		assertEquals(a.identity, live.identity)
		assertEquals(input(a).epoch(), input(b).epoch())
		assertEquals(input(a).epoch(), input(live).epoch())
	}

	@Test fun sameContentRepublishAdvancesSequenceWithoutChangingPredictionEpoch() {
		val config = attached()
		val a = current(config)
		val sequence = config.hipBodyModelPublicationSequence
		config.setOffset(SkeletonConfigOffsets.WAIST, a.waistLength, false)
		val b = current(config)
		assertEquals(sequence + 1, config.hipBodyModelPublicationSequence)
		assertNotSame(a, b)
		assertEquals(a.identity, b.identity)
		assertEquals(input(a).epoch(), input(b).epoch())
	}

	@Test fun liveUpdateDuringPredictPreservesOldInputAndNextInputUsesNewSnapshot() {
		val config = attached()
		val old = input(current(config))
		val epoch = old.epoch()
		val fake = MainDecoupledHipPredictor {
			config.setOffset(SkeletonConfigOffsets.HIP, .625f, false)
			assertSame(old.bodyModel, it.bodyModel)
			assertEquals(geometry, fields(it.bodyModel))
			assertEquals(epoch, it.epoch())
			prediction(it, Vector3(it.bodyModel.hipLength, 0f, 0f))
		}
		assertEquals(Vector3(.15f, 0f, 0f), fake.predict(old).position)
		val next = old.copy(bodyModel = current(config))
		assertEquals(.625f, next.bodyModel.hipLength)
		assertEquals(.15f, old.bodyModel.hipLength)
		assertEquals(epoch, old.epoch())
		assertNotEquals(epoch, next.epoch())
	}

	@ParameterizedTest
	@ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun nonfinitePublicationPreventsNewInputUntilRecovery(index: Int) {
		for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
			val config = attached()
			val old = input(current(config))
			config.setOffset(chain[index], invalid, false)
			assertIs<HipBodyModelSnapshotResult.Unavailable>(config.currentHipBodyModelSnapshot())
			// Old inputs are immutable values, not proof that unavailable publication is current.
			assertEquals(geometry, fields(old.bodyModel))
			config.setOffset(chain[index], geometry[index], false)
			assertEquals(old.epoch(), input(current(config)).epoch())
		}
	}

	@ParameterizedTest
	@ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun liveRevertRestoresContentBasedPredictionEpoch(index: Int) {
		val config = attached()
		val a = input(current(config))
		config.setOffset(chain[index], geometry[index] + .125f, false)
		val b = input(current(config))
		config.setOffset(chain[index], geometry[index], false)
		val reverted = input(current(config))
		assertNotEquals(a.bodyModel.identity, b.bodyModel.identity)
		assertNotEquals(a.epoch(), b.epoch())
		assertEquals(a.bodyModel.identity, reverted.bodyModel.identity)
		assertEquals(a.epoch(), reverted.epoch())
	}

	@ParameterizedTest
	@ValueSource(ints = [0, 1, 2, 3, 4, 5])
	fun signedZeroIsDistinctInPredictionLineage(index: Int) {
		val positive = input(snapshot(geometry.toMutableList().also { it[index] = +0.0f }))
		val negative = input(snapshot(geometry.toMutableList().also { it[index] = -0.0f }))
		assertEquals(0, fields(positive.bodyModel)[index].toRawBits())
		assertEquals(Int.MIN_VALUE, fields(negative.bodyModel)[index].toRawBits())
		assertNotEquals(positive.bodyModel.identity, negative.bodyModel.identity)
		assertNotEquals(positive.epoch(), negative.epoch())
	}

	@Test fun finiteUnusualGeometryIsNotClampedOrRejected() {
		val values = listOf(-.1f, 0f, -2f, Float.MAX_VALUE, -0f, .001f)
		val source = input(snapshot(values))
		assertEquals(values.map { it.toRawBits() }, fields(source.bodyModel).map { it.toRawBits() })
	}

	@Test fun physicalSamplesAndPredictionProgressionDoNotAlterBodyOrEpoch() {
		val before = input()
		val after = before.copy(
			rawHmd = before.rawHmd.copy(provenance = before.rawHmd.provenance.copy(sequence = 5, sampleAtNanos = 81)),
			rawImu = before.rawImu.copy(provenance = before.rawImu.provenance.copy(sequence = 5, sampleAtNanos = 91)),
			nowNanos = 101)
		val p = prediction(before, Vector3(0f, 0f, 0f)).provenance!!
		val progressed = prediction(after, Vector3(0f, 0f, 0f)).provenance!!.copy(predictionSequence = 8)
		assertNotEquals(p.predictionSequence, progressed.predictionSequence)
		assertNotEquals(p.generatedAtNanos, progressed.generatedAtNanos)
		assertNotEquals(p.inputEarliestAtNanos, progressed.inputEarliestAtNanos)
		assertNotEquals(p.inputLatestAtNanos, progressed.inputLatestAtNanos)
		assertSame(before.bodyModel, after.bodyModel)
		assertEquals(before.bodyModel.identity, after.bodyModel.identity)
		assertEquals(p.epoch, progressed.epoch)
	}

	@Test fun inputHasOnlySnapshotBodyPathAndNoMutableOrTeacherReferences() {
		val forbidden = setOf(SkeletonConfigManager::class.java, HumanPoseManager::class.java,
			HumanSkeleton::class.java, Tracker::class.java, PoseObservation::class.java,
			EffectiveConstraint::class.java, ResolvedTrackingPose::class.java, OutputPose::class.java,
			BackgroundIkResult::class.java, BodyModelIdentity::class.java, HipBodyModelSnapshotResult::class.java,
			MainHipCenterPositionTeacher::class.java, MainTrackerMountCalibrationSnapshot::class.java)
		val inputClass = MainDecoupledHipInput::class.java
		assertTrue(inputClass.declaredFields.none { it.type in forbidden })
		assertTrue(inputClass.constructors.all { it.parameterTypes.none { type -> type in forbidden } })
		assertEquals(HipBodyModelSnapshot::class.java, inputClass.getDeclaredField("bodyModel").type)
		assertTrue(inputClass.declaredFields.none { it.name.contains("publication", ignoreCase = true) })
		assertTrue(PositionPredictionEpoch::class.java.declaredFields.none {
			it.name.contains("publication", ignoreCase = true) || it.type == MainTrackerMountCalibrationIdentity::class.java
		})
		assertTrue(PositionTeacherEpoch::class.java.declaredFields.none {
			it.type == HipBodyModelSnapshot::class.java || it.type == BodyModelIdentity::class.java ||
				it.name.contains("bodyModel", ignoreCase = true)
		})
		assertTrue(HipBodyModelSnapshot::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
			.all { Modifier.isFinal(it.modifiers) && (it.type == Float::class.javaPrimitiveType ||
				it.type == BodyModelIdentity::class.java) })
	}
}
