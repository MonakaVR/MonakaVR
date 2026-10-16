package dev.monaka.runtime.ingress;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import dev.monaka.tracking.hil.CorePocHilJson;
/** Owns only this invocation's immutable Core executable and file IPC session. */
final class CoreProcess implements AutoCloseable {
 private final ObjectMapper json=CorePocHilJson.INSTANCE.getMapper();
 private final Path session;private final Process process;private int sequence;
 CoreProcess(Path pkg,Path config,Path session)throws Exception{
  this.session=session.toAbsolutePath();
  var lib=pkg.resolve("lib").toRealPath();var actual=new TreeMap<String,String>();
  try(var entries=Files.list(lib)){
   for(var file:entries.toList()){
    if(!Files.isRegularFile(file)||Files.isSymbolicLink(file)||!file.toRealPath().getParent().equals(lib))throw new IllegalArgumentException("Core library alias/directory rejected");
    actual.put(file.getFileName().toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
   }
  }
  if(!actual.equals(CoreArtifactPin.LIBRARIES))throw new IllegalArgumentException("Pinned Core library inventory/content mismatch");
  if(Files.exists(session))throw new IllegalArgumentException("Refuse reused IPC session");Files.createDirectories(session);
  var java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
  var cmd=List.of(java,"-cp",pkg.toAbsolutePath().resolve("lib").toString()+File.separator+"*","dev.monaka.tracking.hil.CorePocHilMain","--session",this.session.toString(),"--config",config.toAbsolutePath().toString());
  process=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(session.resolve("core-process.log").toFile()).start();
  try{await(session.resolve("ready.json"));}catch(Exception e){process.destroy();throw e;}
 }
 private JsonNode await(Path file)throws Exception{
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
  while(System.nanoTime()<deadline){
   if(Files.exists(file))try{return json.readTree(Files.readAllBytes(file));}catch(java.nio.file.AccessDeniedException e){/* retry read only, never resend */}
   if(!process.isAlive())throw new IllegalStateException("Core exited "+process.exitValue()+"; inspect core-process.log");
   Thread.sleep(10);
  }throw new IllegalStateException("Core IPC timeout");
 }
 JsonNode input(ObjectNode frame)throws Exception{return send("input",frame);}
 private JsonNode send(String op,ObjectNode frame)throws Exception{
  String id="runtime-"+(sequence++);ObjectNode request=json.createObjectNode().put("id",id).put("op",op);if(frame!=null)request.set("frame",frame);
  Files.writeString(session.resolve("requests.jsonl"),json.writeValueAsString(request)+"\n",StandardOpenOption.APPEND);
  return await(session.resolve("response-"+id+".json"));
 }
 @Override public void close()throws Exception{
  if(process.isAlive()){
   try{send("shutdown",null);if(!process.waitFor(10,TimeUnit.SECONDS))throw new IllegalStateException("Core shutdown timeout");}
   finally{if(process.isAlive())process.destroy();}
  }
  if(process.exitValue()!=0)throw new IllegalStateException("Core nonzero exit "+process.exitValue());
 }
}
