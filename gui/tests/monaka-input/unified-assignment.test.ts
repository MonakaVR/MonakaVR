import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BodyPart } from 'solarxr-protocol';
import {
  executeAssignment,
  legacyObserved,
  legacyUnchanged,
  locatorKey,
  planAssignment,
  presentCandidates,
  type LegacyTracker,
} from '../../src/monaka-input/assignment.js';
import {
  bodyPartForInput,
  INPUT_ROLES,
  inputRole,
} from '../../src/monaka-input/roles.js';
import {
  InputSelectionController,
  type InputCandidate,
  type InputSnapshot,
} from '../../src/monaka-input/controller.js';

const physical: LegacyTracker = {
  id: { deviceId: { id: 0 }, trackerNum: 0 },
  body: BodyPart.CHEST,
  editable: true,
};
const source: InputCandidate = {
  id: 'opaque-physical',
  publisher: null,
  source: null,
  tracker: 'display-name-is-not-identity',
  capabilities: ['orientation'],
  conflict: false,
  qSelectable: true,
  sixDofSelectable: false,
  qUsableNow: true,
  sixDofUsableNow: false,
  origin: 'slime_orientation_stream',
  slimeTrackerId: physical.id,
};
const snapshot = (): InputSnapshot => ({
  generation: '0',
  bodies: ['HIP', 'CHEST'],
  candidates: [source],
  assignments: [],
});
const draft = {
  body: 'HIP',
  qSource: source.id,
  sixDofSource: null,
  manualSix: false,
  mode: 'ik',
};

test('explicit role map is bijective for all 50 supported names and excludes unsupported values', () => {
  assert.equal(Object.keys(INPUT_ROLES).length, 50);
  assert.equal(new Set(Object.values(INPUT_ROLES)).size, 50);
  for (const [part, role] of Object.entries(INPUT_ROLES)) {
    assert.equal(BodyPart[Number(part)], role);
    assert.equal(inputRole(Number(part), [role]), role);
    assert.equal(bodyPartForInput(role), Number(part));
  }
  for (const part of [
    BodyPart.NONE,
    BodyPart.LEFT_HIP,
    BodyPart.RIGHT_HIP,
    12,
    13,
    255,
  ])
    assert.equal(inputRole(part, Object.values(INPUT_ROLES)), null);
  assert.equal(inputRole(BodyPart.HIP, ['CHEST']), null);
});

test('locator zero is valid and no-device differs from device zero; invalid transport IDs fail closed', () => {
  assert.equal(locatorKey(physical.id), '0:0');
  assert.equal(locatorKey({ deviceId: null, trackerNum: 0 }), 'none:0');
  for (const id of [
    null,
    undefined,
    { deviceId: { id: 256 }, trackerNum: 0 },
    { deviceId: null, trackerNum: -1 },
    { deviceId: null, trackerNum: 0.5 },
  ])
    assert.equal(locatorKey(id), null);
});

test('typed adaptor uses exact locator and never joins display labels or duplicate locators', () => {
  const adapt = (sources: InputCandidate[], trackers: LegacyTracker[]) =>
    presentCandidates(sources, trackers, (t) => t.id);
  assert.deepEqual(adapt([source], [physical])[0], {
    kind: 'slime',
    source,
    tracker: physical,
  });
  for (const candidates of [
    [{ ...source, slimeTrackerId: null }],
    [{ ...source, slimeTrackerId: undefined }],
  ])
    assert.equal(
      (adapt(candidates, [physical])[0] as { tracker: unknown }).tracker,
      null
    );
  assert.equal(
    (adapt([source], [physical, physical])[0] as { tracker: unknown }).tracker,
    null
  );
  const mvip = { ...source, id: 'mvip', origin: 'mvip_pose', slimeTrackerId: null };
  assert.deepEqual(adapt([mvip], [physical])[0], { kind: 'mvip', source: mvip });
});

test('physical replacement plan includes only exact target clear and selected tracker move', () => {
  const old = {
    ...physical,
    id: { deviceId: { id: 1 }, trackerNum: 2 },
    body: BodyPart.HIP,
  };
  const plan = planAssignment(snapshot(), draft, BodyPart.HIP, [physical, old]);
  assert.deepEqual(plan.changes, [
    { id: old.id, from: BodyPart.HIP, to: BodyPart.NONE },
    { id: physical.id, from: BodyPart.CHEST, to: BodyPart.HIP },
  ]);
  assert.equal(legacyUnchanged(plan, [physical, old]), true);
  assert.equal(
    legacyUnchanged(plan, [{ ...physical, body: BodyPart.HEAD }, old]),
    false
  );
  assert.equal(
    legacyUnchanged(plan, [
      physical,
      old,
      { ...old, id: { deviceId: null, trackerNum: 8 } },
    ]),
    false
  );
  assert.equal(
    legacyObserved(plan, [
      { ...physical, body: BodyPart.HIP },
      { ...old, body: BodyPart.NONE },
    ]),
    true
  );
});

test('source reuse across roles, ambiguous association and noneditable displacement are rejected before writes', () => {
  const s = snapshot();
  s.assignments.push({ ...draft, body: 'CHEST' });
  assert.throws(
    () => planAssignment(s, draft, BodyPart.HIP, [physical]),
    /another body/
  );
  assert.throws(
    () => planAssignment(snapshot(), draft, BodyPart.HIP, [physical, physical]),
    /associate/
  );
  const old = {
    ...physical,
    id: { deviceId: null, trackerNum: 4 },
    body: BodyPart.HIP,
    editable: false,
  };
  assert.throws(
    () => planAssignment(snapshot(), draft, BodyPart.HIP, [physical, old]),
    /safely reassigned/
  );
});

test('six-only change preserves legacy assignment; explicit Q clear and full reset require legacy plan', () => {
  const old = { ...physical, body: BodyPart.HIP };
  const sixOnly = {
    ...draft,
    qSource: null,
    sixDofSource: 'mvip-six',
    manualSix: true,
  };
  assert.equal(
    planAssignment(snapshot(), sixOnly, BodyPart.HIP, [old]).changes.length,
    0
  );
  assert.equal(
    planAssignment(snapshot(), sixOnly, BodyPart.HIP, [old], true).changes.length,
    1
  );
  const reset = planAssignment(
    snapshot(),
    { ...sixOnly, sixDofSource: null },
    BodyPart.HIP,
    [old]
  );
  assert.equal(reset.reset, true);
  assert.equal(reset.changes.length, 1);
});

test('Monaka failure never sends legacy write; concurrent legacy change after save leaves partial result', async () => {
  const plan = planAssignment(snapshot(), draft, BodyPart.HIP, [physical]);
  let writes = 0;
  const io = {
    save: async () => false,
    current: () => [physical],
    connected: () => true,
    write: () => {
      writes++;
    },
    observe: async () => true,
  };
  assert.equal(await executeAssignment(plan, io), 'save-unknown');
  assert.equal(writes, 0);
  assert.equal(
    await executeAssignment(plan, {
      ...io,
      save: async () => true,
      current: () => [{ ...physical, body: BodyPart.HEAD }],
    }),
    'partial'
  );
  assert.equal(writes, 0);
  assert.equal(
    await executeAssignment(plan, {
      ...io,
      save: async () => true,
      connected: () => false,
    }),
    'partial'
  );
  assert.equal(writes, 0);
});

test('save precedes legacy write and absent feed confirmation is partial with no compensation', async () => {
  const plan = planAssignment(snapshot(), draft, BodyPart.HIP, [physical]);
  const events: string[] = [];
  const result = await executeAssignment(plan, {
    save: async () => {
      events.push('saved');
      return true;
    },
    current: () => [physical],
    connected: () => true,
    write: () => events.push('write'),
    observe: async () => {
      events.push('observe');
      return false;
    },
  });
  assert.equal(result, 'partial');
  assert.deepEqual(events, ['saved', 'write', 'observe']);
});

test('confirmed feed reports observed legacy only; unchanged physical Q can reconcile after partial save', async () => {
  const s = snapshot();
  s.assignments.push(draft);
  const plan = planAssignment(s, draft, BodyPart.HIP, [physical]);
  assert.equal(plan.changes.length, 1);
  assert.equal(
    await executeAssignment(plan, {
      save: async () => true,
      current: () => [physical],
      connected: () => true,
      write: () => {},
      observe: async () => true,
    }),
    'legacy-observed'
  );
});

test('controller rejects false success readback and preserves dirty draft for explicit reload', async () => {
  const s = snapshot();
  const model = new InputSelectionController({
    request: async () => structuredClone(s),
  });
  await model.load();
  model.selectQ(source.id);
  assert.equal(await model.save(), false);
  assert.equal(model.state.needsReload, true);
  assert.equal(model.state.draft.qSource, source.id);
  assert.equal(model.state.dirty, true);
});
