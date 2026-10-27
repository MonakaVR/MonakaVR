export interface InputCandidate {
  id: string;
  publisher: string | null;
  source: string | null;
  tracker: string;
  capabilities: string[] | null;
  conflict: boolean;
  qSelectable: boolean;
  sixDofSelectable: boolean;
  qUsableNow: boolean;
  sixDofUsableNow: boolean;
  origin: string;
}
export interface InputAssignment {
  body: string;
  qSource: string | null;
  sixDofSource: string | null;
  manualSix: boolean;
  mode: string;
}
export interface InputSnapshot {
  generation: string;
  bodies: string[];
  candidates: InputCandidate[];
  assignments: InputAssignment[];
}
export interface InputApi {
  request(fields: Record<string, unknown>): Promise<InputSnapshot>;
}
export class InputSelectionController {
  state: {
    snapshot: InputSnapshot | null;
    draft: InputAssignment;
    generation: string;
    dirty: boolean;
    busy: boolean;
    error: string;
    needsReload: boolean;
  } = {
    snapshot: null,
    draft: {
      body: 'HIP',
      qSource: null,
      sixDofSource: null,
      manualSix: false,
      mode: 'ik',
    },
    generation: '0',
    dirty: false,
    busy: false,
    error: '',
    needsReload: false,
  };
  private listeners = new Set<() => void>();
  private epoch = 0;
  private autoConsidered = false;
  private loading = false;
  private reloadRequested = false;
  constructor(private api: InputApi) {}
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };
  private notify() {
    this.state = { ...this.state };
    this.listeners.forEach((listener) => listener());
  }
  private reset(body = this.state.draft.body) {
    const saved = this.state.snapshot?.assignments.find((x) => x.body === body);
    this.state.draft = saved
      ? { ...saved }
      : { body, qSource: null, sixDofSource: null, manualSix: false, mode: 'ik' };
    this.autoConsidered = !!saved;
    this.state.generation = this.state.snapshot?.generation ?? '0';
    this.state.dirty = false;
  }
  async load(discardDraft = false) {
    if (this.loading) {
      if (discardDraft) {
        this.reloadRequested = true;
        this.state.busy = true;
        this.notify();
      }
      return;
    }
    if (this.state.busy) return;
    this.loading = true;
    if (discardDraft) {
      this.state.busy = true;
      this.notify();
    }
    const epoch = this.epoch;
    try {
      const snapshot = await this.api.request({ action: 'snapshot' });
      if (epoch !== this.epoch) return;
      this.state.snapshot = snapshot;
      if (discardDraft || !this.state.dirty) this.reset();
      if (discardDraft) this.state.needsReload = false;
      if (!this.state.needsReload)
        this.state.error =
          this.state.dirty && this.state.generation !== snapshot.generation
            ? 'Assignments changed. Reload before saving.'
            : '';
    } catch (error) {
      if (epoch === this.epoch)
        this.state.error = String(error instanceof Error ? error.message : error);
    } finally {
      this.loading = false;
      // A save may now own the UI lock; its completion must release it.
      if (epoch === this.epoch) this.state.busy = false;
      if (this.reloadRequested) {
        this.reloadRequested = false;
        await this.load(true);
      } else this.notify();
    }
  }
  selectBody(body: string) {
    if (
      this.state.dirty ||
      this.state.busy ||
      !this.state.snapshot?.bodies.includes(body)
    )
      return;
    this.reset(body);
    this.notify();
  }
  selectQ(id: string | null) {
    if (this.state.busy || !this.state.snapshot) return;
    const source = this.state.snapshot.candidates.find((x) => x.id === id);
    if (id !== null && !source?.qSelectable) return;
    const draft = { ...this.state.draft, qSource: id };
    if (!this.autoConsidered && !draft.manualSix && id !== null) {
      draft.sixDofSource =
        source?.sixDofSelectable && source.origin === 'mvip_pose' ? id : null;
      this.autoConsidered = true;
    }
    this.state.draft = draft;
    this.state.dirty = true;
    this.notify();
  }
  selectSix(id: string | null) {
    if (this.state.busy || !this.state.snapshot) return;
    if (
      id !== null &&
      !this.state.snapshot.candidates.find((x) => x.id === id)?.sixDofSelectable
    )
      return;
    this.state.draft = { ...this.state.draft, sixDofSource: id, manualSix: true };
    this.autoConsidered = true;
    this.state.dirty = true;
    this.notify();
  }
  cancel() {
    if (this.state.busy || this.state.needsReload) return;
    this.epoch++;
    this.reset();
    this.state.error = '';
    this.notify();
  }
  async save() {
    if (
      !this.state.dirty ||
      this.state.busy ||
      this.state.needsReload ||
      !this.state.snapshot
    )
      return;
    this.epoch++;
    this.state.busy = true;
    this.state.error = '';
    this.notify();
    try {
      const snapshot = await this.api.request({
        action: 'save',
        generation: this.state.generation,
        body: this.state.draft.body,
        qSource: this.state.draft.qSource,
        sixDofSource: this.state.draft.sixDofSource,
        manualSix: this.state.draft.manualSix,
      });
      this.state.snapshot = snapshot;
      this.reset();
    } catch (error) {
      this.state.needsReload = true;
      this.state.error = `${error instanceof Error ? error.message : String(error)} Reload to confirm the saved state before retrying.`;
    } finally {
      this.state.busy = false;
      this.notify();
    }
  }
}
