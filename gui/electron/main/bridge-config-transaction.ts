import { spawn } from 'node:child_process';
import { readFile, unlink, writeFile } from 'node:fs/promises';

// One bounded native commit. The CLI owns the OS-lifetime lock and exact-byte CAS.
export type BridgeCommit = (
  executable: string,
  target: string,
  expected: Buffer,
  candidate: string,
  signal: AbortSignal
) => Promise<void>;

function command(executable: string, args: string[], signal: AbortSignal) {
  return new Promise<string>((resolve, reject) => {
    signal.throwIfAborted();
    const child = spawn(executable, args, {
      shell: false,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let output = '',
      error = '',
      size = 0,
      failure: Error | null = null;
    const stop = (cause: Error) => {
      failure ??= cause;
      child.kill();
    };
    const abort = () =>
      stop(new Error('Save interrupted; reload to verify commit outcome.'));
    signal.addEventListener('abort', abort, { once: true });
    const timeout = setTimeout(
      () =>
        stop(
          new Error('Configuration transaction timed out; reload to verify outcome.')
        ),
      15_000
    );
    const data = (b: Buffer, stderr: boolean) => {
      size += b.length;
      if (size > 64 * 1024) stop(new Error('Configuration transaction output limit.'));
      else if (stderr) error += b.toString('utf8');
      else output += b.toString('utf8');
    };
    child.stdout.on('data', (b: Buffer) => data(b, false));
    child.stderr.on('data', (b: Buffer) => data(b, true));
    child.once('error', (cause) => {
      failure = cause;
    });
    child.once('close', (code) => {
      clearTimeout(timeout);
      signal.removeEventListener('abort', abort);
      if (failure) reject(failure);
      else if (code !== 0)
        reject(new Error(error.trim() || `Configuration transaction exit ${code}`));
      else resolve(output.trim());
    });
  });
}

export const commitBridgeCandidate: BridgeCommit = async (
  executable,
  target,
  expected,
  candidate,
  signal
) => {
  let version: string;
  try {
    version = await command(executable, [target, 'transaction-version'], signal);
  } catch (cause) {
    if (signal.aborted) throw cause;
    throw new Error('A matching shared transaction CLI is required. ' + String(cause));
  }
  if (version !== 'monaka-config-transaction-v1')
    throw new Error('A matching shared transaction CLI is required.');
  const snapshot = candidate + '.expected';
  const bytes = await readFile(candidate);
  await writeFile(snapshot, expected, { flag: 'wx' });
  try {
    try {
      await command(executable, [target, 'commit', snapshot, candidate], signal);
    } catch (cause) {
      // Readback cannot establish which writer committed identical bytes.
      // Interrupted callers must reload; never silently retry or claim ownership.
      if (
        (signal.aborted || String(cause).includes('timed out')) &&
        (await readFile(target)).equals(bytes)
      )
        throw new Error(
          'Save outcome unknown after interruption; target matches the proposed configuration. Reload before editing.'
        );
      throw cause;
    }
  } finally {
    await unlink(snapshot).catch(() => {});
  }
};
