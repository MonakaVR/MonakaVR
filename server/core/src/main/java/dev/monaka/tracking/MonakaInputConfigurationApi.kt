package dev.monaka.tracking

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.monaka.tracking.mtp.MtpObservationBackend
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.UUID

/** Server-owned configuration boundary. Tracking wires and fusion policy are unchanged. */
class MonakaInputConfigurationApi(
 private val configuration: MonakaConfiguration,
 private val backend: MtpObservationBackend,
 private val trackers: () -> Iterable<Tracker>,
 path: Path,
) {
 private val path = path.toAbsolutePath().normalize()
 private val mapper = ObjectMapper()
 private var expectedFile = readBounded(this.path)
 private var owner: Thread? = null
 private fun checkThread() {
  if (owner == null) owner = Thread.currentThread()
  check(owner === Thread.currentThread()) { "Input configuration requires the server thread" }
 }
 data class Candidate(val reference: TrackerReference, val capabilities: DeclaredTrackerCapabilities?,
  val conflict: Boolean, val qSelectable: Boolean, val sixDofSelectable: Boolean,
  val qUsableNow: Boolean, val sixDofUsableNow: Boolean, val origin: String)
 data class Snapshot(val generation: Long, val candidates: List<Candidate>,
  val assignments: Map<TrackerPosition, MainTrackerAssignment>)
 class Edit internal constructor(internal val generation: Long, val assignments: Map<TrackerPosition, MainTrackerAssignment>)
 fun snapshot(now: Long): Snapshot {
  checkThread(); require(now >= 0)
  val state = configuration.assignments.snapshot()
  val mtp = backend.capabilitiesSnapshot().values.map { source ->
   val fresh = source.freshAt(now, configuration.timeoutNanos)
   Candidate(TrackerReference.mtp(source.tracker), source.capabilities, source.conflict,
    !source.conflict && source.capabilities?.supportsQ == true,
    !source.conflict && source.capabilities?.supportsSixDof == true,
    fresh && source.orientationValid, fresh && source.positionValid && source.orientationValid && source.modality == "full",
    "mvip_pose")
  }
  val adapter = SlimeTrackerPoseObservationAdapter()
  val slime = trackers().filter(FeedbackExclusion::accepts).filter { it.hasRotation }.groupBy { it.name }.map { (name, matches) ->
   val sample = matches.singleOrNull()?.let { adapter.adapt(it, now, TrackerPosition.HEAD) }
   Candidate(TrackerReference.slime(name), DeclaredTrackerCapabilities.orientationOnly(), matches.size != 1,
    matches.size == 1, false, sample?.rotation != null && sample.rotationQuality.usable, false, "slime_orientation_stream")
  }
  return Snapshot(state.generation, Collections.unmodifiableList(mtp + slime), state.targets)
 }
 fun editSources(target: TrackerPosition, q: TrackerReference?, six: TrackerReference?, manualSix: Boolean,
  generation: Long, now: Long): Edit {
  val snapshot = snapshot(now); require(target.name != "NONE")
  require(snapshot.generation == generation) { "Assignments changed; reload before saving" }
  val qCandidate = q?.let { ref -> snapshot.candidates.singleOrNull { it.reference == ref } }
  val sixCandidate = six?.let { ref -> snapshot.candidates.singleOrNull { it.reference == ref } }
  val current = snapshot.assignments[target]
  val currentQ = current?.let { if (it.sourceSelection != null) it.sourceSelection.qSource else it.rotationFallbackTracker ?: it.mainTracker }
  val currentSix = current?.let { if (it.sourceSelection != null) it.sourceSelection.sixDofSource else it.mainTracker.takeIf { ref -> ref.mtp != null } }
  require(q == null || qCandidate?.qSelectable == true || q == currentQ) { "Q Source has unknown/conflicting orientation support" }
  require(six == null || sixCandidate?.sixDofSelectable == true || six == currentSix) { "6DoF Source requires declared position and orientation" }
  if (!manualSix) {
   val permitted = if (current == null) q?.takeIf { qCandidate?.capabilities?.supportsSixDof == true && qCandidate.origin == "mvip_pose" }
    else currentSix
   require(six == permitted) { "Automatic 6DoF choice is permitted only on the first Q selection" }
  }
  val candidate = TrackerBodyAssignments().also { it.replaceTargets(snapshot.assignments) }
  candidate.configureSources(target, q, six, manualSix)
  return Edit(generation, candidate.snapshot().targets)
 }
 /** Persist first, then publish the already-validated assignment snapshot. */
 fun saveAndApply(edit: Edit): TrackerBodyAssignments.Snapshot = synchronized(configuration.assignments) {
  checkThread()
  val current = configuration.assignments.snapshot()
  require(current.generation == edit.generation && current.generation < Long.MAX_VALUE) { "Assignments changed; reload before saving" }
  val affected = (current.targets.keys + edit.assignments.keys).filter { current.targets[it] != edit.assignments[it] }
  if (affected.isEmpty()) return@synchronized current
  require(affected.none { current.targets[it]?.outputMode?.let { m -> m != OutputMode.IK } == true ||
   edit.assignments[it]?.outputMode?.let { m -> m != OutputMode.IK } == true } &&
   configuration.rotationCorrection == null && configuration.positionCorrection == null) {
   "This output/correction assignment requires a server restart"
  }
  val next = TrackerBodyAssignments().also { it.replaceTargets(edit.assignments) }
  val candidate = configuration.copy(assignments = next) // All existing configuration validators.
  require(!Files.isSymbolicLink(path)) { "Configuration aliases require offline review" }
  val parent = path.parent.toRealPath()
  val stage = parent.resolve(".monaka-input-${UUID.randomUUID()}.json")
  val channel = FileChannel.open(parent.resolve(path.fileName.toString() + ".input-write.lock"),
   StandardOpenOption.CREATE, StandardOpenOption.WRITE)
  var lock: java.nio.channels.FileLock? = null
  try {
   lock = channel.tryLock() ?: error("Configuration is being saved; reload and retry")
   require(readBounded(path).contentEquals(expectedFile)) { "Configuration file changed; reload/restart before saving" }
   candidate.save(stage)
   val generated = mapper.readTree(stage.toFile())
   val document = mapper.readTree(expectedFile) as ObjectNode
   document.set<com.fasterxml.jackson.databind.JsonNode>("version", generated["version"])
   val oldByBody = document["assignments"].associateBy { it["body"].asText() }
   val assignments = mapper.createArrayNode()
   for (node in generated["assignments"]) {
    val row = (oldByBody[node["body"].asText()] as? ObjectNode)?.deepCopy() ?: mapper.createObjectNode()
    row.setAll<ObjectNode>(node as ObjectNode)
    if (!node.has("sourceSelection")) row.remove("sourceSelection")
    assignments.add(row)
   }
   document.set<com.fasterxml.jackson.databind.JsonNode>("assignments", assignments)
   val bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document)
   require(bytes.size <= MAX_BYTES)
   Files.write(stage, bytes, StandardOpenOption.TRUNCATE_EXISTING)
   Files.write(parent.resolve(path.fileName.toString() + ".pre-input-edit-${UUID.randomUUID()}.bak"),
    expectedFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
   require(readBounded(path).contentEquals(expectedFile)) { "Configuration changed before commit" }
   Files.move(stage, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
   expectedFile = bytes
  } finally {
   // Cleanup cannot turn a completed commit into a reported cancellation/failure.
   runCatching { lock?.release() }; runCatching { channel.close() }
   runCatching { Files.deleteIfExists(stage) }; runCatching { Files.deleteIfExists(stage.resolveSibling(stage.fileName.toString() + ".pending")) }
  }
  configuration.assignments.replaceTargets(edit.assignments)
  configuration.assignments.snapshot()
 }
 fun handleJson(raw: String, now: Long): String {
  checkThread()
  var id = ""
  return try {
   require(raw.length <= 65536)
   val root = mapper.readTree(raw)
   require(root["type"]?.asText() == "monaka_inputs_v1")
   require(root["id"]?.isTextual == true && root["id"].asText().length in 1..64)
   id = root["id"].asText()
   if (root["action"]?.asText() == "save") {
    val current = snapshot(now)
    val target = TrackerPosition.valueOf(root["body"].asText())
    val existing = current.assignments[target]?.let { listOfNotNull(it.mainTracker, it.rotationFallbackTracker) }.orEmpty()
    fun reference(field: String): TrackerReference? {
     val value = requireNotNull(root[field]); if (value.isNull) return null
     require(value.isTextual)
     return current.candidates.singleOrNull { it.reference.observationId == value.asText() }?.reference
      ?: existing.singleOrNull { it.observationId == value.asText() }
      ?: error("Source unavailable/ambiguous; reload candidates")
    }
    require(root["generation"]?.isTextual == true && root["manualSix"]?.isBoolean == true)
    val edit = editSources(target, reference("qSource"), reference("sixDofSource"),
     root["manualSix"].booleanValue(), root["generation"].asText().toLong(), now)
    saveAndApply(edit)
   } else require(root["action"]?.asText() == "snapshot")
   val s = snapshot(now)
   val data = mapOf("type" to "monaka_inputs_v1", "id" to id, "ok" to true, "generation" to s.generation.toString(),
    "bodies" to TrackerPosition.values().filter { it.name != "NONE" }.map { it.name },
    "candidates" to s.candidates.map { x -> mapOf("id" to x.reference.observationId,
     "publisher" to x.reference.mtp?.publisherId, "source" to x.reference.mtp?.sourceId,
     "tracker" to (x.reference.mtp?.trackerId ?: x.reference.observationId.removePrefix("slime:")),
     "capabilities" to x.capabilities?.components, "conflict" to x.conflict,
     "qSelectable" to x.qSelectable, "sixDofSelectable" to x.sixDofSelectable,
     "qUsableNow" to x.qUsableNow, "sixDofUsableNow" to x.sixDofUsableNow, "origin" to x.origin) },
    "assignments" to s.assignments.map { (body, a) -> mapOf("body" to body.name,
     "qSource" to (a.sourceSelection?.qSource?.observationId ?: if (a.sourceSelection == null) (a.rotationFallbackTracker ?: a.mainTracker).observationId else null),
     "sixDofSource" to (a.sourceSelection?.sixDofSource?.observationId ?: if (a.sourceSelection == null && a.mainTracker.mtp != null) a.mainTracker.observationId else null),
     "manualSix" to (a.sourceSelection?.sixDofManuallySelected ?: true), "mode" to a.outputMode.name.lowercase()) })
   mapper.writeValueAsString(data).also { require(it.length <= MAX_BYTES) }
  } catch (e: Exception) {
   mapper.writeValueAsString(mapOf("type" to "monaka_inputs_v1", "id" to id, "ok" to false,
    "error" to (e.message ?: "Input configuration failed").take(240)))
  }
 }
 companion object {
  private const val MAX_BYTES = 2 * 1024 * 1024
  private fun readBounded(path: Path): ByteArray {
   require(Files.isRegularFile(path) && Files.size(path) <= MAX_BYTES)
   return Files.readAllBytes(path)
  }
 }
}
