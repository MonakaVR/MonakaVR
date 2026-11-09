package dev.monaka.tracking

import com.fasterxml.jackson.databind.JsonNode
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
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/** Server-owned configuration boundary. Tracking wires and fusion policy are unchanged. */
class MonakaInputConfigurationApi(
	private val configuration: MonakaConfiguration,
	private val backend: MtpObservationBackend,
	private val trackers: () -> Iterable<Tracker>,
	path: Path,
	private val trackingOverrideStatus: () -> Map<TrackingOverrideHand, TrackingOverrideFrame> = { emptyMap() },
	private val trackingOverrideDriverReady: () -> Boolean = { false },
	private val trackingOverrideRegisteredHands: () -> Set<TrackingOverrideHand> = { emptySet() },
	private val overrideEditorToken: String? = System.getenv("MONAKA_TRACKING_OVERRIDE_EDITOR_TOKEN"),
) {
	private val path = path.toAbsolutePath().normalize()
	private val mapper = ObjectMapper()
	private var expectedFile = readBounded(this.path)
	private var expectedFileDigest = fileDigest(expectedFile)
	private val initiallySaved = MonakaConfiguration.loadBytes(this.path, expectedFile)
	private var savedAssignments = initiallySaved.assignments.snapshot().targets
	private var overrideSelections = configuration.trackingOverride
	private data class OverridePreview(
		val selection: TrackingOverrideSelection,
		val generation: Long,
		val epoch: String,
		val receivedAt: Long,
	)
	private val overridePreviews = mutableMapOf<TrackingOverrideHand, OverridePreview>()
	private var restartPending = savedAssignments != configuration.assignments.snapshot().targets
	private var tokenEpoch = UUID.randomUUID().toString()
	private fun readbackToken(): String = "$tokenEpoch:$expectedFileDigest"
	private fun checkFile() {
		require(!Files.isSymbolicLink(path) && readBounded(path).contentEquals(expectedFile)) {
			"Configuration file changed; reload/restart and review offline before saving"
		}
	}
	private fun checkEditable() {
		require(!restartPending) { "Saved configuration is awaiting server restart; all input edits are blocked" }
		checkFile()
	}
	private var owner: Thread? = null
	private fun checkThread() {
		if (owner == null) owner = Thread.currentThread()
		check(owner === Thread.currentThread()) { "Input configuration requires the server thread" }
	}
	fun trackingOverrideSelections(): Map<TrackingOverrideHand, TrackingOverrideSelection> {
		checkThread()
		return overrideSelections
	}
	fun effectiveTrackingOverrideSelections(now: Long): Map<TrackingOverrideHand, TrackingOverrideSelection> {
		checkThread()
		if (overridePreviews.isEmpty()) return overrideSelections
		val generation = configuration.assignments.snapshot().generation
		overridePreviews.values.removeIf {
			it.epoch != tokenEpoch || it.generation != generation || now - it.receivedAt !in 0 until OVERRIDE_PREVIEW_LEASE_NANOS
		}
		return overrideSelections + overridePreviews.mapValues { it.value.selection }
	}
	data class Candidate(
		val reference: TrackerReference,
		val capabilities: DeclaredTrackerCapabilities?,
		val conflict: Boolean,
		val qSelectable: Boolean,
		val sixDofSelectable: Boolean,
		val qUsableNow: Boolean,
		val sixDofUsableNow: Boolean,
		val origin: String,
		val slimeTrackerId: SlimeTrackerId? = null,
		val displayName: String? = null,
	)

	/** Ephemeral locator for an actual SolarXR tracker; never a persistent source identity. */
	data class SlimeTrackerId(val deviceId: SlimeDeviceId?, val trackerNum: Int)
	data class SlimeDeviceId(val id: Int)
	data class Snapshot(
		val generation: Long,
		val candidates: List<Candidate>,
		val assignments: Map<TrackerPosition, MainTrackerAssignment>,
	)
	class Edit internal constructor(internal val generation: Long, val assignments: Map<TrackerPosition, MainTrackerAssignment>)
	private fun sixDofSource(assignment: MainTrackerAssignment, candidates: List<Candidate>): TrackerReference? =
		assignment.configuredSixDofSource()?.takeIf { main ->
			assignment.sourceSelection != null || main.mtp != null || candidates.singleOrNull { it.reference == main }?.capabilities?.supportsSixDof == true
		}

	private fun overrideSource(hand: TrackingOverrideHand, snapshot: Snapshot): TrackerReference? =
		snapshot.assignments[hand.body]?.let { sixDofSource(it, snapshot.candidates) }

	private fun canEnableOverride(source: TrackerReference?, snapshot: Snapshot): Boolean =
		!restartPending && source?.mtp != null && snapshot.candidates.any { it.reference == source && it.sixDofSelectable }
	fun snapshot(now: Long): Snapshot {
		checkThread()
		require(now >= 0)
		checkFile()
		val state = configuration.assignments.snapshot()
		val mtp = backend.capabilitiesSnapshot().values.map { source ->
			val fresh = source.freshAt(now, configuration.timeoutNanos)
			Candidate(
				TrackerReference.mtp(source.tracker),
				source.capabilities,
				source.conflict,
				!source.conflict && source.capabilities?.supportsQ == true,
				!source.conflict && source.capabilities?.supportsSixDof == true,
				fresh && source.orientationValid,
				fresh && source.positionValid && source.orientationValid && source.modality == "full",
				"mvip_pose",
				displayName = source.displayName,
			)
		}
		val adapter = SlimeTrackerPoseObservationAdapter()
		val liveTrackers = trackers().toList()
		val locatorCounts = liveTrackers.groupingBy { it.device?.id to it.trackerNum }.eachCount()
		val nameCounts = liveTrackers.groupingBy { it.name }.eachCount()
		val slime = liveTrackers.filter(FeedbackExclusion::accepts).filter { it.hasRotation }.groupBy { it.name }.map { (name, matches) ->
			val tracker = matches.singleOrNull()
			val locator = tracker?.takeIf {
				it.trackerNum in 0..255 &&
					(it.device == null || it.device.id in 0..255) &&
					nameCounts[name] == 1 &&
					locatorCounts[it.device?.id to it.trackerNum] == 1
			}?.let { SlimeTrackerId(it.device?.let { device -> SlimeDeviceId(device.id) }, it.trackerNum) }
			val sample = matches.singleOrNull()?.let { adapter.adapt(it, now, TrackerPosition.HEAD) }
			// These immutable Tracker declarations are separate from current sample validity.
			val capabilities = tracker?.let {
				DeclaredTrackerCapabilities(listOfNotNull("position".takeIf { _ -> it.hasPosition }, "orientation".takeIf { _ -> it.hasRotation }))
			}
			Candidate(
				TrackerReference.slime(name),
				capabilities,
				matches.size != 1,
				matches.size == 1,
				capabilities?.supportsSixDof == true,
				sample?.rotation != null && sample.rotationQuality.usable,
				sample?.modality == TrackingModality.FULL && sample.position != null && sample.rotation != null && sample.positionQuality.usable && sample.rotationQuality.usable,
				"slime_orientation_stream",
				locator,
				displayName = tracker?.customName?.takeIf { it.isNotBlank() } ?: tracker?.displayName,
			)
		}
		return Snapshot(state.generation, Collections.unmodifiableList(mtp + slime), state.targets)
	}
	fun editSources(
		target: TrackerPosition,
		q: TrackerReference?,
		six: TrackerReference?,
		manualSix: Boolean,
		generation: Long,
		now: Long,
	): Edit {
		val snapshot = snapshot(now)
		require(target.name != "NONE")
		checkEditable()
		require(snapshot.generation == generation) { "Assignments changed; reload before saving" }
		val qCandidate = q?.let { ref -> snapshot.candidates.singleOrNull { it.reference == ref } }
		val sixCandidate = six?.let { ref -> snapshot.candidates.singleOrNull { it.reference == ref } }
		val current = snapshot.assignments[target]
		val currentQ = current?.let { if (it.sourceSelection != null) it.sourceSelection.qSource else it.rotationFallbackTracker ?: it.mainTracker }
		val currentSix = current?.let { sixDofSource(it, snapshot.candidates) }
		require(q == null || qCandidate?.qSelectable == true || q == currentQ) { "Q Source has unknown/conflicting orientation support" }
		require(six == null || sixCandidate?.sixDofSelectable == true || six == currentSix) { "6DoF Source requires declared position and orientation" }
		if (!manualSix) {
			val permitted = if (current == null) {
				q?.takeIf { qCandidate?.capabilities?.supportsSixDof == true }
			} else {
				currentSix
			}
			require(six == permitted) { "Automatic 6DoF choice is permitted only on the first Q selection" }
		}
		val candidate = TrackerBodyAssignments().also { it.replaceTargets(snapshot.assignments) }
		candidate.configureSources(target, q, six, manualSix)
		return Edit(generation, candidate.snapshot().targets)
	}

	/** Persist first, then publish the already-validated assignment snapshot. */
	fun saveAndApply(edit: Edit): TrackerBodyAssignments.Snapshot = synchronized(configuration.assignments) {
		checkThread()
		checkEditable()
		val current = configuration.assignments.snapshot()
		require(current.generation == edit.generation && current.generation < Long.MAX_VALUE) { "Assignments changed; reload before saving" }
		val affected = (current.targets.keys + edit.assignments.keys).filter { current.targets[it] != edit.assignments[it] }
		if (affected.isEmpty()) return@synchronized current
		val requiresRestart = affected.any {
			current.targets[it]?.outputMode?.let { mode -> mode != OutputMode.IK } == true ||
				edit.assignments[it]?.outputMode?.let { mode -> mode != OutputMode.IK } == true
		}
		val handSourceChanged = TrackingOverrideHand.entries.any { hand ->
			current.targets[hand.body]?.configuredSixDofSource() != edit.assignments[hand.body]?.configuredSixDofSource()
		}

		val next = TrackerBodyAssignments().also { it.replaceTargets(edit.assignments) }
		val candidate = configuration.copy(assignments = next, trackingOverride = overrideSelections) // All existing configuration validators.
		commit { stage ->
			candidate.saveUnmanaged(stage)
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
			// Canonicalize any loaded legacy binding before changing the assignment
			// so a legacy source can never be rebound implicitly on the next load.
			if (handSourceChanged && generated.has("trackingOverride")) {
				document.set<JsonNode>("trackingOverride", generated["trackingOverride"])
			}
			mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document)
		}
		if (requiresRestart) restartPending = true else configuration.assignments.replaceTargets(edit.assignments)
		savedAssignments = edit.assignments
		configuration.assignments.snapshot()
	}
	private fun modeAssignment(current: MainTrackerAssignment, mode: OutputMode) = current.copy(
		outputMode = mode,
		continuity = if (mode == OutputMode.HYBRID) ContinuityPolicy.BACKGROUND_IK else ContinuityPolicy.NONE,
		useAsIkConstraint = if (mode == OutputMode.HYBRID) true else current.useAsIkConstraint,
	)
	private fun validateMode(target: TrackerPosition, mode: OutputMode): MainTrackerAssignment {
		val targets = configuration.assignments.snapshot().targets
		val current = requireNotNull(targets[target]) { "Assign a Main input before selecting an output mode" }
		val changed = modeAssignment(current, mode)
		val next = TrackerBodyAssignments().also { it.replaceTargets(targets + (target to changed)) }
		configuration.copy(assignments = next) // Same role, correction, space and calibration validators as startup.
		return changed
	}

	/** Offline only. The live assignment generation and every runtime owner remain unchanged. */
	fun saveMode(target: TrackerPosition, mode: OutputMode, generation: Long, readbackToken: String) = synchronized(configuration.assignments) {
		checkThread()
		checkEditable()
		val current = configuration.assignments.snapshot()
		require(current.generation == generation && readbackToken == readbackToken()) { "Stale mode review; reload before saving" }
		require(savedAssignments == current.targets) { "Runtime and saved assignments differ; restart/review before mode editing" }
		require(target.name != "NONE")
		val changed = validateMode(target, mode)
		if (changed == current.targets[target]) return@synchronized
		val document = mapper.readTree(expectedFile) as ObjectNode
		require(document["version"].asInt() in 2..4) { "Migrate legacy configuration explicitly before mode editing" }
		val row = document["assignments"].single { it["body"].asText() == target.name } as ObjectNode
		// Patch only these three fields. In particular, never serialize/replace sourceSelection or references.
		row.put("outputMode", changed.outputMode.name.lowercase())
		row.put("continuity", changed.continuity.name.lowercase())
		row.put("useAsIkConstraint", changed.useAsIkConstraint)
		val bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document)
		val nextSaved = current.targets + (target to changed)
		commit { bytes }
		savedAssignments = nextSaved
		restartPending = true
	}
	private fun saveOverride(root: JsonNode, now: Long): TrackingOverrideHand {
		require(root["readbackToken"]?.textValue() == readbackToken()) { "Saved configuration changed; reload before editing" }
		val hand = TrackingOverrideConfigJson.hand(requireNotNull(root["hand"]?.textValue()))
		val current = overrideSelections[hand] ?: TrackingOverrideSelection()
		val edit = TrackingOverrideConfigJson.readEdit(requireNotNull(root["selection"]))
		val next = if (!edit.enabled && (!edit.sameUniverseConfirmed || edit.gripOffset == null)) {
			// Release remains possible without an available source or a current calibration.
			current.copy(enabled = false)
		} else {
			val snapshot = snapshot(now)
			val source = overrideSource(hand, snapshot)
			if (!edit.enabled && !canEnableOverride(source, snapshot) && current.enabled && edit.gripOffset == current.gripOffset) {
				current.copy(enabled = false)
			} else {
				require(canEnableOverride(source, snapshot)) { "Assign a supported MVIP 6DoF source to this hand first" }
				require(edit.gripOffset != null && edit.sameUniverseConfirmed) {
					"Confirm a grip offset and the SteamVR world for this hand assignment"
				}
				TrackingOverrideSelection.confirmed(edit.enabled, hand, requireNotNull(source), configuration.space, edit.gripOffset)
			}
		}
		if (next == current) {
			overridePreviews.remove(hand)
			return hand
		}
		val selections = overrideSelections + (hand to next)
		val document = mapper.readTree(expectedFile) as ObjectNode
		require(document["version"].asInt() in 2..4) { "Migrate legacy configuration before editing tracking overrides" }
		document.put("version", 4)
		document.set<JsonNode>("trackingOverride", mapper.valueToTree(TrackingOverrideConfigJson.write(selections)))
		commit { mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document) }
		overrideSelections = Collections.unmodifiableMap(selections)
		return hand
	}
	private fun previewOverride(root: JsonNode, now: Long) {
		require(root.fieldNames().asSequence().toSet() == setOf("type", "id", "action", "hand", "gripOffset", "readbackToken", "editorToken"))
		require(root["readbackToken"]?.textValue() == readbackToken()) { "Saved configuration changed; reload before previewing" }
		val hand = TrackingOverrideConfigJson.hand(requireNotNull(root["hand"]?.textValue()))
		val offset = root["gripOffset"]
		if (offset.isNull) {
			overridePreviews.remove(hand)
			return
		}
		require(!restartPending) { "Saved configuration is awaiting server restart" }
		val assignment = configuration.assignments.snapshot()
		val source = assignment.targets[hand.body]?.configuredSixDofSource()
		val saved = overrideSelections[hand]
		require(saved?.enabled == true && saved.calibrationCurrent(hand, source, configuration.space) &&
			source?.mtp?.let(backend::declaredCapabilitiesFor)?.supportsSixDof == true) {
			"Save an enabled override for a supported MVIP source before previewing"
		}
		overridePreviews[hand] = OverridePreview(
			saved.copy(gripOffset = TrackingOverrideConfigJson.readGrip(offset)), assignment.generation, tokenEpoch, now,
		)
	}
	private fun overrideEditorAuthorized(root: JsonNode): Boolean {
		val suppliedToken = root["editorToken"]?.textValue()
		return overrideEditorToken != null && suppliedToken != null &&
			MessageDigest.isEqual(overrideEditorToken.toByteArray(), suppliedToken.toByteArray())
	}

	private fun commit(generate: (Path) -> ByteArray) {
		require(!Files.isSymbolicLink(path)) { "Configuration aliases require offline review" }
		val parent = path.parent.toRealPath()
		val stage = parent.resolve(".monaka-input-${UUID.randomUUID()}.json")
		val nextToken = UUID.randomUUID().toString()
		try {
			MonakaConfiguration.withWriteLock(path) {
				checkFile()
				val bytes = generate(stage)
				require(bytes.size <= MAX_BYTES)
				val digest = fileDigest(bytes)
				Files.write(stage, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
				MonakaConfiguration.load(stage) // Validate the exact merged document, not merely the generated fields.
				FileChannel.open(stage, StandardOpenOption.WRITE).use { it.force(true) }
				Files.write(
					parent.resolve(path.fileName.toString() + ".pre-input-edit-${UUID.randomUUID()}.bak"),
					expectedFile,
					StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE,
				)
				require(readBounded(path).contentEquals(expectedFile)) { "Configuration changed before commit" }
				Files.move(stage, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
				expectedFile = bytes
				expectedFileDigest = digest
				tokenEpoch = nextToken
				overridePreviews.clear()
			}
		} finally {
			// Cleanup cannot turn a completed commit into a reported cancellation/failure.
			runCatching { Files.deleteIfExists(stage) }
			runCatching { Files.deleteIfExists(stage.resolveSibling(stage.fileName.toString() + ".pending")) }
		}
	}
	fun handleJson(raw: String, now: Long): String {
		checkThread()
		var id = ""
		var invalidateReadback = true
		return try {
			require(raw.length <= 65536)
			val root = mapper.readTree(raw)
			require(root["type"]?.asText() == "monaka_inputs_v1")
			val action = root["action"]?.asText()
			if (action == "preview_override") invalidateReadback = false
			require(root["id"]?.isTextual == true && root["id"].asText().length in 1..64)
			id = root["id"].asText()
			var editedOverride: TrackingOverrideHand? = null
			if (action == "preview_override") {
				require(overrideEditorAuthorized(root)) { "Tracking override editing requires the local desktop application" }
				previewOverride(root, now)
				return mapper.writeValueAsString(mapOf("type" to "monaka_inputs_v1", "id" to id, "ok" to true, "preview" to true))
			} else if (action == "save") {
				val current = snapshot(now)
				val target = TrackerPosition.valueOf(root["body"].asText())
				val existing = current.assignments[target]?.let { listOfNotNull(it.mainTracker, it.rotationFallbackTracker) }.orEmpty()
				fun reference(field: String): TrackerReference? {
					val value = requireNotNull(root[field])
					if (value.isNull) return null
					require(value.isTextual)
					return current.candidates.singleOrNull { it.reference.observationId == value.asText() }?.reference
						?: existing.singleOrNull { it.observationId == value.asText() }
						?: error("Source unavailable/ambiguous; reload candidates")
				}
				require(root["generation"]?.isTextual == true && root["manualSix"]?.isBoolean == true)
				val edit = editSources(
					target,
					reference("qSource"),
					reference("sixDofSource"),
					root["manualSix"].booleanValue(),
					root["generation"].asText().toLong(),
					now,
				)
				saveAndApply(edit)
			} else if (action == "save_mode") {
				require(
					root["body"]?.isTextual == true &&
						root["generation"]?.isTextual == true &&
						root["mode"]?.isTextual == true &&
						root["readbackToken"]?.isTextual == true,
				)
				val mode = when (root["mode"].asText()) {
					"ik" -> OutputMode.IK
					"direct" -> OutputMode.DIRECT
					"hybrid" -> OutputMode.HYBRID
					else -> error("Unknown output mode")
				}
				saveMode(TrackerPosition.valueOf(root["body"].asText()), mode, root["generation"].asText().toLong(), root["readbackToken"].asText())
			} else if (action == "save_override") {
				invalidateReadback = overrideEditorAuthorized(root)
				require(invalidateReadback) { "Tracking override editing requires the local desktop application" }
				editedOverride = saveOverride(root, now)
			} else {
				require(action == "snapshot")
			}
			val s = snapshot(now)
			val statuses = trackingOverrideStatus()
			val registeredHands = trackingOverrideRegisteredHands()
			val data = mapOf(
				"type" to "monaka_inputs_v1", "id" to id, "ok" to true, "generation" to s.generation.toString(),
				"readbackToken" to readbackToken(), "restartRequired" to restartPending,
				"trackingOverride" to mapOf(
					"driverReady" to trackingOverrideDriverReady(),
					"hands" to TrackingOverrideHand.entries.associate { hand ->
						val selection = overrideSelections[hand] ?: TrackingOverrideSelection()
						val source = overrideSource(hand, s)
						val calibrationCurrent = selection.calibrationCurrent(hand, source, configuration.space)
						hand.name.lowercase() to mapOf(
							"saved" to TrackingOverrideConfigJson.readback(selection, calibrationCurrent),
							"source" to source?.observationId,
							"canEnable" to canEnableOverride(source, s),
							"calibrationCurrent" to calibrationCurrent,
							"sourceStatus" to statuses[hand]?.takeIf { hand != editedOverride && it.source == source }?.status?.name,
							"devicePath" to hand.devicePath,
							"targetPath" to hand.targetPath,
							"registeredDevicePath" to hand.devicePath.takeIf { hand in registeredHands },
						)
					},
				),
				"backgroundIkAlignmentConfirmed" to (configuration.backgroundIkSharedSpace?.convention == configuration.space.convention),
				"bodies" to TrackerPosition.values().filter { it.name != "NONE" }.map { it.name },
				"candidates" to s.candidates.map { x ->
					mapOf(
						"id" to x.reference.observationId,
						"publisher" to x.reference.mtp?.publisherId, "source" to x.reference.mtp?.sourceId,
						"tracker" to (x.reference.mtp?.trackerId ?: x.reference.observationId.removePrefix("slime:")),
						"displayName" to x.displayName,
						"capabilities" to x.capabilities?.components, "conflict" to x.conflict,
						"qSelectable" to x.qSelectable, "sixDofSelectable" to x.sixDofSelectable,
						"qUsableNow" to x.qUsableNow, "sixDofUsableNow" to x.sixDofUsableNow, "origin" to x.origin,
						"slimeTrackerId" to x.slimeTrackerId,
					)
				},
				"assignments" to s.assignments.map { (body, a) ->
					mapOf(
						"body" to body.name,
						"qSource" to (a.sourceSelection?.qSource?.observationId ?: if (a.sourceSelection == null) (a.rotationFallbackTracker ?: a.mainTracker).observationId else null),
						"sixDofSource" to sixDofSource(a, s.candidates)?.observationId,
						"savedManualSix" to (savedAssignments[body]?.sourceSelection?.sixDofManuallySelected ?: true),
						"savedQSource" to (savedAssignments[body]?.let { saved -> if (saved.sourceSelection != null) saved.sourceSelection.qSource else saved.rotationFallbackTracker ?: saved.mainTracker }?.observationId),
						"savedSixDofSource" to savedAssignments[body]?.let { sixDofSource(it, s.candidates) }?.observationId,
						"manualSix" to (a.sourceSelection?.sixDofManuallySelected ?: true), "mode" to a.outputMode.name.lowercase(),
						"activeMode" to a.outputMode.name.lowercase(), "savedMode" to savedAssignments[body]?.outputMode?.name?.lowercase(),
						"restartRequired" to (savedAssignments[body] != a),
						"modeOptions" to OutputMode.values().map { mode ->
							val reason = if (restartPending) {
								"Saved configuration is awaiting server restart"
							} else {
								runCatching {
									validateMode(body, mode)
									null
								}.getOrElse { it.message ?: "Configuration is incompatible" }
							}
							mapOf("mode" to mode.name.lowercase(), "available" to (reason == null), "reason" to reason)
						},
					)
				},
			)
			mapper.writeValueAsString(data).also { require(it.length <= MAX_BYTES) }
		} catch (e: Exception) {
			if (invalidateReadback) tokenEpoch = UUID.randomUUID().toString() // Failed authorized writes require fresh readback.
			mapper.writeValueAsString(
				mapOf(
					"type" to "monaka_inputs_v1",
					"id" to id,
					"ok" to false,
					"reloadRequired" to true,
					"restartRequired" to restartPending,
					"error" to (e.message ?: "Input configuration failed").take(240),
				),
			)
		}
	}
	companion object {
		private const val MAX_BYTES = 2 * 1024 * 1024
		private const val OVERRIDE_PREVIEW_LEASE_NANOS = 2_000_000_000L
		private fun fileDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
		private fun readBounded(path: Path): ByteArray {
			require(Files.isRegularFile(path) && Files.size(path) <= MAX_BYTES)
			return Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }.also { require(it.size <= MAX_BYTES) }
		}
	}
}
