{ buildGoModule }:
buildGoModule {
  pname = "timelapse-server";
  version = "0.1.0";
  src = ../server;
  vendorHash = null; # Standard library only; no dependency download/vendor step.
  subPackages = [ "cmd/timelapse-server" ];
  # subPackages limits the default Go check phase too; test all internal packages.
  checkPhase = ''
    runHook preCheck
    go test ./...
    runHook postCheck
  '';
  # Go 1.26+ is required. The flake locks its compiler independently of the host.
  env = {
    CGO_ENABLED = "0";
    GOPROXY = "off";
    GOTOOLCHAIN = "local";
    GOSUMDB = "off";
  };
  meta = {
    description = "Private Tailscale-only timelapse archive and administrative CLI";
    mainProgram = "timelapse-server";
    platforms = [
      "x86_64-linux"
      "aarch64-linux"
    ];
    homepage = "https://github.com/sinh-x/lapse-cam";
  };
}
