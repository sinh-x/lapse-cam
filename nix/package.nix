{ buildGoModule }:
buildGoModule {
  pname = "timelapse-server";
  version = "0.1.0";
  src = ../server;
  vendorHash = null; # Standard library only; no dependency download/vendor step.
  subPackages = [ "cmd/timelapse-server" ];
  env.CGO_ENABLED = "0";
  meta.description = "Private Tailscale-only timelapse archive (TTL-014 implementation milestone)";
  meta.mainProgram = "timelapse-server";
}
