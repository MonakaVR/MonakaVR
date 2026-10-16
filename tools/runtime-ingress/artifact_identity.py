"""Build-time identity; generated Java pins have no runtime override."""
from pathlib import Path
import hashlib,json,re

LEGACY_SOURCE='c73805da1a64d0b161775decd60e5632ea295a0f'
LEGACY_CORE='ef37474a804ea0c3705fe556d21c97861fc4c1fd8feb1b622c42130c8f061863'

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def select(package,manifest,source_commit,source_clean):
 package=Path(package).resolve(strict=True);lib=package/'lib'
 entries=list(lib.iterdir())
 if any(not p.is_file() or p.is_symlink() or p.resolve().parent!=lib.resolve() for p in entries):
  raise ValueError('Core library aliases/directories are not supported')
 actual={p.name:sha(p) for p in entries}
 if 'core.jar' not in actual or any(not re.fullmatch(r'[A-Za-z0-9_.+\-]+\.jar',n) for n in actual):
  raise ValueError('Core package requires only named JAR files in lib')
 if manifest is None:
  if actual['core.jar']!=LEGACY_CORE:raise ValueError('Accepted legacy Core JAR mismatch; supply reviewed candidate manifest')
  return dict(mode='accepted-legacy',source_commit=LEGACY_SOURCE,libraries=actual)
 document=json.loads(Path(manifest).read_text(encoding='utf-8'))
 if document.get('schema')!='monaka.runtime-ingress.candidate.v1' or document.get('repository')!='MonakaVR/MonakaVR':
  raise ValueError('Candidate manifest schema/repository mismatch')
 if not source_clean or not re.fullmatch(r'[0-9a-f]{40}',source_commit) or document.get('source_commit')!=source_commit:
  raise ValueError('Candidate requires exact clean checkout HEAD')
 rows=document.get('libraries');expected={}
 if not isinstance(rows,list) or not rows:raise ValueError('Candidate library inventory missing')
 for row in rows:
  name=row['path'];digest=row['sha256']
  if not isinstance(name,str) or not re.fullmatch(r'lib/[A-Za-z0-9_.+\-]+\.jar',name) or name[4:] in expected:
   raise ValueError('Invalid/duplicate candidate library path')
  if not isinstance(digest,str) or not re.fullmatch(r'[0-9a-f]{64}',digest):raise ValueError('Invalid candidate digest')
  expected[name[4:]]=digest
 if expected!=actual:raise ValueError('Candidate library inventory/content mismatch')
 return dict(mode='reviewed-candidate',source_commit=source_commit,libraries=actual)

def java_source(identity):
 entries=',\n'.join('Map.entry('+json.dumps(n)+','+json.dumps(v)+')' for n,v in sorted(identity['libraries'].items()))
 return ('package dev.monaka.runtime.ingress;\nimport java.util.Map;\n'
  'final class CoreArtifactPin {\n static final String SOURCE='+json.dumps(identity['source_commit'])+';\n'
  ' static final boolean STDIO='+str(identity['mode']=='reviewed-candidate').lower()+';\n'
  ' static final Map<String,String> LIBRARIES=Map.ofEntries('+entries+');\n private CoreArtifactPin() {}\n}\n')
