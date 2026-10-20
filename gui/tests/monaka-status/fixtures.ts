import type { InputCandidate, InputSnapshot } from '../../src/monaka-input/controller';
import type { TrackingConnection } from '../../src/monaka-status/view-model';

export function source(
  publisher = 'publisher-a',
  overrides: Partial<InputCandidate> = {}
): InputCandidate {
  return {
    id: `mtp:11:${publisher}8:tracking7:tracker`,
    publisher,
    source: 'tracking',
    tracker: 'tracker',
    origin: 'mvip_pose',
    capabilities: ['orientation'],
    conflict: false,
    qSelectable: true,
    sixDofSelectable: false,
    qUsableNow: true,
    sixDofUsableNow: false,
    ...overrides,
  };
}
export const q = source();
export const full = source('publisher-b', {
  capabilities: ['position', 'orientation'],
  sixDofSelectable: true,
  sixDofUsableNow: true,
});
export const slime = source('slime', {
  id: 'slime:imu',
  publisher: null,
  source: null,
  tracker: 'imu',
  origin: 'slime_orientation_stream',
});
export function snapshot(
  candidates: InputCandidate[] = [q],
  overrides: Partial<InputSnapshot> = {}
): InputSnapshot {
  return {
    generation: '7',
    bodies: ['HIP', 'CHEST'],
    candidates,
    assignments: [
      {
        body: 'HIP',
        qSource: candidates[0]?.id ?? null,
        sixDofSource: null,
        manualSix: false,
        mode: 'ik',
      },
    ],
    ...overrides,
  };
}
export interface StatusFixture {
  name: string;
  snapshot: InputSnapshot | null;
  connection: TrackingConnection;
  expected: string[];
}
export const fixtures: StatusFixture[] = [
  {
    name: 'Q-only',
    snapshot: snapshot(),
    connection: 'connected',
    expected: [
      'Orientation only',
      'Unassigned',
      'Q usable now: Yes',
      '6DoF usable now: No',
    ],
  },
  {
    name: 'Pos+Q',
    snapshot: snapshot([full], {
      assignments: [
        {
          body: 'HIP',
          qSource: full.id,
          sixDofSource: full.id,
          manualSix: true,
          mode: 'direct',
        },
      ],
    }),
    connection: 'connected',
    expected: ['Position + orientation', 'Manual selection', '6DoF usable now: Yes'],
  },
  {
    name: 'unknown',
    snapshot: snapshot([
      source('publisher-a', {
        capabilities: null,
        qSelectable: false,
        qUsableNow: false,
      }),
    ]),
    connection: 'connected',
    expected: ['Unknown', 'Q usable now: No'],
  },
  {
    name: 'conflict',
    snapshot: snapshot([
      source('publisher-a', { conflict: true, qSelectable: false, qUsableNow: false }),
    ]),
    connection: 'connected',
    expected: ['Conflicting', 'Q usable now: No'],
  },
  {
    name: 'same-name/different-ID',
    snapshot: snapshot([q, full]),
    connection: 'connected',
    expected: ['publisher-a', 'publisher-b'],
  },
  {
    name: 'saved offline',
    snapshot: snapshot([], {
      assignments: [
        {
          body: 'HIP',
          qSource: q.id,
          sixDofSource: full.id,
          manualSix: true,
          mode: 'hybrid',
        },
      ],
    }),
    connection: 'connected',
    expected: ['Saved (offline)', 'Manual selection', '6DoF usable now: Unknown'],
  },
  {
    name: 'invalid-reconnect',
    snapshot: snapshot([
      source('publisher-a', { id: 'new-connection-id', qUsableNow: false }),
    ]),
    connection: 'connected',
    expected: ['Q usable now: No', 'new-connection-id'],
  },
  {
    name: 'Slime Q + separate MVIP 6DoF',
    snapshot: snapshot([slime, full], {
      assignments: [
        {
          body: 'HIP',
          qSource: slime.id,
          sixDofSource: full.id,
          manualSix: true,
          mode: 'hybrid',
        },
      ],
    }),
    connection: 'connected',
    expected: ['slime_orientation_stream', 'Manual selection', 'publisher-b'],
  },
  {
    name: '0 candidates',
    snapshot: snapshot([], { assignments: [] }),
    connection: 'connected',
    expected: ['No input candidates in snapshot.', 'Unassigned'],
  },
  {
    name: 'disconnected',
    snapshot: null,
    connection: 'offline',
    expected: ['Core disconnected', 'Source list unavailable.'],
  },
  {
    name: 'disabled',
    snapshot: snapshot([full]),
    connection: 'disabled',
    expected: ['runtime disabled', 'Saved (offline)', '6DoF usable now: Unknown'],
  },
  {
    name: 'loopback denied',
    snapshot: snapshot([full]),
    connection: 'loopback-denied',
    expected: [
      'loopback access unavailable',
      'Saved (offline)',
      '6DoF usable now: Unknown',
    ],
  },
];
