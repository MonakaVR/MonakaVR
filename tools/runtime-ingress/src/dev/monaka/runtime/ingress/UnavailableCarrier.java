package dev.monaka.runtime.ingress;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
/** Existing accepted Common Pose unavailable representation. This is serialization
 * of the production backend's sample-removal decision, not a new validity rule.
 * It preserves the last admitted sample's provenance; metadata never refreshes it.
 * Provenance: 2C-3R capture_only_main.py pose_free_main_frame, SHA-256
 * 0121d6be8b5e8eefc564b76042a7790ec83c1437189c00cb4949be6518f18444.
 */
final class UnavailableCarrier {
 static ObjectNode project(ObjectNode template,JsonNode last){
  ObjectNode frame=template.deepCopy();frame.remove("mainMtp");frame.remove("mainReceivedAtNanos");
  ObjectNode main=last.required("sourceProvenance").deepCopy();
  main.put("sourceId",last.required("sourceId").asText());main.put("valid",false);
  main.putNull("positionMeters");main.putNull("quaternionXyzw");frame.set("main",main);return frame;
 }
}
