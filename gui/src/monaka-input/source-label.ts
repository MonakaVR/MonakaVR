import type { InputCandidate } from './controller.js';

type LabelSource = Pick<
  InputCandidate,
  'id' | 'publisher' | 'source' | 'tracker' | 'displayName'
>;
type TrackerLabelSource = LabelSource & Pick<InputCandidate, 'origin'>;

// Long public IDs must not determine the width of a picker or body card.
const compact = (value: string, limit = 32) => {
  const points = Array.from(value);
  return points.length <= limit
    ? value
    : `${points.slice(0, limit - 9).join('')}…${points.slice(-8).join('')}`;
};
const displayLabel = (source: LabelSource, unknown: string) =>
  compact(source.displayName?.trim() || unknown);

const trackerLabel = (source: TrackerLabelSource, unknown: string) =>
  source.origin === 'mvip_pose'
    ? compact(source.tracker || unknown)
    : displayLabel(source, unknown);

/** Physical metadata is available only when the MVIP candidate supplies it. */
export function sourcePhysicalId(
  source: Pick<InputCandidate, 'origin' | 'displayName'>
): string | undefined {
  return source.origin === 'mvip_pose' && source.displayName?.trim()
    ? source.displayName
    : undefined;
}

/** Exact identity remains available without occupying the primary label. */
export function sourceDetails(
  source: LabelSource,
  { includeTracker = true, includeInputDevice = true } = {}
): string {
  return [
    includeInputDevice &&
      source.displayName &&
      `Input device: ${source.displayName}`,
    source.publisher && `Publisher: ${source.publisher}`,
    source.source && `Source: ${source.source}`,
    includeTracker && `Tracker: ${source.tracker}`,
    `Identity: ${source.id}`,
  ]
    .filter(Boolean)
    .join('\n');
}

function disambiguatedLabel<T extends LabelSource>(
  source: T,
  candidates: readonly T[],
  baseLabel: (candidate: T) => string,
  identityFields: readonly ('publisher' | 'source')[]
): string {
  const base = baseLabel(source);
  const duplicates = candidates.filter((candidate) => baseLabel(candidate) === base);
  if (duplicates.length < 2) return base;
  for (const field of identityFields) {
    const values = duplicates.map((candidate) => candidate[field]?.trim() ?? '');
    if (
      values.every((value) => value.length > 0 && Array.from(value).length <= 24) &&
      new Set(values).size === duplicates.length
    )
      return `${base} · ${source[field]!.trim()}`;
  }
  // Same public device can have multiple logical channels. Sorted ordinals are
  // deterministic for this list; the exact immutable identity is in details.
  const order = [...duplicates].sort((a, b) => a.id.localeCompare(b.id));
  return `${base} · ${order.findIndex((candidate) => candidate.id === source.id) + 1}`;
}

/** Names never merge sources. Only colliding primary labels acquire a suffix. */
export function sourceLabel(
  source: LabelSource,
  candidates: readonly LabelSource[],
  unknown = '未接続の入力'
): string {
  return disambiguatedLabel(
    source,
    candidates,
    (candidate) => displayLabel(candidate, unknown),
    ['publisher']
  );
}

export function trackerSourceLabel(
  source: TrackerLabelSource,
  candidates: readonly TrackerLabelSource[],
  unknown = '未接続の入力'
): string {
  return disambiguatedLabel(
    source,
    candidates,
    (candidate) => trackerLabel(candidate, unknown),
    ['publisher', 'source']
  );
}

/** A cold offline saved reference contains no trustworthy physical label. */
export function savedSourceLabel(offline = '未接続の入力'): string {
  return offline;
}

export function selectedSourceLabel(
  id: string | null | undefined,
  candidates: readonly LabelSource[],
  unassigned = '—',
  offline = '未接続の入力'
): string {
  if (!id) return unassigned;
  const source = candidates.find((candidate) => candidate.id === id);
  return source ? sourceLabel(source, candidates, offline) : savedSourceLabel(offline);
}

export function selectedTrackerSourceLabel(
  id: string | null | undefined,
  candidates: readonly TrackerLabelSource[],
  unassigned = '—',
  offline = '未接続の入力'
): string {
  if (!id) return unassigned;
  const source = candidates.find((candidate) => candidate.id === id);
  return source
    ? trackerSourceLabel(source, candidates, offline)
    : savedSourceLabel(offline);
}

export function selectedSourceDetails(
  id: string | null | undefined,
  candidates: readonly LabelSource[]
): string | undefined {
  if (!id) return undefined;
  const source = candidates.find((candidate) => candidate.id === id);
  return source ? sourceDetails(source) : `Saved identity: ${id}`;
}
