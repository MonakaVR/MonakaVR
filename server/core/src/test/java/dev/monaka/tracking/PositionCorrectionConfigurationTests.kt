package dev.monaka.tracking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PositionCorrectionConfigurationTests {
    private val mapper = ObjectMapper()
    private val space = CoordinateSpace("config-world", "rh_y_up_neg_z_forward", 7)
    private val main = TrackerReference.mtp(LogicalTracker("source", "tracker", "publisher"))
    private val fallback = TrackerReference.slime("hip-imu")
    // Explicit synthetic test inputs, not recommended production/HIL tuning.
    private fun correction() = PositionCorrectionConfig(
        PositionCorrectionRawImuSpaceConfig(fallback.observationId, space, true),
        PositionCorrectionMainMountCalibrationConfig("main;fit:腰", "mount-session", main.observationId, Vector3(-0.0f, -.08f, .02f)),
        PositionCorrectionFixedCalibrationConfig("head;fit:頭", "head-session", "hmd:configured", "model:exact",
            Vector3(.01f, -.1f, -0.0f), Quaternion(-2f, .3f, -.4f, .5f)),
        PositionCorrectionPredictorPolicyConfig(0, 123, Long.MAX_VALUE),
        PositionCorrectionPairingPolicyConfig(0, 456, 789, Long.MAX_VALUE),
        PositionCorrectionLearningTuningConfig(.1, .2, 1.0, 2.0, 3.0, .05, 123456789, 87654321,
            .3, .4, .01, 34567890, 3, .00001),
        PositionCorrectionReacquisitionTuningConfig(987654321),
    )
    private fun assignments(mode: OutputMode = OutputMode.HYBRID) = TrackerBodyAssignments().also {
        it.configure(TrackerPosition.HIP, main, fallback, mode)
    }
    private fun rotation() = RotationCorrectionConfig(
        RotationCorrectionFrames(Quaternion.IDENTITY, Quaternion.rotationAroundYAxis(.2f), space, true),
        RotationCorrectionTuning(2_000_000, 1_000_000, .1, .2, 1.0, .1, 2.0, 2, 50_000_000, 100_000_000),
    )
    private fun config(position: PositionCorrectionConfig? = correction(), mode: OutputMode = OutputMode.HYBRID,
        rotationEnabled: Boolean = false) = MonakaConfiguration(space, assignments(mode), port = 0,
        backgroundIkSharedSpace = space, continuityTuning = ContinuityTuning(123, 234, 345),
        rotationCorrection = if (rotationEnabled) rotation() else null, positionCorrection = position)
    private fun <T> file(block: (Path) -> T): T {
        val dir = Files.createTempDirectory("position-config")
        val path = dir.resolve("config.json")
        try { return block(path) } finally {
            Files.list(dir).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(dir)
        }
    }
    private fun document(position: PositionCorrectionConfig? = correction()): ObjectNode = file { path ->
        config(position).save(path)
        mapper.readTree(path.toFile()) as ObjectNode
    }
    private fun load(root: ObjectNode) = file { path ->
        mapper.writeValue(path.toFile(), root)
        MonakaConfiguration.load(path)
    }
    private fun node(root: ObjectNode, path: String): ObjectNode = root.at(path) as ObjectNode
    private fun reject(path: String, edit: (ObjectNode) -> Unit) {
        val root = document()
        edit(node(root, path))
        assertFails { load(root) }
    }
    private fun objects(root: ObjectNode, path: String = "/positionCorrection"): List<Pair<String, ObjectNode>> {
        val current = node(root, path)
        return listOf(path to current) + current.properties().filter { it.value.isObject }.flatMap {
            objects(root, "$path/${it.key}")
        }
    }

    @Test fun absentAndDisabledRemainNullAndSaveOmittedAtV3() {
        val root = document(null)
        assertEquals(3, root["version"].intValue())
        assertFalse(root.has("positionCorrection"))
        val absent = load(root)
        root.putObject("positionCorrection").put("enabled", false)
        val disabled = load(root)
        assertNull(absent.positionCorrection)
        assertNull(disabled.positionCorrection)
        assertEquals(absent.assignments.snapshot(), disabled.assignments.snapshot())
        assertEquals(absent.continuityTuning, disabled.continuityTuning)
        assertEquals(absent.backgroundIkSharedSpace, disabled.backgroundIkSharedSpace)
        file { path -> disabled.save(path); assertFalse(mapper.readTree(path.toFile()).has("positionCorrection")) }
    }

    @Test fun allExplicitValuesAndRuntimeIdentitiesRoundTripExactlyRepeatedly() = file { path ->
        val original = config(rotationEnabled = true)
        var current = original
        val expected = original.positionCorrection!!.toFoundation()
        repeat(4) {
            current.save(path)
            val root = mapper.readTree(path.toFile())
            assertEquals(3, root["version"].intValue())
            assertEquals(Long.MAX_VALUE, root.at("/positionCorrection/predictorPolicy/maxImuSampleAgeNanos").longValue())
            current = MonakaConfiguration.load(path)
            assertEquals(original.positionCorrection, current.positionCorrection)
            assertEquals(original.rotationCorrection, current.rotationCorrection)
            assertEquals(original.assignments.snapshot(), current.assignments.snapshot())
            assertEquals(original.continuityTuning, current.continuityTuning)
            assertEquals(original.backgroundIkSharedSpace, current.backgroundIkSharedSpace)
            assertEquals(original.port, current.port)
            assertEquals(original.timeoutNanos, current.timeoutNanos)
            val actual = current.positionCorrection!!.toFoundation()
            assertEquals(expected.rawImuBinding, actual.rawImuBinding)
            assertEquals(expected.mainMountCalibration.identity, actual.mainMountCalibration.identity)
            assertEquals(expected.mainMountCalibration.sourceId, actual.mainMountCalibration.sourceId)
            assertEquals(expected.mainMountCalibration.sessionEpoch, actual.mainMountCalibration.sessionEpoch)
            assertEquals(Int.MIN_VALUE, actual.mainMountCalibration.trackerToHipCenterLocalOffset.x.toRawBits())
            assertEquals(expected.fixedCalibration.identity, actual.fixedCalibration.identity)
            assertEquals(expected.fixedCalibration.hmdSourceId, actual.fixedCalibration.hmdSourceId)
            assertEquals(expected.fixedCalibration.bodyModelId, actual.fixedCalibration.bodyModelId)
            assertEquals(expected.fixedCalibration.sessionEpoch, actual.fixedCalibration.sessionEpoch)
            assertEquals(expected.fixedCalibration.hmdToHeadAnchorOrientation, actual.fixedCalibration.hmdToHeadAnchorOrientation)
            assertEquals(Int.MIN_VALUE, actual.fixedCalibration.hmdToHeadAnchorLocalOffset.z.toRawBits())
            assertEquals(expected.predictorPolicy, actual.predictorPolicy)
            assertEquals(expected.pairingPolicy, actual.pairingPolicy)
            assertEquals(expected.learningTuning, actual.learningTuning)
            assertEquals(expected.reacquisitionTuning, actual.reacquisitionTuning)
        }
    }

    @TestFactory fun missingEveryFieldFailsClosed(): List<DynamicTest> = objects(document()).flatMap { (path, obj) ->
        obj.fieldNames().asSequence().map { field -> DynamicTest.dynamicTest("missing $path/$field") {
            reject(path) { it.remove(field) }
        } }.toList()
    }
    @TestFactory fun nullEveryFieldFailsClosed(): List<DynamicTest> = objects(document()).flatMap { (path, obj) ->
        obj.fieldNames().asSequence().map { field -> DynamicTest.dynamicTest("null $path/$field") {
            reject(path) { it.putNull(field) }
        } }.toList()
    }
    @TestFactory fun unknownFieldAtEveryObjectFailsClosed(): List<DynamicTest> = objects(document()).map { (path, _) ->
        DynamicTest.dynamicTest("unknown $path") { reject(path) { it.put("typo", 1) } }
    }
    @TestFactory fun wrongObjectShapesFailClosed(): List<DynamicTest> = objects(document()).flatMap { (path, _) ->
        listOf<JsonNode>(mapper.nodeFactory.textNode("invalid"), mapper.nodeFactory.arrayNode(), mapper.nodeFactory.numberNode(0)).map { value ->
            DynamicTest.dynamicTest("shape $path = $value") {
                val root = document()
                val parent = node(root, path.substringBeforeLast('/'))
                parent.set<JsonNode>(path.substringAfterLast('/'), value)
                assertFails { load(root) }
            }
        }
    }

    @Test fun sectionAndConfirmationMustBeExplicitBooleans() {
        for (value in listOf<JsonNode>(mapper.nodeFactory.textNode("true"), mapper.nodeFactory.numberNode(1))) {
            reject("/positionCorrection") { it.set<JsonNode>("enabled", value) }
            reject("/positionCorrection/rawImuSpace") { it.set<JsonNode>("confirmed", value) }
        }
        reject("/positionCorrection/rawImuSpace") { it.put("confirmed", false) }
        val root = document(null)
        root.putObject("positionCorrection").put("enabled", false).put("staleCalibration", "forbidden")
        assertFails { load(root) }
    }

    @TestFactory fun allIntegerFieldsRejectNegativeFractionStringAndOverflow(): List<DynamicTest> = objects(document()).flatMap { (path, obj) ->
        obj.properties().filter { it.value.isIntegralNumber }.flatMap { entry ->
            val overflow = if (entry.key == "recoverySamples") BigInteger.valueOf(Int.MAX_VALUE.toLong()).add(BigInteger.ONE)
                else BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)
            listOf<JsonNode>(mapper.nodeFactory.numberNode(-1), mapper.nodeFactory.numberNode(.5),
                mapper.nodeFactory.textNode("1"), mapper.nodeFactory.numberNode(overflow)).map { value ->
                DynamicTest.dynamicTest("integer $path/${entry.key} = $value") {
                    reject(path) { it.set<JsonNode>(entry.key, value) }
                }
            }
        }
    }

    @TestFactory fun learningAndReacquisitionRangesUseExistingAuthorities(): List<DynamicTest> {
        val cases = listOf(
            "trackingTauSeconds" to 0.0, "recoveryTauSeconds" to -1.0, "maxResidualMeters" to 0.0,
            "maxCorrectionMagnitudeMeters" to 0.0, "maxCorrectionRateMetersPerSecond" to 0.0,
            "maxUpdateStepMeters" to 0.0, "decayTauSeconds" to 0.0, "maxDecayRateMetersPerSecond" to 0.0,
            "recoveryResidualMeters" to -1.0, "recoveryResidualMeters" to 1.01,
            "zeroEpsilonMeters" to -1.0, "zeroEpsilonMeters" to 2.01,
        )
        return cases.map { (field, value) -> DynamicTest.dynamicTest("range $field = $value") {
            reject("/positionCorrection/learningTuning") { it.put(field, value) }
        } } + listOf("maxLearningDtNanos", "recoverySamples").map { field -> DynamicTest.dynamicTest("positive $field") {
            reject("/positionCorrection/learningTuning") { it.put(field, 0) }
        } } + DynamicTest.dynamicTest("positive reacquisition") {
            reject("/positionCorrection/reacquisitionTuning") { it.put("reacquireDurationNanos", 0) }
        }
    }

    @TestFactory fun finiteNumbersAndFloatRepresentabilityAreRequired(): List<DynamicTest> {
        val doubles = node(document(), "/positionCorrection/learningTuning").properties().filter { it.value.isFloatingPointNumber }.map { it.key }
        val tuningTests = doubles.flatMap { field ->
            listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).map { value ->
                DynamicTest.dynamicTest("finite $field = $value") {
                    // Direct DTO validation cannot be evaded by skipping the JSON parser.
                    val root = document()
                    node(root, "/positionCorrection/learningTuning").put(field, value)
                    assertFails { PositionCorrectionConfigJson.read(root["positionCorrection"]) }
                }
            } + DynamicTest.dynamicTest("numeric $field") {
                reject("/positionCorrection/learningTuning") { it.put(field, "0.1") }
            }
        }
        val vectors = listOf("mainMountCalibration" to "trackerToHipCenterLocalOffsetMeters",
            "predictorFixedCalibration" to "hmdToHeadAnchorLocalOffsetMeters", "predictorFixedCalibration" to "hmdToHeadAnchorOrientationWxyz")
        return tuningTests + vectors.flatMap { (section, field) ->
            listOf(Double.NaN, Double.POSITIVE_INFINITY, 1e100, 1e-100).map { value -> DynamicTest.dynamicTest("float $field = $value") {
                val root = document()
                val arr = node(root, "/positionCorrection/$section")[field] as com.fasterxml.jackson.databind.node.ArrayNode
                arr.set(0, mapper.nodeFactory.numberNode(value))
                assertFails { PositionCorrectionConfigJson.read(root["positionCorrection"]) }
            } } + listOf(0, 2, 5).map { size -> DynamicTest.dynamicTest("array $field length $size") {
                reject("/positionCorrection/$section") { obj -> obj.putArray(field).also { arr -> repeat(size) { arr.add(0.0) } } }
            } } + DynamicTest.dynamicTest("numeric array $field") {
                reject("/positionCorrection/$section") { obj ->
                    (obj[field] as com.fasterxml.jackson.databind.node.ArrayNode).set(0, mapper.nodeFactory.textNode("0"))
                }
            }
        }
    }

    @TestFactory fun nonblankCalibrationAndSourceIdsAreRequired(): List<DynamicTest> =
        listOf("rawImuSpace" to listOf("sourceId"), "mainMountCalibration" to listOf("calibrationId", "sessionEpoch", "sourceId"),
            "predictorFixedCalibration" to listOf("calibrationId", "sessionEpoch", "hmdSourceId", "bodyModelId"),
            "rawImuSpace/space" to listOf("id")).flatMap { (section, fields) -> fields.map { field ->
            DynamicTest.dynamicTest("blank $section/$field") { reject("/positionCorrection/$section") { it.put(field, "  ") } }
        } }

    @Test fun sourceAndExactSpaceBindingsFailClosed() {
        reject("/positionCorrection/rawImuSpace") { it.put("sourceId", "slime:other") }
        reject("/positionCorrection/mainMountCalibration") { it.put("sourceId", "mtp:other") }
        reject("/positionCorrection/rawImuSpace/space") { it.put("id", "other") }
        reject("/positionCorrection/rawImuSpace/space") { it.put("revision", space.revision + 1) }
        reject("/positionCorrection/rawImuSpace/space") { it.put("convention", "other") }
        reject("/positionCorrection/rawImuSpace/space") { it.put("revision", 4294967296L) }
    }

    @Test fun hipParticipationAndPhysicalSlimeFallbackAndMtpMainAreRequired() {
        val invalid = listOf(TrackerBodyAssignments(),
            TrackerBodyAssignments().also { it.configure(TrackerPosition.HIP, main) },
            TrackerBodyAssignments().also { it.configure(TrackerPosition.HIP, main, fallback, useAsIkConstraint = false) },
            TrackerBodyAssignments().also { it.configure(TrackerPosition.HIP, main, TrackerReference.mtp(LogicalTracker("s", "f", "p"))) },
            TrackerBodyAssignments().also { it.configure(TrackerPosition.HIP, TrackerReference.slime("main"), fallback) },
            TrackerBodyAssignments().also { it.configure(TrackerPosition.HIP, main, TrackerReference.slime("other")) },
            TrackerBodyAssignments().also { it.configure(TrackerPosition.HIP, TrackerReference.mtp(LogicalTracker("other", "m", "p")), fallback) })
        invalid.forEach { assertFails { MonakaConfiguration(space, it, positionCorrection = correction()) } }
    }

    @Test fun outputModesAreNotOverconstrainedAndRotationRemainsIndependent() {
        for (mode in OutputMode.entries) {
            val loaded = file { path -> config(mode = mode).save(path); MonakaConfiguration.load(path) }
            assertEquals(mode, loaded.assignments.snapshot().targets[TrackerPosition.HIP]!!.outputMode)
            assertNotNull(loaded.positionCorrection)
        }
        val original = config(rotationEnabled = true)
        val loaded = file { path -> original.save(path); MonakaConfiguration.load(path) }
        assertEquals(original.rotationCorrection, loaded.rotationCorrection)
        assertEquals(original.positionCorrection, loaded.positionCorrection)
        assertFails { MonakaConfiguration(space, assignments(), rotationCorrection = rotation(), positionCorrection = correction()) }
        // Position-only does not infer or require Rotation Correction's separate alignment assertion.
        assertNotNull(MonakaConfiguration(space, assignments(), positionCorrection = correction()).positionCorrection)
    }

    @Test fun zeroAndIdentityAreAllowedOnlyAsExplicitCalibrationValues() {
        val explicit = correction().copy(
            mainMountCalibration = correction().mainMountCalibration.copy(trackerToHipCenterLocalOffsetMeters = Vector3(0f, 0f, 0f)),
            fixedCalibration = correction().fixedCalibration.copy(hmdToHeadAnchorLocalOffsetMeters = Vector3(0f, 0f, 0f),
                hmdToHeadAnchorOrientationWxyz = Quaternion.IDENTITY))
        val runtime = load(document(explicit)).positionCorrection!!.toFoundation()
        assertEquals(Vector3(0f, 0f, 0f), runtime.mainMountCalibration.trackerToHipCenterLocalOffset)
        assertEquals(Vector3(0f, 0f, 0f), runtime.fixedCalibration.hmdToHeadAnchorLocalOffset)
        assertEquals(Quaternion.IDENTITY, runtime.fixedCalibration.hmdToHeadAnchorOrientation)
        assertFails { explicit.fixedCalibration.copy(hmdToHeadAnchorOrientationWxyz = Quaternion(0f, 0f, 0f, 0f)) }
        assertFails { explicit.fixedCalibration.copy(hmdToHeadAnchorOrientationWxyz = Quaternion(Float.NaN, 0f, 0f, 0f)) }
        assertFails { explicit.fixedCalibration.copy(hmdToHeadAnchorLocalOffsetMeters = Vector3(Float.POSITIVE_INFINITY, 0f, 0f)) }
        assertFails { explicit.mainMountCalibration.copy(trackerToHipCenterLocalOffsetMeters = Vector3(Float.NaN, 0f, 0f)) }
        assertFails { explicit.rawImuSpace.copy(operatorConfirmed = false) }
        assertFails { explicit.learningTuning.copy(trackingTauSeconds = Double.NaN) }
        assertFails { explicit.predictorPolicy.copy(maxHmdSampleAgeNanos = -1) }
        assertFails { explicit.pairingPolicy.copy(maxTeacherAgeNanos = -1) }
        assertFails { explicit.reacquisitionTuning.copy(reacquireDurationNanos = 0) }
    }

    @Test fun factoryOwnsCanonicalOrientationAndIdentityAndBindingLineage() {
        val dto = correction().fixedCalibration
        val q = dto.hmdToHeadAnchorOrientationWxyz
        val positive = dto.copy(hmdToHeadAnchorOrientationWxyz = Quaternion(-q.w, -q.x, -q.y, -q.z))
        val first = dto.toSnapshot()
        val second = positive.toSnapshot()
        val authority = PredictorFixedCalibrationSnapshot.create(dto.calibrationId, dto.sessionEpoch, dto.hmdSourceId,
            dto.bodyModelId, dto.hmdToHeadAnchorLocalOffsetMeters, q) as PredictorFixedCalibrationSnapshotResult.Available
        assertEquals(authority.snapshot.identity, first.identity)
        assertEquals(first.identity, second.identity)
        assertEquals(first.hmdToHeadAnchorOrientation, second.hmdToHeadAnchorOrientation)
        assertTrue(first.hmdToHeadAnchorOrientation.w > 0)
        assertTrue(kotlin.math.abs(first.hmdToHeadAnchorOrientation.lenSq() - 1f) < 1e-6f)
        assertNotEquals(first.identity, dto.copy(bodyModelId = "other-model").toSnapshot().identity)
        assertNotEquals(first.identity, dto.copy(hmdSourceId = "other-hmd").toSnapshot().identity)
        assertNotEquals(first.identity, dto.copy(sessionEpoch = "other-session").toSnapshot().identity)
        val mount = correction().mainMountCalibration
        val mountAuthority = MainTrackerMountCalibrationSnapshot.create(mount.calibrationId, mount.sessionEpoch, mount.sourceId,
            mount.trackerToHipCenterLocalOffsetMeters) as MainTrackerMountCalibrationSnapshotResult.Available
        assertEquals(mountAuthority.snapshot.identity, mount.toSnapshot().identity)
        assertNotEquals(mount.toSnapshot().identity, mount.copy(sessionEpoch = "remounted").toSnapshot().identity)
        // Translation signed zero must retain the exact factory lineage.
        assertNotEquals(mount.toSnapshot().identity, mount.copy(trackerToHipCenterLocalOffsetMeters =
            Vector3(0.0f, mount.trackerToHipCenterLocalOffsetMeters.y, mount.trackerToHipCenterLocalOffsetMeters.z)).toSnapshot().identity)
    }

    @Test fun pureMaterializationRetainsConfiguredBodyAndHmdWithoutLiveAcquisition() {
        val dto = correction()
        val runtime = dto.toFoundation()
        assertEquals(MainDecoupledHipPredictorPolicy(0, 123, Long.MAX_VALUE), runtime.predictorPolicy)
        assertEquals(PositionTemporalPairingPolicy(0, 456, 789, Long.MAX_VALUE), runtime.pairingPolicy)
        assertEquals(PositionCorrectionTuning(.1, .2, 1.0, 2.0, 3.0, .05, 123456789, 87654321,
            .3, .4, .01, 34567890, 3, .00001), runtime.learningTuning)
        assertEquals(PositionCorrectionReacquisitionTuning(987654321), runtime.reacquisitionTuning)
        assertEquals("model:exact", runtime.fixedCalibration.bodyModelId)
        assertEquals("hmd:configured", runtime.fixedCalibration.hmdSourceId)
        assertEquals(space, runtime.rawImuBinding.space)
        assertEquals(fallback.observationId, runtime.rawImuBinding.sourceId)
        assertTrue(runtime.rawImuBinding.operatorConfirmed)
        val changedVector = dto.fixedCalibration.hmdToHeadAnchorLocalOffsetMeters + Vector3(1f, 2f, 3f)
        val changedQuaternion = dto.fixedCalibration.hmdToHeadAnchorOrientationWxyz.unit()
        assertNotEquals(changedVector, dto.fixedCalibration.hmdToHeadAnchorLocalOffsetMeters)
        assertNotEquals(changedQuaternion, dto.fixedCalibration.hmdToHeadAnchorOrientationWxyz)
        assertEquals(runtime.fixedCalibration.identity, dto.toFoundation().fixedCalibration.identity)
    }

    @Test fun reassignmentCannotPersistStaleConfirmationOrCalibration() = file { path ->
        val config = config()
        config.save(path)
        val before = Files.readAllBytes(path)
        config.assignments.configure(TrackerPosition.HIP, main, TrackerReference.slime("changed"), OutputMode.HYBRID)
        assertFails { config.save(path) }
        assertContentEquals(before, Files.readAllBytes(path))
        assertFalse(Files.exists(path.resolveSibling("config.json.pending")))
    }

    @Test fun v2LoadsWithoutCorrectionPreservingRotationAndExistingFields() {
        val config = config(null, rotationEnabled = true)
        val root = file { path -> config.save(path); mapper.readTree(path.toFile()) as ObjectNode }
        root.put("version", 2)
        val loaded = load(root)
        assertNull(loaded.positionCorrection)
        assertEquals(config.rotationCorrection, loaded.rotationCorrection)
        assertEquals(config.assignments.snapshot(), loaded.assignments.snapshot())
        assertEquals(config.continuityTuning, loaded.continuityTuning)
        assertEquals(config.backgroundIkSharedSpace, loaded.backgroundIkSharedSpace)
    }

    private fun legacy() = mapper.readTree("""{"version":1,"space":{"id":"config-world","revision":7,
        "convention":"rh_y_up_neg_z_forward"},"assignments":[{"body":"HIP","source_id":"old","tracker_id":"old-tracker"}]}""") as ObjectNode
    private val mapping get() = mapOf(("old" to "old-tracker") to main.mtp!!)

    @Test fun v1RequiresExplicitMappingAndAlwaysLoadsDisabled() = file { path ->
        mapper.writeValue(path.toFile(), legacy())
        assertFails { MonakaConfiguration.load(path) }
        val loaded = MonakaConfiguration.load(path, mapping)
        assertNull(loaded.positionCorrection)
        assertEquals(main, loaded.assignments.snapshot().targets[TrackerPosition.HIP]!!.mainTracker)
    }

    @Test fun olderSchemasRejectAnyPositionSectionAndVersionMustFitInt() {
        for (version in listOf(1, 2)) {
            val root = document()
            root.put("version", version)
            assertFails { load(root) }
            root.putObject("positionCorrection").put("enabled", false)
            assertFails { load(root) }
            root.putNull("positionCorrection")
            assertFails { load(root) }
        }
        for (version in listOf(0L, 4L, 4294967299L)) {
            val root = document(null); root.put("version", version); assertFails { load(root) }
        }
    }

    @Test fun migrationValidatesBeforeWriteBacksUpExactBytesAndRefusesOverwrite() = file { path ->
        mapper.writeValue(path.toFile(), legacy())
        val before = Files.readAllBytes(path)
        val backup = path.resolveSibling("config.json.pre-c2.bak")
        assertFails { MonakaConfiguration.migrate(path, emptyMap()) }
        assertContentEquals(before, Files.readAllBytes(path))
        assertFalse(Files.exists(backup))
        val upgraded = MonakaConfiguration.migrate(path, mapping)
        assertNull(upgraded.positionCorrection)
        assertContentEquals(before, Files.readAllBytes(backup))
        assertEquals(3, mapper.readTree(path.toFile())["version"].intValue())
        val after = Files.readAllBytes(path)
        assertFails { MonakaConfiguration.migrate(path, mapping) }
        assertContentEquals(after, Files.readAllBytes(path))
        assertContentEquals(before, Files.readAllBytes(backup))
    }

    @Test fun v2MigrationPreservesBackupInvariants() = file { path ->
        val root = document(null); root.put("version", 2)
        mapper.writeValue(path.toFile(), root)
        val before = Files.readAllBytes(path)
        assertNull(MonakaConfiguration.migrate(path, emptyMap()).positionCorrection)
        assertContentEquals(before, Files.readAllBytes(path.resolveSibling("config.json.pre-c2.bak")))
        assertEquals(3, mapper.readTree(path.toFile())["version"].intValue())
    }
    @Test fun invalidEnabledMigrationDoesNotCreateBackupOrReplaceDocument() = file { path ->
        val root = document(); node(root, "/positionCorrection/predictorFixedCalibration").remove("bodyModelId")
        mapper.writeValue(path.toFile(), root)
        val before = Files.readAllBytes(path)
        assertFails { MonakaConfiguration.migrate(path, emptyMap()) }
        assertContentEquals(before, Files.readAllBytes(path))
        assertFalse(Files.exists(path.resolveSibling("config.json.pre-c2.bak")))
    }
}
