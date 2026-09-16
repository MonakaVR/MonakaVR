# Architecture revision software integration checkpoint

Active contract: C2/wire v2. All consumers pin actual kit source 572e58cfa20b8b4335207ea5dbcb3f04c587ddff, ZIP SHA256 a55567425d071e7338b89f4e6caa925676014fd356aa585730deb1f9d11cd7c3. Contract is frozen; no numeric v1 auto-upgrade.

## Runtime integration

One MonakaRuntime/ConstraintPipeline ingests Slime and MTP. Tracker identity is publisher/source/tracker; source lifetime is publisher/source. Sessions, endpoints, roles, modality and revisions never enter persistent input identity. MTP uses accepted-only bounded session leases, retired UUID guards, first-admission age, sequence and absence watermarks. Space/clock errors invalidate only the affected tracker. Normal inbox budget remains 256; resume admits at most the bounded queue capacity 1024 while samples are suspended.

TrackerBodyAssignments holds one Main and optional explicit rotation fallback per target. ConstraintResolver delegates to the reviewed MainFallbackPolicy: healthy FULL owns both; otherwise no position, usable explicit fallback then explicitly ROTATION_ONLY Main, else nothing. Incomplete FULL never silently becomes rotation-only. Unrelated sources never compete. Candidate tests remain unchanged; candidate helpers do not create a second active store or policy.

Slime identity uses stable Tracker.name rather than connection-order id. Protobuf FULL maps to FULL, IMU to ROTATION_ONLY, NONE/absent/PRECISION to NONE. The existing numeric Slime path is unchanged when the feature gate is false. Numeric position in rotation-only packets cannot reach a Monaka constraint. The upstream OpenVR driver uses its IMU data-source value for Fallback_RotationOnly, not proof of a raw IMU stream; physical behavior remains NOT RUN.

Private IK proxy identity is stable across modality/fallback changes. Necessary component-mask rebuilds migrate actual IKConstraint offset and rotationOffset by stable input name/body relation, including unrelated retained inputs. Solver iteration/math is unchanged. Pause does not revive samples or clear calibration; removal prunes retained identity. Existing full-frame updates do not rebuild topology.

## Configuration and compatibility

Local config version 2 uses body, mainTracker and rotationFallbackTracker. MTP references require kind=mtp plus publisher_id/source_id/tracker_id. Slime references use kind=slime plus persistent name. Duplicate targets, self fallback and sharing a tracker across targets reject atomically.

Version 1 collapsed Bridge/source identity cannot be guessed. MonakaConfiguration.load(path, explicitMapping) and migrate(path, explicitMapping) require a full C2 identity per legacy pair, produce Main-only assignments, and reject ambiguity. migrate writes a non-overwritten .pre-c2.bak before saving v2. No automatic fallback is inferred. Restart reload preserves configured identity/relationship.

## Validation and next checkpoint

Windows/JDK17 repository wrapper: core 588 tests, desktop 6 tests, zero failures/errors/skips; shadowJar PASS. Includes the unchanged candidate suites, C2 fixture compatibility, real separate-JVM loopback, numerical computed-hip movement, feature-off Slime parity, feedback exclusion, pause backlog replay, tracker-local space/clock fault, session lease/retired replay, and non-zero calibration retention through FULL -> ROTATION_ONLY -> external fallback -> FULL plus pause and another target rebuild.

Do not use the old Task5 C1 release report/JAR as v2 evidence. scripts/verify_task5.py and historical packaging still require coordinated v2 release-tool updates; they are not the current checkpoint acceptance gate. No new handoff release artifact was produced.

Remaining: full five-repository E2E (actual backend/Bridge process into this runtime), complete cross-repository release regression and artifact provenance, local test/local-revision-runner orchestration, hardware and SteamVR interactive checks. Runner branch is not merged. All hardware/axes/scale/occlusion/Hub semantics and cutover are NOT RUN. This checkpoint does not authorize further legacy removal or release.
