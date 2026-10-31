{
  description = "Timestamp Timelapse: private archive server, NixOS module and ADB development shell";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/e7439b6b14ad3cc35d05608ebca9bce01a25f5f8";

  outputs =
    { self, nixpkgs, ... }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      packages = forAllSystems (
        system:
        let
          pkgs = import nixpkgs { inherit system; };
          server = pkgs.callPackage ./nix/package.nix { };
        in
        {
          timelapse-server = server;
          default = server;
        }
      );

      apps = forAllSystems (system: {
        timelapse-server = {
          type = "app";
          program = "${self.packages.${system}.timelapse-server}/bin/timelapse-server";
          meta.description = "Private timelapse archive server and administrative CLI";
        };
        default = self.apps.${system}.timelapse-server;
      });

      nixosModules = {
        timelapse-server = import ./nix/timelapse-server.nix;
        default = self.nixosModules.timelapse-server;
      };

      checks = forAllSystems (
        system:
        let
          pkgs = import nixpkgs { inherit system; };
        in
        {
          package = self.packages.${system}.timelapse-server;
          nixos-module = pkgs.runCommand "timelapse-module-evaluation" { } ''
            printf '%s\n' ${
              nixpkgs.lib.escapeShellArg (
                builtins.toJSON (import ./nix/consumer-tests.nix { inherit self nixpkgs system; })
              )
            } > "$out"
          '';
        }
      );

      # Preserve the original x86_64 ADB-only shell; no Android SDK provisioning.
      devShells.x86_64-linux.default =
        let
          pkgs = import nixpkgs { system = "x86_64-linux"; };
        in
        pkgs.mkShell {
          packages = [ pkgs.android-tools ];
        };
    };
}
