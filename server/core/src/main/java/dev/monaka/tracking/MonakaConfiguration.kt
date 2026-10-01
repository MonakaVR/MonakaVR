package dev.monaka.tracking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Versioned local composition settings, independent of wire codec and body-free identity. */
data class MonakaConfiguration(
 val space: CoordinateSpace,
 val assignments: TrackerBodyAssignments = TrackerBodyAssignments(),
 val port: Int = 29811,
 val timeoutNanos: Long = 500_000_000,
 val backgroundIkSharedSpace: CoordinateSpace? = null,
 val continuityTuning: ContinuityTuning = ContinuityTuning(),
 val rotationCorrection: RotationCorrectionConfig? = null,
) {
 init {
  require(space.id.isNotBlank() && space.convention == "rh_y_up_neg_z_forward" && space.revision in 0..4294967295L)
  require(port in 0..65535 && timeoutNanos >= 0)
  require(backgroundIkSharedSpace == null || backgroundIkSharedSpace == space) { "Background IK shared-space assertion must match the configured MTP space/revision" }
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
 fun save(path: Path) {
  fun reference(ref: TrackerReference): Map<String, String> = ref.mtp?.let {
   mapOf("kind" to "mtp", "publisher_id" to it.publisherId, "source_id" to it.sourceId, "tracker_id" to it.trackerId)
  } ?: run {
   require(ref.observationId.startsWith("slime:"))
   mapOf("kind" to "slime", "name" to ref.observationId.removePrefix("slime:"))
  }
  val document = mapOf(
   "version" to 2, "port" to port, "timeout_ns" to timeoutNanos.toString(),
   "space" to mapOf("id" to space.id, "revision" to space.revision, "convention" to space.convention),
   "backgroundIkAlignment" to backgroundIkSharedSpace?.let {
    mapOf("kind" to "confirmed_same_space", "space" to mapOf("id" to it.id, "revision" to it.revision, "convention" to it.convention))
   },
   "continuityTuning" to mapOf("stableFullDwellMs" to continuityTuning.stableFullDwellMs,
    "reacquireDurationMs" to continuityTuning.reacquireDurationMs, "fallbackBlendMs" to continuityTuning.fallbackBlendMs),
   "assignments" to assignments.snapshot().targets.map { (body, relation) ->
    mapOf("body" to body.name, "outputMode" to relation.outputMode.name.lowercase(), "mainTracker" to reference(relation.mainTracker),
     "rotationFallbackTracker" to relation.rotationFallbackTracker?.let(::reference),
     "useAsIkConstraint" to relation.useAsIkConstraint, "continuity" to relation.continuity.name.lowercase())
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
  )) } ?: emptyMap())
  val absolute = path.toAbsolutePath()
  Files.createDirectories(absolute.parent)
  val temporary = absolute.resolveSibling(absolute.fileName.toString() + ".pending")
  Files.write(temporary, ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(document))
  Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
 }
 companion object {
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
   val root = ObjectMapper().readTree(path.toFile())
   val version = root["version"]
   require(version?.isIntegralNumber == true && version.asInt() in 1..2)
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
     TrackerPosition.valueOf(text(it, "body")) to MainTrackerAssignment(reference(requireNotNull(it["mainTracker"])), fallback, mode, participation, continuity)
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
   return MonakaConfiguration(expectedSpace, assignments, port, timeout, shared, tuning, correction)
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
