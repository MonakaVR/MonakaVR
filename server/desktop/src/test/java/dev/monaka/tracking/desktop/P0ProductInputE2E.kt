package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v2.*
import dev.monaka.tracking.MonakaConfiguration
import dev.slimevr.desktop.platform.ProtobufMessages.ProtobufMessage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Offline orchestrator only. Public wire remains byte-for-byte from the actual
 * adapter/Bridge/WPF run. Production consumer never reads the producer config.
 * Reuses the normal integration probe; no parallel solver or hand-made teacher.
 */
object P0ProductInputE2E {
	@JvmStatic fun main(args: Array<String>) {
		require(args.size == 4) { "BRIDGE_RUN NEW_EVIDENCE NODE GUI_RUNNER_JS" }
		val bridge = Path.of(args[0]).toAbsolutePath()
		val out = Path.of(args[1]).toAbsolutePath()
		require(!Files.exists(out)); Files.createDirectories(out)
		val mapper = ObjectMapper()
		fun hash(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
		fun write(path: Path, value: Any) = Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardOpenOption.CREATE_NEW)
		val result = linkedMapOf<String, Any?>("software" to "RUNNING", "hardware" to "NOT_RUN", "physical_slime_udp" to "NOT_RUN", "cutover" to "NOT_AUTHORIZED")
		try {
			val bridgeResult = mapper.readTree(bridge.resolve("result.json").toFile())
			check(bridgeResult["software"].asText() == "PASS_SCOPED_BACKEND_BRIDGE_GUI_OUTPUT")
			check(bridgeResult["saved_config_sha256"].asText() == hash(bridge.resolve("config.json")))
			val document = mapper.readTree(bridge.resolve("config.json").toFile())
			val revision = document["mapping_revision"].asLong()
			fun wires(phase: String): List<String> = Files.readAllLines(bridge.resolve("$phase/captured.jsonl")).map(mapper::readTree)
				.filter { it["channel"].asText() == "monaka" && it["envelope"]["type"].asText() == "pose" && it["envelope"]["mapping_revision"].asLong() == revision }
				.map { it["wire"].asText().also { wire -> check(MonakaCodec.decodeEnvelope(wire.toByteArray()) is DecodeResult.Success) } }
			val first = wires("first"); val restart = wires("restart")
			val poses = first.map { (MonakaCodec.decodeEnvelope(it.toByteArray()) as DecodeResult.Success).value as MtpPose }
			// This is the operator's explicit fixture plan: saved mapping rows in
			// order -> HIP, UPPER_CHEST. Datagram arrival order never assigns bodies.
			val keys = document["mappings"].map { mapping -> poses.first { it.source_id == mapping["source_id"].asText() && it.input.device_id == mapping["device_id"].asText() && it.tracker_id == mapping["tracker_id"].asText() } }
			check(keys.size == 2 && keys.map { it.source_id }.distinct().size == 2 && keys.map { it.tracker_id }.distinct().size == 1)
			check(restart.isNotEmpty())
			val config = out.resolve("consumer.json")
			MonakaConfiguration(keys.first().coordinate_space, port = 0).save(config)
			val plan = out.resolve("wire-plan.json")
			write(plan, mapOf("first" to first, "restart" to restart, "bindings" to keys.mapIndexed { i, p -> mapOf("body" to if(i == 0) "HIP" else "UPPER_CHEST", "publisher" to p.publisher_id, "source" to p.source_id, "tracker" to p.tracker_id) }))
			result["inputs"] = listOf("config.json", "result.json", "first/captured.jsonl", "restart/captured.jsonl").associateWith { hash(bridge.resolve(it)) }
			result["bridge_configuration_revision"] = revision
			result["wire_plan_sha256"] = hash(plan)
			result["operator_body_plan"] = document["mappings"].mapIndexed { i, mapping -> mapOf("body" to if(i == 0) "HIP" else "UPPER_CHEST", "source" to mapping["source_id"].asText(), "device" to mapping["device_id"].asText(), "tracker" to mapping["tracker_id"].asText(), "world" to mapping["world"], "mount" to mapping["mount"]) }
			result["public_pose_counts"] = mapOf("first" to first.size, "restart" to restart.size)
			// A manifest classpath avoids Windows command-length limits without
			// mutating dependencies or shipping a different product artifact.
			val classpath = out.resolve("probe-classpath.jar")
			result["classpath_files"] = System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { Path.of(it) }.filter { Files.isRegularFile(it) }.associate { it.toAbsolutePath().toString() to hash(it) }
			result["actual_class_sha256"] = listOf(P0ProductInputE2E::class.java, NormalRuntimeProcessProbe::class.java, MonakaServerIntegration::class.java, dev.monaka.tracking.MonakaRuntime::class.java).associate { type -> type.name to type.getResourceAsStream("${type.simpleName}.class")!!.use { stream -> MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()).joinToString("") { "%02x".format(it) } } }
			val manifest = Manifest().also { it.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"; it.mainAttributes[Attributes.Name.CLASS_PATH] = System.getProperty("java.class.path").split(java.io.File.pathSeparator).joinToString(" ") { Path.of(it).toUri().toASCIIString() } }
			JarOutputStream(Files.newOutputStream(classpath, StandardOpenOption.CREATE_NEW), manifest).close()
			val proofs = mutableListOf<Any>()
			var saved: ByteArray? = null
			for (run in 0..1) {
				val dir = out.resolve("consumer-$run"); Files.createDirectory(dir)
				val evidence = dir.resolve("runtime"); val ready = dir.resolve("ready.json")
				val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-cp", classpath.toString(), NormalRuntimeProcessProbe::class.java.name, config.toString(), ready.toString(), evidence.toString(), "-", "150")
				val process = ProcessBuilder(command).redirectOutput(dir.resolve("jvm.stdout.log").toFile()).redirectError(dir.resolve("jvm.stderr.log").toFile()).start()
				write(dir.resolve("owned-process.json"), mapOf("pid" to process.pid(), "command" to command))
				try {
					val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
					while (!Files.exists(ready)) { check(process.isAlive && System.nanoTime() < deadline) { "Normal integration probe not ready; see $dir" }; Thread.sleep(20) }
					val nodeCommand = ProcessBuilder(args[2], args[3], ready.toString(), plan.toString(), dir.resolve("gui.json").toString(), if(run == 0) "save" else "reload")
						.redirectOutput(dir.resolve("gui.stdout.log").toFile()).redirectError(dir.resolve("gui.stderr.log").toFile())
					val nodeTemp=Files.createDirectory(dir.resolve("browser-temp"))
					nodeCommand.environment()["TEMP"]=nodeTemp.toString(); nodeCommand.environment()["TMP"]=nodeTemp.toString()
					nodeCommand.environment()["NODE_COMPILE_CACHE"]=dir.resolve("node-code-cache").toString()
					val node=nodeCommand.start()
					try { check(node.waitFor(120, TimeUnit.SECONDS)) { "GUI fixture deadline" }; check(node.exitValue() == 0) { "Actual React/normal RPC fixture failed; see $dir" } }
					finally { if(node.isAlive) { node.destroy(); if(!node.waitFor(3, TimeUnit.SECONDS)) node.destroyForcibly() } }
					check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { "Normal integration shutdown failed; see $dir" }
				} finally { if(process.isAlive) { process.destroy(); if(!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly() } }
				val reloaded = MonakaConfiguration.load(config)
				check(reloaded.positionCorrection == null) { "No hand-authored position activation DTO" }
				val assignments = reloaded.assignments.snapshot().targets
				val readyDocument=mapper.readTree(ready.toFile())
				for ((i, key) in keys.withIndex()) {
					val target = dev.slimevr.tracking.trackers.TrackerPosition.valueOf(if(i == 0) "HIP" else "UPPER_CHEST")
					check(assignments.getValue(target).sourceSelection?.sixDofSource?.mtp == dev.monaka.tracking.LogicalTracker(key.source_id,key.tracker_id,key.publisher_id))
					check(assignments.getValue(target).sourceSelection?.qSource?.observationId == readyDocument["imu_sources"][i].asText())
				}
				if(saved != null) check(saved.contentEquals(Files.readAllBytes(config))) { "Consumer restart changed saved choices" }
				saved = Files.readAllBytes(config)
				val frames = Files.readAllLines(evidence.resolve("same-frame.jsonl")).filter(String::isNotBlank).map(mapper::readTree)
				check(frames.any { it["position_correction_detail"]?.get("learning")?.asText() == "UPDATED" }) { "No actual position learning" }
				check(frames.any { it["position_correction_detail"]?.get("continuity")?.asText() == "FALLBACK_ACTIVE" }) { "No solver fallback" }
				check(frames.any { it["position_correction_detail"]?.get("continuity")?.asText() == "REACQUIRING" }) { "No solver reacquisition" }
				val guided = frames.flatMap { it["body_rotation_guidance"].toList() }.filter { it["ready"].asBoolean() }.map { it["target"].asText() }.toSet()
				check(guided.containsAll(listOf("HIP", "UPPER_CHEST"))) { "Independent Q learner not ready for both supported bodies: $guided" }
				fun vec(value: com.fasterxml.jackson.databind.JsonNode?) = value?.takeIf { it.isArray && (it.size() == 3 || it.size() == 4) && it.all { n -> n.isNumber && n.asDouble().isFinite() } }?.map { it.asDouble() }
				fun distance(a: List<Double>, b: List<Double>): Double {
					check(a.size == 3 && b.size == 3)
					return sqrt(a.zip(b).sumOf { (x,y) -> (x-y)*(x-y) })
				}
				fun angle(a: List<Double>, b: List<Double>): Double {
					check(a.size == 4 && b.size == 4)
					val norm=sqrt(a.sumOf { it*it }*b.sumOf { it*it })
					check(norm>0)
					return 2*acos((abs(a.zip(b).sumOf { (x,y) -> x*y })/norm).coerceIn(0.0,1.0))
				}
				fun detail(frame: com.fasterxml.jackson.databind.JsonNode) = frame["position_correction_detail"]?.takeIf { it.isObject }
				fun correctedPrediction(frame: com.fasterxml.jackson.databind.JsonNode): List<Double>? {
					val prediction=vec(detail(frame)?.get("prediction")) ?: return null
					val correction=vec(detail(frame)?.get("correction_world")) ?: return null
					return prediction.zip(correction).map { (p,c) -> p+c }
				}
				val learned = frames.withIndex().filter { (_, f) -> detail(f)?.get("learning")?.asText() == "UPDATED" && correctedPrediction(f) != null && vec(detail(f)?.get("measured_teacher")) != null }
				check(learned.size >= 2)
				val firstLearn = learned.first()
				val firstLoss = (firstLearn.index+1 until frames.size).first { vec(detail(frames[it])?.get("measured_teacher")) == null }
				val continuous = learned.filter { it.index < firstLoss }.map { (_, f) -> distance(correctedPrediction(f)!!, vec(detail(f)?.get("measured_teacher"))!!) }
				check(continuous.size >= 2 && continuous.drop(1).min() < continuous.first()-1e-6) { "Continuous FULL samples did not numerically improve corrected prediction: $continuous" }
				fun continuity(index: Int) = detail(frames[index])?.get("continuity")?.asText()
				val fallbackIndex = frames.indices.firstOrNull { index ->
					val frame = frames[index]
					val d = detail(frame) ?: return@firstOrNull false
					val prediction = vec(d["prediction"]) ?: return@firstOrNull false
					val correction = vec(d["correction_world"]) ?: return@firstOrNull false
					val applied = vec(d["applied_position"]) ?: return@firstOrNull false
					val solver = vec(d["solver_position"]) ?: return@firstOrNull false
					if (d["continuity"]?.asText() != "FALLBACK_ACTIVE" || distance(prediction.zip(correction).map { (p,c) -> p+c }, applied) > 1e-5 || distance(applied, solver) > 1e-5) return@firstOrNull false
					val resolved = frame["resolved"].firstOrNull { it["target"].asText() == "HIP" } ?: return@firstOrNull false
					val guidance = frame["body_rotation_guidance"].firstOrNull { it["target"].asText() == "HIP" } ?: return@firstOrNull false
					val fallback = vec(guidance["fallback_xyzw"]) ?: return@firstOrNull false
					val physical = frame["observations"].firstOrNull { it["identity"].asText() == resolved["rotation_owner"]?.asText() } ?: return@firstOrNull false
					val rawQ = vec(physical["rotation_xyzw"]) ?: return@firstOrNull false
					val committed = vec(resolved["rotation_xyzw"]) ?: return@firstOrNull false
					val nextTransition = (index + 1 until frames.size).firstOrNull { continuity(it) != "FALLBACK_ACTIVE" }
					angle(committed, fallback) < 1e-4 && angle(committed, rawQ) > 1e-4 &&
						nextTransition != null && continuity(nextTransition) == "REACQUIRING"
				}
				check(fallbackIndex != null) { "No loss episode committed corrected Q and then reacquired Main" }
				val correctedFallback = frames[fallbackIndex]
				fun hipResolved(frame: com.fasterxml.jackson.databind.JsonNode) = frame["resolved"].first { it["target"].asText()=="HIP" }
				val teacherFrame=frames.take(fallbackIndex).last { frame -> frame["observations"].any { it["target"].asText()=="HIP" && vec(it["position"])!=null } }
				val teacherQ=vec(hipResolved(teacherFrame)["rotation_xyzw"])!!
				val correctedQ=vec(hipResolved(correctedFallback)["rotation_xyzw"])!!
				val physicalQ=vec(correctedFallback["observations"].first { it["identity"].asText()==hipResolved(correctedFallback)["rotation_owner"].asText() }["rotation_xyzw"])!!
				check(angle(correctedQ,teacherQ)<angle(physicalQ,teacherQ)-1e-4) { "Learned Q fallback did not improve alignment with the actual preceding teacher" }
				check(frames.any { frame ->
					val d=detail(frame) ?: return@any false
					val hipResolved=frame["resolved"].firstOrNull { it["target"].asText()=="HIP" } ?: return@any false
					vec(d["measured_teacher"])==null && vec(hipResolved["position"])==null && vec(d["solver_position"])!=null && d["solver_position_reference"].asText()=="IK_EFFECTIVE_TARGET"
				}) { "Derived fallback position was not kept separate from unavailable raw Main P" }
				val reacquire = frames.withIndex().first { (index, frame) -> index>fallbackIndex && detail(frame)?.get("continuity")?.asText()=="REACQUIRING" }
				val previous=frames[reacquire.index-1]
				val solverDelta=distance(vec(detail(previous)?.get("solver_position"))!!,vec(detail(reacquire.value)?.get("solver_position"))!!)
				check(solverDelta < .001) { "First reacquisition jumps at actual solver input: $solverDelta" }
				fun computed(frame: com.fasterxml.jackson.databind.JsonNode) = vec(frame["computed_trackers"].first { it["target"].asText()=="HIP" }["position"])!!
				fun computedRotation(frame: com.fasterxml.jackson.databind.JsonNode) = vec(frame["computed_trackers"].first { it["target"].asText()=="HIP" }["rotation_xyzw"])!!
				val firstVirtualDelta=distance(computed(previous),computed(reacquire.value))
				check(firstVirtualDelta < .02) { "First reacquisition jumps in computed output: $firstVirtualDelta" }
				val firstRotationDelta=angle(vec(hipResolved(previous)["rotation_xyzw"])!!,vec(hipResolved(reacquire.value)["rotation_xyzw"])!!)
				check(firstRotationDelta < .02) { "First reacquisition jumps at solver Q: $firstRotationDelta" }
				val firstVirtualRotationDelta=angle(computedRotation(previous),computedRotation(reacquire.value))
				check(firstVirtualRotationDelta < .02) { "First reacquisition jumps at computed virtual Q: $firstVirtualRotationDelta" }
				val converged=frames.drop(reacquire.index+1).firstOrNull { frame ->
					val d=detail(frame) ?: return@firstOrNull false
					val teacher=vec(d["measured_teacher"]) ?: return@firstOrNull false
					val solver=vec(d["solver_position"]) ?: return@firstOrNull false
					val teacherQ=frame["observations"].firstOrNull { it["target"].asText()=="HIP" && vec(it["position"])!=null }?.get("rotation_xyzw")?.let(::vec) ?: return@firstOrNull false
					d["continuity"].asText()=="MAIN_DIRECT" && distance(teacher,solver)<1e-4 && angle(teacherQ,vec(hipResolved(frame)["rotation_xyzw"])!!)<1e-3 && angle(teacherQ,computedRotation(frame))<.01
				}
				check(converged!=null) { "Subsequent FULL input did not numerically converge to actual Main position" }
				check(distance(computed(converged),computed(previous)) > 1e-6) { "IK computed position never changed from fallback" }
				val final = mapper.readTree(evidence.resolve("final-proof.json").toFile())
				val bytes = Files.readAllLines(evidence.resolve("virtual-output.ndjson")).filter(String::isNotBlank).map { ProtobufMessage.parseFrom(Base64.getDecoder().decode(it)) }
				val hip = final["virtual_trackers"].first { it["target"]?.asText() == "HIP" }
				val wire = bytes.last { it.hasPosition() && it.position.trackerId == hip["id"].asInt() }.position
				val actual = listOf(wire.x, wire.y, wire.z, wire.qx, wire.qy, wire.qz, wire.qw)
				val expected = hip["position"].toList() + hip["rotation_xyzw"].toList()
				check(actual.zip(expected).all { (a,b) -> a.isFinite() && abs(a.toDouble()-b.asDouble()) < 1e-5 }) { "Serialized virtual bytes do not match actual computed HIP" }
				val virtualHip=bytes.filter { it.hasPosition() && it.position.trackerId==hip["id"].asInt() }.map { message -> message.position.let { p -> listOf(p.x,p.y,p.z,p.qx,p.qy,p.qz,p.qw).map(Float::toDouble) } }
				var virtualAt=-1
				val keyframes=listOf(firstLearn.value,correctedFallback,reacquire.value,converged)
				for(frame in keyframes) {
					val expectedPose=computed(frame)+computedRotation(frame)
					virtualAt=(virtualAt+1 until virtualHip.size).firstOrNull { index -> virtualHip[index].zip(expectedPose).all { (a,b) -> abs(a-b)<1e-5 } } ?: error("Learning/loss/reacquisition/convergence keyframe absent from ordered serialized virtual bytes")
				}
				check(!mapper.readTree(evidence.resolve("closed.json").toFile())["receiver_alive"].asBoolean())
				proofs += mapOf("run" to run, "frames" to frames.size, "guided_bodies" to guided, "virtual_messages" to bytes.size, "config_sha256" to hash(config), "gui_receipt_sha256" to hash(dir.resolve("gui.json")), "first_corrected_prediction_error" to continuous.first(), "later_corrected_prediction_error" to continuous.drop(1).min(), "raw_q_teacher_angle" to angle(physicalQ,teacherQ), "corrected_q_teacher_angle" to angle(correctedQ,teacherQ), "first_reacquire_solver_delta" to solverDelta, "first_reacquire_rotation_delta" to firstRotationDelta, "first_reacquire_computed_delta" to firstVirtualDelta, "first_reacquire_computed_rotation_delta" to firstVirtualRotationDelta, "ordered_virtual_keyframes" to keyframes.size)
			}
			result["runs"] = proofs
			result["boundary"] = "actual adapter/Bridge/WPF MVIP bytes -> actual UDP consumer -> actual React handlers/RPC/CAS -> normal Integration/HPM/learners/IK -> serialized virtual bytes; explicit software physical-category IMU and HMD transports"
			result["software"] = "PASS"
		} catch(t: Throwable) { result["software"] = "FAIL"; result["error"] = t.toString(); throw t }
		finally { write(out.resolve("result.json"), result) }
	}
}
