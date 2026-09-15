"""Enforce contract hash/generated files and runtime dependency boundaries."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
R=Path(__file__).resolve().parents[1]
subprocess.run([sys.executable,str(R/'tools/generate.py'),'--check'],check=True)
lock=json.loads((R/'dependencies.lock.json').read_text())
for name,item in lock['vendored_files'].items():
    assert hashlib.sha256((R/name).read_bytes()).hexdigest()==item['sha256'],f'dependency hash drift: {name}'
for folder in ['cpp/include','cpp/src','jvm/src/main']:
    for p in (R/folder).rglob('*'):
        if not p.is_file(): continue
        text=p.read_text(encoding='utf-8').lower()
        for token in ['winsock','sys/socket','java.net','openvr.h','hidapi','pico_runtime','steamvr_driver','qtwidgets','android.']:
            assert token not in text,f'forbidden dependency {token}: {p}'
print('PASS contract hash, generated files, pinned dependencies and forbidden dependency imports')
