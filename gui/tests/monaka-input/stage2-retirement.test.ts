import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';

test('consumer entry points contain no producer-control activation', () => {
  for (const file of [
    'electron/main/index.ts',
    'electron/preload/index.ts',
    'electron/preload/interface.d.ts',
    'electron/shared.ts',
    'src/App.tsx',
    'src/components/settings/SettingsSidebar.tsx',
    'src/components/settings/SettingsLayout.tsx',
  ]) {
    assert.doesNotMatch(
      readFileSync(file, 'utf8'),
      /MonakaBridge|monakaBridge|monaka-bridge|BRIDGE_CONTROL|BRIDGE_OPEN|["']\/bridge["']/,
      `${file}: retired control remains reachable`
    );
  }
  assert.match(
    readFileSync('electron/main/monaka-bridge-service.ts', 'utf8'),
    /class MonakaBridgeService/
  );
});

test('actual consumer preload exposes no producer API or producer IPC listener', () => {
  const exposed = new Map<string, Record<string, unknown>>();
  const listeners: string[] = [];
  const invocations: string[] = [];
  const electron = {
    contextBridge: {
      exposeInMainWorld: (name: string, api: Record<string, unknown>) =>
        exposed.set(name, api),
    },
    ipcRenderer: {
      on: (channel: string) => listeners.push(channel),
      removeListener: () => undefined,
      invoke: (channel: string) => {
        invocations.push(channel);
        throw new Error('No IPC request expected during preload');
      },
    },
  };
  function execute(file: string): Record<string, unknown> {
    const result = ts.transpileModule(readFileSync(file, 'utf8'), {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2023,
      },
      fileName: file,
      reportDiagnostics: true,
    });
    assert.equal(
      result.diagnostics?.filter((d) => d.category === ts.DiagnosticCategory.Error)
        .length ?? 0,
      0
    );
    const module = { exports: {} };
    runInNewContext(
      result.outputText,
      {
        module,
        exports: module.exports,
        window: { location: { hash: '' } },
        require: (name: string) => {
          if (name === 'electron') return electron;
          if (name === '../shared') return execute('electron/shared.ts');
          throw new Error(`Unexpected preload dependency: ${name}`);
        },
      },
      { filename: file }
    );
    return module.exports;
  }
  execute('electron/preload/index.ts');
  const api = exposed.get('electronAPI');
  assert.ok(api);
  assert.equal(Object.hasOwn(api, 'bridge'), false);
  assert.equal(typeof api.onServerStatus, 'function');
  assert.equal(typeof api.getStorage, 'function');
  assert.deepEqual(listeners, []);
  assert.deepEqual(invocations, []);
});

test('actual store preserves cloned legacy and unknown preferences with byte-exact rollback', async () => {
  const parent = resolve('build/stage2-preferences');
  await mkdir(parent, { recursive: true });
  const root = await mkdtemp(`${parent}/case-`);
  const file = resolve(root, 'gui-settings.dat');
  const original =
    '{"monakaBridge":{"exe":"synthetic-unused.exe","future":{"v":[1,2]}},"lang":"ja","future":{"opaque":true}}\n';
  await writeFile(file, original);
  await writeFile(`${file}.backup`, original);
  const loader = createRequire(resolve('package.json'));
  const result = ts.transpileModule(readFileSync('electron/main/store.ts', 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2023 },
  });
  const module = { exports: {} as Record<string, any> };
  runInNewContext(result.outputText, {
    module,
    exports: module.exports,
    setTimeout,
    clearTimeout,
    require: (name: string) => {
      if (name === './logger') return { logger: { warn() {}, error() {} } };
      if (name === './paths') return { getGuiDataFolder: () => root };
      if (name === 'fs/promises' || name === 'path') return loader(name);
      throw new Error(`Unexpected store dependency ${name}`);
    },
  });
  const stores = await module.exports.initStores();
  assert.equal(
    await readFile(file, 'utf8'),
    original,
    'opening clone must not migrate or rewrite it'
  );
  stores.settings.set('lang', 'en');
  assert.equal(await stores.settings.save(), true);
  const saved = JSON.parse(await readFile(file, 'utf8'));
  const initial = JSON.parse(original);
  assert.deepEqual(saved.monakaBridge, initial.monakaBridge);
  assert.deepEqual(saved.future, initial.future);
  assert.equal(saved.lang, 'en');
  await writeFile(file, await readFile(`${file}.backup`));
  const restored = await module.exports.initStores();
  assert.equal(restored.settings.get('lang'), 'ja');
  assert.equal(await readFile(file, 'utf8'), original);
});
