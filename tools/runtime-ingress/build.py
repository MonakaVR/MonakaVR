"""Build separate production ingress tool against the immutable accepted Core package."""
from pathlib import Path
import argparse,hashlib,json,subprocess,os
p=argparse.ArgumentParser();p.add_argument('--core-package',type=Path,required=True);p.add_argument('--jdk',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
R=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
jar=a.core_package/'lib/core.jar'
if sha(jar)!='ef37474a804ea0c3705fe556d21c97861fc4c1fd8feb1b622c42130c8f061863':raise RuntimeError('Accepted Core JAR mismatch')
a.output.mkdir(parents=True,exist_ok=True);classes=a.output/'classes';classes.mkdir(exist_ok=True)
sources=sorted((R/'src').rglob('*.java'))
subprocess.run([str(a.jdk/'bin/javac.exe'),'-encoding','UTF-8','--release','17','-cp',str(a.core_package/'lib/*'),'-d',str(classes),*map(str,sources)],check=True)
out=a.output/'monaka-runtime-ingress.jar'
temp=a.output/'tmp';temp.mkdir(exist_ok=True)
subprocess.run([str(a.jdk/'bin/jar.exe'),'-J-Djava.io.tmpdir='+str(temp),'--create','--file',str(out),'-C',str(classes),'.'],check=True)
receipt=dict(artifact=out.name,sha256=sha(out),core_jar_sha256=sha(jar),core_source='c73805da1a64d0b161775decd60e5632ea295a0f',source_sha256={p.relative_to(R).as_posix():sha(p) for p in sources},implementation='production MtpRuntimeIngress -> accepted MtpInbox/MtpObservationBackend/MtpPoseAdapter -> existing Core process')
(a.output/'runtime-ingress-provenance.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8');print(json.dumps(receipt))
