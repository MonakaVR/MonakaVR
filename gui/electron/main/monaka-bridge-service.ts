import {
  commitBridgeCandidate,
  type BridgeCommit,
} from './bridge-config-transaction.js';
import { spawn } from 'node:child_process';
import { open, realpath, stat, unlink, writeFile } from 'node:fs/promises';
import { basename } from 'node:path';
import { randomUUID } from 'node:crypto';
import {
  isBridgePolicy,
  type BridgeConfiguration,
  type BridgeHealthRead,
  type BridgeLocations,
  type BridgePolicy,
} from '../bridge-types.js';

export interface BridgePreferences {
  read(): Promise<BridgeLocations>;
  write(value: BridgeLocations): Promise<void>;
}
export type BridgeValidator = (
  executable: string,
  candidate: string,
  signal: AbortSignal
) => Promise<void>;
export class BridgeValidationError extends Error {
  constructor(
    message: string,
    readonly kind: 'rejected' | 'launch' | 'timeout' | 'cancelled' | 'output-limit'
  ) {
    super(message);
  }
}
const emptyConfiguration = (locations: BridgeLocations): BridgeConfiguration => ({
  locations,
  token: null,
  policy: null,
  revision: null,
  publisher: null,
  mappings: [],
});
const object = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value);
const uint32 = (value: unknown): value is number =>
  typeof value === 'number' &&
  Number.isInteger(value) &&
  value >= 0 &&
  value <= 0xffffffff;

async function boundedRead(
  path: string,
  maximum: number,
  signal?: AbortSignal
): Promise<Buffer> {
  const file = await open(path, 'r');
  try {
    if ((await file.stat()).size > maximum)
      throw new Error('Bridge file exceeds its size limit.');
    const bytes = await file.readFile({ signal });
    if (bytes.length > maximum) throw new Error('Bridge file exceeds its size limit.');
    return bytes;
  } finally {
    await file.close();
  }
}
function parseConfiguration(bytes: Buffer): Record<string, unknown> {
  const value: unknown = JSON.parse(
    new TextDecoder('utf-8', { fatal: true }).decode(bytes).replace(/^\uFEFF/, '')
  );
  if (
    !object(value) ||
    value.namespace !== 'monaka.bridge' ||
    value.version !== 2 ||
    !isBridgePolicy(value.policy) ||
    !uint32(value.mapping_revision) ||
    typeof value.bridge_id !== 'string' ||
    !value.bridge_id ||
    !Array.isArray(value.mappings)
  ) {
    throw new Error('Expected an existing monaka.bridge version 2 configuration.');
  }
  return value;
}
function configuration(
  value: Record<string, unknown>,
  locations: BridgeLocations
): BridgeConfiguration {
  return {
    locations,
    token: randomUUID(),
    policy: value.policy as BridgePolicy,
    revision: value.mapping_revision as number,
    publisher: value.bridge_id as string,
    mappings: (value.mappings as unknown[]).filter(object).map((row) => {
      const profile =
        object(value.profiles) && typeof row.profile === 'string'
          ? value.profiles[row.profile]
          : undefined;
      return {
        source: typeof row.source_id === 'string' ? row.source_id : 'unknown',
        device: typeof row.device_id === 'string' ? row.device_id : 'unknown',
        tracker: typeof row.tracker_id === 'string' ? row.tracker_id : 'unknown',
        profile: typeof row.profile === 'string' ? row.profile : 'unknown',
        inputSpace: typeof row.input_space === 'string' ? row.input_space : 'unknown',
        inputRevision: uint32(row.input_revision) ? row.input_revision : null,
        approved: typeof row.space_approved === 'boolean' ? row.space_approved : null,
        profileApproved:
          object(profile) && typeof profile.approved === 'boolean'
            ? profile.approved
            : null,
      };
    }),
  };
}

export const validateBridgeCandidate: BridgeValidator = (
  executable,
  candidate,
  signal
) =>
  new Promise((resolve, reject) => {
    signal.throwIfAborted();
    const child = spawn(executable, [candidate, 'validate'], {
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let error = '',
      size = 0,
      failure: Error | null = null;
    const stop = (reason: Error) => {
      failure ??= reason;
      child.kill();
    };
    const abort = () =>
      stop(
        new BridgeValidationError(
          'Configuration save cancelled before commit.',
          'cancelled'
        )
      );
    signal.addEventListener('abort', abort, { once: true });
    const timeout = setTimeout(
      () => stop(new BridgeValidationError('Bridge validation timed out.', 'timeout')),
      15_000
    );
    const data = (chunk: Buffer, stderr: boolean) => {
      size += chunk.length;
      if (size > 64 * 1024)
        stop(
          new BridgeValidationError(
            'Bridge validation output exceeded its limit.',
            'output-limit'
          )
        );
      else if (stderr) error += chunk.toString('utf8');
    };
    child.stdout.on('data', (chunk: Buffer) => data(chunk, false));
    child.stderr.on('data', (chunk: Buffer) => data(chunk, true));
    child.once('error', (cause) => {
      failure = new BridgeValidationError(cause.message, 'launch');
    });
    child.once('close', (code) => {
      clearTimeout(timeout);
      signal.removeEventListener('abort', abort);
      if (failure) reject(failure);
      else if (signal.aborted)
        reject(
          new BridgeValidationError(
            'Configuration save cancelled before commit.',
            'cancelled'
          )
        );
      else if (code !== 0)
        reject(
          new BridgeValidationError(
            `Bridge validation failed: ${error.trim() || `exit ${code}`}`,
            'rejected'
          )
        );
      else resolve();
    });
  });

// Owns only local configuration I/O. No ingress, pose conversion, calibration,
// output sockets, driver or backend lifecycle is implemented here.
export class MonakaBridgeService {
  private snapshot: {
    result: BridgeConfiguration;
    bytes: Buffer;
    value: Record<string, unknown>;
  } | null = null;
  private generation = 0;
  private pending: { id: string; abort: AbortController } | null = null;
  private reading: { id: string; abort: AbortController; done: Promise<void> } | null =
    null;
  private selecting = false;
  private disposed = false;
  constructor(
    private readonly preferences: BridgePreferences,
    private readonly validate: BridgeValidator = validateBridgeCandidate,
    private readonly commit: BridgeCommit = commitBridgeCandidate
  ) {}
  get busy(): boolean {
    return !!this.pending || !!this.reading || this.selecting || this.disposed;
  }

  async load(requestId: string = randomUUID()): Promise<BridgeConfiguration> {
    if (this.reading?.abort.signal.aborted) await this.reading.done;
    if (this.disposed) throw new Error('Bridge control window closed.');
    if (this.pending || this.reading || this.selecting)
      throw new Error('A Bridge operation is already in progress.');
    const generation = ++this.generation;
    const previous = this.snapshot;
    const abort = new AbortController();
    let finish!: () => void;
    const done = new Promise<void>((resolve) => {
      finish = resolve;
    });
    this.reading = { id: requestId, abort, done };
    try {
      const locations = await this.preferences.read();
      abort.signal.throwIfAborted();
      if (!locations.configPath) {
        this.snapshot = null;
        return emptyConfiguration(locations);
      }
      const bytes = await boundedRead(
        locations.configPath,
        2 * 1024 * 1024,
        abort.signal
      );
      const value = parseConfiguration(bytes);
      abort.signal.throwIfAborted();
      if (generation !== this.generation || this.disposed)
        throw new Error('Superseded Bridge configuration read.');
      const result = configuration(value, locations);
      this.snapshot = { result, bytes, value };
      return result;
    } catch (cause) {
      this.snapshot = abort.signal.aborted && !this.disposed ? previous : null;
      throw cause;
    } finally {
      this.reading = null;
      finish();
    }
  }

  async configure(
    kind: 'config' | 'validator',
    selected: string
  ): Promise<BridgeConfiguration> {
    if (this.pending || this.reading || this.selecting || this.disposed)
      throw new Error('Wait for the current Bridge operation to finish.');
    this.selecting = true;
    try {
      const path = await realpath(selected);
      if (!(await stat(path)).isFile()) throw new Error('Select an existing file.');
      if (kind === 'config')
        parseConfiguration(await boundedRead(path, 2 * 1024 * 1024));
      else if (basename(path).toLowerCase() !== 'monaka_bridge_config.exe')
        throw new Error('Select the tested monaka_bridge_config.exe utility.');
      if (this.pending || this.disposed)
        throw new Error('Bridge selection changed while another operation started.');
      const previous = await this.preferences.read();
      const next = {
        ...previous,
        [kind === 'config' ? 'configPath' : 'validatorPath']: path,
      };
      await this.preferences.write(next);
      this.selecting = false;
      return this.load();
    } finally {
      this.selecting = false;
    }
  }

  async health(): Promise<BridgeHealthRead> {
    const locations = await this.preferences.read();
    if (!locations.configPath)
      return {
        configPath: null,
        value: null,
        error: 'Select the Bridge configuration to read diagnostics.',
      };
    try {
      const bytes = await boundedRead(
        locations.configPath + '.status.json',
        32 * 1024 * 1024
      );
      const value: unknown = JSON.parse(
        new TextDecoder('utf-8', { fatal: true }).decode(bytes).replace(/^\uFEFF/, '')
      );
      if (
        !object(value) ||
        !Array.isArray(value.devices) ||
        value.devices.length > 16384
      )
        throw new Error('Invalid Bridge diagnostic snapshot.');
      return { configPath: locations.configPath, value, error: null };
    } catch (cause) {
      return {
        configPath: locations.configPath,
        value: null,
        error:
          cause instanceof Error ? cause.message : 'Bridge diagnostics unavailable.',
      };
    }
  }

  async apply(
    token: string,
    policy: BridgePolicy,
    requestId: string
  ): Promise<BridgeConfiguration> {
    if (this.pending || this.reading || this.selecting || this.disposed)
      throw new Error('A Bridge operation is already in progress or closed.');
    const loaded = this.snapshot;
    if (!loaded || loaded.result.token !== token || !isBridgePolicy(policy))
      throw new Error('Reload Bridge configuration before applying.');
    const { configPath, validatorPath } = loaded.result.locations;
    if (!configPath || !validatorPath)
      throw new Error('Choose the configuration and tested Bridge validator first.');
    const abort = new AbortController();
    this.pending = { id: requestId, abort };
    const signal = abort.signal;
    const candidate = `${configPath}.desktop-candidate-${randomUUID()}`;
    try {
      signal.throwIfAborted();
      const locations = await this.preferences.read();
      if (
        locations.configPath !== configPath ||
        locations.validatorPath !== validatorPath
      )
        throw new Error('Bridge locations changed; reload first.');
      const unchanged = async () => {
        if (!(await boundedRead(configPath, 2 * 1024 * 1024)).equals(loaded.bytes))
          throw new Error(
            'Configuration changed in another tool; reload before applying.'
          );
      };
      await unchanged();
      if (loaded.result.policy === policy) return loaded.result;
      const revision = loaded.result.revision!;
      if (revision === 0xffffffff) throw new Error('Mapping revision exhausted.');
      const value = { ...loaded.value, policy, ['mapping_revision']: revision + 1 };
      const bytes = Buffer.from(JSON.stringify(value));
      await writeFile(candidate, bytes, { flag: 'wx' });
      signal.throwIfAborted();
      await this.validate(validatorPath, candidate, signal);
      await this.checkRuntimeContractValidator(value, validatorPath, candidate, signal);
      signal.throwIfAborted();
      await unchanged();
      await this.commit(validatorPath, configPath, loaded.bytes, candidate, signal);
      // No cancellation check after commit: report the actual saved result.
      const result = configuration(value, loaded.result.locations);
      if (!this.disposed) this.snapshot = { result, bytes, value };
      return result;
    } finally {
      await unlink(candidate).catch(() => {});
      this.pending = null;
    }
  }

  cancel(requestId: string): void {
    if (this.pending?.id === requestId) this.pending.abort.abort();
    if (this.reading?.id === requestId) this.reading.abort.abort();
  }

  private async checkRuntimeContractValidator(
    value: Record<string, unknown>,
    executable: string,
    candidate: string,
    signal: AbortSignal
  ): Promise<void> {
    const rows = Array.isArray(value.mappings) ? value.mappings : [];
    const index = rows.findIndex(
      (row) => object(row) && Object.hasOwn(row, 'runtime_contract')
    );
    if (index < 0) return;
    // The existing P2R CLI requires runtime_contract ID/provenance. An older
    // validator silently ignores this extension; never count that as validation.
    // Probe only temporary config bytes, with no backend or normalization logic.
    const probe = `${candidate}.contract-probe`;
    const invalid = structuredClone(value);
    const mapping = (invalid.mappings as Record<string, unknown>[])[index];
    mapping['runtime_contract'] = {
      ...(object(mapping.runtime_contract) ? mapping.runtime_contract : {}),
      id: '',
      provenance: '',
    };
    try {
      await writeFile(probe, JSON.stringify(invalid), { flag: 'wx' });
      let rejected = false;
      try {
        await this.validate(executable, probe, signal);
      } catch (cause) {
        if (cause instanceof BridgeValidationError && cause.kind === 'rejected')
          rejected = true;
        else throw cause;
      }
      signal.throwIfAborted();
      if (!rejected)
        throw new Error(
          'This validator ignores P2R runtime_contract. Select the matching P2R monaka_bridge_config.exe.'
        );
    } finally {
      await unlink(probe).catch(() => {});
    }
  }
  dispose(): void {
    this.disposed = true;
    ++this.generation;
    this.snapshot = null;
    this.pending?.abort.abort();
    this.reading?.abort.abort();
  }
}
