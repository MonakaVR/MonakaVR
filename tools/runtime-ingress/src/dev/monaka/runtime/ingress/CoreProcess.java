package dev.monaka.runtime.ingress;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import dev.monaka.tracking.hil.CorePocHilJson;
/** Owns only this invocation's immutable Core executable and file IPC session. */
final class CoreProcess implements AutoCloseable {
 private final ObjectMapper json=CorePocHilJson.INSTANCE.getMapper();
 private final Path session;private final Process process;private int sequence;
 private final BufferedReader responses;private final BufferedWriter requests;private final ExecutorService reads;
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
  var cmd=List.of(java,"-cp",pkg.toAbsolutePath().resolve("lib").toString()+File.separator+"*",CoreArtifactPin.STDIO?"dev.monaka.tracking.hil.CorePocHilStdioMain":"dev.monaka.tracking.hil.CorePocHilMain","--session",this.session.toString(),"--config",config.toAbsolutePath().toString());
  var builder=new ProcessBuilder(cmd);
  if(CoreArtifactPin.STDIO)builder.redirectError(session.resolve("core-process.log").toFile());
  else builder.redirectErrorStream(true).redirectOutput(session.resolve("core-process.log").toFile());
  process=builder.start();
  responses=CoreArtifactPin.STDIO?new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)):null;
  requests=CoreArtifactPin.STDIO?new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8)):null;
  reads=CoreArtifactPin.STDIO?Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"core-stdio-response");t.setDaemon(true);return t;}):null;
  try{await(session.resolve("ready.json"));}catch(Exception e){process.destroy();if(reads!=null)reads.shutdownNow();throw e;}
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
  if(CoreArtifactPin.STDIO){
   requests.write(json.writeValueAsString(request));requests.newLine();requests.flush();
   var future=reads.submit(()->responses.readLine());String line;
   try{line=future.get(30,TimeUnit.SECONDS);}catch(Exception error){future.cancel(true);throw error;}
   if(line==null)throw new IllegalStateException("Core stdio ended before response; inspect core-process.log");
   if(line.length()>2097152)throw new IllegalStateException("Core stdio response too large");
   var response=json.readTree(line);if(!id.equals(response.path("id").asText()))throw new IllegalStateException("Core stdio response identity mismatch");
   return response.required("response");
  }
  Files.writeString(session.resolve("requests.jsonl"),json.writeValueAsString(request)+"\n",StandardOpenOption.APPEND);
  return await(session.resolve("response-"+id+".json"));
 }
 @Override public void close()throws Exception{
  try{
   if(process.isAlive()){
    try{send("shutdown",null);if(!process.waitFor(10,TimeUnit.SECONDS))throw new IllegalStateException("Core shutdown timeout");}
    finally{if(process.isAlive()){process.destroy();process.waitFor(10,TimeUnit.SECONDS);}}
   }
   if(process.isAlive()||process.exitValue()!=0)throw new IllegalStateException("Core did not exit successfully");
  }finally{if(reads!=null)reads.shutdownNow();if(requests!=null)requests.close();if(responses!=null)responses.close();}
 }
}
