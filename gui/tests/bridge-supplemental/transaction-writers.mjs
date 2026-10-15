import assert from 'node:assert/strict';
import {
  readFile,
  writeFile,
  mkdir,
  copyFile,
  readdir,
} from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';
const [repo, cli, harness, output] = process.argv.slice(2);
const { MonakaBridgeService, validateBridgeCandidate } = await import(
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
await mkdir(output, { recursive: true });
const base = {
  namespace: 'monaka.bridge',
  version: 2,
  bridge_id: 'cooperative-writer-fixture',
  mapping_revision: 10,
  policy: 'steamvr',
  profiles: {},
  mappings: [],
  unknown_extension: { preserve: ['all', 'writers'] },
};
const rows = [];
for (const writer of ['cli', 'tray', 'wpf'])
  for (const phase of [
    'before-apply',
    'during-validation',
    'before-native-cas',
    'after-commit',
  ]) {
    const root = join(output, writer + '-' + phase);
    await mkdir(join(root, 'config'), { recursive: true });
    await mkdir(join(root, 'build/Release'), { recursive: true });
    await copyFile(cli, join(root, 'build/Release/monaka_bridge_config.exe'));
    const config = join(root, 'config/bridge.json');
    await writeFile(config, JSON.stringify(base));
    const external = async () => {
      if (writer === 'cli')
        execFileSync(cli, [config, 'policy', 'disabled'], {
          windowsHide: true,
        });
      else if (writer === 'tray')
        execFileSync(harness, [root, 'Disabled'], { windowsHide: true });
      else {
        const expected = join(root, 'wpf-expected.json'),
          candidate = join(root, 'wpf-candidate.json');
        await copyFile(config, expected);
        const v = JSON.parse(await readFile(config));
        await writeFile(
          candidate,
          JSON.stringify({
            ...v,
            policy: 'disabled',
            mapping_revision: v.mapping_revision + 1,
          })
        );
        execFileSync(harness, [root, expected, candidate], {
          windowsHide: true,
        });
      }
      return JSON.parse(await readFile(config));
    };
    let value, externalValue, error;
    const service = new MonakaBridgeService(
      {
        read: async () => ({ configPath: config, validatorPath: cli }),
        write: async () => {},
      },
      async (...args) => {
        await validateBridgeCandidate(...args);
        if (phase === 'during-validation') externalValue = await external();
      },
      async (...args) => {
        if (phase === 'before-native-cas') externalValue = await external();
        await commitBridgeCandidate(...args);
      }
    );
    const loaded = await service.load();
    if (phase === 'before-apply') externalValue = await external();
    try {
      value = await service.apply(loaded.token, 'both', 'cooperative-race');
    } catch (e) {
      error = e.message;
    }
    if (phase === 'after-commit') externalValue = await external();
    const final = JSON.parse(await readFile(config));
    if (phase === 'after-commit') {
      assert.equal(value.policy, 'both');
      assert.equal(final.mapping_revision, 12);
    } else {
      assert.match(error, /changed/i);
      assert.equal(value, undefined);
      assert.equal(final.mapping_revision, 11);
    }
    assert.equal(final.policy, 'disabled');
    assert.deepEqual(final.unknown_extension, base.unknown_extension);
    service.dispose();
    rows.push({
      writer,
      phase,
      result: 'PASS',
      error,
      final,
      externalValue,
      lost_update: false,
    });
  }
await writeFile(
  join(output, 'summary.json'),
  JSON.stringify(
    {
      result: 'PASS',
      cases: rows.length,
      lost_updates: 0,
      unknown_extension_drops: 0,
      rows,
    },
    null,
    2
  )
);
console.log(
  'PASS actual Desktop/CLI/tray/WPF:12 schedules, lost0, unknown-drop0'
);
