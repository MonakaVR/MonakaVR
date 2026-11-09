export const overrideHands = ['left', 'right'] as const;
export type OverrideHand = (typeof overrideHands)[number];

export function overridePaths(hand: OverrideHand) {
  return {
    devicePath: '/devices/slimevr/monaka-override-v1-' + hand,
    targetPath: '/user/hand/' + hand,
  };
}

export type GripOffset = {
  positionM: [number, number, number];
  rotationWxyz: [number, number, number, number];
};

export type OverrideSelection = {
  enabled: boolean;
  gripOffset: GripOffset | null;
  sameUniverseConfirmed: boolean;
};

export type OverrideSourceStatus =
  | 'OFF'
  | 'UNASSIGNED'
  | 'NO_GRIP_OFFSET'
  | 'UNCONFIRMED_UNIVERSE'
  | 'CAPABILITY_UNKNOWN'
  | 'CAPABILITY_UNSUPPORTED'
  | 'SOURCE_UNAVAILABLE'
  | 'STALE'
  | 'INCOMPLETE'
  | 'SPACE_MISMATCH'
  | 'FULL';

export type OverrideOutputSnapshot = {
  driverReady: boolean;
  hands: Record<
    OverrideHand,
    {
      saved: OverrideSelection;
      source: string | null;
      canEnable: boolean;
      calibrationCurrent: boolean;
      sourceStatus: OverrideSourceStatus | null;
      devicePath: string;
      targetPath: string;
      registeredDevicePath: string | null;
    }
  >;
};

export type SteamOverrideSnapshot = {
  token: string;
  hands: Record<OverrideHand, { owned: boolean; conflict: boolean }>;
};

export type OverrideRequest =
  | { action: 'snapshot' }
  | { action: 'save'; hand: OverrideHand; token: string; selection: OverrideSelection }
  | { action: 'apply' | 'remove'; hand: OverrideHand; token: string };

export type OverrideReply =
  | { type: 'steam'; settings: SteamOverrideSnapshot }
  | { type: 'core'; settings?: SteamOverrideSnapshot; error?: string };
