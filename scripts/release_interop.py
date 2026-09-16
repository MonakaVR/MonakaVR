"""Test the supplied v2 C++ codec against its JVM codec (or the actual app JAR)."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
from release_v2 import require, save, sha

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--kit", type=Path, required=True)
    p.add_argument("--cpp", type=Path, required=True)
    p.add_argument("--java-home", type=Path, required=True)
    p.add_argument("--work", type=Path, required=True)
    p.add_argument("--jar", type=Path)
    p.add_argument("--dependencies", type=Path)
    a = p.parse_args()
    a.work.mkdir(parents=True, exist_ok=True)
    jars = [a.jar] if a.jar else sorted((a.kit / "jvm/libs").glob("*.jar"))
    if a.dependencies:
        jars.extend(sorted(a.dependencies.glob("*.jar")))
    require(jars, "No codec JAR")
    cp = os.pathsep.join(str(x.resolve()) for x in [a.work, *jars])
    java = a.java_home / "bin/java.exe"
    javac = a.java_home / "bin/javac.exe"
    source = a.work / "ReleaseCodecConsumer.java"
    source.write_text("""import java.nio.file.*;
import dev.monaka.protocol.v2.*;
public class ReleaseCodecConsumer {
 public static void main(String[] args) throws Exception {
  DecodeResult d = MonakaCodec.decodeEnvelope(Files.readAllBytes(Path.of(args[0])));
  if (d instanceof DecodeResult.Failure) {
   System.out.println("ERROR:" + ((DecodeResult.Failure)d).getCode()); return;
  }
  EncodeResult e = MonakaCodec.encodeEnvelope(((DecodeResult.Success)d).getValue());
  if (!(e instanceof EncodeResult.Success)) throw new IllegalStateException(e.toString());
  System.out.write(((EncodeResult.Success)e).getValue());
 }
}
""", encoding="utf-8")
    subprocess.run([str(javac), "--release", "17", "-cp", cp, str(source)], check=True)
    commands = [[str(a.cpp.resolve())], [str(java), "-Dfile.encoding=UTF-8", "-cp", cp, "ReleaseCodecConsumer"]]
    fixtures = a.kit / "fixtures/v2"
    index = json.loads((fixtures / "index.json").read_bytes())
    require(index and any(x["error"] for x in index) and any(not x["error"] for x in index), "Incomplete v2 fixture index")
    def invoke(command, path):
        return subprocess.check_output([*command, str(path.resolve())], timeout=20).decode("utf-8").strip()
    directions = 0
    for item in index:
        results = [invoke(cmd, fixtures / item["file"]) for cmd in commands]
        if item["error"]:
            require(results == ["ERROR:" + item["error"]] * 2, "Rejection mismatch: " + item["file"])
        else:
            for i, text in enumerate(results):
                require(json.loads(text) == item["decoded"], "Decode mismatch: " + item["file"])
                path = a.work / "cross.json"
                path.write_text(text, encoding="utf-8")
                require(json.loads(invoke(commands[1-i], path)) == item["decoded"], "Cross codec mismatch: " + item["file"])
                directions += 1
    save(a.work / "result.json", {"result": "PASS", "wire_version": {"major": 2, "minor": 0},
        "fixtures": len(index), "cross_language_directions": directions,
        "cpp_sha256": sha(a.cpp.read_bytes()), "jar_sha256": {str(j): sha(j.read_bytes()) for j in jars},
        "fixture_index_sha256": sha((fixtures / "index.json").read_bytes()), "hardware": "NOT RUN"})
    print(f"PASS {len(index)} v2 fixtures, {directions} cross-language directions")

if __name__ == "__main__":
    main()
