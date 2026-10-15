// Software-only reproduction with real frozen CLI / actual legacy SaveRoute.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import {
  copyFile,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rename,
  writeFile,
} from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';
import {
  MonakaBridgeService,
  validateBridgeCandidate,
} from '../../build/bridge-tests/electron/main/monaka-bridge-service.js';

const output = process.env.MONAKA_SUPPLEMENT_ROOT;
const cli = process.env.MONAKA_LEGACY_CONFIG;
const trayHarness = process.env.MONAKA_LEGACY_TRAY_HARNESS;
assert.ok(
  output && cli && trayHarness,
  'Explicit task-owned output and frozen executables required'
);
const base = {
  namespace: 'monaka.bridge',
  version: 2,
  bridge_id: 'race-software-fixture',
  mapping_revision: 10,
  policy: 'steamvr',
  pose_timeout_ms: 500,
  profiles: {},
  mappings: [],
  extension_fixture: {
    note: 'software test only; preserve unless native serializer drops unknown fields',
  },
};
const rows = [];
for (const writer of ['cli', 'legacy-tray-save']) {
  for (const phase of [
    'before-apply',
    'during-validation',
    'after-final-compare',
    'after-commit',
  ]) {
    const root = await mkdtemp(join(output, `${writer}-${phase}-`));
    await mkdir(join(root, 'config'));
    await mkdir(join(root, 'build/Release'), { recursive: true });
    await copyFile(cli, join(root, 'build/Release/monaka_bridge_config.exe'));
    const config = join(root, 'config/bridge.json');
    await writeFile(config, JSON.stringify(base));
    const disk = async () => JSON.parse(await readFile(config, 'utf8'));
    const external = async () => {
      const lockPresent = (await readdir(join(root, 'config'))).some((name) =>
        name.endsWith('.lock')
      );
      if (writer === 'cli')
        execFileSync(cli, [config, 'policy', 'disabled'], {
          windowsHide: true,
          timeout: 10_000,
        });
      else
        execFileSync(trayHarness, [root, 'disabled'], {
          windowsHide: true,
          timeout: 10_000,
        });
      const state = await disk();
      assert.equal(state.policy, 'disabled');
      return { lockPresent, state };
    };
    let externalResult;
    const preferences = {
      read: async () => ({ configPath: config, validatorPath: cli }),
      write: async () => {},
    };
    const service = new MonakaBridgeService(
      preferences,
      async (...args) => {
        await validateBridgeCandidate(...args);
        if (phase === 'during-validation') externalResult = await external();
      },
      async (candidate, target) => {
        if (phase === 'after-final-compare') externalResult = await external();
        await rename(candidate, target);
      }
    );
    const loaded = await service.load();
    if (phase === 'before-apply') externalResult = await external();
    let saved = null,
      error = null;
    try {
      saved = await service.apply(loaded.token, 'both', 'race-check');
    } catch (cause) {
      error = cause.message;
    }
    if (phase === 'after-commit') externalResult = await external();
    const final = await disk();
    if (phase === 'before-apply' || phase === 'during-validation') {
      assert.match(error, /changed in another tool/);
      assert.equal(saved, null);
      assert.equal(final.policy, 'disabled');
    } else if (phase === 'after-final-compare') {
      assert.equal(error, null);
      assert.equal(saved.policy, 'both');
      assert.equal(final.policy, 'both');
      assert.equal(
        externalResult.state.mapping_revision,
        final.mapping_revision
      );
      assert.equal(externalResult.lockPresent, true);
    } else {
      assert.equal(error, null);
      assert.equal(saved.policy, 'both');
      assert.equal(final.policy, 'disabled');
      assert.equal(final.mapping_revision, saved.revision + 1);
      assert.equal((await service.load()).policy, 'disabled');
    }
    const backupFiles = (await readdir(join(root, 'config'))).filter(
      (name) => name.includes('.bak') || name.includes('.backup-')
    );
    const backups = await Promise.all(
      backupFiles.map(async (name) => ({
        name,
        value: JSON.parse(await readFile(join(root, 'config', name), 'utf8')),
      }))
    );
    const record = {
      writer,
      phase,
      savedPolicy: saved?.policy ?? null,
      error,
      external: externalResult,
      final,
      backups,
      externalPolicyLost:
        phase === 'after-final-compare' &&
        final.policy !== externalResult.state.policy,
      externalPolicyInAutomaticBackup: backups.some(
        (item) => item.value.policy === 'disabled'
      ),
      root,
      sourceUnchanged: true,
      schedule:
        phase === 'after-final-compare'
          ? 'Deterministic existing replace test seam: real legacy write before real rename, after final compare'
          : phase,
    };
    if (phase === 'after-final-compare')
      assert.equal(record.externalPolicyInAutomaticBackup, false);
    service.dispose();
    rows.push(record);
  }
}
const result = {
  cases: rows.length,
  checksPassed: true,
  actualLegacyTraySaveOnly: true,
  hardwareOrServiceNotification: false,
  rows,
  nativeCliSha256: createHash('sha256')
    .update(await readFile(cli))
    .digest('hex'),
  trayHarnessSha256: createHash('sha256')
    .update(await readFile(trayHarness))
    .digest('hex'),
  limits:
    'Known race is reproduced, not fixed. No manual tray click or SetRoute/global service event. The critical schedule is forced at the existing replacement seam.',
};
await writeFile(
  resolve(output, 'race-result.json'),
  JSON.stringify(result, null, 2)
);
console.log(
  JSON.stringify(
    {
      cases: rows.length,
      checksPassed: true,
      lostUpdatesReproduced: rows.filter((row) => row.externalPolicyLost)
        .length,
      unknownExtensionDroppedByLegacy: rows.every(
        (row) => !('extension_fixture' in row.external.state)
      ),
    },
    null,
    2
  )
);
