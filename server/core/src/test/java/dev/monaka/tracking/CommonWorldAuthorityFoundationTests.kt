package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.*

internal class CommonWorldAuthorityFoundationTests {
	private val source = SourceSpaceAuthorityEpoch("synthetic:alvr", "xr-session", "view-in-stage-identity-offsets", 3)
	private val world = CommonWorldAuthority("bridge", CoordinateSpace("monaka-world-local", "rh_y_up_neg_z_forward", 7),
		CommonWorldEpoch("opaque-W1"), source)
	private val identity = SourceToCommonRigidTransform(Quaternion.IDENTITY, Vector3(0f, 0f, 0f))
	private fun snapshot(w: CommonWorldAuthority = world, revision: Long = 100, calibration: String = "C1",
		transform: SourceToCommonRigidTransform = identity) = SourceToCommonMappingSnapshot(
		w.ownerId, source, w, CommonMappingCalibrationEpoch(calibration), CommonMappingRevision(revision), transform)
	private fun state(capacity: Int = 128) = CommonWorldAuthorityState("bridge", capacity)
	private fun live(capacity: Int = 128) = state(capacity).also {
		assertNotNull(it.establishWorld(world)); assertNotNull(it.publishMapping(snapshot()))
	}
	private fun nextWorld(epoch: String = "opaque-W2", revision: Long = 8) =
		world.copy(epoch = CommonWorldEpoch(epoch), space = world.space.copy(revision = revision))
	private fun pose(space: SourceSpaceAuthorityEpoch = source, p: Vector3 = Vector3(1f, 2f, 3f),
		q: Quaternion = Quaternion.IDENTITY) = TrustedHmdSourcePoseSnapshot(TrustedHmdSourceObservationIdentity(space, 41), p, q)
	private fun closeEnough(p: Vector3, expected: Vector3) {
		assertEquals(expected.x, p.x, 1e-5f); assertEquals(expected.y, p.y, 1e-5f); assertEquals(expected.z, p.z, 1e-5f)
	}

	@Test fun firstAndIdempotentWorldAndMappingReuseTheSameLiveHandles() {
		val s = state(); assertNull(s.publishMapping(snapshot()))
		val w = assertNotNull(s.establishWorld(world)); assertSame(w, s.establishWorld(world.copy()))
		val m = assertNotNull(s.publishMapping(snapshot())); assertSame(m, s.publishMapping(snapshot().copy()))
		assertSame(w, m.worldHandle); assertTrue(s.isCurrent(w)); assertTrue(s.isCurrent(m))
		// Equal structural facts cannot create a current capability.
		assertFalse(s.isCurrent(CommonWorldAuthorityHandle(world)))
		assertFalse(s.isCurrent(SourceToCommonMappingHandle(snapshot(), w)))
		assertTrue(CommonWorldAuthorityHandle::class.java.declaredMethods.none { it.name == "copy" })
		assertTrue(SourceToCommonMappingHandle::class.java.declaredMethods.none { it.name == "copy" })
	}

	@ParameterizedTest @ValueSource(strings = ["owner", "session", "source", "generation", "space", "revision", "convention"])
	fun sameWorldTokenWithDifferentContentRevokesImmediately(case: String) {
		val s = live(); val w = s.currentWorld()!!; val m = s.currentMapping()!!
		// Invalid convention is rejected by the value boundary rather than admitted as live authority.
		if (case == "convention") {
			assertFailsWith<IllegalArgumentException> { world.copy(space = world.space.copy(convention = "other")) }; return
		}
		val changed = when (case) {
			"owner" -> world.copy(ownerId = "foreign")
			"session" -> world.copy(anchor = source.copy(sourceSessionEpoch = "xr-2"))
			"source" -> world.copy(anchor = source.copy(sourceId = "other"))
			"generation" -> world.copy(anchor = source.copy(generation = 4))
			"space" -> world.copy(space = world.space.copy(id = "different"))
			else -> world.copy(space = world.space.copy(revision = 8))
		}
		assertNull(s.establishWorld(changed)); assertFalse(s.isCurrent(w)); assertFalse(s.isCurrent(m))
		assertNull(s.mapSourcePose(pose())); assertNull(s.establishWorld(world))
	}

	@ParameterizedTest @ValueSource(longs = [0, 6, 7])
	fun equalOrRollbackWorldRevisionRejectsWithoutReplacingCurrent(revision: Long) {
		val s = live(); val current = s.currentWorld()
		assertNull(s.establishWorld(nextWorld(revision = revision))); assertSame(current, s.currentWorld())
		assertNotNull(s.mapSourcePose(pose()))
	}

	@Test fun replacementRequiresFreshEpochAndHigherRevisionAndRetiresOldMappingCalibration() {
		val s = live(); val oldWorld = s.currentWorld()!!; val oldMapping = s.currentMapping()!!
		val next = nextWorld(); val w = assertNotNull(s.establishWorld(next))
		assertFalse(s.isCurrent(oldWorld)); assertFalse(s.isCurrent(oldMapping)); assertNull(s.currentMapping())
		assertNull(s.establishWorld(world.copy(space = world.space.copy(revision = 9)))) // W1 -> W2 -> W1
		assertSame(w, s.currentWorld())
		assertNull(s.publishMapping(snapshot(next, 101, "C1"))) // retired fit cannot cross worlds
		assertNull(s.publishMapping(snapshot(next, 100, "C2"))) // Config content revision must advance
		assertNotNull(s.publishMapping(snapshot(next, 101, "C2")))
	}

	@Test fun mappingOnlyRevokePreservesWorldButCannotReviveOldCalibrationOrRevision() {
		val s = live(); val w = s.currentWorld(); val m = s.currentMapping()!!
		s.revokeMapping(CommonWorldRevocationReason.MAPPING_WITHDRAWN)
		assertSame(w, s.currentWorld()); assertFalse(s.isCurrent(m)); assertNull(s.currentMapping())
		assertNull(s.publishMapping(snapshot())); assertNull(s.publishMapping(snapshot(revision = 101)))
		assertNotNull(s.publishMapping(snapshot(revision = 101, calibration = "C2")))
		assertEquals(CommonWorldRevocationReason.MAPPING_WITHDRAWN, s.lastRevocation()!!.reason)
	}

	@ParameterizedTest @ValueSource(strings = ["ANCHOR_AUTHORITY_LOST", "SOURCE_SPACE_CHANGED", "SOURCE_SESSION_CHANGED", "EVENT_STREAM_LOST", "MAPPING_WITHDRAWN"])
	fun worldRevocationRequiresNoNewPoseAndCannotResurrectEpoch(reason: String) {
		val s = live(); val w = s.currentWorld()!!; val m = s.currentMapping()!!
		s.revokeWorld(CommonWorldRevocationReason.valueOf(reason))
		assertFalse(s.isCurrent(w)); assertFalse(s.isCurrent(m)); assertNull(s.mapSourcePose(pose()))
		assertNull(s.establishWorld(world)); assertNull(s.establishWorld(nextWorld("opaque-W1", 99)))
		assertEquals(world.epoch, s.lastRevocation()!!.worldEpoch)
		assertEquals(reason, s.lastRevocation()!!.reason.name)
		assertNotNull(s.establishWorld(nextWorld()))
	}

	@Test fun worldRevisionNamespacesAreDistinctButTokensAreNeverReusableAcrossIds() {
		val s = live(); val other = nextWorld().copy(space = world.space.copy(id = "other-world", revision = 0))
		assertNotNull(s.establishWorld(other))
		assertNull(s.establishWorld(world.copy(space = world.space.copy(id = "third", revision = 0))))
		assertNull(s.establishWorld(nextWorld("opaque-W3", 7)))
		assertNotNull(s.establishWorld(nextWorld("opaque-W3", 8)))
	}

	@ParameterizedTest @ValueSource(strings = ["sameCalibration", "freshCalibration", "transform"])
	fun sameWorldNewerMappingRevisionReplacesOnlyTheMapping(case: String) {
		val s = live(); val w = s.currentWorld(); val old = s.currentMapping()!!
		val next = snapshot(revision = 101, calibration = if (case == "freshCalibration") "C2" else "C1",
			transform = if (case == "transform") SourceToCommonRigidTransform(Quaternion.IDENTITY, Vector3(1f, 0f, 0f)) else identity)
		val m = assertNotNull(s.publishMapping(next))
		assertSame(w, s.currentWorld()); assertTrue(s.isCurrent(m)); assertFalse(s.isCurrent(old))
		assertEquals(world.epoch, m.snapshot.commonWorld.epoch); assertEquals(7, m.snapshot.commonWorld.space.revision)
		assertEquals(next, s.mapSourcePose(pose())!!.mapping)
	}

	@ParameterizedTest @ValueSource(strings = ["transform", "calibration", "source"])
	fun sameRevisionConflictRevokesMappingAndRetainsWorld(case: String) {
		val s = live(); val w = s.currentWorld(); val old = s.currentMapping()!!
		val conflict = when (case) {
			"transform" -> snapshot(transform = SourceToCommonRigidTransform(Quaternion.IDENTITY, Vector3(1f, 0f, 0f)))
			"calibration" -> snapshot(calibration = "C2")
			else -> snapshot().copy(sourceSpace = source.copy(generation = 4))
		}
		assertNull(s.publishMapping(conflict)); assertSame(w, s.currentWorld()); assertFalse(s.isCurrent(old))
		assertNull(s.currentMapping()); assertEquals(CommonWorldRevocationReason.MAPPING_CONFLICT, s.lastRevocation()!!.reason)
		assertNull(s.publishMapping(snapshot())); assertNull(s.publishMapping(snapshot(revision = 101)))
	}

	@Test fun staleMappingCannotDestroyGoodMappingOrHighWater() {
		val s = live(); val old = s.currentMapping()
		assertNull(s.publishMapping(snapshot(revision = 99, calibration = "other")))
		assertSame(old, s.currentMapping()); assertNotNull(s.mapSourcePose(pose()))
		assertNotNull(s.publishMapping(snapshot(revision = 101)))
	}

	@Test fun calibrationAbaIsRejectedEvenWithNewRevisionAndIdenticalTransform() {
		val s = live(); assertNotNull(s.publishMapping(snapshot(revision = 101, calibration = "C2")))
		val current = s.currentMapping()
		assertNull(s.publishMapping(snapshot(revision = 102, calibration = "C1")))
		assertSame(current, s.currentMapping()); assertEquals("C2", s.mapSourcePose(pose())!!.mapping.calibrationEpoch.value)
	}

	@ParameterizedTest @ValueSource(strings = ["world", "calibration", "namespace"])
	fun boundedTombstoneExhaustionPermanentlyClosesTrust(case: String) {
		val s = live(1)
		when (case) {
			"world" -> { assertNotNull(s.establishWorld(nextWorld())); assertNull(s.establishWorld(nextWorld("W3", 9))) }
			"calibration" -> {
				assertNotNull(s.publishMapping(snapshot(revision = 101, calibration = "C2")))
				assertNull(s.publishMapping(snapshot(revision = 102, calibration = "C3")))
			}
			else -> assertNull(s.establishWorld(nextWorld().copy(space = world.space.copy(id = "other"))))
		}
		assertNull(s.currentWorld()); assertNull(s.currentMapping()); assertNull(s.mapSourcePose(pose()))
		assertNull(s.establishWorld(nextWorld("fresh", 10)))
		assertEquals(CommonWorldRevocationReason.CAPACITY_EXHAUSTED, s.lastRevocation()!!.reason)
	}

	@ParameterizedTest @ValueSource(strings = ["source", "session", "space", "generation"])
	fun strictOpenXrAnchorAndSourceObservationMustMatchExactly(case: String) {
		val different = when (case) {
			"source" -> source.copy(sourceId = "other")
			"session" -> source.copy(sourceSessionEpoch = "other")
			"space" -> source.copy(sourceSpaceId = "other")
			else -> source.copy(generation = 4)
		}
		assertFailsWith<IllegalArgumentException> { SourceToCommonMappingSnapshot.openXrAnchor(different, world,
			CommonMappingCalibrationEpoch("C1"), CommonMappingRevision(100), identity) }
		assertNull(live().mapSourcePose(pose(different)))
	}

	@Test fun strictAnchorAcceptsDerivedIdentityAndRejectsNonidentity() {
		assertEquals(snapshot(), SourceToCommonMappingSnapshot.openXrAnchor(source, world,
			CommonMappingCalibrationEpoch("C1"), CommonMappingRevision(100), identity))
		assertFailsWith<IllegalArgumentException> { SourceToCommonMappingSnapshot.openXrAnchor(source, world,
			CommonMappingCalibrationEpoch("C1"), CommonMappingRevision(100),
			SourceToCommonRigidTransform(Quaternion.IDENTITY, Vector3(1f, 0f, 0f))) }
	}

	@Test fun identityPreservesExactSourceBitsAndAllIndependentBindingFacts() {
		val s = live(); val sourcePose = pose(p = Vector3(-0f, Float.fromBits(1), 2f), q = Quaternion(-1f, -0f, 0f, -0f))
		val result = assertNotNull(s.mapSourcePose(sourcePose))
		assertSame(sourcePose, result.source); assertSame(s.currentMapping()!!.snapshot, result.mapping)
		assertEquals(sourcePose.position, result.position); assertEquals(sourcePose.orientation, result.orientation)
		assertEquals(Int.MIN_VALUE, result.position.x.toRawBits()); assertEquals(1, result.position.y.toRawBits())
		assertEquals(source, result.source.identity.sourceSpace); assertEquals(41, result.source.identity.observationId)
		assertEquals("opaque-W1", result.mapping.commonWorld.epoch.value); assertEquals(7, result.mapping.commonWorld.space.revision)
		assertEquals("C1", result.mapping.calibrationEpoch.value); assertEquals(100, result.mapping.mappingRevision.value)
		assertEquals(identity, result.mapping.transform)
		assertTrue(SourceToCommonRigidTransform(Quaternion(-1f, -0f, 0f, 0f), Vector3(-0f, 0f, -0f)).isIdentity)
	}

	@ParameterizedTest @ValueSource(strings = ["translation", "yaw", "pitch", "roll", "rotationTranslation"])
	fun directionAndHamiltonMathUseRotationThenTranslation(case: String) {
		val halfPi = (Math.PI / 2).toFloat()
		val q = when (case) {
			"pitch" -> Quaternion.rotationAroundXAxis(halfPi)
			"roll" -> Quaternion.rotationAroundZAxis(halfPi)
			"yaw", "rotationTranslation" -> Quaternion.rotationAroundYAxis(halfPi)
			else -> Quaternion.IDENTITY
		}
		val t = if (case in listOf("translation", "rotationTranslation")) Vector3(10f, 20f, 30f) else Vector3(0f, 0f, 0f)
		val s = state(); s.establishWorld(world); s.publishMapping(snapshot(transform = SourceToCommonRigidTransform(q, t)))
		val sourceQ = Quaternion.rotationAroundXAxis(.3f)
		val mapped = assertNotNull(s.mapSourcePose(pose(q = sourceQ)))
		val rotated = when (case) {
			"pitch" -> Vector3(1f, -3f, 2f)
			"roll" -> Vector3(-2f, 1f, 3f)
			"yaw", "rotationTranslation" -> Vector3(3f, 2f, -1f)
			else -> Vector3(1f, 2f, 3f)
		}
		closeEnough(mapped.position, rotated + t)
		// Basis vectors independently verify Q order against R_mapping(R_source(v)).
		for (basis in listOf(Vector3(1f, 0f, 0f), Vector3(0f, 1f, 0f), Vector3(0f, 0f, 1f)))
			closeEnough(mapped.orientation.sandwich(basis), q.sandwich(sourceQ.sandwich(basis)))
		assertEquals(sourceQ, mapped.source.orientation)
	}

	@Test fun finiteInputsThatOverflowAreRejectedWithoutClamping() {
		val s = state(); s.establishWorld(world)
		s.publishMapping(snapshot(transform = SourceToCommonRigidTransform(Quaternion.IDENTITY, Vector3(Float.MAX_VALUE, 0f, 0f))))
		assertNull(s.mapSourcePose(pose(p = Vector3(Float.MAX_VALUE, 0f, 0f))))
	}

	@Test fun negativeIdentityRotationDoesNotSilentlyCanonicalizeQuaternionProduct() {
		val s = state(); s.establishWorld(world)
		val negative = SourceToCommonRigidTransform(Quaternion(-1f, 0f, 0f, 0f), Vector3(-0f, 0f, 0f))
		assertTrue(negative.isIdentity); s.publishMapping(snapshot(transform = negative))
		val sourcePose = pose(q = Quaternion.rotationAroundXAxis(.3f))
		val result = assertNotNull(s.mapSourcePose(sourcePose))
		assertEquals(-sourcePose.orientation.w, result.orientation.w)
		assertEquals(-sourcePose.orientation.x, result.orientation.x)
		assertEquals(sourcePose.position, result.position); assertSame(sourcePose, result.source)
	}

	@ParameterizedTest @ValueSource(strings = ["mappingUpdate", "mappingRevoke", "worldRevoke", "worldReplace", "revokeRepublish"])
	fun inFlightFinalCheckRejectsDeterministicallyWithoutSleep(case: String) {
		val s = live(); val before = s.currentMapping()!!
		val mapped = s.mapSourcePose(pose()) {
			when (case) {
				"mappingUpdate" -> assertNotNull(s.publishMapping(snapshot(revision = 101)))
				"mappingRevoke" -> s.revokeMapping(CommonWorldRevocationReason.MAPPING_WITHDRAWN)
				"worldRevoke" -> s.revokeWorld(CommonWorldRevocationReason.ANCHOR_AUTHORITY_LOST)
				"worldReplace" -> assertNotNull(s.establishWorld(nextWorld()))
				else -> {
					s.revokeMapping(CommonWorldRevocationReason.MAPPING_WITHDRAWN)
					assertNotNull(s.publishMapping(snapshot(revision = 101, calibration = "C2")))
				}
			}
		}
		assertNull(mapped); assertFalse(s.isCurrent(before))
	}

	@Test fun invalidIdentityAndPoseFieldsAreRejectedByValueBoundaries() {
		for (blank in listOf("", " ", "\t")) {
			assertFailsWith<IllegalArgumentException> { source.copy(sourceId = blank) }
			assertFailsWith<IllegalArgumentException> { source.copy(sourceSessionEpoch = blank) }
			assertFailsWith<IllegalArgumentException> { source.copy(sourceSpaceId = blank) }
			assertFailsWith<IllegalArgumentException> { CommonWorldEpoch(blank) }
			assertFailsWith<IllegalArgumentException> { CommonMappingCalibrationEpoch(blank) }
			assertFailsWith<IllegalArgumentException> { world.copy(ownerId = blank) }
			assertFailsWith<IllegalArgumentException> { world.copy(space = world.space.copy(id = blank)) }
		}
		assertFailsWith<IllegalArgumentException> { source.copy(generation = -1) }
		for (revision in listOf(-1L, 4294967296L, Long.MAX_VALUE)) {
			assertFailsWith<IllegalArgumentException> { world.copy(space = world.space.copy(revision = revision)) }
			assertFailsWith<IllegalArgumentException> { CommonMappingRevision(revision) }
		}
		assertFailsWith<IllegalArgumentException> { snapshot().copy(ownerId = "foreign") }
		assertFailsWith<IllegalArgumentException> { TrustedHmdSourceObservationIdentity(source, -1) }
		for (q in listOf(Quaternion.NULL, Quaternion(2f, 0f, 0f, 0f), Quaternion(Float.NaN, 0f, 0f, 0f),
			Quaternion(Float.POSITIVE_INFINITY, 0f, 0f, 0f))) {
			assertFailsWith<IllegalArgumentException> { SourceToCommonRigidTransform(q, Vector3(0f, 0f, 0f)) }
			assertFailsWith<IllegalArgumentException> { pose(q = q) }
		}
		for (p in listOf(Vector3(Float.NaN, 0f, 0f), Vector3(0f, Float.POSITIVE_INFINITY, 0f))) {
			assertFailsWith<IllegalArgumentException> { SourceToCommonRigidTransform(Quaternion.IDENTITY, p) }
			assertFailsWith<IllegalArgumentException> { pose(p = p) }
		}
	}

	@Test fun revisionsAtUint32LimitCannotWrap() {
		val s = state(); val max = world.copy(space = world.space.copy(revision = 4294967295L))
		assertNotNull(s.establishWorld(max)); assertNotNull(s.publishMapping(snapshot(max, 4294967295L)))
		assertNull(s.establishWorld(nextWorld(revision = 0))); assertNull(s.publishMapping(snapshot(max, 0, "C2")))
		assertNotNull(s.mapSourcePose(pose()))
	}
}
