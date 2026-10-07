# Strong Trusted HMD support policy (2B-5Q / 2B-5R)

MonakaVR does not infer correction authority from 6DoF pose availability. Only a reviewed backend contract and complete evidence for the exact current immutable sample can qualify for Strong Trusted admission. Missing, invalid or lost evidence closes the trusted boundary. Ordinary pose processing continues independently.

Current OpenVR HMD: **normal pose SUPPORTED; pose visibility YES; Strong Trusted UNSUPPORTED; correction authority NO**. POSE_ONLY, POSE_BOUND, SPACE_BOUND and STRONG_TRUSTED describe completeness; they are not another production enum/state machine. Production keeps `Unavailable(reasons)` / `Ready` and `Rejected` / `Accepted`.

## Provider and receiver contract

`HmdAcceptedPoseMessageSample.providerEvidence` contains immutable `RawHmdProviderPoseEvidence`, frozen with the final P/Q. Generic OpenVR always supplies null. Internal reviewed registration supplies `ReviewedHmdBackendContract` and explicitly establishes `RawHmdProviderSession`; declarations or arbitrary proof strings in a message cannot establish this association. There is no production Strong Trusted backend registration, transport or operator override in this phase.

| Fact | Owner and lifecycle |
| --- | --- |
| sourceIdentity | Reviewed backend/source endpoint, matched to ingress identity; outputs/internal sources excluded |
| providerSessionEpoch | Provider incarnation, independently established; restart/reconnect requires a fresh non-reused token |
| observationId | Ordered nonnegative Long local to that source/provider session, exact P/Q/proof snapshot; gaps allowed, overflow requires a new incarnation |
| rawSpaceOwner / incarnation / generation | Frame-defining provider facts covering every public raw-basis relocalization, reset and mutation; opaque non-reused scoped tokens |
| appliedMapping.outputSpace | Resolved final output descriptor: id, convention, revision; reviewed contract target must agree |
| appliedMapping.outputSpaceEpoch | Provider output/mapping incarnation and change token; distinct from upstream raw generation |
| appliedMapping.calibrationEpoch / revision / identity | Actual sample-applied mapping/calibration; nonidentity requires a nonnegative revision; identity is explicit, never inferred |
| sourceValid | Same-observation provider validity; null/false rejects independently of receipt age or numeric FULL data |
| sourceEpoch / transportSessionEpoch / sequence | Receiver object lifetime, host connection and host accepted-message identity; none substitute for provider facts |
| receivedAtSystemNanos | HOST_RECEIVE_MONOTONIC, System.nanoTime ns, fixed once before queue; acquisition time remains unknown |

Host sequence cannot substitute for observation ID; transport UUID cannot substitute for provider session; sourceEpoch, Universe ID and mapping revision cannot substitute for raw-space generation. Host receipt time cannot substitute for provider acquisition time. No missing provider evidence is synthesized in production.

`deriveRawHmdPoseInputCapability` validates completeness, reviewed source/raw/output association, valid FULL structure, receiver metadata and freshness policy before constructing Ready. Its projections and full expected snapshot come from the sample. The evaluator rechecks these facts against the current provider context and exact current candidate, validates freshness/feedback exclusion, then checks active handle object identity, provider context identity and candidate identity again before allocating input. Internal negative-test copies cannot bypass the evaluator. Ready is not a lease; every use must read the current gate.

`RawHmdFrameProofKind` is retained as the frame-reference portion of the complete provider contract (Option A), not as a second trust level. Existing 25 reason codes retain their meanings. Four codes are added: `hmd_provider_session_unavailable`, `hmd_observation_id_unavailable`, `hmd_raw_space_generation_unavailable`, `hmd_provider_evidence_invalid`. Reference/output and source-validity defects use existing frame reasons and provider-evidence-invalid, avoiding redundant aliases. Consumers exhaustively matching the enum must account for the additive entries.

## Candidate lifecycle and replay

Historical and ordinary current-session receipt records remain separate from the trusted candidate. A current FULL message with missing provider evidence, incomplete/nonfinite P, invalid Q, non-FULL modality/data source or provider validity loss clears trusted authority. A no-X rotation-only update still updates ordinary rotation/velocity while clearing trusted authority. Tracker statuses with `sendData=false` (DISCONNECTED, ERROR, OCCLUDED, TIMED_OUT) clear the candidate; existing BUSY semantics remain usable. OK alone never revives a previous candidate.

Transport open/close invalidates eligibility immediately using the active epoch, without transport-thread mutation of server-thread observation state. A new transport cannot reuse the established provider context. Explicit provider establishment retires the previous transport's provider token; reconnect/disconnect callbacks retire it too. Tracker recreation creates a new sourceEpoch and clears the candidate. Old transport, retired provider or sessionless traffic cannot authorize or revoke a distinct current candidate. Ordinary legacy processing is preserved.

`HmdProviderObservationState` holds one observation high-water and last immutable observation per source/provider context, with bounded retired lifecycle tokens rather than a persistent/global replay cache:

- Polling the same candidate is allowed and never advances sequence or receipt.
- Same ID / same immutable payload retransmission is ignored; it retains the first candidate and receipt, and cannot revive a validity loss. A new host snapshot carrying the reused ID is not accepted as a new trusted sample.
- Same ID / different payload retires the provider scope. A fresh explicitly established provider incarnation is required.
- Lower ID is ignored and cannot replace or refresh the current candidate. Newer invalid evidence clears it.
- Raw/output context changes clear authority while retaining the provider observation high-water. Retired raw/output tokens cannot return; mapping changes require a new output epoch. Retirement bounds exhaustion fails closed for the source lifetime.

Freshness retains `age <= limit` with a positive explicit caller limit. Fixture limit 500 ns accepts 499/500 and rejects 501; it is not a production default. Receipt excludes buffering before decoded ingress and does not prove sensor acquisition age. FeedbackExclusion/RAW_HMD guards remain mandatory.

## Runtime boundary

2B-5P is **NOT READY**. Predictor and Position Correction remain **NOT CONNECTED**. No protobuf schema, OpenVR driver/runtime, MonakaBridge or MonakaProtocol change is made. Synthetic test evidence verifies receiver software only. Physical trusted HIL is **BLOCKED BY BACKEND CAPABILITY**; normal physical HMD smoke is NOT RUN in this software phase.

Future activation must separately prove the reviewed acquisition implementation/endpoint association, mutation coverage, atomic capture/forwarding, provider lifecycle and mapping semantics. The current core Accepted projection does not preserve the full provider contract for downstream runtime use; that design and independent temporal gates remain required before predictor/correction connection.
