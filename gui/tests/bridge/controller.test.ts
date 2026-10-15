import assert from 'node:assert/strict';
import { test } from 'node:test';
import { BridgeController } from '../../src/bridge/controller.js';
import { BridgeHealthPresentation } from '../../electron/bridge-health.js';
import { bridgeTrayEntry } from '../../electron/main/monaka-bridge-tray.js';
import type {
  BridgeConfiguration,
  BridgeDesktopApi,
  BridgeHealthRead,
  BridgePolicy,
} from '../../electron/bridge-types.js';

const configuration = (policy: BridgePolicy = 'steamvr'): BridgeConfiguration => ({
  locations: {
    configPath: 'fixture/bridge.json',
    validatorPath: 'fixture/monaka_bridge_config.exe',
  },
  token: 'token',
  policy,
  revision: 4,
  publisher: 'bridge-fixture',
  mappings: [],
});
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { resolve, reject, promise };
};
const health = (
  stamp = 1000,
  overrides: Record<string, unknown> = {}
): BridgeHealthRead => ({
  configPath: 'fixture/bridge.json',
  error: null,
  value: {
    publisher_id: 'bridge-fixture',
    snapshot_unix_ms: stamp,
    policy: 'monaka',
    mapping_revision: 4,
    pose_timeout_ms: 500,
    devices: [
      {
        publisher_id: 'bridge-fixture',
        source: 'source-a',
        device: 'device-a',
        tracker: 'tracker',
        fresh: true,
        pose_age_ms: 100,
        position_valid: true,
        orientation_valid: false,
        collision: false,
        tracking_state: 'degraded',
      },
    ],
    ...overrides,
  },
});
class Api implements BridgeDesktopApi {
  loaded = configuration();
  calls = 0;
  reads = 0;
  loads = 0;
  cancelled: string[] = [];
  loadError: Error | null = null;
  chooseError: Error | null = null;
  cancelError: Error | null = null;
  chooseResult: BridgeConfiguration | null = null;
  pendingLoad: ReturnType<typeof deferred<BridgeConfiguration>> | null = null;
  pending = deferred<BridgeConfiguration>();
  pendingHealth: ReturnType<typeof deferred<BridgeHealthRead>> | null = null;
  result = health();
  lastId = '';
  async load() {
    ++this.loads;
    if (this.loadError) throw this.loadError;
    return this.pendingLoad ? this.pendingLoad.promise : this.loaded;
  }
  async choose() {
    if (this.chooseError) throw this.chooseError;
    return this.chooseResult;
  }
  async health() {
    ++this.reads;
    return this.pendingHealth ? this.pendingHealth.promise : this.result;
  }
  async apply(_token: string, _policy: BridgePolicy, id: string) {
    ++this.calls;
    this.lastId = id;
    return this.pending.promise;
  }
  async cancel(id: string) {
    this.cancelled.push(id);
    if (this.cancelError) throw this.cancelError;
  }
}
const choose = (model: BridgeController, policy: BridgePolicy) => {
  model.selectPolicy(policy);
  model.confirm(true);
};

test('load restores saved setting, review is required and selection changes revoke review', async () => {
  const api = new Api(),
    model = new BridgeController(api);
  await model.load();
  assert.equal(model.state.selectedPolicy, 'steamvr');
  assert.equal(model.canApply, false);
  model.selectPolicy('both');
  assert.equal(model.canApply, false);
  model.confirm(true);
  assert.equal(model.canApply, true);
  model.selectPolicy('monaka');
  assert.equal(model.state.confirmed, false);
  assert.equal(model.canApply, false);
  model.selectPolicy('invalid' as never);
  assert.equal(model.state.selectedPolicy, 'monaka');
});

test('duplicate apply and refresh are guarded, mutation during save is ignored, cancel waits for completion', async () => {
  const api = new Api(),
    model = new BridgeController(api);
  await model.load();
  choose(model, 'both');
  const pending = model.apply();
  await Promise.all(Array.from({ length: 1000 }, () => model.apply()));
  await model.load();
  assert.equal(api.calls, 1);
  assert.equal(api.loads, 1);
  assert.equal(model.state.busy, true);
  model.selectPolicy('disabled');
  model.confirm(false);
  assert.equal(model.state.selectedPolicy, 'both');
  assert.equal(model.state.confirmed, true);
  await model.cancel();
  assert.equal(api.cancelled.at(-1), api.lastId);
  assert.equal(model.state.busy, true);
  api.pending.reject(new Error('aborted'));
  await pending;
  assert.equal(model.state.selectedPolicy, 'steamvr');
  assert.equal(model.state.confirmed, false);
  assert.equal(model.state.busy, false);
  assert.match(model.state.message, /Cancelled before save/);
});

test('save failure restores last loaded setting, retry succeeds, cancelling after commit reports saved', async () => {
  const api = new Api(),
    model = new BridgeController(api);
  await model.load();
  choose(model, 'both');
  const failed = model.apply();
  api.pending.reject(new Error('validation failure'));
  await failed;
  assert.match(model.state.message, /validation failure/);
  assert.equal(model.state.selectedPolicy, 'steamvr');
  assert.equal(model.canApply, false);
  api.pending = deferred();
  choose(model, 'monaka');
  const retry = model.apply();
  await model.cancel();
  api.pending.resolve(configuration('monaka'));
  await retry;
  assert.equal(model.state.configuration?.policy, 'monaka');
  assert.match(model.state.message, /Saved monaka/);
  assert.equal(model.state.busy, false);
  model.selectPolicy('both');
  await model.cancel();
  assert.equal(model.state.selectedPolicy, 'monaka');
  assert.equal(model.state.confirmed, false);
});

test('close/dispose cancels owned save and drops all late notifications', async () => {
  const api = new Api(),
    model = new BridgeController(api);
  await model.load();
  choose(model, 'disabled');
  const pending = model.apply();
  let notifications = 0;
  model.subscribe(() => ++notifications);
  model.dispose();
  assert.equal(api.cancelled.at(-1), api.lastId);
  api.pending.resolve(configuration('disabled'));
  await pending;
  assert.equal(notifications, 0);
  assert.equal(model.state.configuration?.policy, 'steamvr');
  assert.equal(model.canApply, false);
  await model.load();
  await model.poll();
  assert.equal(api.loads, 1);
  assert.equal(api.reads, 0);
});

test('load failure/cancellation/retry and missing configuration/validator do not allow save', async () => {
  const api = new Api(),
    model = new BridgeController(api);
  await model.load();
  api.pendingLoad = deferred();
  const reading = model.load();
  await model.cancel();
  api.pendingLoad.reject(new Error('read aborted'));
  await reading;
  assert.equal(model.state.configuration?.policy, 'steamvr');
  assert.match(model.state.message, /Load cancelled/);
  api.pendingLoad = null;
  api.loadError = new Error('corrupt settings');
  await model.load();
  assert.equal(model.state.configuration, null);
  assert.equal(model.canApply, false);
  api.loadError = null;
  api.loaded = {
    ...configuration('both'),
    locations: { configPath: 'fixture/bridge.json', validatorPath: null },
  };
  await model.load();
  choose(model, 'monaka');
  assert.equal(model.canApply, false);
  api.loaded = configuration('both');
  await model.load();
  choose(model, 'monaka');
  assert.equal(model.canApply, true);
  api.loaded = { ...configuration(), token: null, policy: null };
  await model.load();
  choose(model, 'both');
  assert.equal(model.canApply, false);
});

test('native file-dialog cancellation preserves edits and failure retains the previous source', async () => {
  const api = new Api(),
    model = new BridgeController(api);
  await model.load();
  choose(model, 'both');
  await model.choose('config');
  assert.equal(model.state.selectedPolicy, 'both');
  assert.equal(model.state.confirmed, true);
  api.chooseError = new Error('preference save failed');
  await model.choose('validator');
  assert.equal(model.state.configuration?.policy, 'steamvr');
  assert.match(model.state.message, /preference save failed/);
  api.chooseError = null;
  api.chooseResult = configuration('disabled');
  await model.choose('config');
  assert.equal(model.state.selectedPolicy, 'disabled');
  assert.equal(model.state.confirmed, false);
});

test('unconfirmed cancellation does not imply saved state or release the busy guard', async () => {
  const api = new Api(),
    model = new BridgeController(api);
  await model.load();
  choose(model, 'both');
  const saving = model.apply();
  api.cancelError = new Error('host disconnected');
  await model.cancel();
  assert.equal(model.state.busy, true);
  assert.match(model.state.message, /Cancellation is unconfirmed/);
  api.pending.resolve(configuration('both'));
  await saving;
  assert.match(model.state.message, /Saved both/);
});

test('polling single-flight, failed read, reconnect, stale read and late close are explicit', async () => {
  let now = 1100;
  const api = new Api(),
    model = new BridgeController(api, () => now);
  await model.load();
  await model.poll();
  assert.equal(model.state.diagnostics.current, true);
  assert.equal(model.state.diagnostics.observedPolicy, 'monaka');
  const key = model.state.diagnostics.devices[0].key;
  model.selectDevice(key);
  now = 1450;
  model.tick();
  assert.equal(model.state.selectedDevice, key);
  assert.equal(model.state.diagnostics.devices[0].freshness, 'stale');
  api.result = {
    configPath: 'fixture/bridge.json',
    value: null,
    error: 'disconnected',
  };
  await model.poll();
  assert.equal(model.state.diagnostics.current, false);
  assert.equal(model.state.diagnostics.devices[0].freshness, 'historical');
  now = 1600;
  api.result = health(1600);
  await model.poll();
  assert.equal(model.state.diagnostics.current, true);
  assert.equal(model.state.selectedDevice, key);
  api.result = health(1500);
  await model.poll();
  assert.equal(model.state.diagnostics.current, false);
  assert.match(model.state.diagnostics.message, /Older/);
  api.pendingHealth = deferred();
  const before = api.reads,
    pending = model.poll();
  await model.poll();
  assert.equal(api.reads, before + 1);
  let notifications = 0;
  model.subscribe(() => ++notifications);
  model.dispose();
  api.pendingHealth.resolve(health(1600));
  await pending;
  assert.equal(notifications, 0);
});

test('health presenter separates liveness, freshness, components, policy and revision', () => {
  const view = new BridgeHealthPresentation(),
    config = configuration();
  view.observe(health(), config, 1100);
  const current = view.display(config, 1100);
  assert.equal(current.backendStarted, 'unknown');
  assert.equal(current.publisherRunning, 'unknown');
  assert.equal(current.current, true);
  assert.equal(current.observedPolicy, 'monaka');
  assert.equal(current.revision, 'match');
  assert.match(current.flow, /1 \/ 1/);
  assert.equal(current.devices[0].positionValid, 'valid');
  assert.equal(current.devices[0].orientationValid, 'invalid');
  assert.equal(current.devices[0].collision, 'no');
  assert.match(current.message, /delivery is unconfirmed/);
  assert.equal(view.display(config, 1450).devices[0].freshness, 'stale');
  assert.equal(view.display(config, 4000).observedPolicy, null);
  assert.equal(view.display(config, 999).current, false);
  view.observe(health(1100, { mapping_revision: 3 }), config, 1100);
  assert.equal(view.display(config, 1100).revision, 'different');
  view.observe(
    health(1200, { mapping_revision: 'invalid', policy: 'unsupported' }),
    config,
    1200
  );
  assert.equal(view.display(config, 1200).revision, 'unknown');
  assert.equal(view.display(config, 1200).observedPolicy, null);
});

test('wrong config/publisher, old/future/missing timestamps and malformed devices fail closed', () => {
  const cases = [
    health(1000, { publisher_id: 'other' }),
    { ...health(), configPath: 'other.json' },
    health(2000),
    health(1000, { snapshot_unix_ms: undefined }),
    health(1000, { snapshot_unix_ms: NaN }),
    health(1000, { devices: [null] }),
  ];
  for (const row of cases) {
    const view = new BridgeHealthPresentation();
    view.observe(row, configuration(), 1100);
    const result = view.display(configuration(), 1100);
    assert.equal(result.current, false);
    assert.equal(result.observedPolicy, null);
    assert.equal(result.flow, 'unknown');
  }
  const view = new BridgeHealthPresentation();
  view.observe(health(1100), configuration(), 1100);
  view.observe(health(1000), configuration(), 1100);
  assert.equal(view.display(configuration(), 1100).current, false);
});

test('device identity survives reorder, separates source/publisher, clears disappearance and selection changes', async () => {
  let now = 1000;
  const api = new Api(),
    model = new BridgeController(api, () => now);
  await model.load();
  const a = health().value!.devices as Record<string, unknown>[];
  const b = { ...a[0], source: 'source-b' };
  api.result = health(1000, { devices: [a[0], b] });
  await model.poll();
  const rows = model.state.diagnostics.devices;
  assert.notEqual(rows[0].key, rows[1].key);
  model.selectDevice(rows[0].key);
  now = 1100;
  api.result = health(1100, { devices: [b, a[0]] });
  await model.poll();
  assert.equal(model.state.selectedDevice, rows[0].key);
  now = 1200;
  api.result = health(1200, { devices: [b] });
  await model.poll();
  assert.equal(model.state.selectedDevice, null);
  api.chooseResult = {
    ...configuration(),
    publisher: 'different-publisher',
    locations: { configPath: 'different.json', validatorPath: null },
  };
  await model.choose('config');
  assert.deepEqual(model.state.diagnostics.devices, []);
});

test('unknown flags and malformed ages never manufacture valid components or fresh flow', () => {
  for (const age of [null, '1', -1, Infinity, NaN]) {
    const view = new BridgeHealthPresentation();
    view.observe(
      health(1000, {
        devices: [{ source: 's', device: 'd', fresh: true, pose_age_ms: age }],
      }),
      configuration(),
      1000
    );
    const row = view.display(configuration(), 1000).devices[0];
    assert.notEqual(row.freshness, 'fresh');
    assert.equal(row.positionValid, 'unknown');
    assert.equal(row.orientationValid, 'unknown');
    assert.equal(row.collision, 'unknown');
  }
  const view = new BridgeHealthPresentation();
  view.observe(health(1000, { pose_timeout_ms: 'invalid' }), configuration(), 1000);
  assert.notEqual(view.display(configuration(), 1000).devices[0].freshness, 'fresh');
});

test('tray entry is a single navigation action and has no output writer', () => {
  let shown = 0,
    navigated = 0;
  const entry = bridgeTrayEntry(
    () => ++shown,
    () => ++navigated
  );
  assert.equal(entry.label, 'Monaka Bridge…');
  assert.equal(typeof entry.click, 'function');
  (entry.click as () => void)();
  assert.equal(shown, 1);
  assert.equal(navigated, 1);
  assert.equal(entry.submenu, undefined);
});
