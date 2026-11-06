# nixup-rebuild as a package, built with the nixpkgs it is added to (Nixup's flake has no nixpkgs of
# its own): with its overlay, environment.systemPackages = [ pkgs.nixup-rebuild ];
# The script finds Nixup's nix/ next to its own folder, so both go in share/nixup. nixup-play-login
# (a Google Play account's token, for the config) comes with it.
{ lib, stdenvNoCC, makeWrapper, android-tools, openssh, gnutar, curl }:
stdenvNoCC.mkDerivation {
  pname = "nixup-rebuild";
  version = "0.1.0";

  src = lib.fileset.toSource {
    root = ../.;
    fileset = lib.fileset.unions [ ./nixup-rebuild ./nixup-play-login ./play-login-window.py ../nix ];
  };

  nativeBuildInputs = [ makeWrapper ];

  installPhase = ''
    runHook preInstall
    mkdir -p $out/share/nixup $out/bin
    cp -r rebuild nix $out/share/nixup/
    # adb and ssh reach the phone; nix is the system's own (its daemon's version).
    makeWrapper $out/share/nixup/rebuild/nixup-rebuild $out/bin/nixup-rebuild \
      --prefix PATH : ${lib.makeBinPath [ android-tools openssh gnutar ]}
    # The browser it opens is the system's own (xdg-open, open).
    makeWrapper $out/share/nixup/rebuild/nixup-play-login $out/bin/nixup-play-login \
      --prefix PATH : ${lib.makeBinPath [ curl ]}
    runHook postInstall
  '';

  meta = {
    description = "Builds a phone's Nixup config on this machine and applies it on the phone, as nixos-rebuild --target-host";
    homepage = "https://github.com/by-architect/Nixup";
    mainProgram = "nixup-rebuild";
    platforms = lib.platforms.unix;
  };
}
