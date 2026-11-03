package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.slimevr.desktop.platform.ProtobufMessages.Position
import dev.slimevr.desktop.platform.ProtobufMessages.ProtobufMessage
import dev.slimevr.desktop.platform.ProtobufMessages.TrackerAdded
import dev.slimevr.desktop.platform.ProtobufMessages.UserAction
import dev.slimevr.desktop.platform.TransportSessionHandle
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SerializedOutputDiagnosticsTests {
	@TempDir lateinit var directory: Path
	private val pipe = "\\\\.\\pipe\\SlimeVRDriver"
	private val session = TransportSessionHandle("test-accepted-transport")
	private fun rows(bytes: ByteArrayOutputStream): List<JsonNode> = bytes.toString(Charsets.UTF_8)
		.lineSequence().filter(String::isNotBlank).map { ObjectMapper().readTree(it) }.toList()
	private fun output(sink: SameFrameDiagnostics, message: ProtobufMessage) = sink.recordCompletedPipeWrite(
		message, "steamvr", pipe, session, message.serializedSize + 4,
	)
	private fun position(source: Position.DataSource? = null): Position.Builder = Position.newBuilder()
		.setTrackerId(71).setQx(0f).setQy(0.5f).setQz(0f).setQw(0.8660254f)
		.also { if (source != null) it.dataSource = source }

	@Test fun serializedFullImuNoneAndLegacyKeepExactValuesPresenceAndIdentity() {
		val bytes = ByteArrayOutputStream()
		val sink = SameFrameDiagnostics(bytes)
		val earliest = System.nanoTime()
		val registration = ProtobufMessage.newBuilder().setTrackerAdded(
			TrackerAdded.newBuilder().setTrackerId(71).setTrackerSerial("opaque-output-key").setTrackerName("opaque-display").setTrackerRole(1),
		).build()
		output(sink, ProtobufMessage.parseFrom(registration.toByteArray()))
		val builder = position(Position.DataSource.FULL).setX(1.25f).setY(-2.5f).setZ(3.75f)
		val full = ProtobufMessage.newBuilder().setPosition(builder).build()
		output(sink, ProtobufMessage.parseFrom(full.toByteArray()))
		builder.setX(999f) // The async record must not retain a mutable producer builder.
		output(sink, ProtobufMessage.newBuilder().setPosition(position(Position.DataSource.IMU)).build())
		output(sink, ProtobufMessage.newBuilder().setPosition(position(Position.DataSource.NONE)).build())
		output(sink, ProtobufMessage.newBuilder().setPosition(position().setX(4f).setY(5f).setZ(6f)).build())
		val latest = System.nanoTime()
		sink.close()
		assertTrue(sink.awaitTermination(2000))
		val records = rows(bytes)
		assertEquals(5, records.size)
		for (row in records) {
			assertEquals("COMPLETED_PIPE_WRITE", row["output_boundary"].asText())
			assertEquals("system_nanotime", row["clock_domain"].asText())
			assertTrue(row["system_ns"].asText().toLong() in earliest..latest)
			assertEquals(71, row["tracker_id"].asInt())
			assertEquals(pipe, row["pipe"].asText())
			assertEquals(session.epoch, row["transport_epoch"].asText())
			assertTrue(row["steamvr_consumed"].isNull)
		}
		assertEquals("opaque-output-key", records[0]["tracker_serial"].asText())
		assertEquals("opaque-display", records[0]["tracker_name"].asText())
		assertEquals(full.serializedSize + 4, records[1]["framed_bytes"].asInt())
		assertEquals(listOf(1.25, -2.5, 3.75), records[1]["position"].map(JsonNode::asDouble))
		assertEquals(listOf(true, true, true), records[1]["position_presence"].map(JsonNode::asBoolean))
		assertEquals("FULL", records[1]["data_source"].asText())
		assertEquals("IMU", records[2]["data_source"].asText())
		assertEquals("NONE", records[3]["data_source"].asText())
		assertTrue(records[1]["position_validity"].asBoolean())
		assertTrue(records[1]["orientation_validity"].asBoolean())
		assertFalse(records[2]["position_validity"].asBoolean())
		assertTrue(records[2]["orientation_validity"].asBoolean())
		assertFalse(records[3]["position_validity"].asBoolean())
		assertFalse(records[3]["orientation_validity"].asBoolean())
		assertEquals("DECLARED_DATA_SOURCE_NOT_PHYSICAL", records[1]["validity_basis"].asText())
		assertTrue(records[2]["position"].all { it.isNull })
		assertFalse(records[4]["data_source_present"].asBoolean())
		assertTrue(records[4]["data_source"].isNull)
		assertTrue(records[4]["position_validity"].isNull)
		assertTrue(records[4]["orientation_validity"].isNull)
		assertEquals("UNKNOWN_LEGACY", records[4]["semantic_validity"].asText())
		assertEquals("NOT_ENCODED_BY_SCHEMA", records[4]["rotation_presence"].asText())
		assertEquals(full.position.qw.toDouble(), records[1]["rotation_xyzw"][3].asDouble(), 0.000001)
	}

	@Test fun outputFactoryIsOptInRoleBoundCreateNewAndFailSoft() {
		val path = directory.resolve("output.jsonl")
		assertNull(SameFrameDiagnostics.outputForBridge("steamvr", pipe, null, null))
		assertNull(SameFrameDiagnostics.outputForBridge("steamvr_feeder", "\\\\.\\pipe\\SlimeVRInput", null, path.toString()))
		assertFalse(Files.exists(path))
		val sink = assertNotNull(SameFrameDiagnostics.outputForBridge("steamvr", pipe, null, path.toString()))
		sink.close()
		assertTrue(sink.awaitTermination(2000))
		Files.writeString(path, "existing evidence")
		assertNull(SameFrameDiagnostics.outputForBridge("steamvr", pipe, path.toString(), null))
		assertEquals("existing evidence", Files.readString(path))
		assertNull(SameFrameDiagnostics.outputForBridge("steamvr", pipe, "relative-output.jsonl", null))
		assertEquals("property", SameFrameDiagnostics.configuredPath("property", "environment"))
		assertEquals("environment", SameFrameDiagnostics.configuredPath(null, "environment"))
	}

	@Test fun closedCaptureAndNonOutputMessagesDoNotAcquireOutputEvidence() {
		val bytes = ByteArrayOutputStream()
		val sink = SameFrameDiagnostics(bytes)
		output(sink, ProtobufMessage.newBuilder().setUserAction(UserAction.newBuilder().setName("control")).build())
		sink.close()
		output(sink, ProtobufMessage.newBuilder().setPosition(position(Position.DataSource.FULL)).build())
		assertTrue(sink.awaitTermination(2000))
		assertTrue(rows(bytes).isEmpty())
	}

	@Test fun blockedOrFailedWriterCannotBlockTheOutputObservationCaller() {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val sink = SameFrameDiagnostics(object : OutputStream() {
			override fun write(value: Int) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
		}, capacity = 1)
		val message = ProtobufMessage.newBuilder().setPosition(position(Position.DataSource.IMU)).build()
		try {
			output(sink, message)
			assertTrue(entered.await(2, TimeUnit.SECONDS))
			output(sink, message)
			val start = System.nanoTime()
			output(sink, message)
			assertTrue(System.nanoTime() - start < 250_000_000)
			assertEquals(1L, sink.dropped.get())
		} finally {
			release.countDown()
			sink.close()
			assertTrue(sink.awaitTermination(2000))
		}
		val failed = SameFrameDiagnostics(object : OutputStream() {
			override fun write(value: Int): Unit = throw IOException("private diagnostic failure")
		})
		output(failed, message)
		assertTrue(failed.awaitTermination(2000))
		assertEquals("IOException", failed.failure)
		output(failed, message) // No exception escapes a failed logger.
	}

	@Test fun frameOutputRowsExposeWireTrackerIdsWithoutRewritingConsumerTime() {
		val bytes = ByteArrayOutputStream()
		val sink = SameFrameDiagnostics(bytes)
		val skeleton = HumanPoseManager(emptyList()).skeleton
		val direct = Tracker(null, 71, "opaque-direct", trackerPosition = TrackerPosition.HIP, hasPosition = true, hasRotation = true)
		sink.after(mapOf("now_ns" to "consumer-time", "system_ns" to "observation-time"), skeleton, listOf(direct), true)
		sink.close()
		assertTrue(sink.awaitTermination(2000))
		val row = rows(bytes).single()
		assertEquals("consumer-time", row["now_ns"].asText())
		assertEquals("observation-time", row["system_ns"].asText())
		assertEquals(71, row["direct_outputs"].single()["tracker_id"].asInt())
		assertEquals(skeleton.humanPoseManager.computedTrackers.map { it.id }, row["computed_trackers"].map { it["tracker_id"].asInt() })
		assertEquals("PRE_BRIDGE_DATA_WRITE", row["output_boundary"].asText())
	}
}
