# OpenSSH's server, as NixOS's services.openssh: sshd, a unit run by root in Nixup's view of the phone (see
# nix/units.nix), so a session is a shell there, the one the app's terminal and nixup-shell open:
# environment.systemPackages on PATH, /nix, and Android's own commands. Logins are root's or those of the users in
# users.users (nix/users.nix), with keys (or a password, see below): users.users.<name>.openssh.authorizedKeys, as on NixOS,
# ~/.ssh/authorized_keys in the user's home, or authorizedKeysFiles. Its host keys are made on its first start, in
# /etc/ssh (/data/adb/nixup/etc/ssh on the phone itself), and kept, also when it is turned off; a hostKeys path
# that is there already (a secret the build puts on the phone) is used as it is.
#
# Its options are NixOS's, with the same names, types and meanings; one the phone cannot do is there too, and setting
# it stops the build and says why. Two defaults differ, as a phone's root has no password: PasswordAuthentication and
# KbdInteractiveAuthentication are false (NixOS: true).
#
# Passwords (users.users.<name>.hashedPassword, ...) are checked by PAM, as on NixOS: with password logins on, UsePAM is
# too, sshd reads /etc/pam.d/sshd (pam_unix: /etc/shadow, see nix/users.nix) and pam_unix's helper is where nixpkgs'
# PAM looks for it (/run/wrappers/bin/unix_chkpwd). nixpkgs' OpenSSH without PAM checks a password with DES_crypt
# alone, which no $6$ or yescrypt hash matches.
{ config, lib, pkgs, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.services.openssh;
  q = lib.escapeShellArg;

  # As NixOS writes settings into sshd_config: true and false as yes and no; lists comma- or space-separated.
  commaSeparated = [ "Ciphers" "KexAlgorithms" "Macs" ];
  settingValue = key: value:
    if builtins.isBool value then (if value then "yes" else "no")
    else if builtins.isList value then lib.concatStringsSep (if lib.elem key commaSeparated then "," else " ") (map toString value)
    else toString value;
  settings = lib.filterAttrs (_: value: value != null) (removeAttrs cfg.settings [ "_module" ]);

  # Each user's keys: from files of the config's folder read now, as on NixOS; a file named by its full path on the
  # phone is read there, when sshd starts.
  keyLines = keys: keys.keys ++ lib.concatMap (file:
    lib.filter (line: line != "" && !lib.hasPrefix "#" line) (map lib.trim (lib.splitString "\n" (builtins.readFile file)))
  ) (lib.filter builtins.isPath keys.keyFiles);
  phoneKeyFiles = keys: lib.filter (file: !builtins.isPath file) keys.keyFiles;
  withKeys = lib.filterAttrs (_: user: user.enable && (user.openssh.authorizedKeys.keys != [ ] || user.openssh.authorizedKeys.keyFiles != [ ]))
    config.users.users;
  writeKeys = name: user: let keys = user.openssh.authorizedKeys; in ''
    {
      printf '%s' ${q (lib.concatMapStrings (line: "${line}\n") (keyLines keys))}
      ${lib.concatMapStrings (file: ''
        if [ -r ${q file} ]; then cat ${q file}; echo; else echo "No key file ${file} on the phone: its keys are left out" >&2; fi
      '') (phoneKeyFiles keys)}
    } | grep -v '^[[:space:]]*$' > /etc/ssh/authorized_keys.d/.${name}.new || true
    chmod 0444 /etc/ssh/authorized_keys.d/.${name}.new
    mv -f /etc/ssh/authorized_keys.d/.${name}.new /etc/ssh/authorized_keys.d/${name}
  '';

  # An IPv6 address with a port in brackets, as sshd reads it: "::" or, as NixOS's configs write it, "[::]".
  listen = { addr, port, ... }:
    let host = if port != null && lib.hasInfix ":" addr && !lib.hasPrefix "[" addr then "[${addr}]" else addr;
    in "ListenAddress ${host}${lib.optionalString (port != null) ":${toString port}"}\n";

  sshdConfig = ''
    # Nixup's sshd_config, from services.openssh (see nix/openssh.nix).
    ${lib.concatMapStrings (port: "Port ${toString port}\n") cfg.ports}${lib.concatMapStrings listen cfg.listenAddresses}${lib.concatMapStrings (key: "HostKey ${key.path}\n") cfg.hostKeys}PidFile none
    PrintLastLog no
    ${lib.optionalString cfg.allowSFTP "Subsystem sftp ${cfg.sftpServerExecutable} ${lib.concatStringsSep " " cfg.sftpFlags}\n"}${lib.optionalString (!(settings ? AuthorizedKeysFile)) "AuthorizedKeysFile ${lib.optionalString cfg.authorizedKeysInHomedir "%h/.ssh/authorized_keys "}/etc/ssh/authorized_keys.d/%u ${lib.concatStringsSep " " cfg.authorizedKeysFiles}\n"}
    ${lib.optionalString (cfg.banner != null) "Banner /etc/ssh/banner\n"}${lib.concatStrings (lib.mapAttrsToList (key: value: "${key} ${settingValue key value}\n") settings)}${cfg.extraConfig}
  '';

  # NixOS's options the phone has not got: each one's value that leaves it unused, and why.
  notHere = [
    { name = "startWhenNeeded"; unused = !cfg.startWhenNeeded; why = "sshd starts with each build and at boot; one started for each connection (a socket with Accept) is not on the phone."; }
    { name = "authorizedKeysCommand"; unused = cfg.authorizedKeysCommand == "none"; why = "not on the phone yet."; }
  ];
in
{
  options.services.openssh = {
    enable = lib.mkEnableOption ''
      OpenSSH's server (sshd), as NixOS's services.openssh: run by root in Nixup's view, where a session gets root's
      shell with environment.systemPackages, as the app's terminal has it. It starts with each build and at boot'';

    package = lib.mkPackageOption pkgs "openssh" { };

    ports = mkOption {
      type = types.listOf types.port;
      default = [ 22 ];
      description = "The ports sshd listens on.";
    };

    listenAddresses = mkOption {
      type = types.listOf (types.submodule {
        options = {
          addr = mkOption {
            type = types.nullOr types.str;
            default = null;
            description = "An address to listen on (IPv4 or IPv6).";
          };
          port = mkOption {
            type = types.nullOr types.port;
            default = null;
            description = "Its port (null: each of ports).";
          };
        };
      });
      default = [ ];
      example = [ { addr = "127.0.0.1"; port = 22; } ];
      description = ''
        The addresses sshd listens on (none: every address). "127.0.0.1" keeps the phone's networks (Wi-Fi, mobile
        data) out while its tailnet still reaches it: services.tailscale passes connections to its own address on.
      '';
    };

    openFirewall = mkOption {
      type = types.bool;
      default = true;
      description = "Open the firewall for ports, as on NixOS. A phone has no firewall in its way, so here it does nothing.";
    };

    allowSFTP = mkOption {
      type = types.bool;
      default = true;
      description = "SFTP (file transfers, as file managers and sshfs use).";
    };

    sftpServerExecutable = mkOption {
      type = types.str;
      default = "${cfg.package}/libexec/sftp-server";
      defaultText = lib.literalExpression ''"''${config.services.openssh.package}/libexec/sftp-server"'';
      example = "internal-sftp";
      description = "The SFTP server: a program's path, or \"internal-sftp\" for sshd's own.";
    };

    sftpFlags = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "-f AUTHPRIV" "-l INFO" ];
      description = "Flags for the SFTP server.";
    };

    hostKeys = mkOption {
      type = types.listOf types.attrs;
      default = [
        { type = "rsa"; bits = 4096; path = "/etc/ssh/ssh_host_rsa_key"; }
        { type = "ed25519"; path = "/etc/ssh/ssh_host_ed25519_key"; }
      ];
      description = ''
        sshd's host keys, made on its first start where they are not yet (type, path, and bits, rounds, comment), as
        on NixOS. /etc/ssh is /data/adb/nixup/etc/ssh on the phone.
      '';
    };

    banner = mkOption {
      type = types.nullOr types.lines;
      default = null;
      description = "Text shown before a login.";
    };

    authorizedKeysFiles = mkOption {
      type = types.listOf types.str;
      default = [ ];
      description = ''
        More files sshd reads keys from, as AuthorizedKeysFile takes them (%u: the user, %h: its home). On the phone
        each must be in a folder only root can write to, all the way up: /data is not one, so a key file there goes in
        users.users.<name>.openssh.authorizedKeys.keyFiles instead.
      '';
    };

    authorizedKeysInHomedir = mkOption {
      type = types.bool;
      default = true;
      description = "Read ~/.ssh/authorized_keys too: root's, in /data/adb/nixup/home/.ssh.";
    };

    authorizedKeysCommand = mkOption {
      type = types.str;
      default = "none";
      description = "A program that gives a user's keys, as on NixOS: not on the phone yet.";
    };

    authorizedKeysCommandUser = mkOption {
      type = types.str;
      default = "nobody";
      description = "The user that runs authorizedKeysCommand, as on NixOS.";
    };

    moduliFile = mkOption {
      type = types.path;
      default = "${cfg.package}/etc/ssh/moduli";
      defaultText = lib.literalExpression ''"''${config.services.openssh.package}/etc/ssh/moduli"'';
      description = "The Diffie-Hellman groups sshd may use (/etc/ssh/moduli).";
    };

    startWhenNeeded = mkOption {
      type = types.bool;
      default = false;
      description = "Start sshd on the first connection, as on NixOS: not on the phone (sshd always runs).";
    };

    settings = mkOption {
      type = types.submodule {
        freeformType = types.attrsOf (types.nullOr (types.oneOf [ types.str types.int types.bool (types.listOf types.str) ]));
        options = {
          PasswordAuthentication = mkOption {
            type = types.nullOr types.bool;
            default = false;
            description = "Password logins (NixOS: true; a phone's root has no password, so here false).";
          };
          KbdInteractiveAuthentication = mkOption {
            type = types.nullOr types.bool;
            default = false;
            description = "Keyboard-interactive logins (NixOS: true; they need a password or PAM, which a phone has not got).";
          };
          PermitRootLogin = mkOption {
            type = types.nullOr (types.enum [ "yes" "without-password" "prohibit-password" "forced-commands-only" "no" ]);
            default = "prohibit-password";
            description = "Whether root may log in, and how: \"prohibit-password\" takes keys only.";
          };
          X11Forwarding = mkOption {
            type = types.nullOr types.bool;
            default = false;
            description = "X11 forwarding.";
          };
          UseDns = mkOption {
            type = types.nullOr types.bool;
            default = false;
            description = "Look up the name of each client's address.";
          };
          StrictModes = mkOption {
            type = types.nullOr types.bool;
            default = true;
            description = "Refuse key files that others can write to (or in folders they can).";
          };
          LogLevel = mkOption {
            type = types.nullOr (types.enum [ "QUIET" "FATAL" "ERROR" "INFO" "VERBOSE" "DEBUG" "DEBUG1" "DEBUG2" "DEBUG3" ]);
            default = "INFO";
            description = "How much sshd logs (in /data/adb/nixup/units/sshd/log).";
          };
          GatewayPorts = mkOption {
            type = types.nullOr types.str;
            default = "no";
            description = "Whether remote port forwardings may listen on other addresses than localhost.";
          };
          PrintMotd = mkOption {
            type = types.nullOr types.bool;
            default = false;
            description = "Show /etc/motd at a login.";
          };
          UsePAM = mkOption {
            type = types.nullOr types.bool;
            default = false;
            description = ''
              Logins through PAM (pam_unix, with the view's /etc/shadow), which passwords need: on by itself with
              PasswordAuthentication or KbdInteractiveAuthentication (NixOS: true).
            '';
          };
          Ciphers = mkOption {
            type = types.nullOr (types.listOf types.str);
            default = null;
            description = "The ciphers allowed (null: OpenSSH's own list).";
          };
          KexAlgorithms = mkOption {
            type = types.nullOr (types.listOf types.str);
            default = null;
            description = "The key exchange algorithms allowed (null: OpenSSH's own list).";
          };
          Macs = mkOption {
            type = types.nullOr (types.listOf types.str);
            default = null;
            description = "The MACs allowed (null: OpenSSH's own list).";
          };
          AllowUsers = mkOption {
            type = types.nullOr (types.listOf types.str);
            default = null;
            description = "Only these users (and user@host patterns) may log in.";
          };
          DenyUsers = mkOption {
            type = types.nullOr (types.listOf types.str);
            default = null;
            description = "These users may not log in.";
          };
          AllowGroups = mkOption {
            type = types.nullOr (types.listOf types.str);
            default = null;
            description = "Only users of these groups may log in.";
          };
          DenyGroups = mkOption {
            type = types.nullOr (types.listOf types.str);
            default = null;
            description = "Users of these groups may not log in.";
          };
        };
      };
      default = { };
      example = { PermitRootLogin = "no"; LogLevel = "VERBOSE"; };
      description = "sshd_config's settings, by their names there, as on NixOS (sshd_config(5) lists them).";
    };

    extraConfig = mkOption {
      type = types.lines;
      default = "";
      description = "More lines for sshd_config, after the settings.";
    };
  };

  config = lib.mkIf cfg.enable {
    # ssh, scp and ssh-keygen in sessions too, as on NixOS.
    environment.systemPackages = [ cfg.package ];

    # Password logins go through PAM (see the top), as on NixOS: pam_unix with the view's users and /etc/shadow.
    services.openssh.settings.UsePAM = lib.mkDefault (cfg.settings.PasswordAuthentication == true
      || cfg.settings.KbdInteractiveAuthentication == true);
    environment.etc."pam.d/sshd" = lib.mkIf (cfg.settings.UsePAM == true) {
      text = ''
        # sshd's PAM (nix/openssh.nix): the view's users and their passwords (/etc/shadow), through pam_unix.
        auth     sufficient ${pkgs.pam}/lib/security/pam_unix.so likeauth try_first_pass
        auth     required   ${pkgs.pam}/lib/security/pam_deny.so
        account  required   ${pkgs.pam}/lib/security/pam_unix.so
        password sufficient ${pkgs.pam}/lib/security/pam_unix.so nullok yescrypt
        password required   ${pkgs.pam}/lib/security/pam_deny.so
        session  required   ${pkgs.pam}/lib/security/pam_unix.so
      '';
    };

    systemd.services.sshd = {
      description = "SSH daemon";
      wantedBy = [ "multi-user.target" ];
      # The phone's security: what the SSH server does shows in no notification (the Logs tab has it).
      notifications = false;
      path = [ cfg.package ];
      # Its config, keys and banner, written before each start: sshd_config(5) refuses what it does not take.
      preStart = ''
        mkdir -p /etc/ssh/authorized_keys.d /var/empty
        chmod 0755 /etc/ssh /etc/ssh/authorized_keys.d /var/empty
        ${lib.optionalString (cfg.settings.UsePAM == true) ''
          # pam_unix's helper, where nixpkgs' PAM looks for it (NixOS's security wrapper; sshd runs it as root).
          mkdir -p /run/wrappers/bin
          ln -sfn ${pkgs.pam}/bin/unix_chkpwd /run/wrappers/bin/unix_chkpwd
        ''}
        ${lib.concatMapStrings (key: ''
          if ! [ -s ${q key.path} ]; then
            mkdir -p "$(dirname ${q key.path})"
            echo "Making the host key ${key.path}"
            ssh-keygen -q -t ${q key.type} ${lib.optionalString (key ? bits) "-b ${toString key.bits}"} ${lib.optionalString (key ? rounds) "-a ${toString key.rounds}"} ${lib.optionalString (key ? comment) "-C ${q key.comment}"} -f ${q key.path} -N ""
          fi
        '') cfg.hostKeys}
        printf '%s' ${q sshdConfig} > /etc/ssh/sshd_config
        ${if cfg.banner != null then "printf '%s\\n' ${q cfg.banner} > /etc/ssh/banner" else "rm -f /etc/ssh/banner"}
        cp -f ${q (toString cfg.moduliFile)} /etc/ssh/moduli 2> /dev/null || true
        # Each user's keys; a user who left the config takes its file along.
        for file in /etc/ssh/authorized_keys.d/*; do
          case " ${lib.concatStringsSep " " (lib.attrNames withKeys)} " in *" ''${file##*/} "*) ;; *) rm -f "$file" ;; esac
        done
        ${lib.concatStrings (lib.mapAttrsToList writeKeys withKeys)}
        sshd -t -f /etc/ssh/sshd_config
      '';
      serviceConfig = {
        ExecStart = "${cfg.package}/bin/sshd -D -e -f /etc/ssh/sshd_config";
        # As on NixOS: a restart of sshd (a build that changes it) ends sshd alone, not the sessions it started, nor
        # a build run through one of them.
        KillMode = "process";
        Restart = "always";
        RestartSec = 5;
      };
    };

    assertions = [
      {
        assertion = cfg.ports != [ ] || lib.all (address: address.port != null) cfg.listenAddresses;
        message = "services.openssh: give it a port to listen on (ports, or listenAddresses with a port).";
      }
    ] ++ map (option: {
      assertion = option.unused;
      message = "services.openssh.${option.name}: ${option.why}";
    }) notHere;
  };
}
