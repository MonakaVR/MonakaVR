"""Check all hashes and build/run consumers using only the extracted kit."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
R=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--cmake',default='cmake');a=p.parse_args()
def sha(b):return hashlib.sha256(b).hexdigest()
manifest=json.loads((R/'dist/handoff-manifest.json').read_text())
archive=R/'dist'/manifest['artifact']
assert sha(archive.read_bytes())==manifest['sha256']
with tempfile.TemporaryDirectory(prefix='monaka-kit-') as directory:
    w=Path(directory); kit=w/'third_party/monaka-protocol'
    assert w.resolve().parent==Path(tempfile.gettempdir()).resolve()
    with zipfile.ZipFile(archive) as z:
        assert all(not Path(n).is_absolute() and '..' not in Path(n).parts for n in z.namelist())
        z.extractall(kit)
    lock=json.loads((kit/'protocol.lock.json').read_text())
    assert sha((kit/'protocol.lock.json').read_bytes())==manifest['protocol_lock_sha256']
    assert lock['source_commit']==manifest['source_commit'] and lock['schema_commit']==manifest['schema_commit']
    for line in (kit/'SHA256SUMS').read_text().splitlines():
        h,n=line.split('  ',1);assert sha((kit/n).read_bytes())==h,n
    for n,h in lock['files_sha256'].items():assert sha((kit/n).read_bytes())==h,n
    for n,info in lock['toolchain_and_dependencies']['vendored_files'].items():assert sha((kit/n).read_bytes())==info['sha256'],n
    (w/'CMakeLists.txt').write_text('''cmake_minimum_required(VERSION 3.20)
project(KitConsumer LANGUAGES CXX)
add_subdirectory(third_party/monaka-protocol/cpp)
add_executable(consumer main.cpp)
target_link_libraries(consumer PRIVATE MonakaProtocol::Codec)
''')
    (w/'main.cpp').write_text('''#include <monaka/protocol/v1/codec.hpp>
#include <fstream>
#include <iterator>
#include <string>
int main(int argc,char** argv) {
  if(argc!=2) return 1;
  std::ifstream f(argv[1],std::ios::binary);
  std::string b((std::istreambuf_iterator<char>(f)),{}), encoded;
  monaka::protocol::v1::Envelope out; monaka::protocol::v1::Error error;
  if(!monaka::protocol::v1::DecodeEnvelope(reinterpret_cast<const uint8_t*>(b.data()),b.size(),out,error)) return 2;
  return monaka::protocol::v1::EncodeEnvelope(out,encoded,error)?0:3;
}
''')
    def run(cmd):subprocess.run([str(x) for x in cmd],cwd=w,check=True)
    run([a.cmake,'-S','.', '-B','build','-DCMAKE_BUILD_TYPE=Release'])
    run([a.cmake,'--build','build','--config','Release'])
    binary=w/('build/Release/consumer.exe' if os.name=='nt' else 'build/consumer')
    for name in ['observation','device-state','mtp-pose','tracker-state']:run([binary,kit/f'fixtures/valid/{name}.json'])
    (w/'Consumer.java').write_text('''import dev.monaka.protocol.v1.*;
import java.nio.file.*;
public class Consumer {
 public static void main(String[] args) throws Exception {
  DecodeResult r = MonakaCodec.decodeEnvelope(Files.readAllBytes(Path.of(args[0])));
  if (!(r instanceof DecodeResult.Success)) throw new AssertionError(r);
  if (!(MonakaCodec.encodeEnvelope(((DecodeResult.Success)r).getValue()) instanceof EncodeResult.Success)) throw new AssertionError();
 }
}
''')
    cp=str(kit/'jvm/libs/*')
    run(['javac','--release','17','-cp',cp,'Consumer.java'])
    for name in ['observation','device-state','mtp-pose','tracker-state']:run(['java','-cp','.'+os.pathsep+cp,'Consumer',kit/f'fixtures/valid/{name}.json'])
print('PASS external ZIP hash, internal file/lock/dependency hashes and isolated C++/JVM consumers (4 messages each)')
(R/'build/kit-verification.json').write_text(json.dumps({'status':'PASS','sha256':manifest['sha256'],'source_commit':manifest['source_commit'],'cpp_messages':4,'jvm_messages':4},indent=2)+'\n')
