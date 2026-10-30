# MonakaVR repository instructions

This repository owns the producer-independent tracking consumer: public-contract input ingestion, source identity and capability catalog, body assignment, fusion/fallback, IK, and final virtual tracker output.

## Highest-priority architecture invariant: input-producer independence

**Mandatory for all future changes, including fixes, migration work, UI, tests, scripts, and packaging.** This section overrides historical task documents or existing implementation patterns when they conflict. It applies to **every input transport and format**, not only MVIP.

MonakaVR MUST be able to operate without any particular input producer being installed, running, configured, or known to the consumer implementation. A conforming MVIP producer MUST be usable without adding or changing consumer code on the basis of that producer's identity.

### Prohibited consumer behavior

- MUST NOT import, link, embed, or call producer-private APIs, ABIs, SDKs, protocols, internal models, or device-control interfaces in the common consumer runtime.
- MUST NOT discover, install, start, stop, restart, terminate, pair, reconfigure, calibrate, or otherwise control an input producer or its physical devices. This includes indirect control through helper processes or application integrations.
- MUST NOT read, write, validate, migrate, or persist producer-owned configuration, process state, executables, installation paths, diagnostic files, or private state. Consumer-owned input preferences and MVIP connection settings are distinct and permitted.
- MUST NOT provide producer-specific lifecycle, setup, mapping, route, or configuration panels, IPC handlers, tray actions, background services, or executable launchers inside the consumer application.
- MUST NOT branch on, infer implementation type from, or assign special semantics to `publisher_id`, `source_id`, `tracker_id`, device names, manufacturer/model strings, network endpoints, or opaque identities.
- MUST NOT implement input-producer-specific coordinate transforms, packet decoding, recovery algorithms, discovery procedures, resource lookup, or virtual-device registration in the common consumer. Treat conforming MVIP pose data as already normalized to the declared coordinate contract; validate rather than silently reinterpret it.
- MUST NOT require a particular producer, registration service, or external manager to exist before the consumer starts, enumerates available inputs, or exposes assignment controls.
- MUST NOT introduce a special case for one producer to work around missing public contract fields. Report the contract gap and resolve it at the owning protocol/producer boundary instead.

A passive receive/listen/subscribe/reconnect operation performed entirely through the generic public input contract is allowed. It MUST NOT mutate or manage the producer's lifecycle or configuration.

### Permitted consumer responsibilities

- Receive, decode, validate, and admit MVIP messages using the versioned public contract.
- Maintain a generic catalog of input identities, static capabilities, dynamic validity, timestamps, session/space revisions, freshness, and usability.
- Apply consumer-owned source selection, body assignment, explicit fusion/fallback policy, IK, output routing, and final virtual tracker publication.
- Handle generic consumer-side timeout, replay protection, reconnect bookkeeping, and feedback exclusion without commanding producer recovery.
- Accept different conforming MVIP producers through the same catalog, assignment, and solver pathways.
- Preserve existing non-MVIP consumer input compatibility during migration without extending or duplicating producer-specific paths.

**Never equate producer independence with zero consumer policy:** source admission, identity mapping, consumer-owned assignments, constraint solving, and final output remain consumer responsibilities.

### Names and specifications

- This file MUST NOT enumerate or refer to concrete input producer, device, vendor, or external manager names. Express these rules only through abstract roles and public contract names.
- Implementation-specific source identity MUST be treated as opaque data, not as an implicit device class, protocol version, calibration rule, or resource locator.
- If a feature needs new input metadata or functionality, define it at the appropriate versioned public contract boundary and preserve backwards-compatible, fail-closed handling of missing or unsupported fields.
- Existing producer-specific consumer code is a migration liability, not precedent. Inventory references and affected users; isolate or retire it only through an explicitly approved, tested cutover. Do not silently remove working legacy input paths.

### Mandatory review and acceptance gates

For changes that actually affect input reception, GUI, service startup, configuration, or integration, apply the relevant checks below (not the entire list to every unrelated edit):

1. Confirm the application starts and the public input UI operates with no producer installed or running.
2. Demonstrate with two distinct conforming test producers that identical public messages take the same consumer path without source-name-specific branches.
3. Verify that neither startup nor normal use accesses producer-owned processes, files, APIs, configuration, pairing, calibration, or runtime state.
4. Verify that producer identity and descriptive strings do not alter routing, coordinate mapping, assignment, fusion, or output behavior without an explicit generic contract rule.
5. Verify loss/reconnect, capability-versus-validity, identity/session/space, and feedback-exclusion regressions, including an absent or unsupported producer.
6. Review newly added source paths, imports, IPC, settings, tests, scripts, and packaging for producer-specific dependencies. An exception requires explicit architectural approval; task scope alone is not approval.

Treat a violation as an **architecture defect and release blocker**, not an optional cleanup. Record untested gates as `NOT RUN`; do not claim them as passed.

## Product-software verification and historical audit (2026-10-30)

MonakaVR is a producer-independent SlimeVR 6DoF constraint extension, not a physical-tracker quality certification tool. Normal software PASS must demonstrate actual public-input handling, transforms, IK/fallback/output or the relevant backend function; version/source hashes and historic approvals do not substitute for that behavior.

`scripts/test_revision.ps1` uses `-Mode Quick` and `-Mode Full` for product-software checks and `-Mode Audit` for historical source/release-policy checks. Audit may be used for a relevant release, dependency or policy migration, but must not be imposed as a default per-edit requirement. See `docs/verification-lanes.md`. Software PASS is not hardware HIL PASS. The existing mandatory calibration approval/revision model is a migration target under MonakaVR/MonakaBridge#17, not the intended long-term product acceptance criterion.

## Current coordinated Architecture Revision

Older fixed C1/wire-1.0 and generic component-arbitration handoffs are historical implementation baselines, not authority to override the active versioned protocol and source-selection contract. The current coordinated protocol/identity/modality/Main-Fallback semantics are specified by the active shared protocol revision (`docs/architecture-revision.md` and `docs/C2.md` in the protocol owner repository).

Do not retain old behavior solely because it exists in the tree. The active default policy is one explicitly assigned Main Tracker per body target with an optional Rotation Fallback Source. A usable FULL Main owns paired position and orientation; otherwise position is unavailable and the configured rotation-fallback policy applies.

Protocol-dependent changes require an actual generated, source/content-pinned protocol kit from a clean committed contract source. When the matching artifact is missing, stop at the protocol boundary. Do not invent hashes, infer fields from an earlier wire version, or implement a replacement codec.

Maintain existing IK, input interoperability, self-feedback exclusion, lifecycle, and hardware-validation constraints where they do not conflict with the current revision or producer-independence rules.

## Codex task entry point

Read these files before implementation changes for historical context and compatible task constraints:

1. `docs/codex/Task5_MonakaVR.md`
2. `docs/codex/task5/00_upstream_handoff_status.md`

Historical producer-specific integration guidance is **not authorization** to add producer-specific code to the consumer. Treat conflicting instructions as superseded by the architecture invariant above; identify conflicts in the work report instead of silently implementing them.

## Repository-wide constraints

- Work only in this repository unless a coordinated task explicitly authorizes additional repositories.
- Preserve existing validated consumer input paths, IK behavior, and virtual-tracker output except where a scoped, reviewed change requires otherwise.
- Never add a producer-private API, protocol decoder, device-specific coordinate transform, or process-management dependency to the common runtime, application, or packaging.
- Do not independently change shared protocol fields, units, coordinate conventions, timestamp semantics, versions, ports, or codec APIs.
- Use only the exact verified active protocol artifact. Never fabricate substitutes for missing dependencies.
- Treat valid MVIP input as already transformed into its declared output coordinate space; do not add a second input-specific correction in the consumer.
- Main/Fallback assignment must remain explicit. Do not infer relationships from identity strings, quality rank, connection order, or body role.
- Preserve stable IK/calibration state across FULL -> ROTATION_ONLY -> fallback -> FULL transitions; modality changes alone must not recreate topology.
- Never feed consumer Direct output, solver output, or final virtual-tracker output into raw observation/MVIP input. Test feedback exclusion.
- Do not remove an active legacy input path until a conforming public-contract replacement, assignment migration, solver regressions, existing-input regressions, and an explicitly authorized cutover are complete.
- Do not delete branches, files, repositories, or unrelated code. Do not force-push, rebase, amend, reset/rewrite history, or prune remote branches.
- Keep commits small and reviewable. Run applicable builds/tests and report unexecuted checks as `NOT RUN`.
- Do not claim physical-device or interactive-runtime verification unless it was actually performed.

## Codex Cloud managed branch exception

A managed local branch named `work` may not expose or fetch ordinary remote refs. If checked out on `work`, verify and record its actual prepared lineage and dependencies instead of failing solely on the branch name. Never silently switch to `main`.

Before continuing work on an existing branch, incorporate the current producer-independence instructions using a history-preserving update or an explicitly reviewed, documentation-only backport. Do not rewrite frozen review or HIL baselines and do not merge unrelated changes simply to propagate this file.

If working-tree state, the current protocol kit, or historical task documents conflict with this file, report the conflict and stop at the affected boundary rather than changing behavior without authorization.
