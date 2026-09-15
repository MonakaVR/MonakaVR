import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
R=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--cpp',default='build/cpp/Release/monaka_codec_runner.exe' if os.name=='nt' else 'build/cpp/monaka_codec_runner');a=p.parse_args()
cp=os.pathsep.join(str(R/x) for x in ['jvm/build/classes/kotlin/test','jvm/build/libs/monaka-protocol-jvm-0.1.0.jar','jvm/libs/gson-2.11.0.jar','jvm/libs/kotlin-stdlib-2.3.10.jar'])
report={'platform':platform.platform(),'processor':platform.processor(),'iterations':1000,'cpp_warmup':100,'jvm_warmup':1000,'samples':[]}
for label,cmd in [('cpp',[str(R/a.cpp)]),('jvm',['java','-Dfile.encoding=UTF-8','-cp',cp,'dev.monaka.protocol.v1.Runner'])]:
    for frame in ['observation','derivatives','mtp-pose']:
        path=R/f'fixtures/valid/{frame}.json'
        result=subprocess.check_output(cmd+[str(path),'--bench'],text=True).strip()
        report['samples'].append(dict(language=label,frame=frame,input_bytes=path.stat().st_size,measurements=result))
report['limitations']=['Single local run, not a latency percentile or throughput guarantee.','C++ allocation counts cover ordinary global new/new[] only; not malloc or aligned allocations.','JVM allocated bytes use HotSpot ThreadMXBean; object allocation count not available.','JVM CPU measures calling thread only; JIT/GC worker CPU is excluded.','C++ CPU uses GetProcessTimes on Windows and std::clock elsewhere; timer resolution limits precision.','Encode includes validation and a decode verification pass. No transport or hardware work is measured.']
(R/'docs/performance.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
