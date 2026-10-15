import {
  isBridgePolicy,
  type BridgeConfiguration,
  type BridgeHealthRead,
  type BridgePolicy,
} from './bridge-types.js';

export interface BridgeDeviceDisplay {
  key: string;
  publisher: string;
  source: string;
  device: string;
  tracker: string;
  tracking: string;
  positionValid: 'valid' | 'invalid' | 'unknown';
  orientationValid: 'valid' | 'invalid' | 'unknown';
  freshness: 'fresh' | 'stale' | 'historical';
  collision: 'yes' | 'no' | 'unknown';
}
export interface BridgeDiagnostics {
  backendStarted: 'unknown';
  publisherRunning: 'unknown';
  current: boolean;
  observedPolicy: BridgePolicy | null;
  observedRevision: number | null;
  revision: 'unknown' | 'match' | 'different';
  flow: string;
  message: string;
  devices: BridgeDeviceDisplay[];
}
const numeric = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value);
const text = (value: unknown) =>
  typeof value === 'string' && value ? value : 'unknown';
const validity = (value: unknown) =>
  value === true ? 'valid' : value === false ? 'invalid' : 'unknown';
const record = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value);

// A diagnostic presenter only: fresh native poses do not prove backend/publisher
// process liveness, final SteamVR output, or downstream delivery.
export class BridgeHealthPresentation {
  private expected: string | null = null;
  private path: string | null = null;
  private highWater = -Infinity;
  private snapshot: Record<string, unknown> | null = null;
  private stamp = -Infinity;
  private available = false;
  private error = 'No Bridge diagnostics loaded.';

  observe(
    read: BridgeHealthRead,
    config: BridgeConfiguration | null,
    now: number
  ): void {
    const publisher = config?.publisher ?? null,
      path = config?.locations.configPath ?? null;
    if (publisher !== this.expected || path !== this.path) {
      this.expected = publisher;
      this.path = path;
      this.highWater = -Infinity;
      this.snapshot = null;
    }
    this.available = false;
    if (read.error || !read.value) {
      this.error = read.error ?? 'Bridge diagnostics unavailable.';
      return;
    }
    const value = read.value;
    if (!publisher || read.configPath !== path || value.publisher_id !== publisher) {
      this.error = 'Diagnostic publisher/configuration does not match this selection.';
      return;
    }
    const stamp = value.snapshot_unix_ms;
    if (!numeric(stamp) || stamp < 0 || stamp > now) {
      this.error = 'Diagnostic timestamp is missing or in the future.';
      return;
    }
    if (stamp < this.highWater) {
      this.error = 'Older diagnostic update ignored.';
      return;
    }
    if (!Array.isArray(value.devices) || value.devices.some((row) => !record(row))) {
      this.error = 'Invalid diagnostic device list.';
      return;
    }
    this.highWater = stamp;
    this.stamp = stamp;
    this.snapshot = value;
    this.available = true;
    this.error = '';
  }

  display(config: BridgeConfiguration | null, now: number): BridgeDiagnostics {
    const relevant =
      config?.publisher === this.expected && config?.locations.configPath === this.path;
    const value = relevant ? this.snapshot : null;
    const current =
      this.available &&
      !!config?.publisher &&
      config.publisher === this.expected &&
      config.locations.configPath === this.path &&
      now >= this.stamp &&
      now - this.stamp < 3000;
    const elapsed = now - this.stamp;
    const timeout =
      value?.pose_timeout_ms === undefined
        ? 500
        : numeric(value.pose_timeout_ms) && value.pose_timeout_ms > 0
          ? value.pose_timeout_ms
          : 0;
    const rows = Array.isArray(value?.devices) ? value.devices.filter(record) : [];
    const devices = rows.map((row): BridgeDeviceDisplay => {
      const fresh =
        row.fresh === true &&
        numeric(row.pose_age_ms) &&
        row.pose_age_ms >= 0 &&
        elapsed >= 0 &&
        row.pose_age_ms + elapsed < timeout;
      const publisher = text(row.publisher_id ?? value?.publisher_id),
        source = text(row.source),
        device = text(row.device);
      return {
        key: JSON.stringify([publisher, source, device]),
        publisher,
        source,
        device,
        tracker: text(row.tracker),
        tracking: text(row.tracking_state),
        positionValid: validity(row.position_valid),
        orientationValid: validity(row.orientation_valid),
        freshness: !current ? 'historical' : fresh ? 'fresh' : 'stale',
        collision:
          row.collision === true ? 'yes' : row.collision === false ? 'no' : 'unknown',
      };
    });
    const observedPolicy =
      current && isBridgePolicy(value?.policy) ? value.policy : null;
    const observedRevision =
      current &&
      numeric(value?.mapping_revision) &&
      Number.isInteger(value.mapping_revision) &&
      value.mapping_revision >= 0 &&
      value.mapping_revision <= 0xffffffff
        ? value.mapping_revision
        : null;
    return {
      backendStarted: 'unknown',
      publisherRunning: 'unknown',
      current,
      observedPolicy,
      observedRevision,
      revision:
        observedRevision === null || config?.revision === null
          ? 'unknown'
          : observedRevision === config?.revision
            ? 'match'
            : 'different',
      flow: current
        ? `${devices.filter((row) => row.freshness === 'fresh').length} / ${devices.length} fresh native poses observed`
        : 'unknown',
      message:
        this.error ||
        (current
          ? 'Diagnostic snapshot; output delivery is unconfirmed.'
          : 'Diagnostic snapshot is historical; current state is unknown.'),
      devices,
    };
  }
}
