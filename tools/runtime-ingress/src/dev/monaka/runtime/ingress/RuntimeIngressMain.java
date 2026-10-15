package dev.monaka.runtime.ingress;
import java.nio.file.*;
import java.io.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.monaka.tracking.hil.CorePocHilJson;
/** Thin file entrypoint: production MtpRuntimeIngress owns all input processing. */
public final class RuntimeIngressMain {
 public static void main(String[] args)throws Exception{
  if(args.length==1&&args[0].equals("--help")){System.out.println("--package CORE_PACKAGE --config CONFIG --session NEW_DIR --input JSONL --output JSONL");return;}
  if(args.length!=10)throw new IllegalArgumentException("Use --help");
  java.util.Map<String,String> options=new java.util.HashMap<>();for(int i=0;i<args.length;i+=2)options.put(args[i],args[i+1]);
  Path pkg=Path.of(options.get("--package")),configPath=Path.of(options.get("--config")),session=Path.of(options.get("--session"));
  ObjectMapper json=CorePocHilJson.INSTANCE.getMapper();JsonNode config=json.readTree(Files.readAllBytes(configPath));
  try(var ingress=new MtpRuntimeIngress(config);var core=new CoreProcess(pkg,configPath,session);
      var reader=options.get("--input").equals("-")?new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8)):Files.newBufferedReader(Path.of(options.get("--input")));var writer=Files.newBufferedWriter(Path.of(options.get("--output")),StandardOpenOption.CREATE_NEW)){
   String line;while((line=reader.readLine())!=null){if(line.isBlank())continue;if(line.length()>1048576)throw new IllegalArgumentException("Carrier too large");
    JsonNode row=json.readTree(line);var delivery=ingress.receive(row.required("envelope"),row.required("received_at_ns").longValue(),row.path("peer").asText("runtime-ingress"),(ObjectNode)row.required("frame"));
    ObjectNode result=json.createObjectNode();result.put("processed",delivery.processed());result.put("admitted",delivery.admitted());result.put("kind",delivery.kind());result.set("backendDiagnostics",json.valueToTree(delivery.diagnostics()));
    if(delivery.frame()!=null){JsonNode response=core.input(delivery.frame());ingress.acceptedCoreFrame(response);result.set("core",response);result.set("submittedFrame",delivery.frame());}
    writer.write(json.writeValueAsString(result));writer.newLine();writer.flush();
   }System.out.println("Processed "+ingress.processed()+" MTP messages through fixed production backend");
  }
 }
}
