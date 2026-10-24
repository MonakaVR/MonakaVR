import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

// The real index/App/providers/routes; only the build labels are test values.
export default defineConfig({
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
  server: { host: '127.0.0.1', port: 4207, strictPort: true },
});
