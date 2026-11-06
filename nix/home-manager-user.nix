# What each user's Home Manager gets on the phone: nix/home-manager.nix adds it to every user's (as
# home-manager.sharedModules). Home Manager runs as it does on NixOS, but for what a phone has not got:
#   - a Nix daemon: a user cannot write Nixup's store, so the three Nix commands Home Manager's activation runs (to see
#     its store answer, to keep its generation as a root and a profile, to make the user's profiles folder) are stand-ins
#     that make links instead; Nixup keeps every generation in its store itself, as one of its roots;
#   - a user's systemd: Nixup's engine runs the user's units (nix/units.nix), so Home Manager writes no unit files and
#     starts none itself (systemd.user.enable = false);
#   - room for what a phone has no use for: the manual page (built from every option's docs), the desktop's file types
#     (xdg.mime), and every locale but the UTF-8 ones; each can still be turned on.
# A user's packages are installed by the phone itself, as its profile (/etc/profiles/per-user/<name>), whatever
# useUserPackages says: a user cannot install into the store.
{ lib, pkgs, ... }:
let
  # As Home Manager's activation runs them: nix-build to see that the store answers; nix-env -q, which makes the user's
  # profiles folder, and --profile <profile> --set <generation> (Home Manager before 26.05) for its generations; and
  # nix-store --realise <generation> --add-root <link>, for the generation it keeps.
  standIns = [
    (pkgs.writeShellScriptBin "nix-build" ''
      # Home Manager on Nixup: the store answers (Nixup holds it; a user has no Nix daemon on the phone).
      exit 0
    '')
    (pkgs.writeShellScriptBin "nix-env" ''
      # Home Manager on Nixup: what its activation asks of nix-env, without the store (Nixup holds it).
      profile= generation=
      while [ $# -gt 0 ]; do
        case $1 in
          --version) echo "nix-env (Nixup's stand-in for Home Manager's activation)"; exit 0 ;;
          --profile | -p) profile=$2; shift ;;
          --set) generation=$2; shift ;;
        esac
        shift
      done
      mkdir -p "''${XDG_STATE_HOME:-$HOME/.local/state}/nix/profiles"
      if [ -n "$profile" ] && [ -n "$generation" ]; then ln -sfn "$generation" "$profile"; fi
      exit 0
    '')
    (pkgs.writeShellScriptBin "nix-store" ''
      # Home Manager on Nixup: its generation kept as a link (Nixup keeps it in its store, as one of its roots).
      path= root=
      while [ $# -gt 0 ]; do
        case $1 in
          --add-root) root=$2; shift ;;
          -*) ;;
          *) path=$1 ;;
        esac
        shift
      done
      if [ -n "$path" ] && [ -n "$root" ]; then mkdir -p "$(dirname "$root")" && ln -sfn "$path" "$root"; fi
      if [ -n "$path" ]; then echo "$path"; fi
      exit 0
    '')
  ];
in
{
  # (Before the rest of its PATH, where Home Manager's NixOS module puts nix.package's.)
  home.extraActivationPath = lib.mkBefore standIns;

  submoduleSupport.externalPackageInstall = lib.mkForce true;

  systemd.user.enable = lib.mkDefault false;

  manual.manpages.enable = lib.mkDefault false;
  xdg.mime.enable = lib.mkDefault false;
  i18n.glibcLocales = lib.mkDefault pkgs.glibcLocalesUtf8;
}
