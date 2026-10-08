"""Pinned schema preservation, reproducibility, golden bytes and fresh old-parser compatibility."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
from monaka_bridge_protocol_overlay import patched_schema, EVIDENCE_MESSAGE
from generate_monaka_protobuf import generate

NEW_HARNESS = r'''
import dev.slimevr.desktop.platform.ProtobufMessages.*;
import java.nio.file.*;
import java.util.*;
public class ProtocolCheck {
  static void check(boolean ok) { if(!ok) throw new AssertionError(); }
  static String hex(byte[] b) { return HexFormat.of().formatHex(b); }
  static Position p(int id, boolean xyz) {
    var b = Position.newBuilder().setTrackerId(id).setQw(1).setDataSource(xyz ? Position.DataSource.FULL : Position.DataSource.IMU);
    if(xyz) b.setX(1).setY(2).setZ(3); return b.build();
  }
  static byte[] msg(Position p) { return ProtobufMessage.newBuilder().setPosition(p).build().toByteArray(); }
  public static void main(String[] args) throws Exception {
    var out=Path.of(args[0]);
    Position[] ps={p(0,true),p(0,false),p(7,true)};
    String[] gold={"0a16150000803f1d000000402500004040450000803f4803","0a07450000803f4801","0a180807150000803f1d000000402500004040450000803f4803"};
    for(int i=0;i<3;i++){check(hex(msg(ps[i])).equals(gold[i]));Files.write(out.resolve("pose"+i+".bin"),msg(ps[i]));}
    var e=HmdProviderSampleEvidenceV1.newBuilder().setProviderSessionEpoch("session").setObservationId(Long.MAX_VALUE)
      .setRawX(-0f).setRawY(Float.intBitsToFloat(0x7fc12345)).setRawZ(3)
      .setRawQx(0).setRawQy(0).setRawQz(0).setRawQw(1)
      .setWireX(1).setWireY(2).setWireZ(3).setWireQx(0).setWireQy(0).setWireQz(0).setWireQw(1)
      .setDataSource(3).setPoseValid(false).setDeviceConnected(true).setTrackingResult(300).build();
    var bytes=msg(ps[0].toBuilder().setHmdProviderEvidenceV1(e).build());
    var decoded=ProtobufMessage.parseFrom(bytes).getPosition().getHmdProviderEvidenceV1();
    check(Float.floatToRawIntBits(decoded.getRawY())==0x7fc12345);
    check(Float.floatToRawIntBits(decoded.getRawX())==0x80000000);
    check(decoded.getObservationId()==Long.MAX_VALUE);
    Files.write(out.resolve("evidence.bin"),bytes);
    System.out.println(hex(bytes));
  }
}
'''
OLD_HARNESS = r'''
import dev.slimevr.desktop.platform.ProtobufMessages.*;
import java.nio.file.*;
import java.util.*;
public class ProtocolCheck {
  static void check(boolean ok) { if(!ok) throw new AssertionError(); }
  public static void main(String[] args) throws Exception {
    var out=Path.of(args[0]);
    var bytes=Files.readAllBytes(out.resolve("evidence.bin"));
    var m=ProtobufMessage.parseFrom(bytes); var p=m.getPosition();
    check(p.getTrackerId()==0 && p.hasX() && p.hasY() && p.hasZ() && p.hasDataSource());
    check(p.getX()==1 && p.getY()==2 && p.getZ()==3 && p.getQx()==0 && p.getQy()==0 && p.getQz()==0 && p.getQw()==1 && p.getDataSourceValue()==3);
    check(p.getUnknownFields().hasField(13)); check(Arrays.equals(bytes,m.toByteArray()));
    for(int i=0;i<3;i++) {
      var b=Files.readAllBytes(out.resolve("pose"+i+".bin"));
      check(Arrays.equals(b,ProtobufMessage.parseFrom(b).toByteArray()));
    }
    System.out.println("PASS fresh pinned old parser: known outer fields/unknown evidence/golden bytes");
  }
}
'''


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--source", type=Path, required=True)
    p.add_argument("--protoc", type=Path, required=True)
    p.add_argument("--protobuf-jar", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--overlay", type=Path)
    a = p.parse_args()
    original = (a.source / "src/bridge/ProtobufMessages.proto").read_text(encoding="utf-8")
    patched = patched_schema(original)
    assert patched == patched_schema(original)
    added = "    optional HmdProviderSampleEvidenceV1 hmd_provider_evidence_v1 = 13;\n"
    assert patched.replace(EVIDENCE_MESSAGE, "").replace(added, "") == original
    for incorrect in (original + "\n", original.replace("vz = 12", "vz = 14"), patched):
        try: patched_schema(incorrect)
        except ValueError: pass
        else: raise AssertionError("Unpinned source accepted")
    out = a.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    java = generate(a.source.resolve(), a.protoc.resolve(), out / "new")
    root = Path(__file__).resolve().parents[1]
    assert java.read_bytes() == (root / "server/desktop/src/main/java/dev/slimevr/desktop/platform/ProtobufMessages.java").read_bytes()
    assert (out / "new/schema/ProtobufMessages.proto").read_bytes() == ((a.overlay or root / "build/provider-transport-overlay") / "src/bridge/ProtobufMessages.proto").read_bytes()
    old = out / "old"; old.mkdir(exist_ok=True)
    schema = old / "ProtobufMessages.proto"; schema.write_bytes(original.encode())
    subprocess.run([str(a.protoc), "--proto_path=" + str(old), "--java_out=" + str(old), str(schema)], check=True)
    jar = str(a.protobuf_jar.resolve())
    for mode, harness in (("new", NEW_HARNESS), ("old", OLD_HARNESS)):
        d = out / mode; h = d / "ProtocolCheck.java"; h.write_text(harness)
        generated = java if mode == "new" else old / "dev/slimevr/desktop/platform/ProtobufMessages.java"
        subprocess.run([shutil.which("javac"), "-cp", jar, "-d", str(d / "classes"), str(generated), str(h)], check=True)
        r = subprocess.check_output([shutil.which("java"), "-cp", str(d / "classes") + os.pathsep + jar, "ProtocolCheck", str(out)], text=True).strip()
        if mode == "new":
            # Golden is checked below against a tracked fixture, never auto-updated.
            golden = root / "native/direct-driver/hmd-provider-evidence-v1.hex"
            assert r == golden.read_text().strip(), "Evidence-present golden mismatch: " + r
        else: print(r)
    print("PASS exact pinned input/deterministic patch/fields 1-12/oneof 1-6/field 13/v1 presence/Java byte equality")


if __name__ == "__main__":
    main()
