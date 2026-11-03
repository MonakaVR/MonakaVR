import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { test } from 'node:test';
import { ownedChild } from '../../electron/main/owned-child.js';

test(
  'owned child shutdown after natural exit is idempotent',
  { timeout: 10000 },
  async () => {
    const child = spawn(process.execPath, ['-e', 'process.exit(0)']);
    const owned = ownedChild(child);
    await owned.waitForExit();
    owned.close();
    owned.close();
    await owned.waitForExit();
    assert.equal(child.exitCode, 0);
  }
);

test('owned child spawn failure settles shutdown', { timeout: 10000 }, async () => {
  const child = spawn('monaka-nonexistent-owned-test-executable');
  const owned = ownedChild(child);
  owned.close();
  await owned.waitForExit();
  owned.close();
  assert.equal(child.pid, undefined);
});

test(
  'owned child closed stdin does not cause unhandled EPIPE',
  { timeout: 10000 },
  async () => {
    const child = spawn(process.execPath, [
      '-e',
      "require('node:fs').closeSync(0); console.log('ready'); setTimeout(() => process.exit(0), 100)",
    ]);
    const owned = ownedChild(child);
    await once(child.stdout!, 'data');
    owned.close();
    await owned.waitForExit();
    assert.equal(child.exitCode, 0);
  }
);

test('owned child receives graceful exit command', { timeout: 10000 }, async () => {
  const child = spawn(process.execPath, [
    '-e',
    "process.stdin.on('data', x => process.exit(x.toString() === 'exit\\n' ? 0 : 1))",
  ]);
  const owned = ownedChild(child);
  owned.close();
  await owned.waitForExit();
  assert.equal(child.exitCode, 0);
});
