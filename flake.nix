{
  description = "ADB-only development shell for Timestamp Timelapse";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/e7439b6b14ad3cc35d05608ebca9bce01a25f5f8";

  outputs = { nixpkgs, ... }: {
    packages.x86_64-linux.timelapse-server =
      let pkgs = import nixpkgs { system = "x86_64-linux"; };
      in pkgs.callPackage ./nix/package.nix {};

    nixosModules.timelapse-server = import ./nix/timelapse-server.nix;

    devShells.x86_64-linux.default =
      let
        pkgs = import nixpkgs { system = "x86_64-linux"; };
      in
      pkgs.mkShell {
        packages = [ pkgs.android-tools ];
      };
  };
}
