import type { InputApi, InputCandidate, InputSnapshot } from './controller';

export interface HomeInputRow {
  id: string;
  candidate: InputCandidate;
  variants: InputCandidate[];
  conflict: boolean;
  route: 'mvip' | 'unknown';
  capabilities: string[] | null;
  qUsableNow: boolean | null;
  sixDofUsableNow: boolean | null;
  savedRoles: { body: string; kind: 'q' | 'six' }[];
}

// Compare contract fields, without parsing IDs or assigning meaning to names.
const fingerprint = (candidate: InputCandidate) =>
  JSON.stringify([
    candidate.publisher,
    candidate.source,
    candidate.tracker,
    candidate.displayName ?? null,
    candidate.capabilities,
    candidate.conflict,
    candidate.qSelectable,
    candidate.sixDofSelectable,
    candidate.qUsableNow,
    candidate.sixDofUsableNow,
    candidate.origin,
    candidate.slimeTrackerId ?? null,
  ]);

/** Home owns no assignment or registration policy; this is a read-only projection. */
export function buildHomeInputCatalog(
  snapshot: InputSnapshot | null,
  fresh: boolean
): HomeInputRow[] {
  const groups = new Map<string, InputCandidate[]>();
  for (const candidate of snapshot?.candidates ?? []) {
    const variants = groups.get(candidate.id) ?? [];
    if (!variants.some((item) => fingerprint(item) === fingerprint(candidate)))
      variants.push(candidate);
    groups.set(candidate.id, variants);
  }
  const rows: HomeInputRow[] = [];
  for (const [id, variants] of groups) {
    // The existing SolarXR cards own legacy inputs. Do not duplicate those rows.
    if (variants.every((candidate) => candidate.origin === 'slime_orientation_stream'))
      continue;
    const first = variants[0];
    const conflict = variants.length !== 1 || first.conflict;
    const route = variants.every((candidate) => candidate.origin === 'mvip_pose')
      ? 'mvip'
      : 'unknown';
    const known = fresh && !conflict && route === 'mvip';
    const savedRoles: HomeInputRow['savedRoles'] = [];
    for (const assignment of snapshot?.assignments ?? []) {
      const q = snapshot?.restartRequired
        ? (assignment.savedQSource ?? null)
        : assignment.qSource;
      const six = snapshot?.restartRequired
        ? (assignment.savedSixDofSource ?? null)
        : assignment.sixDofSource;
      if (q === id) savedRoles.push({ body: assignment.body, kind: 'q' });
      if (six === id) savedRoles.push({ body: assignment.body, kind: 'six' });
    }
    rows.push({
      id,
      candidate: conflict
        ? {
            ...first,
            displayName: null,
            publisher: null,
            source: null,
            tracker: id,
          }
        : first,
      variants,
      conflict,
      route,
      capabilities: conflict ? null : first.capabilities,
      qUsableNow: known ? first.qUsableNow : null,
      sixDofUsableNow: known ? first.sixDofUsableNow : null,
      savedRoles,
    });
  }
  return rows;
}

export const inputAssignmentsPath = '/onboarding/trackers-assign';

export function inputAssignmentsHref(search: string): string {
  return inputAssignmentsPath + search;
}

/** Snapshot-only reader. Expiration prevents a hung request reviving cached usability. */
export class HomeInputCatalogReader {
  state: { snapshot: InputSnapshot | null; fresh: boolean; failed: boolean } = {
    snapshot: null,
    fresh: false,
    failed: false,
  };
  private listeners = new Set<() => void>();
  private loading = false;
  private epoch = 0;
  private expiry: ReturnType<typeof setTimeout> | undefined;
  constructor(
    private api: InputApi,
    private maxAgeMs = 5000
  ) {}
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  private update(patch: Partial<HomeInputCatalogReader['state']>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((listener) => listener());
  }
  async load() {
    if (this.loading) return;
    this.loading = true;
    const epoch = this.epoch;
    try {
      const snapshot = await this.api.request({ action: 'snapshot' });
      if (epoch !== this.epoch) return;
      clearTimeout(this.expiry);
      this.update({ snapshot, fresh: true, failed: false });
      this.expiry = setTimeout(() => this.update({ fresh: false }), this.maxAgeMs);
    } catch {
      if (epoch === this.epoch) this.update({ fresh: false, failed: true });
    } finally {
      if (epoch === this.epoch) this.loading = false;
    }
  }
  stop() {
    this.epoch++;
    this.loading = false;
    clearTimeout(this.expiry);
    this.update({ fresh: false });
  }
}
