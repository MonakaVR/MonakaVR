import type { ChildProcess } from 'node:child_process';

/** Shutdown only the retained child; tolerate spawn failure and closed input. */
export function ownedChild(child: ChildProcess) {
  let stopped = child.exitCode !== null || child.signalCode !== null;
  let closing = false;
  let deadline: ReturnType<typeof setTimeout> | undefined;
  let resolveExit: () => void;
  const exited = new Promise<void>((resolve) => (resolveExit = resolve));
  const finish = () => {
    stopped = true;
    clearTimeout(deadline);
    resolveExit();
  };
  child.once('exit', finish);
  child.once('close', finish);
  child.on('error', () => {
    if (!child.pid) finish();
  });
  // A concurrent child exit can close the pipe after the writable check.
  child.stdin?.on('error', () => {});
  if (stopped) finish();
  return {
    process: child,
    close: () => {
      if (stopped || closing) return;
      closing = true;
      if (child.stdin?.writable && !child.stdin.destroyed)
        child.stdin.write('exit\n', () => {});
      deadline = setTimeout(() => {
        if (!stopped && child.pid) child.kill();
      }, 5000);
    },
    waitForExit: () => exited,
  };
}
