# OpenSSH's server, as NixOS's services.openssh: sshd, a unit run by root in Seraph's view of the phone (see
# nix/units.nix), so a session is root's shell there, the one the app's terminal and seraph-shell open:
# environment.systemPackages on PATH, /nix, and Android's own commands. The phone has one user, root, so logins are
# root's, with keys only: users.users.root.openssh.authorizedKeys, as on NixOS, ~/.ssh/authorized_keys in its home
# (/data/adb/seraph/home), or authorizedKeysFiles. Its host keys are made on its first start, in /etc/ssh
# (/data/adb/seraph/etc/ssh on the phone itself), and kept, also when it is turned off.
#
# Its options are NixOS's, with the same names, types and meanings; one the phone cannot do is there too, and setting
# it stops the build and says why. Two defaults differ, as a phone's root has no password: PasswordAuthentication and
# KbdInteractiveAuthentication are false (NixOS: true).
{ config, lib, pkgs, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.services.openssh;
  root = config.users.users.root.openssh.authorizedKeys or { keys = [ ]; keyFiles = [ ]; };
  q = lib.escapeShellArg;

  # As NixOS writes settings into sshd_config: true and false as yes and no; lists comma- or space-separated.
  commaSeparated = [ "Ciphers" "KexAlgorithms" "Macs" ];
  settingValue = key: value:
    if builtins.isBool value then (if value then "yes" else "no")
    else if builtins.isList value then lib.concatStringsSep (if lib.elem key commaSeparated then "," else " ") (map toString value)
    else toString value;
  settings = lib.filterAttrs (_: value: value != null) (removeAttrs cfg.settings [ "_module" ]);

  # Keys from files of the config's folder are read now, as on NixOS; a file named by its full path on the phone is
  # read there, when sshd starts.
  keyLines = root.keys ++ lib.concatMap (file:
    lib.filter (line: line != "" && !lib.hasPrefix "#" line) (map lib.trim (lib.splitString "\n" (builtins.readFile file)))
  ) (lib.filter builtins.isPath root.keyFiles);
  phoneKeyFiles = lib.filter (file: !builtins.isPath file) root.keyFiles;

  listen = { addr, port, ... }:
    let host = if port != null && lib.hasInfix ":" addr then "[${addr}]" else addr;
    in "ListenAddress ${host}${lib.optionalString (port != null) ":${toString port}"}\n";

  sshdConfig = ''
    # Seraph's sshd_config, from services.openssh (see nix/openssh.nix).
    ${lib.concatMapStrings (port: "Port ${toString port}\n") cfg.ports}${lib.concatMapStrings listen cfg.listenAddresses}${lib.concatMapStrings (key: "HostKey ${key.path}\n") cfg.hostKeys}PidFile none
    PrintLastLog no
    ${lib.optionalString cfg.allowSFTP "Subsystem sftp ${cfg.sftpServerExecutable} ${lib.concatStringsSep " " cfg.sftpFlags}\n"}AuthorizedKeysFile ${lib.optionalString cfg.authorizedKeysInHomedir "%h/.ssh/authorized_keys "}/etc/ssh/authorized_keys.d/%u ${lib.concatStringsSep " " cfg.authorizedKeysFiles}
    ${lib.optionalString (cfg.banner != null) "Banner /etc/ssh/banner\n"}${lib.concatStrings (lib.mapAttrsToList (key: value: "${key} ${settingValue key value}\n") settings)}${cfg.extraConfig}
  '';

  # NixOS's options the phone has not got: each one's value that leaves it unused, and why.
  notHere = [
    { name = "startWhenNeeded"; unused = !cfg.startWhenNeeded; why = "sshd starts with each build and at boot; starting it on a first connection (socket activation) is not on the phone."; }
    { name = "authorizedKeysCommand"; unused = cfg.authorizedKeysCommand == "none"; why = "not on the phone yet."; }
    { name = "settings.UsePAM"; unused = cfg.settings.UsePAM == false; why = "a phone has no PAM."; }
  ];
in
{
  options.services.openssh = {
    enable = lib.mkEnableOption ''
      OpenSSH's server (sshd), as NixOS's services.openssh: run by root in Seraph's view, where a session gets root's
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
        on NixOS. /etc/ssh is /data/adb/seraph/etc/ssh on the phone.
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
        users.users.root.openssh.authorizedKeys.keyFiles instead.
      '';
    };

    authorizedKeysInHomedir = mkOption {
      type = types.bool;
      default = true;
      description = "Read ~/.ssh/authorized_keys too: root's, in /data/adb/seraph/home/.ssh.";
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
            description = "Whether root may log in, and how: \"prohibit-password\" takes keys only. root is the phone's only user.";
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
            description = "How much sshd logs (in /data/adb/seraph/units/sshd/log).";
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
            description = "Logins through PAM (NixOS: true). A phone has no PAM, so it must stay false.";
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

  options.users.users = mkOption {
    type = types.attrsOf (types.submodule {
      options.openssh = {
        authorizedKeys = {
          keys = mkOption {
            type = types.listOf types.singleLineStr;
            default = [ ];
            example = [ "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA… neo@laptop" ];
            description = "Public keys that may log in as this user, as on NixOS.";
          };
          keyFiles = mkOption {
            type = types.listOf (types.either types.path types.str);
            default = [ ];
            example = lib.literalExpression ''[ ./laptop.pub "/data/adb/seraph/secrets/ssh_keys" ]'';
            description = ''
              Files of public keys that may log in as this user: a file from the config's folder (read when the config
              is built, as on NixOS), or, as a string, the full path of one on the phone (read there when sshd starts).
            '';
          };
        };
        authorizedPrincipals = mkOption {
          type = types.listOf types.singleLineStr;
          default = [ ];
          description = "Certificate principals this user may log in with, as on NixOS: not on the phone yet.";
        };
      };
    });
    default = { };
    description = ''
      The phone's users, as NixOS's users.users: it has one, root, whose openssh.authorizedKeys sshd takes
      (services.openssh).
    '';
  };

  config = lib.mkIf cfg.enable {
    # ssh, scp and ssh-keygen in sessions too, as on NixOS.
    environment.systemPackages = [ cfg.package ];

    systemd.services.sshd = {
      description = "SSH daemon";
      wantedBy = [ "multi-user.target" ];
      path = [ cfg.package ];
      # Its config, keys and banner, written before each start: sshd_config(5) refuses what it does not take.
      preStart = ''
        mkdir -p /etc/ssh/authorized_keys.d /var/empty
        chmod 0755 /etc/ssh /etc/ssh/authorized_keys.d /var/empty
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
        keys=/etc/ssh/authorized_keys.d/root
        {
          printf '%s' ${q (lib.concatMapStrings (line: "${line}\n") keyLines)}
          ${lib.concatMapStrings (file: ''
            if [ -r ${q file} ]; then cat ${q file}; echo; else echo "No key file ${file} on the phone: its keys are left out" >&2; fi
          '') phoneKeyFiles}
        } | grep -v '^[[:space:]]*$' > "$keys.new" || true
        chmod 0444 "$keys.new"
        mv -f "$keys.new" "$keys"
        sshd -t -f /etc/ssh/sshd_config
      '';
      serviceConfig = {
        ExecStart = "${cfg.package}/bin/sshd -D -e -f /etc/ssh/sshd_config";
        Restart = "always";
        RestartSec = 5;
      };
    };

    assertions = [
      {
        assertion = lib.all (name: name == "root") (lib.attrNames (lib.filterAttrs (_: user:
          user.openssh.authorizedKeys.keys != [ ] || user.openssh.authorizedKeys.keyFiles != [ ]
        ) config.users.users));
        message = "users.users: the phone has one user, root; give the keys to users.users.root.openssh.authorizedKeys.";
      }
      {
        assertion = lib.all (file: builtins.isPath file || lib.hasPrefix "/" file) root.keyFiles;
        message = "users.users.root.openssh.authorizedKeys.keyFiles: a file from the config's folder is a path, written without quotes (./laptop.pub); a string is a full path on the phone.";
      }
      {
        assertion = lib.all (user: user.openssh.authorizedPrincipals == [ ]) (lib.attrValues config.users.users);
        message = "users.users.<name>.openssh.authorizedPrincipals: not on the phone yet.";
      }
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
