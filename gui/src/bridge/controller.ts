import {
  BridgeHealthPresentation,
  type BridgeDiagnostics,
} from '../../electron/bridge-health.js';
import {
  isBridgePolicy,
  type BridgeConfiguration,
  type BridgeDesktopApi,
  type BridgePolicy,
} from '../../electron/bridge-types.js';

export interface BridgeControlState {
  configuration: BridgeConfiguration | null;
  selectedPolicy: BridgePolicy | null;
  confirmed: boolean;
  busy: boolean;
  diagnostics: BridgeDiagnostics;
  selectedDevice: string | null;
  message: string;
}
export class BridgeController {
  private readonly health = new BridgeHealthPresentation();
  private readonly listeners = new Set<() => void>();
  private disposed = false;
  private healthBusy = false;
  private operation: string | null = null;
  private cancelRequested = false;
  private generation = 0;
  state: BridgeControlState;
  constructor(
    private readonly api: BridgeDesktopApi,
    private readonly now = () => Date.now(),
    private readonly id: () => string = () => crypto.randomUUID()
  ) {
    this.state = {
      configuration: null,
      selectedPolicy: null,
      confirmed: false,
      busy: false,
      diagnostics: this.health.display(null, now()),
      selectedDevice: null,
      message: 'Choose an existing Bridge configuration and its tested validator.',
    };
  }
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };
  private update(change: Partial<BridgeControlState>): void {
    if (this.disposed) return;
    this.state = { ...this.state, ...change };
    this.listeners.forEach((listener) => listener());
  }
  get canApply(): boolean {
    const config = this.state.configuration;
    return (
      !this.disposed &&
      !this.state.busy &&
      !!config?.token &&
      !!config.locations.validatorPath &&
      isBridgePolicy(this.state.selectedPolicy) &&
      this.state.selectedPolicy !== config.policy &&
      this.state.confirmed
    );
  }
  selectPolicy(policy: BridgePolicy): void {
    if (!this.state.busy && isBridgePolicy(policy))
      this.update({ selectedPolicy: policy, confirmed: false });
  }
  confirm(confirmed: boolean): void {
    if (!this.state.busy) this.update({ confirmed });
  }
  selectDevice(key: string): void {
    this.update({ selectedDevice: key });
  }
  private adopt(configuration: BridgeConfiguration): void {
    this.update({
      configuration,
      selectedPolicy: configuration.policy,
      confirmed: false,
    });
    this.tick();
  }
  async load(): Promise<void> {
    if (this.disposed || this.state.busy) return;
    const generation = ++this.generation;
    const operation = this.id();
    this.operation = operation;
    this.cancelRequested = false;
    this.update({ busy: true });
    try {
      const result = await this.api.load(operation);
      if (this.disposed || generation !== this.generation) return;
      this.adopt(result);
      this.update({
        message: result.token
          ? 'Saved settings loaded. Review the selected output policy before applying.'
          : 'Select the existing Bridge configuration.',
      });
    } catch (cause) {
      if (generation === this.generation)
        this.update(
          this.cancelRequested
            ? {
                confirmed: false,
                message: 'Load cancelled; previous selection retained.',
              }
            : { configuration: null, confirmed: false, message: this.error(cause) }
        );
    } finally {
      this.operation = null;
      if (generation === this.generation) this.update({ busy: false });
    }
  }
  async choose(kind: 'config' | 'validator'): Promise<void> {
    if (this.disposed || this.state.busy) return;
    ++this.generation;
    this.update({ busy: true });
    try {
      const result = await this.api.choose(kind);
      if (result && !this.disposed) {
        this.adopt(result);
        this.update({ message: 'Bridge selection saved in Desktop settings.' });
      }
    } catch (cause) {
      this.update({ message: this.error(cause) });
    } finally {
      this.update({ busy: false });
    }
  }
  async apply(): Promise<void> {
    if (!this.canApply) return;
    const { token } = this.state.configuration!;
    const policy = this.state.selectedPolicy!;
    const operation = this.id();
    this.operation = operation;
    this.cancelRequested = false;
    this.update({ busy: true, message: `Validating and saving ${policy}...` });
    try {
      const result = await this.api.apply(token!, policy, operation);
      if (this.disposed) return;
      this.adopt(result);
      this.update({
        message: `Saved ${result.policy}. Check current matching diagnostics for runtime observation; delivery remains unconfirmed.`,
      });
    } catch (cause) {
      this.update({
        selectedPolicy: this.state.configuration?.policy ?? null,
        confirmed: false,
        message: this.cancelRequested
          ? 'Cancelled before save; reload to confirm the saved policy.'
          : `Save failed: ${this.error(cause)} Last loaded selection restored; reload if another writer changed it.`,
      });
    } finally {
      this.operation = null;
      this.update({ busy: false });
    }
  }
  async cancel(): Promise<void> {
    if (this.disposed) return;
    if (this.operation) {
      this.cancelRequested = true;
      this.update({
        message: 'Cancellation requested; waiting for the save boundary.',
      });
      try {
        await this.api.cancel(this.operation);
      } catch (cause) {
        this.update({
          message: `Cancellation is unconfirmed: ${this.error(cause)}. Reload after completion.`,
        });
      }
    } else if (!this.state.busy)
      this.update({
        selectedPolicy: this.state.configuration?.policy ?? null,
        confirmed: false,
        message: 'Selection restored to the last loaded saved policy.',
      });
  }
  async poll(): Promise<void> {
    this.tick();
    if (this.disposed || this.healthBusy) return;
    this.healthBusy = true;
    const generation = this.generation;
    try {
      const result = await this.api.health();
      if (!this.disposed && generation === this.generation) {
        this.health.observe(result, this.state.configuration, this.now());
        this.tick();
      }
    } catch (cause) {
      if (!this.disposed && generation === this.generation) {
        this.health.observe(
          { configPath: null, value: null, error: this.error(cause) },
          this.state.configuration,
          this.now()
        );
        this.tick();
      }
    } finally {
      this.healthBusy = false;
    }
  }
  tick(): void {
    if (this.disposed) return;
    const diagnostics = this.health.display(this.state.configuration, this.now());
    const selectedDevice = diagnostics.devices.some(
      (row) => row.key === this.state.selectedDevice
    )
      ? this.state.selectedDevice
      : null;
    this.update({ diagnostics, selectedDevice });
  }
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    ++this.generation;
    this.listeners.clear();
    if (this.operation) void this.api.cancel(this.operation).catch(() => {});
  }
  private error(cause: unknown): string {
    return cause instanceof Error ? cause.message : 'Bridge operation failed.';
  }
}
