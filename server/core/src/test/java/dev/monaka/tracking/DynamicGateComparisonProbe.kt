package dev.monaka.tracking

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v2.*
import dev.monaka.tracking.mtp.MtpPoseAdapter
import dev.slimevr.tracking.trackers.TrackerPosition
import java.nio.file.*

/** Runs unchanged against old/new runtime JARs; never creates a receiver. */
object DynamicGateComparisonProbe {
 @JvmStatic fun main(args: Array<String>) {
  require(args.size == 2 && !Files.exists(Path.of(args[1])))
  val mapper = ObjectMapper(); val adapter = MtpPoseAdapter()
  val results = Files.readAllLines(Path.of(args[0])).filter { it.isNotBlank() }.map { raw ->
   when (val decoded = MonakaCodec.decodeEnvelope(raw.toByteArray())) {
    is DecodeResult.Failure -> mapOf("decode" to decoded.code.toString())
    is DecodeResult.Success -> {
     val p = decoded.value as? MtpPose
     if (p == null) mapOf("state" to true) else try {
      val o = adapter.adapt(p, TrackerPosition.HIP, 1_000_000_000)
      mapOf("modality" to o.modality.name, "position" to o.position?.let { listOf(it.x, it.y, it.z) },
       "rotation" to o.rotation?.let { listOf(it.w, it.x, it.y, it.z) },
       "position_quality" to o.positionQuality.name, "rotation_quality" to o.rotationQuality.name)
     } catch (e: Exception) { mapOf("adapter_error" to e.javaClass.name) }
    }
   }
  }
  Files.writeString(Path.of(args[1]), mapper.writeValueAsString(mapOf("artifact" to MtpPoseAdapter::class.java.protectionDomain.codeSource.location.toString(), "results" to results)), StandardOpenOption.CREATE_NEW)
 }
}
