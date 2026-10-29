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

test('review requires a fresh device packet with every affected identity and body info', () => {
  const id = new TrackerIdT(new DeviceIdT(7), 2);
  const pending = new AssignmentTransactions().begin('a', draft, [id]);
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
