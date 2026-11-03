import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  AssignmentTransactions,
  assignmentReviewFeed,
} from '../../src/monaka-input/transactions.js';
import {
  BodyPart,
  DataFeedUpdateT,
  DeviceDataT,
  DeviceIdT,
  TrackerDataT,
  TrackerIdT,
  TrackerInfoT,
} from 'solarxr-protocol';

const draft = {
  body: 'HIP',
  qSource: 'a',
  sixDofSource: 'b',
  manualSix: true,
  mode: 'ik',
};
const storage = () => {
  const data = new Map<string, string>();
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => {
      data.set(key, value);
    },
    removeItem: (key: string) => {
      data.delete(key);
    },
  };
};
test('session recovery survives owner recreation and never crosses endpoints', () => {
  const persistent = storage();
  const first = new AssignmentTransactions(persistent);
  assert.equal(first.read('ws://a/'), null);
  const pending = first.begin('ws://a/', draft);
  const remounted = new AssignmentTransactions(persistent);
  assert.deepEqual(remounted.read('ws://a/'), pending);
  assert.equal(remounted.read('ws://b/'), null);
  assert.equal(remounted.clear('ws://b/', pending.token), false);
});
test('late save owner cannot erase review or a later transaction', () => {
  const owner = new AssignmentTransactions(storage());
  const first = owner.begin('a', draft);
  assert.equal(
    owner.clear('a', first.token),
    false,
    'in-flight reconciliation prohibited'
  );
  owner.settled(first.token);
  assert.ok(owner.read('a'), 'settling alone cannot clear unknown outcome');
  assert.equal(owner.clear('a', first.token), true);
  const next = owner.begin('a', draft);
  owner.settled(first.token);
  assert.equal(owner.clear('a', first.token), false);
  assert.equal(owner.read('a')?.token, next.token);
});
test('storage failure falls back to module-owned memory for route remount', () => {
  const owner = new AssignmentTransactions({
    getItem() {
      throw new Error('denied');
    },
    setItem() {
      throw new Error('denied');
    },
    removeItem() {
      throw new Error('denied');
    },
  });
  const pending = owner.begin('a', draft);
  assert.equal(owner.read('a'), pending);
  owner.settled(pending.token);
  assert.equal(owner.clear('a', pending.token), true);
  assert.equal(owner.read('a'), null);
});

test('physical assignment requires exact recovery persistence before either writer', () => {
  const id = new TrackerIdT(new DeviceIdT(7), 2);
  const working = storage();
  const cases = [
    undefined,
    {
      ...working,
      setItem() {
        throw new Error('quota');
      },
    },
    {
      ...working,
      setItem() {},
      getItem() {
        return null;
      },
    },
    {
      ...working,
      setItem() {},
      getItem() {
        return 'mismatched';
      },
    },
    {
      ...working,
      getItem() {
        throw new Error('denied');
      },
    },
  ];
  for (const unavailable of cases) {
    const owner = new AssignmentTransactions(unavailable);
    let writes = 0;
    assert.throws(() => {
      owner.begin('blocked', draft, [id]);
      writes++; // The caller can only reach Core/save and legacy dispatch after begin.
    }, /Recovery storage unavailable/);
    assert.equal(writes, 0);
    assert.equal(owner.isActive('blocked'), false, 'no phantom in-flight writer');
  }
});

test('verified physical recovery marker survives owner recreation before writes', () => {
  const persistent = storage();
  const owner = new AssignmentTransactions(persistent);
  const id = new TrackerIdT(new DeviceIdT(7), 2);
  const pending = owner.begin('a', draft, [id]);
  assert.deepEqual(
    new AssignmentTransactions(persistent).read('a'),
    JSON.parse(JSON.stringify(pending))
  );
  assert.equal(owner.isActive('a'), true);
});

test('uncertain recovery writes retain review evidence without starting a writer', () => {
  const id = new TrackerIdT(new DeviceIdT(7), 2);
  for (const failure of ['write', 'readback']) {
    const persistent = storage();
    let written = false;
    const uncertain = {
      ...persistent,
      setItem(key: string, value: string) {
        persistent.setItem(key, value);
        written = true;
        if (failure === 'write') throw new Error('failed after storing');
      },
      getItem(key: string) {
        if (written && failure === 'readback') throw new Error('readback denied');
        return persistent.getItem(key);
      },
    };
    const owner = new AssignmentTransactions(uncertain);
    assert.throws(() => owner.begin('a', draft, [id]), /Recovery storage unavailable/);
    assert.equal(owner.isActive('a'), false);
    const pending = owner.read('a');
    assert.ok(pending, 'current renderer retains review evidence');
    assert.deepEqual(
      new AssignmentTransactions(persistent).read('a'),
      JSON.parse(JSON.stringify(pending)),
      'persisted marker remains available after renderer recreation'
    );
  }
});

test('review requires a fresh device packet with every affected identity and body info', () => {
  const id = new TrackerIdT(new DeviceIdT(7), 2);
  const pending = new AssignmentTransactions(storage()).begin('a', draft, [id]);
  const packet = new DataFeedUpdateT();
  assert.equal(assignmentReviewFeed(pending, packet), false, 'empty device feed');
  const device = new DeviceDataT();
  device.trackers = [new TrackerDataT(id)];
  packet.devices = [device];
  assert.equal(assignmentReviewFeed(pending, packet), false, 'pose-only tracker');
  const info = new TrackerInfoT();
  info.bodyPart = BodyPart.HIP;
  device.trackers[0].info = info;
  packet.index = 1;
  assert.equal(assignmentReviewFeed(pending, packet), false, 'bone feed');
  packet.index = 0;
  assert.equal(
    assignmentReviewFeed(pending, packet),
    true,
    'current physical body can be reviewed'
  );
  device.trackers.push(device.trackers[0]);
  assert.equal(assignmentReviewFeed(pending, packet), false, 'duplicate identity');
});
