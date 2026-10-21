import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve, dirname } from 'node:path';
import { spawnSync } from 'node:child_process';

// Use the esbuild already pinned by Vite; no new dependency or product script.
const require = createRequire(import.meta.url);
const viteRequire = createRequire(require.resolve('vite/package.json'));
const { build } = await import(
  pathToFileURL(viteRequire.resolve('esbuild')).href
);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const tests = ['view-model.test.ts', 'component.test.tsx'];
await build({
  absWorkingDir: root,
  entryPoints: tests.map((name) => `tests/monaka-status/${name}`),
  outdir: 'build/monaka-status',
  outExtension: { '.js': '.mjs' },
  bundle: true,
  platform: 'node',
  format: 'esm',
  packages: 'external',
  jsx: 'automatic',
  alias: { '@': resolve(root, 'src') },
});
const result = spawnSync(
  process.execPath,
  [
    '--test',
    '--test-reporter=tap',
    ...tests.map((name) =>
      resolve(root, 'build/monaka-status', name.replace(/\.tsx?$/, '.mjs'))
    ),
  ],
  { cwd: root, stdio: 'inherit' }
);
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
