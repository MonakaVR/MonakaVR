import { defineConfig, searchForWorkspaceRoot } from 'vite';
import react from '@vitejs/plugin-react';
import { dirname, resolve } from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

// The real index/App/providers/routes; only the build labels are test values.
export default defineConfig({
  cacheDir: 'build/monaka-input-route-vite-cache',
  plugins: [react()],
  define: {
    __COMMIT_HASH__: JSON.stringify('issue-15-software-fixture'),
    __VERSION_TAG__: JSON.stringify(''),
    __GIT_CLEAN__: false,
  },
  resolve: { alias: { '@': resolve(__dirname, '../../src') } },
  optimizeDeps: {
    needsInterop: ['solarxr-protocol'],
    include: ['solarxr-protocol'],
  },
  css: { preprocessorOptions: { scss: { api: 'modern' } } },
  server: {
    fs: {
      allow: [
        searchForWorkspaceRoot(process.cwd()),
        dirname(require.resolve('@fontsource/poppins/package.json')),
      ],
    },
    host: '127.0.0.1',
    port: Number(process.env.MONAKA_INPUT_SMOKE_PORT ?? '4207'),
    strictPort: true,
  },
});
