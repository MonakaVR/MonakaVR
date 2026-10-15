// Creates an unsigned GUI-only TEST package; never a full runtime distribution.
import assert from 'node:assert/strict';
import { build, Platform, Arch } from 'electron-builder';
import { extractFile, listPackage } from '@electron/asar';
import {
  copyFile,
  mkdir,
  readFile,
  writeFile,
  readdir,
} from 'node:fs/promises';
import { resolve, join, normalize } from 'node:path';
import { createHash } from 'node:crypto';

const root = process.env.MONAKA_SUPPLEMENT_ROOT;
const runtime = process.env.MONAKA_TEST_ELECTRON_DIST;
assert.ok(
  root && runtime,
  'Task-owned output and cached pinned runtime required'
);
assert.equal(
  root,
  resolve('../build/gui-supplemental-20261015'),
  'Packaging output must stay in this task build directory'
);
const entryFolder = join(root, 'package-entry');
await mkdir(entryFolder, { recursive: true });
await copyFile(
  resolve('tests/bridge-supplemental/isolated-production-main.mjs'),
  join(entryFolder, 'isolated-production-main.mjs')
);
const config = {
  extends: null,
  appId: 'local.monaka.softwaretest',
  productName: 'MonakaGuiIsolatedTest',
  directories: {
    output: join(root, 'package'),
    buildResources: resolve('electron/resources'),
  },
  electronVersion: '40.9.1',
  electronDist: runtime,
  npmRebuild: false,
  asar: true,
  asarUnpack: ['out/main/chunks/*.jar'],
  files: [
    'out/**/*',
    'package.json',
    'node_modules/**',
    { from: entryFolder, to: '.', filter: ['isolated-production-main.mjs'] },
  ],
  extraFiles: [],
  extraResources: [],
  publish: null,
  extraMetadata: {
    name: 'monaka-gui-isolated-test',
    main: 'isolated-production-main.mjs',
  },
  win: { target: 'dir', signAndEditExecutable: false, extraFiles: [] },
};
const configPath = join(root, 'test-package-config.json');
await writeFile(configPath, JSON.stringify(config, null, 2));
if (!process.argv.includes('--verify-only'))
  await build({
    projectDir: resolve('.'),
    config: configPath,
    targets: Platform.WINDOWS.createTarget('dir', Arch.x64),
    publish: 'never',
  });
const appRoot = join(root, 'package/win-unpacked');
const asar = join(appRoot, 'resources/app.asar');
const fileList = listPackage(asar).map((name) => name.replaceAll('\\', '/'));
assert.ok(!fileList.some((name) => name.endsWith('/slimevr.jar')));
assert.ok(
  !(await readdir(appRoot)).some((name) =>
    /slimevr\.jar|openvr|bindings-provider/i.test(name)
  )
);
const hashes = {};
const productFiles = [];
async function visit(folder) {
  for (const item of await readdir(resolve(folder), { withFileTypes: true })) {
    const name = join(folder, item.name);
    if (item.isDirectory()) await visit(name);
    else productFiles.push(name);
  }
}
await visit('out');
for (const name of productFiles) {
  const source = await readFile(resolve(name));
  const archived = extractFile(asar, normalize(name));
  assert.ok(
    source.equals(archived),
    `Product bytes changed in package: ${name}`
  );
  hashes[name.replaceAll('\\', '/')] = createHash('sha256')
    .update(source)
    .digest('hex');
}
assert.ok(
  extractFile(asar, 'isolated-production-main.mjs').equals(
    await readFile(
      resolve('tests/bridge-supplemental/isolated-production-main.mjs')
    )
  )
);
const metadata = JSON.parse(extractFile(asar, 'package.json').toString());
assert.equal(metadata.main, 'isolated-production-main.mjs');
await mkdir(join(root, 'package-receipts'), { recursive: true });
await writeFile(
  join(root, 'package-receipts/asar-file-list.json'),
  JSON.stringify(fileList, null, 2)
);
await writeFile(
  join(root, 'package-result.json'),
  JSON.stringify(
    {
      result: 'PASS_TEST_PACKAGE_BUILD',
      configPath,
      appRoot,
      asar,
      executable: join(appRoot, 'MonakaGuiIsolatedTest.exe'),
      asarSha256: createHash('sha256')
        .update(await readFile(asar))
        .digest('hex'),
      executableSha256: createHash('sha256')
        .update(await readFile(join(appRoot, 'MonakaGuiIsolatedTest.exe')))
        .digest('hex'),
      hashes,
      sourceBytesPreserved: true,
      coreAndOutputAssetsOmitted: true,
      publish: 'never',
      limits:
        'Unsigned GUI-only guarded test package. Full product distribution/installer/signing/HIL NOT_RUN.',
    },
    null,
    2
  )
);
console.log(
  JSON.stringify({
    result: 'PASS_TEST_PACKAGE_BUILD',
    appRoot,
    sourceBytesPreserved: true,
  })
);
