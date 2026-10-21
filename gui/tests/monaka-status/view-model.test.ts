import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildTrackingStatusModel } from '../../src/monaka-status/view-model';
import type { InputSnapshot } from '../../src/monaka-input/controller';
import { fixtures, full, q, slime, snapshot, source } from './fixtures';

const connected = { connection: 'connected' } as const;
test('an initially offline cached snapshot retains assignments without usable claims', () => {
  const model = buildTrackingStatusModel(fixtures[1].snapshot, {
    connection: 'offline',
  });
  assert.equal(model.acceptedSnapshot, null);
  assert.equal(model.bodies[0].sixDof?.id, full.id);
  assert.equal(model.bodies[0].manualSix, true);
  assert.equal(model.sources[0].sixDofUsableNow, 'unknown');
  const absent = buildTrackingStatusModel(null, {
    connection: 'offline',
    previousSnapshot: model.retainedSnapshot,
  });
  assert.equal(absent.bodies[0].sixDof?.id, full.id);
});
for (const fixture of fixtures) {
  test(`projects fixture: ${fixture.name}`, () => {
    const model = buildTrackingStatusModel(fixture.snapshot, {
      connection: fixture.connection,
    });
    assert.equal(
      model.status,
      fixture.connection === 'connected' ? 'snapshot' : 'offline'
    );
  });
}
test('static capability survives dynamic invalidity without losing manual assignments', () => {
  const previous = fixtures[1].snapshot!;
  const next = {
    ...previous,
    candidates: [{ ...full, qUsableNow: false, sixDofUsableNow: false }],
  };
  const model = buildTrackingStatusModel(next, {
    ...connected,
    previousSnapshot: previous,
  });
  assert.equal(model.sources[0].capability, 'position-orientation');
  assert.equal(model.sources[0].sixDofSelectable, true);
  assert.equal(model.sources[0].sixDofUsableNow, 'no');
  assert.equal(model.bodies[0].sixDof?.id, full.id);
  assert.equal(model.bodies[0].manualSix, true);
});
test('same-name sources remain distinct by full ID and tuple', () => {
  const model = buildTrackingStatusModel(snapshot([q, full]), connected);
  assert.equal(model.sources.length, 2);
  assert.notEqual(model.sources[0].id, model.sources[1].id);
  assert.equal(model.sources[0].tracker, model.sources[1].tracker);
  assert.notEqual(model.sources[0].publisher, model.sources[1].publisher);
});
test('known telemetry capabilities do not downgrade valid Pos+Q or Q-only sources', () => {
  const telemetry = [
    'linear_velocity',
    'angular_velocity',
    'linear_acceleration',
    'battery_fraction',
    'charging',
  ];
  const fullWithTelemetry = {
    ...full,
    capabilities: ['position', 'orientation', ...telemetry],
  };
  const qWithTelemetry = {
    ...q,
    capabilities: ['orientation', ...telemetry],
  };
  const model = buildTrackingStatusModel(
    snapshot([fullWithTelemetry, qWithTelemetry]),
    connected
  );
  assert.equal(model.status, 'snapshot');
  const positionSource = model.sources.find((x) => x.id === full.id)!;
  assert.equal(positionSource.capability, 'position-orientation');
  assert.equal(positionSource.sixDofSelectable, true);
  const orientationSource = model.sources.find((x) => x.id === q.id)!;
  assert.equal(orientationSource.capability, 'orientation-only');
  assert.equal(orientationSource.qSelectable, true);
  assert.equal(orientationSource.sixDofSelectable, false);
});
test('Q-only has no synthetic 6DoF assignment; Slime never gains 6DoF', () => {
  assert.equal(buildTrackingStatusModel(snapshot(), connected).bodies[0].sixDof, null);
  const model = buildTrackingStatusModel(snapshot([slime, full]), connected);
  assert.equal(model.sources[0].sixDofSelectable, false);
  assert.equal(model.sources[0].capability, 'orientation-only');
});
test('unknown declarations do not infer static capability from live usability', () => {
  const model = buildTrackingStatusModel(
    snapshot([
      source('publisher-a', {
        capabilities: null,
        qSelectable: false,
        qUsableNow: true,
      }),
    ]),
    connected
  );
  assert.equal(model.sources[0].capability, 'unknown');
  assert.equal(model.sources[0].qUsableNow, 'yes');
});
test('disconnect and absent snapshots retain choices with unknown current usability', () => {
  const previous = fixtures[1].snapshot!;
  for (const connection of [
    'offline',
    'disabled',
    'loopback-denied',
    'error',
    'unknown',
    'connected',
  ] as const) {
    const model = buildTrackingStatusModel(null, {
      connection,
      previousSnapshot: previous,
    });
    assert.equal(model.generation, null);
    assert.equal(model.retainedGeneration, previous.generation);
    assert.equal(model.bodies[0].sixDof?.id, full.id);
    assert.equal(model.bodies[0].manualSix, true);
    assert.equal(model.sources[0].sixDofUsableNow, 'unknown');
    assert.equal(model.sources[0].availability, 'saved-offline');
  }
});
test('offline IDs are deduplicated and never decoded into invented metadata', () => {
  const previous = fixtures[1].snapshot!;
  const model = buildTrackingStatusModel(
    snapshot([], { assignments: previous.assignments }),
    connected
  );
  assert.equal(model.sources.length, 1);
  assert.equal(model.sources[0].id, full.id);
  assert.equal(model.sources[0].publisher, null);
  assert.equal(model.bodies[0].q, model.bodies[0].sixDof);
});
test('reconnect cannot substitute another ID with the same display identity', () => {
  const previous = fixtures[1].snapshot!;
  const model = buildTrackingStatusModel(
    {
      ...previous,
      candidates: [
        { ...full, id: 'new-id', qUsableNow: false, sixDofUsableNow: false },
      ],
    },
    { ...connected, previousSnapshot: previous }
  );
  assert.equal(model.sources.length, 2);
  assert.equal(model.bodies[0].sixDof?.id, full.id);
  assert.equal(model.bodies[0].sixDof?.availability, 'saved-offline');
});
test('old generations fail closed with integer precision above JS safe integers', () => {
  const previous = snapshot([full], { generation: '9007199254740993' });
  const model = buildTrackingStatusModel(
    { ...previous, generation: '9007199254740992' },
    { ...connected, previousSnapshot: previous }
  );
  assert.equal(model.reason, 'stale-generation');
  assert.equal(model.generation, null);
  assert.equal(model.sources[0].qUsableNow, 'unknown');
  assert.equal(
    buildTrackingStatusModel(previous, {
      ...connected,
      minimumGeneration: '9007199254740994',
    }).reason,
    'stale-generation'
  );
});
test('same generation permits validity updates but rejects changed selected IDs', () => {
  const previous = snapshot();
  assert.equal(
    buildTrackingStatusModel(snapshot([q, full]), {
      ...connected,
      previousSnapshot: previous,
    }).status,
    'snapshot'
  );
  const changed = snapshot([full]);
  const model = buildTrackingStatusModel(changed, {
    ...connected,
    previousSnapshot: previous,
  });
  assert.equal(model.reason, 'inconsistent-generation');
  assert.equal(model.bodies[0].q?.id, q.id);
  assert.equal(
    buildTrackingStatusModel(
      { ...changed, generation: '8' },
      { ...connected, previousSnapshot: previous }
    ).bodies[0].q?.id,
    full.id
  );
});
test('malformed known fields and contradictory source catalogs fail closed', () => {
  const good = snapshot();
  const bad: unknown[] = [
    {},
    { ...good, generation: '1e3' },
    { ...good, generation: '9223372036854775808' },
    { ...good, bodies: ['HIP', 'HIP'] },
    { ...good, candidates: null },
    { ...good, candidates: [{ ...q, qUsableNow: 'true' }] },
    { ...good, candidates: [{ ...q, publisher: null }] },
    { ...good, candidates: [q, q] },
    { ...good, candidates: [q, { ...q, id: 'other-id' }] },
    { ...good, candidates: [{ ...q, sixDofSelectable: true }] },
    { ...good, candidates: [{ ...slime, sixDofUsableNow: true }] },
    { ...good, assignments: [{ ...good.assignments[0], manualSix: 'yes' }] },
    { ...good, assignments: [...good.assignments, ...good.assignments] },
    { ...good, candidates: [{ ...full, qUsableNow: false }] },
  ];
  for (const value of bad) {
    const model = buildTrackingStatusModel(value as InputSnapshot, {
      ...connected,
      previousSnapshot: good,
    });
    assert.equal(model.status, 'error', JSON.stringify(value));
    assert.equal(model.bodies[0].q?.id, q.id);
    assert.equal(model.sources[0].qUsableNow, 'unknown');
  }
});
test('unknown origin or capability values stay unknown; additive API fields are ignored', () => {
  const unknownOrigin = buildTrackingStatusModel(
    snapshot([{ ...q, origin: 'future-origin' }]),
    connected
  );
  assert.equal(unknownOrigin.sources[0].capability, 'unknown');
  assert.equal(unknownOrigin.sources[0].qUsableNow, 'unknown');
  const unknownCapability = buildTrackingStatusModel(
    snapshot([{ ...q, capabilities: ['future'], qSelectable: false }]),
    connected
  );
  assert.equal(unknownCapability.sources[0].capability, 'unknown');
  const extended = {
    ...snapshot(),
    savedMode: 'direct',
    activeMode: 'hybrid',
    restartRequired: true,
  };
  assert.equal(buildTrackingStatusModel(extended, connected).status, 'snapshot');
});
test('projection neither mutates input nor retains mutable API arrays', () => {
  const input = snapshot();
  const before = JSON.stringify(input);
  const model = buildTrackingStatusModel(input, connected);
  assert.equal(JSON.stringify(input), before);
  input.candidates[0] = full;
  assert.equal(model.acceptedSnapshot?.candidates[0].id, q.id);
});
