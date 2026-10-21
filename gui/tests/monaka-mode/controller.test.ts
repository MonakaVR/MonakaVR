import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  TrackingModeController,
  parseModeSnapshot,
  type ModeSnapshot,
} from '../../src/monaka-mode/mode-controller.js';

function snapshot(): ModeSnapshot {
  return {
    generation: '9007199254740993',
    readbackToken: 'review-token',
    restartRequired: false,
    backgroundIkAlignmentConfirmed: false,
    bodies: ['HIP', 'LEFT_FOOT'],
    assignments: ['HIP', 'LEFT_FOOT'].map((body) => ({
      body,
      mode: 'ik',
      activeMode: 'ik',
      savedMode: 'ik',
      restartRequired: false,
      modeOptions: ['ik', 'direct', 'hybrid'].map((mode) => ({
        mode: mode as 'ik' | 'direct' | 'hybrid',
        available: true,
        reason: null,
      })),
    })),
  };
}
class Api {
  value = snapshot();
  calls: Record<string, unknown>[] = [];
  async request(fields: Record<string, unknown>): Promise<unknown> {
    this.calls.push(fields);
    if (fields.action === 'save_mode') {
      assert.equal(fields.generation, this.value.generation);
      assert.equal(fields.readbackToken, this.value.readbackToken);
      this.value.restartRequired = true;
      const row = this.value.assignments.find((row) => row.body === fields.body)!;
      row.savedMode = fields.mode as string;
      row.restartRequired = true;
      this.value.readbackToken = 'new-token';
    }
    return structuredClone(this.value);
  }
}
function defer<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((a, b) => {
    resolve = a;
    reject = b;
  });
  return { promise, resolve, reject };
}
test('mock: three output modes require review; save sends only mode fields and preserves U63', async () => {
  const api = new Api();
  const model = new TrackingModeController(api);
  await model.load();
  assert.equal(model.state.phase, 'loaded');
  model.selectMode('hybrid');
  await model.save();
  assert.equal(api.calls.length, 1);
  model.review();
  assert.equal(model.state.phase, 'review');
  await model.save();
  assert.deepEqual(api.calls[1], {
    action: 'save_mode',
    body: 'HIP',
    mode: 'hybrid',
    generation: '9007199254740993',
    readbackToken: 'review-token',
  });
  assert.equal(model.assignment?.activeMode, 'ik');
  assert.equal(model.assignment?.savedMode, 'hybrid');
  assert.equal(model.state.phase, 'pendingRestart');
  model.cancel();
  model.selectMode('direct');
  await model.save();
  assert.equal(api.calls.length, 2);
  await model.load();
  assert.equal(model.state.phase, 'pendingRestart');
  api.value.assignments[0].activeMode = 'hybrid';
  api.value.assignments[0].mode = 'hybrid';
  api.value.assignments[0].restartRequired = false;
  api.value.restartRequired = false;
  await model.load();
  assert.equal(model.state.phase, 'loaded');
  assert.equal(model.assignment?.activeMode, model.assignment?.savedMode);
});
test('mock: cancel discards only current body draft; body switches retain manual mode drafts', async () => {
  const api = new Api();
  const model = new TrackingModeController(api);
  await model.load();
  model.selectMode('hybrid');
  model.selectBody('LEFT_FOOT');
  model.selectMode('direct');
  model.selectBody('HIP');
  assert.equal(model.draft, 'hybrid');
  model.review();
  model.cancel();
  assert.equal(model.draft, 'ik');
  model.selectBody('LEFT_FOOT');
  assert.equal(model.draft, 'direct');
  assert.equal(api.calls.length, 1);
});
test('mock: rapid clicks and reload while saving cannot start duplicate requests', async () => {
  const api = new Api();
  const model = new TrackingModeController(api);
  await model.load();
  model.selectMode('direct');
  model.review();
  const pending = defer<unknown>();
  let saves = 0;
  api.request = async () => {
    saves++;
    return pending.promise;
  };
  const first = model.save();
  await model.save();
  await model.load();
  model.cancel();
  model.selectBody('LEFT_FOOT');
  assert.equal(saves, 1);
  assert.equal(model.state.phase, 'saving');
  assert.equal(model.state.body, 'HIP');
  const response = snapshot();
  response.readbackToken = 'committed-token';
  response.restartRequired = true;
  response.assignments[0].savedMode = 'direct';
  response.assignments[0].restartRequired = true;
  pending.resolve(response);
  await first;
  assert.equal(model.state.phase, 'pendingRestart');
});
test('mock: inconsistent commit token, runtime generation or active mode requires diagnosis', async () => {
  for (const mismatch of ['token', 'generation', 'active']) {
    const api = new Api();
    const model = new TrackingModeController(api);
    await model.load();
    model.selectMode('direct');
    model.review();
    const request = api.request.bind(api);
    api.request = async (fields) => {
      const response = (await request(fields)) as ModeSnapshot;
      if (mismatch === 'token') response.readbackToken = 'review-token';
      if (mismatch === 'generation') response.generation = '9007199254740994';
      if (mismatch === 'active') {
        response.assignments[0].activeMode = 'direct';
        response.assignments[0].mode = 'direct';
      }
      return response;
    };
    await model.save();
    assert.equal(model.state.phase, 'error', mismatch);
    assert.equal(model.state.needsReload, true);
  }
});
test('mock: conflict requires explicit reload and fresh review', async () => {
  const api = new Api();
  const model = new TrackingModeController(api);
  await model.load();
  model.selectMode('direct');
  model.review();
  const realRequest = api.request.bind(api);
  api.request = async () => {
    throw new Error('Configuration changed before commit');
  };
  await model.save();
  assert.equal(model.state.phase, 'error');
  assert.equal(model.canEdit, false);
  model.cancel();
  assert.equal(model.state.needsReload, true);
  api.request = realRequest;
  await model.load();
  assert.equal(model.state.phase, 'loaded');
});
test('mock: timeout after committed save requires readback; no retry can overwrite pending mode', async () => {
  const api = new Api();
  const model = new TrackingModeController(api, 10);
  await model.load();
  model.selectMode('hybrid');
  model.review();
  const realRequest = api.request.bind(api);
  api.request = async (fields) => {
    await realRequest(fields);
    return new Promise(() => {});
  };
  await model.save();
  assert.equal(model.state.phase, 'error');
  assert.match(model.state.error, /unknown/);
  await model.save();
  assert.equal(api.calls.length, 2);
  api.request = realRequest;
  await model.load();
  assert.equal(model.state.phase, 'pendingRestart');
});
test('mock: disconnect invalidates an old save response across reconnect and reload', async () => {
  const api = new Api();
  const model = new TrackingModeController(api);
  await model.load();
  model.selectMode('direct');
  model.review();
  const old = defer<unknown>();
  const realRequest = api.request.bind(api);
  api.request = () => old.promise;
  const saving = model.save();
  model.disconnect();
  assert.equal(model.state.phase, 'disconnected');
  api.request = realRequest;
  await model.load();
  old.resolve({ invalid: true });
  await saving;
  assert.equal(model.state.phase, 'loaded');
  assert.equal(model.state.needsReload, false);
  assert.equal(model.assignment?.savedMode, 'ik');
});
test('mock: unknown modes display as unknown and cannot enable editing; invalid availability fails closed', async () => {
  const api = new Api();
  api.value.assignments[0].mode = 'future';
  api.value.assignments[0].activeMode = 'future';
  api.value.assignments[0].savedMode = 'future';
  const model = new TrackingModeController(api);
  await model.load();
  assert.equal(model.canEdit, false);
  model.selectMode('ik');
  assert.equal(model.draft, 'future');
  assert.throws(() => parseModeSnapshot({ ...snapshot(), readbackToken: undefined }));
  const invalid = snapshot();
  invalid.assignments[0].modeOptions = [];
  assert.throws(() => parseModeSnapshot(invalid));
  const inconsistent = snapshot();
  inconsistent.assignments[0].savedMode = 'direct';
  assert.throws(() => parseModeSnapshot(inconsistent));
});
test('mock: Core validator reasons prevent selection; unassigned bodies cannot create a mode assignment', async () => {
  const api = new Api();
  api.value.assignments[0].modeOptions[1] = {
    mode: 'direct',
    available: false,
    reason: 'Direct output requires a SteamVR role',
  };
  api.value.bodies.push('CHEST');
  const model = new TrackingModeController(api);
  await model.load();
  model.selectMode('direct');
  assert.equal(model.draft, 'ik');
  model.selectBody('CHEST');
  assert.equal(model.canEdit, false);
  model.selectMode('hybrid');
  assert.equal(model.canReview, false);
});
