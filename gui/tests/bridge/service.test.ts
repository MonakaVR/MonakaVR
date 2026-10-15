import assert from 'node:assert/strict';
import { test, type TestContext } from 'node:test';
import { mkdtemp, readFile, readdir, rename, rm, writeFile } from 'node:fs/promises';
import { basename, dirname, join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import {
  MonakaBridgeService,
  validateBridgeCandidate,
} from '../../electron/main/monaka-bridge-service.js';
import type {
  BridgePreferences,
  BridgeValidator,
} from '../../electron/main/monaka-bridge-service.js';
import type { BridgeLocations } from '../../electron/bridge-types.js';

const template = {
  namespace: 'monaka.bridge',
  version: 2,
  bridge_id: 'desktop-software-test',
  mapping_revision: 1,
  policy: 'steamvr',
  pose_timeout_ms: 500,
  profiles: {
    fixture: {
      convention: 'native-test',
      position_axes: [1, 2, 3],
      quaternion_axes: [1, 2, 3, 4],
      approved: true,
      evidence: 'synthetic test only',
      angular_space_verified: false,
    },
  },
  mappings: [],
};
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { resolve, reject, promise };
};
class Preferences implements BridgePreferences {
  readGate: ReturnType<typeof deferred<BridgeLocations>> | null = null;
  fail = false;
  constructor(public value: BridgeLocations) {}
  async read() {
    return this.readGate ? this.readGate.promise : { ...this.value };
  }
  async write(value: BridgeLocations) {
    if (this.fail) throw new Error('injected preferences failure');
    this.value = { ...value };
  }
}
async function fixture(t: TestContext) {
  const parent = resolve(tmpdir()),
    root = await mkdtemp(join(parent, 'monaka-desktop-test-'));
  t.after(async () => {
    if (dirname(root) !== parent || !basename(root).startsWith('monaka-desktop-test-'))
      throw new Error('Unsafe test cleanup path');
    await rm(root, { recursive: true, force: true });
  });
  const config = join(root, 'bridge.json');
  await writeFile(config, JSON.stringify(template));
  const validator = process.env.MONAKA_BRIDGE_TEST_VALIDATOR;
  assert.ok(
    validator,
    'Provide the pinned baseline validator path; no synthetic native PASS is substituted.'
  );
  return {
    root,
    config,
    validator,
    preferences: new Preferences({ configPath: config, validatorPath: validator }),
  };
}

test('native validator: save/backup/revision/restore/no-op and untouched fields', async (t) => {
  const f = await fixture(t),
    service = new MonakaBridgeService(f.preferences);
  const original = await readFile(f.config),
    loaded = await service.load();
  const saved = await service.apply(loaded.token!, 'monaka', 'save-1');
  assert.equal(saved.policy, 'monaka');
  assert.equal(saved.revision, 2);
  const disk = JSON.parse(await readFile(f.config, 'utf8'));
  assert.deepEqual({ ...disk, policy: 'steamvr', mapping_revision: 1 }, template);
  const backups = (await readdir(f.root)).filter((name) => name.includes('.backup-'));
  assert.equal(backups.length, 1);
  assert.deepEqual(await readFile(join(f.root, backups[0])), original);
  const bytes = await readFile(f.config);
  await service.apply(saved.token!, 'monaka', 'no-op');
  assert.deepEqual(await readFile(f.config), bytes);
  const restarted = new MonakaBridgeService(f.preferences);
  assert.equal((await restarted.load()).policy, 'monaka');
  assert.equal(
    (await readdir(f.root)).some(
      (name) => name.includes('desktop-candidate') || name.endsWith('.lock')
    ),
    false
  );
});

test('same revision external bytes and changes during validation are preserved', async (t) => {
  const f = await fixture(t),
    service = new MonakaBridgeService(f.preferences);
  const loaded = await service.load();
  await writeFile(f.config, JSON.stringify(template) + ' ');
  const external = await readFile(f.config);
  await assert.rejects(service.apply(loaded.token!, 'both', 'conflict'), /changed/);
  assert.deepEqual(await readFile(f.config), external);
  const racing = new MonakaBridgeService(f.preferences, async () => {
    await writeFile(f.config, JSON.stringify(template) + '  ');
  });
  const current = await racing.load();
  await assert.rejects(racing.apply(current.token!, 'both', 'racing'), /changed/);
  assert.equal(await readFile(f.config, 'utf8'), JSON.stringify(template) + '  ');
});

test('native failure, missing executable, revision exhaustion and retry leave saved data intact', async (t) => {
  const f = await fixture(t);
  await writeFile(f.config, JSON.stringify({ ...template, pose_timeout_ms: 0 }));
  const service = new MonakaBridgeService(f.preferences),
    loaded = await service.load(),
    original = await readFile(f.config);
  await assert.rejects(
    service.apply(loaded.token!, 'both', 'invalid'),
    /validation failed/
  );
  assert.deepEqual(await readFile(f.config), original);
  await writeFile(
    f.config,
    JSON.stringify({ ...template, mapping_revision: 0xffffffff })
  );
  const exhausted = await service.load();
  await assert.rejects(service.apply(exhausted.token!, 'both', 'max'), /exhausted/);
  f.preferences.value.validatorPath = join(f.root, 'missing.exe');
  await writeFile(f.config, JSON.stringify(template));
  const missing = await service.load();
  await assert.rejects(service.apply(missing.token!, 'both', 'missing'), /ENOENT/);
  assert.equal(JSON.parse(await readFile(f.config, 'utf8')).policy, 'steamvr');
  f.preferences.value.validatorPath = f.validator;
  const retry = await service.load();
  assert.equal(
    (await service.apply(retry.token!, 'disabled', 'retry')).policy,
    'disabled'
  );
});

test('duplicate apply, wrong cancellation ID, cancel, window close and retry', async (t) => {
  const f = await fixture(t),
    entered = deferred<AbortSignal>(),
    release = deferred<void>();
  let slow = true;
  const validator: BridgeValidator = async (exe, candidate, signal) => {
    if (slow) {
      entered.resolve(signal);
      await release.promise;
      signal.throwIfAborted();
    } else await validateBridgeCandidate(exe, candidate, signal);
  };
  const service = new MonakaBridgeService(f.preferences, validator),
    loaded = await service.load();
  const pending = service.apply(loaded.token!, 'both', 'owned');
  const signal = await entered.promise;
  await assert.rejects(service.apply(loaded.token!, 'monaka', 'duplicate'), /progress/);
  await assert.rejects(service.load(), /progress/);
  await assert.rejects(service.configure('config', f.config), /finish/);
  service.cancel('different');
  assert.equal(signal.aborted, false);
  service.cancel('owned');
  assert.equal(signal.aborted, true);
  release.resolve();
  await assert.rejects(pending);
  assert.equal(JSON.parse(await readFile(f.config, 'utf8')).policy, 'steamvr');
  slow = false;
  assert.equal(
    (await service.apply(loaded.token!, 'monaka', 'retry')).policy,
    'monaka'
  );
  const closingEntered = deferred<void>(),
    closingRelease = deferred<void>();
  const closing = new MonakaBridgeService(
    f.preferences,
    async (_exe, _candidate, abort) => {
      closingEntered.resolve();
      await closingRelease.promise;
      abort.throwIfAborted();
    }
  );
  const before = await closing.load(),
    saving = closing.apply(before.token!, 'both', 'close');
  await closingEntered.promise;
  closing.dispose();
  closingRelease.resolve();
  await assert.rejects(saving);
  assert.equal(JSON.parse(await readFile(f.config, 'utf8')).policy, 'monaka');
  await assert.rejects(closing.load(), /closed/);
});

test('exclusive Desktop writers and stale locks fail closed without deleting foreign locks', async (t) => {
  const f = await fixture(t),
    entered = deferred<void>(),
    release = deferred<void>();
  const first = new MonakaBridgeService(f.preferences, async () => {
    entered.resolve();
    await release.promise;
  });
  const second = new MonakaBridgeService(f.preferences),
    a = await first.load(),
    b = await second.load();
  const pending = first.apply(a.token!, 'both', 'first');
  await entered.promise;
  await assert.rejects(second.apply(b.token!, 'monaka', 'second'), /EEXIST/);
  release.resolve();
  await pending;
  await writeFile(f.config + '.desktop-policy.lock', 'prior instance');
  const reload = await second.load();
  await assert.rejects(second.apply(reload.token!, 'disabled', 'stale'), /EEXIST/);
  assert.equal(
    await readFile(f.config + '.desktop-policy.lock', 'utf8'),
    'prior instance'
  );
});

test('replacement failure restores no external data, retry works, post-commit cancel is reported saved', async (t) => {
  const f = await fixture(t);
  let fail = true;
  let service: MonakaBridgeService;
  service = new MonakaBridgeService(
    f.preferences,
    validateBridgeCandidate,
    async (from, to) => {
      if (fail) throw new Error('replace failed');
      await rename(from, to);
      service.cancel('after-commit');
    }
  );
  const loaded = await service.load();
  await assert.rejects(
    service.apply(loaded.token!, 'both', 'replace'),
    /replace failed/
  );
  assert.equal(JSON.parse(await readFile(f.config, 'utf8')).policy, 'steamvr');
  fail = false;
  assert.equal(
    (await service.apply(loaded.token!, 'both', 'after-commit')).policy,
    'both'
  );
  assert.equal(JSON.parse(await readFile(f.config, 'utf8')).policy, 'both');
});

test('configuration selection persistence/failure, independent approval flags, unknown profile and stale token', async (t) => {
  const f = await fixture(t),
    service = new MonakaBridgeService(f.preferences);
  const old = await service.load();
  const mapping = {
    source_id: 's',
    device_id: 'd',
    tracker_id: 't',
    profile: 'fixture',
    input_space: 'space',
    input_revision: 3,
    space_approved: false,
  };
  const alternate = join(f.root, 'alternate.json');
  await writeFile(alternate, JSON.stringify({ ...template, mappings: [mapping] }));
  const selected = await service.configure('config', alternate);
  assert.equal(f.preferences.value.configPath, alternate);
  assert.equal(selected.mappings[0].approved, false);
  assert.equal(selected.mappings[0].profileApproved, true);
  await assert.rejects(service.apply(old.token!, 'both', 'stale'), /Reload/);
  f.preferences.fail = true;
  await assert.rejects(service.configure('config', f.config), /preferences failure/);
  assert.equal(f.preferences.value.configPath, alternate);
  f.preferences.fail = false;
  await assert.rejects(
    service.configure('validator', f.config),
    /monaka_bridge_config/
  );
  await writeFile(
    alternate,
    JSON.stringify({ ...template, mappings: [{ ...mapping, profile: 'missing' }] })
  );
  assert.equal((await service.load()).mappings[0].profileApproved, null);
  await assert.rejects(
    service.apply((await service.load()).token!, 'bogus' as never, 'bad'),
    /Reload/
  );
});

test('bounded/corrupt/unconfigured files, health missing/malformed, actual snapshot age, no writes', async (t) => {
  const f = await fixture(t),
    service = new MonakaBridgeService(f.preferences);
  f.preferences.value.configPath = null;
  assert.equal((await service.load()).token, null);
  assert.match((await service.health()).error!, /Select/);
  f.preferences.value.configPath = f.config;
  const original = await readFile(f.config);
  assert.ok((await service.health()).error);
  await writeFile(
    f.config + '.status.json',
    '{"snapshot_unix_ms":0,"publisher_id":"desktop-software-test","devices":[]}'
  );
  assert.equal((await service.health()).value?.snapshot_unix_ms, 0);
  await writeFile(f.config + '.status.json', 'malformed');
  assert.ok((await service.health()).error);
  assert.deepEqual(await readFile(f.config), original);
  await writeFile(f.config, Buffer.alloc(2 * 1024 * 1024 + 1));
  await assert.rejects(service.load(), /size limit/);
  await writeFile(f.config, Buffer.from([0xff, 0xfe]));
  await assert.rejects(service.load());
  await writeFile(f.config, '{"namespace":"other"}');
  await assert.rejects(service.load(), /version 2/);
});

test('load cancellation is bounded, preserves prior selection and accepts a fresh read after cancel', async (t) => {
  const f = await fixture(t),
    service = new MonakaBridgeService(f.preferences);
  await service.load();
  const gate = deferred<BridgeLocations>();
  f.preferences.readGate = gate;
  const loading = service.load('cancel-read');
  await assert.rejects(service.load('duplicate-read'), /progress/);
  service.cancel('cancel-read');
  const next = service.load('new-read');
  f.preferences.readGate = null;
  gate.resolve(f.preferences.value);
  await assert.rejects(loading);
  assert.equal((await next).policy, 'steamvr');
});

const runtimeContractConfiguration = () => ({
  ...template,
  mappings: [
    {
      source_id: 'source-a',
      device_id: 'device-a',
      tracker_id: 'tracker-a',
      profile: 'fixture',
      input_space: 'fixture-map',
      input_revision: 1,
      world_space: 'fixture-world',
      world_revision: 0,
      space_approved: true,
      world: { rotation: [0, 0, 0, 1], translation: [0, 0, 0] },
      mount: { rotation: [0, 0, 0, 1], translation: [0, 0, 0] },
      runtime_contract: {
        id: 'desktop-fixture-contract',
        provenance: 'Explicit software-only config fixture',
        input_declaration: ['position', 'orientation', 'linear_velocity'],
        output_declaration: [
          'position',
          'orientation',
          'linear_velocity',
          'angular_velocity',
        ],
        input_policy: 'STRICT',
        output_policy: 'LEGACY_DYNAMIC',
      },
    },
  ],
});

test('P2R extension: old validator cannot silently approve runtime_contract; original bytes preserved', async (t) => {
  const f = await fixture(t);
  await writeFile(f.config, JSON.stringify(runtimeContractConfiguration()));
  const original = await readFile(f.config),
    service = new MonakaBridgeService(f.preferences),
    loaded = await service.load();
  await assert.rejects(
    service.apply(loaded.token!, 'both', 'old-validator'),
    /ignores P2R runtime_contract/
  );
  assert.deepEqual(await readFile(f.config), original);
  assert.equal(
    (await readdir(f.root)).some((name) => name.includes('desktop-candidate')),
    false
  );
});

test('P2R matching native validator preserves complete nested contract and changes only policy/revision', async (t) => {
  const f = await fixture(t),
    p2rValidator = process.env.MONAKA_BRIDGE_TEST_P2R_VALIDATOR;
  assert.ok(
    p2rValidator,
    'Provide the independently built 42ec6c8 validator. This test is not skipped.'
  );
  f.preferences.value.validatorPath = p2rValidator;
  const original = runtimeContractConfiguration();
  await writeFile(f.config, JSON.stringify(original));
  const service = new MonakaBridgeService(f.preferences),
    loaded = await service.load(),
    saved = await service.apply(loaded.token!, 'monaka', 'p2r');
  assert.equal(saved.policy, 'monaka');
  const disk = JSON.parse(await readFile(f.config, 'utf8'));
  assert.deepEqual(
    { ...disk, policy: original.policy, mapping_revision: original.mapping_revision },
    original
  );
  assert.deepEqual(
    disk.mappings[0].runtime_contract,
    original.mappings[0].runtime_contract
  );
  const invalid = runtimeContractConfiguration();
  invalid.mappings[0].runtime_contract.id = '';
  await writeFile(f.config, JSON.stringify(invalid));
  const invalidLoaded = await service.load();
  await assert.rejects(
    service.apply(invalidLoaded.token!, 'disabled', 'invalid-p2r'),
    /validation failed/
  );
  assert.deepEqual(JSON.parse(await readFile(f.config, 'utf8')), invalid);
});
