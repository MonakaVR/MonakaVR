export const OUTPUT_MODES = ['ik', 'direct', 'hybrid'] as const;
export type OutputMode = (typeof OUTPUT_MODES)[number];
export const isOutputMode = (value: unknown): value is OutputMode =>
  OUTPUT_MODES.some((mode) => mode === value);

export interface ModeOption {
  mode: OutputMode;
  available: boolean;
  reason: string | null;
}
export interface ModeAssignment {
  body: string;
  mode: string;
  activeMode: string;
  savedMode: string;
  restartRequired: boolean;
  modeOptions: ModeOption[];
}
export interface ModeSnapshot {
  generation: string;
  readbackToken: string;
  restartRequired: boolean;
  backgroundIkAlignmentConfirmed: boolean;
  bodies: string[];
  assignments: ModeAssignment[];
}
export interface ModeApi {
  request(fields: Record<string, unknown>): Promise<unknown>;
}
export type ModePhase =
  | 'loaded'
  | 'editing'
  | 'review'
  | 'saving'
  | 'pendingRestart'
  | 'error'
  | 'disconnected';

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid Core mode response. Reload before editing.');
  return value as Record<string, unknown>;
}
export function parseModeSnapshot(value: unknown): ModeSnapshot {
  const root = record(value);
  if (
    typeof root.generation !== 'string' ||
    !/^(0|[1-9][0-9]{0,18})$/.test(root.generation) ||
    BigInt(root.generation) > 9223372036854775807n ||
    typeof root.readbackToken !== 'string' ||
    root.readbackToken.length < 1 ||
    root.readbackToken.length > 256 ||
    typeof root.restartRequired !== 'boolean' ||
    typeof root.backgroundIkAlignmentConfirmed !== 'boolean' ||
    !Array.isArray(root.bodies) ||
    !root.bodies.every((body) => typeof body === 'string') ||
    !Array.isArray(root.assignments)
  )
    throw new Error(
      'Core mode editing is unavailable or invalid. Reload after updating Core.'
    );
  const bodies = root.bodies as string[];
  if (new Set(bodies).size !== bodies.length)
    throw new Error('Ambiguous Core body targets.');
  const assignments = root.assignments.map((value) => {
    const row = record(value);
    if (
      typeof row.body !== 'string' ||
      !bodies.includes(row.body) ||
      typeof row.mode !== 'string' ||
      typeof row.activeMode !== 'string' ||
      row.mode !== row.activeMode ||
      typeof row.savedMode !== 'string' ||
      typeof row.restartRequired !== 'boolean' ||
      !Array.isArray(row.modeOptions)
    )
      throw new Error('Invalid Core mode assignment.');
    const modeOptions = row.modeOptions.map((value) => {
      const option = record(value);
      if (
        !isOutputMode(option.mode) ||
        typeof option.available !== 'boolean' ||
        !(option.reason === null || typeof option.reason === 'string')
      )
        throw new Error('Invalid Core mode availability.');
      return option as unknown as ModeOption;
    });
    if (
      modeOptions.length !== OUTPUT_MODES.length ||
      new Set(modeOptions.map((option) => option.mode)).size !== OUTPUT_MODES.length ||
      (row.activeMode !== row.savedMode && !row.restartRequired) ||
      (row.restartRequired && !root.restartRequired)
    )
      throw new Error('Inconsistent Core saved/active modes.');
    return { ...row, modeOptions } as unknown as ModeAssignment;
  });
  if (new Set(assignments.map((row) => row.body)).size !== assignments.length)
    throw new Error('Ambiguous Core assignments.');
  return { ...root, bodies, assignments } as unknown as ModeSnapshot;
}

/** A mode draft contains no source, calibration or runtime-pose fields. */
export class TrackingModeController {
  state: {
    phase: ModePhase;
    snapshot: ModeSnapshot | null;
    body: string;
    drafts: Record<string, OutputMode>;
    busy: boolean;
    needsReload: boolean;
    error: string;
  } = {
    phase: 'disconnected',
    snapshot: null,
    body: 'HIP',
    drafts: {},
    busy: false,
    needsReload: true,
    error: '',
  };
  private listeners = new Set<() => void>();
  private epoch = 0;
  constructor(
    private api: ModeApi,
    private timeoutMs = 10000
  ) {}
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  private notify() {
    this.state = { ...this.state };
    this.listeners.forEach((listener) => listener());
  }
  get assignment() {
    return this.state.snapshot?.assignments.find((row) => row.body === this.state.body);
  }
  get draft() {
    return (
      this.state.drafts[this.state.body] ?? this.assignment?.savedMode ?? 'unknown'
    );
  }
  get canEdit() {
    return (
      !this.state.busy &&
      !this.state.needsReload &&
      !this.state.snapshot?.restartRequired &&
      !!this.assignment &&
      isOutputMode(this.assignment.activeMode) &&
      isOutputMode(this.assignment.savedMode)
    );
  }
  get canReview() {
    return (
      this.canEdit &&
      this.draft !== this.assignment?.savedMode &&
      this.assignment?.modeOptions.some(
        (option) => option.mode === this.draft && option.available
      ) === true
    );
  }
  private async request(fields: Record<string, unknown>) {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      const result = await Promise.race([
        this.api.request(fields),
        new Promise<never>((_, reject) => {
          timer = setTimeout(
            () =>
              reject(new Error('Core request timed out; saved result may be unknown.')),
            this.timeoutMs
          );
        }),
      ]);
      return parseModeSnapshot(result);
    } finally {
      clearTimeout(timer);
    }
  }
  private accept(snapshot: ModeSnapshot) {
    this.state.snapshot = snapshot;
    this.state.drafts = {};
    if (!snapshot.bodies.includes(this.state.body))
      this.state.body = snapshot.bodies[0] ?? '';
    this.state.needsReload = false;
    this.state.error = '';
    this.state.phase = snapshot.restartRequired ? 'pendingRestart' : 'loaded';
  }
  async load() {
    if (this.state.busy) return;
    const epoch = ++this.epoch;
    this.state.busy = true;
    this.notify();
    try {
      const snapshot = await this.request({ action: 'snapshot' });
      if (epoch === this.epoch) this.accept(snapshot);
    } catch (error) {
      if (epoch === this.epoch) this.fail(error);
    } finally {
      if (epoch === this.epoch) {
        this.state.busy = false;
        this.notify();
      }
    }
  }
  selectBody(body: string) {
    if (this.state.busy || !this.state.snapshot?.bodies.includes(body)) return;
    this.state.body = body;
    if (this.canEdit) this.state.phase = this.canReview ? 'editing' : 'loaded';
    this.notify();
  }
  selectMode(mode: OutputMode) {
    if (
      !this.canEdit ||
      !this.assignment?.modeOptions.some(
        (option) => option.mode === mode && option.available
      )
    )
      return;
    this.state.drafts = { ...this.state.drafts, [this.state.body]: mode };
    this.state.phase = this.canReview ? 'editing' : 'loaded';
    this.notify();
  }
  review() {
    if (!this.canReview) return;
    this.state.phase = 'review';
    this.notify();
  }
  cancel() {
    if (!this.canEdit) return;
    const drafts = { ...this.state.drafts };
    delete drafts[this.state.body];
    this.state.drafts = drafts;
    this.state.phase = 'loaded';
    this.notify();
  }
  async save() {
    if (this.state.phase !== 'review' || !this.canReview || !this.state.snapshot)
      return;
    const epoch = ++this.epoch;
    const body = this.state.body;
    const mode = this.draft;
    const activeMode = this.assignment?.activeMode;
    const { generation, readbackToken } = this.state.snapshot;
    this.state.phase = 'saving';
    this.state.busy = true;
    this.notify();
    try {
      const snapshot = await this.request({
        action: 'save_mode',
        body,
        mode,
        generation,
        readbackToken,
      });
      if (epoch !== this.epoch) return;
      const saved = snapshot.assignments.find((row) => row.body === body);
      if (
        saved?.savedMode !== mode ||
        !snapshot.restartRequired ||
        saved.activeMode !== activeMode ||
        snapshot.generation !== generation ||
        snapshot.readbackToken === readbackToken
      )
        throw new Error('Core did not confirm an offline save. Reload to diagnose.');
      this.accept(snapshot);
    } catch (error) {
      if (epoch === this.epoch) this.fail(error);
    } finally {
      if (epoch === this.epoch) {
        this.state.busy = false;
        this.notify();
      }
    }
  }
  private fail(error: unknown) {
    this.state.phase = 'error';
    this.state.needsReload = true;
    this.state.error = `${error instanceof Error ? error.message : String(error)} Reload to confirm saved and active modes before retrying.`;
  }
  disconnect() {
    this.epoch++;
    this.state.phase = 'disconnected';
    this.state.busy = false;
    this.state.needsReload = true;
    this.state.error =
      'Core disconnected; saved result may be unknown. Reconnect and reload.';
    this.notify();
  }
}
