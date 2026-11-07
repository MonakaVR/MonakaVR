"""Offline Q-jump evidence analysis. Explicit inputs only; never controls a product.

Exact wire links establish correspondence. UTC coverage establishes temporal relevance.
Stage-local quaternion changes establish candidates, never physical fault proof.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
import re

SCHEMA = 'qjump.capture.v1'
COMPONENTS = {'pico', 'bridge', 'monaka'}
OBS = ('source_id', 'device_id', 'session_id', 'sequence')
MVIP = ('publisher_id', 'source_id', 'tracker_id', 'session_id', 'clock_id', 'sequence')
POTB = ('serial', 'sender_session_id', 'wire_sequence')


class EvidenceError(ValueError):
    pass


def integer(value, field):
    if not isinstance(value, str) or not re.fullmatch(r'-?[0-9]+', value):
        raise EvidenceError(f'{field}: decimal string required')
    return int(value)


def key(value, fields):
    if not isinstance(value, dict) or any(not isinstance(value.get(f), str) or not value[f] for f in fields):
        return None
    for f in fields:
        if f in ('sequence', 'wire_sequence'):
            integer(value[f], f)
    return tuple(value[f] for f in fields)


def quaternion(row):
    q = row.get('q')
    if not isinstance(q, list) or len(q) != 4 or any(isinstance(x, bool) or not isinstance(x, (float, int)) or not math.isfinite(x) for x in q):
        return None
    norm = math.hypot(*q)
    return tuple(x / norm for x in q) if norm > 1e-15 else None


def distance(a, b):
    if a is None or b is None:
        return None
    return math.degrees(2 * math.acos(min(1., max(0., abs(sum(x*y for x, y in zip(a, b)))))))


def link(row, names, fields):
    links = row.get('links') or {}
    for name in names:
        result = key(links.get(name), fields)
        if result:
            return result
    return None


def wire_sent(row):
    context = row.get('context') or {}
    phase = row.get('publication_phase', context.get('publication_phase'))
    success = row.get('wire_send_success', context.get('wire_send_success'))
    return phase == 'actual_wire' and (success is True or context.get('send_result') == 'sent')


def load_bundle(path):
    path = Path(path)
    if not path.is_file() or path.stat().st_size > 32 * 1024 * 1024:
        raise EvidenceError(f'{path.name}: missing/oversize bundle')
    rows = []
    with path.open(encoding='utf-8') as stream:
        for line in stream:
            if len(line) > 256 * 1024:
                raise EvidenceError('oversize record')
            try:
                row = json.loads(line, parse_constant=lambda _: (_ for _ in ()).throw(EvidenceError('nonfinite JSON')))
            except (ValueError, TypeError) as e:
                raise EvidenceError(f'invalid JSON: {e}') from e
            if not isinstance(row, dict):
                raise EvidenceError('record must be object')
            rows.append(row)
            if len(rows) > 200000:
                raise EvidenceError('record-count cap')
    if len(rows) < 2 or rows[0].get('kind') != 'header' or rows[-1].get('kind') != 'footer':
        raise EvidenceError('header/footer missing or not closed')
    header, footer = rows[0], rows[-1]
    component, process = header.get('component'), header.get('process_instance')
    if component not in COMPONENTS or not isinstance(process, str) or not process:
        raise EvidenceError('component/process identity missing')
    for row in rows:
        if row.get('schema') != SCHEMA or row.get('component') != component or row.get('process_instance') != process:
            raise EvidenceError('mixed schema/component/process')
        integer(row.get('wall_clock_utc_ns'), 'wall_clock_utc_ns')
        if row.get('kind') not in ('header', 'sample', 'trigger', 'footer'):
            raise EvidenceError('unknown record kind')
        if row.get('kind') == 'sample':
            integer(row.get('process_monotonic_ns'), 'process_monotonic_ns')
            if not isinstance(row.get('stage'), str) or not isinstance(row.get('stream_id'), str):
                raise EvidenceError('sample boundary/lane missing')
            for field in ('identity', 'timestamps', 'links', 'validity', 'context'):
                if field in row and not isinstance(row[field], dict):
                    raise EvidenceError(f'{field} must be object')
            for k, v in (row.get('identity') or {}).items():
                if k.endswith(('sequence', 'revision')) and v is not None:
                    integer(v, f'identity.{k}')
    if not header.get('event_id') or header.get('event_id') != footer.get('event_id'):
        raise EvidenceError('event identity mismatch')
    ids = set(header.get('incident_ids') or [])
    issues = []
    if not ids or not ids.intersection(footer.get('incident_ids') or []):
        issues.append('incident aliases missing in header/footer')
    if footer.get('complete') is not True:
        issues.append('incomplete bundle')
    for field in ('dropped', 'writer_errors', 'missed_events'):
        if field in footer and integer(footer[field], field) != 0:
            issues.append(field)
    for field in ('truncated', 'shutdown'):
        if footer.get(field) is True:
            issues.append(field)
    if footer.get('prewindow_complete') is False:
        issues.append('short prewindow')
    if footer.get('post_data_present') is False or footer.get('no_new_post_samples') is True:
        issues.append('no observed post data')
    start = integer(footer.get('coverage_start_wall_clock_utc_ns'), 'coverage_start_wall_clock_utc_ns')
    end = integer(footer.get('coverage_end_wall_clock_utc_ns'), 'coverage_end_wall_clock_utc_ns')
    if start > end:
        issues.append('reversed coverage')
    return dict(path=str(path), component=component, process=process, header=header, footer=footer,
                samples=[r for r in rows if r['kind'] == 'sample'], triggers=[r for r in rows if r['kind'] == 'trigger'],
                incidents=ids, start=start, end=end, issues=issues)


def interpretation(row):
    context = row.get('context') or {}
    life = context.get('lifecycle') or {}
    return (context.get('reset_generation'), tuple((k, str(life.get(k))) for k in ('assignment_generation', 'q_source', 'six_dof_source', 'proxy_instance', 'proxy_name', 'reset_epoch')))


def physical(row):
    return tuple((k, json.dumps(v, sort_keys=True)) for k, v in sorted((row.get('identity') or {}).items()) if k not in ('sequence', 'wire_sequence'))


def lane(row):
    identity = row.get('identity') or {}
    context = row.get('context') or {}
    # Never compare bodies/read-sites or sessions/coordinate frames as one stream.
    return row.get('stage'), row.get('stream_id'), context.get('target'), context.get('read_site'), physical(row), interpretation(row)


def step_records(samples, threshold, interpretation_scoped=True):
    grouped = defaultdict(list)
    for row in samples:
        validity = row.get('validity') or {}
        if quaternion(row) is not None and validity.get('fresh') is not False and all(validity.get(k) is not False for k in ('rotation', 'rotation_valid', 'orientation_valid')):
            grouped[lane(row) if interpretation_scoped else lane(row)[:-1]].append(row)
    steps = []
    for group in grouped.values():
        group.sort(key=lambda r: integer(r['process_monotonic_ns'], 'local monotonic'))
        for previous, current in zip(group, group[1:]):
            angle = distance(quaternion(previous), quaternion(current))
            if angle >= threshold:
                steps.append(dict(stage=current['stage'], angle_degrees=angle, before=previous, after=current))
    return steps, grouped


def chain_joins(bundles):
    stages = defaultdict(list)
    for bundle in bundles:
        for row in bundle['samples']:
            stages[row['stage']].append(row)
    pp = {key(r.get('identity'), OBS): r for r in stages['producer_published'] if wire_sent(r)}
    bi = {key(r.get('identity'), OBS): r for r in stages['bridge_input']}
    bc = {(link(r, ('input', 'observation'), OBS) or key(r.get('identity'), OBS)): r for r in stages['bridge_calibrated']}
    bp = {key(r.get('identity'), MVIP): r for r in stages['bridge_published'] if wire_sent(r)}
    admitted = {key(r.get('identity'), MVIP): r for r in stages['admitted']}
    native = {link(r, ('potb',), POTB) or key(r.get('identity'), POTB): r for r in stages['native']}
    chains = []
    missing = defaultdict(int)
    bones_by_frame = defaultdict(list)
    computed_by_frame = defaultdict(list)
    solver_by_frame = defaultdict(list)
    for row in stages['bone']:
        context = row.get('context') or {}
        bones_by_frame[(row.get('process_instance'), context.get('tick'), context.get('target'), link(row, ('mvip',), MVIP) or key(row.get('identity'), MVIP))].append(row)
    for row in stages['computed']:
        context = row.get('context') or {}
        computed_by_frame[(row.get('process_instance'), context.get('tick'))].append(row)
    for row in stages['solver_constraint']:
        solver_by_frame[(row.get('process_instance'), (row.get('context') or {}).get('tick'))].append(row)
    for consumed in stages['consumed']:
        consumer_tick = (consumed.get('context') or {}).get('tick')
        if not isinstance(consumer_tick, str):
            missing['missing_actual_consumer_tick'] += 1
            continue
        integer(consumer_tick, 'consumer tick')
        mk = link(consumed, ('mvip',), MVIP) or key(consumed.get('identity'), MVIP)
        a, published = admitted.get(mk), bp.get(mk)
        ok = link(published or {}, ('input', 'observation'), OBS)
        producer, incoming = pp.get(ok), bi.get(ok)
        calibrated = bc.get(ok)
        nk = link(producer or {}, ('potb',), POTB)
        n = native.get(nk)
        if not all((mk, a, published, ok, producer, incoming, calibrated, nk, n)):
            missing['unmatched_consumed_chain'] += 1
            continue
        if any(quaternion(row) is None or any((row.get('validity') or {}).get(k) is False for k in ('fresh', 'rotation', 'rotation_valid', 'orientation_valid')) for row in (n, producer, incoming, calibrated, published, a, consumed)):
            missing['invalid_or_unavailable_chain_orientation'] += 1
            continue
        # Validate the actual same-wire measurements, not only coincident reused counters.
        pt, it = producer.get('timestamps') or {}, incoming.get('timestamps') or {}
        bt, at = published.get('timestamps') or {}, a.get('timestamps') or {}
        observation_ts = pt.get('observation_timestamp_ns', pt.get('source_timestamp_ns'))
        observation_sent = pt.get('observation_sent_at_ns', pt.get('sent_at_ns'))
        same_observation = (observation_ts is not None and observation_ts == it.get('source_timestamp_ns') and
                            (observation_sent is None or observation_sent == it.get('source_sent_at_ns', it.get('sent_at_ns'))))
        same_mvip = (bt.get('source_timestamp_ns') is not None and bt.get('source_timestamp_ns') == at.get('source_timestamp_ns') and
                     bt.get('source_sent_at_ns', bt.get('sent_at_ns')) == at.get('sent_at_ns'))
        if not same_observation or not same_mvip:
            missing['wire_timestamp_disagreement'] += 1
            continue
        if distance(quaternion(producer), quaternion(incoming)) is None or distance(quaternion(producer), quaternion(incoming)) > .001 or distance(quaternion(published), quaternion(a)) is None or distance(quaternion(published), quaternion(a)) > .001:
            missing['same_wire_quaternion_disagreement'] += 1
            continue
        target = (consumed.get('context') or {}).get('target')
        process = consumed.get('process_instance')
        bones = bones_by_frame[(process, consumer_tick, target, mk)]
        computed = []
        for r in computed_by_frame[(process, consumer_tick)]:
            links = r.get('links') or {}
            if link(r, ('mvip',), MVIP) == mk or mk in [key(v, MVIP) for v in (links.get('selected_mvip') or {}).values()]:
                computed.append(r)
                continue
            frame = links.get('consumer_frame') or {}
            if not isinstance(frame, dict):
                missing['invalid_computed_frame_reference'] += 1
                continue
            if consumer_tick is None or frame.get('tick') != consumer_tick or r.get('process_instance') != consumed.get('process_instance'):
                continue
            solver = solver_by_frame[(process, consumer_tick)]
            expected = integer(frame.get('selected_target_count'), 'selected_target_count')
            generation = ((consumed.get('context') or {}).get('lifecycle') or {}).get('assignment_generation')
            if len({(s.get('context') or {}).get('target') for s in solver}) != expected or frame.get('assignment_generation') != generation or not any(key(s.get('identity'), MVIP) == mk for s in solver):
                missing['unresolved_computed_frame_reference'] += 1
                continue
            computed.append(r)
        if not bones or not computed:
            missing['missing_downstream_stage'] += 1
            continue
        chains.append(dict(native=n, producer_published=producer, bridge_input=incoming, bridge_calibrated=calibrated, bridge_published=published, admitted=a, consumed=consumed, bone=bones[0], computed=computed[0]))
    return chains, dict(missing)


def analyze(paths, threshold=45., wall_window_ns=2_000_000_000):
    result = {'schema': 'qjump.analysis.v1', 'status': 'INSUFFICIENT_EVIDENCE', 'candidates': [], 'issues': [], 'joins': {}, 'causality': 'CORRELATED_NOT_PROVEN'}
    bundles = []
    for path in paths:
        try:
            bundles.append(load_bundle(path))
        except (EvidenceError, OSError) as e:
            result['issues'].append(f'{Path(path).name}: {e}')
    if {b['component'] for b in bundles} != COMPONENTS or len(bundles) != 3:
        result['issues'].append('exactly one valid bundle per component required')
    if bundles:
        shared = set.intersection(*(b['incidents'] for b in bundles))
        result['incident_ids'] = sorted(shared)
        if not shared:
            result['issues'].append('no common incident identity')
        if max(b['start'] for b in bundles) > min(b['end'] for b in bundles):
            result['issues'].append('UTC coverage windows do not overlap')
        events = [integer(b['header']['wall_clock_utc_ns'], 'trigger UTC') for b in bundles]
        if max(events) - min(events) > wall_window_ns:
            result['issues'].append('event UTC window mismatch')
        for b in bundles:
            result['issues'].extend(f"{b['component']}: {x}" for x in b['issues'])
    try:
        chains, missing = chain_joins(bundles)
    except (EvidenceError, TypeError, AttributeError) as e:
        chains, missing = [], {}
        result['issues'].append(f'invalid chain/frame reference: {e}')
    result['joins'] = dict(complete_chain_observations=len(chains), **missing)
    if not chains:
        result['issues'].append('no exact native -> wire -> admitted -> consumed -> bone/computed chain')
    all_samples = [s for b in bundles for s in b['samples']]
    steps, grouped = step_records(all_samples, threshold)
    adjacent_steps, _ = step_records(all_samples, threshold, interpretation_scoped=False)
    result['stage_steps'] = [dict(stage=s['stage'], stream_id=s['after']['stream_id'], angular_degrees=s['angle_degrees'], identity=s['after']['identity']) for s in steps]
    # A stage change is only used on exact connected wire cohorts, never cross-basis equality.
    if not result['issues']:
        cohorts = defaultdict(list)
        blocked = set()
        eligible_cohorts = 0
        for chain in chains:
            consumed = chain['consumed']
            identity = consumed.get('identity') or {}
            cohorts[(tuple((k, str(v)) for k, v in sorted(identity.items()) if k != 'sequence'), (consumed.get('context') or {}).get('target'))].append(chain)
        for cohort in cohorts.values():
            if len({(c['consumed'].get('process_instance'), (c['consumed'].get('context') or {}).get('tick')) for c in cohort}) < 2:
                result.setdefault('cohort_coverage', []).append({'target': (cohort[0]['consumed'].get('context') or {}).get('target'), 'reason': 'only one complete consumer tick; not used for change attribution'})
                continue
            eligible_cohorts += 1
            linked_ids = {id(row) for chain in cohort for row in chain.values()}
            cohort_read = cohort[0]['consumed']
            onset_missing = any(s['stage'] in ('consumed','bone') and physical(s['after']) == physical(cohort_read) and
                                (s['after'].get('context') or {}).get('target') == (cohort_read.get('context') or {}).get('target') and
                                (id(s['before']) not in linked_ids or id(s['after']) not in linked_ids) for s in steps)
            if onset_missing:
                result.setdefault('candidate_coverage_gaps', []).append({'target': (cohort_read.get('context') or {}).get('target'), 'reason': 'candidate onset/before is not an exact complete chain'})
                blocked.add((physical(cohort_read), (cohort_read.get('context') or {}).get('target')))
                continue
            by_site = defaultdict(list)
            for chain in cohort:
                by_site[(chain['consumed'].get('context') or {}).get('read_site')].append(chain)
            found = set()
            for site in by_site.values():
                site.sort(key=lambda c: integer(c['consumed']['process_monotonic_ns'], 'consumer local time'))
                for before, after in zip(site, site[1:]):
                    angles = {stage: distance(quaternion(before[stage]), quaternion(after[stage])) for stage in before}
                    stepped = {stage for stage, value in angles.items() if value is not None and value >= threshold}
                    detail = {'target': (after['consumed'].get('context') or {}).get('target'), 'before_tick': (before['consumed'].get('context') or {}).get('tick'), 'onset_tick': (after['consumed'].get('context') or {}).get('tick'), 'before_identity': before['admitted']['identity'], 'onset_identity': after['admitted']['identity']}
                    if {'native', 'producer_published', 'bridge_published', 'admitted', 'consumed', 'bone'} <= stepped and 'A' not in found:
                        result['candidates'].append(dict(case='A', candidate='NATIVE_INPUT_STEP', proof='one exact connected before/onset pair; not vendor-library root cause', **detail)); found.add('A')
                    elif not ({'native', 'producer_published', 'bridge_input'} & stepped) and {'bridge_published', 'admitted', 'consumed'} <= stepped and 'B' not in found:
                        if (after['bridge_calibrated'].get('context') or {}).get('transform'):
                            result['candidates'].append(dict(case='B', candidate='BRIDGE_DIVERGENCE', **detail)); found.add('B')
                        else:
                            result.setdefault('candidate_coverage_gaps', []).append({'reason':'Bridge transform context unavailable', **detail})
                    elif not ({'native', 'producer_published', 'bridge_published', 'admitted'} & stepped) and 'consumed' in stepped and 'C' not in found:
                        result['candidates'].append(dict(case='C', candidate='CONSUMER_INTERPRETATION_DIVERGENCE', note='intentional reset interpretation remains possible', **detail)); found.add('C')
        # D: same local stage/bone baseline and returned consumed Q, with observed displaced tail.
        connected_bone_ids = {id(c['bone']) for c in chains}
        for raw_group in grouped.values():
            if raw_group[0]['stage'] != 'bone' or (physical(raw_group[0]), (raw_group[0].get('context') or {}).get('target')) in blocked:
                continue
            group = [row for row in raw_group if id(row) in connected_bone_ids]
            if len(group) < 3:
                continue
            target = (group[0].get('context') or {}).get('target')
            reads = [c['consumed'] for c in chains if (c['consumed'].get('context') or {}).get('target') == target and physical(c['consumed']) == physical(group[0]) and interpretation(c['consumed']) == interpretation(group[0])]
            by_site = defaultdict(list)
            for read in reads:
                by_site[(read.get('context') or {}).get('read_site')].append(read)
            returned = False
            for site_reads in by_site.values():
                site_reads.sort(key=lambda r: integer(r['process_monotonic_ns'], 'local time'))
                measured = distance(quaternion(site_reads[0]), quaternion(site_reads[-1])) if len(site_reads) >= 2 else None
                if measured is not None and measured <= 1.:
                    returned = True
            if not returned:
                continue
            tail = [r for r in group if integer(group[-1]['process_monotonic_ns'], 'local time') - integer(r['process_monotonic_ns'], 'local time') <= 2_000_000_000]
            if len(tail) >= 3 and integer(tail[-1]['process_monotonic_ns'], 'local time') - integer(tail[0]['process_monotonic_ns'], 'local time') >= 1_900_000_000 and all(distance(quaternion(group[0]), quaternion(r)) >= threshold for r in tail):
                result['candidates'].append({'case': 'D', 'candidate': 'SOLVER_BONE_RESIDUAL', 'target': target, 'note': 'not proof of irreversible corruption'})
        lifecycle = [t for b in bundles for t in b['triggers'] if str(t.get('trigger_type', '')).split('_')[0] in ('T3', 'T4', 'T5', 'T6')]
        if lifecycle and adjacent_steps:
            onset = [integer(s['after']['wall_clock_utc_ns'], 'step UTC') for s in adjacent_steps]
            correlated = [t for t in lifecycle if any(abs(integer(t['wall_clock_utc_ns'], 'lifecycle UTC') - x) <= wall_window_ns for x in onset)]
            if correlated:
                result['candidates'].append({'case': 'E', 'candidate': 'LIFECYCLE_CORRELATION', 'trigger_types': sorted({t['trigger_type'] for t in correlated})})
        if result.get('candidate_coverage_gaps') and not result['candidates']:
            result['issues'].append('candidate onset/pre/post correspondence is insufficient')
        if eligible_cohorts == 0 and not result['candidates']:
            result['issues'].append('no complete before/after consumer cohort')
        result['status'] = 'INSUFFICIENT_EVIDENCE' if result['issues'] else ('CORRELATED_NOT_PROVEN' if result['candidates'] else 'NO_DIVERGENCE_OBSERVED')
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundles', nargs='+', type=Path)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--threshold-deg', type=float, default=45.)
    args = parser.parse_args(argv)
    if not 0.1 <= args.threshold_deg <= 180.:
        parser.error('threshold must be 0.1..180 degrees')
    result = analyze(args.bundles, args.threshold_deg)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    json_path = args.output_dir / 'QJUMP_INCIDENT_ANALYSIS.json'
    md_path = args.output_dir / 'QJUMP_INCIDENT_ANALYSIS.md'
    # Explicit outputs never overwrite prior incident evidence.
    with json_path.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    with md_path.open('x', encoding='utf-8') as stream:
        stream.write(f"# Q-jump incident analysis\n\nStatus: **{result['status']}**\n\nCausality: CORRELATED_NOT_PROVEN\n\n")
        for candidate in result['candidates']:
            stream.write(f"- CASE {candidate['case']}: {candidate['candidate']}\n")
        if result['issues']:
            stream.write('\nEvidence limitations:\n\n' + ''.join(f'- {issue}\n' for issue in result['issues']))
    print(json.dumps({'status': result['status'], 'json': str(json_path), 'markdown': str(md_path)}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
