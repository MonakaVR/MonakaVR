import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  InputSelectionController,
  type InputCandidate,
  type InputSnapshot,
} from '../../src/monaka-input/controller.js';
import {
  sourceLabel,
  sourceDetails,
  sourcePhysicalId,
  trackerSourceLabel,
  selectedSourceLabel,
  selectedSourceDetails,
  selectedTrackerSourceLabel,
} from '../../src/monaka-input/source-label.js';

function candidate(id: string, name: string | null = 'device-17'): InputCandidate {
  return {
    id,
    publisher: `publisher-${id}`,
    source: `source-${id}`,
    tracker: `logical-${id}`,
    displayName: name,
    capabilities: ['position', 'orientation'],
    conflict: false,
    qSelectable: true,
    sixDofSelectable: true,
    qUsableNow: false,
    sixDofUsableNow: false,
    origin: 'mvip_pose',
  };
}
test('public device ID is primary; full immutable identity is available only in details', () => {
  const a = candidate('mtp:36:publisher-uuid36:source-uuid18:logical-random');
  assert.equal(sourceLabel(a, [a]), 'device-17');
  assert.match(sourceDetails(a), /Input device: device-17/);
  assert.ok(sourceDetails(a).includes(a.id));
  assert.ok(sourceDetails(a).includes(a.tracker));
  assert.equal(selectedSourceLabel(a.id, [a]), 'device-17');
  assert.equal(selectedSourceDetails(a.id, [a]), sourceDetails(a));
});
test('tracker labels opt into the logical ID and use only declared physical metadata', () => {
  const a = candidate('a', ' serial-080 ');
  const b = { ...candidate('b', 'serial-081'), publisher: a.publisher, tracker: a.tracker };
  assert.equal(sourcePhysicalId(a), ' serial-080 ');
  assert.equal(trackerSourceLabel(a, [a]), 'logical-a');
  assert.equal(trackerSourceLabel(a, [a, b]), 'logical-a · source-a');
  assert.equal(trackerSourceLabel(b, [b, a]), 'logical-a · source-b');
  assert.equal(selectedTrackerSourceLabel(b.id, [a, b]), 'logical-a · source-b');
  assert.equal(sourceLabel(a, [a, b]), 'serial-080');
  const legacy = { ...a, origin: 'slime_orientation_stream' };
  assert.equal(sourcePhysicalId(legacy), undefined);
  assert.equal(trackerSourceLabel(legacy, [legacy]), 'serial-080');
  for (const displayName of [undefined, null, '  ']) {
    const missing = { ...a, displayName };
    assert.equal(trackerSourceLabel(missing, [missing]), 'logical-a');
    assert.equal(sourcePhysicalId(missing), undefined);
  }
  assert.equal(selectedTrackerSourceLabel(a.id, [], 'None', 'Offline'), 'Offline');
  assert.equal(selectedTrackerSourceLabel(null, [], 'None', 'Offline'), 'None');
});
test('duplicate names disambiguate only when needed and never collapse IDs', () => {
  const sources = [candidate('left'), candidate('right')];
  const labels = sources.map((a) => sourceLabel(a, sources));
  assert.deepEqual(labels, [
    'device-17 · publisher-left',
    'device-17 · publisher-right',
  ]);
  assert.equal(sourceLabel(sources[0], [sources[0]]), 'device-17');
  assert.equal(sourceLabel(sources[0], [...sources].reverse()), labels[0]);
  const identical = sources.map((a) => ({
    ...a,
    source: 'same',
    tracker: 'same',
    publisher: 'same',
  }));
  assert.notEqual(
    sourceLabel(identical[0], identical),
    sourceLabel(identical[1], identical)
  );
  const longSources = sources.map((a, i) => ({
    ...a,
    publisher: `11111111-1111-4111-8111-11111111111${i}`,
  }));
  assert.notEqual(
    sourceLabel(longSources[0], longSources),
    sourceLabel(longSources[1], longSources),
    'opaque publisher UUIDs use distinct ordinals instead of shortened IDs'
  );
  const mixed = [
    { ...candidate('a'), source: 'x', tracker: 'same' },
    { ...candidate('b'), source: 'same', tracker: 'x' },
    { ...candidate('c'), source: 'same', tracker: 'same' },
  ];
  assert.equal(
    new Set(mixed.map((source) => sourceLabel(source, mixed))).size,
    3,
    'only one consistent descriptive publisher suffix is used per duplicate group'
  );
});
test('long Unicode names remain bounded and old APIs or cold offline references are honest', () => {
  const long = candidate('a', '機器'.repeat(48));
  assert.ok(Array.from(sourceLabel(long, [long])).length <= 32);
  assert.ok(sourceDetails(long).includes(long.displayName!));
  const old = candidate('old');
  delete old.displayName;
  assert.equal(sourceLabel(old, [old]), '未接続の入力');
  const legacy = {
    ...old,
    publisher: null,
    source: null,
    tracker: 'opaque-encoded-persistent-reference',
  };
  assert.equal(sourceLabel(legacy, [legacy]), '未接続の入力');
  assert.ok(sourceDetails(legacy).includes(legacy.tracker));
  const id =
    'mtp:36:11111111-1111-4111-8111-11111111111136:22222222-2222-4222-8222-22222222222220:opaque-logical-name';
  const offline = selectedSourceLabel(id, []);
  assert.equal(offline, '未接続の入力');
  assert.equal(
    selectedSourceLabel(id, [], 'Unassigned', 'Saved source (offline)'),
    'Saved source (offline)'
  );
  assert.ok(!offline.includes('mtp:'));
  assert.ok(selectedSourceDetails(id, [])?.includes(id));
  assert.equal(selectedSourceLabel(null, [], 'Unassigned'), 'Unassigned');
});
test('display metadata changes do not change generation, selection IDs, or save payloads', async () => {
  const a = {
    ...candidate('exact-persistent-key'),
    qUsableNow: true,
    sixDofUsableNow: true,
  };
  let snapshot: InputSnapshot = {
    generation: '7',
    bodies: ['HIP'],
    candidates: [a],
    assignments: [],
  };
  const requests: Record<string, unknown>[] = [];
  const model = new InputSelectionController({
    async request(fields) {
      requests.push(fields);
      if (fields.action === 'save')
        snapshot = {
          ...snapshot,
          generation: '8',
          assignments: [
            {
              body: 'HIP',
              qSource: a.id,
              sixDofSource: a.id,
              manualSix: false,
              mode: 'ik',
            },
          ],
        };
      return structuredClone(snapshot);
    },
  });
  await model.load();
  model.selectQ(a.id);
  snapshot.candidates = [{ ...a, displayName: 'renamed-public-device' }];
  await model.load();
  assert.equal(model.state.draft.qSource, a.id);
  assert.equal(model.state.draft.sixDofSource, a.id);
  assert.equal(model.state.generation, '7');
  await model.save();
  const save = requests.find((r) => r.action === 'save')!;
  assert.equal(save.qSource, a.id);
  assert.equal(save.sixDofSource, a.id);
  assert.ok(!('displayName' in save));
  assert.ok(!('tracker' in save));
});
