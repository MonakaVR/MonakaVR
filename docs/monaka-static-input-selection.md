# Static input declarations and explicit Q / 6DoF selection

This local implementation is based on dev `bd595803ecc015a8d1c49fa3e9cc6e1059094e5c`.
It selectively reuses the capability/assignment model from reviewed local commit
`0cee1fda1f12c4d8cb9379b6ab15e3efe2a91b4b`; it does not merge that older integration
branch or its protocol/contract changes.

The MTP backend keeps a canonical declared-capability set separate from its
latest pose. Pose and TrackerState share the same declaration for the admitted
publisher/source/tracker/session lifetime. Conflicting declarations quarantine
samples and remain visible in diagnostics/catalog metadata. Current modality,
tracking, validity, confidence and freshness continue through the existing
adapter and input profile. Lost/stale metadata never establishes support.

The GUI lists full persistent source identities, including unassigned sources.
Q requires declared orientation; 6DoF requires declared position and orientation.
Unknown/conflicting support cannot create a new assignment. Legacy Slime
orientation streams are Q candidates only; that does not prove independent IMU
authenticity. Their existing name identity remains unchanged and duplicate names
fail closed. Feedback outputs are excluded.

On the first Q choice for an unassigned body only, a confirmed Pos+Q MTP source
also fills 6DoF. Q-only, unknown and Slime sources do not. Explicit/manual choices,
clears and saved choices survive subsequent metadata, loss and reconnect. Six-only
selection is allowed. The fields map into the existing policy: 6DoF is Main,
selected Q is rotation fallback when Main is unavailable. A usable Main FULL pose
retains its paired position/orientation. Q-only selection masks position at the
configuration boundary without changing received observations or Fusion policy.

The product settings page uses the existing GUI WebSocket with bounded namespace
`monaka_inputs_v1`; the server dispatches loopback requests onto its owner thread.
The handler exists only for explicitly enabled Monaka runtime startup. Opening
the UI never activates a receiver or rewrites configuration.

Save validates static support, expected assignment generation, configuration
bytes, a nonblocking file lock and existing configuration validators. It writes
an exact preimage backup and atomically replaces the file before applying live
assignments. Unknown root and assignment members survive. Cancel performs no
write. Failure/uncertain response requires explicit reload before retry. Existing
Bridge policy/revision transactions are untouched and remain covered by their
native/GUI tests.

Source selections use local configuration schema v4. Tracking wire versions and
P1 protocol artifacts are unchanged. Schemas v1-v3 remain loadable under their
existing migration rules; old readers reject v4 rather than silently discard
selection intent. Keep the generated pre-edit backup for offline rollback.

Online assignment edits currently support IK output without active correction
configuration. Existing Direct/Hybrid output ownership and active correction
bindings require a server restart; the save operation rejects those hot edits.
No automatic restart, topology rebuild or Fusion/IK tuning change is performed.

`PICO same-tracker IMU authenticity: NOT VERIFIED / DEFERRED`.
Admission Gate default remains OFF; thresholds remain hardware-unvalidated.
This implementation and its process/browser fixtures are software-only;
`hil_ready=false` and physical 2C-4A is not authorized.
