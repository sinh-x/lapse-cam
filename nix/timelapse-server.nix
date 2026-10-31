# Optional only: importing this module does not enable the service, Tailscale or a firewall rule.
{ config, lib, pkgs, ... }:
let
  cfg = config.services.timestamp-timelapse;
  packageDefault = pkgs.callPackage ./package.nix {};
  configuration = pkgs.writeText "timelapse-server-config.json" (builtins.toJSON {
    address = cfg.tailnetAddress;
    hostname = cfg.hostname;
    port = cfg.port;
    certificate = cfg.certificateFile;
    key = cfg.privateKeyFile;
    credentials = cfg.credentialsFile;
    state = cfg.stateDirectory;
  });
in {
  options.services.timestamp-timelapse = {
    enable = lib.mkEnableOption "the private timelapse archive (requires separate operator rollout approval)";
    package = lib.mkOption { type = lib.types.package; default = packageDefault; description = "Native timelapse-server package."; };
    tailnetAddress = lib.mkOption { type = lib.types.str; description = "Exact local Tailscale IPv4/IPv6 address; no wildcard or LAN binding."; };
    hostname = lib.mkOption { type = lib.types.str; description = "Certificate-matching lowercase .ts.net FQDN."; };
    port = lib.mkOption { type = lib.types.port; default = 8443; description = "Dedicated unprivileged HTTPS port; do not reuse gateway ports."; };
    stateDirectory = lib.mkOption { type = lib.types.strMatching "/var/lib/timestamp-timelapse(-[a-z0-9-]+)?"; default = "/var/lib/timestamp-timelapse"; description = "Dedicated mode0700 archive directory; optional lowercase instance suffix. Custom paths use the native configuration after operator review."; };
    certificateFile = lib.mkOption { type = lib.types.str; description = "Quoted absolute path to operator-provisioned certificate chain. Never embed secrets in Nix expressions."; };
    privateKeyFile = lib.mkOption { type = lib.types.str; description = "Quoted absolute path to the regular mode0600 private key; readable by timestamp-timelapse."; };
    credentialsFile = lib.mkOption { type = lib.types.str; description = "Quoted absolute path to private application credentials, not their contents. Provision using native admin commands."; };
  };
  config = lib.mkIf cfg.enable {
    assertions = [
      { assertion = cfg.port >= 1024; message = "Timelapse must use its own non-root port, not 80/443."; }
      { assertion = builtins.all (p: builtins.match "/[a-zA-Z0-9_./-]+" p != null && !(lib.hasInfix "/../" p) && !(lib.hasInfix "/./" p))
          [ cfg.stateDirectory cfg.certificateFile cfg.privateKeyFile cfg.credentialsFile ];
        message = "Timelapse paths must be absolute dedicated canonical paths without whitespace; provide paths, never secret contents."; }
      { assertion = !(lib.hasPrefix "/nix/store/" cfg.privateKeyFile) && !(lib.hasPrefix "/nix/store/" cfg.credentialsFile);
        message = "Never put timelapse private keys or application credentials in the Nix store."; }
    ];
    users.groups.timestamp-timelapse = {};
    users.users.timestamp-timelapse = {
      isSystemUser = true;
      group = "timestamp-timelapse";
      home = cfg.stateDirectory;
    };
    systemd.tmpfiles.rules = [ "d ${cfg.stateDirectory} 0700 timestamp-timelapse timestamp-timelapse -" ];
    systemd.services.timestamp-timelapse = {
      description = "Private timelapse archive";
      wantedBy = [ "multi-user.target" ];
      after = [ "network-online.target" "tailscaled.service" ];
      wants = [ "network-online.target" ];
      serviceConfig = {
        Type = "simple";
        User = "timestamp-timelapse";
        Group = "timestamp-timelapse";
        UMask = "0077";
        WorkingDirectory = cfg.stateDirectory;
        ExecStart = "${lib.getExe cfg.package} serve --config ${configuration}";
        Restart = "on-failure";
        RestartSec = "10s";
        TimeoutStopSec = "20s";
        ProtectSystem = "strict";
        ProtectHome = true;
        ReadWritePaths = [ cfg.stateDirectory ];
        PrivateTmp = true;
        PrivateDevices = true;
        NoNewPrivileges = true;
        ProtectKernelTunables = true;
        ProtectKernelModules = true;
        ProtectControlGroups = true;
        RestrictSUIDSGID = true;
        LockPersonality = true;
        MemoryDenyWriteExecute = true;
        RestrictAddressFamilies = [ "AF_UNIX" "AF_INET" "AF_INET6" ];
        IPAddressDeny = "any";
        IPAddressAllow = [ "100.64.0.0/10" "fd7a:115c:a1e0::/48" ];
      };
    };
  };
}
