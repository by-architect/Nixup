# NixOS's own options that its service modules set or read besides their own (nix/nixos.nix runs those modules on
# the phone), each with NixOS's name and type, so a module builds here as it does there:
#   - what the phone does, it does: /etc/hosts (networking.hosts, extraHosts), the DNS servers its services use
#     (networking.nameservers), a unit's targets, timers and sockets (systemd.targets, systemd.timers, systemd.sockets,
#     in nix/units.nix), /etc (nix/etc.nix), systemd.tmpfiles (nix/tmpfiles.nix) and the shells' environment
#     (environment.variables, ..., nix/shells.nix);
#   - what has nothing to do on a phone is taken and does nothing: a firewall's open ports (a phone has none in the
#     way), IPv6, PAM's services, the documentation, D-Bus's and udev's packages (Android has neither);
#   - what it cannot do is there, so a module that only reads it builds, and turning it on stops the build and says
#     why: a firewall, units started when a path changes, mounts, kernel modules, polkit, sudo, another service NixOS
#     has and Nixup not.
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

  # A unit kind the phone has not got (paths, mounts): its options as NixOS's, any of them taken; one that is there
  # and wanted stops the build.
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

  cfgProxy = config.networking.proxy;
  hostsText = lib.concatStrings (lib.mapAttrsToList (ip: names: lib.optionalString (names != [ ]) "${ip} ${lib.concatStringsSep " " names}\n")
    config.networking.hosts) + config.networking.extraHosts;
in
{
  # NixOS's older names, which modules still use.
  imports = [
    (lib.mkAliasOptionModule [ "users" "extraUsers" ] [ "users" "users" ])
    (lib.mkAliasOptionModule [ "users" "extraGroups" ] [ "users" "groups" ])
  ];

  options = {
    networking = {
      firewall = mkOption {
        type = types.submodule {
          freeformType = types.lazyAttrsOf types.anything;
          options = firewallPorts // {
            enable = mkOption {
              type = types.bool;
              default = false;
              description = "As on NixOS, a firewall: a phone has none in the way of its services, and Nixup has none yet, so it is off (NixOS's is on).";
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
      hostFiles = mkOption {
        type = types.listOf types.path;
        default = [ ];
        example = lib.literalExpression ''[ "''${pkgs.stevenblack-blocklist}/hosts" ]'';
        description = ''
          Files whose lines go into the view's /etc/hosts, after networking.hosts and extraHosts, as on NixOS
          (networking.stevenblack's blocklist is one). Unlike NixOS's, the list has no files of networking.hosts and
          extraHosts: Nixup writes those lines itself. Android's own apps look names up in Android's hosts file, not
          this one: the services and shells of Nixup's view use it.
        '';
      };
      proxy = {
        default = mkOption {
          type = types.nullOr types.str;
          default = null;
          example = "http://127.0.0.1:3128";
          description = "The proxy of httpProxy, httpsProxy, ftpProxy, rsyncProxy and allProxy unless they say another, as on NixOS.";
        };
        httpProxy = mkOption {
          type = types.nullOr types.str;
          default = config.networking.proxy.default;
          defaultText = lib.literalExpression "config.networking.proxy.default";
          description = "http_proxy, as on NixOS.";
        };
        httpsProxy = mkOption {
          type = types.nullOr types.str;
          default = config.networking.proxy.default;
          defaultText = lib.literalExpression "config.networking.proxy.default";
          description = "https_proxy, as on NixOS.";
        };
        ftpProxy = mkOption {
          type = types.nullOr types.str;
          default = config.networking.proxy.default;
          defaultText = lib.literalExpression "config.networking.proxy.default";
          description = "ftp_proxy, as on NixOS.";
        };
        rsyncProxy = mkOption {
          type = types.nullOr types.str;
          default = config.networking.proxy.default;
          defaultText = lib.literalExpression "config.networking.proxy.default";
          description = "rsync_proxy, as on NixOS.";
        };
        allProxy = mkOption {
          type = types.nullOr types.str;
          default = config.networking.proxy.default;
          defaultText = lib.literalExpression "config.networking.proxy.default";
          description = "all_proxy, as on NixOS.";
        };
        noProxy = mkOption {
          type = types.nullOr types.str;
          default = null;
          example = "127.0.0.1,localhost,.localdomain";
          description = "no_proxy, as on NixOS (with a default proxy and no noProxy: 127.0.0.1,localhost).";
        };
        envVars = mkOption {
          type = types.attrs;
          internal = true;
          default = { };
          description = ''
            The proxy's environment variables, as on NixOS: every shell session gets them, and so does Nix (the phone's
            builds' downloads, Nix's daemon). Android's own apps, and Nixup's downloads of apps, keep Android's.
          '';
        };
      };
      nameservers = mkOption {
        type = types.listOf types.str;
        default = [ ];
        example = [ "1.1.1.1" "9.9.9.9" ];
        description = ''
          The DNS servers Nixup's services and shells ask, as on NixOS (the view's /etc/resolv.conf); empty: those of
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

    # (environment.variables, sessionVariables and the shells' environment are NixOS's own: nix/shells.nix.)
    environment = {
      pathsToLink = taken (types.listOf types.str) [ ] "folders of packages linked into the system's profile";
      extraOutputsToInstall = taken (types.listOf types.str) [ ] "more outputs of the packages to install";
    };

    security = {
      pki = {
        caBundle = mkOption {
          type = types.str;
          readOnly = true;
          default = "/etc/ssl/certs/ca-certificates.crt";
          description = "The trusted certificates, as on NixOS: in Nixup's view, Android's.";
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
      # (stateVersion, with the system's version: nix/version.nix.)
      nssModules = mkOption {
        type = types.listOf types.path;
        default = [ ];
        description = "NSS modules, as on NixOS. Taken; the phone's services find users and hosts in Nixup's /etc.";
        apply = list: { inherit list; path = lib.makeLibraryPath list; };
      };
      nssDatabases = anyAttrs;
      path = mkOption {
        type = types.package;
        readOnly = true;
        # With the commands every shell has, as NixOS's has them (cron's jobs run with it as their PATH). Made as
        # nix-env makes a profile, by Nix itself (nixpkgs' buildEnv would need Perl on the phone): each package with
        # a priority of its own, so that the first of two with the same file wins, as ignoreCollisions has it.
        default =
          let
            packages = config.environment.systemPackages ++ (with pkgs; [ bashInteractive coreutils findutils gnugrep gnused less ]);
            outputsOf = package: map (output: package.${output}.outPath or package.outPath) (package.meta.outputsToInstall or [ "out" ]);
          in
          derivation {
            name = "system-path";
            system = "builtin";
            builder = "builtin:buildenv";
            manifest = builtins.toFile "env-manifest.nix" "[ ]";
            derivations = lib.imap1 (priority: package: [ "true" priority (builtins.length (outputsOf package)) ] ++ outputsOf package) packages;
            preferLocalBuild = true;
            allowSubstitutes = false;
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
      kernelModules = mkOption { type = types.listOf types.str; default = [ ]; description = "As on NixOS: not loaded on the phone (its kernel is Android's; the build says which it left)."; };
      kernelPackages = mkOption {
        type = types.raw;
        default = pkgs.linuxPackages;
        defaultText = lib.literalExpression "pkgs.linuxPackages";
        description = "As on NixOS, read by modules (the kernel's version): taken, the phone's kernel is Android's.";
      };
      extraModulePackages = mkOption { type = types.listOf types.package; default = [ ]; description = "As on NixOS: not on the phone."; };
      kernelParams = mkOption { type = types.listOf types.str; default = [ ]; description = "As on NixOS: not on the phone."; };
      isContainer = taken types.bool false "whether the system is a container";
      enableContainers = taken types.bool false "NixOS containers";
      supportedFilesystems = taken (types.either (types.listOf types.str) (types.attrsOf types.bool)) [ ] "file systems the system can mount";
      binfmt = anyAttrs;
    };

    systemd = {
      slices = anyAttrs;
      mounts = mkOption { type = types.listOf types.anything; default = [ ]; description = "As on NixOS, mount units: not on the phone."; };
      automounts = mkOption { type = types.listOf types.anything; default = [ ]; description = "As on NixOS: not on the phone."; };
      packages = mkOption {
        type = types.listOf types.package;
        default = [ ];
        description = "Packages whose own unit files are used, as on NixOS (nix/units.nix reads them).";
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
      home-assistant = absent "Home Assistant" { };
      avahi = absent "Avahi (Android has its own mDNS)" { };
    };

    hardware = {
      graphics = absent "the GPU's drivers" { };
    };

    # (Home Manager's fontconfig follows it for its default.)
    fonts.fontconfig.enable = mkOption {
      type = types.bool;
      default = false;
      description = "As on NixOS, the system's fontconfig: the phone has none (Android draws its own text), so it is off (NixOS's is on).";
    };

    documentation = {
      enable = taken types.bool true "documentation";
      man.enable = taken types.bool true "man pages";
      # (programs.fish sets them, for its completions of the man pages.)
      man.cache.enable = taken types.bool false "a cache of the man pages, made at each build";
      man.cache.generateAtRuntime = taken types.bool false "a cache of the man pages, made at the first start";
      doc.enable = taken types.bool true "documentation";
      info.enable = taken types.bool true "info pages";
      nixos.enable = taken types.bool true "NixOS's manual";
    };

    users = {
      mutableUsers = taken types.bool true "whether users can be changed outside the config";
      enforceIdUniqueness = taken types.bool true "unique uids and gids";
      allowNoPasswordLogin = taken types.bool false "logins without a password";
    };
  };

  config = {
    # The view's /etc/hosts and /etc/resolv.conf (Etc.kt writes them).
    system.build.network = lib.filterAttrs (_: value: value != null) {
      hosts = if hostsText == "" then null else hostsText;
      nameservers = if config.networking.nameservers == [ ] then null else config.networking.nameservers;
      # The files of networking.hostFiles, whose lines Etc.kt adds (their paths: the phone fetches them first).
      hostFiles = if config.networking.hostFiles == [ ] then null else map (file: "${file}") config.networking.hostFiles;
      # The proxy's variables, which the phone's own Nix takes too (module/common.sh's nix_env, from Etc.kt's
      # /etc/nixup/proxy).
      proxy = if config.networking.proxy.envVars == { } then null else config.networking.proxy.envVars;
    };

    # The proxy's variables, as NixOS makes them, for every shell session and for Nix (its daemon, nix/nix.nix).
    networking.proxy.envVars =
      lib.optionalAttrs (cfgProxy.default != null) { no_proxy = "127.0.0.1,localhost"; }
      // lib.optionalAttrs (cfgProxy.httpProxy != null) { http_proxy = cfgProxy.httpProxy; }
      // lib.optionalAttrs (cfgProxy.httpsProxy != null) { https_proxy = cfgProxy.httpsProxy; }
      // lib.optionalAttrs (cfgProxy.rsyncProxy != null) { rsync_proxy = cfgProxy.rsyncProxy; }
      // lib.optionalAttrs (cfgProxy.ftpProxy != null) { ftp_proxy = cfgProxy.ftpProxy; }
      // lib.optionalAttrs (cfgProxy.allProxy != null) { all_proxy = cfgProxy.allProxy; }
      // lib.optionalAttrs (cfgProxy.noProxy != null) { no_proxy = cfgProxy.noProxy; };
    environment.sessionVariables = config.networking.proxy.envVars;
    nix.envVars = config.networking.proxy.envVars;
    # NixOS keeps /etc/hosts in environment.etc, which modules read (dnsmasq restarts when its source changes): the
    # same text, turned off, as Etc.kt writes the file itself.
    environment.etc.hosts = {
      enable = lib.mkDefault false;
      text = lib.mkDefault ("127.0.0.1 localhost\n::1 localhost ip6-localhost\n" + hostsText);
    };

    assertions = [
      (off "networking.firewall.enable" config.networking.firewall.enable "Nixup has no firewall yet (a phone has none in the way of its services): leave it off.")
      (off "networking.nftables.enable" config.networking.nftables.enable "not on the phone (it has no firewall of Nixup's).")
      (off "networking.networkmanager.enable" config.networking.networkmanager.enable "Android manages the phone's networks.")
      (off "networking.wireless.enable" config.networking.wireless.enable "Android manages the phone's Wi-Fi.")
      (off "networking.interfaces" (config.networking.interfaces != { }) "Android manages the phone's network interfaces.")
      (off "security.acme.certs" (config.security.acme.certs != { }) "certificates from Let's Encrypt are not on the phone yet.")
      (off "security.pki.certificateFiles" (config.security.pki.certificateFiles != [ ] || config.security.pki.certificates != [ ]) "more trusted certificates are not on the phone yet (Nixup's view trusts Android's).")
      (off "security.polkit.enable" config.security.polkit.enable "polkit is not on the phone.")
      (off "security.sudo.enable" config.security.sudo.enable "sudo is not on the phone (root is su there).")
      (off "security.doas.enable" config.security.doas.enable "doas is not on the phone.")
      (off "security.rtkit.enable" config.security.rtkit.enable "RealtimeKit is not on the phone.")
      (off "security.apparmor.enable" config.security.apparmor.enable "AppArmor is not on the phone (Android has SELinux).")
      (off "boot.extraModulePackages" (config.boot.extraModulePackages != [ ] || config.boot.kernelParams != [ ]) "the phone's kernel is Android's: Nixup loads no modules of its own and sets no parameters.")
      (off "systemd.mounts" (config.systemd.mounts != [ ] || config.systemd.automounts != [ ]) "mount units are not on the phone.")
      (off "systemd.network.enable" config.systemd.network.enable "Android manages the phone's networks.")
      (off "services.resolved.enable" config.services.resolved.enable "not on the phone (networking.nameservers sets its services' DNS servers).")
      (off "services.xserver.enable" config.services.xserver.enable "not on the phone.")
      (off "services.pulseaudio.enable" config.services.pulseaudio.enable "Android plays the phone's sound.")
      (off "services.pipewire.enable" config.services.pipewire.enable "Android plays the phone's sound.")
      (off "services.postgresql.enable" (config.services.postgresql.enable or false) "PostgreSQL needs System V shared memory, which Android's kernel has not got (no CONFIG_SYSVIPC): a module's own SQLite database is on the phone (often database.type = \"sqlite3\").")
      (off "services.home-assistant.enable" config.services.home-assistant.enable "Home Assistant is not on the phone yet.")
      (off "services.avahi.enable" config.services.avahi.enable "Android has its own mDNS on the phone's port 5353.")
      (off "hardware.graphics.enable" config.hardware.graphics.enable "the phone's GPU is Android's.")
    ];

    warnings = lib.optional (config.boot.kernelModules != [ ])
      "boot.kernelModules: the phone's kernel is Android's, and Nixup loads none of its modules (${lib.concatStringsSep ", " config.boot.kernelModules}): what is built into it is there."
    ++ lib.optional (config.boot.kernel.sysctl != { })
      "boot.kernel.sysctl: not set on the phone yet (${lib.concatStringsSep ", " (lib.attrNames config.boot.kernel.sysctl)})."
      ++ lib.optional (config.security.wrappers != { })
      "security.wrappers: the phone makes no setuid wrappers (${lib.concatStringsSep ", " (lib.attrNames config.security.wrappers)}): root runs those programs as they are.";
  };
}
