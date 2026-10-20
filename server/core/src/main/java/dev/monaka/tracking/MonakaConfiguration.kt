package dev.monaka.tracking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Versioned local composition settings, independent of wire codec and body-free identity. */
data class MonakaConfiguration(
 val space: CoordinateSpace,
 val assignments: TrackerBodyAssignments = TrackerBodyAssignments(),
 val port: Int = 29811,
 val timeoutNanos: Long = 500_000_000,
 val backgroundIkSharedSpace: CoordinateSpace? = null,
 val continuityTuning: ContinuityTuning = ContinuityTuning(),
 val rotationCorrection: RotationCorrectionConfig? = null,
 val positionCorrection: PositionCorrectionConfig? = null,
) {
 // A loaded writer must not overwrite a newer API transaction with its old runtime state.
 private var loadedPath: Path? = null
 private var loadedBytes: ByteArray? = null
 init {
  require(space.id.isNotBlank() && space.convention == "rh_y_up_neg_z_forward" && space.revision in 0..4294967295L)
  require(port in 0..65535 && timeoutNanos >= 0)
  require(backgroundIkSharedSpace == null || backgroundIkSharedSpace == space) { "Background IK shared-space assertion must match the configured MTP space/revision" }
  validateSixDofTeacherSelection()
  positionCorrection?.validateBinding(space, assignments.snapshot().targets[TrackerPosition.HIP])
  if (rotationCorrection != null) {
   require(backgroundIkSharedSpace == space && rotationCorrection.frames.assertedSpace == space) {
    "Rotation correction requires explicit shared-space assertion"
   }
   val hip = assignments.snapshot().targets[TrackerPosition.HIP]
   require(hip?.outputMode == OutputMode.HYBRID && hip.rotationFallbackTracker != null && hip.useAsIkConstraint) {
    "Rotation correction requires assigned HIP Hybrid Main and rotation fallback"
   }
   require(hip.mainTracker.mtp != null && hip.rotationFallbackTracker.mtp == null &&
    hip.rotationFallbackTracker.observationId.startsWith("slime:")) {
    "Phase 1 requires MTP Main and explicitly assigned physical Slime IMU fallback"
   }
  }
 }
 private fun validateSixDofTeacherSelection() {
  if (rotationCorrection != null || positionCorrection != null) {
   val selection = assignments.snapshot().targets[TrackerPosition.HIP]?.sourceSelection
   require(selection == null || selection.sixDofSource != null) {
    "Correction requires an explicit 6DoF teacher; a Q-only assignment has no position teacher"
   }
  }
 }
 fun save(path: Path) {
  val absolute = path.toAbsolutePath().normalize()
  Files.createDirectories(absolute.parent)
  require(!Files.isSymbolicLink(absolute)) { "Configuration aliases require offline review" }
  // A generated data-class copy does not inherit loadedPath/loadedBytes.
  // Existing populated files require file provenance; empty pre-created files
  // (for example Files.createTempFile) may be initialized, but only if still empty
  // when the lock is acquired and immediately before atomic replacement.
  val ownsExistingFile = loadedPath == absolute && loadedBytes != null
  val initializingEmptyFile = !ownsExistingFile && Files.isRegularFile(absolute) && Files.size(absolute) == 0L
  require(ownsExistingFile || initializingEmptyFile || !Files.exists(absolute)) {
   "Existing configuration must be loaded from this path before replacement"
  }
  val preimage = when {
   ownsExistingFile -> loadedBytes
   initializingEmptyFile -> ByteArray(0)
   else -> null
  }
  withWriteLock(absolute) {
   fun unchanged() = if (preimage == null) !Files.exists(absolute) else
    Files.isRegularFile(absolute) && Files.readAllBytes(absolute).contentEquals(preimage)
   require(unchanged()) { "Configuration file changed; reload/restart before saving" }
   // Generate separately so preimage is checked again immediately before replacement.
   val stage = absolute.resolveSibling(".monaka-config-${java.util.UUID.randomUUID()}.json")
   try {
    saveUnmanaged(stage)
    require(unchanged()) { "Configuration changed before commit" }
    if (assignments.snapshot().targets.values.any { it.sourceSelection != null } && preimage != null) {
     val version = ObjectMapper().readTree(preimage)["version"]
     require(version?.isIntegralNumber == true && version.canConvertToInt() && version.asInt() in 2..4) {
      "Validate/migrate legacy configuration before saving capability selections"
     }
     if (version.asInt() < 4) Files.write(absolute.resolveSibling(absolute.fileName.toString() + ".pre-capability-selection-v4.bak"),
      preimage, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
    val written = Files.readAllBytes(stage)
    FileChannel.open(stage, StandardOpenOption.WRITE).use { it.force(true) }
    require(unchanged()) { "Configuration changed before commit" }
    Files.move(stage, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    loadedPath = absolute; loadedBytes = written
   } finally {
    runCatching { Files.deleteIfExists(stage) }
    runCatching { Files.deleteIfExists(stage.resolveSibling(stage.fileName.toString() + ".pending")) }
   }
  }
 }
 /** Only for isolated staging files held by the shared transaction lock. */
 internal fun saveUnmanaged(path: Path) {
  validateSixDofTeacherSelection()
  val assignmentSnapshot = assignments.snapshot()
  positionCorrection?.validateBinding(space, assignmentSnapshot.targets[TrackerPosition.HIP])
  fun reference(ref: TrackerReference): Map<String, String> = ref.mtp?.let {
   mapOf("kind" to "mtp", "publisher_id" to it.publisherId, "source_id" to it.sourceId, "tracker_id" to it.trackerId)
  } ?: run {
   require(ref.observationId.startsWith("slime:"))
   mapOf("kind" to "slime", "name" to ref.observationId.removePrefix("slime:"))
  }
  val document = mapOf(
   "version" to (if (assignmentSnapshot.targets.values.any { it.sourceSelection != null }) 4 else 3), "port" to port, "timeout_ns" to timeoutNanos.toString(),
   "space" to mapOf("id" to space.id, "revision" to space.revision, "convention" to space.convention),
   "backgroundIkAlignment" to backgroundIkSharedSpace?.let {
    mapOf("kind" to "confirmed_same_space", "space" to mapOf("id" to it.id, "revision" to it.revision, "convention" to it.convention))
   },
   "continuityTuning" to mapOf("stableFullDwellMs" to continuityTuning.stableFullDwellMs,
    "reacquireDurationMs" to continuityTuning.reacquireDurationMs, "fallbackBlendMs" to continuityTuning.fallbackBlendMs),
   "assignments" to assignmentSnapshot.targets.map { (body, relation) ->
    mapOf("body" to body.name, "outputMode" to relation.outputMode.name.lowercase(), "mainTracker" to reference(relation.mainTracker),
     "rotationFallbackTracker" to relation.rotationFallbackTracker?.let(::reference),
     "useAsIkConstraint" to relation.useAsIkConstraint, "continuity" to relation.continuity.name.lowercase()) +
     (relation.sourceSelection?.let { selected -> mapOf("sourceSelection" to mapOf(
      "qSource" to selected.qSource?.let(::reference), "sixDofSource" to selected.sixDofSource?.let(::reference),
      "sixDofManuallySelected" to selected.sixDofManuallySelected)) } ?: emptyMap())
   },
  ) + (rotationCorrection?.let { correction -> mapOf("rotationCorrection" to mapOf(
   "enabled" to true,
   "framesConfirmed" to correction.frames.operatorConfirmed,
   // Config quaternions use w,x,y,z, unlike MTP wire xyzw. Explicit values are mandatory.
   "mainTrackerToBodyWxyz" to listOf(correction.frames.mainTrackerToBody.w, correction.frames.mainTrackerToBody.x,
    correction.frames.mainTrackerToBody.y, correction.frames.mainTrackerToBody.z),
   "fallbackToBodyWxyz" to listOf(correction.frames.fallbackToBody.w, correction.frames.fallbackToBody.x,
    correction.frames.fallbackToBody.y, correction.frames.fallbackToBody.z),
   "fullStableNanos" to correction.tuning.fullStableNanos,
   "pairWindowNanos" to correction.tuning.pairWindowNanos,
   "trackingTauSeconds" to correction.tuning.trackingTauSeconds,
   "recoveryTauSeconds" to correction.tuning.recoveryTauSeconds,
   "maxResidualRadians" to correction.tuning.maxResidualRadians,
   "recoveryResidualRadians" to correction.tuning.recoveryResidualRadians,
   "maxRadiansPerSecond" to correction.tuning.maxRadiansPerSecond,
   "recoveryPairs" to correction.tuning.recoveryPairs,
   "maxDtNanos" to correction.tuning.maxDtNanos,
   "maxImuSampleAgeNanos" to correction.tuning.maxImuSampleAgeNanos,
  )) } ?: emptyMap()) + (positionCorrection?.let { mapOf("positionCorrection" to PositionCorrectionConfigJson.write(it)) } ?: emptyMap())
  val absolute = path.toAbsolutePath()
  Files.createDirectories(absolute.parent)
  if (assignmentSnapshot.targets.values.any { it.sourceSelection != null } && Files.exists(absolute)) {
   val previousVersion = ObjectMapper().readTree(absolute.toFile())["version"]
   require(previousVersion?.isIntegralNumber == true && previousVersion.canConvertToInt() && previousVersion.asInt() in 2..4) {
    "Validate/migrate legacy configuration before saving capability selections"
   }
   if (previousVersion.asInt() < 4) {
    Files.copy(absolute, absolute.resolveSibling(absolute.fileName.toString() + ".pre-capability-selection-v4.bak"))
   }
  }
  val temporary = absolute.resolveSibling(absolute.fileName.toString() + ".pending")
  Files.write(temporary, ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(document))
  Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
 }
 companion object {
  internal fun <T> withWriteLock(path: Path, block: () -> T): T {
   val lockPath = path.resolveSibling(path.fileName.toString() + ".input-write.lock")
   val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
   var lock: java.nio.channels.FileLock? = null
   try {
    lock = channel.tryLock() ?: error("Configuration is being saved; reload and retry")
    return block()
   } finally {
    // A cleanup failure after atomic replacement must not report a rolled-back save.
    runCatching { lock?.release() }; runCatching { channel.close() }
   }
  }
  private fun text(node: JsonNode, name: String): String {
   val value = node[name]
   require(value?.isTextual == true && value.asText().isNotBlank()) { "Missing/invalid $name" }
   return value.asText()
  }
  private fun reference(node: JsonNode): TrackerReference = when (text(node, "kind")) {
   "mtp" -> TrackerReference.mtp(LogicalTracker(text(node, "source_id"), text(node, "tracker_id"), text(node, "publisher_id")))
   "slime" -> TrackerReference.slime(text(node, "name"))
   else -> error("Unknown tracker reference")
  }
  /** v1 source_id was a collapsed Bridge namespace: require an explicit full-identity mapping. */
  fun load(path: Path, legacyMapping: Map<Pair<String, String>, LogicalTracker> = emptyMap()): MonakaConfiguration {
   require(Files.isRegularFile(path)) { "MTP requires explicit world/assignments in $path" }
   return loadBytes(path, Files.readAllBytes(path), legacyMapping)
  }
  internal fun loadBytes(path: Path, bytes: ByteArray, legacyMapping: Map<Pair<String, String>, LogicalTracker> = emptyMap()): MonakaConfiguration {
   val root = ObjectMapper().readTree(bytes)
   val version = root["version"]
   require(version?.isIntegralNumber == true && version.canConvertToInt() && version.asInt() in 1..4)
   require(version.asInt() >= 3 || !root.has("positionCorrection")) { "Position correction requires schema v3+" }
   val space = requireNotNull(root["space"])
   val revision = space["revision"]
   require(revision != null && revision.isIntegralNumber && revision.canConvertToLong())
   val entries = requireNotNull(root["assignments"]); require(entries.isArray)
   val assignments = TrackerBodyAssignments()
   if (version.asInt() == 1) {
    val legacy = entries.map {
     val key = text(it, "source_id") to text(it, "tracker_id")
     requireNotNull(legacyMapping[key]) { "Explicit publisher/source/tracker migration required for $key" } to TrackerPosition.valueOf(text(it, "body"))
    }
    require(legacy.map { it.first }.distinct().size == legacy.size)
    assignments.replace(legacy.toMap())
   } else {
    val targets = entries.map {
     val fallback = it["rotationFallbackTracker"]?.takeUnless { value -> value.isNull }?.let(::reference)
     val mode = it["outputMode"]?.let { value ->
      require(value.isTextual) { "outputMode must be ik, direct or hybrid" }
      when (value.asText()) { "ik" -> OutputMode.IK; "direct" -> OutputMode.DIRECT; "hybrid" -> OutputMode.HYBRID; else -> error("Unknown outputMode") }
     } ?: OutputMode.IK
     val participation = it["useAsIkConstraint"]?.let { value -> require(value.isBoolean); value.booleanValue() } ?: true
     val continuity = it["continuity"]?.let { value ->
      require(value.isTextual)
      when (value.asText()) { "none" -> ContinuityPolicy.NONE; "background_ik" -> ContinuityPolicy.BACKGROUND_IK; else -> error("Unknown continuity") }
     } ?: if (mode == OutputMode.HYBRID) ContinuityPolicy.BACKGROUND_IK else ContinuityPolicy.NONE
     val selection = it["sourceSelection"]?.let { selected ->
      require(version.asInt() == 4 && selected.isObject) { "sourceSelection requires config v4" }
      require(selected.fieldNames().asSequence().toSet() == setOf("qSource", "sixDofSource", "sixDofManuallySelected"))
      val manual = requireNotNull(selected["sixDofManuallySelected"])
      require(manual.isBoolean)
      QSixDofSourceSelection(selected["qSource"]?.takeUnless { value -> value.isNull }?.let(::reference),
       selected["sixDofSource"]?.takeUnless { value -> value.isNull }?.let(::reference), manual.booleanValue())
     }
     TrackerPosition.valueOf(text(it, "body")) to MainTrackerAssignment(reference(requireNotNull(it["mainTracker"])), fallback, mode, participation, continuity, selection)
    }
    require(targets.map { it.first }.distinct().size == targets.size)
    assignments.replaceTargets(targets.toMap())
   }
   val port = root["port"]?.let { require(it.isIntegralNumber && it.canConvertToInt()); it.asInt() } ?: 29811
   val timeout = root["timeout_ns"]?.let { require(it.isTextual); it.asText().toLong() } ?: 500_000_000
   val shared = root["backgroundIkAlignment"]?.takeUnless { it.isNull }?.let { alignment ->
    require(text(alignment, "kind") == "confirmed_same_space") { "Unsupported background IK alignment" }
    val declared = requireNotNull(alignment["space"])
    val declaredRevision = requireNotNull(declared["revision"])
    require(declaredRevision.isIntegralNumber && declaredRevision.canConvertToLong())
    CoordinateSpace(text(declared, "id"), text(declared, "convention"), declaredRevision.longValue())
   }
   val tuning = root["continuityTuning"]?.takeUnless { it.isNull }?.let { node ->
    require(node.isObject) { "continuityTuning must be an object" }
    require(node.fieldNames().asSequence().all { it in setOf("stableFullDwellMs", "reacquireDurationMs", "fallbackBlendMs") }) {
     "Unknown continuityTuning field"
    }
    fun duration(name: String, fallback: Long): Long = node[name]?.let { value ->
     require(value.isIntegralNumber && value.canConvertToLong()) { "$name must be an integer" }
     value.longValue()
    } ?: fallback
    val defaults = ContinuityTuning()
    ContinuityTuning(duration("stableFullDwellMs", defaults.stableFullDwellMs),
     duration("reacquireDurationMs", defaults.reacquireDurationMs), duration("fallbackBlendMs", defaults.fallbackBlendMs))
   } ?: ContinuityTuning()
   val expectedSpace = CoordinateSpace(text(space, "id"), text(space, "convention"), revision.asLong())
   val correction = root["rotationCorrection"]?.takeUnless { it.isNull }?.let { node ->
    require(node.isObject && node["enabled"]?.isBoolean == true) { "rotationCorrection.enabled must be boolean" }
    if (!node["enabled"].booleanValue()) null else {
     val fields = setOf("enabled", "framesConfirmed", "mainTrackerToBodyWxyz", "fallbackToBodyWxyz", "fullStableNanos", "pairWindowNanos",
      "trackingTauSeconds", "recoveryTauSeconds", "maxResidualRadians", "recoveryResidualRadians",
      "maxRadiansPerSecond", "recoveryPairs", "maxDtNanos", "maxImuSampleAgeNanos")
     require(node.fieldNames().asSequence().all { it in fields }) { "Unknown rotationCorrection field" }
     fun quat(name: String): Quaternion {
      val values = node[name]
      require(values?.isArray == true && values.size() == 4 && values.all { it.isNumber && it.asDouble().isFinite() }) {
       "$name must contain four finite wxyz numbers"
      }
      return Quaternion(values[0].floatValue(), values[1].floatValue(), values[2].floatValue(), values[3].floatValue())
     }
     fun long(name: String): Long = node[name]?.let { require(it.isIntegralNumber && it.canConvertToLong()); it.longValue() }
      ?: error("Missing $name")
     fun double(name: String): Double = node[name]?.let { require(it.isNumber && it.asDouble().isFinite()); it.asDouble() }
      ?: error("Missing $name")
     require(node["framesConfirmed"]?.isBoolean == true && node["framesConfirmed"].booleanValue()) {
      "rotationCorrection.framesConfirmed must be explicitly true"
     }
     val recoveryPairs = node["recoveryPairs"]
     require(recoveryPairs != null && recoveryPairs.isIntegralNumber && recoveryPairs.canConvertToInt()) { "recoveryPairs must be an integer" }
     RotationCorrectionConfig(RotationCorrectionFrames(quat("mainTrackerToBodyWxyz"), quat("fallbackToBodyWxyz"), expectedSpace, true),
      RotationCorrectionTuning(long("fullStableNanos"), long("pairWindowNanos"), double("trackingTauSeconds"),
       double("recoveryTauSeconds"), double("maxResidualRadians"), double("recoveryResidualRadians"),
       double("maxRadiansPerSecond"), recoveryPairs.intValue(), long("maxDtNanos"), long("maxImuSampleAgeNanos")))
    }
   }
   val positionCorrection = root["positionCorrection"]?.let(PositionCorrectionConfigJson::read)
   return MonakaConfiguration(expectedSpace, assignments, port, timeout, shared, tuning, correction, positionCorrection).also {
    it.loadedPath = path.toAbsolutePath().normalize(); it.loadedBytes = bytes
   }
  }
  fun migrate(path: Path, legacyMapping: Map<Pair<String, String>, LogicalTracker>): MonakaConfiguration {
   val config = load(path, legacyMapping) // Validate everything before any write.
   val backup = path.resolveSibling(path.fileName.toString() + ".pre-c2.bak")
   Files.copy(path, backup) // Refuse to overwrite an earlier backup.
   config.save(path)
   return config
  }
 }
}
