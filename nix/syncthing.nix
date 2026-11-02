# Syncthing, as NixOS's services.syncthing (see nix/services.nix for how services are made): two
# units, as on NixOS. syncthing runs it, as root, from its Nix package; syncthing-init sends the
# config's settings to it through its REST API, as NixOS's does (curl and jq): each device and
# folder of the config added or replaced, the others removed (overrideDevices, overrideFolders),
# each folder's ignore patterns, and its other settings (options, gui, ...), so what its GUI shows is
# what the config says. cert and key give the phone a fixed identity (its device ID stays across a
# reinstall): android.secrets is the place for them.
#
# Its files are in /var/lib/syncthing, as on NixOS (in the services' view; on the phone itself
# /data/adb/seraph/var/lib/syncthing). A folder's path is a full path on the phone, like
# /storage/emulated/0/Documents, or an SD card's: the files Syncthing writes there are the phone's
# own, as any app's would be. Its GUI and API answer on a socket only root can reach, by default.
{ config, lib, pkgs, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.services.syncthing;

  # JSON, as NixOS's (pkgs.formats.json) is: what a freeform setting can be.
  json = types.attrsOf types.anything;
  anyAutoAccept = lib.any (device: device.autoAcceptFolders) (lib.attrValues cfg.settings.devices);
  home = "/var/lib/syncthing";
  # The secret files on the phone: put there by the build, when they are files from the config's folder.
  secretFiles = import ./secret-files.nix { inherit lib; };
  secretOptions = { cert = cfg.cert; key = cfg.key; guiPasswordFile = cfg.guiPasswordFile; };
  onPhone = lib.mapAttrs (option: value: secretFiles.path config "services.syncthing.${option}" value) secretOptions;

  deviceModule = { name, ... }: {
    freeformType = json;
    options = {
      name = mkOption {
        type = types.str;
        default = name;
        description = "The name of the device.";
      };
      id = mkOption {
        type = types.str;
        description = "The device ID. See <https://docs.syncthing.net/dev/device-ids.html>.";
      };
      autoAcceptFolders = mkOption {
        type = types.bool;
        default = false;
        description = "Automatically create or share folders that this device advertises at the default path.";
      };
    };
  };

  folderModule = { name, config, ... }: {
    freeformType = json;
    options = {
      enable = mkOption {
        type = types.bool;
        default = true;
        description = "Whether to share this folder (false: as if it were not in the config).";
      };
      path = mkOption {
        type = types.str;
        default = name;
        example = "/storage/emulated/0/Documents";
        description = "The folder's full path on the phone, like /storage/emulated/0/Documents.";
      };
      id = mkOption {
        type = types.str;
        default = name;
        description = "The folder's ID, the same on every device that shares it.";
      };
      label = mkOption {
        type = types.str;
        default = name;
        description = "The folder's name in the GUI.";
      };
      type = mkOption {
        type = types.enum [ "sendreceive" "sendonly" "receiveonly" "receiveencrypted" ];
        default = "sendreceive";
        description = "Whether the folder sends, receives, or both.";
      };
      devices = mkOption {
        type = types.listOf (types.either types.str (types.submodule {
          freeformType = json;
          options = {
            name = mkOption {
              type = types.str;
              description = "The device's name, as in settings.devices.";
            };
            encryptionPasswordFile = mkOption {
              type = types.nullOr types.str;
              default = null;
              description = "A file with the password for an untrusted device: not on the phone yet.";
            };
          };
        }));
        default = [ ];
        description = "The devices this folder is shared with, by their names in settings.devices.";
      };
      versioning = mkOption {
        type = types.nullOr (types.submodule {
          freeformType = json;
          options.type = mkOption {
            type = types.enum [ "external" "simple" "staggered" "trashcan" ];
            description = "How old versions of changed files are kept.";
          };
        });
        default = null;
        description = "How Syncthing keeps the old versions of files that change (null: it does not).";
      };
      copyOwnershipFromParent = mkOption {
        type = types.bool;
        default = false;
        description = "Give new files the owner of their folder.";
      };
      ignorePerms = mkOption {
        type = types.bool;
        default = lib.hasPrefix "/storage/" config.path;
        defaultText = lib.literalExpression ''lib.hasPrefix "/storage/" path'';
        description = ''
          Leave files' permissions out of what is synced. True by default for the phone's storage
          (/storage/...), which keeps no permissions of its own (on NixOS this is false unless set).
        '';
      };
      ignorePatterns = mkOption {
        type = types.nullOr (types.listOf types.str);
        default = null;
        example = [ "/**/.git" "*.tmp" ];
        description = "Patterns of files to leave out, as in .stignore (null: Syncthing's own are kept).";
      };
    };
  };

  # NixOS's options the phone has not got: each one's value that leaves it unused, and why.
  notHere = [
    { name = "user"; unused = cfg.user == "syncthing"; why = "on the phone Syncthing runs as root."; }
    { name = "group"; unused = cfg.group == "syncthing"; why = "on the phone Syncthing runs as root."; }
    { name = "dataDir"; unused = cfg.dataDir == home; why = "on the phone Syncthing keeps its files in ${home}."; }
    { name = "configDir"; unused = cfg.configDir == home; why = "on the phone Syncthing keeps its files in ${home}."; }
    { name = "databaseDir"; unused = cfg.databaseDir == home; why = "on the phone Syncthing keeps its files in ${home}."; }
    { name = "relay.enable"; unused = !cfg.relay.enable; why = "a relay server is not on the phone."; }
  ];
  encrypted = lib.concatLists (lib.mapAttrsToList (name: folder:
    map (_: name) (lib.filter (device: builtins.isAttrs device && device.encryptionPasswordFile != null) folder.devices)
  ) cfg.settings.folders);
  folderList = lib.filter (folder: folder.enable) (lib.attrValues cfg.settings.folders);
  unknownDevices = lib.concatMap (folder:
    map (device: { folder = folder.id; inherit device; })
      (lib.filter (device: !(cfg.settings.devices ? ${device})) (map (device: if builtins.isString device then device else device.name) folder.devices))
  ) folderList;

  # The settings as NixOS sends them: each device with deviceID, each folder's devices by theirs,
  # unset settings left out; each folder's ignore patterns apart, as Syncthing takes them.
  clean = lib.converge (lib.filterAttrsRecursive (_: value: value != null && value != { }));
  idOf = name: cfg.settings.devices.${name}.id;
  devices = map (device: clean (device // { deviceID = device.id; })) (lib.attrValues cfg.settings.devices);
  folders = map (folder: {
    inherit (folder) id path;
    ignore = folder.ignorePatterns;
    json = clean (removeAttrs folder [ "enable" "ignorePatterns" ]) // {
      devices = map (device:
        if builtins.isString device then { deviceID = idOf device; }
        else clean (removeAttrs device [ "name" "encryptionPasswordFile" ]) // { deviceID = idOf device.name; }
      ) folder.devices;
    };
  }) folderList;
  others = clean (removeAttrs cfg.settings [ "devices" "folders" ]);

  q = lib.escapeShellArg;
  socket = lib.removePrefix "unix://" cfg.guiAddress;
  # curl's words for a path of its API: over its socket, or its address.
  api = path: if lib.hasPrefix "unix://" cfg.guiAddress
    then "--unix-socket ${q socket} http://syncthing.local${path}"
    else "http://${cfg.guiAddress}${path}";
  waitUp = ''
    for i in $(seq 300); do
      curl -sSf ${api "/rest/noauth/health"} > /dev/null 2>&1 && break
      sleep 0.2
    done
  '';

  init = ''
    # Its API answers once it is up (a first start makes its keys and index first).
    ${waitUp}
    key=$(sed -n 's:.*<apikey>\(.*\)</apikey>.*:\1:p' ${home}/config.xml)
    [ -n "$key" ] || { echo "Syncthing does not answer (its log: /data/adb/seraph/units/syncthing/log)"; exit 1; }
    st() { curl -sSf -H "X-API-Key: $key" "$@"; }
    me=$(st ${api "/rest/system/status"} | jq -r .myID)

    # The devices: the phone itself is Syncthing's own, not one to add or remove.
    ${lib.concatMapStrings (device: ''
      [ ${q device.deviceID} = "$me" ] || st --json ${q (builtins.toJSON device)} ${api "/rest/config/devices"} > /dev/null
    '') devices}
    ${lib.optionalString cfg.overrideDevices ''
      for id in $(st ${api "/rest/config/devices"} | jq -r --arg me "$me" --argjson keep ${q (builtins.toJSON (map (device: device.deviceID) devices))} '[.[].deviceID] - $keep - [$me] | .[] | @uri'); do
        st -X DELETE ${api "/rest/config/devices"}/"$id" > /dev/null
      done
    ''}

    # The folders, each in its place with Syncthing's marker in it, so it takes it as one to sync.
    ${lib.concatMapStrings (folder: ''
      [ -d ${q (dirOf folder.path)} ] || { echo "services.syncthing.settings.folders.${folder.id}: there is no ${dirOf folder.path} on the phone to keep it in (an SD card out?)"; exit 1; }
      mkdir -p ${q folder.path}/.stfolder
      st --json ${q (builtins.toJSON folder.json)} ${api "/rest/config/folders"} > /dev/null
    '' + lib.optionalString (folder.ignore != null) ''
      st --json ${q (builtins.toJSON { inherit (folder) ignore; })} ${api "/rest/db/ignores?folder=${lib.strings.escapeURL folder.id}"} > /dev/null
    '') folders}
    ${lib.optionalString cfg.overrideFolders ''
      for id in $(st ${api "/rest/config/folders"} | jq -r --argjson keep ${q (builtins.toJSON (map (folder: folder.id) folders))} '[.[].id] - $keep | .[] | @uri'); do
        st -X DELETE ${api "/rest/config/folders"}/"$id" > /dev/null
      done
    ''}

    # Its other settings, over what it has.
    ${lib.concatStrings (lib.mapAttrsToList (part: value: ''
      st -X ${if builtins.isAttrs value then "PATCH" else "PUT"} --json ${q (builtins.toJSON value)} ${api "/rest/config/${part}"} > /dev/null
    '') others)}
    ${lib.optionalString (onPhone.guiPasswordFile != null) ''
      st -X PATCH --json "$(jq -n --rawfile password ${q onPhone.guiPasswordFile} '{ password: ($password | rtrimstr("\n")) }')" ${api "/rest/config/gui"} > /dev/null
    ''}

    if st ${api "/rest/config/restart-required"} | jq -e .requiresRestart > /dev/null; then
      st -X POST ${api "/rest/system/restart"} > /dev/null
      sleep 1
      ${waitUp}
    fi
    connected=$(st ${api "/rest/system/connections"} | jq --arg me "$me" --argjson ids ${q (builtins.toJSON (map (device: device.deviceID) devices))} \
      '[.connections | to_entries[] | select(.value.connected and (.key as $k | $ids | index($k)) and .key != $me)] | length')
    peers=$(jq -n --arg me "$me" --argjson ids ${q (builtins.toJSON (map (device: device.deviceID) devices))} '$ids - [$me] | length')
    echo "Syncthing: running as $me ($connected of $peers devices connected, ${toString (builtins.length folders)} folders)"
  '';
in
{
  options.services.syncthing = {
    enable = lib.mkEnableOption ''
      Syncthing, as NixOS's services.syncthing: run by root, from its Nix package, keeping the
      config's folders in sync with the config's devices'';

    package = lib.mkPackageOption pkgs "syncthing" { };

    cert = mkOption {
      type = secretFiles.type;
      default = null;
      example = lib.literalExpression "./syncthing/cert.pem";
      description = ''
        The cert.pem file (with key: the phone's identity, its device ID), copied into Syncthing's
        folder: a file from the config's folder (./syncthing/cert.pem), which each build puts on the
        phone, readable by root alone and never in the profile or Nix's store; or, as a string, the
        full path of one already on the phone. null: Syncthing makes one of its own on its first
        start, and keeps it.
      '';
    };

    key = mkOption {
      type = secretFiles.type;
      default = null;
      example = lib.literalExpression "./syncthing/key.pem";
      description = "The key.pem file, with cert (a file from the config's folder, or a full path on the phone).";
    };

    guiPasswordFile = mkOption {
      type = secretFiles.type;
      default = null;
      description = "A file with the GUI's password, as on NixOS (a file from the config's folder, or a full path on the phone).";
    };

    overrideDevices = mkOption {
      type = types.bool;
      default = true;
      description = "Remove the devices that are not in settings.devices (ones added in the GUI too).";
    };

    overrideFolders = mkOption {
      type = types.bool;
      default = !anyAutoAccept;
      defaultText = lib.literalMD "true, unless a device has autoAcceptFolders";
      description = "Remove the folders that are not in settings.folders (ones added in the GUI too).";
    };

    settings = mkOption {
      type = types.submodule {
        freeformType = json;
        options = {
          options = mkOption {
            type = json;
            default = { };
            example = { relaysEnabled = true; urAccepted = -1; };
            description = "Syncthing's other settings (its options), by their names in its config: localAnnounceEnabled, relaysEnabled, urAccepted, ...";
          };
          devices = mkOption {
            type = types.attrsOf (types.submodule deviceModule);
            default = { };
            example = lib.literalExpression ''
              {
                laptop = {
                  id = "7CFNTQM-IMTJBHJ-3UWRDIU-ZGQJFR6-VCXZ3NB-XUH3KZO-N52ITXR-LAIYUAU";
                  addresses = [ "tcp://laptop.example.ts.net:22000" ];
                };
              }
            '';
            description = "The devices Syncthing talks to, by a name of your choosing.";
          };
          folders = mkOption {
            type = types.attrsOf (types.submodule folderModule);
            default = { };
            example = lib.literalExpression ''
              {
                documents = {
                  path = "/storage/emulated/0/Documents";
                  devices = [ "laptop" ];
                };
              }
            '';
            description = "The folders Syncthing keeps in sync, by their IDs.";
          };
        };
      };
      default = { };
      description = ''
        Syncthing's settings, as NixOS's services.syncthing.settings: devices, folders, options, and any
        other part of its config (gui, defaults, ...), sent through its REST API at each build.
      '';
    };

    guiAddress = mkOption {
      type = types.str;
      default = "unix:///run/syncthing/gui.sock";
      example = "127.0.0.1:8384";
      description = ''
        Where its GUI and REST API answer: a socket only root can reach, by default (NixOS's default,
        127.0.0.1:8384, is reachable by every app on a phone, and the GUI has no password unless
        guiPasswordFile or settings.gui sets one), or host:port, for a browser on the phone.
      '';
    };

    systemService = mkOption {
      type = types.bool;
      default = true;
      description = "As on NixOS; on the phone Syncthing always runs as a system service (root's).";
    };

    user = mkOption {
      type = types.str;
      default = "syncthing";
      description = "As on NixOS. On the phone Syncthing runs as root.";
    };

    group = mkOption {
      type = types.str;
      default = "syncthing";
      description = "As on NixOS. On the phone Syncthing runs as root.";
    };

    dataDir = mkOption {
      type = types.str;
      default = home;
      description = "Where Syncthing keeps its files, as on NixOS: ${home}, which is the only place on the phone.";
    };

    configDir = mkOption {
      type = types.str;
      default = cfg.dataDir;
      defaultText = lib.literalExpression "config.services.syncthing.dataDir";
      description = "As on NixOS (see dataDir).";
    };

    databaseDir = mkOption {
      type = types.str;
      default = cfg.configDir;
      defaultText = lib.literalExpression "config.services.syncthing.configDir";
      description = "As on NixOS (see dataDir).";
    };

    openDefaultPorts = mkOption {
      type = types.bool;
      default = false;
      description = "Open Syncthing's ports, as on NixOS. A phone has no firewall in its way, so here it does nothing.";
    };

    extraFlags = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "--reset-deltas" ];
      description = "More flags for `syncthing serve`.";
    };

    all_proxy = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "socks5://127.0.0.1:1055";
      description = "A proxy for Syncthing's connections (all_proxy), as on NixOS.";
    };

    relay.enable = lib.mkEnableOption "a Syncthing relay server, as on NixOS. Not on the phone";
  };

  config = lib.mkIf cfg.enable {
    android.secrets = lib.foldl' (all: option: all // secretFiles.secrets "services.syncthing.${option}" secretOptions.${option}) { } (lib.attrNames secretOptions);

    systemd.services.syncthing = {
      description = "Syncthing";
      wantedBy = [ "multi-user.target" ];
      environment = {
        HOME = home;
        STNODEFAULTFOLDER = "yes";
        STNOUPGRADE = "yes";
        all_proxy = cfg.all_proxy;
      };
      # The config's identity, readable by root alone, so its device ID is theirs.
      preStart = lib.optionalString (onPhone.cert != null) ''
        for part in ${q onPhone.cert}:cert.pem ${q onPhone.key}:key.pem; do
          [ "$(sha256sum < "''${part%:*}")" = "$(sha256sum < ${home}/"''${part#*:}" 2> /dev/null)" ] \
            || install -m 600 "''${part%:*}" ${home}/"''${part#*:}"
        done
      '';
      serviceConfig = {
        ExecStart = "${cfg.package}/bin/syncthing serve --no-browser --no-restart --home=${home}"
          + " --gui-address=${cfg.guiAddress} ${lib.escapeShellArgs cfg.extraFlags}";
        StateDirectory = "syncthing";
        RuntimeDirectory = "syncthing";
        Restart = "on-failure";
        RestartSec = 10;
      };
    };

    systemd.services.syncthing-init = {
      description = "Syncthing's settings, from the config";
      wantedBy = [ "multi-user.target" ];
      after = [ "syncthing.service" ];
      wants = [ "syncthing.service" ];
      path = [ pkgs.curl pkgs.jq ];
      serviceConfig.Type = "oneshot";
      script = init;
    };

    assertions = [
      {
        assertion = (cfg.cert == null) == (cfg.key == null);
        message = "services.syncthing: cert and key go together (the phone's identity), or neither is set.";
      }
    ] ++ lib.mapAttrsToList (option: value: {
      assertion = secretFiles.valid value;
      message = secretFiles.problem "services.syncthing.${option}" value;
    }) secretOptions ++ [
      {
        assertion = builtins.match "[^/ ]+:[0-9]+|unix:///[^ ]+" cfg.guiAddress != null;
        message = "services.syncthing.guiAddress: \"${cfg.guiAddress}\" is not host:port (like 127.0.0.1:8384) or unix:///<a socket's path>.";
      }
      {
        assertion = lib.all (folder: lib.hasPrefix "/" folder.path) folderList;
        message = "services.syncthing.settings.folders: each path is a full path on the phone, like /storage/emulated/0/Documents.";
      }
      {
        assertion = unknownDevices == [ ];
        message = lib.concatMapStringsSep "\n" (u: "services.syncthing.settings.folders.${u.folder}: there is no device named ${u.device} in settings.devices.") unknownDevices;
      }
      {
        assertion = encrypted == [ ];
        message = "services.syncthing.settings.folders: encryptionPasswordFile (${lib.concatStringsSep ", " encrypted}) is not on the phone yet.";
      }
    ] ++ map (option: {
      assertion = option.unused;
      message = "services.syncthing.${option.name}: ${option.why}";
    }) notHere;

    warnings = lib.optional (!lib.hasPrefix "unix://" cfg.guiAddress && cfg.guiPasswordFile == null && !((cfg.settings.gui or { }) ? password))
      "services.syncthing.guiAddress: every app on the phone can reach ${cfg.guiAddress}, and the GUI has no password: set guiPasswordFile (or settings.gui.user and settings.gui.password).";
  };
}
