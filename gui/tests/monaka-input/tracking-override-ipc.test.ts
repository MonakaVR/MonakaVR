import assert from 'node:assert/strict';
import { test } from 'node:test';
import { requestTrackingOverride } from '../../electron/main/tracking-override-ipc.js';
import {
  overridePaths,
  type OverrideSelection,
  type SteamOverrideSnapshot,
} from '../../electron/tracking-override-types.js';
import type { InputSnapshot } from '../../src/monaka-input/controller.js';

const selection: OverrideSelection = {
  enabled: true,
  gripOffset: { positionM: [0, 0, 0], rotationWxyz: [1, 0, 0, 0] },
  sameUniverseConfirmed: true,
};

function setup({ blockRemove = false, failSave = false } = {}) {
  const mutations: unknown[][] = [];
  const coreRequests: unknown[] = [];
  const order: string[] = [];
  const snapshot: SteamOverrideSnapshot = {
    token: 'steam-review',
    hands: {
      left: { owned: false, conflict: false },
      right: { owned: true, conflict: false },
    },
  };
  const coreSnapshot: InputSnapshot = {
    generation: '0',
    readbackToken: 'core-review',
    candidates: [],
    assignments: [],
    bodies: [],
    trackingOverride: {
      driverReady: false,
      hands: {
        left: {
          ...overridePaths('left'),
          registeredDevicePath: null,
          sourceStatus: null,
          source: 'test-public-source',
          canEnable: true,
          calibrationCurrent: true,
          saved: selection,
        },
        right: {
          ...overridePaths('right'),
          registeredDevicePath: null,
          sourceStatus: null,
          source: 'test-public-source',
          canEnable: true,
          calibrationCurrent: true,
          saved: selection,
        },
      },
    },
  };
  let connections = 0;
  let closes = 0;
  let steamReads = 0;
  const steam = {
    snapshot: async () => {
      steamReads++;
      return snapshot;
    },
    change: async (...args: unknown[]) => {
      order.push('steam:' + args[0]);
      if (blockRemove) throw new Error('SteamVR still running');
      mutations.push(args);
      return snapshot;
    },
  };
  const dispatch = (value: unknown) =>
    requestTrackingOverride(
      value,
      steam,
      () => {
        connections++;
        return {
          request: async (request) => {
            order.push('core:' + request.action);
            coreRequests.push(request);
            if (failSave && request.action === 'save_override')
              throw new Error('Core save uncertain');
            return coreSnapshot;
          },
          requestPreview: async (request) => {
            coreRequests.push(request);
          },
          close: () => closes++,
        };
      },
      'desktop-secret'
    );
  return {
    dispatch,
    coreRequests,
    mutations,
    coreSnapshot,
    order,
    connections: () => connections,
    closes: () => closes,
    steamReads: () => steamReads,
  };
}

test('Preview and clear use only the authenticated lightweight Core action', async () => {
  const fixture = setup();
  const request = {
    action: 'preview',
    hand: 'left',
    token: 'core-review',
    gripOffset: selection.gripOffset,
  };
  await assert.rejects(
    () => fixture.dispatch({ ...request, enabled: true }),
    /preview/
  );
  await assert.rejects(
    () =>
      fixture.dispatch({
        ...request,
        gripOffset: { positionM: [NaN, 0, 0], rotationWxyz: [1, 0, 0, 0] },
      }),
    /preview/
  );
  assert.equal(fixture.connections(), 0);

  assert.deepEqual(await fixture.dispatch(request), { type: 'preview' });
  assert.deepEqual(await fixture.dispatch({ ...request, gripOffset: null }), {
    type: 'preview',
  });
  assert.deepEqual(
    fixture.coreRequests,
    [selection.gripOffset, null].map((gripOffset) => ({
      action: 'preview_override',
      hand: 'left',
      readbackToken: 'core-review',
      editorToken: 'desktop-secret',
      gripOffset,
    }))
  );
  assert.equal(fixture.connections(), 2);
  assert.equal(fixture.closes(), 2);
  assert.equal(fixture.steamReads(), 0);
  assert.deepEqual(fixture.mutations, []);
});

test('Apply requires exact activated path for the saved hand and allows earlier registration after stopping', async () => {
  const fixture = setup();
  const action = { action: 'apply', hand: 'left', token: 'steam-review' };
  await assert.rejects(() => fixture.dispatch(action), /driver registration/);
  const row = fixture.coreSnapshot.trackingOverride!.hands.left;
  row.registeredDevicePath = overridePaths('right').devicePath;
  await assert.rejects(() => fixture.dispatch(action), /driver registration/);
  row.registeredDevicePath = row.devicePath;
  row.saved = { ...selection, enabled: false };
  await assert.rejects(() => fixture.dispatch(action), /saved|Save/);
  row.saved = selection;
  row.calibrationCurrent = false;
  await assert.rejects(() => fixture.dispatch(action), /Save/);
  row.calibrationCurrent = true;
  row.canEnable = false;
  await assert.rejects(() => fixture.dispatch(action), /Save/);
  row.canEnable = true;
  assert.equal((await fixture.dispatch(action)).type, 'steam');
  assert.deepEqual(fixture.mutations, [['apply', 'left', 'steam-review']]);
  assert.equal(fixture.connections(), fixture.closes());
});

test('Remove bypasses Core and malformed hand or extra path never reaches a writer', async () => {
  const fixture = setup();
  await assert.rejects(
    () => fixture.dispatch({ action: 'remove', hand: 'head', token: 'steam-review' }),
    /hand/
  );
  await assert.rejects(
    () =>
      fixture.dispatch({
        action: 'remove',
        hand: 'right',
        token: 'steam-review',
        path: 'bad',
      }),
    /action/
  );
  assert.deepEqual(fixture.mutations, []);
  await fixture.dispatch({ action: 'remove', hand: 'right', token: 'steam-review' });
  assert.equal(fixture.connections(), 0);
  assert.deepEqual(fixture.mutations, [['remove', 'right', 'steam-review']]);
});

test('Only the desktop-to-Core save injects the editor credential', async () => {
  const fixture = setup();
  const reply = await fixture.dispatch({
    action: 'save',
    hand: 'right',
    token: 'core-review',
    selection,
  });
  assert.deepEqual(reply, { type: 'core' });
  assert.deepEqual(fixture.coreRequests, [
    {
      action: 'save_override',
      hand: 'right',
      selection,
      readbackToken: 'core-review',
      editorToken: 'desktop-secret',
    },
  ]);
  assert.deepEqual(fixture.mutations, []);
});

test('Disabling checks the local Core token, then removes its owned mapping before saving OFF', async () => {
  const fixture = setup();
  const action = {
    action: 'save',
    hand: 'right',
    token: 'core-review',
    selection: { ...selection, enabled: false },
  };
  await assert.rejects(
    () => fixture.dispatch({ ...action, token: 'foreign' }),
    /Reload/
  );
  assert.deepEqual(fixture.mutations, []);
  fixture.order.length = 0;
  assert.equal((await fixture.dispatch(action)).type, 'core');
  assert.deepEqual(fixture.order, [
    'core:snapshot',
    'steam:remove',
    'core:save_override',
  ]);
});

test('A blocked removal preserves Core, and a failed Core save reports the completed removal', async () => {
  const action = {
    action: 'save',
    hand: 'right',
    token: 'core-review',
    selection: { ...selection, enabled: false },
  };
  const blocked = setup({ blockRemove: true });
  await assert.rejects(() => blocked.dispatch(action), /still running/);
  assert.deepEqual(blocked.order, ['core:snapshot', 'steam:remove']);
  const partial = await setup({ failSave: true }).dispatch(action);
  assert.equal(partial.type, 'core');
  if (partial.type !== 'core') throw new Error('Expected Core save outcome');
  assert.ok(partial.settings);
  assert.match(partial.error ?? '', /Core save uncertain/);
});
