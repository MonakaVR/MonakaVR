import assert from 'node:assert/strict';
import { test } from 'node:test';
import path from 'node:path';
import { desktopInstance } from '../../electron/main/desktop-instance.js';

test('ordinary desktop defaults are preserved', () => {
  assert.deepEqual(desktopInstance({}), {
    profile: undefined,
    networkOnly: false,
    port: 21110,
  });
});

test('network-only desktop requires an explicit independent profile', () => {
  const profile = path.resolve('owned-profile');
  assert.deepEqual(
    desktopInstance({ profile, networkOnly: true, serverPort: '49153' }),
    { profile, networkOnly: true, port: 49153 }
  );
  for (const options of [
    { networkOnly: true },
    { profile: 'relative' },
    { profile, networkOnly: true, steam: true },
    { profile, networkOnly: true, install: true },
    { serverPort: '0' },
    { serverPort: '65536' },
    { serverPort: '21110suffix' },
  ])
    assert.throws(() => desktopInstance(options));
});
