import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  InputSelectionController,
  type InputApi,
  type InputCandidate,
  type InputSnapshot,
} from '../../src/monaka-input/controller.js';

function candidate(id: string, six = false, origin = 'mvip_pose'): InputCandidate {
  return {
    id,
    publisher: id,
    source: 'source',
    tracker: 'same-name',
    capabilities: six ? ['position', 'orientation'] : ['orientation'],
    conflict: false,
    qSelectable: true,
    sixDofSelectable: six,
    qUsableNow: false,
    sixDofUsableNow: false,
    origin,
  };
}
class Api implements InputApi {
  value: InputSnapshot = {
    generation: '0',
    bodies: ['HIP', 'CHEST'],
    candidates: [
      candidate('publisher-a', true),
      candidate('publisher-b', true),
      candidate('q-only'),
      candidate('slime-q', false, 'slime_orientation_stream'),
    ],
    assignments: [],
  };
  saves: Record<string, unknown>[] = [];
  fail = false;
  async request(fields: Record<string, unknown>) {
    if (fields.action === 'save') {
      this.saves.push(fields);
      if (this.fail || fields.generation !== this.value.generation)
        throw new Error('Stale or failed save');
      this.value.generation = String(BigInt(this.value.generation) + 1n);
      this.value.assignments = [
        {
          body: fields.body as string,
          qSource: fields.qSource as string | null,
          sixDofSource: fields.sixDofSource as string | null,
          manualSix: fields.manualSix as boolean,
          mode: 'ik',
        },
      ];
    }
    return structuredClone(this.value);
  }
}
test('explicit reload blocks editing and survives an in-flight metadata request', async () => {
  const api = new Api();
  const model = new InputSelectionController(api);
  await model.load();
  let release!: (value: InputSnapshot) => void;
  let calls = 0;
  api.request = async () => {
    calls++;
    if (calls === 1)
      return new Promise<InputSnapshot>((resolve) => {
        release = resolve;
      });
    return structuredClone(api.value);
  };
  const pending = model.load();
  await model.load(true);
  assert.equal(model.state.busy, true);
  model.selectQ('publisher-a');
  assert.equal(model.state.draft.qSource, null);
  release(structuredClone(api.value));
  await pending;
  assert.equal(calls, 2);
  assert.equal(model.state.busy, false);
  model.selectQ('publisher-a');
  model.selectSix('publisher-b');
  assert.equal(model.state.draft.sixDofSource, 'publisher-b');
});
test('first Pos+Q selection uses static support even while invalid; manual clear persists', async () => {
  const api = new Api(),
    model = new InputSelectionController(api);
  await model.load();
  model.selectQ('publisher-a');
  assert.equal(model.state.draft.sixDofSource, 'publisher-a');
  model.selectSix(null);
  await model.save();
  assert.equal(model.state.draft.sixDofSource, null);
  api.value.candidates.forEach((x) => {
    x.qUsableNow = true;
    x.sixDofUsableNow = x.sixDofSelectable;
  });
  await model.load();
  assert.equal(model.state.draft.sixDofSource, null);
  model.selectQ('publisher-b');
  assert.equal(model.state.draft.sixDofSource, null);
});
test('Q-only and Slime never fill 6DoF; unknown/conflicting support is not selectable', async () => {
  for (const id of ['q-only', 'slime-q']) {
    const model = new InputSelectionController(new Api());
    await model.load();
    model.selectQ(id);
    assert.equal(model.state.draft.qSource, id);
    assert.equal(model.state.draft.sixDofSource, null);
  }
  const api = new Api();
  api.value.candidates.push({
    ...candidate('unknown'),
    capabilities: null,
    qSelectable: false,
  });
  const model = new InputSelectionController(api);
  await model.load();
  model.selectQ('unknown');
  assert.equal(model.state.draft.qSource, null);
  assert.equal(model.state.draft.sixDofSource, null);
});
test('same names with different publishers, independent manual choices, and 6DoF-only', async () => {
  const api = new Api(),
    model = new InputSelectionController(api);
  await model.load();
  model.selectSix('publisher-b');
  assert.equal(model.state.draft.qSource, null);
  await model.save();
  model.selectQ('publisher-a');
  assert.equal(model.state.draft.sixDofSource, 'publisher-b');
  await model.save();
  assert.equal(model.state.draft.qSource, 'publisher-a');
  assert.equal(model.state.draft.sixDofSource, 'publisher-b');
});
test('cancel is read-only; reconnect metadata preserves an unsaved explicit choice', async () => {
  const api = new Api(),
    model = new InputSelectionController(api);
  await model.load();
  model.selectQ('q-only');
  await model.load();
  assert.equal(model.state.draft.qSource, 'q-only');
  model.cancel();
  assert.equal(model.state.draft.qSource, null);
  assert.equal(api.saves.length, 0);
  model.selectQ('publisher-a');
  model.selectQ('publisher-b');
  assert.equal(model.state.draft.sixDofSource, 'publisher-a');
});
test('failure or unknown outcome prevents retry until explicit reload; no automatic save', async () => {
  const api = new Api(),
    model = new InputSelectionController(api);
  await model.load();
  model.selectQ('publisher-a');
  api.fail = true;
  await model.save();
  assert.equal(model.state.dirty, true);
  assert.equal(model.state.needsReload, true);
  await model.load();
  await model.save();
  assert.equal(api.saves.length, 1);
  assert.match(model.state.error, /Reload/);
  api.fail = false;
  await model.load(true);
  model.selectQ('q-only');
  await model.save();
  assert.equal(api.saves.length, 2);
});
test('another writer generation never overwrites the dirty draft or the saved assignment', async () => {
  const api = new Api(),
    model = new InputSelectionController(api);
  await model.load();
  model.selectQ('q-only');
  api.value.generation = '1';
  api.value.assignments = [
    {
      body: 'HIP',
      qSource: 'publisher-b',
      sixDofSource: null,
      manualSix: true,
      mode: 'ik',
    },
  ];
  await model.load();
  assert.equal(model.state.draft.qSource, 'q-only');
  assert.equal(model.state.generation, '0');
  await model.save();
  assert.equal(api.value.assignments[0].qSource, 'publisher-b');
  assert.equal(model.state.needsReload, true);
});
