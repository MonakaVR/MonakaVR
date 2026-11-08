import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

export default defineConfig({
  root: resolve(__dirname, '../..'),
  plugins: [react()],
  resolve: { alias: { '@': resolve(__dirname, '../../src') } },
  optimizeDeps: { include: ['solarxr-protocol'] },
  server: { host: '127.0.0.1', port: 4198, strictPort: true },
});
