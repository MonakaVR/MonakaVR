import { test, expect, type Page } from '@playwright/test';
import { CoreUiFixture, candidate, forbidBridgeApi } from './fixtures';
import { Builder, ByteBuffer } from 'flatbuffers';
import {
  AssignTrackerRequestT,
  BodyPart,
  DataFeedMessage,
  DataFeedMessageHeaderT,
  DataFeedUpdateT,
  DeviceDataT,
  DeviceIdT,
  MessageBundle,
  MessageBundleT,
  RpcMessage,
  RpcMessageHeaderT,
  TapDetectionSetupNotificationT,
  TrackerIdT,
  TrackerDataT,
  TrackerInfoT,
} from 'solarxr-protocol';

async function open(page: Page, fixture: CoreUiFixture, language = 'en') {
  await fixture.install(page, language);
  await forbidBridgeApi(page);
  await page.addInitScript(() => {
    const config = JSON.parse(localStorage.getItem('config.json')!);
    localStorage.setItem(
      'config.json',
      JSON.stringify({ ...config, assignMode: 'all' })
    );
  });
  await page.goto('/?ip=127.0.0.1&port=29992#/onboarding/trackers-assign');
  await expect.poll(() => fixture.requests.length).toBeGreaterThan(0);
  await page.locator('.control#HIP').click();
  await expect(page.getByTestId('unified-assignment')).toBeVisible();
}
const qName = 'Q Source: device-publisher-a';
const sixName = '6DoF Source: device-publisher-a';

test('MVIP picker offers live identities and retains offline choices only on their saved body', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  const previous = {
    ...candidate('previous-identity'),
    publisher: 'publisher-a',
    displayName: 'Shared device',
  };
  const current = {
    ...previous,
    id: 'current-identity',
    sixDofUsableNow: false,
  };
  const otherPublisher = {
    ...previous,
    id: 'independent-identity',
    publisher: 'publisher-b',
  };
  const offline = (source: typeof previous) => ({
    ...source,
    qUsableNow: false,
    sixDofUsableNow: false,
  });
  fixture.snapshot.candidates = [previous];
  await open(page, fixture);
  const picker = page.getByTestId('unified-assignment');
  const row = (id: string) =>
    picker.getByRole('row').filter({ has: page.locator(`[title$="Identity: ${id}"]`) });
  const q = (id: string) => row(id).getByRole('radio', { name: /^Q Source:/ });
  const six = (id: string) => row(id).getByRole('radio', { name: /^6DoF Source:/ });
  const save = picker.getByRole('button', { name: 'Save input choices', exact: true });
  const readback = picker.getByTestId('assignment-readback');

  await expect(q(previous.id)).toHaveAccessibleName('Q Source: Shared device');
  await q(previous.id).check();
  await expect(six(previous.id)).toBeChecked();

  fixture.snapshot.candidates = [offline(previous), current];
  await expect(row(previous.id)).toHaveCount(0);
  await expect(q(current.id)).toHaveAccessibleName('Q Source: Shared device');
  await expect(six(current.id)).toBeEnabled();
  await expect(save).toBeDisabled();
  await expect(picker.getByText(/A newly selected input is unavailable/)).toBeVisible();
  expect(fixture.saves).toHaveLength(0);

  fixture.snapshot.candidates.push(otherPublisher);
  await expect(q(current.id)).toHaveAccessibleName('Q Source: Shared device · publisher-a');
  await expect(q(otherPublisher.id)).toHaveAccessibleName('Q Source: Shared device · publisher-b');
  await picker.getByRole('button', { name: 'Cancel input changes', exact: true }).click();
  await q(current.id).check();
  await expect(six(current.id)).toBeChecked();
  await save.click();
  await expect(readback).toHaveAttribute('title', /Identity: current-identity/);
  expect(fixture.saves[0]).toMatchObject({ qSource: current.id, sixDofSource: current.id });

  fixture.snapshot.candidates = [offline(previous), offline(current), otherPublisher];
  await expect(row(current.id)).toContainText('Saved source offline');
  await expect(q(current.id)).toBeChecked();
  await expect(q(current.id)).toBeDisabled();
  await expect(q(otherPublisher.id)).toBeEnabled();
  const close = picker.getByRole('button', { name: 'Close', exact: true });
  await close.click();
  await page.locator('.control#CHEST').click();
  await expect(row(current.id)).toHaveCount(0);
  await expect(q(otherPublisher.id)).toHaveAccessibleName('Q Source: Shared device');
  await close.click();
  await page.locator('.control#HIP').click();

  fixture.snapshot.candidates = [offline(previous), otherPublisher];
  await expect(readback).toHaveAttribute('title', /Saved identity: current-identity/);
  await expect(readback).toContainText('Saved source offline');
  fixture.snapshot.candidates = [offline(previous), current, otherPublisher];
  await expect(q(current.id)).toBeEnabled();
  await expect(q(current.id)).toBeChecked();
  await expect(readback).not.toContainText('Saved source offline');

  fixture.snapshot.candidates = [offline(previous), offline(current), otherPublisher];
  await expect(q(current.id)).toBeDisabled();
  await picker.getByRole('radio', { name: 'Q Source: Unassigned', exact: true }).check();
  await save.click();
  await expect(save).toBeDisabled();
  expect(fixture.saves[1]).toMatchObject({ qSource: null, sixDofSource: current.id });
  await picker.getByRole('radio', { name: '6DoF Source: Unassigned', exact: true }).check();
  await save.click();
  await picker.getByRole('button', { name: 'Confirm and save', exact: true }).click();
  await expect(row(current.id)).toHaveCount(0);
  expect(fixture.saves[2]).toMatchObject({ qSource: null, sixDofSource: null });
});

test('inherited assignment is visible without creating or saving an override', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  const id = new TrackerIdT(new DeviceIdT(7), 2);
  const source = fixture.snapshot.candidates.find((c) => c.id === 'slime-q')!;
  source.slimeTrackerId = id;
  source.capabilities = ['position', 'orientation'];
  source.sixDofSelectable = true;
  await open(page, fixture, 'ja');
  const info = new TrackerInfoT();
  info.editable = true;
  info.bodyPart = BodyPart.HIP;
  info.displayName = 'physical fixture';
  const device = new DeviceDataT();
  device.id = id.deviceId;
  device.trackers = [new TrackerDataT(id, info)];
  const header = new DataFeedMessageHeaderT();
  header.messageType = DataFeedMessage.DataFeedUpdate;
  header.message = new DataFeedUpdateT([device], [], [], null, 0);
  const message = new MessageBundleT();
  message.dataFeedMsgs = [header];
  const builder = new Builder(128);
  builder.finish(message.pack(builder));
  fixture.binaryConnections.forEach((socket) =>
    socket.send(Buffer.from(builder.asUint8Array()))
  );
  await expect(page.getByTestId('assignment-inherited')).toContainText(
    '現在の割り当て'
  );
  await expect(page.getByTestId('assignment-readback')).toHaveCount(0);
  expect(fixture.snapshot.assignments).toEqual([]);
  expect(fixture.saves).toHaveLength(0);
});

test('a later partial operation reviews its current body after recovering a different body', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  await fixture.install(page);
  await forbidBridgeApi(page);
  await page.addInitScript(() => {
    const config = JSON.parse(localStorage.getItem('config.json')!);
    localStorage.setItem(
      'config.json',
      JSON.stringify({ ...config, assignMode: 'all' })
    );
    sessionStorage.setItem(
      'monaka-assignment-review-v1:ws://127.0.0.1:29992/',
      JSON.stringify({
        token: 'prior-chest-owner',
        body: 'CHEST',
        requested: {
          body: 'CHEST',
          qSource: null,
          sixDofSource: null,
          manualSix: false,
          mode: 'ik',
        },
        legacyIds: [],
      })
    );
  });
  await page.goto('/?ip=127.0.0.1&port=29992#/onboarding/trackers-assign');
  const modal = page.getByTestId('unified-assignment');
  await expect(modal.getByText(/Partial or unconfirmed/)).toBeVisible();
  const header = new DataFeedMessageHeaderT();
  header.messageType = DataFeedMessage.DataFeedUpdate;
  header.message = new DataFeedUpdateT();
  const message = new MessageBundleT();
  message.dataFeedMsgs = [header];
  const builder = new Builder(128);
  builder.finish(message.pack(builder));
  const timer = setInterval(
    () =>
      fixture.binaryConnections.forEach((socket) =>
        socket.send(Buffer.from(builder.asUint8Array()))
      ),
    100
  );
  try {
    await modal
      .getByRole('button', { name: 'Reload input choices', exact: true })
      .click();
    await expect(modal.getByRole('radio', { name: qName, exact: true })).toBeEnabled();
  } finally {
    clearInterval(timer);
  }
  await modal.getByRole('button', { name: 'Close', exact: true }).click();
  await page.locator('.control#HIP').click();
  await modal.getByRole('radio', { name: qName, exact: true }).check();
  fixture.snapshot.generation = '1';
  await modal.getByRole('button', { name: 'Save input choices', exact: true }).click();
  await expect(modal.getByRole('alert')).toContainText('generation');
  await expect(modal.getByRole('radio', { name: qName, exact: true })).toBeDisabled();
  const recoveryBody = await page.evaluate(
    () =>
      JSON.parse(
        sessionStorage.getItem('monaka-assignment-review-v1:ws://127.0.0.1:29992/')!
      ).body
  );
  expect(recoveryBody).toBe('HIP');
  await expect(page.getByText(/hip\?$/)).toBeVisible();
  await expect(page.getByText(/chest\?$/)).toHaveCount(0);
});

test('normal body picker saves Pos+Q and renders the actual body summary without a settings page', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await open(page, fixture);
  await page.getByRole('radio', { name: qName, exact: true }).check();
  await expect(page.getByRole('radio', { name: sixName, exact: true })).toBeChecked();
  await page.getByRole('button', { name: 'Save input choices', exact: true }).click();
  await expect(page.getByTestId('assignment-readback')).toContainText('publisher-a');
  expect(fixture.saves).toHaveLength(1);
  await page.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(page.getByTestId('assignment-summary-HIP')).toContainText('publisher-a');
  expect(page.url()).toContain('/onboarding/trackers-assign');
  expect(errors).toEqual([]);
});

test('pending two-writer recovery survives actual Layout disconnect/remount and page reload', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  const id = new TrackerIdT(new DeviceIdT(7), 2);
  fixture.snapshot.candidates.find((c) => c.id === 'slime-q')!.slimeTrackerId = id;
  fixture.snapshot.assignments = [
    {
      body: 'HIP',
      qSource: 'slime-q',
      sixDofSource: 'publisher-b',
      manualSix: true,
      mode: 'ik',
    },
  ];
  const feed = (index = 0, complete = true) => {
    const info = new TrackerInfoT();
    info.editable = true;
    info.bodyPart = BodyPart.HIP;
    info.displayName = 'physical fixture';
    const device = new DeviceDataT();
    device.id = id.deviceId;
    device.trackers = [new TrackerDataT(id, info)];
    const header = new DataFeedMessageHeaderT();
    header.messageType = DataFeedMessage.DataFeedUpdate;
    header.message = new DataFeedUpdateT(complete ? [device] : [], [], [], null, index);
    const message = new MessageBundleT();
    message.dataFeedMsgs = [header];
    const builder = new Builder(128);
    builder.finish(message.pack(builder));
    fixture.binaryConnections.forEach((socket) =>
      socket.send(Buffer.from(builder.asUint8Array()))
    );
  };
  await open(page, fixture);
  feed();
  await expect(page.getByTestId('unified-assignment')).toContainText(
    'Physical body observed'
  );
  await page.getByRole('radio', { name: qName, exact: true }).check();
  expect(
    await page.evaluate(() =>
      Object.keys(sessionStorage).filter((key) =>
        key.startsWith('monaka-assignment-review-v1:')
      )
    )
  ).toHaveLength(0);
  await page.getByRole('button', { name: 'Save input choices', exact: true }).click();
  fixture.afterSave = () => {
    fixture.binaryConnections.forEach((socket) => socket.close());
    fixture.binaryConnections.clear();
    fixture.afterSave = undefined;
    return true; // Core saved; no response before the real binary close unmounts Layout.
  };
  await page.getByRole('button', { name: 'Confirm and save', exact: true }).click();
  await expect(page.getByTestId('unified-assignment')).toHaveCount(0);
  await expect(page.getByTestId('unified-assignment')).toBeVisible({ timeout: 10000 });
  const modal = page.getByTestId('unified-assignment');
  await expect(modal.getByText(/Partial or unconfirmed/)).toBeVisible();
  await expect(modal.getByRole('radio', { name: qName, exact: true })).toBeDisabled();
  const warning = modal.getByTestId('assignment-recovery-warning');
  await expect(warning).toBeVisible();
  const panelBox = (await modal.boundingBox())!;
  const warningBox = (await warning.boundingBox())!;
  expect(warningBox.y - panelBox.y).toBeLessThan(40);
  await page.screenshot({ path: 'build/r4-recovery-warning.png', fullPage: true });
  expect(fixture.snapshot.assignments[0].qSource).toBe('publisher-a');
  expect(
    fixture.binaryRequests
      .flatMap(
        (bytes) =>
          MessageBundle.getRootAsMessageBundle(new ByteBuffer(bytes)).unpack().rpcMsgs
      )
      .filter((rpc) => rpc.messageType === RpcMessage.AssignTrackerRequest)
  ).toHaveLength(0);
  fixture.binaryConnections.clear();
  await page.reload();
  await expect(modal.getByText(/Partial or unconfirmed/)).toBeVisible();
  await expect(modal.getByRole('radio', { name: qName, exact: true })).toBeDisabled();
  fixture.snapshotError = true;
  await modal
    .getByRole('button', { name: 'Reload input choices', exact: true })
    .click();
  await expect(modal.getByRole('alert')).toContainText('unavailable');
  await expect(modal.getByRole('radio', { name: qName, exact: true })).toBeDisabled();
  fixture.snapshotError = false;
  const incompleteFeed = setInterval(() => {
    feed(1);
    feed(0, false);
  }, 100);
  try {
    await modal
      .getByRole('button', { name: 'Reload input choices', exact: true })
      .click();
    await expect(
      modal.getByRole('button', { name: 'Reload input choices', exact: true })
    ).toBeEnabled({ timeout: 5000 });
    await expect(modal.getByRole('radio', { name: qName, exact: true })).toBeDisabled();
  } finally {
    clearInterval(incompleteFeed);
  }
  const feedTimer = setInterval(feed, 100);
  try {
    await modal
      .getByRole('button', { name: 'Reload input choices', exact: true })
      .click();
    await expect(modal.getByRole('radio', { name: qName, exact: true })).toBeEnabled();
    await expect(modal.getByText(/Partial or unconfirmed/)).toHaveCount(0);
    expect(fixture.saves).toHaveLength(1);
  } finally {
    clearInterval(feedTimer);
  }
});

test('Q-only and old-Core Slime association fail closed while manual clear survives metadata reload', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  await open(page, fixture);
  await expect(
    page.getByRole('radio', {
      name: 'Q Source: device-slime-q',
      exact: true,
    })
  ).toBeDisabled();
  await page
    .getByRole('radio', {
      name: 'Q Source: device-q-only',
      exact: true,
    })
    .check();
  await expect(
    page.getByRole('radio', {
      name: '6DoF Source: device-q-only',
      exact: true,
    })
  ).toBeDisabled();
  await page.getByRole('radio', { name: sixName, exact: true }).check();
  await page
    .getByRole('radio', { name: '6DoF Source: Unassigned', exact: true })
    .check();
  await page.getByRole('button', { name: 'Save input choices', exact: true }).click();
  await page.getByRole('button', { name: 'Reload input choices', exact: true }).click();
  await expect(
    page.getByRole('radio', { name: '6DoF Source: Unassigned', exact: true })
  ).toBeChecked();
  expect(fixture.snapshot.assignments[0].manualSix).toBe(true);
});

test('full reset requires explicit confirmation and stale generations never show success', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  fixture.snapshot.assignments = [
    {
      body: 'HIP',
      qSource: 'publisher-a',
      sixDofSource: 'publisher-a',
      manualSix: false,
      mode: 'ik',
    },
  ];
  await open(page, fixture);
  await page.getByRole('radio', { name: 'Q Source: Unassigned', exact: true }).check();
  await page
    .getByRole('radio', { name: '6DoF Source: Unassigned', exact: true })
    .check();
  await page.getByRole('button', { name: 'Save input choices', exact: true }).click();
  await expect(page.getByRole('alertdialog')).toContainText(
    'resets its selection history'
  );
  expect(fixture.saves).toHaveLength(0);
  fixture.snapshot.generation = '2';
  await page.getByRole('button', { name: 'Confirm and save', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('generation');
  await expect(page.getByRole('status')).toContainText('No physical body write');
  expect(fixture.snapshot.assignments[0].qSource).toBe('publisher-a');
});

test('Japanese normal picker remains operable at narrow width', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const fixture = new CoreUiFixture();
  await open(page, fixture, 'ja');
  await page
    .getByRole('radio', {
      name: 'Q入力元: device-publisher-a',
      exact: true,
    })
    .check();
  await page.getByRole('button', { name: '入力の選択を保存', exact: true }).click();
  await expect(page.getByTestId('assignment-readback')).toContainText('publisher-a');
  await page.screenshot({ path: 'build/r4-unified-mobile.png', fullPage: true });
});

test('unsupported input role keeps actual legacy tap assignment and never writes the previous Core body', async ({
  page,
}) => {
  const fixture = new CoreUiFixture();
  fixture.snapshot.bodies = ['CHEST'];
  await fixture.install(page);
  await page.addInitScript(() => {
    const config = JSON.parse(localStorage.getItem('config.json')!);
    localStorage.setItem(
      'config.json',
      JSON.stringify({ ...config, assignMode: 'all' })
    );
  });
  await page.goto('/?ip=127.0.0.1&port=29992#/onboarding/trackers-assign');
  await expect.poll(() => fixture.requests.length).toBeGreaterThan(0);
  await page.locator('.control#HIP').click();
  await expect(page.getByTestId('unified-assignment')).toHaveCount(0);
  await expect(page.getByRole('status')).toContainText('Legacy tracker operation only');
  page.on('dialog', (dialog) => dialog.accept());
  const trackerId = new TrackerIdT(new DeviceIdT(7), 2);
  const message = new MessageBundleT();
  const header = new RpcMessageHeaderT();
  header.messageType = RpcMessage.TapDetectionSetupNotification;
  header.message = new TapDetectionSetupNotificationT(trackerId);
  message.rpcMsgs = [header];
  const builder = new Builder(128);
  builder.finish(message.pack(builder));
  fixture.connections.forEach((socket) =>
    socket.send(Buffer.from(builder.asUint8Array()))
  );
  const assignments = () =>
    fixture.binaryRequests.flatMap((bytes) =>
      MessageBundle.getRootAsMessageBundle(new ByteBuffer(bytes))
        .unpack()
        .rpcMsgs.filter((rpc) => rpc.messageType === RpcMessage.AssignTrackerRequest)
        .map((rpc) => rpc.message as AssignTrackerRequestT)
    );
  await expect.poll(() => assignments().length).toBe(1);
  expect(assignments()[0].bodyPosition).toBe(BodyPart.HIP);
  expect(assignments()[0].trackerId).toEqual(trackerId);
  expect(fixture.saves).toHaveLength(0);
});
