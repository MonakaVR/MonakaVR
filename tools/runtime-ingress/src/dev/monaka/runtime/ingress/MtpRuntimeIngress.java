package dev.monaka.runtime.ingress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.monaka.protocol.v2.*;
import dev.monaka.tracking.*;
import dev.monaka.tracking.hil.*;
import dev.monaka.tracking.mtp.*;
import dev.slimevr.tracking.trackers.TrackerPosition;
import java.util.*;

/** Production ingress boundary shared by file and loopback frontends.
 * No codec, normalization, pose math or validity policy is implemented here:
 * canonical decode and all sample decisions belong to the fixed Core backend.
 * P1 normalization occurs in the production Bridge before MTP publication.
 */
public final class MtpRuntimeIngress implements AutoCloseable {
 public record Delivery(boolean processed, boolean admitted, String kind, ObjectNode frame, Map<String,Long> diagnostics) {}
 private final ObjectMapper json=CorePocHilJson.INSTANCE.getMapper();
 private final MtpInbox inbox=new MtpInbox(1024);
 private final MtpObservationBackend backend;
 private final TrackerBodyAssignments assignments=new TrackerBodyAssignments();
 private LogicalTracker configuredKey;
 private final CorePocHilConfig config;
 private JsonNode lastCoreFrame;
 private long processed=0;
 public MtpRuntimeIngress(JsonNode configJson) {
  config=CorePocHilJson.INSTANCE.config(configJson);
  // The already explicit HIL Main reference is checked against each decoded key.
  backend=new MtpObservationBackend(inbox,assignments,config.getSpace(),"mtp","mtp");
 }
 public void acceptedCoreFrame(JsonNode value){lastCoreFrame=value.deepCopy();}
 public long processed(){return processed;}
 public Delivery receive(JsonNode envelope,long receipt,String peer,ObjectNode template) throws Exception {
  byte[] bytes=json.writeValueAsBytes(envelope);
  var decoded=MonakaCodec.decodeEnvelope(bytes);
  if(!(decoded instanceof DecodeResult.Success success))throw new IllegalArgumentException("Canonical MVIP decode rejected input");
  var message=success.getValue();LogicalTracker key;String kind;
  if(message instanceof MtpPose p){key=new LogicalTracker(p.getSource_id(),p.getTracker_id(),p.getPublisher_id());kind="pose";}
  else if(message instanceof MtpTrackerState s){key=new LogicalTracker(s.getSource_id(),s.getTracker_id(),s.getPublisher_id());kind="tracker_state";}
  else throw new IllegalArgumentException("MTP production ingress requires pose or tracker_state");
  if(!key.getObservationId().equals(config.getMainSourceId()))throw new IllegalArgumentException("SourceIdentityMismatch");
  if(configuredKey==null){assignments.assign(key,TrackerPosition.HIP);configuredKey=key;}
  // Existing backend performs sequence/session/peer/space/mapping and component conversion.
  // Explicit assignment is internal to this opt-in HIL ingress, never inferred from support.
  var previous=backend.samples().get(key);
  var counters=inbox.diagnostics();
  if(!inbox.receive(bytes,receipt,peer))throw new IllegalArgumentException("Production MtpInbox rejected input");
  var observations=backend.poll(receipt);
  var current=backend.samples().get(key);
  var diagnostics=inbox.diagnostics();boolean admitted=true;
  for(var e:diagnostics.entrySet())if(e.getValue()>counters.getOrDefault(e.getKey(),0L)&&
      !Set.of("Validated","PoseAccepted","UnassignedPose","SessionChanged").contains(e.getKey()))admitted=false;
  ObjectNode frame=null;
  if(message instanceof MtpPose&&current!=null&&current!=previous&&admitted&&!observations.isEmpty()){
   frame=template.deepCopy();frame.remove("main");frame.set("mainMtp",envelope.deepCopy());frame.put("mainReceivedAtNanos",receipt);
  }else if(previous!=null&&current==null&&lastCoreFrame!=null){
   frame=UnavailableCarrier.project(template,lastCoreFrame);
  }
  ++processed;
  return new Delivery(true,admitted,kind,frame,diagnostics);
 }
 @Override public void close(){backend.close();}
}
