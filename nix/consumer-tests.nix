# Evaluate the actual consumer example. No host activation, credentials or network IO.
{
  self,
  nixpkgs,
  system,
}:
let
  make =
    enable:
    nixpkgs.lib.nixosSystem {
      inherit system;
      specialArgs.inputs.lapse-cam = self;
      modules = [
        ../examples/nixos/timelapse.nix
        {
          system.stateVersion = "26.11";
          services.timestamp-timelapse.enable = nixpkgs.lib.mkForce enable;
        }
      ];
    };
  disabled = make false;
  enabled = make true;
  cfg = enabled.config;
  service = cfg.systemd.services.timestamp-timelapse;
  package = self.packages.${system}.timelapse-server;
  # Include the existing negative port/secret fixtures for the underlying module.
  isolation = import ./module-tests.nix {
    inherit nixpkgs system;
    module = self.nixosModules.timelapse-server;
  };
in
assert isolation.passed;
assert !(disabled.config.systemd.services ? timestamp-timelapse);
assert !(disabled.config.users.users ? timestamp-timelapse);
assert builtins.elem package disabled.config.environment.systemPackages;
assert cfg.services.timestamp-timelapse.package == package;
assert self.packages.${system}.default == package;
assert self.apps.${system}.default.program == "${package}/bin/timelapse-server";
assert nixpkgs.lib.hasPrefix "${package}/bin/timelapse-server serve --config "
  service.serviceConfig.ExecStart;
assert cfg.networking.firewall.allowedTCPPorts == [ ];
assert !cfg.services.tailscale.enable;
assert !(cfg.systemd.services ? timestamp-timelapse-prepare);
assert !(cfg.systemd.timers ? timestamp-timelapse-renew);
assert builtins.elem "d /var/lib/timestamp-timelapse 0700 timestamp-timelapse timestamp-timelapse -"
  cfg.systemd.tmpfiles.rules;
{
  passed = true;
  fixture = "repository-input-nixos-consumer";
  inherit system;
  liveEffects = false;
}
