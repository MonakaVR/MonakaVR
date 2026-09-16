"""Write the task's required final report using actual commits and verified artifacts."""
import hashlib
import json
from pathlib import Path
import subprocess
R=Path(__file__).resolve().parents[1]
def git(*args):return subprocess.check_output(['git',*args],cwd=R,text=True).strip()
def read(path):return json.loads((R/path).read_text(encoding='utf-8'))
m=read('dist/handoff-manifest.json'); lock=read('dist/protocol.lock.json')
t=read('build/test-results.json'); v=read('build/kit-verification.json')
assert v['sha256']==m['sha256'] and v['status']=='PASS'
assert t['fixtures_passed']==t['fixture_count'] and not t['errors']
assert git('rev-parse','HEAD')==m['source_commit'] and not git('status','--porcelain')
def reason(path):
    if path.startswith('fixtures/invalid/'):return 'Reject malformed/invalid wire input; expected error is in fixtures/index.json.'
    if path.startswith('fixtures/valid/'):return 'Shared valid wire sample and expected semantic model for both codecs.'
    if path.startswith('fixtures/'):return 'Index expected decoded models/errors and declarative consumer session/loss/replay scenarios.'
    if path.startswith('schema/'):return 'Draft 2020-12 contract: distinct message family, fixed dispatch, required fields and ranges.'
    if path.startswith('cpp/third_party/'):return 'Vendor hash-pinned nlohmann JSON parser and its MIT license.'
    if path.startswith('cpp/'):return 'C++17 public types/API, strict validation, deterministic codec and test/build support.'
    if path.startswith('jvm/libs/'):return 'Offline, exact-version JVM runtime dependency.'
    if path.startswith('jvm/'):return 'JVM17 Kotlin types/API, strict codec, reproducible JAR and build/test/runtime manifest.'
    if path.startswith('licenses/') or path=='NOTICE':return 'Preserve dependency license and notice handoff.'
    if path.startswith('docs/'):return 'Preserve C1, baseline decisions and measured validation/performance evidence.'
    if path.startswith('tools/'):return 'Reproducible generation, validation, testing, packaging or actual-result reporting.'
    if path.startswith('.github/'):return 'Windows/Linux contract and kit consumer CI checks.'
    return 'Repository build, dependency pinning, reproducibility and consumer usage documentation.'
changed=[{'file':p,'reason':reason(p)} for p in git('diff','--name-only','663aea9d2d7ad4116e6ce7c16a4f74bec5416f02','HEAD').splitlines()]
report={
 'repository':'MonakaVR/MonakaProtocol',
 'audited_base_branch':'main (unborn)','audited_base_sha':None,
 'actual_base_branch':'origin/main','actual_base_sha':'663aea9d2d7ad4116e6ce7c16a4f74bec5416f02',
 'base_change_reason':'Remote initialized after audit. Existing work branch 5ac2a963d9ebb4f897bfe32a7f2884ae1cede3b9 and main have identical trees, divergent documentation histories. Continued existing branch without rewrite; bootstrap unnecessary.',
 'work_branch':git('branch','--show-current'),'HEAD_SHA':git('rev-parse','HEAD'),
 'protocol_version':'C1 candidate wire 1.0; JVM artifact 0.1.0',
 'schema_commit':m['schema_commit'],'protocol_kit_sha256':m['sha256'],'contract_c1_sha256':m['contract_c1_sha256'],
 'changed_files':changed,
 'build_result':[
   {'command':'cmake -S cpp -B build/cpp -G "Visual Studio 18 2026" -A x64 -DMONAKA_BUILD_TESTS=ON; cmake --build build/cpp --config Release','environment':'Windows 11 x64, C++17, MSVC 19.51.36257.0, CMake 4.3.1-msvc1','status':'PASS'},
   {'command':'gradle -p jvm --offline --no-daemon build','environment':'Gradle 8.14.4, Kotlin 2.3.10, Microsoft OpenJDK 17.0.20.1','status':'PASS'},
   {'command':'Linux C++17 CI build','environment':'No Linux/WSL locally','status':'NOT RUN'},
   {'command':'python tools/verify_kit.py --cmake <installed cmake>','environment':'Separate temporary directory; only extracted kit; C++ and javac --release 17 consumers','status':'PASS'}],
 'unit_test_result':{'command':'ctest --test-dir build/cpp -C Release --output-on-failure; python tools/test.py; python tools/check_boundaries.py','status':'PASS','details':t},
 'mock_or_cross_language_result':{'status':'PASS','cross_language_directions':t['cross_language_directions_passed'],'consumer_scenarios':'Supplied as declarative fixtures; no runtime replay/freshness implementation or hardware validation claimed.'},
 'hardware_validation':'NOT RUN / not required; tracker connection and accuracy not verified.',
 'compatibility_status':'C1 is independent of POTB v1/PICO C ABI v1. No other repository or old consumer changed. Consumer migration requires the supplied kit; equal version numbers do not imply compatibility.',
 'phase_or_DoD_status':'candidate implementation complete / master reconciliation pending; Windows/JVM handoff DoD verified, Linux build validation pending',
 'unresolved_issues':['Reference master not supplied; reconciliation pending.','Linux C++17 CI configuration supplied but not executed in this environment.'],
 'followup_required_in_other_repos':'Tasks 2-5 must import this exact kit into third_party/monaka-protocol and preserve its provenance lock; no independently rewritten codec or contract changes.',
 'responsibility_summary':'Protocol owns schemas, models, codecs, validation and fixture/kit distribution. No device/runtime/socket/mapping/calibration/GUI/role/solver/driver responsibility was moved into this repository.',
 'handoff_artifacts':[
   {'file':'dist/'+m['artifact'],'sha256':m['sha256'],'source_commit':m['source_commit']},
   {'file':'dist/protocol.lock.json','sha256':m['protocol_lock_sha256'],'source_commit':m['source_commit']},
   {'file':'dist/handoff-manifest.json','sha256':hashlib.sha256((R/'dist/handoff-manifest.json').read_bytes()).hexdigest(),'source_commit':m['source_commit']},
   *[{'file':'kit/'+n,'sha256':h,'source_commit':m['source_commit']} for n,h in lock['artifact_sha256'].items()]],
 'working_tree_clean':True,'push_performed':False,'history_rewrite_performed':False,
}
(R/'dist/final-report.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
print('Wrote dist/final-report.json with every required field and per-file change reasons')
