import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MonakaInputSocket } from '../../src/monaka-input/socket.js';

class FakeSocket {
  static OPEN = 1;
  static instances: FakeSocket[] = [];
  readyState = 0;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  sent: Record<string, unknown>[] = [];
  constructor(_url: string) {
    FakeSocket.instances.push(this);
  }
  open() {
    this.readyState = 1;
    this.onopen?.();
  }
  send(text: string) {
    this.sent.push(JSON.parse(text));
  }
  close() {
    this.readyState = 3;
    this.onclose?.();
  }
  reply() {
    const request = this.sent.at(-1)!;
    this.onmessage?.({
      data: JSON.stringify({
        ...request,
        ok: true,
        generation: '0',
        bodies: [],
        candidates: [],
        assignments: [],
      }),
    });
  }
}

test('null or unrelated JSON cannot throw or consume an input request', async () => {
  const original = globalThis.WebSocket;
  globalThis.WebSocket = FakeSocket as unknown as typeof WebSocket;
  const api = new MonakaInputSocket('ws://test.invalid');
  try {
    const pending = api.request({ action: 'snapshot' });
    const socket = FakeSocket.instances.at(-1)!;
    socket.open();
    await Promise.resolve();
    let failure: unknown;
    for (const data of ['null', 'false', '[]', '"text"', '{}']) {
      try {
        socket.onmessage?.({ data });
      } catch (error) {
        failure = error;
      }
    }
    socket.reply();
    assert.equal((await pending).generation, '0');
    assert.equal(failure, undefined);
  } finally {
    api.close();
    globalThis.WebSocket = original;
  }
});

test('late close from an old transport cannot reject a replacement request', async () => {
  const original = globalThis.WebSocket;
  globalThis.WebSocket = FakeSocket as unknown as typeof WebSocket;
  const api = new MonakaInputSocket('ws://test.invalid');
  try {
    const first = api.request({ action: 'snapshot' });
    const old = FakeSocket.instances.at(-1)!;
    old.open();
    await Promise.resolve();
    old.reply();
    await first;
    old.readyState = 3;
    const next = api.request({ action: 'snapshot' });
    const result = next.then(
      (value) => ({ value }),
      (error) => ({ error })
    );
    const current = FakeSocket.instances.at(-1)!;
    current.open();
    await Promise.resolve();
    old.onclose?.();
    current.reply();
    const resolved = await result;
    assert.ok('value' in resolved);
    assert.equal(resolved.value.generation, '0');
  } finally {
    api.close();
    globalThis.WebSocket = original;
  }
});
