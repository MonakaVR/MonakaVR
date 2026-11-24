import type { BridgeDesktopApi } from '../../electron/bridge-types';

export const BRIDGE_SMOKE_CHANNELS = {
  CONTROL: 'monaka-bridge-smoke-control',
  OPEN: 'monaka-bridge-smoke-open',
} as const;

declare global {
  interface Window {
    bridgeSmokeApi: BridgeDesktopApi;
  }
}
