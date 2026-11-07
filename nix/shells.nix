# The shells' environment, as NixOS's: nixpkgs' own modules on the phone as they are, config/shells-environment.nix
# (environment.variables, shellAliases, shellInit, loginShellInit, interactiveShellInit, extraInit, shells, profiles,
# homeBinInPath, localBinInPath) and the shells' (programs.bash, programs.zsh, programs.fish, programs.xonsh, with
# their plugins), which write /etc/set-environment, /etc/profile, /etc/bashrc, /etc/zshenv, /etc/zshrc, /etc/fish, ...
# into Nixup's /etc, as NixOS writes them into its own. A NixOS module that sets them (a program's aliases, its line in
# every interactive shell) builds here and does what it does there.
#
# A shell in Nixup's view (an ssh session, the app's terminal, nixup-shell) then gets them as on NixOS:
# /etc/set-environment sets environment.variables and a PATH made of the profiles (the user's own, ~/.nix-profile and
# /etc/profiles/per-user/<user>; root's /nix/var/nix/profiles/default; the system's /run/current-system/sw, which is
# system.path: environment.systemPackages with bash, coreutils and the rest), then environment.extraInit, which here
# also has what a NixOS session gets from systemd and logind and the phone has not: a language and terminal, the
# phone's time zone, the user's /run/user/<uid>, the phone's name for the prompt (the kernel's is Android's own), and
# Android's own commands after the Nix ones. The users' shell is users.users.<name>.shell, else users.defaultUserShell
# (bash, as programs.bash makes it on NixOS), started by Nixup's login (/etc/nixup/login) for those without one of their
# own.
#
# Only with Nix packages (environment.systemPackages, or a user's): a config without them keeps Android's sh, which reads
# none of it (environment.variables and extraInit are given to it all the same, through /etc/nixup/environment), and
# fetches nothing for it.
#
# A configuration.nix built alone (no flake) has no nixpkgs until it uses a Nix package: a first look at it has these
# options as placeholders, which take anything, so that look can say whether it needs nixpkgs (one is set).
{ config, lib, options, pkgs, _nixup, ... }:
let
  inherit (lib) mkOption types;
  nixpkgs = _nixup.nixpkgs;
  source = if nixpkgs == null then null else nixpkgs.outPath or nixpkgs;

  # Each shell's option (programs.<name>), and its modules in nixpkgs' nixos/modules.
  shells = {
    bash = [ "programs/bash/bash.nix" "programs/bash/bash-completion.nix" "programs/bash/ls-colors.nix"
      "programs/bash/undistract-me.nix" "programs/bash/blesh.nix" ];
    zsh = [ "programs/zsh/zsh.nix" "programs/zsh/oh-my-zsh.nix" "programs/zsh/zsh-autoenv.nix"
      "programs/zsh/zsh-autosuggestions.nix" "programs/zsh/zsh-syntax-highlighting.nix" ];
    fish = [ "programs/fish.nix" ];
    xonsh = [ "programs/xonsh.nix" ];
  };
  environmentModules = [ "config/shells-environment.nix" ];
  # Its options (environment.<name>), placeholders without nixpkgs.
  environmentOptions = [ "shellAliases" "shellInit" "loginShellInit" "interactiveShellInit" "shells" "homeBinInPath"
    "localBinInPath" "profiles" "profileRelativeEnvVars" "binsh" "shell" ];

  placeholder = name: mkOption {
    type = types.anything;
    default = null;
    internal = true;
    description = "${name}, from NixOS's module, once nixpkgs is there.";
  };
  set = option: option.highestPrio < (lib.mkOptionDefault null).priority;

  # NixOS's type of environment.variables and sessionVariables: a value, or a list of them (joined with ":"); null
  # leaves one out.
  variablesType = with types; attrsOf (nullOr (oneOf [ (listOf (oneOf [ int str path ])) int str path ]));
  toStr = value: if lib.isPath value then "${value}" else toString value;
  variablesApply = attrs: lib.mapAttrs (_: value: if lib.isList value then lib.concatMapStringsSep ":" toStr value else toStr value)
    (lib.filterAttrs (_: value: value != null) attrs);

  # Whether the phone has a Nix shell: only with Nix packages, the system's or a user's (as nix/build.nix has it).
  hasShell = config.environment.systemPackages != [ ] || lib.any (user: user.enable && user.packages != [ ]) (lib.attrValues config.users.users);
  # Whether NixOS's shells' environment is on (with nixpkgs and a Nix shell): /etc/set-environment and the shells' files.
  nixosShells = source != null && config.environment.shell.enable;

  # What a session of Android's sh (no Nix shell) runs first, /etc/nixup/environment: environment.variables and
  # environment.extraInit, as NixOS's /etc/set-environment has them (in double quotes, as there: a value may say $HOME).
  exportLine = name: value: ''export ${name}="${value}"'';
  androidEnvironment = lib.concatStringsSep "\n" (lib.filter (line: line != "")
    (lib.mapAttrsToList exportLine (config.environment.variables // config.environment.sessionVariables)
      ++ [ config.environment.extraInit ]));

  # What a NixOS session gets from systemd and logind, and the phone has not got (Etc.kt writes the files it reads).
  phoneInit = ''
    # The phone (nix/shells.nix): a language and terminal, the phone's time zone (Android's is /etc/localtime, its zones
    # /etc/zoneinfo; without it, its offset from UTC), the user's own folder in /run (as logind makes it on NixOS:
    # tmux's socket, ssh-agent's) and the phone's name, which the prompt shows (the kernel's is Android's own).
    export LANG="''${LANG:-C.UTF-8}" TERM="''${TERM:-xterm-256color}"
    [ -d /etc/zoneinfo ] && export TZDIR=/etc/zoneinfo
    if [ -z "''${TZ:-}" ] && [ ! -s /etc/localtime ]; then
      __nixup_off=$(/system/bin/date +%z)
      case $__nixup_off in
        +[0-9][0-9][0-9][0-9]) export TZ="<$__nixup_off>-''${__nixup_off:1:2}:''${__nixup_off:3:2}" ;;
        -[0-9][0-9][0-9][0-9]) export TZ="<$__nixup_off>+''${__nixup_off:1:2}:''${__nixup_off:3:2}" ;;
      esac
      unset __nixup_off
    fi
    if [ -z "''${XDG_RUNTIME_DIR:-}" ]; then
      # (The uid as the kernel has it: Android's id stops at a user only Nixup's /etc/passwd has.)
      while read -r __nixup_key __nixup_uid __nixup_rest; do [ "$__nixup_key" = Uid: ] && break; done < /proc/self/status
      [ -d "/run/user/$__nixup_uid" ] && export XDG_RUNTIME_DIR="/run/user/$__nixup_uid"
      unset __nixup_key __nixup_uid __nixup_rest
    fi
    if [ -z "''${NIXUP_HOSTNAME:-}" ] && [ -r /etc/nixup/hostname ]; then
      read -r NIXUP_HOSTNAME < /etc/nixup/hostname && export NIXUP_HOSTNAME
    fi
  '';
  # Android's own commands after the Nix ones: their folders as the engine finds them on the phone (/system/bin,
  # /product/bin, where a root solution keeps su, ...).
  androidPath = ''
    # Android's own commands, after the Nix ones (nix/shells.nix).
    if [ -r /etc/nixup/path ]; then
      read -r __nixup_path < /etc/nixup/path && PATH="$PATH:$__nixup_path"
      unset __nixup_path
    fi
  '';

  # NixOS's prompt (programs.bash.promptInit's default), with the phone's name: NIXUP_HOSTNAME (its device name, or
  # networking.hostName), as the kernel's (\h) is Android's own ("localhost").
  prompt = ''
    # Provide a nice prompt if the terminal supports it.
    if [ "$TERM" != "dumb" ] || [ -n "$INSIDE_EMACS" ]; then
      PROMPT_COLOR="1;31m"
      ((UID)) && PROMPT_COLOR="1;32m"
      __host="''${NIXUP_HOSTNAME:-\h}"
      if [ -n "$INSIDE_EMACS" ]; then
        # Emacs term mode doesn't support xterm title escape sequence (\e]0;)
        PS1="\n\[\033[$PROMPT_COLOR\][\u@$__host:\w]\\$\[\033[0m\] "
      else
        PS1="\n\[\033[$PROMPT_COLOR\][\[\e]0;\u@$__host: \w\a\]\u@$__host:\w]\\$\[\033[0m\] "
      fi
      if test "$TERM" = "xterm"; then
        PS1="\[\033]2;$__host:\u:\w\007\]$PS1"
      fi
      unset __host
    fi
  '';
in
{
  # (The ones this nixpkgs has, as nix/nixos.nix takes them: one it dropped leaves its options out.)
  imports = lib.optionals (source != null) (lib.filter builtins.pathExists
    (map (file: source + "/nixos/modules/${file}") (environmentModules ++ lib.concatLists (lib.attrValues shells))));

  options = lib.optionalAttrs (source == null) {
    programs = lib.mapAttrs (name: _: placeholder "programs.${name}") shells;
    environment = lib.genAttrs environmentOptions (name: placeholder "environment.${name}") // {
      # Without nixpkgs the phone's shell is Android's sh, which gets these (see androidEnvironment).
      variables = mkOption {
        type = variablesType;
        apply = variablesApply;
        default = { };
        example = { EDITOR = "nvim"; };
        description = ''
          Environment variables every session in Nixup's view gets (ssh, the app's terminal, nixup-shell), as on NixOS:
          a value, or a list of them (joined with ":"); null leaves one out.
        '';
      };
      sessionVariables = mkOption {
        type = variablesType;
        apply = variablesApply;
        default = { };
        description = "As environment.variables, as on NixOS.";
      };
      extraInit = mkOption {
        type = types.lines;
        default = "";
        example = ''export PATH="$PATH:/storage/emulated/0/bin"'';
        description = ''
          Shell commands every session in Nixup's view runs first, as NixOS's environment.extraInit: an ssh login or
          command, the app's terminal and nixup-shell, for root and every user (plain sh).
        '';
      };
    };
  } // lib.optionalAttrs (source != null) {
    # NixOS's config/system-environment.nix has these, for PAM, which the phone has not got: here they are what NixOS's
    # shells' environment makes of them (it adds them to environment.variables and environment.profileRelativeEnvVars).
    environment.sessionVariables = mkOption {
      default = { };
      description = ''
        Environment variables of the users' sessions, as on NixOS: set in every shell of the view, with
        environment.variables (NixOS's PAM, which sets them for every program of a session, the phone has not got).
      '';
      inherit (options.environment.variables) type apply;
    };
    environment.profileRelativeSessionVariables = mkOption {
      type = types.attrsOf (types.listOf types.str);
      default = { };
      example = { PATH = [ "/bin" ]; MANPATH = [ "/man" "/share/man" ]; };
      description = ''
        Environment variables made of each profile of environment.profiles and the paths given (PATH = [ "/bin" ];), as
        on NixOS: set in every shell of the view, with environment.profileRelativeEnvVars.
      '';
    };
  };

  config = lib.mkMerge [
    {
      # For a configuration.nix's first look: whether it sets one of them (so it needs nixpkgs); and whether NixOS's
      # shells' environment is on (nix/build.nix: /run/current-system/sw, the login's shell).
      system.build.nixosShells = {
        used = source == null && lib.any set (lib.attrValues (lib.getAttrs (lib.attrNames shells) options.programs)
          ++ map (name: options.environment.${name}) environmentOptions);
        enabled = nixosShells;
      };

      # /etc/nixup/environment: what a session runs first when NixOS's /etc/set-environment is not there.
      system.build.androidEnvironment = if nixosShells then "" else androidEnvironment;
    }

    (lib.optionalAttrs (source != null) {
      # NixOS's shells' environment and bash's files, only with a Nix shell: Android's sh reads none of them.
      environment.shell.enable = lib.mkDefault hasShell;
      programs.bash.enable = lib.mkDefault hasShell;
      programs.bash.promptInit = lib.mkDefault prompt;
      # readline's settings as text, rather than a link into nixpkgs' source, which a phone keeps only with Nix's
      # registry (nix.enable).
      environment.etc = lib.mkIf config.programs.bash.enable {
        inputrc.text = lib.mkDefault (builtins.readFile (source + "/nixos/modules/programs/bash/inputrc"));
      };
      # (Made on the phone from every package's man pages, with Python.)
      programs.fish.generateCompletions = lib.mkDefault false;

      # NixOS's programs/environment.nix, as a phone with a Nix shell takes it: the pager (less is in every Nix shell
      # here), the profiles, and the paths made of them; NixOS's config/users-groups.nix adds the users' own profiles.
      environment.variables = lib.mkIf hasShell { PAGER = lib.mkDefault "less"; };
      environment.profiles = lib.mkIf hasShell (lib.mkMerge [
        [ "$HOME/.nix-profile" "\${XDG_STATE_HOME}/nix/profile" "$HOME/.local/state/nix/profile" "/etc/profiles/per-user/$USER" ]
        (lib.mkAfter [ "/nix/var/nix/profiles/default" "/run/current-system/sw" ])
      ]);
      environment.sessionVariables = lib.mkIf hasShell { XDG_CONFIG_DIRS = [ "/etc/xdg" ]; };
      environment.profileRelativeSessionVariables = lib.mkIf hasShell {
        PATH = [ "/bin" ];
        INFOPATH = [ "/info" "/share/info" ];
        XDG_CONFIG_DIRS = [ "/etc/xdg" ];
        XDG_DATA_DIRS = [ "/share" ];
        LIBEXEC_PATH = [ "/libexec" ];
        # (NixOS's config/terminfo.nix: the terminals' descriptions of the packages, for tmux, htop, vim, ...)
        TERMINFO_DIRS = [ "/share/terminfo" ];
      };
      environment.extraInit = lib.mkIf nixosShells (lib.mkMerge [ (lib.mkBefore phoneInit) (lib.mkAfter androidPath) ]);

      # As NixOS checks it (config/users-groups.nix): a user whose shell is zsh, fish or xonsh needs its program, which
      # writes the files that shell reads (its PATH among them).
      assertions = lib.concatLists (lib.mapAttrsToList (name: user: map (shell: {
        assertion = !user.ignoreShellProgramCheck -> (user.shell == pkgs.${shell}) -> (config.programs.${shell}.enable == true);
        message = ''
          users.users.${name}.shell is set to ${shell}, but
          programs.${shell}.enable is not true. This will cause the ${shell}
          shell to lack the basic nix directories in its PATH and might make
          logging in as that user impossible. You can fix it with:
          programs.${shell}.enable = true;

          If you know what you're doing and you are fine with the behavior,
          set users.users.${name}.ignoreShellProgramCheck = true;
          instead.
        '';
      }) [ "fish" "xonsh" "zsh" ]) config.users.users);
    })

    {
      warnings = lib.optional (source != null && !hasShell && lib.any set (map (name: options.environment.${name})
        [ "shellAliases" "shellInit" "loginShellInit" "interactiveShellInit" ]))
        "environment.shellAliases, shellInit, loginShellInit, interactiveShellInit: only with Nix packages (environment.systemPackages), as the phone's shell is Android's sh without them, which reads none of them.";
    }
  ];
}
