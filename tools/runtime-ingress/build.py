"""Build ingress against exact legacy Core or an explicitly reviewed candidate."""
from pathlib import Path
import argparse,hashlib,json,subprocess,os,zipfile
from artifact_identity import select,java_source
p=argparse.ArgumentParser();p.add_argument('--core-package',type=Path,required=True);p.add_argument('--jdk',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--candidate-manifest',type=Path);a=p.parse_args()
R=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
jar=a.core_package/'lib/core.jar';root=R.parents[1]
head=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()
clean=not subprocess.check_output(['git','-C',str(root),'status','--porcelain','--untracked-files=all'],text=True).strip()
identity=select(a.core_package,a.candidate_manifest,head,clean)
if identity['mode']=='reviewed-candidate':
 with zipfile.ZipFile(jar) as z:
  if 'dev/monaka/tracking/hil/CorePocHilStdioMain.class' not in z.namelist():raise RuntimeError('Candidate Core lacks opt-in stdio control entrypoint')
a.output.mkdir(parents=True,exist_ok=True);classes=a.output/'classes';classes.mkdir(exist_ok=True)
sources=sorted((R/'src').rglob('*.java'))
generated=a.output/'CoreArtifactPin.java';generated.write_text(java_source(identity),encoding='utf-8')
subprocess.run([str(a.jdk/'bin/javac.exe'),'-encoding','UTF-8','--release','17','-cp',str(a.core_package/'lib/*'),'-d',str(classes),*map(str,sources),str(generated)],check=True)
out=a.output/'monaka-runtime-ingress.jar'
temp=a.output/'tmp';temp.mkdir(exist_ok=True)
subprocess.run([str(a.jdk/'bin/jar.exe'),'-J-Djava.io.tmpdir='+str(temp),'--create','--file',str(out),'-C',str(classes),'.'],check=True)
receipt=dict(artifact=out.name,sha256=sha(out),core_jar_sha256=sha(jar),core_source=identity['source_commit'],identity=identity,generated_pin_sha256=sha(generated),candidate_manifest_sha256=sha(a.candidate_manifest) if a.candidate_manifest else None,source_sha256={p.relative_to(R).as_posix():sha(p) for p in sources},implementation='production MtpRuntimeIngress -> pinned MtpInbox/MtpObservationBackend/MtpPoseAdapter -> existing Core process')
(a.output/'runtime-ingress-provenance.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8');print(json.dumps(receipt))
