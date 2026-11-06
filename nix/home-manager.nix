# Home Manager, as on NixOS: through Home Manager's own NixOS module, which the config imports as a NixOS config does
# (a flake: home-manager.nixosModules.home-manager; a configuration.nix alone: <home-manager/nixos>, which the phone
# fetches as it fetches nixpkgs). Each user's home-manager.users.<name> is Home Manager's own, with all of its modules
# and options: programs.*, services.*, home.file, xdg.*, home.packages, home.activation, ... What it makes, the phone
# runs with one bridge, the same for all of them:
#   - its activation (Home Manager's unit of the user, home-manager-<name>.service) runs as the user at each build that
#     changes it and at the phone's start, as on NixOS: it links the user's files into its home (/home/<name>, or
#     root's) and runs each module's steps (onChange, home.activation);
#   - the units Home Manager makes for the user (each services.* module's, and systemd.user.* of one's own) run
#     through Nixup's engine as the user's own, after its activation: user-<name>-<unit>, as its systemd --user would
#     run them (nix/units.nix);
#   - its packages are the user's profile, /etc/profiles/per-user/<name>, on its PATH (nix/users.nix).
# What the phone has not got stays out: a user's systemd (Home Manager's own unit files and sd-switch are off), a
# D-Bus session bus, a desktop session (graphical-session.target: what only that starts is not started, and the build
# says so). Home Manager's files are links into /nix/store, so they are there in Nixup's view of the phone (ssh, the
# app's terminal, the services), not for Android's apps. Taking a user out of home-manager.users takes Home Manager's
# links out of its home again. The settings every user's Home Manager gets on the phone: nix/home-manager-user.nix.
{ config, options, lib, pkgs, _nixup, ... }:
let
  cfg = config.home-manager;
  # Whether the config imports Home Manager's NixOS module.
  imported = options ? home-manager.users;
  # Its users' modules need nixpkgs, which a configuration.nix's first look has not got (nix/build.nix asks for it).
  live = imported && _nixup.nixpkgs != null && cfg.users != { };
  inherit (config._module.args) utils;
  # Home Manager's unit of a user, as its NixOS module names it.
  unitOf = hm: "home-manager-${utils.escapeSystemdPath hm.home.username}";

  # What Home Manager's unit of a user runs once the user leaves home-manager.users, as its user: each link of its home
  # into one of Home Manager's generations goes (and the folders that leaves empty), and so does the link to the
  # generation in use, as Home Manager's own uninstall does.
  cleanup = pkgs.writeShellScript "home-manager-cleanup" ''
    PATH=${lib.makeBinPath [ pkgs.coreutils pkgs.findutils ]}
    cd "$HOME" || exit 0
    find . -xdev -type l -lname '${builtins.storeDir}/*-home-manager-files/*' -print0 | while IFS= read -r -d "" link; do
      rm -f "$link" && echo "removed ~/''${link#./}"
      dir=''${link%/*}
      if [ "$dir" != . ]; then rmdir -p --ignore-fail-on-non-empty "$dir" 2> /dev/null || true; fi
    done
    rm -f "''${XDG_STATE_HOME:-$HOME/.local/state}/home-manager/gcroots/current-home"
    echo "Home Manager's files are out of $HOME"
  '';
in
{
  config = lib.optionalAttrs imported {
    home-manager.sharedModules = [ ./home-manager-user.nix ];

    # Each user's own units, as Home Manager makes them, through Nixup's engine (nix/units.nix), after the user's
    # activation (its files are linked then); with the variables its systemd gives them (systemd.user.sessionVariables).
    systemd.user.byUser = lib.mkIf live (lib.mapAttrs' (name: hm: lib.nameValuePair hm.home.username {
      inherit (hm.systemd.user) services sockets timers paths targets;
      environment = lib.mapAttrs (_: toString) hm.systemd.user.sessionVariables;
      after = [ "${unitOf hm}.service" ];
      origin = "home-manager.users.${name}.systemd.user";
    }) cfg.users);

    systemd.services = lib.mkIf live (lib.mapAttrs' (_: hm: lib.nameValuePair (unitOf hm) { cleanup = [ "${cleanup}" ]; }) cfg.users);

    # A user's packages are its profile whatever useUserPackages says (Home Manager's NixOS module adds them with it).
    users.users = lib.mkIf (live && !cfg.useUserPackages) (lib.mapAttrs (_: hm: { packages = [ hm.home.path ]; }) cfg.users);

    assertions = [
      {
        assertion = !cfg.startAsUserService;
        message = "home-manager.startAsUserService: the phone has no user sessions to start it in; each user's Home Manager runs at each build and at the phone's start (leave it false).";
      }
    ] ++ map (name: {
      assertion = config.users.users ? ${name};
      message = "home-manager.users.${name}: there is no users.users.${name} (a user with isNormalUser = true; root is there already).";
    }) (lib.attrNames cfg.users);
  };
}
