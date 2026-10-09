package dev.monaka.tracking.diagnostic

import com.google.gson.GsonBuilder
import dev.monaka.protocol.v2.*
import dev.monaka.tracking.LogicalTracker
import dev.monaka.tracking.TrackerBodyAssignments
import dev.monaka.tracking.PoseObservation
import dev.monaka.tracking.ObservationQuality
import java.time.Instant
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/** No wire fields: a separate, opt-in JSONL observer. I/O and serialization stay on a worker. */
class DiagnosticWriter(private val sink: (String) -> Unit, private val capacity: Int = 2048) : AutoCloseable {
	private data class Item(val event: Map<String, Any?>, val critical: Boolean)
	private val queue = ArrayDeque<Item>()
	private val important = ConcurrentLinkedQueue<Item>()
	private val importantCount = AtomicInteger()
	private val lock = ReentrantLock()
	@Volatile private var stopping = false
	val dropped = AtomicLong()
	val criticalDropped = AtomicLong()
	val writeFailures = AtomicLong()
	private val gson = GsonBuilder().serializeNulls().create()
	private val worker: Thread
	init {
		require(capacity >= 2)
		worker = thread(name = "Monaka diagnostic JSONL", isDaemon = true) {
			var sequence = 0L
			while (true) {
				val item = run {
					lock.lock()
					try {
						val critical = important.peek()
						val pose = queue.firstOrNull()
						if (critical != null && (pose == null || (critical.event["monotonic_ns"] as Long) <= (pose.event["monotonic_ns"] as Long)))
							important.poll()?.also { importantCount.decrementAndGet() }
						else if (queue.isEmpty()) null else queue.removeFirst()
					} finally { lock.unlock() }
				}
				if (item == null) {
					if (stopping && important.isEmpty()) break
					LockSupport.parkNanos(2_000_000); continue
				}
				try {
					val event = item.event.toMutableMap()
					if (event["event_type"] == "component_started") event["build"] = build()
					event["sequence"] = sequence++
					@Suppress("UNCHECKED_CAST")
					val details = (event["details"] as Map<String, Any?>).toMutableMap()
					details["dropped_events"] = dropped.get()
					details["critical_dropped_events"] = criticalDropped.get()
					details["write_failures"] = writeFailures.get()
					details["diagnostic_delivery_age_ns"] = (System.nanoTime() - observationEpoch - (event["monotonic_ns"] as Long)).coerceAtLeast(0)
					event["details"] = details
					sink(gson.toJson(event))
				} catch (_: Exception) {
					writeFailures.incrementAndGet(); dropped.incrementAndGet()
					if (item.critical) criticalDropped.incrementAndGet()
				}
			}
		}
	}

	fun emit(type: String, source: String = "component", identity: Map<String, Any?>? = null,
		role: Map<String, Any?>? = null, session: Map<String, Any?>? = null,
		validity: Map<String, Any?>? = null, details: Map<String, Any?> = emptyMap(),
		critical: Boolean = true, identities: List<Map<String, Any?>>? = null,
	) {
		if (stopping) { drop(critical); return }
		val event = linkedMapOf<String, Any?>("schema_version" to 1, "component" to "MonakaVR",
			"timestamp_utc" to Instant.now().toString(), "monotonic_ns" to (System.nanoTime() - observationEpoch).coerceAtLeast(0), "monotonic_domain" to "provider-process",
			"event_type" to type, "source" to source, "tracker_identity" to identity,
			"role" to role, "session" to session, "validity" to validity,
			"build" to null, "details" to details)
		if (identities != null) event["identities"] = identities
		if (critical) {
			if (importantCount.incrementAndGet() > capacity) { importantCount.decrementAndGet(); drop(true); return }
			important.add(Item(event, true)); LockSupport.unpark(worker); return
		}
		if (!lock.tryLock()) { drop(false); return }
		try {
			if (stopping) { drop(critical); return }
			if (queue.size >= capacity) {
				val index = if (critical) queue.indexOfFirst { !it.critical } else -1
				if (index < 0) { drop(critical); return }
				queue.removeAt(index); drop(false)
			}
			queue.addLast(Item(event, false)); LockSupport.unpark(worker)
		} catch (_: Exception) { drop(critical) } finally { lock.unlock() }
	}
	private fun drop(critical: Boolean) { dropped.incrementAndGet(); if (critical) criticalDropped.incrementAndGet() }
	override fun close() {
		stopping = true; LockSupport.unpark(worker)
		if (Thread.currentThread() !== worker) worker.join(2000)
	}
	companion object {
		private val observationEpoch = System.nanoTime()
		fun build(): Map<String, Any?> {
			val p = Properties()
			DiagnosticWriter::class.java.getResourceAsStream("/monaka-diagnostic-build.properties")?.use { p.load(it) }
			return mapOf("component_name" to "MonakaVR", "version" to p.getProperty("version"),
				"git_commit" to p.getProperty("git_commit"), "git_branch_or_ref" to p.getProperty("git_branch_or_ref"),
				"dirty_at_build" to p.getProperty("dirty_at_build")?.toBooleanStrictOrNull(),
				"build_timestamp_utc" to p.getProperty("build_timestamp_utc"), "binary_sha256" to null,
				"build_system" to "Gradle")
		}
		fun buildJson(): String = GsonBuilder().serializeNulls().create().toJson(build())
	}
}

/** Server-owned observation state. Does not alter assignments, freshness, Fusion or IK. */
class RuntimeDiagnosticObserver(private val writer: DiagnosticWriter) {
	private class Tracker { var registered = false; var firstPose = false; var firstPosition = false; var p: Boolean? = null; var q: Boolean? = null }
	private class Lifetime { var id = ""; var source = ""; var generation = 0L; var connected = false; var last = -1L; val trackers = linkedMapOf<String, Tracker>() }
	private val sources = linkedMapOf<String, Lifetime>()
	private val slime = linkedMapOf<String, Tracker>()
	private val slimeSessionId = java.util.UUID.randomUUID().toString()
	private fun identity(key: LogicalTracker) = mapOf<String, Any?>("domain" to "protocol_tracker_id", "value" to key.trackerId,
		"stable_scope" to key.lifetimeId, "source" to key.sourceId)
	private fun session(l: Lifetime) = mapOf<String, Any?>("session_id" to l.id, "session_token" to null,
		"state" to if (l.connected) "connected" else "disconnected", "generation" to l.generation, "peer" to null)
	private fun disconnect(source: String, l: Lifetime, reason: String) {
		l.connected = false
		for ((id, tracker) in l.trackers) if (tracker.registered) {
			writer.emit("tracker_unregistered", source, mapOf("domain" to "protocol_tracker_id", "value" to id,
				"stable_scope" to source, "source" to l.source), session = session(l), details = mapOf("reason" to reason))
			tracker.registered = false
		}
		writer.emit("session_disconnected", source, session = session(l), details = mapOf("reason" to reason, "boundary" to "source-observation"))
	}
	fun observe(envelope: Envelope, now: Long, assignments: TrackerBodyAssignments) = runCatching {
		val p = envelope as? MtpPose
		val s = envelope as? MtpTrackerState
		if (p == null && s == null) return@runCatching
		val key = if (p != null) LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
			else LogicalTracker(s!!.source_id, s.tracker_id, s.publisher_id)
		val sid = p?.session_id ?: s!!.session_id
		if (key.lifetimeId !in sources && sources.size >= 64) { warning("DiagnosticSourceLimit"); return@runCatching }
		val l = sources.getOrPut(key.lifetimeId) { Lifetime() }
		if (l.id != sid || !l.connected) {
			if (l.connected) disconnect(key.lifetimeId, l, "session_replaced")
			val reconnect = l.id.isNotEmpty()
			l.id = sid; l.source = key.sourceId; l.generation++; l.trackers.clear()
			writer.emit(if (reconnect) "session_reconnecting" else "session_connecting", key.lifetimeId, session = session(l))
			l.connected = true
			writer.emit("session_connected", key.lifetimeId, session = session(l), details = mapOf("publisher_id" to key.publisherId))
		}
		l.last = now
		if (key.trackerId !in l.trackers && l.trackers.size >= 1024) { warning("DiagnosticTrackerLimit"); return@runCatching }
		val t = l.trackers.getOrPut(key.trackerId) { Tracker() }
		val id = identity(key); val ss = session(l)
		val role = assignments.snapshot().entries[key]?.let {
			mapOf<String, Any?>("role_source" to "configured_role", "role_value" to it.name, "role_scope" to "body_assignment")
		}
		if (!t.registered) {
			writer.emit("tracker_discovered", key.lifetimeId, id, role, ss)
			writer.emit("tracker_registered", key.lifetimeId, id, role, ss, details = mapOf("registration_kind" to "MTP-cache"))
			t.registered = true
		}
		if (p == null) {
			writer.emit("tracker_updated", key.lifetimeId, id, role, ss, details = mapOf("presence" to s!!.presence, "tracking_state" to s.tracking_state))
			if (s.presence == "absent" || s.tracking_state == "disconnected" || s.tracking_state == "lost") {
				writer.emit("tracker_unregistered", key.lifetimeId, id, role, ss, details = mapOf("reason" to "tracker_state")); t.registered = false
			}
			return@runCatching
		}
		val v = mapOf<String, Any?>("pose_present" to true, "position_valid" to p.validity.position,
			"orientation_valid" to p.validity.orientation, "fully_valid" to (p.validity.position && p.validity.orientation))
		val input = mapOf<String, Any?>("domain" to "backend_device_id", "value" to p.input.device_id,
			"stable_scope" to p.input.source_id, "source" to p.input.source_id)
		writer.emit("pose_observed", key.lifetimeId, id, role, ss, v, mapOf("input_sequence" to p.sequence, "source_sample_age_ns" to (p.sent_at_ns - p.timestamp_ns),
			"validity_authority" to "MTP.validity", "publisher_id" to key.publisherId), false, listOf(id, input))
		if (!t.firstPose) { writer.emit("first_pose", key.lifetimeId, id, role, ss, v); t.firstPose = true }
		if (p.validity.position && !t.firstPosition) { writer.emit("first_position_valid", key.lifetimeId, id, role, ss, v); t.firstPosition = true }
		if (t.p != p.validity.position) writer.emit("position_valid_changed", key.lifetimeId, id, role, ss, v)
		if (t.q != p.validity.orientation) writer.emit("orientation_valid_changed", key.lifetimeId, id, role, ss, v)
		if (t.p == null || t.q == null || (t.p == true && t.q == true) != (p.validity.position && p.validity.orientation))
			writer.emit("fully_valid_changed", key.lifetimeId, id, role, ss, v)
		t.p = p.validity.position; t.q = p.validity.orientation
	}.let { Unit }
	fun warning(reason: String, key: LogicalTracker? = null) = runCatching {
		writer.emit("diagnostic_warning", key?.lifetimeId ?: "mtp", key?.let(::identity), details = mapOf("reason" to reason))
	}.let { Unit }
	fun slimeSnapshot(observations: List<Triple<dev.slimevr.tracking.trackers.Tracker, PoseObservation?, Boolean>>) = runCatching {
		val current = observations.map { it.first.name }.toSet()
		val ss = mapOf<String, Any?>("session_id" to slimeSessionId, "session_token" to null, "state" to "connected", "generation" to 1, "peer" to null)
		fun id(name: String) = mapOf<String, Any?>("domain" to "runtime_registration_id", "value" to name, "stable_scope" to "MonakaVR-tracker-name", "source" to "slime")
		for (name in slime.keys.filter { it !in current }) {
			writer.emit("tracker_unregistered", "slime", id(name), session = ss, details = mapOf("reason" to "authoritative_snapshot_removed")); slime.remove(name)
		}
		for ((tracker, observation, configuredRole) in observations) {
			if (tracker.name !in slime && slime.size >= 1024) { warning("DiagnosticTrackerLimit"); continue }
			val state = slime.getOrPut(tracker.name) { Tracker() }
			val role = observation?.target?.let { mapOf<String, Any?>("role_source" to if (configuredRole) "configured_role" else "runtime_role", "role_value" to it.name, "role_scope" to "body_assignment") }
			if (!state.registered) { writer.emit("tracker_registered", "slime", id(tracker.name), role, ss); state.registered = true }
			val available = setOf(ObservationQuality.TRACKED, ObservationQuality.DEGRADED)
			val p = observation?.let { it.position != null && it.positionQuality in available }
			val q = observation?.let { it.rotation != null && it.rotationQuality in available }
			val validity = mapOf<String, Any?>("pose_present" to (observation != null), "position_valid" to p, "orientation_valid" to q,
				"fully_valid" to if (p == null || q == null) null else p && q)
			writer.emit("pose_observed", "slime", id(tracker.name), role, ss, validity,
				mapOf("validity_authority" to "MonakaVR Slime adapter component availability", "unassigned_validity_unavailable" to (observation == null)), false)
			if (observation != null && !state.firstPose) { writer.emit("first_pose", "slime", id(tracker.name), role, ss, validity); state.firstPose = true }
			if (p == true && !state.firstPosition) { writer.emit("first_position_valid", "slime", id(tracker.name), role, ss, validity); state.firstPosition = true }
			if (p != state.p) writer.emit("position_valid_changed", "slime", id(tracker.name), role, ss, validity)
			if (q != state.q) writer.emit("orientation_valid_changed", "slime", id(tracker.name), role, ss, validity)
			state.p = p; state.q = q
		}
	}.let { Unit }
	fun tick(now: Long, timeout: Long = 500_000_000) = runCatching {
		for ((source, l) in sources) if (l.connected && now >= l.last && now - l.last >= timeout) disconnect(source, l, "observation_timeout")
	}.let { Unit }
	fun close() = runCatching { for ((source, l) in sources) if (l.connected) disconnect(source, l, "backend_closed") }.let { Unit }
}

object RuntimeDiagnostics {
	private val writer: DiagnosticWriter? by lazy {
		val path = System.getenv("MONAKA_DIAGNOSTIC_JSONL")?.takeIf { it.isNotBlank() } ?: return@lazy null
		var output: java.io.BufferedWriter? = null
		DiagnosticWriter({ line ->
			if (output == null) output = java.io.File(path).bufferedWriter(Charsets.UTF_8, 8192)
			output!!.write(line); output!!.newLine(); output!!.flush()
		}).also { w ->
			w.emit("component_started")
			Runtime.getRuntime().addShutdownHook(thread(start = false, name = "Monaka diagnostic shutdown") {
				w.emit("component_stopping"); w.emit("component_stopped"); w.close(); output?.close()
			})
		}
	}
	val observer: RuntimeDiagnosticObserver? by lazy { writer?.let(::RuntimeDiagnosticObserver) }
	fun ready() = runCatching { writer?.emit("component_ready", details = mapOf("inventory_complete" to false)) }.let { Unit }
	fun event(type: String, source: String = "component", identity: Map<String, Any?>? = null,
		role: Map<String, Any?>? = null, details: Map<String, Any?> = emptyMap()) = runCatching {
		writer?.emit(type, source, identity, role, details = details)
	}.let { Unit }
}
