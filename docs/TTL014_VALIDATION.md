# TTL-014 validation helper — execution contract

Authority is the unchanged approved r002 requirements/UAT pair and exact nine-command
matrix, not this guide. `tools/verify_sync_results.py` is an implementation deliverable,
**not a launcher, readiness approval, independent reviewer or UAT substitute**.
Only the independently admitted executor runs V1–V9 in the approved order/environment.
Do not start a shadow review, retry an unchanged PA denial, rewrite approvals or
substitute commands/output paths because a tool/gate is unavailable.

## Modes and evidence

- `--self-test --output PATH`: isolated positive/negative accounting tests. Without
  PA_FEATURE_SHA the result has no candidate binding and cannot satisfy V1/V9.
  With the exact approved environment and clean frozen candidate, V1 additionally
  writes absent-only `.validation/TTL-014/inventory.json` (all raw tracked-source
  hashes and complete Kotlin/Go top-level method sets). Original baseline method
  identities, pinned build inputs, lock and existing read-only CI are checked.
- `--server-fixtures --binary PATH --output PATH`: requires V1 inventory and fresh
  V3 binary with exact clean Git build identity/toolchain. Runs native admin/rejection
  fixtures, complete Go fixture suite on synthetic private/loopback TLS data and
  pure optional-module evaluation. No live listener, certificate or service setup.
- `--android-fixtures --device pixel|oppo --targets PATH --output PATH`: requires
  all native readiness/admission gates, an exact private selector and matching durable
  Sinh permission below. Rechecks current tuple, existing ADB protocol compatibility,
  fixed JDK/SDK and APK identities. Runs the **entire discovered candidate suite once**,
  keeps APKs installed, forbids incompatible-uninstall fallback, archives native XML,
  HTML and stdout privately, and accounts every method without skips. An existing
  enrolled sync context is rejected before instrumentation; there is no reset/disable
  workaround. The current adapter supports only explicitly authorized current user0;
  another profile stops rather than silently testing/installing in a different user.
- `--account --root PATH --output PATH`: reparses both phones' raw XML and both Go
  invocations, matches source/APK/report hashes, requires fresh host/lint/build outputs
  and the realized locked native Nix output. Its summary is **artifact accounting**;
  the independent executor must reconcile its own ordered nine-command exit/timeout/
  output ledger (including V9), original failures and findings. Lint warnings still
  need individual accepted/deferred Minor rationale; Critical/Major are not waived.

Outputs are absent-only, mode0600 under mode0700 `.validation/TTL-014`. Keep earlier
failures/staging/output trees. The wrapper never starts/restarts ADB, clears data,
uninstalls, downgrades, bypasses fixture isolation, provisions tools, edits a lock,
changes Tailscale/firewall/certificates, fills a real disk or activates a service.
Owned subprocess failures/timeouts retain raw output plus process receipts. Timeout
signals target only the newly owned process group, never a shared ADB server.

The trusted runtime's paired-runtime-ready, project-toolchain-ready, one-use exact-SHA
review admission and operator gates remain separate. A caller environment string,
selector file, supplied passed flag or report cannot substitute for those gates.
The helper deliberately does not issue PA native acceptance/finalization reports.

## Private device selector and durable human permission

**Do not copy real serials into public docs/reports.** An operator first authorizes
read-only discovery, then privately reviews current complete tuples and suitable
self-owned disposable fixture contexts. Do not infer permission from historical
Pixel/OPPO observations, tailnet membership, this document or the ticket plan.

After exact review, create mode0600
`.validation/TTL-014/approved-devices.json` under a mode0700 parent:

```json
{
  "schema": "ttl014-devices/v1",
  "authorization": "c-REAL-DURABLE-SINH-COMMENT-ID",
  "devices": {
    "pixel": {
      "serial": "EXACT-PRIVATE-SERIAL",
      "model": "EXACT-ro.product.model",
      "api": "EXACT-ro.build.version.sdk",
      "build": "EXACT-ro.build.display.id",
      "user": "0",
      "context": "EXACT operator-reviewed disposable/self-owned fixture scope"
    },
    "oppo": {
      "serial": "OTHER-EXACT-PRIVATE-SERIAL",
      "model": "EXACT-ro.product.model",
      "api": "EXACT-ro.build.version.sdk",
      "build": "EXACT-ro.build.display.id",
      "user": "0",
      "context": "EXACT operator-reviewed disposable/self-owned fixture scope"
    }
  }
}
```

The displayed placeholder authorization is intentionally invalid. The selector
payload hash is SHA-256 of UTF-8 JSON containing **only** `schema` and `devices`,
serialized with `json.dumps(payload, sort_keys=True, separators=(',', ':'),
ensure_ascii=False)`. Excluding the comment reference avoids a circular digest.
The private tuples/context remain bound by that digest without putting serials
into a ticket comment. Exactly two distinct devices are required.

Sinh must approve the exact privately reviewed payload and permission scope in a
real durable TTL-014 comment whose content is exactly these two lines (second line
canonical sorted compact JSON, actual digest substituted):

```text
TTL-014 authorized-fixture-devices
{"permission":{"installUpdate":true,"instrumentation":true,"keepInstalled":true,"noClearUninstallDowngrade":true,"selfOwnedFixtures":true},"selectorPayloadSha256":"ACTUAL-64-LOWERCASE-HEX-SHA256","ticket":"TTL-014"}
```

No sample digest/comment is authority. The wrapper rereads `ppa ticket show TTL-014
--json`, requires exactly that comment ID, actual author `sinh`, exact content and
current tuple equality. Changed targets need new exact approval, not edited old
permission. This permission covers only the selected self-owned full-suite fixture
install/update/instrumentation effects; it does **not** grant real camera UAT,
permission/VPN/mobile-network changes, live-service operations, data reset,
uninstall, downgrade, reboot, enrollment or production upload.

## Remaining acceptance record

Use the approved sixteen-scenario UAT artifact unchanged. For every scenario record
candidate, exact permitted context, date/reviewer, observed result and evidence;
missing authorization is **Blocked**, not Passed. Browser/Obsidian observation and
human UAT cannot be inferred from HTML generation or successful code compilation.
Candidate-matching existing hosted CI, independent findings review and actual
operator-authorized service coexistence/boot checks remain distinct evidence.
