# Validation workflow — Pixel primary, local Android checks

## Current owner-selected policy

In TTL-015 deployment `d-77bede`, Sinh explicitly selected **Apply this workflow
split** after reviewing the proposal below. This is the current repository
validation allocation; it does not rewrite historical signed plans or turn
unexecuted tests into passes.

| Check | Where | Pre-merge expectation |
|---|---|---|
| All discovered host unit tests | GitHub Actions, also runnable locally | Passing exact PR head; no failures/errors/skips |
| Accounting/workflow safety self-tests | GitHub Actions and local | Passing; pinned actions, read-only token, unskippable host tests and retained failure evidence |
| Debug app and Android-test APK builds | Local | Both compile from the candidate; record hashes |
| Android lint | Local | Zero errors; document warning dispositions |
| Relevant feature/regression Android tests | Authorized isolated Pixel context | Selected method inventory, actual results and build identity recorded; no required exhaustive all-device suite |
| Feature hands-on checks | Pixel | Check the affected user behavior, camera/media/input/lifecycle boundaries as applicable; record actual observations |
| OPPO/other devices and exhaustive cross-device UAT | Optional follow-up | Risk-based supplementary coverage; not an automatic merge blocker |
| Diff review and owner integration decision | PR/session | Record findings and unresolved limitations honestly; no automatic release/UAT approval |

Pixel is the primary device, not a claim of universal Android compatibility.
Expand local regression/device coverage when the changed paths warrant it (e.g.
encoder/store/schema changes). Known failures in the chosen required scope still
block integration until fixed or explicitly accepted by the owner. Missing/skipped
optional checks stay **not run**, never passed.

## Hosted scope

`.github/workflows/android.yml` runs accounting/workflow self-tests and:

```sh
./gradlew --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  :app:testDebugUnitTest
```

The full SDK/JDK setup remains necessary to compile Android-dependent production
code for host tests. Existing runner/JDK/SDK/action pins, exact head checkout,
read-only token and complete logs/test-results/report digests/uploads remain.
Hosted CI does **not** execute APK assembly, lint, device instrumentation or human
UAT. A green host-only run must not be reported as evidence of those checks.

## Local scope

With the existing approved JDK17/SDK36/offline cache, use the environment setup in
[the offline runbook](OFFLINE_INSTRUMENTATION.md) and run:

```sh
./gradlew --offline --no-daemon --console=plain --rerun-tasks \
  -Pandroid.builder.sdkDownload=false :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Retain fresh JUnit XML, both APK hashes, lint XML/warning rationales and the
source/build identity. Documentation/CI-only changes need not rerun unchanged
Pixel camera behavior; identify the last app/test-source candidate and keep its
evidence separate from the new host-only CI head. Changes to compiled app/test
sources require renewed relevant local/Pixel coverage.

Device operations still require a selected authorized device and a disposable
context. Preserve existing app/media/defaults. No implied app replacement,
clear-data, uninstall, server restart, global setting change or device permission
change. Separate package builds must disclose their application-ID/label delta;
they are not execution of a normal-package APK. Never use `connectedDebugAndroidTest`
with automatic uninstall; see the runbook before selecting a runner.

## TTL-015 application of this policy

At app-source candidate `d7f20565eecc644b0f58276b91dc51c545addf7a`:

- Local: 112 host tests passed, both APKs built, lint 0 errors / 78 warnings.
- Pixel: 17/17 selected Android cases passed; actual full-screen blackout,
  pickup-to-reveal and placement/idle-to-blackout observed with capture continuing.
- Rear Adaptive capture/export smoke and sampled JPEG/MP4 images verified before
  the motion addition; that historical media check is not relabelled a later
  all-mode/all-lens test.
- Full 116-method Android execution, OPPO, exhaustive UAT and independent review
  were not performed. Under the revised policy exhaustive cross-device coverage
  is follow-up, not a required two-phone acceptance gate. No independent review
  or full human UAT pass is claimed; source review/owner integration decision
  must not be represented as either.
- Prior full hosted run [41262489862](https://github.com/sinh-x/lapse-cam/actions/runs/41262489862)
  passed on that head. The workflow-only revision needs its own matching **host-only**
  run; previous APK/lint CI evidence remains historical.

Details, failure history, source equivalence and hashes:
[TTL-015 continuation](sessions/d-77bede-ttl015-review.md).

## Historical contracts

Older ticket plans may name a larger independent matrix and two-phone UAT.
Retain their bytes and history; record explicit owner scope revisions rather than
silently approving unexecuted cases. This policy does not manufacture PA review
receipts or retroactively close unrelated TTL-006/TTL-011/TTL-014 validation debt.

`tools/verify_timebox_results.py` retains the default **ttl011-full** workflow
profile for its historical candidate accounting. The current workflow is checked
with an explicitly separate **host-only** profile in self-tests. Cross-profile
negative tests prevent the reduced hosted command from silently satisfying the
old full-hosted contract. Other artifact, authority and test accounting checks
remain intact.
