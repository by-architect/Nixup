# seraph-rebuild as a package, built with the nixpkgs it is added to (Seraph's flake has no nixpkgs of
# its own): with its overlay, environment.systemPackages = [ pkgs.seraph-rebuild ];
# The script finds Seraph's nix/ next to its own folder, so both go in share/seraph. seraph-play-login
# (a Google Play account's token, for the config) comes with it.
{ lib, stdenvNoCC, makeWrapper, android-tools, openssh, gnutar, curl }:
stdenvNoCC.mkDerivation {
  pname = "seraph-rebuild";
  version = "0.1.0";

  src = lib.fileset.toSource {
    root = ../.;
    fileset = lib.fileset.unions [ ./seraph-rebuild ./seraph-play-login ./play-login-window.py ../nix ];
  };

  nativeBuildInputs = [ makeWrapper ];

  installPhase = ''
    runHook preInstall
    mkdir -p $out/share/seraph $out/bin
    cp -r rebuild nix $out/share/seraph/
    # adb and ssh reach the phone; nix is the system's own (its daemon's version).
    makeWrapper $out/share/seraph/rebuild/seraph-rebuild $out/bin/seraph-rebuild \
      --prefix PATH : ${lib.makeBinPath [ android-tools openssh gnutar ]}
    # The browser it opens is the system's own (xdg-open, open).
    makeWrapper $out/share/seraph/rebuild/seraph-play-login $out/bin/seraph-play-login \
      --prefix PATH : ${lib.makeBinPath [ curl ]}
    runHook postInstall
  '';

  meta = {
    description = "Builds a phone's Seraph config on this machine and applies it on the phone, as nixos-rebuild --target-host";
    homepage = "https://github.com/by-architect/Seraph";
    mainProgram = "seraph-rebuild";
    platforms = lib.platforms.unix;
  };
}
