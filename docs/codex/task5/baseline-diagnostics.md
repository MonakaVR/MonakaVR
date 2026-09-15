# Baseline diagnostic failures

Before Task5 functional changes, the required wrapper invocation at prepared HEAD
`af6bcf69dfb992d330a25187f01f4fbab61c3498` ran 564 core tests and failed 12.
The raw XML is preserved in `build/task5-baseline` for the final evidence package.
Each failure was an unconditional `assertTrue(false, diagnosticText)` in an IK
report method, not a failed tolerance or behavioral assertion.

Those 12 methods now publish the same computed text through JUnit `TestReporter`.
Their calculations still execute; no tests are excluded or disabled. Existing
conditional numerical assertions and solver convergence tests remain unchanged.
Successful execution of a diagnostic report is evidence that the report ran, not
proof of physical accuracy or a new convergence guarantee. Task5 additionally
requires separate numerical EffectiveConstraint-to-IK/computed-tracker tests.

The baseline's original failure is retained as FAIL in the audit; it is not
retroactively relabeled PASS.
# Baseline verification disposition

The follow-up run passed all 564 core tests and built the desktop shadow JAR.
Desktop tests remained FAIL: four old native smoke tests require the unset
`monaka.pico.bridge.library` / `monaka.pico.bridge.smokePublisher` properties.
No vendor library was invented or hardware test marked PASS. Their original XML
is retained in `build/task5-baseline/desktop` for the final evidence package.
