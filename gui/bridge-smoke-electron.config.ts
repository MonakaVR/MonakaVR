import { defineConfig } from 'vite';
import { resolve } from 'node:path';

// Main and preload are built from isolated smoke fixture inputs.
export default {
  main: defineConfig({
    build: {
      outDir: 'build/bridge-native',
      rollupOptions: {
        input: resolve(__dirname, 'tests/bridge-smoke/electron-main.ts'),
        output: { format: 'cjs', entryFileNames: 'main.cjs' },
      },
    },
  }),
  preload: defineConfig({
    build: {
      outDir: 'build/bridge-native',
      emptyOutDir: false,
      rollupOptions: {
        input: resolve(__dirname, 'tests/bridge-smoke/electron-preload.ts'),
        output: { format: 'cjs', entryFileNames: 'preload.cjs' },
      },
    },
  }),
};
