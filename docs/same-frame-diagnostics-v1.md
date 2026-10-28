# Same-frame diagnostics v1

Status: opt-in software observation; physical IK/SteamVR validation remains NOT_RUN.

Set JVM property `monaka.diagnostics.frames.path` to a new absolute local file before
starting the generic input consumer. The default is disabled. The file uses CREATE_NEW;
existing evidence is never overwritten. Capture construction failure logs only an
exception class and does not prevent consumer startup. This does not change any input
contract, enable Position Correction, or control a producer.

Each UTF-8 JSONL record has `schema = monaka.same_frame.v1`. Capture takes place on the
server thread after the one generic writeback, with the already resolved tick and its
assignment snapshot. The post-pose hook adds actual solver/skeleton/computed/direct
output values before bridge dataWrite. It does not poll inputs or resolve again. A queued
new packet after the before hook is not part of the current record. An absent post-pose
hook is explicitly recorded as false and cannot substantiate post-IK observation.

## Fields

All integer counters/timestamps/revisions are decimal strings to avoid loss in JSON
consumers. Float positions are metres in the declared normalized space. Quaternion
arrays are xyzw; nonfinite scalar components are null. Null means unavailable, not zero.

| Field | Meaning |
|---|---|
| tick, writeback_tick | Consumer-local tick sequence; must match |
| now_ns, clock_domain | Pinned consumer monotonic tick time, domain consumer_monotonic |
| assignment_generation, paused | Pinned assignment and pause state |
| writeback_owner, writeback_attempted | Actual GenericCommitted receipt |
| position_correction | PRODUCTION_NOT_ENABLED; no trusted Raw HMD admission added |
| observations | Assigned raw observations: pseudonymous identity, target, modality, numerical components, component quality, sample sequence/time/age, source and calibration epochs |
| accepted_mvip | Assigned accepted cache samples: pseudonymous logical identities/session/clock, sequence, remote_timestamp_ns, remote_sent_at_ns, local_sample_ns, mapping/space revision/convention, input_session, input_source, input_device, input_sequence, orientation_evidence, timestamp_kind, modality, validity.position and validity.orientation |
| resolved | Body target, actual selected position/rotation owners and numerical components |
| ik_targets_before/after | Actual calibrated IKConstraint targets (not a reconstructed solver) |
| bones_before/after | Every current bone head/tail/global rotation and retained local length |
| computed_trackers | Actual computed tracker candidates; explicitly not physical teachers |
| direct_outputs | Actual nullable published direct/hybrid component candidates |
| post_ik_hook, solver_enabled | Whether this record crossed the real post-pose hook and solver enabled state |
| output_boundary | PRE_BRIDGE_DATA_WRITE; not proof of downstream transport or runtime acceptance |
| steamvr_observed | Always false; external observation belongs to a separate HIL capture |

Remote timestamps belong to the hashed publisher clock. Never subtract them directly
from consumer now_ns. `local_sample_ns` is the already admitted mapping of sample age.
The collector must not invent cross-machine clock alignment.

Identities are SHA-256 of a random per-capture 256-bit salt plus UTF-8 identity. The salt
is never emitted. Correlation is possible within one capture, not across runs. Raw
publisher/source/tracker/session/device names and exception messages are not emitted.
This is pseudonymization, not a claim that poses themselves are non-sensitive.

## Bounds and failure behavior

Defaults: 128 records queued, 4096 admitted records, 16 MiB written. Constructor bounds
permit at most 1024 queue entries, 65536 frames, 64 MiB. Each serialized record has a
256 KiB ceiling. A full queue drops the current record without waiting. Byte exhaustion
drops the current and queued records and closes admission. Frame exhaustion closes
admission. A writer exception records its class, drops queued data and ends the worker.
The server reads no disk and never joins the worker. `close` stops admission and returns
immediately; an explicit diagnostic owner may await termination for at most five seconds.
A permanently blocked OS write remains a daemon thread until the process exits.

The normal bounded-size path writes only complete JSON lines. An actual OS write error
can leave a partial final line: normalizers must reject it, not treat it as evidence.
Counters `written`, `dropped`, and `failure` are local diagnostic state. Missing frames
are not silently filled or promoted to a successful HIL window.

## Feedback boundary still unresolved

Existing FeedbackExclusion checks opaque prefixes. The current kit accepts a legal
publisher ID starting `monaka-direct:`, but runtime admission excludes it. The new
characterization test records this PREEXISTING_NOT_FIXED architecture defect; its pass
is not contract compliance. Removing existing guards without trustworthy provenance
could admit output feedback. Public versioned provenance/exclusion semantics and an
approved compatibility path remain CONTRACT_DECISION_REQUIRED. No kit changes or new
identity conventions are introduced here.
