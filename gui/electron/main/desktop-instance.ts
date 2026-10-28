import path from 'node:path';

export function desktopInstance(options: Record<string, unknown>) {
  const profile = options.profile;
  if (
    profile !== undefined &&
    (typeof profile !== 'string' || !path.isAbsolute(profile))
  )
    throw new Error('--profile must be an absolute directory');
  const networkOnly = options.networkOnly === true;
  if (networkOnly && (!profile || options.steam || options.install))
    throw new Error(
      '--network-only requires --profile and cannot install or enable Steam integration'
    );
  const port = options.serverPort ?? '21110';
  if (
    typeof port !== 'string' ||
    !/^[0-9]+$/.test(port) ||
    Number(port) < 1 ||
    Number(port) > 65535
  )
    throw new Error('--server-port must be an integer from 1 to 65535');
  return { profile: profile as string | undefined, networkOnly, port: Number(port) };
}
