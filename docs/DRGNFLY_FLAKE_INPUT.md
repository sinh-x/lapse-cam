# Drgnfly: move the native archive package to a flake input

**Prepared patch only. Not applied, locked, rebuilt or activated on Drgnfly.**
Sinh selected “Prepare a patch here” in d-0d4044. The host repository remains read-only
in this deployment; operator application below is separate from preparing the draft.

## What changes

[`patches/TTL-014-drgnfly-flake-input.patch`](patches/TTL-014-drgnfly-flake-input.patch)
changes exactly three public files in `personal-nixos`:

1. `flake.nix`: adds `timestamp-timelapse`, a local development input like `pa-platform`.
2. `systems/x86_64-linux/Drgnfly/timelapse/drgnfly-timelapse.nix`: selects
   `inputs.timestamp-timelapse.packages.${pkgs.stdenv.hostPlatform.system}.timelapse-server`
   instead of building the copied server directory.
3. `systems/x86_64-linux/Drgnfly/timelapse/fixture.nix`: accepts that input and checks
   its selected package rather than asserting the obsolete package store path.

The existing host-specific module remains imported. Do **not** also import the input's
module: that is unnecessary for package selection and could overlap local definitions.
Existing preparation/renewal helpers, service account, archive/credential paths,
persistence, application authorization, firewall and Tailscale settings are unchanged.
The old copied server/package files are left in place; removal is a separate action.

The input intentionally keeps its own locked nixpkgs/Go toolchain; no
`inputs.nixpkgs.follows = "nixpkgs"` is added. The former independent fetchTree pin is
replaced by the equivalent input-owned pin, not a host-wide nixpkgs update.

### Local development source

The URL is explicitly:

```nix
url = "path:/home/sinh/.treehouse/timestamp-timelapse-230d79/4/timestamp-timelapse";
```

`path:` includes dirty/untracked files, including the uncommitted browser-origin fix
and new flake fixtures. The input snapshot may also include ignored build outputs;
never keep credentials/secrets inside that source tree, because Nix store sources are
not secret storage. Nothing under the live archive/credential directories is copied.
Retain this Treehouse worktree/lease while the host refers to it. It is a development
source, not a portable final deployment pin. After publishing a reviewed candidate,
change the URL to `github:sinh-x/lapse-cam/<reviewed-commit>` in a separate update.
The old commit `967380b` alone does **not** include the current browser-origin fix.

## Operator application — no sudo needed for these source/lock steps

The patch was drafted against host branch `develop`, HEAD
`5a58da7b9b1f61ea15b58328058dc5ea06c977c4`, including existing operator edits.
[`patches/TTL-014-drgnfly-flake-input.baseline.json`](patches/TTL-014-drgnfly-flake-input.baseline.json)
records exact before/after file hashes, the untouched lock/helper/module hashes,
and pre-existing dirty paths. Do not stash, reset, replace the whole flake/lock,
or force the patch if this fingerprint has drifted.

First set paths and verify the untouched draft baseline:

```sh
TTL=/home/sinh/.treehouse/timestamp-timelapse-230d79/4/timestamp-timelapse
PATCH="$TTL/docs/patches/TTL-014-drgnfly-flake-input.patch"
BASELINE="$TTL/docs/patches/TTL-014-drgnfly-flake-input.baseline.json"
cd /home/sinh/git-repos/sinh-x/personal-nixos
python3 - "$BASELINE" "$PATCH" <<'PY'
import hashlib, json, pathlib, subprocess, sys
b = json.loads(pathlib.Path(sys.argv[1]).read_text())
root = pathlib.Path.cwd()
assert root == pathlib.Path(b['hostRoot']), 'Wrong host checkout'
assert subprocess.check_output(['git', 'branch', '--show-current'], text=True).strip() == b['hostBranch'], 'Branch drift'
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() == b['hostHead'], 'HEAD drift'
for name, expected in b['hostFileSha256'].items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == expected, 'File drift: ' + name
assert hashlib.sha256(pathlib.Path(sys.argv[2]).read_bytes()).hexdigest() == b['patchSha256'], 'Patch drift'
print('Draft baseline matches. Review the patch before applying; this is not activation approval.')
PY
```

**Stop on any error.** If the baseline matches and the narrow change is acceptable:

```sh
git apply --check "$PATCH"
git apply "$PATCH"
git diff -- flake.nix systems/x86_64-linux/Drgnfly/timelapse
```

The existing `flake.nix`, `flake.lock` and module-test/module edits belong to the
operator; review the new delta relative to the recorded working-tree baseline,
not just Git HEAD. Do not apply this twice.

### Capture only the new input

Preserve the current dirty lock for comparison, then refresh only this input:

```sh
OLD_LOCK=$(mktemp /tmp/ttl014-host-lock.XXXXXX)
cp flake.lock "$OLD_LOCK"
nix flake update --offline timestamp-timelapse
diff -u "$OLD_LOCK" flake.lock
```

A nonzero `diff` exit means files differ; inspect those differences. The selected
input and its own dependency graph may be added/deduplicated, but unrelated root
input identities must not change. Stop on unrelated changes or unavailable cached
inputs; do not replace the lock from a saved whole-file copy. **Never run an unnamed
`nix flake update`, or `sys update timestamp-timelapse`: the host's documented `sys`
wrapper ignores that update argument and refreshes everything.** Future local-source
changes use the same named Nix input refresh after source review.

## Evaluate/build before any activation

From the host checkout, after the reviewed named lock refresh:

```sh
nix eval --offline --no-update-lock-file --no-write-lock-file --raw \
  .#nixosConfigurations.Drgnfly.config.services.timestamp-timelapse.package.outPath
```

For the drafted source, expect:

```text
/nix/store/llrknbgwp8j0x8cly332c84p1lj8jdi5-timelapse-server-0.1.0
```

A different output is source drift, not permission to silently accept another candidate.
The selected package contains the `Referrer-Policy: same-origin` browser-login fix.

Evaluate the host fixture without activating anything:

```sh
nix eval --offline --no-update-lock-file --no-write-lock-file --impure --json --expr '
  let f = builtins.getFlake (builtins.getEnv "PWD"); in
  import ./systems/x86_64-linux/Drgnfly/timelapse/fixture.nix {
    nixpkgs = f.inputs.nixpkgs;
    timelapse = f.inputs.timestamp-timelapse;
  }'
```

Then, under the host repository's scoped validation rules:

```sh
nix build --offline --no-update-lock-file --no-write-lock-file --no-link \
  --print-out-paths .#nixosConfigurations.Drgnfly.config.system.build.toplevel
```

Review the exact full-host closure/unit diff against the running system and preserve
unrelated host work. The package change also changes the preparation unit's reference
to the admin binary. Although its existing helper returns without reinitialization
when `auth.json` exists, activation is still a real service/credential/certificate
lifecycle event, not a harmless package-only installation. No initialization/reset,
credential deletion, OS rollback or global dependency refresh is required by this patch.

**Activation is operator-only and not performed/authorized by this draft.** Use the
host repository's reviewed temporary-activation workflow only after its checks pass.
After activation, reload `/login` (the old page retains its old header policy), log in
with the existing browser account, and confirm existing saved work/video browsing,
trusted HTTPS/authentication and bakery/gateway coexistence. A server restart may
invalidate browser sessions; phone device tokens and archive contents must remain.

Rollback is a separately reviewed reverse patch (`git apply -R --check "$PATCH"`),
with lock changes reviewed independently. Do not reset/stash the dirty checkout or
blindly overwrite its lock, source bundle, credentials or archive.

## Draft checks completed (d-0d4044)

- Patch forward-application and reverse-check pass in a temporary public-source copy;
  all three patched file hashes match the recorded candidate.
- Baseline and patched host fixtures pass pure offline evaluation, including negative
  host/Tailscale/firewall/persistence gates.
- The actual input URL selects the expected fixed server package above.
- Before/after comparisons preserve service settings/hardening/dependencies, renewal
  unit/timer, tmpfiles and persistence. The preparation script changes only its
  selected native admin-binary path; it was never executed.
- Nixfmt checks pass for all three patched Nix files. The fixed native package was
  already built offline with all five Go test packages passing.
- Real host source/lock/helper/module fingerprints remain identical to the baseline.

## Validation boundary

The accompanying draft is checked against temporary copies of the public host
configuration only. Patch application checks, pure module/negative-gate evaluation,
selected-package identity and preservation of lifecycle/hardening/persistence are
not a full host build, activation, live browser login or human acceptance. No host
source/lock, protected data or running service is changed during draft preparation.
