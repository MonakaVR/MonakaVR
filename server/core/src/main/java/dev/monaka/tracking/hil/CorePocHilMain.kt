package dev.monaka.tracking.hil

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.protocol.v2.DecodeResult
import dev.monaka.protocol.v2.MonakaCodec
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.tracking.*
import dev.monaka.tracking.mtp.MtpPoseAdapter
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Local diagnostic carrier parser. Units and quaternion convention match Common Pose. */
object CorePocHilJson {
	val mapper = ObjectMapper()
	private fun text(n: JsonNode, key: String): String = n.required(key).also { require(it.isTextual) }.textValue()
	private fun number(n: JsonNode, key: String): Long = n.required(key).also { require(it.isIntegralNumber && it.canConvertToLong()) }.longValue()
	private fun vector(n: JsonNode): Vector3 {
		require(n.isArray && n.size() == 3 && n.all { it.isNumber && it.doubleValue().isFinite() })
		return Vector3(n[0].floatValue(), n[1].floatValue(), n[2].floatValue()).also { require(listOf(it.x, it.y, it.z).all(Float::isFinite)) }
	}
	private fun quaternion(n: JsonNode): Quaternion {
		require(n.isArray && n.size() == 4 && n.all { it.isNumber && it.doubleValue().isFinite() })
		return Quaternion(n[3].floatValue(), n[0].floatValue(), n[1].floatValue(), n[2].floatValue()).also {
			require(listOf(it.w, it.x, it.y, it.z).all(Float::isFinite) && it.lenSq().isFinite() && it.lenSq() > 1e-10f)
		}
	}
	fun config(n: JsonNode): CorePocHilConfig {
		val s = n.required("space")
		val base = CorePocHilConfig(CoordinateSpace(text(s, "id"), text(s, "convention"), number(s, "revision")),
			text(n, "mainSourceId"), text(n, "imuSourceId"), text(n, "hmdSourceId"),
			n.required("operatorConfirmedSameWorld").booleanValue(), n.required("operatorConfirmedBodyFrames").booleanValue(),
			ContinuityTuning(number(n, "dwellMs"), number(n, "blendMs"), number(n, "fallbackBlendMs")),
			n.path("correctionEnabled").asBoolean(true),
			sameTrackerCanonicalIdentity = n.path("sameTrackerCanonicalIdentity")
				.takeIf { !it.isMissingNode && !it.isNull }?.let { text(n, "sameTrackerCanonicalIdentity") })
		fun value(name: String, default: Double) = n.path(name).takeIf { !it.isMissingNode }?.let { require(it.isNumber); it.doubleValue() } ?: default
		return base.copy(positionTrackingTauSeconds = value("positionTrackingTauSeconds", base.positionTrackingTauSeconds),
			positionRecoveryTauSeconds = value("positionRecoveryTauSeconds", base.positionRecoveryTauSeconds),
			positionHoldNanos = n.path("positionHoldNanos").takeIf { !it.isMissingNode }?.let { number(n, "positionHoldNanos") } ?: base.positionHoldNanos,
			positionDecayTauSeconds = value("positionDecayTauSeconds", base.positionDecayTauSeconds),
			maxPositionResidualMeters = value("maxPositionResidualMeters", base.maxPositionResidualMeters),
			maxPositionCorrectionMeters = value("maxPositionCorrectionMeters", base.maxPositionCorrectionMeters),
			maxPositionCorrectionRate = value("maxPositionCorrectionRate", base.maxPositionCorrectionRate),
			maxPositionUpdateStep = value("maxPositionUpdateStep", base.maxPositionUpdateStep),
			rotationTrackingTauSeconds = value("rotationTrackingTauSeconds", base.rotationTrackingTauSeconds),
			rotationRecoveryTauSeconds = value("rotationRecoveryTauSeconds", base.rotationRecoveryTauSeconds),
			maxAngularCorrectionRate = value("maxAngularCorrectionRate", base.maxAngularCorrectionRate))
	}
	private fun provenance(n: JsonNode, space: CoordinateSpace) = ObservationSampleProvenance(number(n, "sequence"),
		number(n, "sampleAtNanos"), text(n, "sourceEpoch"), text(n, "calibrationEpoch"),
		n.path("mappingRevision").takeIf { !it.isMissingNode && !it.isNull }?.let { number(n, "mappingRevision") }, space,
		n.path("commonWorldEpoch").takeIf { !it.isMissingNode && !it.isNull }?.let { text(n, "commonWorldEpoch") })
	fun frame(n: JsonNode, c: CorePocHilConfig): CorePocInputFrame {
		fun observation(key: String): PoseObservation {
			val o = n.required(key)
			val p = o.path("positionMeters").takeIf { !it.isMissingNode && !it.isNull }?.let(::vector)
			val q = o.path("quaternionXyzw").takeIf { !it.isMissingNode && !it.isNull }?.let(::quaternion)
			val valid = o.required("valid").also { require(it.isBoolean) }.booleanValue()
			return PoseObservation(text(o, "sourceId"), TrackerPosition.HIP, number(o, "sampleAtNanos"), position = p, rotation = q,
				positionQuality = if (valid && p != null) ObservationQuality.TRACKED else ObservationQuality.UNAVAILABLE,
				rotationQuality = if (valid && q != null) ObservationQuality.TRACKED else ObservationQuality.UNAVAILABLE,
				modality = if (!valid) TrackingModality.NONE else if (p == null) TrackingModality.ROTATION_ONLY else TrackingModality.FULL,
				provenance = provenance(o, c.space), correctionRotation = q)
		}
		val h = n.required("hmd")
		require(h.required("valid").isBoolean && h.required("valid").booleanValue()) { "Normal runtime HMD/root must be available for this solver" }
		val envelope = n.path("mainMtp").takeIf { !it.isMissingNode }
		require(envelope == null || !n.has("main")) { "Supply exactly one Main input representation" }
		val main = if (envelope == null) observation("main") else {
			val decoded = MonakaCodec.decodeEnvelope(mapper.writeValueAsBytes(envelope))
			require(decoded is DecodeResult.Success && decoded.value is MtpPose) { "Main requires an accepted pinned-codec MTP pose" }
			val pose = decoded.value as MtpPose
			require(pose.coordinate_space == c.space)
			val receipt = number(n, "mainReceivedAtNanos")
			val age = pose.sent_at_ns - pose.timestamp_ns
			require(age >= 0 && receipt >= age && receipt <= number(n, "timestampNanos"))
			MtpPoseAdapter().adapt(pose, TrackerPosition.HIP, receipt - age)
		}
		val identity = n.path("sameTracker").takeIf { !it.isMissingNode && !it.isNull }?.let {
			val present = it.required("trackerPresent").also { value -> require(value.isBoolean) }.booleanValue()
			SameTrackerInputIdentity(text(it, "sixDofTrackerIdentity"), text(it, "imuTrackerIdentity"), present)
		}
		return CorePocInputFrame(number(n, "timestampNanos"), number(n, "sequence"), main, observation("imu"),
			RawHmdPoseInput(RawSourceIdentity(text(h, "sourceId"), RawSourceKind.RAW_HMD, isHmd = true),
				vector(h.required("positionMeters")), quaternion(h.required("quaternionXyzw")), c.space, provenance(h, c.space)),
			envelope?.let(mapper::writeValueAsString), identity)
	}
}

/** Controls cannot acquire references to correction, solver or Fusion internals. */
class CorePocHilControl(private val session: CorePocHilSession, private val sessionDirectory: Path) {
	fun execute(request: JsonNode): Map<String, Any?> = when (request.required("op").textValue()) {
		"status" -> session.status()
		"capture-start" -> {
			val name = request.path("name").asText("capture.jsonl")
			require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.jsonl")))
			session.captureStart(sessionDirectory.resolve(name)); session.status()
		}
		"capture-stop" -> { session.captureStop(); session.status() }
		"6dof-valid" -> {
			val value = request.required("value").textValue(); require(value == "on" || value == "off")
			session.gate.setAvailable(value == "on"); session.status()
		}
		"mark" -> { session.mark(request.required("note").textValue()); session.status() }
		"shutdown" -> { session.close(); session.status() }
		"input" -> session.accept(CorePocHilJson.frame(request.required("frame"), session.config))
		else -> throw IllegalArgumentException("Unknown operation; controls only availability, capture and lifecycle")
	}
}

/** File-based local IPC only. Requests and sources must be explicitly supplied; no network bind. */
object CorePocHilMain {
	@JvmStatic fun main(args: Array<String>) {
		val arguments = args.toList()
		fun option(name: String) = arguments.indexOf(name).let { require(it >= 0 && it + 1 < arguments.size); arguments[it + 1] }
		val directory = Path.of(option("--session")).toAbsolutePath().normalize()
		Files.createDirectories(directory)
		val configPath = Path.of(option("--config"))
		val configBytes = Files.readAllBytes(configPath)
		val config = CorePocHilJson.config(CorePocHilJson.mapper.readTree(configBytes))
		val hash = MessageDigest.getInstance("SHA-256").digest(configBytes).joinToString("") { "%02x".format(it) }
		CorePocHilSession(config, "--hil" in arguments).use { session ->
			val control = CorePocHilControl(session, directory)
			val requests = directory.resolve("requests.jsonl")
			Files.createFile(requests) // Refuse a reused session, including stale requests.
			publish(directory.resolve("ready.json"), CorePocHilJson.mapper.writeValueAsString(
				mapOf("status" to session.status(), "configSha256" to hash)))
			var offset = 0L
			var partial = byteArrayOf()
			var shutdown = false
			while (!shutdown) {
				java.io.RandomAccessFile(requests.toFile(), "r").use { stream ->
					stream.seek(offset)
					val bytes = ByteArray(minOf(stream.length() - offset, 65_536).toInt())
					stream.readFully(bytes); offset = stream.filePointer
					partial += bytes
					require(partial.size <= 1_048_576) { "Local IPC request exceeds 1MiB" }
				}
				while (10.toByte() in partial && !shutdown) {
					val end = partial.indexOf(10.toByte())
					val line = partial.copyOfRange(0, end).toString(Charsets.UTF_8); partial = partial.copyOfRange(end + 1, partial.size)
					val request = CorePocHilJson.mapper.readTree(line)
					val id = request.required("id").textValue(); require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
					val result = control.execute(request) // A failed source/control request stops this diagnostic session visibly.
					publish(directory.resolve("response-$id.json"), CorePocHilJson.mapper.writeValueAsString(result))
					shutdown = request.required("op").textValue() == "shutdown"
				}
				if (!shutdown) Thread.sleep(10) // IPC poll cadence, never a tracking/recovery retry.
			}
		}
	}
	private fun publish(path: Path, json: String) {
		val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
		Files.writeString(temporary, json, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
		Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
	}
}
