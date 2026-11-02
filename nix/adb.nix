# adb, Android's debugging bridge, as a service of the phone: on or off, over the network too (from each boot on), and
# the computers it lets in without asking ("Allow USB debugging?"). NixOS has nothing like it, so it is Android's own:
# android.adb. The engine does it (see Adb.kt): it adds the trusted keys to the phone's own list
# (/data/misc/adb/adb_keys) at each build and boot, and sets the port adbd listens on, as `adb tcpip` does until a
# restart. A key taken out of the config goes from the list again, unless it was trusted before Seraph added it; the
# port goes back too.
{ config, lib, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.android.adb;

  # An adb public key, as ~/.android/adbkey.pub has it: base64, then the computer's name.
  isKey = key: builtins.match "[A-Za-z0-9+/]{100,}={0,2}( .*)?" key != null;
  # Keys from files of the config's folder are read now; a file named by its full path on the phone is read there.
  fileKeys = lib.concatMap (file:
    lib.filter (line: line != "" && !lib.hasPrefix "#" line) (map lib.trim (lib.splitString "\n" (builtins.readFile file)))
  ) (lib.filter builtins.isPath cfg.trustedKeyFiles);
  keys = lib.unique (map lib.trim cfg.trustedKeys ++ fileKeys);
  phoneFiles = lib.filter (file: !builtins.isPath file) cfg.trustedKeyFiles;
in
{
  options.android.adb = {
    enable = mkOption {
      type = types.nullOr types.bool;
      default = null;
      description = ''
        adb (USB debugging) on or off, over USB and over the network: android.settings.developer.usbDebugging, unless
        that is set. Off cuts the computer the config may be built from over adb too. null: not managed.
      '';
    };

    port = mkOption {
      type = types.nullOr types.port;
      default = null;
      example = 5555;
      description = ''
        adb over the network too, on this TCP port, from each boot on (adb connect <phone>:5555), as `adb tcpip 5555`
        does until a restart. adb then lets in the computers it trusts (trustedKeys), and asks on the phone's screen
        for any other. A build that changes it restarts adb after it ends, so a computer connected over adb connects
        again. null: not managed.
      '';
    };

    trustedKeys = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "QAAAAM0muSn7yYd3dJpCdY8v…GHj9S+0zjSd2Rl0= neo@laptop" ];
      description = ''
        Computers adb lets in without asking ("Allow USB debugging?"): their public keys, as ~/.android/adbkey.pub has
        each. They are added to the computers the phone trusts at each build and boot; one taken out of the config goes
        again, unless the phone trusted it before.
      '';
    };

    trustedKeyFiles = mkOption {
      type = types.listOf (types.either types.path types.str);
      default = [ ];
      example = lib.literalExpression ''[ ./laptop-adbkey.pub "/data/adb/seraph/secrets/adb_keys" ]'';
      description = ''
        The same from files of keys, one a line: a file from the config's folder (read when the config is built), or,
        as a string, the full path of one on the phone (read there at each build and boot).
      '';
    };

    forgetUnused = mkOption {
      type = types.nullOr types.bool;
      default = null;
      description = ''
        Whether Android forgets a computer that has not connected for 7 days, as it does by default (Developer options:
        Disable adb authorization timeout, off). false keeps them, trustedKeys too, between builds. null: not managed.
      '';
    };
  };

  config = {
    android.settings.developer.usbDebugging = lib.mkIf (cfg.enable != null) (lib.mkDefault cfg.enable);

    android.settingMappings = [
      {
        option = "adb.forgetUnused";
        label = "android.adb.forgetUnused";
        table = "global";
        key = "adb_allowed_connection_time";
        value = cfg.forgetUnused;
        # In milliseconds; 0 never forgets.
        store = forget: if forget then 604800000 else 0;
      }
    ];

    system.build.adb = {
      inherit (cfg) port;
      inherit keys;
      keyFiles = phoneFiles;
    };

    assertions = [
      {
        assertion = lib.all isKey keys;
        message = "android.adb.trustedKeys: ${lib.concatStringsSep ", " (map (key: "\"${lib.substring 0 16 key}…\"") (lib.filter (key: !isKey key) keys))} is not an adb public key (as ~/.android/adbkey.pub has it: QAAAA…= name).";
      }
      {
        assertion = lib.all (file: builtins.isPath file || lib.hasPrefix "/" file) cfg.trustedKeyFiles;
        message = "android.adb.trustedKeyFiles: a file from the config's folder is a path, written without quotes (./laptop-adbkey.pub); a string is a full path on the phone.";
      }
    ];
  };
}
