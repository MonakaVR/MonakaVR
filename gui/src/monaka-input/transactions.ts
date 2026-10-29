import type { InputAssignment } from './controller.js';
import type { DataFeedUpdateT } from 'solarxr-protocol';
import { locatorKey, type Locator } from './assignment.js';

export interface PendingAssignment {
  token: string;
  body: string;
  requested: InputAssignment;
  legacyIds: Locator[];
}

/** Bones/pose-only/empty packets cannot confirm a pending physical assignment. */
export function assignmentReviewFeed(
  pending: PendingAssignment,
  packet: DataFeedUpdateT
) {
  if (packet.index !== 0 || !Array.isArray(pending.legacyIds)) return false;
  const trackers = packet.devices.flatMap((device) => device.trackers);
  return pending.legacyIds.every((id) => {
    const key = locatorKey(id);
    const matches = trackers.filter(
      (tracker) => key !== null && locatorKey(tracker.trackerId) === key
    );
    return matches.length === 1 && typeof matches[0].info?.bodyPart === 'number';
  });
}

/** Tab/session recovery evidence only; never a source assignment or RPC queue. */
export class AssignmentTransactions {
  private memory = new Map<string, PendingAssignment>();
  private active = new Set<string>();
  constructor(private storage?: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>) {}
  private key(endpoint: string) {
    return `monaka-assignment-review-v1:${endpoint}`;
  }
  read(endpoint: string): PendingAssignment | null {
    if (this.memory.has(endpoint)) return this.memory.get(endpoint)!;
    try {
      const stored = this.storage?.getItem(this.key(endpoint));
      if (stored) {
        const pending = JSON.parse(stored) as PendingAssignment;
        if (typeof pending.token === 'string' && typeof pending.body === 'string') {
          this.memory.set(endpoint, pending);
          return pending;
        }
      }
    } catch {
      // Storage can be unavailable; the module owner still survives route unmount.
    }
    return null;
  }
  begin(
    endpoint: string,
    requested: InputAssignment,
    legacyIds: Locator[] = []
  ): PendingAssignment {
    if (this.read(endpoint)) throw new Error('Reload the unresolved assignment first.');
    const pending = {
      token: crypto.randomUUID(),
      body: requested.body,
      requested: { ...requested },
      legacyIds,
    };
    if (legacyIds.length) {
      try {
        if (!this.storage) throw new Error('Storage unavailable');
        const serialized = JSON.stringify(pending);
        this.storage.setItem(this.key(endpoint), serialized);
        if (this.storage.getItem(this.key(endpoint)) !== serialized)
          throw new Error('Recovery marker readback mismatch');
      } catch {
        // A write may have succeeded despite an exception or uncertain readback.
        // Retain review evidence, but never mark a writer as started.
        this.memory.set(endpoint, pending);
        throw new Error(
          'Recovery storage unavailable. No assignments were saved. Enable session storage, then reload and review before changing physical body assignments.'
        );
      }
    } else {
      try {
        this.storage?.setItem(this.key(endpoint), JSON.stringify(pending));
      } catch {
        // Core-only operations retain route recovery without a second writer.
      }
    }
    this.memory.set(endpoint, pending);
    this.active.add(pending.token);
    return pending;
  }
  isActive(endpoint: string) {
    const pending = this.read(endpoint);
    return !!pending && this.active.has(pending.token);
  }
  settled(token: string) {
    this.active.delete(token);
  }
  clear(endpoint: string, token: string) {
    if (this.read(endpoint)?.token !== token || this.active.has(token)) return false;
    try {
      this.storage?.removeItem(this.key(endpoint));
    } catch {
      // A remaining stored marker may conservatively require review again.
    }
    this.memory.delete(endpoint);
    return true;
  }
}

let storage: Storage | undefined;
try {
  storage = globalThis.sessionStorage;
} catch {
  // Restricted browser storage still retains recovery state for this renderer.
}
export const assignmentTransactions = new AssignmentTransactions(storage);
