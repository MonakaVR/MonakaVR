import type { InputCandidate, InputSnapshot } from '../../src/monaka-input/controller';

export const source = (
  id = 'opaque-a',
  overrides: Partial<InputCandidate> = {}
): InputCandidate => ({
  id,
  publisher: 'arbitrary-a',
  source: 'source',
  tracker: 'tracker',
  displayName: 'Input device',
  origin: 'mvip_pose',
  capabilities: ['position', 'orientation', 'battery_fraction'],
  conflict: false,
  qSelectable: true,
  sixDofSelectable: true,
  qUsableNow: true,
  sixDofUsableNow: true,
  ...overrides,
});
export const snapshot = (
  candidates: InputCandidate[] = [],
  overrides: Partial<InputSnapshot> = {}
): InputSnapshot => ({
  generation: '1',
  bodies: ['HIP', 'LEFT_UPPER_LEG'],
  candidates,
  assignments: [
    {
      body: 'LEFT_UPPER_LEG',
      qSource: candidates[0]?.id ?? null,
      sixDofSource: candidates[0]?.id ?? null,
      manualSix: true,
      mode: 'hybrid',
    },
  ],
  ...overrides,
});
