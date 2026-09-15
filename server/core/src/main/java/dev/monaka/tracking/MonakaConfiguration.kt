package dev.monaka.tracking

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v1.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Local application settings, not a wire codec. No implicit body or world assignment. */
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
		val mapper = ObjectMapper()
		val document = mapOf(
			"version" to 1, "port" to port, "timeout_ns" to timeoutNanos.toString(),
			"space" to mapOf("id" to space.id, "revision" to space.revision, "convention" to space.convention),
			"assignments" to assignments.snapshot().entries.map { (key, body) ->
				mapOf("source_id" to key.sourceId, "tracker_id" to key.trackerId, "body" to body.name)
			},
		)
		val absolute = path.toAbsolutePath()
		Files.createDirectories(absolute.parent)
		val temporary = absolute.resolveSibling(absolute.fileName.toString() + ".pending")
		Files.write(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document))
		Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
	}
	companion object {
		fun load(path: Path): MonakaConfiguration {
			require(Files.isRegularFile(path)) { "MTP requires explicit world/assignments in $path" }
			val root = ObjectMapper().readTree(path.toFile())
			require(root["version"]?.asInt() == 1)
			val space = requireNotNull(root["space"])
			val revision = space["revision"]
			require(revision != null && revision.isIntegralNumber && revision.canConvertToLong())
			val entries = requireNotNull(root["assignments"])
			require(entries.isArray)
			val assignments = entries.map {
				val source = it["source_id"]; val tracker = it["tracker_id"]; val body = it["body"]
				require(source?.isTextual == true && tracker?.isTextual == true && body?.isTextual == true)
				LogicalTracker(source.asText(), tracker.asText()) to TrackerPosition.valueOf(body.asText())
			}
			require(assignments.map { it.first }.distinct().size == assignments.size)
			val port = root["port"]?.let { require(it.isIntegralNumber && it.canConvertToInt()); it.asInt() } ?: 29811
			val timeout = root["timeout_ns"]?.let { require(it.isTextual); it.asText().toLong() } ?: 500_000_000
			require(space["id"]?.isTextual == true && space["convention"]?.isTextual == true)
			return MonakaConfiguration(
				CoordinateSpace(space["id"].asText(), space["convention"].asText(), revision.asLong()),
				TrackerBodyAssignments(assignments.toMap()), port, timeout,
			)
		}
	}
}
