# Pure module-evaluation fixture; never activates a service or changes a host.
{
  nixpkgs,
  module,
  system ? "x86_64-linux",
}:
let
  make =
    options:
    nixpkgs.lib.nixosSystem {
      inherit system;
      modules = [
        module
        {
          system.stateVersion = "26.11";
          services.timestamp-timelapse = options;
        }
      ];
    };
  disabled = make { };
  enabled = make {
    enable = true;
    tailnetAddress = "100.64.1.2";
    hostname = "fixture.tailnet.ts.net";
    certificateFile = "/var/lib/timelapse-credentials/cert.pem";
    privateKeyFile = "/var/lib/timelapse-credentials/key.pem";
    credentialsFile = "/var/lib/timelapse-credentials/auth.json";
  };
  service = enabled.config.systemd.services.timestamp-timelapse;
  # Read only this module's mkIf content; successful unrelated NixOS assertion
  # messages can intentionally be undefined and must not be forced.
  scoped =
    system:
    (module {
      inherit (system) config pkgs;
      inherit (nixpkgs) lib;
    }).config.content.assertions;
  scopedAssertions = scoped enabled;
  invalidPort = make (enabled.config.services.timestamp-timelapse // { port = 443; });
  invalidSecret = make (
    enabled.config.services.timestamp-timelapse // { privateKeyFile = "/nix/store/not-a-real-secret"; }
  );
in
assert !(disabled.config.systemd.services ? timestamp-timelapse);
assert enabled.config.networking.firewall.allowedTCPPorts == [ ];
assert !enabled.config.services.tailscale.enable;
assert service.serviceConfig.User == "timestamp-timelapse";
assert service.serviceConfig.Group == "timestamp-timelapse";
assert service.serviceConfig.ProtectSystem == "strict";
assert service.serviceConfig.UMask == "0077";
assert service.serviceConfig.ReadWritePaths == [ "/var/lib/timestamp-timelapse" ];
assert service.serviceConfig.IPAddressDeny == "any";
assert builtins.length scopedAssertions == 3;
assert builtins.all (a: a.assertion) scopedAssertions;
assert !(builtins.all (a: a.assertion) (scoped invalidPort));
assert !(builtins.all (a: a.assertion) (scoped invalidSecret));
{
  passed = true;
  fixture = "optional-module-isolation";
  liveEffects = false;
}
