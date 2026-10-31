# Install the private sync server using this repository as a Nix input

Draft packaging, not live-deployment or TTL-014 acceptance. This installs the native
Go server/admin CLI, not the Android APK. The consumer example is **disabled by
default**. No automatic credential initialization, certificate issuance, firewall
change, Tailscale enablement or host activation is performed by these instructions.

For Drgnfly's existing host-specific deployment, use the separate
[flake-input migration patch and operator guide](DRGNFLY_FLAKE_INPUT.md). It retains
its existing lifecycle/persistence module instead of importing a second service module.

## Flake interface

| Output | Purpose |
| --- | --- |
| `packages.<system>.timelapse-server` / `.default` | Native server and admin CLI |
| `apps.<system>.timelapse-server` / `.default` | Explicit CLI invocation |
| `nixosModules.timelapse-server` / `.default` | Optional `services.timestamp-timelapse` module |
| `checks.<system>.package` | Package build and Go tests |
| `checks.<system>.nixos-module` | Pure consumer/disabled/isolation/negative fixtures |

Package outputs are provided for `x86_64-linux` and `aarch64-linux`. ARM evaluation
is not proof of an ARM build or runtime test. The existing x86_64 ADB-only shell is
unchanged. Go 1.26+ is required; select the input's package explicitly as below to
keep its locked compiler independently of your host nixpkgs. Do **not** add
`lapse-cam.inputs.nixpkgs.follows = "nixpkgs"` unless you deliberately validate that
compiler change. The plain module's default package uses the host package set.

## 1. Add the input to your existing host flake

The packaging draft is in `feature/TTL-014-private-sync-server`, **not `develop`**.
Until the changes are committed/published, use the local feature worktree:

```nix
inputs.lapse-cam.url =
  "path:/home/sinh/.treehouse/timestamp-timelapse-230d79/4/timestamp-timelapse";
```

After the branch is published, the remote form is:

```nix
inputs.lapse-cam.url =
  "github:sinh-x/lapse-cam?ref=feature/TTL-014-private-sync-server";
```

For reproducible deployment, prefer `github:sinh-x/lapse-cam/<REVIEWED-COMMIT-SHA>`
after publishing that exact revision. A commit SHA from before this draft does not
contain the new exports/example. Commit the consuming flake's reviewed lockfile;
do not update unrelated inputs or silently switch the existing server source.
Updating/fetching an input may require network access; it is not a live rollout.

Extend, rather than replace, your existing outputs/host definition:

```nix
outputs = inputs@{ nixpkgs, ... }: {
  nixosConfigurations.YOUR_HOST = nixpkgs.lib.nixosSystem {
    system = "x86_64-linux";
    specialArgs = { inherit inputs; };
    modules = [
      ./configuration.nix
      ./timelapse.nix
    ];
  };
};
```

Merge `inputs` into any existing `specialArgs`; preserve your host's modules,
`system.stateVersion`, existing services and unrelated local changes.

## 2. Copy the consumer module

Copy [`examples/nixos/timelapse.nix`](../examples/nixos/timelapse.nix) into the
consuming flake as `timelapse.nix`. It installs the CLI through
`environment.systemPackages` while keeping the service disabled. Merely adding
an input/module never installs anything into the running system: the operator
still reviews/builds/applies their host configuration separately.

The essential wiring is:

```nix
{ inputs, pkgs, ... }:
let
  server = inputs.lapse-cam.packages.${pkgs.stdenv.hostPlatform.system}.timelapse-server;
in {
  imports = [ inputs.lapse-cam.nixosModules.timelapse-server ];
  environment.systemPackages = [ server ];
  services.timestamp-timelapse = {
    enable = false; # Enable only after the separate readiness/rollout review.
    package = server;
    tailnetAddress = "100.64.1.2"; # REPLACE: this host's exact tailscale0 IP.
    hostname = "fixture.tailnet.ts.net"; # REPLACE: certificate-matching FQDN.
    port = 8443;
    certificateFile = "/var/lib/timelapse-credentials/cert.pem";
    privateKeyFile = "/var/lib/timelapse-credentials/key.pem";
    credentialsFile = "/var/lib/timelapse-credentials/auth.json";
  };
}
```

All credential options are quoted **path strings**, never Nix path literals,
`builtins.readFile`, passwords or tokens. Secret contents must never enter the Nix
store. The module creates a dedicated `timestamp-timelapse` user/group and mode0700
`/var/lib/timestamp-timelapse` archive only when enabled. It does not create or
initialize the credentials directory. The main service runs non-root, with
read-only access to separately provisioned private files and write access only to
its own archive. It cannot initialize credentials for you.

## 3. Review readiness before enabling

- Existing Tailscale must be separately configured and `tailscale0` ready. Binding
  is to the exact local tailnet IP, never a LAN/wildcard listener. App authorization
  is mandatory in addition to tailnet membership.
- Provision a trusted hostname-matching certificate and regular private key/auth
  files under canonical directories, with the native server's required 0700/0600
  permissions and readable by the dedicated account. See the
  [operator runbook](PRIVATE_SYNC_SERVER.md#private-application-provisioning-examples-only-not-run-here).
  Provision only genuinely new state; never replay `init-auth` over existing files.
- Review firewall/tailnet ACL access to the dedicated port separately. Importing
  this module opens no ports and changes no gateway or existing service.
- Arrange certificate renewal separately. The generic module does **not** copy
  the host-specific root preparation/recovery helpers or daily renewal timer.
- On impermanent NixOS, persist **both** archive and credentials using canonical
  bind mounts, not symlinks. The generic module does not assume impermanence.
- Review the complete built host closure/unit diff before any explicit operator
  activation. Building a host is not permission to activate it.

**Existing Drgnfly partial installation:** this draft is not a recovery procedure.
Do not import a second conflicting deployment, replace retained passwords/auth,
remove the existing preparation/renewal units, or replay first-install helpers.
Complete the retained read-only post-recovery verification first. Migrating the
existing host-specific source bundle to this flake input needs a separate reviewed
unit/package/persistence diff; retain its lifecycle helpers until reconciled.

## Package-only build and draft checks

From the feature worktree, using its existing locked/cached inputs:

```sh
nix build --offline --no-update-lock-file --no-write-lock-file --no-link \
  "path:$PWD#timelapse-server"
nix build --offline --no-update-lock-file --no-write-lock-file --no-link \
  "path:$PWD#checks.x86_64-linux.nixos-module"
```

Use `path:` for an unstaged local draft so the new example/tests are included.
After committing, ordinary `.#timelapse-server` works too. No lockfile update,
privileged command, network provisioning or live server invocation is needed for
these checks. `nix flake check` on a suitable builder also runs the exposed checks;
full two-phone/browser/UAT and live-host checks remain separate.

### Draft validation (d-0d4044)

- Offline x86_64 package build passed, including `go test ./...` across all five
  server packages (not a race-test run).
- The x86_64 consumer/module check built successfully, including disabled behavior,
  explicit pinned-package selection, no implicit firewall/Tailscale/renewal changes,
  and existing negative port/secret-path fixtures.
- ARM consumer evaluation and all-system `nix flake check --no-build` passed;
  **no ARM package build/runtime test** was performed.
- Nixfmt, Statix on changed Nix files, and `git diff --check` passed.
- No host-repository edits, lock updates, commit/push, privileged operation, live
  credential access, activation or service/phone changes. This is an uncommitted
  packaging overlay on TTL-014 candidate `967380b`, not a new accepted feature SHA.
