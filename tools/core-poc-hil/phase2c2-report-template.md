# Phase 2C-2 — Real HIL: 6DoF FULL -> IMU/IK Fallback -> 6DoF Recovery

Status: NOT RUN

Record actual values. 2C-1 software proof does not establish real HIL or receiver stability.

- Repo / branch / full commit / remote SHA:
- Harness ZIP SHA256 / per-file manifest SHA256 / Java version:
- Config SHA256 / state-machine version / dwell and blend settings:
- Operator / time / selected real 6DoF source / independent IMU / independent HMD-root source:
- Source connection performed once; reconnect is not a gate:
- Body-center/frame and common-world calibration assertion, method and limits:
- Capture SHA256 / event counts / marks / complete raw input SHA256:
- FULL -> continuous correction -> loss -> fallback -> recovery dwell -> blend -> FULL observed:
- Correction A/B results and corrected fallback-start evidence:
- Position/angular discontinuity at loss / recovery / blend cancellation:
- Raw/corrected IK residual before loss / fallback drift growth / recovery candidate / convergence:
- Actual solved IK residual (distinct from predictor/corrected target):
- Dwell elapsed / physical sample span / reset count / blend time:
- Max positional/angular output velocity during blend / chatter count / cancellation count:
- Short dropout / chatter / dwell re-loss / blend re-loss / large and small residual:
- Capture anomalies / source freshness / session/world identity changes:
- Receiver quarantine: unchanged; no production receiver stability certification:
- PASS / FAIL / BLOCKED with evidence and follow-up for 2C-3 tuning:

No numeric tuning claims without measured evidence. Do not label replay/synthetic input as real HIL.
