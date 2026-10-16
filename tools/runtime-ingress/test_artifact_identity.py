"""Independent negative controls for candidate/source/library substitution."""
import copy,json,tempfile,unittest
from pathlib import Path
import artifact_identity as identity

class IdentityTests(unittest.TestCase):
 def setUp(self):
  self.base=Path(__file__).resolve().parents[2]/'build/runtime-ingress-identity-tests';self.base.mkdir(parents=True,exist_ok=True)
  self.temp=tempfile.TemporaryDirectory(dir=self.base);self.addCleanup(self.cleanup)
  self.root=Path(self.temp.name);self.pkg=self.root/'package';(self.pkg/'lib').mkdir(parents=True)
  (self.pkg/'lib/core.jar').write_bytes(b'current-core');(self.pkg/'lib/dependency.jar').write_bytes(b'dependency')
  self.head='a'*40
  self.document=dict(schema='monaka.runtime-ingress.candidate.v1',repository='MonakaVR/MonakaVR',source_commit=self.head,
   libraries=[dict(path='lib/'+p.name,sha256=identity.sha(p)) for p in sorted((self.pkg/'lib').iterdir())])
 def cleanup(self):
  self.assertEqual(Path(self.temp.name).resolve().parent,self.base.resolve())
  self.temp.cleanup()
 def select(self,document=None,head=None,clean=True):
  path=self.root/'candidate.json';path.write_text(json.dumps(self.document if document is None else document))
  return identity.select(self.pkg,path,self.head if head is None else head,clean)
 def reject(self,document):
  with self.assertRaises(ValueError):self.select(document)
 def test_exact_candidate_and_generated_pin(self):
  result=self.select();self.assertEqual(result['source_commit'],self.head)
  self.assertEqual(set(result['libraries']),{'core.jar','dependency.jar'})
  self.assertIn(identity.sha(self.pkg/'lib/core.jar'),identity.java_source(result))
 def test_legacy_does_not_accept_new_core(self):
  with self.assertRaises(ValueError):identity.select(self.pkg,None,self.head,True)
 def test_foreign_source(self):
  with self.assertRaises(ValueError):self.select(head='b'*40)
 def test_dirty_source(self):
  with self.assertRaises(ValueError):self.select(clean=False)
 def test_wrong_schema(self):
  doc=copy.deepcopy(self.document);doc['schema']='other';self.reject(doc)
 def test_wrong_repository(self):
  doc=copy.deepcopy(self.document);doc['repository']='other/other';self.reject(doc)
 def test_core_content_drift(self):
  (self.pkg/'lib/core.jar').write_bytes(b'different-core');self.reject(self.document)
 def test_dependency_content_drift(self):
  (self.pkg/'lib/dependency.jar').write_bytes(b'different-dependency');self.reject(self.document)
 def test_extra_library(self):
  (self.pkg/'lib/extra.jar').write_bytes(b'extra');self.reject(self.document)
 def test_missing_library(self):
  (self.pkg/'lib/dependency.jar').unlink();self.reject(self.document)
 def test_duplicate_and_traversal_inventory(self):
  doc=copy.deepcopy(self.document);doc['libraries'].append(doc['libraries'][0]);self.reject(doc)
  doc=copy.deepcopy(self.document);doc['libraries'][0]['path']='lib/../outside.jar';self.reject(doc)
 def test_digest_and_empty_inventory(self):
  doc=copy.deepcopy(self.document);doc['libraries'][0]['sha256']='invalid';self.reject(doc)
  doc=copy.deepcopy(self.document);doc['libraries']=[];self.reject(doc)

if __name__=='__main__':unittest.main()
