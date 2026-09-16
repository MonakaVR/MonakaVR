package dev.monaka.tracking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Versioned local composition settings, independent of wire codec and body-free identity. */
data class MonakaConfiguration(
 val space: CoordinateSpace,
 val assignments: TrackerBodyAssignments = TrackerBodyAssignments(),
 val port: Int = 29811,
 val timeoutNanos: Long = 500_000_000,
) {
 init {
  require(space.id.isNotBlank() && space.convention == "rh_y_up_neg_z_forward" && space.revision in 0..4294967295L)
  require(port in 0..65535 && timeoutNanos >= 0)
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
   "assignments" to assignments.snapshot().targets.map { (body, relation) ->
    mapOf("body" to body.name, "mainTracker" to reference(relation.mainTracker),
     "rotationFallbackTracker" to relation.rotationFallbackTracker?.let(::reference))
   },
  )
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
     TrackerPosition.valueOf(text(it, "body")) to MainTrackerAssignment(reference(requireNotNull(it["mainTracker"])), fallback)
    }
    require(targets.map { it.first }.distinct().size == targets.size)
    assignments.replaceTargets(targets.toMap())
   }
   val port = root["port"]?.let { require(it.isIntegralNumber && it.canConvertToInt()); it.asInt() } ?: 29811
   val timeout = root["timeout_ns"]?.let { require(it.isTextual); it.asText().toLong() } ?: 500_000_000
   return MonakaConfiguration(CoordinateSpace(text(space, "id"), text(space, "convention"), revision.asLong()), assignments, port, timeout)
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
