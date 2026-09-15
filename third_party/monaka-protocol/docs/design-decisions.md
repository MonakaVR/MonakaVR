# Task 1 implementation decisions

C1 candidate only; the reference master has not been provided. No master
reconciliation or final specification approval is claimed.

## Repository baseline

- Audited base: main (unborn), no commit.
- Fetched actual main: `663aea9d2d7ad4116e6ce7c16a4f74bec5416f02`.
- Existing work branch: `5ac2a963d9ebb4f897bfe32a7f2884ae1cede3b9`.
- Common ancestor: `53494e5b238075f58cb5d59fd6645cb1aed4482a`.
- The main and work branch histories diverge, but their complete trees are
  identical (`git diff origin/main HEAD` was empty). Continue the existing
  remote work branch without rewriting either history. Bootstrap is unnecessary.
- Initial working tree was clean. Fetch used `--no-prune`.

## Contract representation

`docs/C1.md` is the exact UTF-8 LF text between the common C1 markers (excluding
the markers) in the task document. Its expected hash is
`3a76435c8b25b3975028f9a83c4dc3d1e3848806c9608d885f5bba74a1198ed3`.
Schemas and typed language bindings are generated from a checked-in description;
semantic rules are implemented independently in both codecs and checked using
shared fixtures and cross-language tests. Unknown optional fields are accepted
but are not retained in the decoded public model. Encoders emit minor zero.
Object member order is not part of the wire ABI. Container nesting counts the
root object as depth one; depth greater than sixteen is MalformedJson.

Error precedence is transport size, UTF-8, JSON syntax/duplicate/depth, message
dispatch, structural validation, then semantic constraints. Where multiple
independent defects coexist, consumers should not rely on which is reported.
Schema constraints are supplemented by byte length, U63, finite numbers,
quaternion, capability, confidence, and timestamp validators.

Protocol does not implement socket services, vendor access, clocks, replay
caches, mapping, calibration, roles, solvers, or runtime lifecycle. Consumer
sequence scenarios are test data, not a stateful protocol service.
