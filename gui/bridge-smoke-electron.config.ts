import { defineConfig } from 'vite';
import { resolve } from 'node:path';

// No production main, Core launcher, or renderer inputs in this test build.
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
};
