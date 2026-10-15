import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawn } from 'node:child_process';
const [repo, cli, fake, out] = process.argv.slice(2);
const { MonakaBridgeService } = await import(
  pathToFileURL(
    resolve(
      repo,
      'gui/build/bridge-tests/electron/main/monaka-bridge-service.js'
    )
  )
);
const { commitBridgeCandidate } = await import(
  pathToFileURL(
    resolve(
      repo,
      'gui/build/bridge-tests/electron/main/bridge-config-transaction.js'
    )
  )
);
await mkdir(out, { recursive: true });
const rows = [];
const base = {
  namespace: 'monaka.bridge',
  version: 2,
  bridge_id: 'gui-fault-fixture',
  mapping_revision: 10,
  policy: 'steamvr',
  profiles: {},
  mappings: [],
};
for (const mode of ['cancel', 'timeout', 'postcommit-cancel']) {
  const target = join(out, mode + '.json'),
    candidate = join(out, mode + '-candidate.json');
  const before = Buffer.from(JSON.stringify(base));
  await writeFile(target, before);
  await writeFile(
    candidate,
    JSON.stringify({ ...base, policy: 'both', mapping_revision: 11 })
  );
  const controller = new AbortController(),
    started = Date.now();
  const pending = commitBridgeCandidate(
    fake,
    target,
    before,
    candidate,
    controller.signal
  );
  pending.catch(() => {});
  if (mode !== 'timeout') {
    for (let i = 0; i < 200; i++) {
      try {
        await readFile(target + '.sleeping-commit');
        break;
      } catch {
        await new Promise((r) => setTimeout(r, 10));
      }
    }
    controller.abort();
  }
  await assert.rejects(
    pending,
    mode === 'postcommit-cancel'
      ? /outcome unknown/
      : mode === 'cancel'
        ? /interrupted/
        : /timed out/
  );
  if (mode === 'postcommit-cancel') {
    assert.deepEqual(await readFile(target), await readFile(candidate));
    await assert.rejects(
      commitBridgeCandidate(
        cli,
        target,
        before,
        candidate,
        new AbortController().signal
      ),
      /changed/
    );
  } else {
    assert.deepEqual(await readFile(target), before);
    await commitBridgeCandidate(
      cli,
      target,
      before,
      candidate,
      new AbortController().signal
    );
    assert.equal(JSON.parse(await readFile(target)).policy, 'both');
  }
  rows.push({
    mode,
    result: 'PASS',
    elapsed_ms: Date.now() - started,
    target_unchanged_before_retry: mode !== 'postcommit-cancel',
    interrupted_outcome:
      mode === 'postcommit-cancel'
        ? 'UNKNOWN, target matches candidate'
        : 'not observed committed',
    retry_using_real_cli:
      mode === 'postcommit-cancel' ? 'STALE RETRY REJECTED' : 'PASS',
  });
}
// Hard termination before native commit: actual service has no persistent wx lock.
const cfg = join(out, 'gui-kill.json');
await writeFile(cfg, JSON.stringify(base));
const marker = join(out, 'gui-holding.json');
const helper = join(out, 'hold.mjs');
const serviceUrl = pathToFileURL(
  resolve(repo, 'gui/build/bridge-tests/electron/main/monaka-bridge-service.js')
).href;
await writeFile(
  helper,
  `import {writeFile} from 'node:fs/promises';import {MonakaBridgeService} from ${JSON.stringify(serviceUrl)};const s=new MonakaBridgeService({read:async()=>({configPath:${JSON.stringify(cfg)},validatorPath:${JSON.stringify(cli)}}),write:async()=>{}},async()=>{await writeFile(${JSON.stringify(marker)},JSON.stringify({pid:process.pid}));await new Promise(()=>{});});setInterval(()=>{},1000);const v=await s.load();await s.apply(v.token,'both','owned-kill');`
);
const child = spawn(process.execPath, [helper], {
  windowsHide: true,
  stdio: 'ignore',
});
try {
  for (let i = 0; i < 200; i++) {
    try {
      const m = JSON.parse(await readFile(marker));
      assert.equal(m.pid, child.pid);
      break;
    } catch {
      await new Promise((r) => setTimeout(r, 10));
    }
  }
  assert.ok(await readFile(marker));
} finally {
  child.kill();
  await new Promise((r) => child.once('exit', r));
}
const s = new MonakaBridgeService({
  read: async () => ({ configPath: cfg, validatorPath: cli }),
  write: async () => {},
});
const loaded = await s.load();
assert.equal((await s.apply(loaded.token, 'both', 'post-kill')).policy, 'both');
s.dispose();
rows.push({
  mode: 'owned-GUI-hard-exit',
  result: 'PASS',
  new_save_succeeds: true,
});
await writeFile(
  join(out, 'summary.json'),
  JSON.stringify({ result: 'PASS', cases: rows.length, rows }, null, 2)
);
console.log(
  'PASS GUI cancellation/real15s timeout/owned hard-exit recovery4 cases'
);
