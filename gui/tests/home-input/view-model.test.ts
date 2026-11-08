import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildHomeInputCatalog,
  HomeInputCatalogReader,
  inputAssignmentsHref,
} from '../../src/monaka-input/home-catalog';
import { source, snapshot } from './fixtures';
import type { InputSnapshot } from '../../src/monaka-input/controller';

test('empty and absent producer need no registration or producer-specific dependency', () => {
  assert.deepEqual(buildHomeInputCatalog(null, false), []);
  assert.deepEqual(buildHomeInputCatalog(snapshot(), true), []);
});
test('two arbitrary publishers follow identical catalog behavior', () => {
  const rows = ['anything-a', 'anything-b'].map(
    (publisher) =>
      buildHomeInputCatalog(
        snapshot([source('not-a-parsed-id', { publisher })]),
        true
      )[0]
  );
  for (const row of rows) {
    assert.equal(row.route, 'mvip');
    assert.equal(row.qUsableNow, true);
    assert.equal(row.sixDofUsableNow, true);
    assert.deepEqual(row.savedRoles, [
      { body: 'LEFT_UPPER_LEG', kind: 'q' },
      { body: 'LEFT_UPPER_LEG', kind: 'six' },
    ]);
  }
});
test('only exact opaque identity deduplicates: same names and source tuples remain separate', () => {
  const candidate = source();
  assert.equal(
    buildHomeInputCatalog(snapshot([candidate, { ...candidate }]), true).length,
    1
  );
  assert.equal(
    buildHomeInputCatalog(snapshot([candidate, source('opaque-b')]), true).length,
    2
  );
});
test('conflicting duplicate identity fails closed, regardless of order', () => {
  const candidates = [
    source(),
    source('opaque-a', { qUsableNow: false, displayName: 'Other' }),
  ];
  for (const input of [candidates, [...candidates].reverse()]) {
    const [row] = buildHomeInputCatalog(snapshot(input), true);
    assert.equal(row.conflict, true);
    assert.equal(row.capabilities, null);
    assert.equal(row.qUsableNow, null);
    assert.equal(row.sixDofUsableNow, null);
    assert.equal(row.candidate.displayName, null);
    assert.equal(row.variants.length, 2);
  }
});
test('static capability survives loss and stale snapshots never imply usability', () => {
  const input = snapshot([
    source('opaque-a', { qUsableNow: false, sixDofUsableNow: false }),
  ]);
  assert.equal(buildHomeInputCatalog(input, true)[0].sixDofUsableNow, false);
  const [stale] = buildHomeInputCatalog(input, false);
  assert.equal(stale.sixDofUsableNow, null);
  assert.deepEqual(stale.capabilities, source().capabilities);
  assert.equal(
    buildHomeInputCatalog(snapshot([source()]), true)[0].sixDofUsableNow,
    true
  );
});
test('legacy inputs remain in their existing cards and future routes are unknown', () => {
  const rows = buildHomeInputCatalog(
    snapshot([
      source('legacy', { origin: 'slime_orientation_stream' }),
      source('future', { origin: 'future-route' }),
    ]),
    true
  );
  assert.equal(rows.length, 1);
  assert.equal(rows[0].route, 'unknown');
  assert.equal(rows[0].qUsableNow, null);
});
test('restart pending uses saved roles, preserves null, never substitutes active refs for missing saved refs', () => {
  const input = snapshot([source('running'), source('saved')], {
    restartRequired: true,
    assignments: [
      {
        body: 'HIP',
        qSource: 'running',
        sixDofSource: 'running',
        savedQSource: null,
        savedSixDofSource: 'saved',
        manualSix: false,
        savedManualSix: true,
        mode: 'direct',
      },
    ],
  });
  const rows = buildHomeInputCatalog(input, true);
  assert.deepEqual(rows[0].savedRoles, []);
  assert.deepEqual(rows[1].savedRoles, [{ body: 'HIP', kind: 'six' }]);
  delete input.assignments[0].savedSixDofSource;
  assert.deepEqual(buildHomeInputCatalog(input, true)[0].savedRoles, []);
});
test('reader only requests snapshots, retains selections on loss and accepts reconnect', async () => {
  const requests: Record<string, unknown>[] = [];
  let fail = false;
  const reader = new HomeInputCatalogReader({
    request: async (fields) => {
      requests.push(fields);
      if (fail) throw new Error('disconnected');
      return snapshot([source()]);
    },
  });
  await reader.load();
  assert.equal(reader.state.fresh, true);
  fail = true;
  await reader.load();
  assert.equal(reader.state.fresh, false);
  assert.equal(reader.state.snapshot?.candidates[0].id, 'opaque-a');
  fail = false;
  await reader.load();
  assert.equal(reader.state.fresh, true);
  reader.stop();
  assert.deepEqual(requests, [
    { action: 'snapshot' },
    { action: 'snapshot' },
    { action: 'snapshot' },
  ]);
});
test('hung snapshot expires; late completion after stop cannot revive state', async () => {
  let resolve: (snapshot: InputSnapshot) => void = () => {};
  let first = true;
  const reader = new HomeInputCatalogReader(
    {
      request: async () => {
        if (first) {
          first = false;
          return snapshot([source()]);
        }
        return new Promise((done) => {
          resolve = done;
        });
      },
    },
    10
  );
  await reader.load();
  const pending = reader.load();
  await new Promise((done) => setTimeout(done, 25));
  assert.equal(reader.state.fresh, false);
  reader.stop();
  resolve(snapshot([source('late')]));
  await pending;
  assert.equal(reader.state.fresh, false);
  assert.equal(reader.state.snapshot?.candidates[0].id, 'opaque-a');
});
test('consumer settings link preserves connection query', () => {
  assert.equal(
    inputAssignmentsHref('?ip=example.test&port=21111'),
    '/settings/monaka-input?ip=example.test&port=21111'
  );
});
