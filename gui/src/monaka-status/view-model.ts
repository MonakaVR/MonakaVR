import type {
  InputAssignment,
  InputCandidate,
  InputSnapshot,
} from '@/monaka-input/controller';

/** The host owns connection health, request failure and snapshot cadence. */
export type TrackingConnection =
  | 'connected'
  | 'offline'
  | 'disabled'
  | 'loopback-denied'
  | 'error'
  | 'unknown';
export type Capability =
  | 'position-orientation'
  | 'orientation-only'
  | 'conflicting'
  | 'unknown';
export type Usability = 'yes' | 'no' | 'unknown';
export type TrackingStatusReason =
  | TrackingConnection
  | 'missing-snapshot'
  | 'invalid-snapshot'
  | 'inconsistent-sources'
  | 'stale-generation'
  | 'inconsistent-generation';

export interface SourceStatus {
  id: string;
  publisher: string | null;
  source: string | null;
  tracker: string | null;
  origin: string | null;
  capability: Capability;
  qSelectable: boolean | null;
  sixDofSelectable: boolean | null;
  qUsableNow: Usability;
  sixDofUsableNow: Usability;
  availability: 'snapshot' | 'saved-offline';
}
export interface BodyStatus extends InputAssignment {
  q: SourceStatus | null;
  sixDof: SourceStatus | null;
}
export interface TrackingStatusModel {
  status: 'snapshot' | 'offline' | 'unknown' | 'error';
  reason: TrackingStatusReason;
  generation: string | null;
  retainedGeneration: string | null;
  bodies: BodyStatus[];
  sources: SourceStatus[];
  /** Validated, detached data; safe for the host to retain across failures. */
  acceptedSnapshot: InputSnapshot | null;
  /** Cached assignment data only. Does not establish current source usability. */
  retainedSnapshot: InputSnapshot | null;
}
export interface TrackingStatusContext {
  connection: TrackingConnection;
  previousSnapshot?: InputSnapshot | null;
  /** A generation known by the host, in the same Core connection lifetime. */
  minimumGeneration?: string | null;
}

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const nonempty = (value: unknown): value is string =>
  typeof value === 'string' && value.trim().length > 0;
const nullableId = (value: unknown): value is string | null =>
  value === null || nonempty(value);
const generation = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^(0|[1-9][0-9]{0,18})$/.test(value) &&
  BigInt(value) <= 9223372036854775807n;

function capability(candidate: InputCandidate): Capability {
  if (candidate.conflict) return 'conflicting';
  const components = candidate.capabilities;
  if (!components || components.some((x) => !['position', 'orientation'].includes(x)))
    return 'unknown';
  if (components.includes('orientation'))
    return components.includes('position')
      ? 'position-orientation'
      : 'orientation-only';
  return 'unknown';
}

function parseSnapshot(value: unknown): {
  snapshot: InputSnapshot | null;
  reason: 'invalid-snapshot' | 'inconsistent-sources';
} {
  const invalid = { snapshot: null, reason: 'invalid-snapshot' } as const;
  const inconsistent = { snapshot: null, reason: 'inconsistent-sources' } as const;
  if (
    !record(value) ||
    !generation(value.generation) ||
    !Array.isArray(value.bodies) ||
    !value.bodies.every(nonempty) ||
    new Set(value.bodies).size !== value.bodies.length ||
    !Array.isArray(value.candidates) ||
    !Array.isArray(value.assignments)
  )
    return invalid;
  const candidates: InputCandidate[] = [];
  const ids = new Set<string>();
  const identities = new Set<string>();
  for (const item of value.candidates) {
    if (
      !record(item) ||
      !nonempty(item.id) ||
      !nullableId(item.publisher) ||
      !nullableId(item.source) ||
      !nonempty(item.tracker) ||
      !nonempty(item.origin) ||
      !(
        item.capabilities === null ||
        (Array.isArray(item.capabilities) && item.capabilities.every(nonempty))
      ) ||
      ![
        'conflict',
        'qSelectable',
        'sixDofSelectable',
        'qUsableNow',
        'sixDofUsableNow',
      ].every((field) => typeof item[field] === 'boolean')
    )
      return invalid;
    const candidate: InputCandidate = {
      id: item.id,
      publisher: item.publisher,
      source: item.source,
      tracker: item.tracker,
      origin: item.origin,
      capabilities:
        item.capabilities === null ? null : ([...item.capabilities] as string[]),
      conflict: item.conflict as boolean,
      qSelectable: item.qSelectable as boolean,
      sixDofSelectable: item.sixDofSelectable as boolean,
      qUsableNow: item.qUsableNow as boolean,
      sixDofUsableNow: item.sixDofUsableNow as boolean,
    };
    const identity = JSON.stringify([
      candidate.origin,
      candidate.publisher,
      candidate.source,
      candidate.tracker,
    ]);
    if (ids.has(candidate.id) || identities.has(identity)) return inconsistent;
    ids.add(candidate.id);
    identities.add(identity);
    const support = capability(candidate);
    if (candidate.origin === 'mvip_pose' && (!candidate.publisher || !candidate.source))
      return inconsistent;
    if (
      (candidate.qSelectable &&
        (candidate.conflict || !candidate.capabilities?.includes('orientation'))) ||
      (candidate.sixDofSelectable &&
        (candidate.conflict || support !== 'position-orientation')) ||
      (candidate.sixDofUsableNow && !candidate.qUsableNow) ||
      (candidate.origin === 'slime_orientation_stream' &&
        (support === 'position-orientation' ||
          candidate.sixDofSelectable ||
          candidate.sixDofUsableNow))
    )
      return inconsistent;
    candidates.push(candidate);
  }
  const assignments: InputAssignment[] = [];
  const bodies = new Set<string>();
  for (const item of value.assignments) {
    if (
      !record(item) ||
      !nonempty(item.body) ||
      !value.bodies.includes(item.body) ||
      !nullableId(item.qSource) ||
      !nullableId(item.sixDofSource) ||
      typeof item.manualSix !== 'boolean' ||
      !nonempty(item.mode) ||
      bodies.has(item.body)
    )
      return invalid;
    bodies.add(item.body);
    assignments.push({
      body: item.body,
      qSource: item.qSource,
      sixDofSource: item.sixDofSource,
      manualSix: item.manualSix,
      mode: item.mode,
    });
  }
  return {
    snapshot: {
      generation: value.generation,
      bodies: [...value.bodies],
      candidates,
      assignments,
    },
    reason: 'invalid-snapshot',
  };
}

function selectedIds(snapshot: InputSnapshot): string {
  return JSON.stringify(
    snapshot.assignments
      .filter((x) => x.qSource !== null || x.sixDofSource !== null)
      .map((x) => [x.body, x.qSource, x.sixDofSource])
      .sort((a, b) => a[0]!.localeCompare(b[0]!))
  );
}

/** Pure projection. No clock, socket, config writer, polling or solver inference. */
export function buildTrackingStatusModel(
  snapshot: InputSnapshot | null,
  context: TrackingStatusContext
): TrackingStatusModel {
  const previous = parseSnapshot(context.previousSnapshot).snapshot;
  const parsed = parseSnapshot(snapshot);
  let accepted = parsed.snapshot;
  let reason: TrackingStatusReason = 'connected';
  if (context.connection !== 'connected') reason = context.connection;
  else if (snapshot === null) reason = 'missing-snapshot';
  else if (!accepted) reason = parsed.reason;
  else if (context.minimumGeneration != null && !generation(context.minimumGeneration))
    reason = 'invalid-snapshot';
  else if (
    (previous && BigInt(accepted.generation) < BigInt(previous.generation)) ||
    (context.minimumGeneration != null &&
      BigInt(accepted.generation) < BigInt(context.minimumGeneration))
  )
    reason = 'stale-generation';
  else if (
    previous?.generation === accepted.generation &&
    selectedIds(previous) !== selectedIds(accepted)
  )
    reason = 'inconsistent-generation';
  if (reason !== 'connected') accepted = null;
  const retained = accepted ? null : (previous ?? parsed.snapshot);
  const base = accepted ?? retained;
  const sources: SourceStatus[] = (accepted?.candidates ?? []).map((candidate) => {
    const knownOrigin = ['mvip_pose', 'slime_orientation_stream'].includes(
      candidate.origin
    );
    return {
      id: candidate.id,
      publisher: candidate.publisher,
      source: candidate.source,
      tracker: candidate.tracker,
      origin: candidate.origin,
      capability: knownOrigin ? capability(candidate) : 'unknown',
      qSelectable: knownOrigin ? candidate.qSelectable : null,
      sixDofSelectable: knownOrigin ? candidate.sixDofSelectable : null,
      qUsableNow: knownOrigin ? (candidate.qUsableNow ? 'yes' : 'no') : 'unknown',
      sixDofUsableNow: knownOrigin
        ? candidate.sixDofUsableNow
          ? 'yes'
          : 'no'
        : 'unknown',
      availability: 'snapshot',
    };
  });
  const byId = new Map(sources.map((source) => [source.id, source]));
  const resolve = (id: string | null): SourceStatus | null => {
    if (id === null) return null;
    const found = byId.get(id);
    if (found) return found;
    // A saved ID is all that is known. Never decode it or invent tuple metadata.
    const offline: SourceStatus = {
      id,
      publisher: null,
      source: null,
      tracker: null,
      origin: null,
      capability: 'unknown',
      qSelectable: null,
      sixDofSelectable: null,
      qUsableNow: 'unknown',
      sixDofUsableNow: 'unknown',
      availability: 'saved-offline',
    };
    byId.set(id, offline);
    sources.push(offline);
    return offline;
  };
  const assignments = new Map(base?.assignments.map((item) => [item.body, item]));
  const bodies = (base?.bodies ?? []).map((body): BodyStatus => {
    const assignment = assignments.get(body) ?? {
      body,
      qSource: null,
      sixDofSource: null,
      manualSix: false,
      mode: 'unknown',
    };
    return {
      ...assignment,
      q: resolve(assignment.qSource),
      sixDof: resolve(assignment.sixDofSource),
    };
  });
  const status = accepted
    ? 'snapshot'
    : ['offline', 'disabled', 'loopback-denied'].includes(reason)
      ? 'offline'
      : [
            'error',
            'invalid-snapshot',
            'inconsistent-sources',
            'inconsistent-generation',
          ].includes(reason)
        ? 'error'
        : 'unknown';
  return {
    status,
    reason,
    generation: accepted?.generation ?? null,
    retainedGeneration: retained?.generation ?? null,
    bodies,
    sources,
    acceptedSnapshot: accepted,
    retainedSnapshot: retained,
  };
}
