package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v3.DecodeResult
import dev.monaka.protocol.v3.EncodeResult
import dev.monaka.protocol.v3.Envelope
import dev.monaka.protocol.v3.MonakaCodec
import dev.monaka.protocol.v3.ObservationDeviceState
import dev.monaka.protocol.v3.TrackerObservation
import dev.monaka.tracking.LogicalTracker
import dev.monaka.tracking.MonakaConfiguration
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Vector3
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.math.abs

/** One focused software route with a real Bridge process and the normal desktop receive/solve hooks. */
object BridgeC3RouteE2E {
	private val mapper = ObjectMapper()
	private val loopback = InetAddress.getByName("127.0.0.1")
	private const val SERVICE_DURATION_MS = 6000
	private const val PUBLISHER = "cross-product-publisher"
	private const val TRACKER = "explicit-logical-hip"
	private const val CONVENTION = "rh_y_up_neg_z_forward"
	private const val TRANSLATION_X = .25

	@JvmStatic
	fun main(args: Array<String>) {
		require(args.size == 4) { "Usage: BridgeC3RouteE2E SERVICE SERVICE_SHA256 C3_KIT NEW_EVIDENCE_DIR" }
		val service = Path.of(args[0]).toRealPath()
		require(Files.isRegularFile(service) && args[1].matches(Regex("[0-9a-f]{64}")))
		val serviceSha = sha256(service)
		require(serviceSha == args[1]) { "Bridge executable differs from the approved SHA256" }
		val kit = Path.of(args[2]).toRealPath()
		val output = Path.of(args[3]).toAbsolutePath().normalize()
		Files.createDirectories(output.parent)
		Files.createDirectory(output)
		val report = linkedMapOf<String, Any?>(
			"result" to "FAIL",
			"bridge_sha256" to serviceSha,
			"c3_kit_sha256" to sha256(kit),
			"physical_hil" to "NOT_RUN",
			"cutover" to "NOT_RUN",
		)
		try {
			run(service, kit, output, report)
			check(sha256(service) == serviceSha) { "Bridge executable changed during E2E" }
			report["result"] = "PASS"
			println("PASS real Bridge C3 observation to calibrated MTP, MonakaVR IK, and explicit loss")
		} finally {
			mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("result.json").toFile(), report)
		}
	}

	private fun run(service: Path, kit: Path, output: Path, report: MutableMap<String, Any?>) {
		report["stage"] = "startup"
		val (pose, state) = ZipFile(kit.toFile()).use { zip ->
			fun fixture(name: String): Envelope = zip.getInputStream(zip.getEntry("fixtures/v3/$name")).use {
				(MonakaCodec.decodeEnvelope(it.readBytes()) as DecodeResult.Success).value
			}
			(fixture("observation.json") as TrackerObservation) to (fixture("device-state.json") as ObservationDeviceState)
		}
		val source = pose.source_id
		val device = pose.device_id
		val key = LogicalTracker(source, TRACKER, PUBLISHER)
		val capabilities = listOf("position", "orientation")
		val bridgeConfig = output.resolve("bridge.json")
		mapper.writerWithDefaultPrettyPrinter().writeValue(bridgeConfig.toFile(), mapOf(
			"namespace" to "monaka.bridge",
			"version" to 3,
			"bridge_id" to PUBLISHER,
			"policy" to "monaka",
			"pose_timeout_ms" to 1000,
			"profiles" to mapOf("basis" to mapOf(
				"convention" to CONVENTION, "position_axes" to listOf(1, 2, 3), "quaternion_axes" to listOf(1, 2, 3, 4),
			)),
			"source_calibrations" to mapOf(source to mapOf(
				"translation" to listOf(TRANSLATION_X, 0.0, 0.0), "rotation" to listOf(0, 0, 0, 1),
			)),
			"mappings" to listOf(mapOf(
				"source_id" to source, "device_id" to device, "tracker_id" to TRACKER, "profile" to "basis",
				"runtime_contract" to mapOf(
					"id" to "position_rotation", "provenance" to "Synthetic input declaration",
					"input_policy" to "STRICT", "output_policy" to "STRICT",
					"input_declaration" to capabilities, "output_declaration" to capabilities,
				),
			)),
		))
		val bridgeConfigSha = sha256(bridgeConfig)
		report["bridge_config_sha256"] = bridgeConfigSha
		val consumerConfig = output.resolve("monaka-mtp.json")
		mapper.writeValue(consumerConfig.toFile(), mapOf(
			"version" to 5, "convention" to CONVENTION, "port" to 0, "timeout_ns" to "3000000000",
			"assignments" to listOf(mapOf("body" to "HIP", "mainTracker" to mapOf(
				"kind" to "mtp", "source_id" to source, "tracker_id" to TRACKER, "publisher_id" to PUBLISHER,
			))),
		))
		report["consumer_config_sha256"] = sha256(consumerConfig)

		val head = Tracker(
			null, 1, "e2e-local-head", trackerPosition = TrackerPosition.HEAD, hasPosition = true, hasRotation = true,
			allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false, isHmd = true,
		).also {
			it.status = TrackerStatus.OK
			it.position = Vector3(0f, 1.7f, 0f)
		}
		val hpm = HumanPoseManager(listOf(head)).also {
			it.setLegTweaksEnabled(false)
			it.skeleton.ikSolver.enabled = false
			it.update()
		}
		val origin = hpm.skeleton.computedHipTracker!!.position
		var before: Runnable? = null
		var after: Runnable? = null
		requireNotNull(MonakaServerIntegration.startIfEnabled(
			true, { MonakaConfiguration.load(consumerConfig) }, { listOf(head) }, hpm.skeleton,
			{ before = it }, { throw it }, registerAfterPose = { after = it }, configurationPath = consumerConfig,
		)).use { desktop ->
			val reserved = List(3) { DatagramSocket(0, loopback) }
			try {
				val (ingress, direct, mirror) = reserved.map { it.localPort }
				val ports = listOf(ingress, desktop.receiver.port, direct, mirror)
				report["ports"] = ports
				report["service_duration_ms"] = SERVICE_DURATION_MS
				reserved[0].close() // The real service binds this ephemeral port.
				val process = ProcessBuilder(
					listOf(service.toString(), bridgeConfig.toString()) + ports.map(Int::toString) +
						listOf("--duration-ms", SERVICE_DURATION_MS.toString()),
				).directory(output.toFile())
					.redirectOutput(output.resolve("bridge-stdout.log").toFile())
					.redirectError(output.resolve("bridge-stderr.log").toFile())
					.apply {
						environment().remove("BRIDGE_QJUMP_DIAGNOSTICS")
						environment().remove("BRIDGE_QJUMP_DIAGNOSTICS_DIR")
					}.start()
				try {
					fun tick() {
						requireNotNull(before).run()
						hpm.update()
						requireNotNull(after).run()
						check(desktop.receiver.isAlive && desktop.receiver.failure == null)
					}
					fun await(stage: String, condition: () -> Boolean) {
						report["stage"] = stage
						val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
						do {
							check(process.isAlive) { "Bridge exited before $stage" }
							tick()
							if (condition()) return
							check(System.nanoTime() < deadline) {
								"$stage timed out. Consumer diagnostics: ${desktop.runtime.inbox.diagnostics()}"
							}
							Thread.sleep(5)
						} while (true)
					}
					val health = bridgeConfig.resolveSibling(bridgeConfig.fileName.toString() + ".status.json")
					fun readHealth(): JsonNode? =
						try {
							mapper.readTree(health.toFile())
						} catch (_: IOException) {
							null
						}

					await("bridge_ready") {
						val status = readHealth()
						status != null && status.path("publisher_id").asText() == PUBLISHER &&
							status.path("policy").asText() == "monaka" &&
							status.path("config_sha256").asText() == bridgeConfigSha
					}
					DatagramSocket(0, loopback).use { sender ->
						fun publish(envelope: Envelope, name: String) {
							val bytes = (MonakaCodec.encodeEnvelope(envelope) as EncodeResult.Success).value
							Files.write(output.resolve("$name.json"), bytes)
							sender.send(DatagramPacket(bytes, bytes.size, loopback, ingress))
						}
						fun sendPose(sequence: Long, offset: Double) {
							val next = pose.copy(
								sequence = sequence, timestamp_ns = pose.timestamp_ns + sequence, sent_at_ns = pose.timestamp_ns + sequence,
								convention = CONVENTION, capabilities = capabilities, linear_velocity = null, angular_velocity = null, linear_acceleration = null,
								position = listOf(origin.x.toDouble() - TRANSLATION_X + offset, origin.y.toDouble(), origin.z.toDouble()),
							)
							publish(next, "observation-$sequence")
						}
						fun awaitPose(sequence: Long, expectedX: Double) {
							await("c3_admission_$sequence") {
								desktop.runtime.mtp.sampleFor(key)?.c3Pose?.input?.sequence == sequence
							}
							val admitted = requireNotNull(desktop.runtime.mtp.sampleFor(key))
							val wire = requireNotNull(admitted.c3Pose)
							check(wire.convention == CONVENTION && wire.input.device_id == device && wire.capabilities == capabilities)
							val expected = listOf(expectedX, origin.y.toDouble(), origin.z.toDouble())
							check(requireNotNull(wire.position).zip(expected).all { (actual, target) -> abs(actual - target) < 1e-6 })
							val resolved = desktop.runtime.pipeline.resolve(TrackerPosition.HIP, desktop.runtime.clock())
							check(resolved.position?.sourceId == key.observationId && resolved.rotation?.sourceId == key.observationId)
							val provenance = requireNotNull(desktop.runtime.pipeline.observations().single { it.sourceId == key.observationId }.provenance)
							check(provenance.coordinateConvention == CONVENTION && provenance.space == null && provenance.mappingRevision == null)
							Files.write(output.resolve("admitted-$sequence.json"), (MonakaCodec.encodeEnvelope(wire) as EncodeResult.Success).value)
						}

						sendPose(1, 0.0)
						awaitPose(1, origin.x.toDouble())
						hpm.skeleton.ikSolver.resetOffsets()
						hpm.skeleton.ikSolver.enabled = true
						tick()
						val baselineX = hpm.skeleton.computedHipTracker!!.position.x
						sendPose(2, .15)
						awaitPose(2, origin.x.toDouble() + .15)
						await("computed_hip_moves") { hpm.skeleton.computedHipTracker!!.position.x - baselineX > .005f }
						report["observed_computed_hip_dx_m"] = hpm.skeleton.computedHipTracker!!.position.x - baselineX

						check(desktop.runtime.mtp.sampleFor(key) != null)
						publish(state.copy(
							source_id = source, device_id = device, session_id = pose.session_id, clock_id = pose.clock_id,
							sequence = 1, timestamp_ns = pose.timestamp_ns + 3, sent_at_ns = pose.timestamp_ns + 3,
							convention = CONVENTION, capabilities = capabilities, presence = "absent", tracking_state = "lost", modality = "none", battery = null,
						), "observation-loss")
						await("explicit_loss") {
							val lossAccepted = readHealth()?.path("devices")?.any { deviceState ->
								deviceState.path("source").asText() == source && deviceState.path("device").asText() == device &&
									deviceState.path("absent").asBoolean() && deviceState.path("state_sequence").asLong() == 1L
							} == true
							desktop.runtime.mtp.sampleFor(key) == null &&
								desktop.runtime.pipeline.resolve(TrackerPosition.HIP, desktop.runtime.clock()).position == null &&
								lossAccepted
						}
					}
					check(process.waitFor(8, TimeUnit.SECONDS) && process.exitValue() == 0) { "Bridge did not stop successfully" }
					check(sha256(bridgeConfig) == bridgeConfigSha) { "Bridge changed its input configuration" }
					report["consumer_diagnostics"] = desktop.runtime.inbox.diagnostics()
					report["stage"] = "complete"
				} finally {
					if (process.isAlive) {
						process.destroy()
						if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
					}
				}
			} finally {
				reserved.forEach(DatagramSocket::close)
			}
		}
	}

	private fun sha256(path: Path): String =
		MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toHexString()
}
