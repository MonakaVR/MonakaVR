"""Normal fixed Build/runtime entries; never build, install, register or fall back."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from build_provenance import ContractError


def java_executable():
    explicit = os.environ.get('MONAKA_JAVA')
    if explicit:
        path = Path(explicit)
        return path.resolve() if path.is_file() else None
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        path = Path(java_home) / 'bin/java.exe'
        if path.is_file():
            return path.resolve()
    found = shutil.which('java.exe')
    return Path(found).resolve() if found else None


def launch_spec(workspace, product, runtime, manifest=None, profile=None, mtp=False, port=21110):
    workspace, runtime = Path(workspace).resolve(), Path(runtime).resolve()
    repo = workspace / product
    result = {'status': 'READY', 'runtime': str(runtime), 'cwd': str(repo), 'process_started': False}
    if product == 'MonakaBridge':
        config = repo / 'config/bridge.json'
        value = json.loads(config.read_text(encoding='utf-8-sig'))
        if value.get('namespace') != 'monaka.bridge' or value.get('version') not in (2, 3):
            raise ContractError('Bridge requires a monaka.bridge v2 or v3 configuration')
        native = runtime / 'Release'
        result['argv'] = [str(native / 'monaka_bridge_service.exe'), '--native-owner', str(config),
                          str(runtime / 'control/Release/monaka_bridge_control.exe'),
                          str(native / 'monaka_bridge_config.exe'), '29810', '29811', '29812', '29813', '--open']
        required = [result['argv'][i] for i in (0, 3, 4)]
    elif product == 'PicoMotionTrackerBridge':
        gui = runtime / 'control/Release/pico_ot_steamvr_control.exe'
        service = runtime / 'Release/pico_ot_bridge_service.exe'
        result['argv'] = [str(gui), str(repo), '--service-exe', str(service)]
        required = [str(gui), str(service)]
    elif product == 'MonakaVR':
        distribution = runtime / 'win-unpacked'
        java = java_executable()
        if java is None:
            raise ContractError('Java 17+ is required via MONAKA_JAVA, JAVA_HOME or PATH')
        if profile is not None:
            profile = Path(profile)
            if not profile.is_absolute() or (profile.exists() and not profile.is_dir()):
                raise ContractError('--profile must be an absolute directory')
        if not 1 <= port <= 65535:
            raise ContractError('--server-port must be 1..65535')
        result['java'] = str(java)
        result['cwd'] = str(distribution)
        result['argv'] = [str(distribution / 'SlimeVR.exe'), '--path', str(distribution), '--server-port', str(port)]
        if profile is not None:
            result['argv'] += ['--profile', str(Path(profile).resolve())]
        if mtp:
            result['argv'].append('--monaka-mtp')
        required = [str(distribution / 'SlimeVR.exe'), str(distribution / 'slimevr.jar')]
    else:
        raise ContractError('Unknown product')
    for name in required:
        path = Path(name)
        if not path.is_relative_to(runtime) or not path.is_file():
            raise ContractError('Missing fixed Build/runtime launch payload: ' + name)
    return result


def main(product, workspace, argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    if product == 'MonakaVR':
        parser.add_argument('--profile', type=Path, help='Absolute application profile directory')
        parser.add_argument('--monaka-mtp', action='store_true')
        parser.add_argument('--server-port', type=int, default=21110)
    options = parser.parse_args(argv)
    try:
        runtime = Path(workspace).resolve() / product / 'Build/runtime'
        result = launch_spec(workspace, product, runtime, None,
                             getattr(options, 'profile', None), getattr(options, 'monaka_mtp', False),
                             getattr(options, 'server_port', 21110))
        if options.check:
            print(json.dumps(result, ensure_ascii=False))
            return 0
        env = os.environ.copy()
        if product == 'MonakaVR':
            env['JAVA_HOME'] = str(Path(result['java']).parent.parent)
        child = subprocess.Popen(result['argv'], cwd=result['cwd'], env=env,
                                 creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        if product == 'MonakaVR':
            return child.wait()
        try:
            return child.wait(timeout=1)
        except subprocess.TimeoutExpired:
            return 0
    except (ContractError, OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 2
