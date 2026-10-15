import assert from 'node:assert/strict';
import { test } from 'node:test';
import { bridgeControlHandler } from '../../electron/main/monaka-bridge-ipc.js';
import type { MonakaBridgeService } from '../../electron/main/monaka-bridge-service.js';
import type { BridgeDesktopRequest } from '../../electron/bridge-types.js';

test('Desktop IPC restricts owner, shape, identifiers and existing policies', async () => {
  const calls: unknown[] = [];
  const service = {
    busy: false,
    load: async (id: string) => {
      calls.push(['load', id]);
    },
    apply: async (...args: unknown[]) => {
      calls.push(['apply', ...args]);
    },
    health: async () => {
      calls.push('health');
    },
    cancel: (id: string) => {
      calls.push(['cancel', id]);
    },
  } as unknown as MonakaBridgeService;
  let owner: number | null = 7;
  const handle = bridgeControlHandler(
    () => owner,
    () => service,
    async () => null
  );
  await assert.rejects(handle(8, { operation: 'health' }), /Desktop window/);
  owner = null;
  await assert.rejects(handle(7, { operation: 'health' }), /Desktop window/);
  owner = 7;
  for (const request of [
    null,
    {},
    { operation: 'unknown' },
    { operation: 'load', requestId: '' },
    { operation: 'load', requestId: 'x'.repeat(129) },
    { operation: 'apply', token: 't', requestId: 'a', policy: 'invented' },
    { operation: 'choose', kind: 'anything' },
  ]) {
    await assert.rejects(handle(7, request as BridgeDesktopRequest));
  }
  assert.equal(calls.length, 0);
  await handle(7, { operation: 'load', requestId: 'l' });
  await handle(7, { operation: 'apply', token: 't', requestId: 'a', policy: 'both' });
  await handle(7, { operation: 'cancel', requestId: 'a' });
  await handle(7, { operation: 'health' });
  assert.deepEqual(calls, [
    ['load', 'l'],
    ['apply', 't', 'both', 'a'],
    ['cancel', 'a'],
    'health',
  ]);
});

test('Desktop IPC serializes file dialog, retains cancellation and permits retry', async () => {
  let finish!: (path: string | null) => void;
  let fail = false;
  let selections = 0;
  let configured: unknown[] = [];
  const service = {
    busy: false,
    health: async () => 'diagnostics',
    cancel: () => {},
    configure: async (...args: unknown[]) => {
      configured = args;
      return 'selected';
    },
  } as unknown as MonakaBridgeService;
  const handle = bridgeControlHandler(
    () => 7,
    () => service,
    async () => {
      ++selections;
      if (fail) throw new Error('native dialog failure');
      return new Promise<string | null>((resolve) => {
        finish = resolve;
      });
    }
  );
  const first = handle(7, { operation: 'choose', kind: 'config' });
  await assert.rejects(handle(7, { operation: 'choose', kind: 'validator' }), /Finish/);
  await assert.rejects(handle(7, { operation: 'load', requestId: 'l' }), /Finish/);
  assert.equal(await handle(7, { operation: 'health' }), 'diagnostics');
  finish(null);
  assert.equal(await first, null);
  assert.deepEqual(configured, []);
  fail = true;
  await assert.rejects(
    handle(7, { operation: 'choose', kind: 'config' }),
    /dialog failure/
  );
  fail = false;
  const retry = handle(7, { operation: 'choose', kind: 'validator' });
  finish('selected-validator');
  assert.equal(await retry, 'selected');
  assert.deepEqual(configured, ['validator', 'selected-validator']);
  assert.equal(selections, 3);
});
