import type * as actualCli from '../../electron/main/cli.js';
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

test('actual CLI preserves the first flag in packaged and development Electron', async () => {
  const { execFileSync } = await import('node:child_process');
  const cli = new URL('../../electron/main/cli.js', import.meta.url).href;
  const profile = path.resolve('owned-profile');
  for (const development of [false, true]) {
    const argv = [
      'electron.exe',
      ...(development ? ['app'] : []),
      '--profile',
      profile,
      '--network-only',
      '--server-port',
      '49153',
    ];
    const script = `process.defaultApp=${development}; process.argv=${JSON.stringify(argv)}; const { options }=await import(${JSON.stringify(cli)}); console.log(JSON.stringify(options));`;
    const options: typeof actualCli.options = JSON.parse(
      execFileSync(process.execPath, ['--input-type=module', '-e', script], {
        encoding: 'utf8',
      })
    );
    assert.equal(options.profile, profile);
    assert.equal(options.networkOnly, true);
    assert.equal(options.serverPort, '49153');
  }
});
