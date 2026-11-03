# Backups of apps and folders, made by the phone itself into a restic repository (restic: one copy of
# each file, encrypted with a password, on the phone, an SD card, a server over SFTP, S3, ...):
#   android.backup = { repository = "/storage/EF27-E719/Backup"; passwordFile = ./backup-password; };
#   android.backup.apps = [ "org.example.notes" ];          # these apps, with android.backup.defaults
#   android.apps."org.example.chat".backup = { include = [ "data" ]; };   # or one by one
#   android.backup.folders = [ "/storage/emulated/0/DCIM" ];
# An app's backup is its APK (and splits) and its data (include picks which). It is made by itself:
# "auto" when the app was left in the background for quietTime (and at least once a schedule),
# "schedule" once a schedule, "manual" only with seraph-backup now. Only what changed is read again
# and stored, an idle app's processes are paused for the moment of the copy (so its files are whole;
# one in use is read as it is), and the backups kept follow keep. Before each backup the repository's
# storage is checked (there, with room), and an automatic one also waits while the phone is short of
# memory or hot; one a restart broke off starts again. With autoRestore (off unless turned on, for a
# new phone), a build that installs an app restores its data from the newest backup of a version not
# newer than the one installed, never over data the app already has, and an app backed up with its
# APK that is not on the phone (one from the Play Store, say) is installed from its backup, with its
# data; an app this phone backed up and that was removed since stays removed (the Backups tab brings
# it back by hand). Backups stay when they, or the module, leave the config.
# The engine does it (see Backup.kt); by hand, the app's Backups tab and seraph-backup (list, now,
# restore, restic) back up any app or folder, and bring back an app's APK and data, or a folder.
{ config, lib, pkgs, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.android.backup;
  secretFiles = import ./secret-files.nix { inherit lib; };

  parts = [ "data" "deviceData" "storage" "media" "obb" ];
  whens = [ "auto" "schedule" "manual" ];

  # "10m", "6h", "1d", "30s" (or hourly, daily, weekly) as seconds; null when it is none of them.
  seconds = span:
    let
      named = { hourly = 3600; daily = 86400; weekly = 604800; };
      m = builtins.match "([0-9]+) ?(s|m|h|d|w)" span;
      unit = { s = 1; m = 60; h = 3600; d = 86400; w = 604800; };
    in
    if named ? ${span} then named.${span}
    else if m == null then null
    else lib.toInt (builtins.elemAt m 0) * unit.${builtins.elemAt m 1};

  # What an app's backup takes; null: as android.backup.defaults says.
  settingsOptions = isDefault: {
    apk = mkOption {
      type = if isDefault then types.bool else types.nullOr types.bool;
      default = if isDefault then true else null;
      description = "Its APK (and split APKs) too, so it can be installed from the backup.";
    };
    data = mkOption {
      type = if isDefault then types.bool else types.nullOr types.bool;
      default = if isDefault then true else null;
      description = "Its data (the parts include names); false: the APK alone.";
    };
    include = mkOption {
      type = if isDefault then types.listOf (types.enum parts) else types.nullOr (types.listOf (types.enum parts));
      default = if isDefault then [ "data" "deviceData" "storage" ] else null;
      example = [ "data" "deviceData" "storage" "media" "obb" ];
      description = ''
        Which of its data: "data" (its own files, /data/data/<app>), "deviceData" (what it keeps readable before
        the first unlock, /data/user_de/0/<app>), "storage" (Android/data/<app> on the shared storage), "media"
        (Android/media/<app>) and "obb" (Android/obb/<app>, large game files). Caches are left out.
      '';
    };
    when = mkOption {
      type = if isDefault then types.enum whens else types.nullOr (types.enum whens);
      default = if isDefault then "auto" else null;
      description = ''
        When it is backed up: "auto" (after it was used: once it stayed in the background for
        android.backup.quietTime, and at least once a android.backup.schedule, for apps that work in the background),
        "schedule" (once a android.backup.schedule) or "manual" (only with seraph-backup now). An app is backed up
        again when it was updated too, unless it is "manual".
      '';
    };
    autoRestore = mkOption {
      type = if isDefault then types.bool else types.nullOr types.bool;
      default = if isDefault then false else null;
      description = ''
        Off unless turned on (a new phone, to bring everything back): a build that installs the app brings its data
        back from its newest backup (of a version not newer than the one installed), unless the app has data already;
        and an app backed up with its APK that is not on the phone and in no list of android.packages is installed
        from its backup. Never for an app this phone backed up and that was removed since: that one comes back only
        by hand (the Backups tab, seraph-backup restore).
      '';
    };
  };

  # Each app backed up, with its settings: android.backup.apps, then android.apps.<app>.backup.
  appSettings = lib.filterAttrs (_: value: value != null) (
    lib.genAttrs cfg.apps (_: true)
    // lib.filterAttrs (_: value: value != null) (lib.mapAttrs (_: app: app.backup) config.android.apps)
  );
  resolved = lib.mapAttrs (_: value:
    let own = if builtins.isAttrs value then value else { }; in
    lib.genAttrs [ "apk" "data" "include" "when" "autoRestore" ] (name:
      if own.${name} or null != null then own.${name} else cfg.defaults.${name})
  ) (lib.filterAttrs (_: value: value != false) appSettings);

  backends = [ "sftp:" "rest:" "s3:" "b2:" "azure:" "gs:" "swift:" "rclone:" ];
  repository = cfg.repository;
  isRemote = repository != null && lib.any (prefix: lib.hasPrefix prefix repository) backends;
  extraPath = lib.optional (repository != null && lib.hasPrefix "sftp:" repository) pkgs.openssh
    ++ lib.optional (repository != null && lib.hasPrefix "rclone:" repository) pkgs.rclone;
  restic = "${pkgs.restic}/bin/restic";

  passwordFile = secretFiles.path config "android.backup.passwordFile" cfg.passwordFile;
  environmentFile = secretFiles.path config "android.backup.environmentFile" cfg.environmentFile;

  badSpans = lib.filter (name: seconds cfg.${name} == null) [ "schedule" "quietTime" ];
  isPackageName = id: builtins.match "[A-Za-z][A-Za-z0-9_]*([.][A-Za-z][A-Za-z0-9_]*)+" id != null;
in
{
  options.android.backup = {
    repository = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "/storage/EF27-E719/Backup";
      description = ''
        Where the backups go: a restic repository, as restic names one (and NixOS's services.restic.backups): a
        folder on the phone (internal storage, an SD card: /storage/<card>/…), or sftp:user@host:/path, rest:https://…,
        s3:…, b2:…, azure:…, gs:…, swift:…, rclone:… (environmentFile has what they log in with). It is made at the
        first backup (initialize). null: no backups.
      '';
    };

    passwordFile = mkOption {
      type = secretFiles.type;
      default = null;
      example = lib.literalExpression "./backup-password";
      description = ''
        The repository's password, in a file: one from the config's folder (a path, without quotes), which each build
        puts on the phone readable by root alone, or the full path of one on the phone. Keep a copy off the phone: the
        backups cannot be read without it.
      '';
    };

    environmentFile = mkOption {
      type = secretFiles.type;
      default = null;
      example = lib.literalExpression "./backup-env";
      description = ''
        Environment variables restic reads (NAME=value lines): the keys of a remote repository, like
        AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY, as NixOS's services.restic.backups.<name>.environmentFile.
      '';
    };

    initialize = mkOption {
      type = types.bool;
      default = true;
      description = "Make the repository at the first backup when it is not there yet.";
    };

    defaults = settingsOptions true;

    apps = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "org.example.notes" "org.example.chat" ];
      description = ''
        Apps backed up with android.backup.defaults, by package name; android.apps."<app>".backup = { … } sets one's
        own (and false leaves one of this list out).
      '';
    };

    folders = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "/storage/emulated/0/DCIM" "/storage/emulated/0/Documents" ];
      description = "Folders backed up once a schedule, by their full paths on the phone.";
    };

    schedule = mkOption {
      type = types.str;
      default = "daily";
      example = "6h";
      description = ''How often the apps "schedule" (and "auto", at least) and the folders are backed up: "hourly", "daily", "weekly", or like "6h", "2d".'';
    };

    quietTime = mkOption {
      type = types.str;
      default = "10m";
      description = ''How long an "auto" app stays in the background, after it was used, before it is backed up (like "10m", "1h").'';
    };

    keep = mkOption {
      type = types.submodule {
        options = lib.genAttrs [ "last" "hourly" "daily" "weekly" "monthly" "yearly" ] (name: mkOption {
          type = types.ints.unsigned;
          default = { last = 3; daily = 7; weekly = 4; }.${name} or 0;
          description = "The ${if name == "last" then "newest" else "${name}"} backups kept of each app and folder (restic forget --keep-${name}).";
        });
      };
      default = { };
      example = { last = 3; daily = 7; weekly = 4; monthly = 6; };
      description = "Which backups are kept, as restic's forget keeps them; the others go, and the space they took is freed once a week.";
    };

    onlyWhileCharging = mkOption {
      type = types.bool;
      default = false;
      description = "Back up by itself only while the phone charges (never while the battery is low anyway).";
    };
  };

  config = {
    android.secrets = secretFiles.secrets "android.backup.passwordFile" cfg.passwordFile
      // secretFiles.secrets "android.backup.environmentFile" cfg.environmentFile;

    system.build.backup = if repository == null then null else {
      inherit repository restic passwordFile environmentFile;
      inherit (cfg) initialize folders onlyWhileCharging keep;
      path = builtins.unsafeDiscardStringContext "${lib.makeBinPath ([ pkgs.restic ] ++ extraPath)}:/system/bin";
      host = config.networking.hostName;
      schedule = seconds cfg.schedule;
      quietTime = seconds cfg.quietTime;
      apps = resolved;
      # For an app backed up by hand that the config does not name (seraph-backup now, the app's Backups tab).
      defaults = { inherit (cfg.defaults) apk data include when autoRestore; };
    };
    system.build.backupStorePaths = lib.optionals (repository != null)
      (map (package: builtins.unsafeDiscardStringContext "${package}") ([ pkgs.restic ] ++ extraPath));

    assertions = [
      {
        assertion = repository == null || cfg.passwordFile != null;
        message = "android.backup.passwordFile: a repository needs a password: passwordFile = ./backup-password; (a file of the config's folder, kept off the repo).";
      }
      {
        assertion = repository == null || lib.hasPrefix "/" repository || isRemote;
        message = "android.backup.repository: \"${toString repository}\" is neither a full path on the phone nor a restic repository (${lib.concatStringsSep " " backends}).";
      }
      {
        assertion = repository != null || (resolved == { } && cfg.folders == [ ]);
        message = "android.backup: apps or folders are backed up, but there is no repository to put them in: android.backup.repository = \"/storage/…\";";
      }
      { assertion = secretFiles.valid cfg.passwordFile; message = secretFiles.problem "android.backup.passwordFile" cfg.passwordFile; }
      { assertion = secretFiles.valid cfg.environmentFile; message = secretFiles.problem "android.backup.environmentFile" cfg.environmentFile; }
      {
        assertion = badSpans == [ ];
        message = lib.concatMapStringsSep "\n" (name: "android.backup.${name}: \"${cfg.${name}}\" is not like \"10m\", \"6h\", \"2d\" (or hourly, daily, weekly).") badSpans;
      }
      {
        assertion = lib.all isPackageName cfg.apps;
        message = "android.backup.apps: ${lib.concatStringsSep ", " (lib.filter (id: !isPackageName id) cfg.apps)} is not a package name (like \"org.example.app\").";
      }
      {
        assertion = lib.all (lib.hasPrefix "/") cfg.folders;
        message = "android.backup.folders: each folder is a full path on the phone, like \"/storage/emulated/0/DCIM\".";
      }
      {
        assertion = cfg.keep.last + cfg.keep.hourly + cfg.keep.daily + cfg.keep.weekly + cfg.keep.monthly + cfg.keep.yearly > 0;
        message = "android.backup.keep: keep at least one backup (last = 1;).";
      }
    ];
  };
}
