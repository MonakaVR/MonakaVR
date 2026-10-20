package dev.monaka.tracking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.*

class ModeConfigurationTests {
 private val mapper = ObjectMapper()
 private val space = CoordinateSpace("mode-test-world", "rh_y_up_neg_z_forward", 8)
 private val main = TrackerReference.mtp(LogicalTracker("source", "main", "publisher"))
 private val q = TrackerReference.slime("independent-test-imu")
 private fun config(mode: OutputMode = OutputMode.IK, selected: Boolean = true, aligned: Boolean = false): MonakaConfiguration {
  val assignments = TrackerBodyAssignments().also {
   if (selected) it.configureSources(TrackerPosition.HIP, q, main, true)
   else it.configure(TrackerPosition.HIP, main, q)
   val row = it.snapshot().targets.getValue(TrackerPosition.HIP)
   it.replaceTargets(mapOf(TrackerPosition.HIP to row.copy(outputMode = mode,
    continuity = if (mode == OutputMode.HYBRID) ContinuityPolicy.BACKGROUND_IK else ContinuityPolicy.NONE)))
  }
  return MonakaConfiguration(space, assignments, port = 0, backgroundIkSharedSpace = space.takeIf { aligned })
 }
 private fun rpc(api: MonakaInputConfigurationApi, fields: Map<String, Any?>): JsonNode = mapper.readTree(api.handleJson(
  mapper.writeValueAsString(mapOf("type" to "monaka_inputs_v1", "id" to "mode-test") + fields), 1))
 private fun snapshot(api: MonakaInputConfigurationApi) = rpc(api, mapOf("action" to "snapshot")).also { assertTrue(it["ok"].asBoolean(), it.toString()) }
 private fun save(api: MonakaInputConfigurationApi, s: JsonNode, mode: String, body: String = "HIP") = rpc(api, mapOf(
  "action" to "save_mode", "body" to body, "mode" to mode, "generation" to s["generation"].asText(), "readbackToken" to s["readbackToken"].asText()))
 private fun withApi(c: MonakaConfiguration, path: Path, block: (MonakaInputConfigurationApi) -> Unit) {
  MonakaRuntime({ emptyList() }, space, c.assignments, clock = { 1L }).use { runtime ->
   block(MonakaInputConfigurationApi(c, runtime.mtp, { emptyList() }, path))
  }
 }
 @ParameterizedTest @CsvSource("IK,ik", "IK,direct", "IK,hybrid", "DIRECT,ik", "DIRECT,direct", "DIRECT,hybrid", "HYBRID,ik", "HYBRID,direct", "HYBRID,hybrid")
 fun everyModePairSavesOfflineWithExactBackupAndUnchangedSources(initial: OutputMode, mode: String, @TempDir dir: Path) {
  val path = dir.resolve("config.json"); config(initial).save(path)
  val doc = mapper.readTree(path.toFile()) as ObjectNode
  doc.putObject("futureRoot").put("keep", 42); doc.putArray("bodyLengths").add(0.31)
  (doc["assignments"][0] as ObjectNode).putObject("futureAssignment").put("keep", "yes")
  ((doc["assignments"][0]["mainTracker"]) as ObjectNode).put("futureIdentity", "preserved")
  Files.writeString(path, doc.toString())
  val c = MonakaConfiguration.load(path); val active = c.assignments.snapshot(); val before = Files.readAllBytes(path)
  withApi(c, path) { api ->
   val s = snapshot(api); assertEquals(initial.name.lowercase(), s["assignments"][0]["mode"].asText())
   val response = save(api, s, mode); assertTrue(response["ok"].asBoolean(), response.toString())
   val changed = mode != initial.name.lowercase()
   assertEquals(changed, response["restartRequired"].asBoolean())
   assertEquals(initial.name.lowercase(), response["assignments"][0]["activeMode"].asText())
   assertEquals(initial.name.lowercase(), response["assignments"][0]["mode"].asText())
   assertEquals(mode, response["assignments"][0]["savedMode"].asText())
   assertEquals(active, c.assignments.snapshot()); assertEquals(s["generation"], response["generation"])
   val saved = MonakaConfiguration.load(path).assignments.snapshot().targets.getValue(TrackerPosition.HIP)
   assertEquals(OutputMode.valueOf(mode.uppercase()), saved.outputMode)
   assertEquals(mode == "hybrid", saved.continuity == ContinuityPolicy.BACKGROUND_IK); assertTrue(saved.useAsIkConstraint)
   val after = mapper.readTree(path.toFile()) as ObjectNode
   val expected = doc.deepCopy(); val row = expected["assignments"][0] as ObjectNode
   if (changed) { row.put("outputMode", mode); row.put("continuity", if (mode == "hybrid") "background_ik" else "none"); row.put("useAsIkConstraint", true) }
   assertEquals(expected, after) // Deep equality includes unknown nested keys, sourceSelection, lengths and space.
   val backups = Files.list(dir).use { it.filter { p -> p.fileName.toString().endsWith(".bak") }.toList() }
   assertEquals(if (changed) 1 else 0, backups.size)
   if (changed) assertContentEquals(before, Files.readAllBytes(backups.single())) else assertContentEquals(before, Files.readAllBytes(path))
   assertEquals(response["assignments"][0]["activeMode"], snapshot(api)["assignments"][0]["activeMode"])
   assertTrue(Files.list(dir).use { it.noneMatch { p -> p.fileName.toString().startsWith(".monaka-") } })
  }
  val restarted = MonakaConfiguration.load(path)
  withApi(restarted, path) { api -> val s = snapshot(api)
   assertFalse(s["restartRequired"].asBoolean()); assertEquals(s["assignments"][0]["activeMode"], s["assignments"][0]["savedMode"])
  }
 }
 @ParameterizedTest @ValueSource(booleans = [false, true])
 fun alignmentControlsDisclosureWithoutInventingASaveRestriction(aligned: Boolean, @TempDir dir: Path) {
  val c = config(aligned = aligned); val path = dir.resolve("config.json"); c.save(path)
  withApi(c, path) { api ->
   val s = snapshot(api); assertEquals(aligned, s["backgroundIkAlignmentConfirmed"].asBoolean())
   assertTrue(save(api, s, "hybrid")["ok"].asBoolean()); assertEquals(c.backgroundIkSharedSpace, MonakaConfiguration.load(path).backgroundIkSharedSpace)
  }
 }
 @Test fun pendingRestartFencesPreexistingEditsBothRpcWritersAndLostResponses(@TempDir dir: Path) {
  val c = config(); val path = dir.resolve("config.json"); c.save(path); val external = MonakaConfiguration.load(path)
  withApi(c, path) { api ->
   val s = snapshot(api)
   val edit = api.editSources(TrackerPosition.HIP, q, null, true, s["generation"].asText().toLong(), 1)
   assertTrue(save(api, s, "hybrid")["ok"].asBoolean()) // Pretend this response is lost by the transport.
   val committed = Files.readAllBytes(path)
   assertFails { api.saveAndApply(edit) }; assertFails { external.save(path) }; assertFails { c.save(path) }
   val source = rpc(api, mapOf("action" to "save", "body" to "HIP", "generation" to s["generation"].asText(),
    "qSource" to q.observationId, "sixDofSource" to null, "manualSix" to true))
   assertFalse(source["ok"].asBoolean()); assertTrue(source["restartRequired"].asBoolean())
   assertFalse(save(api, snapshot(api), "direct")["ok"].asBoolean())
   assertTrue(snapshot(api)["restartRequired"].asBoolean()); assertContentEquals(committed, Files.readAllBytes(path))
  }
  withApi(c, path) { api -> // Recreating the API with OLD runtime state is not a restart.
   val s = snapshot(api); assertTrue(s["restartRequired"].asBoolean()); assertEquals("ik", s["assignments"][0]["activeMode"].asText())
  }
 }
 @Test fun dataClassCopyLosesFileProvenanceAndCannotOverwriteCommittedMode(@TempDir dir: Path) {
  val c = config(); val path = dir.resolve("config.json"); c.save(path)
  val staleCopy = c.copy() // Kotlin data-class copy excludes private file provenance.
  val diskCopy = MonakaConfiguration.load(path).copy()
  withApi(c, path) { api ->
   assertTrue(save(api, snapshot(api), "direct")["ok"].asBoolean())
  }
  val committed = Files.readAllBytes(path)
  for (detached in listOf(staleCopy, diskCopy, config())) {
   assertFailsWith<IllegalArgumentException> { detached.save(path) }
   assertContentEquals(committed, Files.readAllBytes(path))
  }
  assertEquals(OutputMode.DIRECT, MonakaConfiguration.load(path).assignments.snapshot()
   .targets.getValue(TrackerPosition.HIP).outputMode)
  // A detached copy remains useful for creating a different, new file.
  val independent = dir.resolve("independent.json")
  staleCopy.save(independent)
  assertEquals(OutputMode.IK, MonakaConfiguration.load(independent).assignments.snapshot()
   .targets.getValue(TrackerPosition.HIP).outputMode)
 }
 @Test fun secondApiWriterCannotOverwriteACommittedMode(@TempDir dir: Path) {
  val c = config(); val path = dir.resolve("config.json"); c.save(path)
  withApi(c, path) { first -> withApi(c, path) { second ->
   val firstSnapshot = snapshot(first); val stale = snapshot(second)
   assertTrue(save(first, firstSnapshot, "direct")["ok"].asBoolean())
   val committed = Files.readAllBytes(path)
   assertFalse(save(second, stale, "hybrid")["ok"].asBoolean()); assertContentEquals(committed, Files.readAllBytes(path))
  } }
 }
 @ParameterizedTest @ValueSource(strings = ["ik", "direct", "hybrid"])
 fun modeEditingPreservesExistingParticipationExceptRequiredHybrid(mode: String, @TempDir dir: Path) {
  val c = config(); val row = c.assignments.snapshot().targets.getValue(TrackerPosition.HIP)
  c.assignments.replaceTargets(mapOf(TrackerPosition.HIP to row.copy(useAsIkConstraint = false)))
  val path = dir.resolve("config.json"); c.save(path)
  withApi(c, path) { api -> assertTrue(save(api, snapshot(api), mode)["ok"].asBoolean()) }
  assertEquals(mode == "hybrid", MonakaConfiguration.load(path).assignments.snapshot().targets.getValue(TrackerPosition.HIP).useAsIkConstraint)
 }
 @Test fun failedAtomicReplacementPreservesPreimageAndRequiresFreshReadbackOnWindows(@TempDir dir: Path) {
  org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
  val c = config(); val path = dir.resolve("config.json"); c.save(path)
  withApi(c, path) { api ->
   val s = snapshot(api); val before = Files.readAllBytes(path)
   Files.setAttribute(path, "dos:readonly", true)
   try {
    val response = save(api, s, "hybrid"); assertFalse(response["ok"].asBoolean()); assertTrue(response["reloadRequired"].asBoolean())
    assertContentEquals(before, Files.readAllBytes(path)); assertFalse(snapshot(api)["restartRequired"].asBoolean())
    assertEquals(OutputMode.IK, c.assignments.snapshot().targets.getValue(TrackerPosition.HIP).outputMode)
    assertTrue(Files.list(dir).use { it.noneMatch { p -> p.fileName.toString().startsWith(".monaka-") } })
    val backup = Files.list(dir).use { it.filter { p -> p.fileName.toString().endsWith(".bak") }.findFirst().orElseThrow() }
    assertContentEquals(before, Files.readAllBytes(backup))
   } finally { Files.setAttribute(path, "dos:readonly", false) }
   assertFalse(save(api, s, "hybrid")["ok"].asBoolean())
   assertTrue(save(api, snapshot(api), "hybrid")["ok"].asBoolean())
  }
 }
 @Test fun truncatedExternalFileAndOversizedRpcNeverCausePartialConfigurationWrite(@TempDir dir: Path) {
  val c = config(); val path = dir.resolve("config.json"); c.save(path)
  withApi(c, path) { api ->
   val s = snapshot(api); val before = Files.readAllBytes(path)
   assertFalse(mapper.readTree(api.handleJson(" ".repeat(65537), 1))["ok"].asBoolean()); assertContentEquals(before, Files.readAllBytes(path))
   Files.writeString(path, "{\"version\":4,")
   assertFalse(save(api, s, "direct")["ok"].asBoolean()); assertEquals("{\"version\":4,", Files.readString(path))
   assertEquals(OutputMode.IK, c.assignments.snapshot().targets.getValue(TrackerPosition.HIP).outputMode)
  }
 }
 @Test fun staleTokenGenerationExternalUpdateAndSimultaneousWritersFailClosed(@TempDir dir: Path) {
  val c = config(); val path = dir.resolve("config.json"); c.save(path)
  withApi(c, path) { api ->
   val before = Files.readAllBytes(path); val s = snapshot(api)
   assertFalse(save(api, (s.deepCopy<JsonNode>() as ObjectNode).put("readbackToken", "wrong"), "direct")["ok"].asBoolean())
   assertFalse(save(api, s, "direct")["ok"].asBoolean()) // Error invalidated the earlier review token.
   val generation = snapshot(api).deepCopy<JsonNode>() as ObjectNode; generation.put("generation", "999")
   assertFalse(save(api, generation, "direct")["ok"].asBoolean()); assertContentEquals(before, Files.readAllBytes(path))
   FileChannel.open(path.resolveSibling("config.json.input-write.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
    ch.lock().use { assertFalse(save(api, snapshot(api), "direct")["ok"].asBoolean()); assertFails { c.save(path) } }
   }
   val readback = snapshot(api)
   Files.writeString(path, Files.readString(path) + "\n")
   val external = Files.readAllBytes(path)
   assertFalse(save(api, readback, "hybrid")["ok"].asBoolean())
   assertFalse(rpc(api, mapOf("action" to "snapshot"))["ok"].asBoolean())
   assertContentEquals(external, Files.readAllBytes(path)); assertEquals(OutputMode.IK, c.assignments.snapshot().targets.getValue(TrackerPosition.HIP).outputMode)
  }
 }
 @ParameterizedTest @ValueSource(ints = [2, 3, 4])
 fun schemaVersionsAreRetained(version: Int, @TempDir dir: Path) {
  val c = config(selected = version == 4); val path = dir.resolve("config.json"); c.save(path)
  val doc = mapper.readTree(path.toFile()) as ObjectNode; doc.put("version", version); Files.writeString(path, doc.toString())
  withApi(c, path) { api -> assertTrue(save(api, snapshot(api), "hybrid")["ok"].asBoolean()) }
  assertEquals(version, mapper.readTree(path.toFile())["version"].asInt())
 }
 @Test fun v1StillRequiresExplicitMigrationAndUnknownSchemaOrModeCannotWrite(@TempDir dir: Path) {
  val path = dir.resolve("legacy.json")
  Files.writeString(path, """{"version":1,"space":{"id":"mode-test-world","revision":8,"convention":"rh_y_up_neg_z_forward"},"assignments":[{"body":"HIP","source_id":"old","tracker_id":"old"}]}""")
  val before = Files.readAllBytes(path); assertFails { MonakaConfiguration.load(path) }
  assertEquals(OutputMode.IK, MonakaConfiguration.load(path, mapOf(("old" to "old") to main.mtp!!)).assignments.snapshot().targets.getValue(TrackerPosition.HIP).outputMode)
  assertContentEquals(before, Files.readAllBytes(path))
  val c = MonakaConfiguration.migrate(path, mapOf(("old" to "old") to main.mtp!!))
  withApi(c, path) { api ->
   for (mode in listOf("DIRECT", "unknown", "", "rotation_guidance")) assertFalse(save(api, snapshot(api), mode)["ok"].asBoolean())
   val missing = rpc(api, mapOf("action" to "save_mode", "body" to "HIP", "mode" to "direct", "generation" to "1"))
   assertFalse(missing["ok"].asBoolean())
   assertFalse(save(api, snapshot(api), "direct", "NONE")["ok"].asBoolean())
   assertFalse(save(api, snapshot(api), "direct", "LEFT_FOOT")["ok"].asBoolean())
  }
 }
 @Test fun absentRoleAndDuplicateBodyRowsCannotBypassExistingValidators(@TempDir dir: Path) {
  val c = config(); val hip = c.assignments.snapshot().targets.getValue(TrackerPosition.HIP)
  c.assignments.replaceTargets(mapOf(TrackerPosition.CHEST to hip))
  val path = dir.resolve("config.json"); c.save(path)
  withApi(c, path) { api ->
   val s = snapshot(api); val options = s["assignments"][0]["modeOptions"]
   assertFalse(options.single { it["mode"].asText() == "direct" }["available"].asBoolean())
   assertFalse(save(api, s, "hybrid", "CHEST")["ok"].asBoolean())
  }
  // Current TrackerPosition has no duplicated non-null role. Duplicate rows still fail closed at load.
  val roles = TrackerPosition.values().mapNotNull { it.trackerRole }; assertEquals(roles.size, roles.distinct().size)
  val doc = mapper.readTree(path.toFile()) as ObjectNode
  (doc["assignments"] as com.fasterxml.jackson.databind.node.ArrayNode).add(doc["assignments"][0].deepCopy<JsonNode>())
  Files.writeString(path, doc.toString()); assertFails { MonakaConfiguration.load(path) }
 }
 @Test fun correctionBindingAndExistingDirectHybridHotEditRestrictionsRemain(@TempDir dir: Path) {
  for (mode in listOf(OutputMode.DIRECT, OutputMode.HYBRID)) {
   val c = config(mode); val path = dir.resolve("$mode.json"); c.save(path)
   withApi(c, path) { api ->
    assertFails { api.saveAndApply(api.editSources(TrackerPosition.HIP, q, null, true, c.assignments.snapshot().generation, 1)) }
   }
  }
  val c = config(OutputMode.HYBRID, aligned = true).let { it.copy(rotationCorrection = RotationCorrectionConfig(
   RotationCorrectionFrames(Quaternion.IDENTITY, Quaternion.IDENTITY, space, true),
   RotationCorrectionTuning(2_000_000, 1_000_000, .1, .2, 1.0, .1, 2.0, 2, 50_000_000, 100_000_000))) }
  val path = dir.resolve("correction.json"); c.save(path); val before = Files.readAllBytes(path)
  withApi(c, path) { api ->
   assertFalse(save(api, snapshot(api), "ik")["ok"].asBoolean()); assertContentEquals(before, Files.readAllBytes(path))
  }
 }
 @Test fun queuedCancellationAndWrongThreadNeverCommit(@TempDir dir: Path) {
  val c = config(); val path = dir.resolve("config.json"); c.save(path)
  withApi(c, path) { api ->
   val s = snapshot(api); val before = Files.readAllBytes(path)
   val request = mapper.writeValueAsString(mapOf("type" to "monaka_inputs_v1", "id" to "cancel", "action" to "save_mode",
    "body" to "HIP", "mode" to "direct", "generation" to s["generation"].asText(), "readbackToken" to s["readbackToken"].asText()))
   val tasks = mutableListOf<Runnable>(); var connected = true; var calls = 0
   val dispatcher = MonakaInputRpcDispatcher({ tasks += it }, { { calls++; api.handleJson(it, 1) } })
   dispatcher.receive(request, true, { connected }, { error("Disconnected client must not receive a response") })
   connected = false; tasks.single().run(); assertEquals(0, calls); assertContentEquals(before, Files.readAllBytes(path))
   var failure: Throwable? = null
   Thread { try { api.saveMode(TrackerPosition.HIP, OutputMode.DIRECT, 1, s["readbackToken"].asText()) } catch (e: Throwable) { failure = e } }.also { it.start(); it.join() }
   assertIs<IllegalStateException>(failure); assertContentEquals(before, Files.readAllBytes(path))
  }
 }
 @Test fun subprocessClasspathForGuiIntegration() {
  val file = Path.of("build", "mode-test-classpath.txt"); Files.createDirectories(file.parent)
  val paths = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<java.net.URLClassLoader>()
   .flatMap { it.urLs.asSequence() }.filter { it.protocol == "file" }.map { Path.of(it.toURI()).toString() }.distinct().toList()
  val classpath = if (paths.isEmpty()) System.getProperty("java.class.path") else paths.joinToString(java.io.File.pathSeparator)
  Files.writeString(file, classpath)
 }
}
