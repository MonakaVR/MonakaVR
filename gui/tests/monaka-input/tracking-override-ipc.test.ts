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
  source: 'test-public-source',
  gripOffset: { positionM: [0, 0, 0], rotationWxyz: [1, 0, 0, 0] },
  sameUniverseConfirmed: true,
};

function setup() {
  const mutations: unknown[][] = [];
  const coreRequests: unknown[] = [];
  const snapshot: SteamOverrideSnapshot = {
    token: 'steam-review',
    hands: {
      left: { owned: false, conflict: false },
      right: { owned: true, conflict: false },
    },
  };
  const coreSnapshot: InputSnapshot = {
    generation: '0',
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
          saved: selection,
        },
        right: {
          ...overridePaths('right'),
          registeredDevicePath: null,
          sourceStatus: null,
          saved: selection,
        },
      },
    },
  };
  let connections = 0;
  let closes = 0;
  const steam = {
    snapshot: async () => snapshot,
    change: async (...args: unknown[]) => {
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
            coreRequests.push(request);
            return coreSnapshot;
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
    connections: () => connections,
    closes: () => closes,
  };
}

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
