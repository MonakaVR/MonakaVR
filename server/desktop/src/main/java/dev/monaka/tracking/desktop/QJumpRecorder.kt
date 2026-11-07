package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Diagnostic-only actor. Tracking does bounded immutable offers; only workers encode or touch files. */
class QJumpRecorder internal constructor(
	val config: Config,
	private val monotonic: () -> Long = System::nanoTime,
	private val wall: () -> Long = ::utcNanos,
	private val sink: ((Path) -> OutputStream)? = null,
	private val sourceIdentity: Map<String, Any?> = emptyMap(),
) : AutoCloseable {
	data class Config(val output: Path, val coordination: Path, val thresholdDegrees: Double = 45.0,
		val preNs: Long = 10_000_000_000, val postNs: Long = 10_000_000_000,
		val intervalNs: Long = 50_000_000, val maxRingBytes: Int = 16 * 1024 * 1024,
		val maxEventBytes: Int = 32 * 1024 * 1024, val maxEvents: Int = 16,
		val maxFrames: Int = 4096, val maxFrameBytes: Int = 256 * 1024) {
		init {
			require(output.isAbsolute && coordination.isAbsolute && output.normalize() != coordination.normalize())
			require(thresholdDegrees in 0.1..180.0 && preNs > 0 && postNs > 0 && intervalNs > 0)
			require(maxRingBytes in 1024..16 * 1024 * 1024 && maxEventBytes in 4096..32 * 1024 * 1024)
			require(maxEvents in 1..16 && maxFrames in 2..4096 && maxFrameBytes in 1024..256 * 1024)
		}
	}
	private data class Detection(val sample: Map<String, Any?>, val type: String, val before: Map<String, Any?>?, val degrees: Double?)
	private data class Frame(val mono: Long, val utc: Long, val samples: List<Map<String, Any?>>, val detections: List<Detection> = emptyList(), val exactBefore: Frame? = null)
	private data class Encoded(val mono: Long, val utc: Long, val bytes: List<ByteArray>, val size: Int)
	private data class Prior(val sample: Map<String, Any?>, val q: List<Double>?, val context: Any?, val observedMono: Long, val progressMono: Long, val progressValue: Long?, var anchor: List<Double>? = null, var stepped: Long? = null, var persistent: Boolean = false)
	private data class Event(val id: String, val triggerMono: Long, val triggerUtc: Long, val deadline: Long,
		val frames: MutableList<Encoded>, val triggers: MutableList<Map<String, Any?>>, val incidents: MutableSet<String>,
		val dropStart: Long, val started: Long, var bytes: Int, var incomplete: Boolean = false, var coverageEnd: Long = triggerMono)
	val processInstance: String = UUID.randomUUID().toString()
	val dropped = AtomicLong()
	val missedEvents = AtomicLong()
	val failures = AtomicLong()
	val writtenEvents = AtomicLong()
	val processedFrames = AtomicLong()
	@Volatile var status = "RUNNING"
		private set
	@Volatile var ringBytes = 0
		private set
	@Volatile var ringFrames = 0
		private set
	private val queue = ArrayBlockingQueue<Frame>(8)
	private val writerQueue = ArrayBlockingQueue<Event>(1)
	private val mapper = ObjectMapper()
	private val ring = ArrayDeque<Encoded>()
	private val prior = linkedMapOf<String, Prior>()
	private val entities = linkedSetOf<List<String>>()
	private val seenRequests = linkedSetOf<String>()
	private var active: Event? = null
	private var eventCount = 0
	private val lastRetained = linkedMapOf<String, Long>()
	private val detectionLock = Any()
	private var lastTrackingFrame: Frame? = null
	private var forceTrackingFrame = false
	private var newestRetainedMono = Long.MIN_VALUE
	private val sourceProgressGapNs = 250_000_000L
	private val startedMono = monotonic()
	private val startedUtc = wall()
	private var lastPoll = Long.MIN_VALUE
	private var lastStatus = Long.MIN_VALUE
	@Volatile private var closing = false
	@Volatile private var actorDone = false
	private val runDirectory = config.output.resolve("monaka-$processInstance")
	private val writer = Thread({ writeLoop() }, "monaka-qjump-writer").also { it.isDaemon = true; it.start() }
	private val actor = Thread({ actorLoop() }, "monaka-qjump-capture").also { it.isDaemon = true; it.start() }

	/** Maps/lists must be freshly detached by the adapter; no model references are accepted. */
	fun offer(samples: List<Map<String, Any?>>, enrich: ((List<Map<String, Any?>>) -> List<Map<String, Any?>>)? = null): Boolean {
		if (closing || status == "EXHAUSTED") return false
		if (samples.size > 256 || !bounded(samples)) { dropped.incrementAndGet(); return false }
		// Admission, reset and tracking callbacks can differ in thread. Only bounded detector state
		// is serialized here; no snapshot callback, JSON or filesystem operation runs under this lock.
		val selected = synchronized(detectionLock) {
			val frame = Frame(monotonic(), wall(), samples)
			val detections = detect(frame)
			val tracking = samples.any { it["stage"] == "solver_constraint" }
			val forced = detections.isNotEmpty() || (tracking && forceTrackingFrame)
			val retained = if (forced) samples else samples.filter { sample ->
				val lane = sample["stream_id"] as? String ?: return@filter false
				if (sample["retention"] == "TRIGGER_ONLY" || lane !in prior) return@filter false
				if (lastRetained[lane]?.let { frame.mono - it >= config.intervalNs } != false) { lastRetained[lane] = frame.mono; true } else false
			}
			val previous = lastTrackingFrame.takeIf { forced }
			if (tracking) { lastTrackingFrame = frame; forceTrackingFrame = false }
			else if (detections.isNotEmpty()) forceTrackingFrame = true
			if (retained.isEmpty()) null else frame.copy(samples = retained, detections = detections, exactBefore = previous)
		}
		if (selected == null) { processedFrames.incrementAndGet(); return true }
		val rich = if (enrich == null) selected else selected.copy(samples = enrich(selected.samples))
		if (!bounded(rich.samples) || !queue.offer(rich)) {
			dropped.incrementAndGet()
			if (rich.detections.isNotEmpty()) missedEvents.incrementAndGet()
			return false
		}
		return true
	}
	private fun bounded(value: Any?, depth: Int = 0, budget: IntArray = intArrayOf(0)): Boolean {
		if (depth > 8 || ++budget[0] > 8192) return false
		return when (value) {
			null, is Boolean, is Number -> true
			is String -> value.length <= 1024
			is Map<*, *> -> value.size <= 64 && value.all { (k, v) -> k is String && k.length <= 96 && bounded(v, depth + 1, budget) }
			is List<*> -> value.size <= 256 && value.all { bounded(it, depth + 1, budget) }
			else -> false
		}
	}
	private fun base(kind: String, utc: Long) = mapOf("schema" to "qjump.capture.v1", "kind" to kind,
		"component" to "monaka", "process_instance" to processInstance, "wall_clock_utc_ns" to utc.toString())
	private fun encode(frame: Frame): Encoded? {
		val lines = frame.samples.map { mapper.writeValueAsBytes(base("sample", frame.utc) + it + mapOf("process_monotonic_ns" to frame.mono.toString(), "clock_domain" to "monaka_system_nanotime")) }
		val size = lines.sumOf { it.size + 1 }
		if (size > config.maxFrameBytes) { dropped.incrementAndGet(); return null }
		return Encoded(frame.mono, frame.utc, lines, size)
	}
	private fun actorLoop() {
		try {
			while (!closing || queue.isNotEmpty()) {
				val frame = queue.poll(10, TimeUnit.MILLISECONDS)
				if (frame != null) { process(frame); processedFrames.incrementAndGet() }
				val now = monotonic()
				if (!closing && (lastPoll == Long.MIN_VALUE || now - lastPoll >= 50_000_000)) { lastPoll = now; pollRequests(now, wall()) }
				if (lastStatus == Long.MIN_VALUE || now - lastStatus >= 1_000_000_000) { lastStatus = now; writeStatus() }
				active?.let { if (now >= it.deadline) finish(now, false) }
			}
			if (active != null) finish(monotonic(), true)
		} catch (_: Exception) { failures.incrementAndGet(); status = "CAPTURE_FAILURE"; active?.let { it.incomplete = true; finish(monotonic(), true) } }
		finally { actorDone = true; writeStatus() }
	}
	private fun detect(frame: Frame): List<Detection> {
		val detections = mutableListOf<Detection>()
		fun emit(sample: Map<String, Any?>, type: String, before: Map<String, Any?>?, degrees: Double?) { detections += Detection(sample, type, before, degrees) }
		for (sample in frame.samples) {
			val lane = sample["stream_id"] as? String ?: continue
			val logical = (sample["identity"] as? Map<*, *>)?.let { id -> listOf("publisher_id", "source_id", "tracker_id").mapNotNull { id[it] as? String } }
			if (logical != null && logical.size == 3 && logical !in entities) {
				if (entities.size >= 16) { dropped.incrementAndGet(); continue }
				entities.add(logical)
			}
			if (lane !in prior && prior.size >= 256) { dropped.incrementAndGet(); continue }
			val validity = sample["validity"] as? Map<*, *>
			val q = quaternion(sample["q"]).takeUnless { validity?.get("fresh") == false || validity?.get("rotation") == false }
			val context = sample["context"]
			val identity = sample["identity"]
			val fullEpoch = (((context as? Map<*, *>)?.get("reset_state") as? Map<*, *>)?.get("correction_epoch"))
			val semantic = listOf(identity?.let { (it as? Map<*, *>)?.filterKeys { key -> key != "sequence" } }, (context as? Map<*, *>)?.get("lifecycle"), fullEpoch)
			val old = prior[lane]
			(sample["trigger_type"] as? String)?.let { emit(sample, it, old?.sample, null) }
			if (old != null) {
				if (old.context != semantic) {
					val oldLife = ((old.sample["context"] as? Map<*, *>)?.get("lifecycle") as? Map<*, *>) ?: emptyMap<Any, Any>()
					val life = ((sample["context"] as? Map<*, *>)?.get("lifecycle") as? Map<*, *>) ?: emptyMap<Any, Any>()
					val types = mutableListOf<String>()
					if (life["reset_epoch"] != oldLife["reset_epoch"] || (old.context as? List<*>)?.getOrNull(2) != fullEpoch) types += "T3_RESET"
					if (listOf("assignment_generation", "q_source", "six_dof_source").any { life[it] != oldLife[it] }) types += "T4_ASSIGNMENT"
					if (listOf("proxy_instance", "proxy_name", "proxy_active", "topology_rebuilds").any { life[it] != oldLife[it] }) types += "T5_PROXY"
					if ((old.context as? List<*>)?.firstOrNull() != semantic.first() || types.isEmpty()) types += "T6_CONTEXT"
					for (type in types) emit(sample, type, old.sample, null)
				} else if (q != null && old.q != null) {
					val distance = angle(old.q, q)
					if (distance >= config.thresholdDegrees) {
						emit(sample, "T1_STEP", old.sample, distance)
					}
				}
			}
			val sourceKey = if (sample["stage"] in listOf("bone", "computed")) (context as? Map<*, *>)?.get("tick")
				else (sample["timestamps"] as? Map<*, *>)?.get("source_timestamp_ns") ?: (identity as? Map<*, *>)?.get("sequence")
			val source = (sourceKey as? String)?.toLongOrNull()
			val previousProgress = old?.takeIf { it.context == semantic }
			val progressed = source != null && (previousProgress?.progressValue?.let { source > it } != false)
			val progressMono = if (progressed) frame.mono else previousProgress?.progressMono ?: frame.mono
			val next = Prior(observed(sample, frame.mono, frame.utc), q, semantic, frame.mono, progressMono, if (progressed) source else previousProgress?.progressValue)
			if (old != null && old.context == semantic && old.q != null && q != null && frame.mono - old.observedMono <= sourceProgressGapNs && frame.mono - old.progressMono <= sourceProgressGapNs) {
				if (old.anchor != null) { next.anchor = old.anchor; next.stepped = old.stepped; next.persistent = old.persistent }
				else if (angle(old.q, q) >= config.thresholdDegrees) { next.anchor = old.q; next.stepped = frame.mono }
				val anchor = next.anchor
				if (anchor != null && angle(anchor, q) < config.thresholdDegrees) { next.anchor = null; next.stepped = null }
				if (next.anchor != null && !next.persistent && progressed && frame.mono - (next.stepped ?: frame.mono) >= 2_000_000_000 && (sample["validity"] as? Map<*, *>)?.get("fresh") == true) {
					emit(sample, "T2_PERSISTENT", old.sample, angle(next.anchor!!, q)); next.persistent = true
				}
			}
			prior[lane] = next
		}
		return detections
	}
	private fun process(frame: Frame) {
		frame.exactBefore?.let { before -> retain(before) }
		for (detection in frame.detections) trigger(frame.mono, frame.utc, detection.sample, detection.type, detection.before, detection.degrees)
		retain(frame)
	}
	private fun retain(frame: Frame) {
		val encoded = encode(frame) ?: return
		ring.addLast(encoded); ringBytes += encoded.size
		newestRetainedMono = maxOf(newestRetainedMono, frame.mono)
		// A concurrent reset can overtake a slow rich snapshot; original observation time wins.
		if (ring.size > 1 && ring.elementAt(ring.size - 2).mono > frame.mono) {
			val sorted = ring.sortedBy { it.mono }; ring.clear(); ring.addAll(sorted)
		}
		active?.let { addFrame(it, encoded) }
		while (ring.isNotEmpty() && (newestRetainedMono - ring.first().mono > config.preNs || ring.size > config.maxFrames || ringBytes > config.maxRingBytes)) ringBytes -= ring.removeFirst().size
		ringFrames = ring.size
	}
	private fun addFrame(event: Event, frame: Encoded) {
		if (event.frames.lastOrNull() === frame) return
		if (event.frames.size >= config.maxFrames * 2 + 256 || event.bytes + frame.size > config.maxEventBytes - 64 * 1024) { event.incomplete = true; dropped.incrementAndGet(); return }
		event.frames.add(frame); event.bytes += frame.size; event.coverageEnd = frame.mono
	}
	private fun trigger(now: Long, utc: Long, sample: Map<String, Any?>, type: String, before: Map<String, Any?>?, degrees: Double?, incident: String? = null) {
		var event = active
		if (event == null) {
			if (eventCount >= config.maxEvents) { status = "EXHAUSTED"; missedEvents.incrementAndGet(); return }
			eventCount++
			val id = UUID.randomUUID().toString()
			event = Event(id, now, utc, now + config.postNs, ring.toMutableList(), mutableListOf(), linkedSetOf(incident ?: id), dropped.get(), startedMono, ringBytes)
			active = event
			if (incident == null) publishRequest(id, utc, type)
		}
		if (event.triggers.size >= 128 || event.incidents.size >= 128) { event.incomplete = true; missedEvents.incrementAndGet(); return }
		incident?.let { event.incidents.add(it) }
		val record = base("trigger", utc) + mapOf("event_id" to event.id, "incident_id" to (incident ?: event.id), "trigger_type" to type.substringBefore('_'), "trigger_detail" to type,
			"process_monotonic_ns" to now.toString(), "stage" to sample["stage"], "identity" to sample["identity"], "angular_degrees" to degrees,
			"before" to before, "after" to observed(sample, now, utc))
		val bytes = mapper.writeValueAsBytes(record).size + 1
		if (event.bytes + bytes >= config.maxEventBytes - 8192) { event.incomplete = true; missedEvents.incrementAndGet(); return }
		event.triggers.add(record); event.bytes += bytes
	}
	private fun observed(sample: Map<String, Any?>, mono: Long, utc: Long) = base("sample", utc) + sample + mapOf("process_monotonic_ns" to mono.toString(), "clock_domain" to "monaka_system_nanotime")
	fun reset(state: dev.slimevr.tracking.processor.FullResetDiagnostics.Snapshot) {
		offer(listOf(mapOf("stream_id" to "lifecycle:full_reset", "stage" to "lifecycle", "identity" to emptyMap<String, String>(),
			"trigger_type" to "T3_RESET",
			"timestamps" to mapOf("reset_system_ns" to (state.completedSystemNanos ?: state.startedSystemNanos)?.toString()),
			"validity" to emptyMap<String, Boolean>(), "context" to mapOf("lifecycle" to mapOf("T3_RESET" to state.generation.toString(), "phase" to state.phase)))))
	}
	private fun finish(now: Long, incomplete: Boolean) {
		val event = active ?: return
		active = null
		event.coverageEnd = now
		event.incomplete = event.incomplete || incomplete || dropped.get() > 0 || failures.get() > 0 || event.triggerMono - (event.frames.minOfOrNull { it.mono } ?: event.triggerMono) < config.preNs - config.intervalNs || now < event.deadline
		if (!writerQueue.offer(event)) { missedEvents.incrementAndGet(); failures.incrementAndGet() }
	}
	private fun publishRequest(id: String, utc: Long, type: String) {
		try {
			Files.createDirectories(config.coordination)
			if (Files.list(config.coordination).use { it.limit(257).count() } >= 256) { missedEvents.incrementAndGet(); return }
			val bytes = mapper.writeValueAsBytes(mapOf("schema" to "qjump.request.v1", "incident_id" to id, "origin_component" to "monaka", "origin_process_instance" to processInstance, "wall_clock_utc_ns" to utc.toString(), "trigger_type" to type.substringBefore('_')))
			val temporary = config.coordination.resolve(".$id.pending")
			Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW)
			Files.move(temporary, config.coordination.resolve("$id.json"), StandardCopyOption.ATOMIC_MOVE)
		} catch (_: Exception) { failures.incrementAndGet() }
	}
	private fun pollRequests(now: Long, utc: Long) {
		if (!Files.isDirectory(config.coordination)) return
		try {
			Files.list(config.coordination).use { paths ->
				val files = paths.limit(257).toList()
				if (files.size > 256) { missedEvents.incrementAndGet(); return }
				for (path in files) {
					if (!path.fileName.toString().matches(Regex("[0-9a-fA-F-]{36}\\.json")) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 4096) continue
					val request = mapper.readTree(Files.readAllBytes(path))
					if (request.path("schema").asText() != "qjump.request.v1") continue
					val id = request.path("incident_id").asText()
					if (runCatching { UUID.fromString(id) }.isFailure || id in seenRequests) continue
					if (seenRequests.size >= 256) { missedEvents.incrementAndGet(); continue }
					seenRequests.add(id)
					val requested = request.path("wall_clock_utc_ns").asText().toLongOrNull() ?: continue
					if (!request.path("trigger_type").asText().matches(Regex("T[1-6](_[A-Z_]+)?"))) continue
					if (requested < startedUtc || requested > utc + 1_000_000_000 || utc - requested > 30_000_000_000 || request.path("origin_process_instance").asText() == processInstance) continue
					trigger(now, utc, mapOf("stage" to "coordination", "identity" to emptyMap<String, String>(), "request_wall_clock_utc_ns" to requested.toString(),
						"origin_component" to request.path("origin_component").asText(), "origin_process_instance" to request.path("origin_process_instance").asText()), request.path("trigger_type").asText(), null, null, id)
				}
			}
		} catch (_: Exception) { failures.incrementAndGet() }
	}
	private fun writeStatus() {
		try {
			Files.createDirectories(runDirectory)
			val path = runDirectory.resolve("status-${Thread.currentThread().id}.pending")
			Files.write(path, mapper.writeValueAsBytes(mapOf("schema" to "qjump.status.v1", "component" to "monaka", "process_instance" to processInstance,
				"status" to if (status == "RUNNING") (if (active == null) "ARMED" else "CAPTURING") else status,
				"wall_clock_utc_ns" to wall().toString(), "ring_bytes" to ringBytes.toString(), "ring_frames" to ringFrames.toString(),
				"dropped" to dropped.get().toString(), "missed_events" to missedEvents.get().toString(), "writer_errors" to failures.get().toString(), "written_events" to writtenEvents.get().toString())))
			Files.move(path, runDirectory.resolve("status.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		} catch (_: Exception) { if (failures.getAndIncrement() == 0L) java.util.logging.Logger.getLogger("QJumpDiagnostics").warning("Q-jump status/output unavailable") }
	}
	private fun writeLoop() {
		try {
			while (!actorDone || writerQueue.isNotEmpty()) {
				val event = writerQueue.poll(20, TimeUnit.MILLISECONDS) ?: continue
				event.frames.sortBy { it.mono }
				try {
					Files.createDirectories(runDirectory)
					val path = runDirectory.resolve("${event.id}.jsonl")
					(sink?.invoke(path) ?: Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)).use { output ->
						var written = 0
						fun line(value: Any) { val bytes = mapper.writeValueAsBytes(value); check(written + bytes.size + 1 <= config.maxEventBytes); output.write(bytes); output.write(10); written += bytes.size + 1 }
						line(base("header", event.triggerUtc) + mapOf("event_id" to event.id, "incident_ids" to event.incidents.toList(), "source_identity" to sourceIdentity,
							"capture_start_process_ns" to event.started.toString(), "pre_ns" to config.preNs.toString(), "post_ns" to config.postNs.toString(), "sample_interval_ns" to config.intervalNs.toString(),
							"bounds" to mapOf("ring_bytes" to config.maxRingBytes.toString(), "event_bytes" to config.maxEventBytes.toString(), "frame_bytes" to config.maxFrameBytes.toString(), "queue_frames" to "8", "streams" to "256", "entities" to "16", "max_events" to config.maxEvents.toString())))
						for (frame in event.frames) for (bytes in frame.bytes) { check(written + bytes.size + 1 < config.maxEventBytes - 8192); output.write(bytes); output.write(10); written += bytes.size + 1 }
						for (trigger in event.triggers) line(trigger)
						val anyPostData = event.frames.any { it.mono > event.triggerMono }
						val postData = anyPostData && event.frames.lastOrNull()?.let { event.deadline - it.mono <= config.intervalNs * 2 } == true
						line(base("footer", wall()) + mapOf("event_id" to event.id, "incident_ids" to event.incidents.toList(), "complete" to !event.incomplete,
							"prewindow_complete" to (event.triggerMono - (event.frames.firstOrNull()?.mono ?: event.triggerMono) >= config.preNs - config.intervalNs),
							"post_data_present" to postData, "no_new_post_samples" to !anyPostData,
							"coverage_start_process_ns" to event.frames.firstOrNull()?.mono?.toString(), "coverage_end_process_ns" to event.frames.lastOrNull()?.mono?.toString(),
							"coverage_start_wall_clock_utc_ns" to event.frames.firstOrNull()?.utc?.toString(), "coverage_end_wall_clock_utc_ns" to event.frames.lastOrNull()?.utc?.toString(),
							"capture_window_start_wall_clock_utc_ns" to event.triggerUtc.toString(), "capture_window_end_wall_clock_utc_ns" to wall().toString(),
							"sample_count" to event.frames.sumOf { it.bytes.size }.toString(), "dropped" to dropped.get().toString(), "missed_events" to missedEvents.get().toString(),
							"writer_errors" to failures.get().toString(), "silence_after_last_sample" to (event.frames.lastOrNull()?.mono?.let { event.coverageEnd - it >= config.intervalNs } ?: true)))
						output.flush()
					}
					if (sink == null) java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
					writtenEvents.incrementAndGet()
				} catch (_: Exception) { failures.incrementAndGet(); status = "WRITER_FAILURE"; writeStatus() }
			}
		} finally { if (status == "RUNNING") status = "CLOSED"; writeStatus() }
	}
	override fun close() { closing = true }
	fun awaitTermination(timeoutMillis: Long): Boolean { require(timeoutMillis in 1..5000); actor.join(timeoutMillis); writer.join(timeoutMillis); return !actor.isAlive && !writer.isAlive }
	companion object {
		fun utcNanos(): Long = Instant.now().let { it.epochSecond * 1_000_000_000 + it.nano }
		internal fun quaternion(value: Any?): List<Double>? = (value as? List<*>)?.takeIf { it.size == 4 }?.map { (it as? Number)?.toDouble() ?: return null }?.takeIf { q -> q.all(Double::isFinite) && q.sumOf { it * it } > 1e-20 }
		internal fun angle(a: List<Double>, b: List<Double>): Double = 2 * acos((abs(a.zip(b).sumOf { it.first * it.second }) / sqrt(a.sumOf { it * it } * b.sumOf { it * it })).coerceIn(0.0, 1.0)) * 180 / Math.PI
		fun fromEnvironment(): QJumpRecorder? {
			if (System.getenv("MONAKA_QJUMP_DIAGNOSTICS") != "1") return null
			return runCatching {
				val output = Path.of(requireNotNull(System.getenv("MONAKA_QJUMP_DIAGNOSTICS_DIR")))
				val coordination = Path.of(requireNotNull(System.getenv("QJUMP_CAPTURE_COORDINATION_DIR")))
				val source = runCatching {
					val location = Path.of(QJumpRecorder::class.java.protectionDomain.codeSource.location.toURI())
					val path = location.parent.parent.parent.resolve("provenance/windows.json")
					if (Files.size(path) > 1024 * 1024) emptyMap() else mapOf("standard_provenance" to ObjectMapper().readTree(Files.readAllBytes(path)).path("source"))
				}.getOrDefault(emptyMap())
				QJumpRecorder(Config(output, coordination, System.getenv("MONAKA_QJUMP_THRESHOLD_DEG")?.toDouble() ?: 45.0), sourceIdentity = source)
			}.getOrNull()
		}
	}
}
