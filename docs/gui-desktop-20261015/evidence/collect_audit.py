"""Read-only source/boundary evidence. Does not fetch, switch, build or write repos."""
from pathlib import Path
import datetime
import hashlib
import json
import subprocess

root = Path(r'C:/Users/nynyp/Documents/ChatGPT/MonakaVR_UI')
desktop = root / '.gui-work/MonakaVR-Desktop'
audit_bridge = root / '.gui-work/Bridge-Contract-Audit'
frozen = root / 'MonakaBridge'
validated = Path(r'C:/Users/nynyp/AppData/Local/Temp/mbg-desktop-final-1015-a/inputs/candidate/MonakaBridge')
base = '17c4c9bb712b425bd72911837c7df010dee5bd62'
core = 'ebdde9188b6d1a91befa22d499f3e4228d25a47b'
p2r = '42ec6c809c085a44cdb0d331f2bab452c79701fd'

def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], text=True, encoding='utf-8').strip()

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

originals = {}
for name in ['MonakaBridge', 'MonakaProtocol', 'PicoMotionTrackerBridge', 'ViveUltimateTrackerBridge']:
    repo = root / name
    originals[name] = dict(head=git(repo, 'rev-parse', 'HEAD'), branch=git(repo, 'branch', '--show-current'), status=git(repo, 'status', '--porcelain=v1'))
paths = git(frozen, 'ls-files').splitlines()
hashes = {name: sha(frozen/name) for name in paths if (frozen/name).is_file()}
different = [name for name, expected in hashes.items() if not (validated/name).is_file() or sha(validated/name) != expected]
product = git(desktop, 'diff', '--name-only', base, 'HEAD').splitlines()
outside = [name for name in product if not name.startswith('gui/') and name != 'pnpm-lock.yaml']
core_frontend = git(desktop, 'diff', '--name-only', base, core, '--', 'gui', 'package.json', 'pnpm-lock.yaml', 'solarxr-protocol').splitlines()
runtime_delta = git(desktop, 'diff', '--name-only', base, 'HEAD', '--', 'server', 'src', 'third_party', 'dependencies', 'solarxr-protocol', 'build.gradle.kts', 'settings.gradle.kts').splitlines()
ancestry = subprocess.run(['git', '-C', str(desktop), 'merge-base', '--is-ancestor', base, core]).returncode == 0
public = ['tools/bridge_config.cpp', 'config/bridge.example.json']
out = dict(
    collected_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
    implementation_commit=git(desktop, 'rev-parse', 'HEAD'),
    branch=git(desktop, 'branch', '--show-current'), base=base, core_ingress=core,
    p2r_bridge=p2r, original_checkouts=originals,
    frozen_bridge_full_tracked_file_count=len(hashes),
    frozen_bridge_byte_differences_vs_final_validated_clone=different,
    frozen_bridge_sha256=hashes,
    gui_product_paths=product, gui_product_paths_outside_authorized_surface=outside,
    desktop_runtime_protected_diff=runtime_delta,
    core_derived_from_prepared_base=ancestry,
    core_vs_base_frontend_dependency_diff=core_frontend,
    solarxr_submodule=git(desktop/'solarxr-protocol', 'rev-parse', 'HEAD'),
    bridge_audit_source_status=git(audit_bridge, 'status', '--porcelain=v1'),
    p2r_public_cli_and_example_diff=git(audit_bridge, 'diff', '--name-only', originals['MonakaBridge']['head'], p2r, '--', *public).splitlines(),
    p2r_contract_diff=git(audit_bridge, 'diff', originals['MonakaBridge']['head'], p2r, '--', 'src/mapping/config.cpp', 'src/mapping/config.hpp', 'src/routing/health.cpp'),
    native_validators={
        'baseline_5fbf2a9': sha(Path(r'C:/Users/nynyp/AppData/Local/Temp/mbg-withheld-1015-a/inputs/candidate/MonakaBridge/build/gui-validation/Release/monaka_bridge_config.exe')),
        'p2r_42ec6c8': sha(audit_bridge/'build/gui-contract-validator/Release/monaka_bridge_config.exe')},
    legacy_4pf_receipts_integrity='FAIL: two original receipt files missing; this audit does not reconstruct or supersede them',
    limits='No runtime merge, Core execution, hardware, live Bridge, delivery validation or legacy 4PF repair. Commit comparison and software validator/GUI tests only.'
)
assert not different and not outside and not runtime_delta and not core_frontend and ancestry
assert all(not value['status'] for value in originals.values())
assert not out['bridge_audit_source_status'] and not out['p2r_public_cli_and_example_diff']
(desktop/'build/gui-validation/compatibility-audit.json').write_text(json.dumps(out, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({k: v for k, v in out.items() if k not in ('frozen_bridge_sha256', 'p2r_contract_diff', 'gui_product_paths')}, ensure_ascii=False, indent=2))
