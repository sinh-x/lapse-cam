# Copy/adapt into the consuming NixOS flake, with specialArgs = { inherit inputs; }.
# Draft only: importing this example does NOT start a server. See docs/NIXOS_INSTALL.md.
{ inputs, pkgs, ... }:
let
  server = inputs.lapse-cam.packages.${pkgs.stdenv.hostPlatform.system}.timelapse-server;
in
{
  imports = [ inputs.lapse-cam.nixosModules.timelapse-server ];

  # Install the administrative CLI without enabling or initializing the service.
  environment.systemPackages = [ server ];

  services.timestamp-timelapse = {
    enable = false; # Set true only after reviewing the host and provisioning secrets.
    # Use the input's pinned Go toolchain, not the host's potentially older nixpkgs.
    package = server;
    tailnetAddress = "100.64.1.2"; # Replace with this host's exact tailscale0 IP.
    hostname = "fixture.tailnet.ts.net"; # Replace with its trusted certificate FQDN.
    port = 8443;
    stateDirectory = "/var/lib/timestamp-timelapse";
    certificateFile = "/var/lib/timelapse-credentials/cert.pem";
    privateKeyFile = "/var/lib/timelapse-credentials/key.pem";
    credentialsFile = "/var/lib/timelapse-credentials/auth.json";
  };
}
