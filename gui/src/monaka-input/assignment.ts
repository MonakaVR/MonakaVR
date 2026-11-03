import { BodyPart } from 'solarxr-protocol';
import type { InputAssignment, InputCandidate, InputSnapshot } from './controller.js';

export interface Locator {
  deviceId: { id: number } | null;
  trackerNum: number;
}
export interface LegacyTracker {
  id: Locator;
  body: BodyPart;
  editable: boolean;
}
export type AssignmentCandidate<T> =
  | { kind: 'mvip'; source: InputCandidate }
  | { kind: 'slime'; source: InputCandidate; tracker: T | null };

export function locatorKey(id: Locator | null | undefined): string | null {
  if (
    !id ||
    !Number.isInteger(id.trackerNum) ||
    id.trackerNum < 0 ||
    id.trackerNum > 255
  )
    return null;
  if (id.deviceId === null) return `none:${id.trackerNum}`;
  const device = id.deviceId?.id;
  if (!Number.isInteger(device) || device < 0 || device > 255) return null;
  return `${device}:${id.trackerNum}`;
}

export function presentCandidates<T>(
  candidates: InputCandidate[],
  trackers: T[],
  getId: (tracker: T) => Locator | null
): AssignmentCandidate<T>[] {
  return candidates.map((source) => {
    if (source.origin !== 'slime_orientation_stream') return { kind: 'mvip', source };
    const key = locatorKey(source.slimeTrackerId);
    const matches = key ? trackers.filter((t) => locatorKey(getId(t)) === key) : [];
    return { kind: 'slime', source, tracker: matches.length === 1 ? matches[0] : null };
  });
}

export function sourceOtherBody(snapshot: InputSnapshot, id: string, body: string) {
  return snapshot.assignments.find(
    (row) => row.body !== body && (row.qSource === id || row.sixDofSource === id)
  )?.body;
}

export interface LegacyChange {
  id: Locator;
  from: BodyPart;
  to: BodyPart;
}
export interface AssignmentPlan {
  body: BodyPart;
  before: LegacyTracker[];
  changes: LegacyChange[];
  reset: boolean;
}

function trackedState(trackers: LegacyTracker[], body: BodyPart, keys: Set<string>) {
  return trackers
    .filter((t) => t.body === body || keys.has(locatorKey(t.id) ?? 'invalid'))
    .map((t) => `${locatorKey(t.id)}:${t.body}:${t.editable}`)
    .sort()
    .join('|');
}

export function planAssignment(
  snapshot: InputSnapshot,
  draft: InputAssignment,
  body: BodyPart,
  trackers: LegacyTracker[],
  qExplicit = false
): AssignmentPlan {
  for (const id of [draft.qSource, draft.sixDofSource]) {
    if (id && sourceOtherBody(snapshot, id, draft.body))
      throw new Error(
        'Source is assigned to another body. Explicitly clear that role first.'
      );
  }
  const previous = snapshot.assignments.find((row) => row.body === draft.body);
  const reset = draft.qSource === null && draft.sixDofSource === null;
  const qChanged = draft.qSource !== (previous?.qSource ?? null);
  const changes: LegacyChange[] = [];
  const q = snapshot.candidates.find((row) => row.id === draft.qSource);
  if (!qChanged && !reset && !qExplicit && q?.origin !== 'slime_orientation_stream')
    return { body, before: trackers, changes, reset };
  let selected: LegacyTracker | undefined;
  if (q?.origin === 'slime_orientation_stream') {
    const key = locatorKey(q.slimeTrackerId);
    const matches = key ? trackers.filter((t) => locatorKey(t.id) === key) : [];
    if (matches.length !== 1 || !matches[0].editable)
      throw new Error(
        'Core cannot safely associate this Slime source with one editable tracker. Reload or update Core.'
      );
    selected = matches[0];
  }
  const selectedKey = selected && locatorKey(selected.id);
  for (const tracker of trackers.filter((t) => t.body === body)) {
    if (locatorKey(tracker.id) === selectedKey) continue;
    if (!tracker.editable || locatorKey(tracker.id) === null)
      throw new Error(
        'An existing body tracker cannot be safely reassigned. Review its settings.'
      );
    changes.push({ id: tracker.id, from: body, to: BodyPart.NONE });
  }
  if (selected && selected.body !== body)
    changes.push({ id: selected.id, from: selected.body, to: body });
  const keys = changes.map((c) => locatorKey(c.id));
  if (
    new Set(keys).size !== keys.length ||
    keys.some((key) => trackers.filter((t) => locatorKey(t.id) === key).length !== 1)
  )
    throw new Error('Ambiguous legacy tracker identity. Reload before editing.');
  return { body, before: trackers.map((t) => ({ ...t })), changes, reset };
}

export function legacyUnchanged(plan: AssignmentPlan, current: LegacyTracker[]) {
  const keys = new Set(plan.changes.map((c) => locatorKey(c.id)!));
  return (
    trackedState(plan.before, plan.body, keys) ===
    trackedState(current, plan.body, keys)
  );
}

export function legacyObserved(plan: AssignmentPlan, current: LegacyTracker[]) {
  return plan.changes.every((change) => {
    const matches = current.filter((t) => locatorKey(t.id) === locatorKey(change.id));
    return matches.length === 1 && matches[0].body === change.to;
  });
}

/** Monaka save is durable; SolarXR feed is observed state, never a disk ACK. */
export async function executeAssignment(
  plan: AssignmentPlan,
  io: {
    save: () => Promise<boolean>;
    current: () => LegacyTracker[];
    connected: () => boolean;
    write: (change: LegacyChange) => void;
    observe: () => Promise<boolean>;
  }
): Promise<'saved' | 'legacy-observed' | 'partial' | 'save-unknown'> {
  if (!(await io.save())) return 'save-unknown';
  if (!plan.changes.length) return 'saved';
  if (!io.connected() || !legacyUnchanged(plan, io.current())) return 'partial';
  for (const change of plan.changes) {
    if (!io.connected()) return 'partial';
    io.write(change);
  }
  return (await io.observe()) ? 'legacy-observed' : 'partial';
}
