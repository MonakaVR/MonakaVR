import type { InputCandidate } from './controller.js';

type LabelSource = Pick<
  InputCandidate,
  'id' | 'publisher' | 'source' | 'tracker' | 'displayName'
>;

// Long public IDs must not determine the width of a picker or body card.
const compact = (value: string, limit = 32) => {
  const points = Array.from(value);
  return points.length <= limit
    ? value
    : `${points.slice(0, limit - 9).join('')}…${points.slice(-8).join('')}`;
};
const baseLabel = (source: LabelSource, unknown: string) =>
  compact(source.displayName?.trim() || unknown);

/** Exact identity remains available without occupying the primary label. */
export function sourceDetails(source: LabelSource): string {
  return [
    source.displayName && `Input device: ${source.displayName}`,
    source.publisher && `Publisher: ${source.publisher}`,
    source.source && `Source: ${source.source}`,
    `Tracker: ${source.tracker}`,
    `Identity: ${source.id}`,
  ]
    .filter(Boolean)
    .join('\n');
}

/** Names never merge sources. Only colliding primary labels acquire a suffix. */
export function sourceLabel(
  source: LabelSource,
  candidates: readonly LabelSource[],
  unknown = '未接続の入力'
): string {
  const base = baseLabel(source, unknown);
  const duplicates = candidates.filter(
    (candidate) => baseLabel(candidate, unknown) === base
  );
  if (duplicates.length < 2) return base;
  // A concise declared publisher can distinguish duplicates. Long opaque IDs,
  // source IDs and logical tracker IDs remain in details rather than label chains.
  const publishers = duplicates.map((candidate) => candidate.publisher?.trim() ?? '');
  if (
    publishers.every((value) => value.length > 0 && Array.from(value).length <= 24) &&
    new Set(publishers).size === duplicates.length
  )
    return `${base} · ${source.publisher!.trim()}`;
  // Same public device can have multiple logical channels. Sorted ordinals are
  // deterministic for this list; the exact immutable identity is in details.
  const order = [...duplicates].sort((a, b) => a.id.localeCompare(b.id));
  return `${base} · ${order.findIndex((candidate) => candidate.id === source.id) + 1}`;
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

export function selectedSourceDetails(
  id: string | null | undefined,
  candidates: readonly LabelSource[]
): string | undefined {
  if (!id) return undefined;
  const source = candidates.find((candidate) => candidate.id === id);
  return source ? sourceDetails(source) : `Saved identity: ${id}`;
}
