# NixOS's own options that its service modules set or read besides their own (nix/nixos.nix runs those modules on
# the phone), each with NixOS's name and type, so a module builds here as it does there:
#   - what the phone does, it does: /etc/hosts (networking.hosts, extraHosts), the DNS servers its services use
#     (networking.nameservers), environment variables (environment.variables), a unit's targets (systemd.targets,
#     in nix/units.nix), /etc (nix/etc.nix) and systemd.tmpfiles (nix/tmpfiles.nix);
#   - what has nothing to do on a phone is taken and does nothing: a firewall's open ports (a phone has none in the
#     way), IPv6, PAM's services, the documentation, D-Bus's and udev's packages (Android has neither);
#   - what it cannot do is there, so a module that only reads it builds, and turning it on stops the build and says
#     why: a firewall, socket activation, kernel modules, polkit, sudo, another service NixOS has and Seraph not.
{ config, lib, pkgs, options, ... }:
let
  inherit (lib) mkOption types;

  # An option taken and left alone ("taken" in the descriptions).
  taken = type: default: what: mkOption {
    inherit type default;
    description = "As on NixOS: ${what}. Taken; on the phone it does nothing.";
  };
  # A set of options of which the phone has none: any attribute is taken, enable is false, and turning it on stops
  # the build (see `off` below).
  absent = what: extra: mkOption {
    type = types.submodule {
      freeformType = types.lazyAttrsOf types.anything;
      options = {
        enable = mkOption {
          type = types.bool;
          default = false;
          description = "As on NixOS (${what}): not on the phone.";
        };
      } // extra;
    };
    default = { };
    description = "As on NixOS (${what}): not on the phone; a module that only reads it builds.";
  };
  anyAttrs = mkOption { type = types.lazyAttrsOf types.anything; default = { }; description = "As on NixOS (taken)."; };
  # What the build says when one of those is turned on.
  off = path: value: why: { assertion = !value; message = "${path}: ${why}"; };

  portRange = types.submodule {
    options = {
      from = mkOption { type = types.port; description = "First port."; };
      to = mkOption { type = types.port; description = "Last port."; };
    };
  };
  firewallPorts = {
    allowedTCPPorts = taken (types.listOf types.port) [ ] "TCP ports the firewall lets in (a phone has none in the way)";
    allowedUDPPorts = taken (types.listOf types.port) [ ] "UDP ports the firewall lets in";
    allowedTCPPortRanges = taken (types.listOf portRange) [ ] "TCP port ranges the firewall lets in";
    allowedUDPPortRanges = taken (types.listOf portRange) [ ] "UDP port ranges the firewall lets in";
  };

  # A unit kind the phone has not got (socket activation, timers, paths, mounts): its options as NixOS's, any of
  # them taken; one that is there and wanted stops the build.
  unitKind = kind: what: mkOption {
    type = types.attrsOf (types.submodule {
      freeformType = types.lazyAttrsOf types.anything;
      options = {
        enable = mkOption { type = types.bool; default = true; description = "Whether the ${kind} is there."; };
        wantedBy = mkOption { type = types.listOf types.str; default = [ ]; description = "As on NixOS."; };
        requiredBy = mkOption { type = types.listOf types.str; default = [ ]; description = "As on NixOS."; };
      };
    });
    default = { };
    description = "As NixOS's systemd.${kind}s: ${what}.";
  };
  wanted = units: lib.attrNames (lib.filterAttrs (_: unit: unit.enable && (unit.wantedBy != [ ] || unit.requiredBy != [ ])) units);

  hostsText = lib.concatStrings (lib.mapAttrsToList (ip: names: lib.optionalString (names != [ ]) "${ip} ${lib.concatStringsSep " " names}\n")
    config.networking.hosts) + config.networking.extraHosts;
  # environment.variables (and sessionVariables), as every session in the view gets them.
  variables = config.environment.variables // config.environment.sessionVariables;
  exportLine = name: value: "export ${name}=${lib.escapeShellArg (if builtins.isList value then lib.concatStringsSep ":" (map toString value) else toString value)}";
in
{
  options = {
    networking = {
      firewall = mkOption {
        type = types.submodule {
          freeformType = types.lazyAttrsOf types.anything;
          options = firewallPorts // {
            enable = mkOption {
              type = types.bool;
              default = false;
              description = "As on NixOS, a firewall: a phone has none in the way of its services, and Seraph has none yet, so it is off (NixOS's is on).";
            };
            allowPing = taken types.bool true "whether the firewall answers ping";
            trustedInterfaces = taken (types.listOf types.str) [ ] "interfaces the firewall lets everything in from";
            interfaces = mkOption {
              type = types.attrsOf (types.submodule { options = firewallPorts; });
              default = { };
              description = "As on NixOS: ports the firewall lets in on one interface. Taken; on the phone it does nothing.";
            };
            extraCommands = taken types.lines "" "iptables commands for the firewall";
            extraStopCommands = taken types.lines "" "iptables commands when the firewall stops";
            extraInputRules = taken types.lines "" "nftables rules for the firewall";
            extraForwardRules = taken types.lines "" "nftables rules for forwarded traffic";
            checkReversePath = taken (types.either types.bool (types.enum [ "strict" "loose" ])) true "reverse path filtering";
            logRefusedConnections = taken types.bool true "whether the firewall logs what it refuses";
            package = mkOption {
              type = types.package;
              default = pkgs.iptables;
              defaultText = lib.literalExpression "pkgs.iptables";
              description = "As on NixOS (taken: the phone runs no firewall).";
            };
          };
        };
        default = { };
        description = "As NixOS's networking.firewall: off on the phone, and its open ports are taken (nothing is in their way).";
      };
      nftables = absent "nftables" { };
      enableIPv6 = taken types.bool true "IPv6 (the phone has it as Android sets it)";
      domain = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "home.arpa";
        description = "The phone's domain, as on NixOS (for networking.fqdn, which modules read).";
      };
      search = taken (types.listOf types.str) [ ] "domains to search for host names";
      fqdn = mkOption {
        type = types.str;
        readOnly = true;
        default = if config.networking.domain != null then "${config.networking.hostName}.${config.networking.domain}"
          else throw "networking.fqdn: set networking.hostName and networking.domain (a module asked for the phone's full name)";
        defaultText = lib.literalExpression ''"''${networking.hostName}.''${networking.domain}"'';
        description = "The phone's full name, as on NixOS.";
      };
      fqdnOrHostName = mkOption {
        type = types.str;
        readOnly = true;
        default = if config.networking.domain != null then config.networking.fqdn else config.networking.hostName;
        defaultText = lib.literalExpression "networking.fqdn, else networking.hostName";
        description = "The phone's full name, or its host name, as on NixOS.";
      };
      hosts = mkOption {
        type = types.attrsOf (types.listOf types.str);
        default = { };
        example = { "192.168.1.10" = [ "nas" "nas.home.arpa" ]; };
        description = "Names of addresses in the view's /etc/hosts, as on NixOS (localhost is there too).";
      };
      extraHosts = mkOption {
        type = types.lines;
        default = "";
        description = "More lines of the view's /etc/hosts, as on NixOS.";
      };
      nameservers = mkOption {
        type = types.listOf types.str;
        default = [ ];
        example = [ "1.1.1.1" "9.9.9.9" ];
        description = ''
          The DNS servers Seraph's services and shells ask, as on NixOS (the view's /etc/resolv.conf); empty: those of
          the network in use. Android's own apps keep asking the network's (or Private DNS).
        '';
      };
      timeServers = taken (types.listOf types.str) [ ] "NTP servers (Android sets the phone's time)";
      resolvconf = absent "resolvconf" { };
      dhcpcd = absent "dhcpcd" { denyInterfaces = taken (types.listOf types.str) [ ] "interfaces dhcpcd leaves"; };
      networkmanager = absent "NetworkManager: Android manages the phone's networks" { };
      wireless = absent "wpa_supplicant: Android manages Wi-Fi" { };
      useNetworkd = taken types.bool false "systemd-networkd";
      useDHCP = taken types.bool true "DHCP on the interfaces (Android does it)";
      interfaces = mkOption {
        type = types.attrsOf types.anything;
        default = { };
        description = "As on NixOS, network interfaces: not on the phone (Android manages its networks).";
      };
    };

    environment = {
      variables = mkOption {
        type = types.attrsOf (types.oneOf [ types.str types.path types.int (types.listOf (types.oneOf [ types.str types.path ])) ]);
        default = { };
        example = { EDITOR = "nvim"; };
        description = "Environment variables every session in Seraph's view gets (ssh, the app's terminal, seraph-shell), as on NixOS.";
      };
      sessionVariables = mkOption {
        type = types.attrsOf (types.oneOf [ types.str types.path types.int (types.listOf (types.oneOf [ types.str types.path ])) ]);
        default = { };
        description = "As environment.variables, as on NixOS.";
      };
      pathsToLink = taken (types.listOf types.str) [ ] "folders of packages linked into the system's profile";
      extraOutputsToInstall = taken (types.listOf types.str) [ ] "more outputs of the packages to install";
    };

    security = {
      pki = {
        caBundle = mkOption {
          type = types.str;
          readOnly = true;
          default = "/etc/ssl/certs/ca-certificates.crt";
          description = "The trusted certificates, as on NixOS: in Seraph's view, Android's.";
        };
        certificateFiles = mkOption {
          type = types.listOf types.path;
          default = [ ];
          description = "More trusted certificates, as on NixOS: not on the phone yet.";
        };
        certificates = mkOption {
          type = types.listOf types.str;
          default = [ ];
          description = "More trusted certificates, as on NixOS: not on the phone yet.";
        };
      };
      acme = {
        certs = mkOption {
          type = types.attrsOf types.anything;
          default = { };
          description = "As on NixOS, certificates from Let's Encrypt: not on the phone yet.";
        };
        defaults = anyAttrs;
        acceptTerms = taken types.bool false "Let's Encrypt's terms";
      };
      wrappers = mkOption {
        type = types.attrsOf types.anything;
        default = { };
        description = ''
          As on NixOS, setuid wrappers of programs (crontab) for users that are not root. Taken: the phone makes
          none (root runs the programs as they are; the build says which it left).
        '';
      };
      pam = {
        services = mkOption {
          type = types.attrsOf types.anything;
          default = { };
          description = "As on NixOS, PAM's services. Taken; the phone has no PAM (sshd logs in with keys).";
        };
      };
      polkit = absent "polkit" { };
      sudo = absent "sudo: root is su on the phone" { };
      doas = absent "doas" { };
      rtkit = absent "RealtimeKit" { };
      apparmor = absent "AppArmor: Android has SELinux" { };
    };

    system = {
      stateVersion = mkOption {
        type = types.str;
        default = lib.trivial.release;
        defaultText = lib.literalExpression "lib.trivial.release";
        description = ''
          The NixOS release whose defaults NixOS's modules keep, as on NixOS (where it has no default): here the
          release of the config's nixpkgs.
        '';
      };
      nssModules = mkOption {
        type = types.listOf types.path;
        default = [ ];
        description = "NSS modules, as on NixOS. Taken; the phone's services find users and hosts in Seraph's /etc.";
        apply = list: { inherit list; path = lib.makeLibraryPath list; };
      };
      nssDatabases = anyAttrs;
      path = mkOption {
        type = types.package;
        readOnly = true;
        # With the commands every shell has, as NixOS's has them (cron's jobs run with it as their PATH).
        default = pkgs.buildEnv {
          name = "system-path";
          paths = config.environment.systemPackages ++ (with pkgs; [ bashInteractive coreutils findutils gnugrep gnused less ]);
          ignoreCollisions = true;
        };
        defaultText = lib.literalExpression "environment.systemPackages, with bash, coreutils, findutils, grep, sed and less";
        description = "The system's packages in one folder, as on NixOS (made on the phone when something uses it).";
      };
      checks = taken (types.listOf types.package) [ ] "builds that check the system";
      extraDependencies = taken (types.listOf types.unspecified) [ ] "paths kept with the system";
      extraSystemBuilderCmds = taken types.lines "" "commands the system's build runs";
    };

    boot = {
      kernel.sysctl = mkOption {
        type = types.attrsOf (types.nullOr (types.oneOf [ types.bool types.str types.int ]));
        default = { };
        description = "Kernel settings, as on NixOS: not set on the phone yet (the build says which it left).";
      };
      kernelModules = mkOption { type = types.listOf types.str; default = [ ]; description = "As on NixOS: not on the phone (its kernel is Android's)."; };
      extraModulePackages = mkOption { type = types.listOf types.package; default = [ ]; description = "As on NixOS: not on the phone."; };
      kernelParams = mkOption { type = types.listOf types.str; default = [ ]; description = "As on NixOS: not on the phone."; };
      isContainer = taken types.bool false "whether the system is a container";
      enableContainers = taken types.bool false "NixOS containers";
      supportedFilesystems = taken (types.either (types.listOf types.str) (types.attrsOf types.bool)) [ ] "file systems the system can mount";
      binfmt = anyAttrs;
    };

    systemd = {
      sockets = unitKind "socket" "socket activation, which the phone has not got: a module's option that starts its service without it (often socketActivation = false) runs it";
      timers = unitKind "timer" "timers: not on the phone yet";
      paths = unitKind "path" "units started when a path changes: not on the phone";
      slices = anyAttrs;
      mounts = mkOption { type = types.listOf types.anything; default = [ ]; description = "As on NixOS, mount units: not on the phone."; };
      automounts = mkOption { type = types.listOf types.anything; default = [ ]; description = "As on NixOS: not on the phone."; };
      packages = mkOption {
        type = types.listOf types.package;
        default = [ ];
        description = "Packages whose own unit files NixOS uses: not on the phone (it does not read packages' unit files): give the unit in systemd.services.";
      };
      user = mkOption {
        type = types.submodule { freeformType = types.lazyAttrsOf types.anything; };
        default = { };
        description = "As on NixOS, units of users' own systemd: not on the phone.";
      };
      network = absent "systemd-networkd: Android manages the phone's networks" {
        networks = anyAttrs;
        netdevs = anyAttrs;
        links = anyAttrs;
      };
      package = mkOption {
        type = types.package;
        default = pkgs.systemd;
        defaultText = lib.literalExpression "pkgs.systemd";
        description = "As on NixOS (a module may use its tools; the phone runs no systemd).";
      };
      enableStrictShellChecks = taken types.bool false "shellcheck on the units' scripts";
      additionalUpstreamSystemUnits = taken (types.listOf types.str) [ ] "systemd's own units to add";
      suppressedSystemUnits = taken (types.listOf types.str) [ ] "systemd's own units to leave out";
      coredump = anyAttrs;
      settings = anyAttrs;
    };

    services = {
      resolved = absent "systemd-resolved" { };
      xserver = absent "X" { };
      displayManager = absent "a display manager" {
        sessionPackages = taken (types.listOf types.package) [ ] "desktop sessions";
      };
      pulseaudio = absent "PulseAudio: Android plays the phone's sound" { };
      pipewire = absent "PipeWire: Android plays the phone's sound" {
        pulse.enable = mkOption { type = types.bool; default = false; description = "As on NixOS: not on the phone."; };
        alsa.enable = mkOption { type = types.bool; default = false; description = "As on NixOS: not on the phone."; };
      };
      dbus = {
        enable = taken types.bool true "the D-Bus system bus (Android has none)";
        packages = taken (types.listOf types.package) [ ] "D-Bus services' packages (Android has no D-Bus)";
      };
      udev = {
        packages = taken (types.listOf types.package) [ ] "udev's rules of packages (Android manages devices itself)";
        extraRules = taken types.lines "" "udev rules";
      };
      logrotate = {
        enable = taken types.bool false "logrotate (each unit's log on the phone is set aside past 1 MB)";
        settings = anyAttrs;
      };
      nginx = absent "nginx" { virtualHosts = anyAttrs; };
      httpd = absent "Apache" { virtualHosts = anyAttrs; };
      caddy = absent "Caddy" { virtualHosts = anyAttrs; };
      phpfpm = { pools = anyAttrs; };
      postgresql = absent "PostgreSQL" { };
      mysql = absent "MySQL" { };
      redis = { servers = anyAttrs; };
      home-assistant = absent "Home Assistant" { };
      avahi = absent "Avahi (Android has its own mDNS)" { };
      prometheus = absent "Prometheus" { exporters = anyAttrs; };
    };

    nix = {
      enable = taken types.bool true "Nix's daemon (Seraph runs Nix itself)";
      package = mkOption {
        type = types.package;
        default = pkgs.nix;
        defaultText = lib.literalExpression "pkgs.nix";
        description = "As on NixOS (a module may use its tools).";
      };
      settings = anyAttrs;
      extraOptions = taken types.lines "" "Nix's settings";
      gc = anyAttrs;
      nixPath = taken (types.listOf types.str) [ ] "NIX_PATH";
      registry = anyAttrs;
    };

    hardware = {
      graphics = absent "the GPU's drivers" { };
    };

    documentation = {
      enable = taken types.bool true "documentation";
      man.enable = taken types.bool true "man pages";
      doc.enable = taken types.bool true "documentation";
      info.enable = taken types.bool true "info pages";
      nixos.enable = taken types.bool true "NixOS's manual";
    };

    users = {
      mutableUsers = taken types.bool true "whether users can be changed outside the config";
      defaultUserShell = taken (types.nullOr (types.either types.package types.str)) null "the users' shell (the phone's is /etc/seraph/login)";
      enforceIdUniqueness = taken types.bool true "unique uids and gids";
      allowNoPasswordLogin = taken types.bool false "logins without a password";
    };
  };

  config = {
    environment.extraInit = lib.mkIf (variables != { }) (lib.mkBefore
      (lib.concatStringsSep "\n" (lib.mapAttrsToList exportLine variables)));

    # The view's /etc/hosts and /etc/resolv.conf (Etc.kt writes them).
    system.build.network = lib.filterAttrs (_: value: value != null) {
      hosts = if hostsText == "" then null else hostsText;
      nameservers = if config.networking.nameservers == [ ] then null else config.networking.nameservers;
    };

    assertions = [
      (off "networking.firewall.enable" config.networking.firewall.enable "Seraph has no firewall yet (a phone has none in the way of its services): leave it off.")
      (off "networking.nftables.enable" config.networking.nftables.enable "not on the phone (it has no firewall of Seraph's).")
      (off "networking.networkmanager.enable" config.networking.networkmanager.enable "Android manages the phone's networks.")
      (off "networking.wireless.enable" config.networking.wireless.enable "Android manages the phone's Wi-Fi.")
      (off "networking.interfaces" (config.networking.interfaces != { }) "Android manages the phone's network interfaces.")
      (off "security.acme.certs" (config.security.acme.certs != { }) "certificates from Let's Encrypt are not on the phone yet.")
      (off "security.pki.certificateFiles" (config.security.pki.certificateFiles != [ ] || config.security.pki.certificates != [ ]) "more trusted certificates are not on the phone yet (Seraph's view trusts Android's).")
      (off "security.polkit.enable" config.security.polkit.enable "polkit is not on the phone.")
      (off "security.sudo.enable" config.security.sudo.enable "sudo is not on the phone (root is su there).")
      (off "security.doas.enable" config.security.doas.enable "doas is not on the phone.")
      (off "security.rtkit.enable" config.security.rtkit.enable "RealtimeKit is not on the phone.")
      (off "security.apparmor.enable" config.security.apparmor.enable "AppArmor is not on the phone (Android has SELinux).")
      (off "boot.kernelModules" (config.boot.kernelModules != [ ] || config.boot.extraModulePackages != [ ] || config.boot.kernelParams != [ ]) "the phone's kernel is Android's: Seraph loads no modules and sets no parameters.")
      (off "systemd.sockets" (wanted config.systemd.sockets != [ ]) "${lib.concatStringsSep ", " (wanted config.systemd.sockets)}: socket activation is not on the phone; a module's option that runs its service without it (often socketActivation = false) does.")
      (off "systemd.timers" (wanted config.systemd.timers != [ ]) "${lib.concatStringsSep ", " (wanted config.systemd.timers)}: timers are not on the phone yet.")
      (off "systemd.paths" (wanted config.systemd.paths != [ ]) "${lib.concatStringsSep ", " (wanted config.systemd.paths)}: path units are not on the phone.")
      (off "systemd.mounts" (config.systemd.mounts != [ ] || config.systemd.automounts != [ ]) "mount units are not on the phone.")
      (off "systemd.packages" (config.systemd.packages != [ ]) "the phone does not read packages' own unit files: give the unit in systemd.services.")
      (off "systemd.user.services" ((config.systemd.user.services or { }) != { }) "units of users' own systemd are not on the phone.")
      (off "systemd.network.enable" config.systemd.network.enable "Android manages the phone's networks.")
      (off "services.resolved.enable" config.services.resolved.enable "not on the phone (networking.nameservers sets its services' DNS servers).")
      (off "services.xserver.enable" config.services.xserver.enable "not on the phone.")
      (off "services.pulseaudio.enable" config.services.pulseaudio.enable "Android plays the phone's sound.")
      (off "services.pipewire.enable" config.services.pipewire.enable "Android plays the phone's sound.")
      (off "services.nginx.enable" config.services.nginx.enable "nginx is not on the phone yet.")
      (off "services.httpd.enable" config.services.httpd.enable "Apache is not on the phone yet.")
      (off "services.caddy.enable" config.services.caddy.enable "Caddy is not on the phone yet.")
      (off "services.phpfpm.pools" (config.services.phpfpm.pools != { }) "PHP-FPM is not on the phone yet.")
      (off "services.postgresql.enable" config.services.postgresql.enable "PostgreSQL is not on the phone yet (a module's own SQLite database is: often database.type = \"sqlite3\").")
      (off "services.mysql.enable" config.services.mysql.enable "MySQL is not on the phone yet.")
      (off "services.redis.servers" (lib.any (server: server.enable or false) (lib.attrValues config.services.redis.servers)) "Redis is not on the phone yet.")
      (off "services.home-assistant.enable" config.services.home-assistant.enable "Home Assistant is not on the phone yet.")
      (off "services.avahi.enable" config.services.avahi.enable "Android has its own mDNS on the phone's port 5353.")
      (off "services.prometheus.enable" config.services.prometheus.enable "Prometheus is not on the phone yet.")
      (off "hardware.graphics.enable" config.hardware.graphics.enable "the phone's GPU is Android's.")
    ];

    warnings = lib.optional (config.boot.kernel.sysctl != { })
      "boot.kernel.sysctl: not set on the phone yet (${lib.concatStringsSep ", " (lib.attrNames config.boot.kernel.sysctl)})."
      ++ lib.optional (config.security.wrappers != { })
      "security.wrappers: the phone makes no setuid wrappers (${lib.concatStringsSep ", " (lib.attrNames config.security.wrappers)}): root runs those programs as they are.";
  };
}
