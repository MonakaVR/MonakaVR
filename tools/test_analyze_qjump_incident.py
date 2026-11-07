import copy
import importlib.util
import json
import math
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('analyzer', Path(__file__).with_name('analyze_qjump_incident.py'))
analyzer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analyzer)


class AnalyzerTests(unittest.TestCase):
    def setUp(self):
        base = Path(__file__).resolve().parents[1] / 'Build/diagnostics/qjump/analyzer-temp'
        base.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=base)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def fixture(self, case='A', sign=False):
        rows = {name: [] for name in ('pico', 'bridge', 'monaka')}
        count = 44 if case == 'D' else 5
        def q(angle):
            return [0., math.sin(math.radians(angle)/2), 0., math.cos(math.radians(angle)/2)]
        def sample(component, stage, index, identity, rotation, links, context=None):
            context=copy.deepcopy(context or {})
            if component=='monaka':
                context['tick']=str(index)
                if case=='E': context.update(reset_generation='0' if index==0 else '1', lifecycle={'reset_epoch':'0' if index==0 else '1'})
            rows[component].append(dict(schema='qjump.capture.v1', kind='sample', component=component, process_instance=component+'-run', wall_clock_utc_ns=str(1_800_000_000_000_000_000 + index*50_000_000), process_monotonic_ns=str({'pico': 1000, 'bridge': 4_000_000_000, 'monaka': 9000}[component] + index*50_000_000), clock_domain=component, stream_id=stage+':one', stage=stage, identity=copy.deepcopy(identity), q=rotation, timestamps={'source_timestamp_ns':'9007199254740993','source_sent_at_ns':'9007199254740994','sent_at_ns':'9007199254740994'}, links=copy.deepcopy(links), context=copy.deepcopy(context or {}), validity={'fresh':True}))
        for i in range(count):
            observation = dict(source_id='opaque-source', device_id='opaque-device', session_id='obs-session', sequence=str(9007199254740993+i))
            mvip = dict(publisher_id='opaque-publisher', source_id='opaque-source', tracker_id='opaque-tracker', session_id='mvip-session', clock_id='mvip-clock', sequence=str(9007199254740993+i))
            potb = dict(serial='opaque-native', sender_session_id='41', wire_sequence=str(9007199254740993+i))
            native_angle = 120 if i > 0 and case == 'A' else 0
            bridge_angle = 120 if i > 0 and case in ('A','B') else 0
            consumed_angle = bridge_angle if case in ('A','B') else (120 if i > 0 and case in ('C','E') or i == 1 and case == 'D' else 0)
            bone_angle = 120 if i > 0 and case == 'D' else consumed_angle
            rotations = [q(native_angle), q(bridge_angle), q(consumed_angle), q(bone_angle)]
            if sign and i == 1:
                rotations = [[-v for v in value] for value in rotations]
            sample('pico','native',i,potb,rotations[0],{'potb':potb})
            sample('pico','producer_published',i,observation,rotations[0],{'potb':potb,'observation':observation})
            rows['pico'][-1].update(publication_phase='actual_wire',wire_send_success=True)
            sample('bridge','bridge_input',i,observation,rotations[0],{'input':observation})
            sample('bridge','bridge_calibrated',i,observation,rotations[1],{'input':observation},{'transform':{'world_rotation':q(120 if case=='B' and i else 0), 'mount_rotation':q(0)}})
            sample('bridge','bridge_published',i,mvip,rotations[1],{'input':observation},{'transform':{'world_rotation':q(120 if case=='B' and i else 0), 'mount_rotation':q(0)}})
            rows['bridge'][-1].update(publication_phase='actual_wire',wire_send_success=True)
            for stage, rotation in [('admitted',rotations[1]),('consumed',rotations[2]),('bone',rotations[3]),('computed',rotations[3])]:
                sample('monaka',stage,i,mvip,rotation,{'mvip':mvip,'input':observation},{'target':'CHEST','read_site':'updateSpineTransforms' if stage=='consumed' else None})
        if case == 'E':
            rows['monaka'].append(dict(schema='qjump.capture.v1',kind='trigger',component='monaka',process_instance='monaka-run',wall_clock_utc_ns='1800000000050000000',trigger_type='T3',event_id='monaka-event'))
        for component in rows:
            samples = rows[component]
            header = dict(schema='qjump.capture.v1',kind='header',component=component,process_instance=component+'-run',event_id=component+'-event',incident_ids=['shared'],wall_clock_utc_ns='1800000000050000000',source_identity={'fixture':True})
            footer = dict(header,kind='footer',complete=True,post_data_present=True,no_new_post_samples=False,prewindow_complete=True,dropped='0',writer_errors='0',coverage_start_wall_clock_utc_ns='1800000000000000000',coverage_end_wall_clock_utc_ns=str(1_800_000_000_000_000_000+(count-1)*50_000_000))
            rows[component] = [header]+samples+[footer]
        return rows

    def write(self, rows):
        paths=[]
        for component, records in rows.items():
            path=self.root/(component+'.jsonl')
            path.write_text(''.join(json.dumps(r)+'\n' for r in records),encoding='utf-8')
            paths.append(path)
        return paths

    def test_A_to_E_candidates_with_exact_connected_cohorts(self):
        for case in ('A','B','C','D','E'):
            with self.subTest(case=case):
                result=analyzer.analyze(self.write(self.fixture(case)))
                self.assertEqual('CORRELATED_NOT_PROVEN',result['status'],result)
                self.assertIn(case,[c['case'] for c in result['candidates']],result)
                self.assertGreater(result['joins']['complete_chain_observations'],1)

    def test_sign_flip_does_not_trigger_physical_step(self):
        result=analyzer.analyze(self.write(self.fixture('none',sign=True)))
        self.assertEqual('NO_DIVERGENCE_OBSERVED',result['status'],result)
        self.assertEqual([],result['stage_steps'])

    def test_A_propagation_to_bone_allows_unrelated_stable_computed_output(self):
        rows=self.fixture('A')
        for row in rows['monaka']:
            if row.get('stage')=='computed':
                row['q']=[0.,0.,0.,1.]
                row['context']['target']='HEAD'
        result=analyzer.analyze(self.write(rows))
        self.assertEqual('CORRELATED_NOT_PROVEN',result['status'],result)
        self.assertIn('A',[c['case'] for c in result['candidates']],result)
        self.assertFalse(any(step['stage']=='computed' for step in result['stage_steps']))

    def test_identity_session_sequence_window_missing_layer_and_drop_reject(self):
        for damage in ('identity','session','sequence','window','missing','drop','post','phase'):
            with self.subTest(damage=damage):
                rows=self.fixture()
                if damage in ('identity','session','sequence'):
                    field={'identity':'publisher_id','session':'session_id','sequence':'sequence'}[damage]
                    for r in rows['bridge']:
                        if r.get('stage')=='bridge_published': r['identity'][field]='999' if field=='sequence' else 'mismatch'
                elif damage=='window':
                    for r in rows['bridge']: r['wall_clock_utc_ns']=str(int(r['wall_clock_utc_ns'])+30_000_000_000)
                    rows['bridge'][-1]['coverage_start_wall_clock_utc_ns']='1800000030000000000'
                    rows['bridge'][-1]['coverage_end_wall_clock_utc_ns']='1800000030200000000'
                elif damage=='missing': del rows['pico']
                elif damage=='drop': rows['monaka'][-1]['dropped']='1'
                elif damage=='post': rows['pico'][-1]['post_data_present']=False
                elif damage=='phase':
                    for r in rows['bridge']:
                        if r.get('stage')=='bridge_published': r['publication_phase']='queue'
                result=analyzer.analyze(self.write(rows))
                self.assertEqual('INSUFFICIENT_EVIDENCE',result['status'],result)

    def test_independent_thinning_unmatched_other_body_does_not_veto_complete_candidate(self):
        rows=self.fixture('A')
        stray=copy.deepcopy(next(r for r in rows['monaka'] if r.get('stage')=='consumed'))
        stray['identity']['tracker_id']='unmatched-other-body'
        stray['links']['mvip']=copy.deepcopy(stray['identity'])
        stray['context']['target']='LEFT_UPPER_LEG'
        rows['monaka'].insert(-1,stray)
        result=analyzer.analyze(self.write(rows))
        self.assertEqual('CORRELATED_NOT_PROVEN',result['status'],result)
        self.assertIn('A',[c['case'] for c in result['candidates']])
        self.assertGreater(result['joins']['unmatched_consumed_chain'],0)
        # Also preserve a separately complete but one-tick body cohort without vetoing A.
        extra=self.fixture('none')
        extra=json.loads(json.dumps(extra).replace('opaque-source','other-source').replace('opaque-device','other-device').replace('opaque-tracker','other-tracker').replace('opaque-native','other-native').replace('CHEST','LEFT_UPPER_LEG'))
        for component in rows:
            rows[component][-1:-1]=[r for r in extra[component] if r.get('kind')=='sample' and r['wall_clock_utc_ns']=='1800000000000000000']
        result=analyzer.analyze(self.write(rows))
        self.assertEqual('CORRELATED_NOT_PROVEN',result['status'],result)
        self.assertTrue(result.get('cohort_coverage'))

    def test_candidate_onset_missing_and_wrong_fk_tick_are_insufficient(self):
        for mode in ('onset','tick'):
            rows=self.fixture('A')
            for r in rows['monaka']:
                if r.get('stage')=='consumed' and r['context']['tick']=='1':
                    if mode=='onset': r['links']['mvip']['sequence']='99999999999999'
                    else: r['context']['tick']='999'
            result=analyzer.analyze(self.write(rows))
            self.assertEqual('INSUFFICIENT_EVIDENCE',result['status'],result)

    def test_read_site_and_body_are_not_mixed_into_a_step(self):
        rows=self.fixture('none')
        extra=[]
        for r in rows['monaka']:
            if r.get('stage')=='consumed':
                other=copy.deepcopy(r); other['stream_id']='consumed:other'; other['context']['read_site']='another_site'; other['q']=[0.,1.,0.,0.]; extra.append(other)
        rows['monaka'][-1:-1]=extra
        result=analyzer.analyze(self.write(rows))
        self.assertEqual('NO_DIVERGENCE_OBSERVED',result['status'],result)

    def test_precision_and_no_phase_clock_equivalence(self):
        rows=self.fixture('C')
        result=analyzer.analyze(self.write(rows))
        self.assertEqual('9007199254740994',result['stage_steps'][0]['identity']['sequence'])
        for r in rows['bridge']:
            if r.get('kind')=='sample': r['identity']['sequence']=9007199254740993
        self.assertEqual('INSUFFICIENT_EVIDENCE',analyzer.analyze(self.write(rows))['status'])

    def test_null_and_invalid_semantic_orientation_never_becomes_stable_return(self):
        for mode in ('null','zero','invalid_flag'):
            with self.subTest(mode=mode):
                rows=self.fixture('D')
                for r in rows['monaka']:
                    if r.get('stage')=='consumed':
                        if mode=='null': r['q']=None
                        elif mode=='zero': r['q']=[0,0,0,0]
                        else: r['validity']['rotation']=False
                result=analyzer.analyze(self.write(rows))
                self.assertEqual('INSUFFICIENT_EVIDENCE',result['status'],result)
                self.assertNotIn('D',[c['case'] for c in result['candidates']])


if __name__=='__main__': unittest.main()
