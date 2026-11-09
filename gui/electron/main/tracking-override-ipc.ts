import {
  overrideHands,
  overridePaths,
  type OverrideHand,
  type OverrideOutputSnapshot,
  type OverrideReply,
  type SteamOverrideSnapshot,
} from '../tracking-override-types.js';
import type { SteamTrackingOverrides } from './steam-tracking-overrides.js';

type LocalCoreConnection = {
  request: (fields: Record<string, unknown>) => Promise<{
    readbackToken?: string;
    trackingOverride?: OverrideOutputSnapshot;
  }>;
  close: () => void;
};

export async function requestTrackingOverride(
  value: unknown,
  steam: Pick<SteamTrackingOverrides, 'snapshot' | 'change'>,
  connectCore: () => LocalCoreConnection,
  editorToken: string
): Promise<OverrideReply> {
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    !('action' in value)
  )
    throw new Error('Invalid tracking override request');
  if (value.action === 'snapshot') {
    if (Object.keys(value).length !== 1)
      throw new Error('Invalid tracking override snapshot request');
    return { type: 'steam', settings: await steam.snapshot() };
  }
  if (
    !('hand' in value) ||
    !overrideHands.includes(value.hand as OverrideHand) ||
    !('token' in value) ||
    typeof value.token !== 'string' ||
    value.token.length > 160
  ) {
    throw new Error('Invalid tracking override hand or readback token');
  }
  const hand = value.hand as OverrideHand;
  if (value.action === 'save') {
    if (!('selection' in value) || Object.keys(value).length !== 4)
      throw new Error('Invalid tracking override selection');
    const selection = value.selection;
    if (
      !selection ||
      typeof selection !== 'object' ||
      Object.keys(selection).length !== 3 ||
      !('enabled' in selection) ||
      typeof selection.enabled !== 'boolean' ||
      !('sameUniverseConfirmed' in selection) ||
      typeof selection.sameUniverseConfirmed !== 'boolean' ||
      !('gripOffset' in selection) ||
      JSON.stringify(selection).length > 4096
    )
      throw new Error('Invalid tracking override selection');
    const core = connectCore();
    let removed: SteamOverrideSnapshot | undefined;
    try {
      if (!selection.enabled) {
        const current = await core.request({ action: 'snapshot' });
        if (current.readbackToken !== value.token)
          throw new Error('Saved hand assignment changed. Reload before disabling.');
        if (current.trackingOverride?.hands?.[hand]?.saved.enabled) {
          const settings = await steam.snapshot();
          if (settings.hands[hand].owned)
            removed = await steam.change('remove', hand, settings.token);
        }
      }
      await core.request({
        action: 'save_override',
        hand,
        selection,
        readbackToken: value.token,
        editorToken,
      });
      return removed ? { type: 'core', settings: removed } : { type: 'core' };
    } catch (error) {
      if (removed)
        return {
          type: 'core',
          settings: removed,
          error: error instanceof Error ? error.message : String(error),
        };
      throw error;
    } finally {
      core.close();
    }
  }
  if (
    Object.keys(value).length !== 3 ||
    (value.action !== 'apply' && value.action !== 'remove')
  )
    throw new Error('Invalid SteamVR tracking override action');
  if (value.action === 'apply') {
    const core = connectCore();
    try {
      const snapshot = await core.request({ action: 'snapshot' });
      const row = snapshot.trackingOverride?.hands?.[hand];
      const { devicePath, targetPath } = overridePaths(hand);
      if (
        row?.saved.enabled !== true ||
        row.canEnable !== true ||
        row.calibrationCurrent !== true ||
        row.devicePath !== devicePath ||
        row.registeredDevicePath !== devicePath ||
        row.targetPath !== targetPath
      )
        throw new Error(
          'Save an enabled override and confirm driver registration before applying to SteamVR'
        );
    } finally {
      core.close();
    }
  }
  return {
    type: 'steam',
    settings: await steam.change(value.action, hand, value.token),
  };
}
