# TTL-014 native private archive — operator draft / implementation milestone

**Not ready for live rollout.** Approved product requirements/UAT are attached to
TTL-014 (r002, sign-off `c-20261027124822121`). This runbook is implementation
support, not new approval. Android enrollment, durable transport/export inventory,
shared-history/edit/conflict/download/range-playback UI and the validation wrapper
are now implemented, but two-phone integration, independent validation and human
UAT have not run. No service, phone, certificate, firewall or Tailscale settings were
changed by this work. Do not install this APK on either phone merely to test
migration. See [implementation progress and actual checks](TTL014_IMPLEMENTATION.md).

## Contents and boundaries

- `server/`: Go 1.26 standard-library-only native server/admin CLI, embedded read-only
  browser, merge/storage/auth/network fixtures. [Protocol/schema](../server/PROTOCOL.md).
- `nix/package.nix`: native package; `flake.nix` exposes `.#timelapse-server`.
- `nix/timelapse-server.nix`: optional, disabled-by-default NixOS module. Importing it
  does not enable Tailscale, open firewall ports, configure certificates, or change
  bakery/gateway services. The original ADB-only dev shell and flake lock are retained.
- Work state v3 adds description to records/drafts. v1/v2 reads do not rewrite bytes;
  the next successful local mutation publishes v3 atomically. Existing timing/spans,
  tombstones and source media ownership are preserved. **Do not downgrade a store
  written by this candidate to a v1/v2-only APK.** Unknown/corrupt data blocks writes
  rather than resetting it. Migration instrumentation is compiled, not executed here.

## Local development checks (not the independent nine-command matrix)

Use existing cached tools. Never silently fetch dependencies, upgrade locks or
provision a compiler to make a check pass. Go race tests need a cached C compiler;
this session selected the existing GCC wrapper explicitly, without installation.

```sh
cd server
GOTOOLCHAIN=local GOPROXY=off go test ./internal/merge ./internal/storage
GOTOOLCHAIN=local GOPROXY=off go test -race -count=1 ./...
GOTOOLCHAIN=local GOPROXY=off go vet ./...
GOTOOLCHAIN=local GOPROXY=off go build ./cmd/timelapse-server
```

The last command creates a local executable; use `-o` with an approved system-temp
path when repository output is restricted. Do not run `serve` against real endpoints
as part of these checks. HTTP tests use temporary loopback TLS listeners, synthetic
credentials and injected storage faults. No disk-fill, real camera or phone effect.

```sh
# From repository root, after the candidate's new files are included in Git:
nix --extra-experimental-features 'nix-command flakes' build --offline \
  --no-update-lock-file --no-write-lock-file --no-link .#timelapse-server
```

For this *uncommitted* milestone the same offline package was phase-built using
`"path:$PWD#timelapse-server"` so Nix included untracked source without staging it.
This is not a substitution for a clean exact-SHA independent review command.
Pure module evaluation (no activation/build/reboot):

```sh
nix --extra-experimental-features 'nix-command flakes' eval --offline \
  --no-update-lock-file --no-write-lock-file --impure --json --expr \
  'let f = builtins.getFlake ("path:" + builtins.getEnv "PWD"); in import ./nix/module-tests.nix { nixpkgs = f.inputs.nixpkgs; module = f.nixosModules.timelapse-server; }'
```

Android phase checks use the existing README JDK/SDK/offline Gradle instructions.
No connected test/install is authorized by a successful build.

## Before a later operator-authorized rollout

1. Freeze the tested source as an exact candidate. Obtain independently admitted
   review using [the implemented validation helper](TTL014_VALIDATION.md), the unchanged approved
   nine-command matrix, fresh exact two-phone fixture permissions and human UAT.
   Historical preparation/runtime denials and approvals are not cleared by this work.
2. Obtain explicit approval for the exact host/service account, state/credential
   paths, tailnet address/FQDN/port, certificate lifecycle, enrollment/ACLs, any
   firewall change, installation and service start. Record existing service/listener
   health, including ports 80/443. A previously observed free 8443 is not a reservation.
3. Use a dedicated non-root `timestamp-timelapse` account/group and new mode0700
   archive/credential directories. Never point state at another service's directory.
   Archive files and credential/key files are regular, unaliased mode0600 files.
   Do not use symlinks/hardlinks or the Nix store for secrets.
4. Provision a trusted certificate chain/key for the exact lowercase `.ts.net` FQDN,
   readable by that account. The native server checks key permissions, current
   validity and hostname; clients must independently verify the trusted chain and
   hostname. There is no certificate-bypass flag. The configured address must be
   assigned to an up `tailscale0` interface; userspace-only/LAN fallback is rejected.
5. Restrict tailnet access to the approved identities. No Funnel/public DNS proxy,
   implicit LAN listener or gateway reuse. Tailnet membership is not app authorization.

## Private application provisioning (examples only; not run here)

In a separately authorized operator session, privately create a mode0600 password
input file (12–1024 bytes, no password in shell arguments/history, source or logs).
The credential directory must already be canonical and mode0700. Use the native
binary as the dedicated owner or an appropriately delegated operator:

```sh
timelapse-server init-auth \
  --credentials /var/lib/timelapse-credentials/auth.json \
  --username owner \
  --password-file /var/lib/timelapse-credentials/password-input

timelapse-server enroll \
  --credentials /var/lib/timelapse-credentials/auth.json \
  --device CANONICAL-LOWERCASE-DEVICE-UUID --name Pixel \
  --token-file /var/lib/timelapse-credentials/pixel-token
```

Open **Private sync / shared history → Enrollment / settings** on the separately
approved APK/context. Copy its durable installation UUID and provision exactly that
UUID using the admin CLI. OPPO has its own UUID/token. Never infer either UUID from
phone model/serial, reinstall identity or another device. Privately enter the trusted
`https://host.tailnet.ts.net:8443` endpoint and per-device token, review the explicit
existing-data opt-in, then select **Enroll and enable**. The app checks `/v1/identity`
against its UUID before enabling uploads and encrypts the token using Android
Keystore AES-GCM. It refuses silent archive-endpoint replacement. Do not clear its
state/key to switch archives or troubleshoot a credential failure. There is no
self-registration endpoint; revoked sources cannot enroll themselves again.

Background jobs are Wi-Fi-only, subject to Android scheduling and actual transport
attribution through the VPN. **Sync now** attempts metadata/video independently;
mobile data requires fresh explicit confirmation. Disabling retains local queues and
archive copies. The queue view distinguishes verified progress, pending/missing
sources and preserved rejected proposals. Shared history remains usable offline;
refresh before resolving conflicts against the latest revision. Playback is explicit
authenticated range streaming; downloads use a user-created document and verify
whole size/SHA-256 before destination publication. Copy as new creates a local
planned timebox, never starts a timer/camera or imports old execution/recording links.

Enrollment writes a new mode0600 token output, never prints it. Existing output
files/UUID credentials cannot be overwritten by enrollment. If publication fails,
retain the token/staging files and inspect the credentials before an explicit retry;
never blindly rotate an unknown enrollment. Transfer tokens through a separately
approved private channel, never URLs, logs or screenshots. Retire the plaintext
password input/token provisioning files only under the operator's secret-handling
policy once safe enrollment is verified; the service needs only the hashed auth file.

Revoke a single installation without changing the other device:

```sh
timelapse-server revoke \
  --credentials /var/lib/timelapse-credentials/auth.json \
  --device CANONICAL-LOWERCASE-DEVICE-UUID
```

Credential replacements are atomic and reread per request. Browser password changes
invalidate existing sessions; use a separately reviewed private credential-update
procedure (there is no password-rotation CLI yet). Browser logout also invalidates
the server session. Restart invalidates all sessions and process-local login limits.

## Native configuration / optional module

For a copyable consuming-flake configuration, package/toolchain selection and
installation boundaries, see [NixOS input installation](NIXOS_INSTALL.md).

A native config is an explicit JSON object, no defaults inferred from host discovery:

```json
{
  "address": "100.64.1.2",
  "hostname": "fixture.tailnet.ts.net",
  "port": 8443,
  "certificate": "/var/lib/timelapse-credentials/cert.pem",
  "key": "/var/lib/timelapse-credentials/key.pem",
  "credentials": "/var/lib/timelapse-credentials/auth.json",
  "state": "/var/lib/timestamp-timelapse"
}
```

These are fictional fixture values, not selected live identity. Only after separate
rollout approval: `timelapse-server serve --config /ABSOLUTE/CONFIG.json` as non-root.
Missing identity/credentials/certificate/network readiness fails closed before serving.
Native configuration allows a reviewed dedicated custom canonical state path; the
optional module confines it to `/var/lib/timestamp-timelapse` or an instance suffix
(e.g. `/var/lib/timestamp-timelapse-fixture`) to avoid chmod/ownership of other services.

A later operator can import `inputs.<repo>.nixosModules.timelapse-server`, supply
quoted *path strings* (never Nix secret values/readFile), and configure:

```nix
services.timestamp-timelapse = {
  enable = true; # only with separate explicit rollout approval
  tailnetAddress = "EXACT-APPROVED-TAILNET-IP";
  hostname = "EXACT-APPROVED-HOST.ts.net"; # must actually be lowercase
  port = 8443;
  certificateFile = "/var/lib/timelapse-credentials/cert.pem";
  privateKeyFile = "/var/lib/timelapse-credentials/key.pem";
  credentialsFile = "/var/lib/timelapse-credentials/auth.json";
};
```

The module creates only its dedicated user/group/state directory/service; restricts
write access to state; supplies restart/hardening, tailnet-only peer address families
and IP filtering; and waits for network/Tailscale ordering without enabling Tailscale.
It deliberately does not enable firewall rules or reuse existing gateways. Native
binding/application authorization remain enforced even without systemd IP filtering.
ProtectHome is enabled: provision secrets outside `/home`. Review all options and the
actual host's systemd/BPF support before applying anything. Boot/reboot behavior and
existing-service coexistence have **not** been tested by module evaluation.

## Renewal and health

The operator owns certificate acquisition/renewal hooks; no hook, tailnet setting or
certificate is installed automatically. Schedule renewal well before expiry. Stage
new regular files under the same protected directory, verify chain/hostname/key pair,
owner and mode0600 key, then atomically replace files. Prefer retaining the private
key when appropriate; a two-file certificate/key replacement can briefly fail closed
if a handshake races the swap. Each new TLS handshake reloads and validates the
pair, so valid renewal does not need another service's restart or proxy changes.
Existing connections are not reauthenticated mid-stream; monitor expiry/renewal and
perform separately approved health checks. Never log keys or use a trust-all client.

Authenticated `/v1/status` reports fresh free-space warning strictly below 10%.
The browser fetches it every 30s; phones fetch it on reconnect/status sync.
There is no archive-size cap, free-space reserve, automatic expiry or purge UI.
An actual failed write remains pending and cannot be reported complete.

## Backup, restart and non-destructive recovery

**One server is not an independent backup.** Off-host backup provisioning is deferred.
A later authorized consistent backup procedure is:

1. Pause clients/stop **only** this dedicated service with explicit permission. Confirm
   process exit/flock release. Never stop bakery/gateway, reboot or restart shared ADB.
2. Copy the entire archive (state, lock, every chunk/blob and retained staging file)
   plus a separately protected current credential/config/certificate inventory to an
   independent destination. Retain mode0700 directories and mode0600 files. Record
   file sizes/SHA-256 and schema/tool versions privately. Do not treat a live ordinary
   file copy as a consistent snapshot.
3. Restore into a **new empty dedicated** directory with the same protections. Validate
   hashes and credentials before any exposure. Do not resurrect revoked credentials
   from an old backup: reconcile the current revocation roster first. Never run two
   restored writable servers against the same archive/endpoint. Obtain permission
   before starting it or pointing clients at it.
4. Verify acknowledged metadata, conflicts/receipts and complete MP4 digests using
   self-owned fixtures; clients reconcile original mutation/upload identities.

On corruption, ENOSPC, alias detection, missing state or an uncertain publication:
retain the error/raw files, stop further mutation attempts and preserve a consistent
copy under operator control. After fixing the underlying storage problem, restart
only this service if authorized; committed state may already contain a lost receipt.
Reconcile by original mutation ID/GET upload manifest, never by guessing completion.
Unknown versions and different orphan content require operator-led investigation.
Do not delete `lock`, reset `state.json`, drop receipts, clear phone data, downgrade,
re-encode sources, or delete unknown staging files as a repair. Lock files are stable
inodes: deleting one while a process runs can admit a second owner. There is no
"force repair" or automatic partial-data cleanup command in this milestone.
