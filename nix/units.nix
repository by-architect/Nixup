# Services as NixOS writes them, systemd.services.<name>, which Seraph's engine runs on the phone the
# way systemd runs them on NixOS: each unit's commands (serviceConfig.ExecStart, or script), as its
# user (serviceConfig.User, else root), with its environment and PATH; its folders in /var/lib,
# /var/cache, /var/log, /run and /etc, kept across restarts and builds; started again when it stops
# (Restart); in order (after, before, wants, requires); and with each build and at boot when it is
# wantedBy multi-user.target (or a target that is). Its programs are Nix packages
# (${pkgs.foo}/bin/foo), which the phone fetches from cache.nixos.org (and the small files a config
# makes, which it builds), run in a view of the phone where /nix exists, as are Seraph's own
# services (services.tailscale, services.syncthing) and NixOS's own service modules (nix/nixos.nix).
#
# In that view the phone is as it is (/system, /data, /storage, /dev, ...), with /nix (Seraph's
# store), Seraph's /etc (the network's DNS servers, Android's certificates, environment.etc),
# /var and /run (the units' state, which stays) and a /tmp of the unit's own. Android's commands are
# on PATH too.
#
# The phone has no systemd: a unit says what the engine does with the options below and these keys
# of serviceConfig (each as systemd.exec(5) and systemd.service(5) have it):
#   commands     ExecStart, ExecStartPre, ExecStartPost, ExecCondition, ExecStop, ExecStopPost,
#                ExecReload (taken: a changed unit starts again), Type (simple, exec, oneshot, forking;
#                notify, notify-reload, idle and dbus run as simple), RemainAfterExit, PIDFile,
#                Restart (no, always, on-success, on-failure, on-abnormal, on-abort, on-watchdog),
#                RestartSec, SuccessExitStatus, RestartForceExitStatus, RestartPreventExitStatus,
#                KillMode, KillSignal, SendSIGKILL, TimeoutStopSec, TimeoutStartSec, TimeoutSec
#   environment  Environment, EnvironmentFile, UnsetEnvironment, WorkingDirectory
#   user         User, Group, SupplementaryGroups, DynamicUser, PermissionsStartOnly,
#                AmbientCapabilities, NoNewPrivileges, UMask, Nice, OOMScoreAdjust, Limit*
#   folders      StateDirectory, RuntimeDirectory, CacheDirectory, LogsDirectory,
#                ConfigurationDirectory, their *Mode, RuntimeDirectoryPreserve, BindPaths,
#                BindReadOnlyPaths
#   secrets      LoadCredential, SetCredential (into $CREDENTIALS_DIRECTORY, readable by the unit
#                alone)
# and of unitConfig: RequiresMountsFor, the Condition and Assert keys on paths
# (ConditionPathExists, ...), ConditionVirtualization, ConditionArchitecture and
# ConditionKernelCommandLine. What only hardens a service on NixOS (ProtectSystem, PrivateDevices,
# SystemCallFilter, CPUQuota, ...: the `taken` lists below) is taken, and nothing done: a phone
# unit runs in its own view, its own mount namespace with its own /tmp, as its own user. Anything
# else stops the build and says so, as NixOS's options the phone has not got do.
#
# A service user (User, or DynamicUser) is a user of the view (nix/users.nix) with a uid from 400 to
# 999, as NixOS gives its system users: Android lets those use the network (higher ones, as
# systemd's dynamic users have, it does not). Android's shared storage (/storage, /sdcard) only
# apps and root may open; a unit whose user is in media_rw (or sdcard_rw, external_storage: its
# SupplementaryGroups, or users.users.<name>.extraGroups) gets a /storage of its own in its view,
# with the real folders Android keeps it in (the phone's own storage and each SD card), which
# media_rw may use (Units.kt's STORAGE). Or it runs as root: serviceConfig.User = lib.mkForce "root".
#
# Timers (systemd.timers, and startAt, which makes one as on NixOS) start their unit at their times:
# OnCalendar (systemd.time(7)'s calendar events, with the phone's time zone or one of their own),
# OnBootSec, OnStartupSec, OnActiveSec, OnUnitActiveSec, OnUnitInactiveSec, Persistent and
# RandomizedDelaySec (Timers.kt). A phone asleep runs nothing: a time that passed while it slept
# comes once it wakes, as with systemd's timers without WakeSystem.
#
# Sockets (systemd.sockets: listenStreams, listenDatagrams, socketConfig.ListenStream and
# ListenDatagram; TCP, UDP and unix sockets) the phone opens as root and hands to their service as
# systemd does (as fds 3 and on, with LISTEN_FDS and LISTEN_PID, through systemfd), so a port below
# 1024 is a service user's too. The service starts with the build and at boot rather than at its
# first connection; a service started for each connection (Accept = true) is not on the phone.
#
# unitConfig.RequiresMountsFor: the phone's storage a unit needs (an SD card's folder, the shared
# storage), which Android mounts only once the phone is first unlocked after it starts, or which may
# be taken out. The unit starts once all of it is mounted, waiting quietly till then (a failure
# neither told nor counted); one that storage reached after it started (in its view, which takes no
# mount made later) starts again in a fresh view; and one whose storage goes away stops, to wait for
# it again.
#
# On a phone the app on the screen comes first: units run on Android's background cores (its
# system-background cpuset), at Nice 10 unless serviceConfig.Nice says otherwise, with a low share
# of the storage's time, so a busy service does not make the screen stutter.
{ config, lib, pkgs, ... }:
let
  inherit (lib) mkOption types;

  # What serviceConfig and unitConfig take, as NixOS's unitOption: a value or a list of them (lists in
  # it are flattened); a list from one module and a value from another make one list, as systemd's
  # repeated keys do.
  unitValue =
    let one = types.oneOf [ types.str types.int types.bool types.float types.path types.package ]; in
    lib.mkOptionType {
      name = "systemd option";
      description = "systemd option (a value, or a list of them)";
      check = value: if builtins.isList value then lib.all one.check (lib.flatten value) else one.check value;
      merge = loc: defs:
        if lib.any (def: builtins.isList def.value) defs then lib.concatMap (def: lib.flatten (lib.toList def.value)) defs
        else lib.mergeEqualOption loc defs;
    };

  # The serviceConfig keys the engine does something with (see the top).
  supported = [
    "ExecStart" "ExecStartPre" "ExecStartPost" "ExecCondition" "ExecStop" "ExecStopPost" "ExecReload"
    "Type" "RemainAfterExit" "PIDFile" "Restart" "RestartSec"
    "SuccessExitStatus" "RestartForceExitStatus" "RestartPreventExitStatus"
    "KillMode" "KillSignal" "SendSIGKILL" "TimeoutStopSec" "TimeoutStartSec" "TimeoutSec"
    "Environment" "EnvironmentFile" "UnsetEnvironment" "WorkingDirectory"
    "User" "Group" "SupplementaryGroups" "DynamicUser" "PermissionsStartOnly"
    "AmbientCapabilities" "NoNewPrivileges" "UMask" "Nice" "OOMScoreAdjust"
    "StateDirectory" "RuntimeDirectory" "CacheDirectory" "LogsDirectory" "ConfigurationDirectory"
    "StateDirectoryMode" "RuntimeDirectoryMode" "CacheDirectoryMode" "LogsDirectoryMode" "ConfigurationDirectoryMode"
    "RuntimeDirectoryPreserve" "BindPaths" "BindReadOnlyPaths"
    "LoadCredential" "SetCredential"
  ] ++ map (limit: "Limit${limit}") limits;

  # systemd's Limit* keys (the engine sets them with the shell's ulimit; RTTIME it has not, and leaves).
  limits = [ "CPU" "FSIZE" "DATA" "STACK" "CORE" "RSS" "NOFILE" "AS" "NPROC" "MEMLOCK" "LOCKS" "SIGPENDING" "MSGQUEUE" "NICE" "RTPRIO" "RTTIME" ];

  # The serviceConfig keys that only harden a service, limit what it may use or say how systemd
  # logs and accounts for it: on the phone a unit runs in a view of its own (its mount namespace,
  # its /tmp), as its own user, and logs into its own log, so these are taken and nothing is done.
  taken = [
    # Sandboxing.
    "CapabilityBoundingSet" "SecureBits" "DevicePolicy" "DeviceAllow" "PrivateDevices" "PrivateTmp"
    "PrivateMounts" "PrivateNetwork" "PrivateUsers" "PrivateIPC" "PrivatePIDs" "ProtectSystem" "ProtectHome"
    "ProtectKernelTunables" "ProtectKernelModules" "ProtectKernelLogs" "ProtectControlGroups" "ProtectClock"
    "ProtectHostname" "ProtectProc" "ProcSubset" "RestrictAddressFamilies" "RestrictNamespaces"
    "RestrictRealtime" "RestrictSUIDSGID" "RestrictFileSystems" "RestrictNetworkInterfaces" "LockPersonality"
    "MemoryDenyWriteExecute" "RemoveIPC" "SystemCallFilter" "SystemCallArchitectures" "SystemCallErrorNumber"
    "SystemCallLog" "IPAddressAllow" "IPAddressDeny" "IPAccounting" "IPIngressFilterPath" "IPEgressFilterPath"
    "SocketBindAllow" "SocketBindDeny" "ReadOnlyPaths" "ReadWritePaths" "InaccessiblePaths" "ExecPaths"
    "NoExecPaths" "TemporaryFileSystem" "MountAPIVFS" "MountFlags" "BindLogSockets" "KeyringMode"
    "RootDirectory" "RootImage" "RootEphemeral" "MountImages" "ExtensionImages" "ExtensionDirectories"
    "NetworkNamespacePath" "IPCNamespacePath" "PrivateDevices" "SELinuxContext" "AppArmorProfile"
    "SmackProcessLabel" "Personality" "ImportCredential" "PAMName" "SetLoginEnvironment" "PassEnvironment"
    # Resource control and scheduling.
    "Slice" "Delegate" "CPUQuota" "CPUQuotaPeriodSec" "CPUWeight" "StartupCPUWeight" "CPUShares" "CPUAccounting"
    "MemoryAccounting" "MemoryMax" "MemoryHigh" "MemoryLow" "MemoryMin" "MemorySwapMax" "MemoryZSwapMax" "MemoryLimit"
    "StartupMemoryMax" "StartupMemoryHigh" "TasksAccounting" "TasksMax" "IOAccounting" "IOWeight" "StartupIOWeight"
    "IODeviceWeight" "IOReadBandwidthMax" "IOWriteBandwidthMax" "IOReadIOPSMax" "IOWriteIOPSMax" "BlockIOWeight"
    "BlockIOAccounting" "ManagedOOMSwap" "ManagedOOMMemoryPressure" "ManagedOOMMemoryPressureLimit"
    "ManagedOOMPreference" "OOMPolicy" "CPUSchedulingPolicy" "CPUSchedulingPriority" "CPUSchedulingResetOnFork"
    "CPUAffinity" "NUMAPolicy" "NUMAMask" "IOSchedulingClass" "IOSchedulingPriority" "TimerSlackNSec"
    "AllowedCPUs" "AllowedMemoryNodes" "CoredumpFilter" "CoredumpReceive" "DisableControllers"
    # Logging, which goes to the unit's log.
    "StandardOutput" "StandardError" "StandardInput" "SyslogIdentifier" "SyslogFacility" "SyslogLevel"
    "SyslogLevelPrefix" "LogLevelMax" "LogExtraFields" "LogRateLimitIntervalSec" "LogRateLimitBurst"
    "LogNamespace" "TTYPath" "TTYReset" "TTYVHangup" "TTYVTDisallocate" "UtmpIdentifier" "UtmpMode"
    # systemd's own bookkeeping.
    "NotifyAccess" "WatchdogSec" "WatchdogSignal" "GuessMainPID" "FileDescriptorStoreMax"
    "FileDescriptorStorePreserve" "BusName" "Sockets" "USBFunctionDescriptors" "USBFunctionStrings"
    "OpenFile" "ExitType" "RestartSteps" "RestartMaxDelaySec" "RestartKillSignal" "FinalKillSignal"
    "SendSIGHUP" "TimeoutAbortSec" "TimeoutStartFailureMode" "TimeoutStopFailureMode" "RuntimeMaxSec"
    "RuntimeRandomizedExtraSec" "ReloadSignal" "IgnoreSIGPIPE" "StartLimitIntervalSec" "StartLimitBurst"
    "StartLimitAction" "RebootArgument" "SuccessAction" "FailureAction" "NonBlocking"
  ];

  # unitConfig: the keys the engine does something with, and those it takes and leaves (what orders
  # or binds units the engine does with the options below; the rest is systemd's own).
  unitSupported = [ "RequiresMountsFor" ] ++ lib.attrNames pathChecks ++ lib.attrNames otherChecks;
  unitTaken = [
    "Description" "Documentation" "DefaultDependencies" "StartLimitIntervalSec" "StartLimitBurst"
    "StartLimitAction" "StopWhenUnneeded" "RefuseManualStart" "RefuseManualStop" "AllowIsolate"
    "IgnoreOnIsolate" "JobTimeoutSec" "JobRunningTimeoutSec" "JobTimeoutAction" "OnFailureJobMode"
    "CollectMode" "SourcePath" "WantsMountsFor" "JoinsNamespaceOf" "SuccessAction" "FailureAction"
    "RebootArgument" "ConditionACPower" "AssertACPower"
  ];
  isTakenUnitKey = key: lib.elem key unitTaken || lib.hasPrefix "X-" key;

  # The conditions on paths (unitConfig.Condition..., Assert... alike), and what each tests (sh's test).
  pathChecks = lib.genAttrs [
    "ConditionPathExists" "ConditionPathExistsGlob" "ConditionPathIsDirectory" "ConditionPathIsSymbolicLink"
    "ConditionPathIsMountPoint" "ConditionPathIsReadWrite" "ConditionDirectoryNotEmpty" "ConditionFileNotEmpty"
    "ConditionFileIsExecutable"
    "AssertPathExists" "AssertPathExistsGlob" "AssertPathIsDirectory" "AssertPathIsSymbolicLink"
    "AssertPathIsMountPoint" "AssertPathIsReadWrite" "AssertDirectoryNotEmpty" "AssertFileNotEmpty"
    "AssertFileIsExecutable"
  ] (_: true);
  otherChecks = lib.genAttrs [
    "ConditionVirtualization" "ConditionArchitecture" "ConditionKernelCommandLine"
    "AssertVirtualization" "AssertArchitecture" "AssertKernelCommandLine"
  ] (_: true);

  # SuccessExitStatus and its kind take exit codes, and the signals that ended a program (a shell's
  # status 128 + its number).
  signals = {
    SIGHUP = 129; SIGINT = 130; SIGQUIT = 131; SIGABRT = 134; SIGKILL = 137; SIGUSR1 = 138; SIGSEGV = 139;
    SIGUSR2 = 140; SIGPIPE = 141; SIGALRM = 142; SIGTERM = 143;
  };
  types' = [ "simple" "exec" "oneshot" "forking" "notify" "notify-reload" "idle" "dbus" ];
  restarts = [ "no" "always" "on-success" "on-failure" "on-abnormal" "on-abort" "on-watchdog" ];
  killModes = [ "control-group" "mixed" "process" "none" ];

  # The targets the phone reaches with each build and at boot: a unit wanted by one of these (or by a
  # target of systemd.targets they want) starts then, as on NixOS.
  bootTargets = [
    "multi-user.target" "default.target" "graphical.target" "basic.target" "sysinit.target" "network.target"
    "network-online.target" "network-pre.target" "local-fs.target" "remote-fs.target" "sockets.target"
    "timers.target" "paths.target" "nss-lookup.target" "nss-user-lookup.target" "time-sync.target"
    "time-set.target" "getty.target"
  ];

  unitModule = { name, config, ... }: {
    options = {
      enable = mkOption {
        type = types.bool;
        default = true;
        description = "Whether the unit is there at all (false: as if it were not in the config).";
      };
      description = mkOption {
        type = types.singleLineStr;
        default = "";
        description = "What the unit is, in a few words.";
      };
      documentation = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "Where it is documented, as on NixOS (taken; the phone shows none).";
      };
      script = mkOption {
        type = types.lines;
        default = "";
        description = "Shell commands the unit runs (bash, stopping at the first that fails), as its ExecStart.";
      };
      scriptArgs = mkOption {
        type = types.str;
        default = "";
        example = "%i";
        description = "Arguments script gets, as on NixOS (systemd's specifiers, like %i, are filled in).";
      };
      preStart = mkOption {
        type = types.lines;
        default = "";
        description = "Shell commands run before it starts (each time it does), as an ExecStartPre.";
      };
      postStart = mkOption {
        type = types.lines;
        default = "";
        description = "Shell commands run once it has started, as an ExecStartPost.";
      };
      reload = mkOption {
        type = types.lines;
        default = "";
        description = "Shell commands that reload it, as an ExecReload (taken: on the phone a changed unit starts again).";
      };
      preStop = mkOption {
        type = types.lines;
        default = "";
        description = "Shell commands that stop it, run before its process is ended, as an ExecStop.";
      };
      postStop = mkOption {
        type = types.lines;
        default = "";
        description = "Shell commands run after it stopped, as an ExecStopPost.";
      };
      environment = mkOption {
        type = types.attrsOf (types.nullOr (types.oneOf [ types.str types.path types.package ]));
        default = { };
        example = { HOME = "/var/lib/foo"; };
        description = "Its environment variables (null: left out).";
      };
      path = mkOption {
        type = types.listOf (types.oneOf [ types.package types.str ]);
        default = [ ];
        example = lib.literalExpression "[ pkgs.jq ]";
        description = ''
          Packages whose bin (and sbin) folders are on its PATH, before coreutils, findutils, gnugrep
          and gnused (as on NixOS) and Android's /system/bin.
        '';
      };
      enableDefaultPath = mkOption {
        type = types.bool;
        default = true;
        description = "Whether coreutils, findutils, gnugrep and gnused are on its PATH too, as on NixOS.";
      };
      wantedBy = mkOption {
        type = types.listOf types.str;
        default = [ ];
        example = [ "multi-user.target" ];
        description = ''
          [ "multi-user.target" ] (or another target the phone reaches: default.target, network.target, ...,
          or one of systemd.targets they want): started with each build and at boot. A unit's name
          ("foo.service") makes that unit want it. Without it a unit only starts when another one that
          starts wants or requires it, as on NixOS.
        '';
      };
      requiredBy = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "As wantedBy, and the units named require it.";
      };
      upheldBy = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "As wantedBy (the phone starts it again when it stops only as its Restart says).";
      };
      after = mkOption {
        type = types.listOf types.str;
        default = [ ];
        example = [ "foo.service" ];
        description = "Units it starts after, when they start too.";
      };
      before = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "Units it starts before, when they start too.";
      };
      wants = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "Units started with it.";
      };
      upholds = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "Units started with it, as wants.";
      };
      requires = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "Units started with it, before it; when one of them cannot start, neither does it.";
      };
      bindsTo = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "As requires (the phone does not stop it when they stop).";
      };
      requisite = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "As requires.";
      };
      partOf = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "Units whose stop or restart stops or restarts it, as on NixOS (taken: on the phone a build restarts what changed).";
      };
      conflicts = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "Units that cannot run with it, as on NixOS (taken).";
      };
      onFailure = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "Units started when it fails, as on NixOS (taken: the app tells when a unit fails).";
      };
      onSuccess = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "Units started when it ends well, as on NixOS (taken).";
      };
      propagatesReloadTo = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "As on NixOS (taken: the phone reloads no unit).";
      };
      reloadPropagatedFrom = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "As on NixOS (taken: the phone reloads no unit).";
      };
      aliases = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "Other names of the unit, as on NixOS (taken).";
      };
      restartTriggers = mkOption {
        type = types.listOf types.unspecified;
        default = [ ];
        description = "Values whose change restarts it with the next build, as on NixOS.";
      };
      reloadTriggers = mkOption {
        type = types.listOf types.unspecified;
        default = [ ];
        description = "Values whose change reloads it on NixOS; on the phone they restart it.";
      };
      restartIfChanged = mkOption {
        type = types.bool;
        default = true;
        description = "Whether a build that changes it starts it again, as on NixOS (false: the change waits for its next start).";
      };
      reloadIfChanged = mkOption {
        type = types.bool;
        default = false;
        description = "As on NixOS; the phone reloads no unit, so a changed one starts again.";
      };
      stopIfChanged = mkOption {
        type = types.bool;
        default = true;
        description = "As on NixOS (taken).";
      };
      notSocketActivated = mkOption {
        type = types.bool;
        default = false;
        description = "As on NixOS (taken: the phone has no socket activation).";
      };
      enableStrictShellChecks = mkOption {
        type = types.bool;
        default = false;
        description = "Whether NixOS would run shellcheck on its scripts (taken: the phone does not).";
      };
      startLimitBurst = mkOption {
        type = types.nullOr types.int;
        default = null;
        description = "As on NixOS (taken: the phone tries again as Restart and RestartSec say).";
      };
      startLimitIntervalSec = mkOption {
        type = types.nullOr types.int;
        default = null;
        description = "As on NixOS (taken).";
      };
      overrideStrategy = mkOption {
        type = types.enum [ "asDropinIfExists" "asDropin" ];
        default = "asDropinIfExists";
        description = "As on NixOS (taken: the phone has no unit files of packages to drop into).";
      };
      notifications = mkOption {
        type = types.bool;
        default = true;
        description = ''
          Seraph's own (NixOS has no such thing): whether the app tells, in a notification, when it
          stops by itself, fails, or runs again after a failure (the Logs tab has it either way).
          Off for what is about the phone's security (sshd), so its state shows on no screen.
        '';
      };
      serviceConfig = mkOption {
        type = types.attrsOf unitValue;
        default = { };
        example = { ExecStart = "\${pkgs.foo}/bin/foo --flag"; Restart = "on-failure"; StateDirectory = "foo"; };
        description = "The [Service] section of its unit (the keys the phone takes are listed at the top of nix/units.nix).";
      };
      unitConfig = mkOption {
        type = types.attrsOf unitValue;
        default = { };
        example = { RequiresMountsFor = [ "/storage/1234-5678/Music" ]; };
        description = ''
          The [Unit] section's other keys: RequiresMountsFor, the phone's storage it needs (it starts once that is
          mounted: an SD card, or the storage Android mounts once the phone is first unlocked), and the conditions on
          paths (ConditionPathExists, ...); see nix/units.nix for the rest.
        '';
      };
      startAt = mkOption {
        type = types.either types.str (types.listOf types.str);
        default = [ ];
        example = "Mon..Fri *-*-* 07:30";
        description = "When to start it, as a timer of its name with this OnCalendar (systemd.time(7)), as on NixOS.";
      };
      confinement = {
        enable = mkOption {
          type = types.bool;
          default = false;
          description = "NixOS's confinement of a unit in a root of its own store paths: not on the phone (its view is its own).";
        };
        mode = mkOption {
          type = types.enum [ "full-apivfs" "chroot-only" ];
          default = "full-apivfs";
          description = "As on NixOS (with confinement.enable).";
        };
        packages = mkOption {
          type = types.listOf (types.either types.str types.package);
          default = [ ];
          description = "As on NixOS (with confinement.enable).";
        };
        binSh = mkOption {
          type = types.nullOr types.path;
          default = null;
          description = "As on NixOS (with confinement.enable).";
        };
        fullUnit = mkOption {
          type = types.bool;
          default = false;
          description = "As on NixOS (with confinement.enable).";
        };
      };
    };
  };

  # A target: the units it wants, and the targets it is wanted by.
  targetModule = { ... }: {
    options = lib.genAttrs [ "wantedBy" "requiredBy" "upheldBy" "wants" "requires" "upholds" "after" "before"
      "bindsTo" "partOf" "conflicts" "requisite" "onFailure" "onSuccess" "documentation" "aliases" ] (name: mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "As on NixOS.";
      }) // {
      enable = mkOption {
        type = types.bool;
        default = true;
        description = "Whether the target is there at all.";
      };
      description = mkOption {
        type = types.singleLineStr;
        default = "";
        description = "What the target is.";
      };
      unitConfig = mkOption {
        type = types.attrsOf unitValue;
        default = { };
        description = "Its [Unit] section's other keys (taken).";
      };
    };
  };

  # The lists of a socket's or a timer's [Unit] and [Install] sections, as NixOS has them.
  listOptions = names: lib.genAttrs names (name: mkOption {
    type = types.listOf types.str;
    default = [ ];
    description = "As on NixOS.";
  });

  # A socket: the phone opens it and hands it to its service (see the top).
  socketModule = { ... }: {
    options = listOptions [ "wantedBy" "requiredBy" "after" "before" "wants" "requires" "bindsTo" "partOf"
      "conflicts" "documentation" ] // {
      enable = mkOption {
        type = types.bool;
        default = true;
        description = "Whether the socket is there at all.";
      };
      description = mkOption {
        type = types.singleLineStr;
        default = "";
        description = "What the socket is.";
      };
      listenStreams = mkOption {
        type = types.listOf types.str;
        default = [ ];
        example = [ "8080" "127.0.0.1:8080" "/run/foo.sock" ];
        description = "Its stream sockets (TCP: a port, or an address and port; or a unix socket's path), as on NixOS.";
      };
      listenDatagrams = mkOption {
        type = types.listOf types.str;
        default = [ ];
        example = [ "5353" ];
        description = "Its datagram sockets (UDP: a port, or an address and port), as on NixOS.";
      };
      socketConfig = mkOption {
        type = types.attrsOf unitValue;
        default = { };
        example = { ListenStream = "/run/foo.sock"; };
        description = "The [Socket] section of its unit: ListenStream, ListenDatagram, Service; Accept = false only.";
      };
      unitConfig = mkOption {
        type = types.attrsOf unitValue;
        default = { };
        description = "Its [Unit] section's other keys (taken).";
      };
    };
  };

  # A timer: it starts its unit at the times it says (see the top).
  timerModule = { ... }: {
    options = listOptions [ "wantedBy" "requiredBy" "after" "before" "wants" "requires" "bindsTo" "partOf"
      "conflicts" "documentation" ] // {
      enable = mkOption {
        type = types.bool;
        default = true;
        description = "Whether the timer is there at all.";
      };
      description = mkOption {
        type = types.singleLineStr;
        default = "";
        description = "What the timer is.";
      };
      timerConfig = mkOption {
        type = types.attrsOf unitValue;
        default = { };
        example = { OnCalendar = "daily"; Persistent = true; };
        description = ''
          The [Timer] section of its unit: OnCalendar, OnBootSec, OnStartupSec, OnActiveSec, OnUnitActiveSec,
          OnUnitInactiveSec, Persistent, RandomizedDelaySec and Unit (AccuracySec, WakeSystem and the like are taken).
        '';
      };
      unitConfig = mkOption {
        type = types.attrsOf unitValue;
        default = { };
        description = "Its [Unit] section's other keys (taken).";
      };
    };
  };
  timerKeys = {
    OnCalendar = "onCalendar"; OnBootSec = "onBoot"; OnStartupSec = "onStartup"; OnActiveSec = "onActive";
    OnUnitActiveSec = "onUnitActive"; OnUnitInactiveSec = "onUnitInactive"; RandomizedDelaySec = "randomizedDelay";
  };
  timerTaken = [ "Persistent" "Unit" "AccuracySec" "FixedRandomDelay" "WakeSystem" "RemainAfterElapse" "OnClockChange"
    "OnTimezoneChange" "RandomizedOffsetSec" "DeferReactivation" ];
  socketSupported = [ "ListenStream" "ListenDatagram" "Accept" "Service" ];
  socketTaken = [ "Backlog" "BindIPv6Only" "FreeBind" "ReusePort" "NoDelay" "KeepAlive" "KeepAliveTimeSec"
    "KeepAliveIntervalSec" "KeepAliveProbes" "ReceiveBuffer" "SendBuffer" "RemoveOnStop" "SocketMode" "DirectoryMode"
    "SocketUser" "SocketGroup" "PassCredentials" "PassSecurity" "PassPacketInfo" "MaxConnections" "MaxConnectionsPerSource"
    "Timestamping" "Transparent" "Broadcast" "IPTOS" "IPTTL" "Mark" "PipeSize" "Priority" "Symlinks" "FileDescriptorName"
    "TriggerLimitIntervalSec" "TriggerLimitBurst" "PollLimitIntervalSec" "PollLimitBurst" "DeferTrigger" ];

  # A unit's name as the others give it: foo.service is foo.
  nameOf = lib.removeSuffix ".service";
  isTemplate = name: lib.hasSuffix "@" name;
  # Template units (foo@) start only as instances (foo@bar), which socket activation and
  # systemctl make on NixOS: on the phone an instance is a unit of its own.
  units = lib.filterAttrs (name: unit: unit.enable && !isTemplate name) config.systemd.services;

  listOf = value: if value == null then [ ] else if builtins.isList value then value else [ value ];
  strings = value: map toString (listOf value);
  # An empty value clears what the list had so far, as systemd takes ExecStart = [ "" "cmd" ].
  reset = lib.foldl' (list: value: if value == "" then [ ] else list ++ [ value ]) [ ];
  # StateDirectory = "a b" names two, as systemd takes it.
  words = value: lib.filter (word: word != "") (lib.concatMap (lib.splitString " ") (strings value));
  boolean = value: if builtins.isBool value then value else lib.elem (lib.toLower (toString value)) [ "1" "yes" "true" "on" ];

  defaultPath = with pkgs; [ coreutils findutils gnugrep gnused ];
  pathOf = unit: let all = unit.path ++ lib.optionals unit.enableDefaultPath defaultPath; in
    "${lib.makeBinPath all}:${lib.makeSearchPathOutput "bin" "sbin" all}:/system/bin";

  commands = value: map (command: { inherit command; }) (reset (strings value));
  # Exit codes as numbers ("3 4", [ 3 4 ], "SIGTERM"); a word that is neither is left to problems below.
  exitCodes = value: map (word: signals.${word} or (lib.toInt word))
    (lib.filter (word: signals ? ${word} || builtins.match "[0-9]+" word != null) (words value));
  badExitCodes = value: lib.filter (word: !(signals ? ${word}) && builtins.match "[0-9]+" word == null) (words value);
  script = text: args: lib.optional (text != "") ({ script = text; } // lib.optionalAttrs (args != "") { inherit args; });

  # serviceConfig.Environment, "NAME=value" each (a value may also hold several, quoted), as systemd takes it.
  environmentLines = lines: lib.listToAttrs (lib.concatMap (line:
    let parts = builtins.match "([^=]+)=(.*)" line;
    in lib.optional (parts != null) (lib.nameValuePair (builtins.elemAt parts 0) (builtins.elemAt parts 1))
  ) (lib.concatMap splitAssignments (strings lines)));
  # "A=1 B='two words'" is two assignments, as systemd splits one Environment line.
  splitAssignments = line:
    let quoted = builtins.match ''([A-Za-z_][A-Za-z0-9_]*)="([^"]*)"( .*)?'' line;
        single = builtins.match "([A-Za-z_][A-Za-z0-9_]*)='([^']*)'( .*)?" line;
        plain = builtins.match "([A-Za-z_][A-Za-z0-9_]*=[^ \"']*)( .*)?" line;
        rest = r: if r == null then [ ] else splitAssignments (lib.removePrefix " " r);
    in
    if quoted != null then [ "${builtins.elemAt quoted 0}=${builtins.elemAt quoted 1}" ] ++ rest (builtins.elemAt quoted 2)
    else if single != null then [ "${builtins.elemAt single 0}=${builtins.elemAt single 1}" ] ++ rest (builtins.elemAt single 2)
    else if plain != null && builtins.elemAt plain 1 != null then [ (builtins.elemAt plain 0) ] ++ rest (builtins.elemAt plain 1)
    else [ line ];

  # The user a unit runs as: its User (DynamicUser: the unit's name, when it has none), else root.
  userOf = name: unit:
    let sc = unit.serviceConfig; user = if sc ? User then toString sc.User else null; in
    if user != null && user != "" then user
    else if boolean (sc.DynamicUser or false) then name
    else null;
  isRoot = user: user == null || user == "root" || user == "0";

  # targets: what each target of systemd.targets wants, for the units the phone starts.
  targets = lib.filterAttrs (_: target: target.enable) config.systemd.targets;
  # The targets reached: the boot ones, and those of systemd.targets they want, and so on.
  reached = let
    grow = known:
      let more = lib.filter (target: !lib.elem target known
        && lib.any (by: lib.elem by known) (targets.${lib.removeSuffix ".target" target}.wantedBy or [ ]
          ++ targets.${lib.removeSuffix ".target" target}.requiredBy or [ ]))
        (map (name: "${name}.target") (lib.attrNames targets));
      in if more == [ ] then known else grow (known ++ more);
    in grow bootTargets;
  # The units the reached targets want themselves (systemd.targets.<name>.wants).
  wantedByTargets = lib.unique (map nameOf (lib.concatLists (lib.mapAttrsToList (name: target:
    lib.optionals (lib.elem "${name}.target" reached) (target.wants ++ target.requires)
  ) targets)));
  # A unit wanted (or required) by another unit (wantedBy = [ "foo.service" ]): that one wants it.
  wantedByUnits = name: lib.attrNames (lib.filterAttrs (_: unit:
    lib.any (by: by == "${name}.service" || by == name) (unit.wantedBy ++ unit.requiredBy ++ unit.upheldBy)) units);
  requiredByUnits = name: lib.attrNames (lib.filterAttrs (_: unit:
    lib.any (by: by == "${name}.service" || by == name) unit.requiredBy) units);

  # Sockets (systemd.sockets): the phone opens a wanted one itself and hands it to its service as systemd hands one
  # over (LISTEN_FDS, through systemfd); the service starts with the build and the phone, not at its first
  # connection, as on NixOS.
  sockets = lib.filterAttrs (_: socket: socket.enable) config.systemd.sockets;
  socketWanted = socket: lib.any (target: lib.elem target reached) (socket.wantedBy ++ socket.requiredBy);
  serviceOfSocket = name: socket: nameOf (toString (socket.socketConfig.Service or "${name}.service"));
  # A Listen* value as systemfd takes it: "8080" (every address), "127.0.0.1:8080", "[::1]:8080", "/run/foo.sock";
  # null for what it cannot open (an abstract socket, a unix datagram socket).
  listenSpec = kind: address:
    let
      a = toString address;
      net = if kind == "stream" then "tcp" else "udp";
    in
    if lib.hasPrefix "/" a then (if kind == "stream" then "unix::${a}" else null)
    else if lib.hasPrefix "@" a then null
    else if builtins.match "[0-9]+" a != null then "${net}::[::]:${a}"
    else "${net}::${a}";
  socketSpecs = socket:
    map (listenSpec "stream") (reset (strings socket.listenStreams) ++ reset (strings (socket.socketConfig.ListenStream or [ ])))
    ++ map (listenSpec "datagram") (reset (strings socket.listenDatagrams) ++ reset (strings (socket.socketConfig.ListenDatagram or [ ])));
  wantedSockets = lib.filterAttrs (_: socketWanted) sockets;
  socketsOf = name: lib.concatLists (lib.mapAttrsToList (socketName: socket:
    lib.optionals (serviceOfSocket socketName socket == name) (lib.filter (spec: spec != null) (socketSpecs socket))) wantedSockets);
  socketServices = lib.unique (lib.mapAttrsToList serviceOfSocket wantedSockets);

  # Timers (systemd.timers, with those a unit's startAt makes, below): the engine starts their unit at their
  # times (Timers.kt).
  timers = lib.filterAttrs (_: timer: timer.enable) config.systemd.timers;
  toTimer = name: timer:
    let tc = timer.timerConfig; in
    {
      unit = nameOf (toString (tc.Unit or "${name}.service"));
      inherit (timer) description;
      wanted = lib.any (target: lib.elem target reached) (timer.wantedBy ++ timer.requiredBy);
      onCalendar = reset (strings (tc.OnCalendar or [ ]));
    }
    // lib.optionalAttrs (boolean (tc.Persistent or false)) { persistent = true; }
    // lib.mapAttrs' (key: field: lib.nameValuePair field (toString (lib.last (listOf tc.${key}))))
      (lib.filterAttrs (key: _: key != "OnCalendar" && tc ? ${key}) timerKeys);
  timerProfile = lib.mapAttrs toTimer timers;

  # A unit's user in one of Android's groups of the shared storage reaches it, in its view (see the top).
  storageGroups = [ "media_rw" "sdcard_rw" "external_storage" ];
  storageOf = user: unit: !isRoot user && lib.any (group: lib.elem group storageGroups)
    (words (unit.serviceConfig.SupplementaryGroups or [ ])
      ++ lib.optionals (config.users.users ? ${user}) (config.users.users.${user}.extraGroups ++ [ config.users.users.${user}.group ]));

  # LoadCredential = "id:path" (an id alone: the file of that name in /etc/credstore or /run/credstore, as
  # systemd looks for it); SetCredential = "id:text".
  credential = kind: entry:
    let parts = builtins.match "([^:]+):(.*)" entry; in
    if parts == null then { id = entry; } // lib.optionalAttrs (kind == "text") { text = ""; }
    else { id = builtins.elemAt parts 0; ${kind} = builtins.elemAt parts 1; };
  # BindPaths = "[-]from[:to[:options]]": only where to differs from from, as the view has every path already.
  bind = readOnly: entry:
    let
      optional = lib.hasPrefix "-" entry;
      parts = lib.splitString ":" (lib.removePrefix "-" entry);
      from = builtins.elemAt parts 0;
      to = if builtins.length parts > 1 then builtins.elemAt parts 1 else from;
      ro = readOnly || (builtins.length parts > 2 && lib.elem "ro" (lib.splitString "," (builtins.elemAt parts 2)));
    in { inherit from to optional; readOnly = ro; };
  binds = sc: lib.filter (b: b.from != b.to)
    (map (bind false) (strings (sc.BindPaths or [ ])) ++ map (bind true) (strings (sc.BindReadOnlyPaths or [ ])));
  # Conditions and assertions on unitConfig: [ { key = "ConditionPathExists"; value = "!/var/lib/x"; } ... ].
  checks = unit: lib.concatLists (lib.mapAttrsToList (key: value:
    map (word: { inherit key; value = word; }) (strings value)
  ) (lib.filterAttrs (key: _: pathChecks ? ${key} || otherChecks ? ${key}) unit.unitConfig));

  # A unit as the engine runs it: its steps (each a command line, as systemd takes it, or a script),
  # with everything resolved to strings. What a unit does not set is left out, so a unit is the same
  # as before for what it does not use.
  toProfile = name: unit:
    let
      sc = unit.serviceConfig;
      type = sc.Type or "simple";
      user = userOf name unit;
      dynamic = boolean (sc.DynamicUser or false);
      group = if sc ? Group && toString sc.Group != "" then toString sc.Group else if dynamic then user else null;
      rootSteps = boolean (sc.PermissionsStartOnly or false);
      timeout = key: if sc ? ${key} then toString sc.${key} else if sc ? TimeoutSec then toString sc.TimeoutSec else null;
      envFiles = map (path: { path = lib.removePrefix "-" path; optional = lib.hasPrefix "-" path; }) (reset (strings (sc.EnvironmentFile or [ ])));
      creds = map (credential "path") (strings (sc.LoadCredential or [ ])) ++ map (credential "text") (strings (sc.SetCredential or [ ]));
      dirs = kind: words (sc."${kind}Directory" or [ ]);
      modes = lib.filterAttrs (_: mode: mode != null) (lib.genAttrs [ "State" "Runtime" "Cache" "Logs" "Configuration" ]
        (kind: if sc ? "${kind}DirectoryMode" then toString sc."${kind}DirectoryMode" else null));
      limitsSet = lib.filter (key: sc ? "Limit${key}") limits;
      unitChecks = checks unit;
      extraWants = wantedByUnits name;
      unitSockets = socketsOf name;
    in
    {
      inherit (unit) description;
      type = if lib.elem type [ "oneshot" "forking" ] then type else "simple";
      restart = sc.Restart or "no";
      restartSec = toString (sc.RestartSec or "0.1");
      workingDirectory = if sc ? WorkingDirectory then toString sc.WorkingDirectory else null;
      stateDirectories = dirs "State";
      runtimeDirectories = dirs "Runtime";
      environment = environmentLines (sc.Environment or [ ])
        // lib.mapAttrs (_: toString) (lib.filterAttrs (_: value: value != null) (config.systemd.globalEnvironment // unit.environment))
        // { PATH = pathOf unit; };
      after = map nameOf (unit.after ++ unit.requires ++ unit.bindsTo ++ unit.requisite) ++ requiredByUnits name;
      before = map nameOf unit.before;
      wants = map nameOf (unit.wants ++ unit.upholds ++ unit.requires ++ unit.bindsTo ++ unit.requisite) ++ extraWants;
      requires = map nameOf (unit.requires ++ unit.bindsTo ++ unit.requisite) ++ requiredByUnits name;
      wanted = lib.any (target: lib.elem target reached) (unit.wantedBy ++ unit.requiredBy ++ unit.upheldBy)
        || lib.elem name wantedByTargets || lib.elem name socketServices;
      # Below the apps, unless the unit says (systemd's Nice: -20, first, to 19, last).
      nice = sc.Nice or 10;
      inherit (unit) notifications;
      shell = "${pkgs.bash}/bin/bash";
      pre = commands (sc.ExecStartPre or [ ]) ++ script unit.preStart "";
      start = if unit.script != "" then script unit.script unit.scriptArgs else commands (sc.ExecStart or [ ]);
      post = commands (sc.ExecStartPost or [ ]) ++ script unit.postStart "";
      stop = commands (sc.ExecStop or [ ]) ++ script unit.preStop "";
      # A change of these restarts it; the engine compares the units as they are.
      triggers = builtins.hashString "sha256" (builtins.toJSON (map toString (unit.restartTriggers ++ unit.reloadTriggers)));
    }
    # Only when set, so a unit without them is the same as before.
    // lib.optionalAttrs (unit.unitConfig ? RequiresMountsFor) { mounts = words unit.unitConfig.RequiresMountsFor; }
    // lib.optionalAttrs (sc ? SuccessExitStatus) { successExitStatus = exitCodes sc.SuccessExitStatus; }
    // lib.optionalAttrs (sc ? RestartForceExitStatus) { restartForceExitStatus = exitCodes sc.RestartForceExitStatus; }
    // lib.optionalAttrs (sc ? RestartPreventExitStatus) { restartPreventExitStatus = exitCodes sc.RestartPreventExitStatus; }
    // lib.optionalAttrs (sc ? ExecCondition) { condition = commands sc.ExecCondition; }
    // lib.optionalAttrs (sc ? ExecStopPost || unit.postStop != "") { stopPost = commands (sc.ExecStopPost or [ ]) ++ script unit.postStop ""; }
    // lib.optionalAttrs (boolean (sc.RemainAfterExit or false)) { remainAfterExit = true; }
    // lib.optionalAttrs (sc ? PIDFile) { pidFile = toString sc.PIDFile; }
    // lib.optionalAttrs (sc ? KillMode) { killMode = sc.KillMode; }
    // lib.optionalAttrs (sc ? KillSignal) { killSignal = toString sc.KillSignal; }
    // lib.optionalAttrs (sc ? SendSIGKILL) { sendSigkill = boolean sc.SendSIGKILL; }
    // lib.optionalAttrs (timeout "TimeoutStopSec" != null) { timeoutStop = timeout "TimeoutStopSec"; }
    // lib.optionalAttrs (timeout "TimeoutStartSec" != null) { timeoutStart = timeout "TimeoutStartSec"; }
    // lib.optionalAttrs (envFiles != [ ]) { environmentFiles = envFiles; }
    // lib.optionalAttrs (sc ? UnsetEnvironment) { unsetEnvironment = words sc.UnsetEnvironment; }
    // lib.optionalAttrs (!isRoot user) ({ inherit user; groups = words (sc.SupplementaryGroups or [ ]);
         setpriv = "${pkgs.util-linuxMinimal.bin}/bin/setpriv"; }
       // lib.optionalAttrs (group != null) { inherit group; }
       // lib.optionalAttrs dynamic { inherit dynamic; }
       // lib.optionalAttrs rootSteps { rootSteps = true; }
       // lib.optionalAttrs (sc ? AmbientCapabilities) { ambientCapabilities = words sc.AmbientCapabilities; }
       // lib.optionalAttrs (boolean (sc.NoNewPrivileges or false)) { noNewPrivileges = true; })
    // lib.optionalAttrs (sc ? UMask) { umask = toString sc.UMask; }
    // lib.optionalAttrs (sc ? OOMScoreAdjust) { oomScoreAdjust = sc.OOMScoreAdjust; }
    // lib.optionalAttrs (limitsSet != [ ]) { limits = lib.genAttrs limitsSet (key: toString sc."Limit${key}"); }
    // lib.optionalAttrs (dirs "Cache" != [ ]) { cacheDirectories = dirs "Cache"; }
    // lib.optionalAttrs (dirs "Logs" != [ ]) { logsDirectories = dirs "Logs"; }
    // lib.optionalAttrs (dirs "Configuration" != [ ]) { configurationDirectories = dirs "Configuration"; }
    // lib.optionalAttrs (modes != { }) { directoryModes = modes; }
    // lib.optionalAttrs (sc ? RuntimeDirectoryPreserve) { runtimeDirectoryPreserve = toString sc.RuntimeDirectoryPreserve; }
    // lib.optionalAttrs (binds sc != [ ]) { binds = binds sc; }
    // lib.optionalAttrs (creds != [ ]) { credentials = creds; }
    // lib.optionalAttrs (unitChecks != [ ]) { checks = unitChecks; }
    // lib.optionalAttrs (unitSockets != [ ]) { sockets = unitSockets; systemfd = "${pkgs.systemfd}/bin/systemfd"; }
    // lib.optionalAttrs (toString (sc.StandardInput or "") == "socket") { stdinSocket = true; }
    // lib.optionalAttrs (storageOf user unit) { storage = true; }
    // lib.optionalAttrs (!unit.restartIfChanged && !unit.reloadIfChanged) { restartIfChanged = false; };

  profile = lib.mapAttrs toProfile units;

  # The users the units run as, for the view's /etc/passwd and /etc/group (nix/users.nix): one users.users
  # has not (DynamicUser makes them on NixOS) gets a line of its own, with its group; and each is in
  # the groups its units name (SupplementaryGroups), and in inet, which some phones' kernels want for
  # the network.
  unitAccounts = lib.concatLists (lib.mapAttrsToList (name: unit:
    let user = userOf name unit; spec = profile.${name}; in
    lib.optional (!isRoot user) {
      name = user;
      group = spec.group or user;
      # The shared storage's real folders are media_rw's.
      groups = spec.groups ++ lib.optional (spec.storage or false) "media_rw";
      declared = config.users.users ? ${user};
    }
  ) units);

  # The store paths the units use, as Nix finds a build's references: by their names in the text.
  storePathsIn = text: map builtins.head (lib.filter builtins.isList
    (builtins.split "(/nix/store/[a-z0-9]{32}-[A-Za-z0-9+._?=-]+)" (builtins.unsafeDiscardStringContext text)));

  problems = lib.concatLists (lib.mapAttrsToList (name: unit:
    let
      sc = unit.serviceConfig;
      type = sc.Type or "simple";
      starts = if unit.script != "" then 1 else builtins.length (reset (strings (sc.ExecStart or [ ])));
      other = lib.filter (key: !lib.elem key supported && !lib.elem key taken) (lib.attrNames sc);
      encrypted = lib.filter (key: lib.elem key [ "LoadCredentialEncrypted" "SetCredentialEncrypted" ]) (lib.attrNames sc);
      otherUnit = lib.filter (key: !lib.elem key unitSupported && !isTakenUnitKey key) (lib.attrNames unit.unitConfig);
      mounts = words (unit.unitConfig.RequiresMountsFor or [ ]);
      badCodes = badExitCodes (sc.SuccessExitStatus or [ ]) ++ badExitCodes (sc.RestartForceExitStatus or [ ])
        ++ badExitCodes (sc.RestartPreventExitStatus or [ ]);
      relativeBinds = lib.filter (b: !lib.hasPrefix "/" b.from || !lib.hasPrefix "/" b.to) (binds sc);
      unit' = "systemd.services.${name}";
      them = list: "${lib.concatStringsSep ", " list} ${if builtins.length list == 1 then "is" else "are"}";
    in
    lib.optional (builtins.match "[A-Za-z0-9_.:@\\\\-]+" name == null) "${unit'}: a unit's name is letters, digits and _ . : @ \\ -."
    ++ lib.optional (encrypted != [ ]) "${unit'}.serviceConfig: ${them encrypted} not on the phone (it has no TPM nor systemd's credential key): LoadCredential with a file is."
    ++ lib.optional (other != [ ]) "${unit'}.serviceConfig: ${them (lib.subtractLists encrypted other)} not on the phone (nix/units.nix lists the keys it takes)."
    ++ lib.optional (unit.script != "" && reset (strings (sc.ExecStart or [ ])) != [ ]) "${unit'}: give script or serviceConfig.ExecStart, not both."
    ++ lib.optional (!lib.elem type types') "${unit'}.serviceConfig.Type: \"${toString type}\" is not on the phone (${lib.concatStringsSep ", " types'})."
    ++ lib.optional (type != "oneshot" && starts != 1) "${unit'}: a ${toString type} unit has one ExecStart (or script)${lib.optionalString (starts > 1) "; several are a oneshot's"}."
    ++ lib.optional (type == "oneshot" && starts == 0 && !boolean (sc.RemainAfterExit or false)) "${unit'}: give it something to run (serviceConfig.ExecStart or script)."
    ++ lib.optional (!lib.elem (sc.Restart or "no") restarts) "${unit'}.serviceConfig.Restart: \"${toString (sc.Restart or "")}\" is not on the phone (${lib.concatStringsSep ", " restarts})."
    ++ lib.optional (sc ? KillMode && !lib.elem sc.KillMode killModes) "${unit'}.serviceConfig.KillMode: \"${toString sc.KillMode}\" is not systemd's (${lib.concatStringsSep ", " killModes})."
    ++ lib.optional (sc ? Nice && !(builtins.isInt sc.Nice && sc.Nice >= -20 && sc.Nice <= 19)) "${unit'}.serviceConfig.Nice: a number from -20 to 19."
    ++ lib.optional (otherUnit != [ ]) "${unit'}.unitConfig: ${them otherUnit} not on the phone yet (nix/units.nix lists the keys it takes)."
    ++ lib.optional (lib.any (path: !lib.hasPrefix "/" path) mounts) "${unit'}.unitConfig.RequiresMountsFor: full paths, like /storage/1234-5678/Music."
    ++ lib.optional (badCodes != [ ]) "${unit'}.serviceConfig: ${lib.concatStringsSep ", " badCodes}: an exit status is a number or a signal (${lib.concatStringsSep ", " (lib.attrNames signals)})."
    ++ lib.optional (relativeBinds != [ ]) "${unit'}.serviceConfig.BindPaths: full paths, like /var/lib/foo:/srv/foo."
    ++ lib.optional unit.confinement.enable "${unit'}.confinement.enable: not on the phone (each unit runs in a view of its own already)."
    ++ lib.optional (toString (sc.StandardInput or "") == "socket" && socketsOf name == [ ]) "${unit'}.serviceConfig.StandardInput: \"socket\" needs a socket of systemd.sockets for it."
  ) units)
  ++ lib.concatLists (lib.mapAttrsToList (name: timer:
    let
      tc = timer.timerConfig;
      unit = nameOf (toString (tc.Unit or "${name}.service"));
      other = lib.filter (key: !(timerKeys ? ${key}) && !lib.elem key timerTaken) (lib.attrNames tc);
      timer' = "systemd.timers.${name}";
    in
    # (One there but turned off leaves the timer off.)
    lib.optional (!(config.systemd.services ? ${unit})) "${timer'}: its unit ${unit} is not in systemd.services."
    ++ lib.optional (lib.attrNames (builtins.intersectAttrs timerKeys tc) == [ ] || (lib.attrNames (builtins.intersectAttrs timerKeys tc) == [ "OnCalendar" ] && reset (strings tc.OnCalendar) == [ ]))
      "${timer'}.timerConfig: give it a time (OnCalendar, OnBootSec, OnUnitActiveSec, ...)."
    ++ lib.optional (other != [ ]) "${timer'}.timerConfig: ${lib.concatStringsSep ", " other} not on the phone (nix/units.nix lists the keys it takes)."
  ) timers)
  ++ lib.concatLists (lib.mapAttrsToList (name: socket:
    let
      sc = socket.socketConfig;
      service = serviceOfSocket name socket;
      other = lib.filter (key: !lib.elem key socketSupported && !lib.elem key socketTaken) (lib.attrNames sc);
      unopened = lib.filter (spec: spec == null) (socketSpecs socket);
      socket' = "systemd.sockets.${name}";
    in
    lib.optional (!(config.systemd.services ? ${service})) "${socket'}: its service ${service} is not in systemd.services."
    ++ lib.optional (boolean (sc.Accept or false)) "${socket'}.socketConfig.Accept: a service started for each connection is not on the phone yet (Accept = false is)."
    ++ lib.optional (other != [ ]) "${socket'}.socketConfig: ${lib.concatStringsSep ", " other} not on the phone (nix/units.nix lists the keys it takes)."
    ++ lib.optional (unopened != [ ]) "${socket'}: an abstract socket (@name) or a unix datagram socket is not on the phone."
    ++ lib.optional (socketSpecs socket == [ ]) "${socket'}: give it something to listen on (listenStreams, listenDatagrams)."
  ) (lib.filterAttrs (_: socketWanted) sockets));
in
{
  options.systemd = {
    services = mkOption {
      type = types.attrsOf (types.submodule unitModule);
      default = { };
      example = lib.literalExpression ''
        {
          hello = {
            wantedBy = [ "multi-user.target" ];
            serviceConfig = {
              ExecStart = "''${pkgs.python3}/bin/python3 -m http.server 8000";
              WorkingDirectory = "/storage/emulated/0/Download";
              Restart = "on-failure";
            };
          };
        }
      '';
      description = ''
        Services, as NixOS's systemd.services: programs the phone keeps running, from Nix packages
        (see nix/units.nix for what a unit can say there).
      '';
    };

    targets = mkOption {
      type = types.attrsOf (types.submodule targetModule);
      default = { };
      example = lib.literalExpression ''{ foo = { wantedBy = [ "multi-user.target" ]; wants = [ "foo-a.service" "foo-b.service" ]; }; }'';
      description = ''
        Targets, as NixOS's systemd.targets: a target wanted by multi-user.target (or another the phone
        reaches) starts the units that are wantedBy it, and those it wants.
      '';
    };

    sockets = mkOption {
      type = types.attrsOf (types.submodule socketModule);
      default = { };
      example = lib.literalExpression ''{ foo = { wantedBy = [ "sockets.target" ]; listenStreams = [ "127.0.0.1:8080" ]; }; }'';
      description = ''
        Sockets, as NixOS's systemd.sockets: the phone opens a wanted one and hands it to its service (foo.socket's
        is foo.service, or socketConfig.Service), which starts with the build and at boot.
      '';
    };

    timers = mkOption {
      type = types.attrsOf (types.submodule timerModule);
      default = { };
      example = lib.literalExpression ''{ foo = { wantedBy = [ "timers.target" ]; timerConfig = { OnCalendar = "daily"; Persistent = true; }; }; }'';
      description = ''
        Timers, as NixOS's systemd.timers: a wanted one starts its unit (foo.timer's is foo.service, or
        timerConfig.Unit) at its times; a unit's startAt makes one, as on NixOS.
      '';
    };

    globalEnvironment = mkOption {
      type = types.attrsOf (types.nullOr (types.oneOf [ types.str types.path types.package ]));
      default = { };
      example = { TZ = "CET"; };
      description = "Environment variables every unit gets, as on NixOS.";
    };
  };

  config = {
    # A unit's startAt is a timer of its name, as NixOS makes it (a module may add to its timerConfig).
    systemd.timers = lib.mapAttrs (_: unit: { wantedBy = [ "timers.target" ]; timerConfig.OnCalendar = unit.startAt; })
      (lib.filterAttrs (_: unit: unit.startAt != [ ] && unit.startAt != "") units);
    system.build.units = profile;
    system.build.unitAccounts = unitAccounts;
    system.build.timers = timerProfile;
    system.build.unitStorePaths = lib.unique (storePathsIn (builtins.toJSON profile));
    assertions = map (message: { assertion = false; inherit message; }) problems;
  };
}
