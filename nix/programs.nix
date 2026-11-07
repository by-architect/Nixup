# Programs, as NixOS's programs.<name>: nixpkgs' own modules on the phone as they are. programs.tmux.enable = true;
# runs nixpkgs' nixos/modules/programs/tmux.nix, with its /etc/tmux.conf and its package, as on NixOS; programs.git,
# programs.starship, programs.direnv and the others below alike. The shells (bash, zsh, fish, xonsh) and their
# environment are nix/shells.nix's: a program's lines for them (direnv's hook, starship's prompt, ...) go into their
# files, so, as those, they need Nix packages (a program adds its own to environment.systemPackages).
#
# What NixOS puts on every machine, the phone does not, as each would be a download for every phone or cannot work:
# nano, command-not-found (its database is a NixOS channel's), systemd's ssh proxy, mosh's utempter (a setgid wrapper
# for utmp). A program NixOS gives special rights (security.wrappers: mtr's mtr-packet, tcpdump, iftop) runs as it is:
# root may do what it does, another user may not.
#
# The ones below build with Nixup's options (scripts/test-nix-programs.sh) and ask for nothing the phone has not got.
# Another of nixpkgs' program modules can be added by hand, as on NixOS (imports = [ "${modulesPath}/programs/foo.nix"
# ];): what it needs that the phone has not got stops the build and says so. One of the list can be left out the way
# NixOS leaves out its own (disabledModules = [ "programs/tmux.nix" ];).
#
# A configuration.nix built alone (no flake) has no nixpkgs until it uses a Nix package: a first look at it has these
# options as placeholders, which take anything, so that look can say whether it needs nixpkgs (one is set).
{ config, lib, options, _nixup, ... }:
let
  inherit (lib) mkOption types;
  nixpkgs = _nixup.nixpkgs;
  source = if nixpkgs == null then null else nixpkgs.outPath or nixpkgs;

  # Each program's option (programs.<name>), and its modules in nixpkgs' nixos/modules.
  programs = {
    less = [ "programs/less.nix" ];
    autoenv = [ "programs/autoenv.nix" ];
    autojump = [ "programs/autojump.nix" ];
    bandwhich = [ "programs/bandwhich.nix" ];
    bat = [ "programs/bat.nix" ];
    command-not-found = [ "programs/command-not-found/command-not-found.nix" ];
    direnv = [ "programs/direnv.nix" ];
    fzf = [ "programs/fzf.nix" ];
    git = [ "programs/git.nix" ];
    htop = [ "programs/htop.nix" ];
    iftop = [ "programs/iftop.nix" ];
    lazygit = [ "programs/lazygit.nix" ];
    mosh = [ "programs/mosh.nix" ];
    mtr = [ "programs/mtr.nix" ];
    nano = [ "programs/nano.nix" ];
    neovim = [ "programs/neovim.nix" ];
    nix-index = [ "programs/nix-index.nix" ];
    # (A shell too, which direnv, starship and others write their lines for, as for bash and zsh.)
    nushell = [ "programs/nushell.nix" ];
    pay-respects = [ "programs/pay-respects.nix" ];
    proxychains = [ "programs/proxychains.nix" ];
    screen = [ "programs/screen.nix" ];
    skim = [ "programs/skim.nix" ];
    ssh = [ "programs/ssh.nix" ];
    starship = [ "programs/starship.nix" ];
    tcpdump = [ "programs/tcpdump.nix" ];
    television = [ "programs/television.nix" ];
    tmux = [ "programs/tmux.nix" ];
    traceroute = [ "programs/traceroute.nix" ];
    trippy = [ "programs/trippy.nix" ];
    vim = [ "programs/vim.nix" ];
    vivid = [ "programs/vivid.nix" ];
    whois = [ "programs/whois.nix" ];
    yazi = [ "programs/yazi.nix" ];
    zoxide = [ "programs/zoxide.nix" ];
  };

  placeholder = name: mkOption {
    type = types.anything;
    default = null;
    internal = true;
    description = "${name}, from NixOS's module, once nixpkgs is there.";
  };
  set = option: option.highestPrio < (lib.mkOptionDefault null).priority;
in
{
  # (The ones this nixpkgs has, as nix/nixos.nix takes them: one it dropped leaves its options out.)
  imports = lib.optionals (source != null) (lib.filter builtins.pathExists
    (map (file: source + "/nixos/modules/${file}") (lib.concatLists (lib.attrValues programs))));

  options = lib.optionalAttrs (source == null) {
    programs = lib.mapAttrs (name: _: placeholder "programs.${name}") programs;
  };

  config = lib.mkMerge [
    {
      # For a configuration.nix's first look: whether it sets one of them (so it needs nixpkgs).
      system.build.nixosPrograms = {
        used = source == null && lib.any set (lib.attrValues (lib.getAttrs (lib.attrNames programs) options.programs));
        files = programs;
      };
    }

    (lib.optionalAttrs (source != null) {
      # On NixOS every machine has them; on a phone each would be a download for every config, or cannot work.
      programs.nano.enable = lib.mkDefault false;
      # (NixOS's EDITOR is nano, which every machine of its has: here when it is on.)
      environment.variables.EDITOR = lib.mkIf config.programs.nano.enable (lib.mkDefault "nano");
      # (Its database is the one a NixOS channel has, which the phone has not.)
      programs.command-not-found.enable = lib.mkDefault false;
      # (systemd's, which the phone has not got: its ssh_config would bring systemd.)
      programs.ssh.systemd-ssh-proxy.enable = lib.mkDefault false;
      # (A setgid wrapper for utmp, which the phone has not got.)
      programs.mosh.withUtempter = lib.mkDefault false;
    })
  ];
}
