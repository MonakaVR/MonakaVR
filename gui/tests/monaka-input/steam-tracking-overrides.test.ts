import { strict as assert } from 'node:assert';
import { chmod, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { test as nodeTest, type TestContext } from 'node:test';
import { SteamTrackingOverrides } from '../../electron/main/steam-tracking-overrides.js';
import { overridePaths } from '../../electron/tracking-override-types.js';

const left = overridePaths('left');
const right = overridePaths('right');
const test = process.platform === 'win32' ? nodeTest : nodeTest.skip;
const original = '{\n "steamvr": {"keep": 12345678901234567890},\n "tail": [2, 3]\n}\n';

async function fixture(t: TestContext, contents = original) {
  const directory = await mkdtemp(path.join(tmpdir(), 'monaka-override-fixture-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const file = path.join(directory, 'steamvr.vrsettings');
  await writeFile(file, contents);
  const create = (stopped = async () => {}) =>
    new SteamTrackingOverrides(async () => file, stopped);
  return {
    create,
    directory,
    file,
    journal: file + '.monaka-tracking-overrides.json',
    read: async () => JSON.parse(await readFile(file, 'utf8')),
  };
}

test('hand mappings preserve unrelated settings, retain a backup, and remove independently', async (t) => {
  const f = await fixture(t);
  const service = f.create();
  const starting = await service.snapshot();
  const one = await service.change('apply', 'left', starting.token);
  assert.deepEqual(one.hands, {
    left: { owned: true, conflict: false },
    right: { owned: false, conflict: false },
  });
  assert.deepEqual(await service.change('apply', 'left', one.token), one);
  const two = await service.change('apply', 'right', one.token);
  assert.deepEqual((await f.read()).TrackingOverrides, {
    [left.devicePath]: left.targetPath,
    [right.devicePath]: right.targetPath,
  });
  assert.match(await readFile(f.file, 'utf8'), /"keep": 12345678901234567890/);
  await assert.rejects(service.change('remove', 'left', one.token), /changed/i);
  const afterLeft = await service.change('remove', 'left', two.token);
  await service.change('remove', 'right', afterLeft.token);
  const final = await readFile(f.file, 'utf8');
  assert.match(final, /"steamvr": \{"keep": 12345678901234567890\}/);
  assert.match(final, /"tail": \[2, 3\]/);
  assert.equal(Object.hasOwn(await f.read(), 'TrackingOverrides'), false);
  const backups = (await readdir(f.directory)).filter((name) => name.endsWith('.bak'));
  assert.equal(backups.length, 4);
  assert.ok(
    (
      await Promise.all(
        backups.map((name) => readFile(path.join(f.directory, name), 'utf8'))
      )
    ).includes(original)
  );
});

test('foreign targets, unowned reserved keys, and malformed or duplicate settings fail closed', async (t) => {
  const f = await fixture(
    t,
    JSON.stringify({
      TrackingOverrides: {
        '/devices/other/left': left.targetPath,
        [right.devicePath]: right.targetPath,
        '/devices/other/head': '/user/head',
      },
    })
  );
  const service = f.create();
  const snapshot = await service.snapshot();
  assert.deepEqual(snapshot.hands, {
    left: { owned: false, conflict: true },
    right: { owned: false, conflict: true },
  });
  await assert.rejects(service.change('apply', 'left', snapshot.token), /owns/);
  await assert.rejects(service.change('remove', 'right', snapshot.token), /outside/);
  assert.equal((await f.read()).TrackingOverrides['/devices/other/head'], '/user/head');
  for (const malformed of [
    '{not json}',
    '{"TrackingOverrides":[]}',
    '{"TrackingOverrides":{},"TrackingOverrides":{}}',
    '{"TrackingOverrides":{"/devices/a/s":"/user/head","/devices/a/s":"/user/head"}}',
  ]) {
    await writeFile(f.file, malformed);
    await assert.rejects(service.snapshot());
    assert.equal(await readFile(f.file, 'utf8'), malformed);
  }
});

test('stopped-runtime and external-edit checks guard the final file swap', async (t) => {
  const f = await fixture(t);
  const running = f.create(async () => {
    throw new Error('SteamVR running');
  });
  await assert.rejects(
    running.change('apply', 'left', (await running.snapshot()).token),
    /SteamVR running/
  );
  assert.equal(await readFile(f.file, 'utf8'), original);

  let checks = 0;
  const externalEdit = '{"independent":true}';
  const racing = f.create(async () => {
    if (++checks === 2) await writeFile(f.file, externalEdit);
  });
  await assert.rejects(
    racing.change('apply', 'left', (await racing.snapshot()).token),
    /changed before commit/
  );
  assert.equal(await readFile(f.file, 'utf8'), externalEdit);
  assert.equal((await racing.snapshot()).hands.left.owned, false);
});

test('Windows read-only settings leave exact bytes and no claimed ownership', async (t) => {
  const f = await fixture(t);
  const service = f.create();
  await chmod(f.file, 0o444);
  try {
    await assert.rejects(
      service.change('apply', 'left', (await service.snapshot()).token),
      (error: NodeJS.ErrnoException) =>
        error.code === 'EPERM' || error.code === 'EACCES'
    );
    assert.equal(await readFile(f.file, 'utf8'), original);
    assert.equal((await service.snapshot()).hands.left.owned, false);
  } finally {
    await chmod(f.file, 0o666);
  }
});

test('only one desktop can update the same settings, even with matching tokens', async (t) => {
  const f = await fixture(t);
  let entered!: () => void;
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    entered = resolve;
  });
  const unblock = new Promise<void>((resolve) => {
    release = resolve;
  });
  const first = f.create(async () => {
    entered();
    await unblock;
  });
  const second = f.create();
  const before = await second.snapshot();
  const write = first.change('apply', 'left', before.token);
  try {
    await held;
    await assert.rejects(
      second.change('apply', 'right', before.token),
      /Another MonakaVR desktop/
    );
  } finally {
    release();
  }
  const result = await write;
  assert.equal(
    (await second.change('apply', 'right', result.token)).hands.right.owned,
    true
  );
});

test('interrupted apply/remove reconcile just the managed key after unrelated edits', async (t) => {
  const f = await fixture(t);
  const service = f.create();
  const ownership = { hands: [], createdSection: false };
  const installed = { hands: ['left'], createdSection: true };
  const apply = {
    hand: 'left',
    before: null,
    after: left.targetPath,
    ownership: installed,
  };
  await writeFile(f.journal, JSON.stringify({ version: 1, ownership, pending: apply }));
  assert.equal((await service.snapshot()).hands.left.owned, false); // Interrupted before swapping settings.
  await writeFile(
    f.file,
    JSON.stringify({
      unrelated: 'edited after swap',
      TrackingOverrides: {
        [left.devicePath]: left.targetPath,
        '/devices/other/head': '/user/head',
      },
    })
  );
  const recovered = await service.snapshot();
  assert.equal(recovered.hands.left.owned, true); // Swapped, journal still pending.
  const removed = await service.change('remove', 'left', recovered.token);
  assert.equal(removed.hands.left.owned, false);
  assert.deepEqual(await f.read(), {
    unrelated: 'edited after swap',
    TrackingOverrides: { '/devices/other/head': '/user/head' },
  });

  const remove = { hand: 'left', before: left.targetPath, after: null, ownership };
  await writeFile(
    f.journal,
    JSON.stringify({ version: 1, ownership: installed, pending: remove })
  );
  assert.equal((await service.snapshot()).hands.left.owned, false);
  await writeFile(
    f.file,
    JSON.stringify({ TrackingOverrides: { [left.devicePath]: '/user/head' } })
  );
  assert.deepEqual((await service.snapshot()).hands.left, {
    owned: false,
    conflict: true,
  });
});
