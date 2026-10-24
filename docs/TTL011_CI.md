# TTL-011 hosted CI progress

Sinh explicitly authorized committing/pushing the TTL-011 branch and running scoped
GitHub Actions, then selected a **draft PR to develop**. No merge, release,
repository setting/secret change or device CI is authorized.

- Initial implementation commit: `06cce998255789a7748e71834d78610296161467`.
- Draft PR: https://github.com/sinh-x/lapse-cam/pull/1 .
- Original run: https://github.com/sinh-x/lapse-cam/actions/runs/40092858507 .
  It correctly reported this exact feature head, but **failed** before host/build/
  lint checks: `sdkmanager: command not found`. Those checks were skipped, not
  passed. Setup-Java itself succeeded with Temurin17.0.16+8. Failure evidence and
  the failed run remain retained.

The workflow correction resolves the installed SDK's command-line-tools path
explicitly (preferring latest, otherwise an executable versioned SDK manager),
adds its bin/platform-tools paths, persists ANDROID_HOME for later steps and
records SDK discovery/version/package output. It does not upgrade the pinned SDK
platform36/build-tools35.0.0, JDK, Gradle, AGP or Kotlin. Public upstream Ubuntu24.04
runner-image documentation was read as diagnostic context; it is not proof of a
successful run. No local SDK/tool/dependency download was performed.

Local regression coverage now includes a synthetic SDK12.0 layout with no SDK
manager on the initial PATH, plus negative workflow capability/pin/command cases.
TTL-011 accounting self-tests **7/7**, generic helper **19/19** and all workflow
shell blocks (`bash -n`) pass. These synthetic checks do not constitute hosted CI.
The correction changes only workflow/accounting/docs, not application or Android
regression source; the tested/installed APK source is unchanged.

Both Pixel and the later separately requested OPPO CPH2699/API36/user0 installation
used the same local app digest
`d413acd7ae2e4c25336071dc141749b0d1c8c686e830ba6708fb2b372b3a1730`,
verified against each installed base.apk. OPPO install was an update only, no test
APK, UI/capture/permission operation, data clearing/uninstall, or matrix device
substitution. The mandatory review target remains the approved Pixel.

The SDK-path correction's run
https://github.com/sinh-x/lapse-cam/actions/runs/40093232884 passed all checks on
`8a1446cec1af6defcc8210bcb540dcefb3ceae9f`. Its authenticated artifact ZIP digest
matched, but a strict manifest audit found the listed
`outputs/logs/manifest-merger-debug-report.txt` was not uploaded (only outputs/apk
had been included). That original green run is retained as checks-passing but
artifact-incomplete, not relabelled complete. The subsequent retention correction
uploads the full checksummed outputs tree and adds an exact-path negative regression;
application/test source remains unchanged. The first negative fixture exposed that
a substring check accepted the narrower path; the validator now checks the exact
upload line, with7/7 self-tests passing again. Its failed local assertion is retained
in native command receipts.

At this file's write, a fresh final exact-head hosted rerun is still required. Its final
commit/run/attempt/event/head_sha, runner/JDK/SDK and artifact digests must be
recorded in the PR/ticket evidence without editing an already-reviewed candidate
merely to embed its own SHA. Full80 Android execution, independent admission/
matrix/review and complete TS-1–14 remain separate acceptance gates regardless of
hosted CI success. Do not relabel the original failed run or local checks green.
