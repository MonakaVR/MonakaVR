package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import java.nio.file.Files

/** Software-only stdio fixture. Owns a fresh temporary configuration, no sockets or device APIs. */
object ModeConfigurationProcessFixture {
 @JvmStatic fun main(args: Array<String>) {
  val dir = Files.createTempDirectory("monaka-mode-process-")
  try {
   val path = dir.resolve("configuration.json")
   val config = MonakaConfiguration(CoordinateSpace("mode-process-world", "rh_y_up_neg_z_forward", 1), port = 0)
   config.assignments.configureSources(TrackerPosition.HIP, TrackerReference.slime("fixture-q"),
    TrackerReference.mtp(LogicalTracker("fixture-source", "fixture-main", "fixture-publisher")), true)
   config.save(path)
   MonakaRuntime({ emptyList() }, config.space, config.assignments, clock = { 1L }).use { runtime ->
    val api = MonakaInputConfigurationApi(config, runtime.mtp, { emptyList() }, path)
    System.`in`.bufferedReader().useLines { lines -> lines.forEach { line ->
     println(api.handleJson(line, 1)); System.out.flush()
    } }
   }
  } finally {
   Files.list(dir).use { paths -> paths.forEach { Files.deleteIfExists(it) } }; Files.deleteIfExists(dir)
  }
 }
}
