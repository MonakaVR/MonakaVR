"""Record selected toolchains and verify the fixed vendored dependency bytes."""
import hashlib
import json
from pathlib import Path
R=Path(__file__).resolve().parents[1]
deps={
 'cpp/third_party/nlohmann/json.hpp':('nlohmann/json','3.11.3','9bea4c8066ef4a1c206b2be5a36302f8926f7fdc6087af5d20b417d0cf103ea6','https://raw.githubusercontent.com/nlohmann/json/v3.11.3/single_include/nlohmann/json.hpp'),
 'jvm/libs/gson-2.11.0.jar':('com.google.code.gson:gson','2.11.0','57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b','https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar'),
 'jvm/libs/kotlin-stdlib-2.3.10.jar':('org.jetbrains.kotlin:kotlin-stdlib','2.3.10','f61662c6d3a2f8ef5bd34362a02d877772c39f393cd394feb259dfaf7f4d8437','https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.3.10/kotlin-stdlib-2.3.10.jar'),
}
files={}
for path,(name,version,sha,url) in deps.items():
    assert hashlib.sha256((R/path).read_bytes()).hexdigest()==sha,path
    files[path]=dict(name=name,version=version,sha256=sha,upstream=url)
lock={
 'language_levels':{'cpp':'C++17','jvm':17},
 'selected_toolchains':{'kotlin':'2.3.10','gradle':'8.14.4','python':'3.13.15','cmake':'4.3.1-msvc1','windows_compiler':'MSVC 19.51.36257.0','java':'Microsoft OpenJDK 17.0.20.1+1-LTS'},
 'linux_compiler':{'selection':'GCC C++17 via ubuntu-latest CI runner','local_verification':'NOT RUN; no Linux/WSL installed'},
 'vendored_files':files,
 'test_dependencies':(R/'tools/test-requirements.txt').read_text().splitlines(),
}
(R/'dependencies.lock.json').write_text(json.dumps(lock,indent=2)+'\n')
runtime={'library':{'file':'libs/monaka-protocol-jvm-0.1.0.jar','version':'0.1.0'},
         'java_release':17,'runtime_dependencies':[{'file':n.removeprefix('jvm/'),**x} for n,x in files.items() if n.endswith('.jar')],
         'note':'JAR artifact hashes, including the protocol JAR, are recorded in protocol.lock.json. No Maven publication is required.'}
(R/'jvm/runtime-dependencies.json').write_text(json.dumps(runtime,indent=2)+'\n')
print('PASS pinned dependency SHA256 checks')
