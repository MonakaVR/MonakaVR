import { execFile } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { lstat, open, realpath, rename, unlink } from 'node:fs/promises';
import { createServer } from 'node:net';
import path from 'node:path';
import { promisify } from 'node:util';
import {
  overrideHands,
  overridePaths,
  type OverrideHand,
  type SteamOverrideSnapshot,
} from '../tracking-override-types.js';

const MAX_SETTINGS = 2 * 1024 * 1024;
const SECTION = 'TrackingOverrides';
const digest = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');
const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const notFound = (error: unknown) => (error as NodeJS.ErrnoException).code === 'ENOENT';

async function readBounded(file: string) {
  const metadata = await lstat(file);
  if (!metadata.isFile() || metadata.isSymbolicLink() || metadata.size > MAX_SETTINGS)
    throw new Error('Expected a regular SteamVR settings file of at most 2 MiB');
  const handle = await open(file, 'r');
  try {
    const buffer = Buffer.alloc(MAX_SETTINGS + 1);
    let used = 0;
    while (used < buffer.length) {
      const { bytesRead } = await handle.read(buffer, used, buffer.length - used);
      if (!bytesRead) break;
      used += bytesRead;
    }
    if (used > MAX_SETTINGS) throw new Error('SteamVR settings file exceeds 2 MiB');
    return buffer.subarray(0, used);
  } finally {
    await handle.close();
  }
}

async function writeNew(file: string, bytes: Buffer) {
  const handle = await open(file, 'wx');
  try {
    await handle.writeFile(bytes);
    await handle.sync();
  } finally {
    await handle.close();
  }
}

function parseUtf8(bytes: Buffer) {
  const original = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(
    bytes
  );
  const bom = original.startsWith('\uFEFF') ? '\uFEFF' : '';
  const source = original.slice(bom.length);
  const value: unknown = JSON.parse(source);
  if (!isObject(value)) throw new Error('SteamVR settings must contain a JSON object');
  return { value, source, bom };
}

type Member = { name: string; start: number; valueStart: number; end: number };

/** Locate direct properties in already parsed JSON, so unrelated root values keep their bytes. */
function objectMembers(source: string) {
  const members = new Map<string, Member>();
  let depth = 0;
  let key: { name: string; start: number; valueStart: number } | undefined;
  let atKey = false;
  let closing = 0;
  for (const match of source.matchAll(/"(?:\\[\s\S]|[^"\\])*"|[{}\[\]:,]/g)) {
    const [token] = match;
    const index = match.index;
    if (depth === 1) {
      if (atKey && token.startsWith('"')) {
        key = { name: JSON.parse(token), start: index, valueStart: 0 };
        atKey = false;
      } else if (token === ':' && key) {
        key.valueStart = index + 1;
      } else if (token === ',' || token === '}') {
        if (key) {
          if (members.has(key.name)) throw new Error('Duplicate SteamVR settings key');
          members.set(key.name, { ...key, end: index });
          key = undefined;
        }
        atKey = token === ',';
        closing = index;
      }
    }
    if (token === '{' || token === '[') {
      if (depth === 0) atKey = true;
      depth++;
    } else if (token === '}' || token === ']') depth--;
  }
  return { members, closing };
}

function parseSettings(bytes: Buffer) {
  const { value, source, bom } = parseUtf8(bytes);
  const { members, closing } = objectMembers(source);
  const section = members.get(SECTION);
  const overrides = section ? value[SECTION] : {};
  if (
    !isObject(overrides) ||
    !Object.values(overrides).every((item) => typeof item === 'string')
  )
    throw new Error('SteamVR TrackingOverrides must map device paths to target paths');
  if (section) objectMembers(source.slice(section.valueStart, section.end));
  return {
    source,
    bom,
    closing,
    members,
    section,
    overrides: overrides as Record<string, string>,
  };
}

type Ownership = { hands: OverrideHand[]; createdSection: boolean };
type Journal = {
  version: 1;
  ownership: Ownership;
  pending?: {
    hand: OverrideHand;
    before: string | null;
    after: string | null;
    ownership: Ownership;
  };
};

function isOwnership(value: unknown): value is Ownership {
  return (
    isObject(value) &&
    typeof value.createdSection === 'boolean' &&
    Array.isArray(value.hands) &&
    value.hands.every((hand) => overrideHands.includes(hand)) &&
    new Set(value.hands).size === value.hands.length
  );
}

async function readJournal(file: string): Promise<Journal> {
  const bytes = await readBounded(file).catch((error) => {
    if (notFound(error)) return null;
    throw error;
  });
  if (!bytes) return { version: 1, ownership: { hands: [], createdSection: false } };
  const { value } = parseUtf8(bytes);
  const pending = value.pending;
  if (
    value.version !== 1 ||
    !isOwnership(value.ownership) ||
    (pending !== undefined &&
      (!isObject(pending) ||
        !overrideHands.includes(pending.hand as OverrideHand) ||
        (pending.before !== null &&
          pending.before !== overridePaths(pending.hand as OverrideHand).targetPath) ||
        (pending.after !== null &&
          pending.after !== overridePaths(pending.hand as OverrideHand).targetPath) ||
        pending.before === pending.after ||
        !isOwnership(pending.ownership)))
  ) {
    throw new Error('MonakaVR tracking override recovery record is invalid');
  }
  return value as Journal;
}

function mergedBytes(
  settings: ReturnType<typeof parseSettings>,
  removeSection: boolean
) {
  const { source, bom, section, overrides, closing, members } = settings;
  const replacement = JSON.stringify(overrides, null, 2).replaceAll('\n', '\n  ');
  let merged: string;
  if (!section) {
    merged =
      source.slice(0, closing) +
      (members.size ? ',' : '') +
      '\n  "' +
      SECTION +
      '": ' +
      replacement +
      '\n' +
      source.slice(closing);
  } else if (removeSection) {
    const entries = [...members.values()];
    const index = entries.indexOf(section);
    const previous = entries[index - 1];
    // Drop the preceding comma if this was the final property; otherwise drop its following comma.
    const start = !entries[index + 1] && previous ? previous.end : section.start;
    const end = entries[index + 1] ? section.end + 1 : section.end;
    merged = source.slice(0, start) + source.slice(end);
  } else {
    const originalValue = source.slice(section.valueStart, section.end);
    const leading = originalValue.match(/^\s*/)?.[0] ?? '';
    const trailing = originalValue.match(/\s*$/)?.[0] ?? '';
    merged =
      source.slice(0, section.valueStart) +
      leading +
      replacement +
      trailing +
      source.slice(section.end);
  }
  return Buffer.from(bom + merged);
}

/** Exclusive OS lease. The pipe accepts no messages and Windows releases it on process exit. */
async function acquireSettingsLease(file: string) {
  const key = digest(Buffer.from(file.toLowerCase()));
  const address = String.raw`\\.\pipe\MonakaVR.TrackingOverrides.${key}`;
  const server = createServer((socket) => socket.destroy());
  await new Promise<void>((resolve, reject) => {
    server.once('error', (error) =>
      reject(
        (error as NodeJS.ErrnoException).code === 'EADDRINUSE'
          ? new Error('Another MonakaVR desktop is updating these SteamVR settings')
          : error
      )
    );
    server.listen(address, resolve);
  });
  return () => new Promise<void>((resolve) => server.close(() => resolve()));
}

export class SteamTrackingOverrides {
  constructor(
    private readonly settingsPath: () => Promise<string>,
    private readonly assertSteamVRStopped: () => Promise<void>
  ) {}

  private async locate() {
    const configured = await this.settingsPath();
    return path.join(
      await realpath(path.dirname(configured)),
      path.basename(configured)
    );
  }

  private async read(file: string) {
    const journalFile = file + '.monaka-tracking-overrides.json';
    const [bytes, journal] = await Promise.all([
      readBounded(file),
      readJournal(journalFile),
    ]);
    const settings = parseSettings(bytes);
    const hash = digest(bytes);
    let ownership = journal.ownership;
    if (journal.pending) {
      const { devicePath } = overridePaths(journal.pending.hand);
      const value = Object.hasOwn(settings.overrides, devicePath)
        ? settings.overrides[devicePath]
        : null;
      if (value === journal.pending.after) ownership = journal.pending.ownership;
      else if (value !== journal.pending.before) {
        // An outside writer changed the key itself. Preserve it as a conflict and
        // retain independently owned hands so the PC recovery action still works.
        ownership = {
          ...ownership,
          hands: ownership.hands.filter((hand) => hand !== journal.pending?.hand),
        };
      }
    }
    const hands = Object.fromEntries(
      overrideHands.map((hand) => {
        const { devicePath, targetPath } = overridePaths(hand);
        const value = settings.overrides[devicePath];
        const owned = ownership.hands.includes(hand) && value === targetPath;
        const foreignTarget = Object.entries(settings.overrides).some(
          ([key, target]) => key !== devicePath && target === targetPath
        );
        return [
          hand,
          { owned, conflict: (value !== undefined && !owned) || foreignTarget },
        ];
      })
    ) as SteamOverrideSnapshot['hands'];
    const token = digest(Buffer.from(JSON.stringify([file, hash, journal])));
    return {
      file,
      journalFile,
      bytes,
      settings,
      ownership,
      snapshot: { token, hands },
    };
  }

  async snapshot(): Promise<SteamOverrideSnapshot> {
    return (await this.read(await this.locate())).snapshot;
  }

  async change(
    action: 'apply' | 'remove',
    hand: OverrideHand,
    token: string
  ): Promise<SteamOverrideSnapshot> {
    const file = await this.locate();
    const release = await acquireSettingsLease(file);
    try {
      const current = await this.read(file);
      const { journalFile, bytes, settings, ownership, snapshot } = current;
      if (snapshot.token !== token)
        throw new Error('SteamVR settings changed. Reload before applying.');
      const state = snapshot.hands[hand];
      if (action === 'apply' && state.conflict)
        throw new Error('Another override already owns this source path or target');
      if (action === 'remove' && !state.owned && state.conflict)
        throw new Error('This override is owned outside MonakaVR');
      if (state.owned === (action === 'apply')) return snapshot;
      await this.assertSteamVRStopped();
      const { devicePath, targetPath } = overridePaths(hand);
      const before = settings.overrides[devicePath] ?? null;
      if (action === 'apply') settings.overrides[devicePath] = targetPath;
      else delete settings.overrides[devicePath];
      const next: Ownership = {
        hands: overrideHands.filter((side) =>
          side === hand ? action === 'apply' : snapshot.hands[side].owned
        ),
        createdSection: ownership.createdSection || !settings.section,
      };
      const removeSection =
        next.createdSection && Object.keys(settings.overrides).length === 0;
      if (removeSection) next.createdSection = false;
      const merged = mergedBytes(settings, removeSection);
      if (merged.length > MAX_SETTINGS)
        throw new Error('Merged SteamVR settings exceed 2 MiB');
      parseSettings(merged);
      const suffix = randomUUID();
      const stage = file + '.monaka-' + suffix + '.tmp';
      const journalStage = journalFile + '.' + suffix + '.tmp';
      const backup = file + '.pre-monaka-override-' + suffix + '.bak';
      const journal = (value: Journal) => Buffer.from(JSON.stringify(value) + '\n');
      const pending = {
        hand,
        before,
        after: settings.overrides[devicePath] ?? null,
        ownership: next,
      };
      let pendingWritten = false;
      let moved = false;
      try {
        await writeNew(backup, bytes);
        await writeNew(stage, merged);
        await writeNew(journalStage, journal({ version: 1, ownership, pending }));
        if (
          (await this.read(file)).snapshot.token !== token ||
          (await this.locate()) !== file
        )
          throw new Error('SteamVR settings changed before commit. Reload.');
        await rename(journalStage, journalFile);
        pendingWritten = true;
        await this.assertSteamVRStopped();
        if (!(await readBounded(file)).equals(bytes) || (await this.locate()) !== file)
          throw new Error('SteamVR settings changed before commit. Reload.');
        await rename(stage, file);
        moved = true;
        await writeNew(journalStage, journal({ version: 1, ownership: next }));
        await rename(journalStage, journalFile);
        return (await this.read(file)).snapshot;
      } catch (error) {
        if (pendingWritten && !moved) {
          await writeNew(journalStage, journal({ version: 1, ownership }))
            .then(() => rename(journalStage, journalFile))
            .catch(() => {});
        }
        throw error;
      } finally {
        await Promise.all([
          unlink(stage).catch(() => {}),
          unlink(journalStage).catch(() => {}),
        ]);
      }
    } finally {
      await release();
    }
  }
}

export async function windowsSteamVRSettingsPath(env: NodeJS.ProcessEnv = process.env) {
  let config = env.VR_CONFIG_PATH;
  if (!config) {
    const registry =
      env.VR_PATHREG_OVERRIDE ||
      (env.LOCALAPPDATA && path.join(env.LOCALAPPDATA, 'openvr', 'openvrpaths.vrpath'));
    if (!registry || !path.isAbsolute(registry))
      throw new Error('OpenVR path registry is unavailable');
    const { value } = parseUtf8(await readBounded(registry));
    config =
      Array.isArray(value.config) && typeof value.config[0] === 'string'
        ? value.config[0]
        : undefined;
  }
  if (!config || !path.isAbsolute(config))
    throw new Error('SteamVR has no registered configuration folder');
  return path.join(config, 'steamvr.vrsettings');
}

export async function assertWindowsSteamVRStopped() {
  const root = process.env.SystemRoot;
  if (!root || !path.isAbsolute(root))
    throw new Error('Cannot verify whether SteamVR is stopped');
  const { stdout } = await promisify(execFile)(
    path.join(root, 'System32', 'tasklist.exe'),
    ['/FO', 'CSV', '/NH'],
    {
      windowsHide: true,
      timeout: 4000,
      maxBuffer: 2 * 1024 * 1024,
    }
  );
  if (!/^"[^"]+","[0-9]+",/m.test(stdout))
    throw new Error('Cannot verify whether SteamVR is stopped');
  if (/^"(?:vrserver|vrmonitor|vrcompositor|vrstartup)\.exe",/im.test(stdout))
    throw new Error(
      'Close SteamVR on the PC before applying or removing tracking overrides'
    );
}
