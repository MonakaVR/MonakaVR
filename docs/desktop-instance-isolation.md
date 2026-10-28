# Explicit desktop instances

The Windows Electron entry point accepts `--profile <absolute-directory>`,
`--server-port <1..65535>`, `--network-only`, and `--monaka-mtp`.
`--path` continues to select the distribution folder containing `slimevr.jar`.
No option changes the normal default profile, WebSocket port 21110, tracker
port, or native integration behavior when omitted.

A network-only instance requires an explicit profile and rejects installer
or Steam integration flags. It runs the real Desktop main, Core, IK, public
WebSocket API, public MVIP receiver and consumer configuration writer.
Native device discovery, serial/flashing, native output pipes, hotkeys and
external runtime configuration access are not started. Slime UDP remains the
same parser on an OS-allocated loopback-only socket with discovery broadcasts
disabled. MVIP port 0 in the consumer-owned `monaka-mtp.json` similarly asks
the OS to allocate a loopback port. These flags do not claim physical output.

The explicit profile contains `vrconfig.yml`, `monaka-mtp.json`, logs, and
`gui/` preferences/session data. Existing files are not automatically migrated.
The renderer receives the selected WebSocket port through its ordinary URL
connection parameters. A per-profile Electron instance lock focuses the
existing GUI on a second launch instead of starting another owned JVM.

For a direct JVM launch the equivalent environment is
`MONAKA_PROFILE_DIRECTORY`, `MONAKA_NETWORK_ONLY=true`, and
`MONAKA_WEBSOCKET_PORT` (0 is supported when no renderer needs a known port).
Malformed values fail before runtime startup. Set child APPDATA/LOCALAPPDATA
to independent directories when launching the JVM outside Electron.

Electron sends `exit` to its retained JVM stdin and awaits process exit on
quit; it terminates only that child if the graceful deadline expires. Closing
the parent pipe (including a parent crash) also requests JVM shutdown. No
process is selected for termination by executable name or port ownership.

This is an isolated software acceptance path, not deployment or adoption of
the separate Issue16 retirement candidate. Optional legacy management remains
unchanged on the non-retirement branch; normal public-input startup does not
open its selected configuration/helper paths.
