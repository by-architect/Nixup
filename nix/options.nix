# The options a phone config can set. A config that sets anything else, or a
# value of the wrong type, stops the build with an error; so does a failed
# assertion, and warnings are shown, as in NixOS.
{ config, lib, ... }:
let
  inherit (lib) mkOption types;

  isPackageName = id: builtins.match "[A-Za-z][A-Za-z0-9_]*([.][A-Za-z][A-Za-z0-9_]*)+" id != null;

  # An F-Droid repository: its address and the key its index is signed with.
  repoModule = { config, ... }: {
    options = {
      url = mkOption {
        type = types.str;
        example = "https://example.org/fdroid/repo?fingerprint=50941BDA…";
        description = "The repository's address, as F-Droid shares it: with ?fingerprint=… or without.";
      };
      fingerprint = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = "SHA-256 of the key the repository signs its index with; from the url's ?fingerprint= when null.";
      };
      address = mkOption {
        type = types.str;
        internal = true;
        readOnly = true;
        description = "The url without ?fingerprint= and a trailing slash.";
      };
      signingKey = mkOption {
        type = types.str;
        internal = true;
        readOnly = true;
        description = "The fingerprint, in lower case.";
      };
    };
    config =
      let
        parts = builtins.match "([^?]*)[?]fingerprint=([0-9A-Fa-f]+)" config.url;
      in
      {
        address = lib.removeSuffix "/" (if parts == null then config.url else builtins.elemAt parts 0);
        signingKey = lib.toLower (
          if config.fingerprint != null then config.fingerprint
          else if parts == null then ""
          else builtins.elemAt parts 1
        );
      };
  };

  # An app picked from a repository's names (with pkgs.fdroid; [ org.example.app ]): a
  # node of its package name tree, which holds the full name in _id and the repo in _repo.
  repoApp = types.addCheck types.attrs (app: app ? _id) // {
    description = "app from a repository (from pkgs.<repo>)";
  };

  # An app from its own releases, as Obtainium adds one: android.packages.obtainium.
  releasesApp = types.submodule {
    options = {
      id = mkOption {
        type = types.str;
        description = "The app's package name, like dev.imranr.obtainium.";
      };
      url = mkOption {
        type = types.str;
        example = "https://github.com/ImranR98/Obtainium";
        description = ''
          Where its releases are: https://github.com/<owner>/<repo>,
          https://gitlab.com/<group>/<project>, https://codeberg.org/<owner>/<repo>, or
          another Forgejo or Gitea server.
        '';
      };
      apkFilter = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "^(?!.*fdroid)";
        description = ''
          A regular expression the APK's file name must match (Obtainium's apkFilterRegEx).
          The APK for the phone's ABI (arm64-v8a, …) is picked by itself; this picks
          between the others a release has, like flavors: "^(?!.*fdroid)" takes the
          names without "fdroid" in them.
        '';
      };
      signer = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "B3:53:60:1F:6A:1D:5F:D6:60:3A:E2:F5:0B:E8:0C:F3:01:36:7B:86:B6:AB:8B:1F:66:24:3D:A9:6C:D5:73:62";
        description = ''
          SHA-256 of the certificate the app is signed with (64 hex digits, with or
          without colons, as apksigner and Obtainium show it): an APK signed otherwise is
          refused. Without it, the first install trusts what the release offers (later
          updates must have the same signer anyway: Android checks that).
        '';
      };
      prerelease = mkOption {
        type = types.bool;
        default = false;
        description = "Take pre-releases too.";
      };
    };
  };
  # A repository's node always has _id (and may have a child named url); an app from releases has none.
  isReleasesApp = app: builtins.isAttrs app && !(app ? _id) && app ? url;
  idOf = app: if builtins.isString app then app else if app ? _id then app._id else app.id;

  # Sources that are not F-Droid repositories, always there.
  builtinSources = [ "obtainium" ];

  repos = config.android.repos;
  packages = config.android.packages;
  ids = lib.mapAttrs (_: map idOf) packages;
  allIds = lib.concatLists (lib.attrValues ids);
  unknownRepos = lib.filter (name: !(repos ? ${name}) && !(lib.elem name builtinSources))
    (lib.unique (lib.attrNames packages ++ lib.attrNames config.android.settings));
  releasesApps = packages.obtainium or [ ];
  notReleasesApps = map idOf (lib.filter (app: !isReleasesApp app) releasesApps);
  misplacedReleasesApps = lib.concatLists (lib.mapAttrsToList (repo: list:
    map (app: { inherit repo; inherit (app) id; }) (lib.filter isReleasesApp list)
  ) (removeAttrs packages [ "obtainium" ]));
  badReleaseUrls = map (app: app.id) (lib.filter (app: isReleasesApp app && builtins.match "https://[^/]+/[^/]+/[^/]+.*" app.url == null) releasesApps);
  badSigners = map (app: app.id) (lib.filter (app: isReleasesApp app && app.signer != null
    && builtins.match "[0-9a-fA-F]{64}|([0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}" app.signer == null) releasesApps);
  twice = lib.concatLists (lib.mapAttrsToList (repo: list:
    map (id: { inherit repo id; }) (lib.unique (lib.filter (id: lib.count (other: other == id) list > 1) list))
  ) ids);
  inSeveral = lib.filter (id: lib.length (lib.filter (list: lib.elem id list) (lib.attrValues ids)) > 1) (lib.unique allIds);
  notNames = lib.filter (id: !isPackageName id) allIds;
  wrongRepo = lib.concatLists (lib.mapAttrsToList (repo: list:
    map (app: { inherit repo; id = app._id; from = app._repo; })
      (lib.filter (app: !builtins.isString app && app ? _repo && app._repo != repo) list)
  ) packages);
  badRepoNames = lib.filter (name: builtins.match "[a-z][a-zA-Z0-9]*" name == null) (lib.attrNames repos);
  notHttps = lib.filter (name: !lib.hasPrefix "https://" repos.${name}.address) (lib.attrNames repos);
  badKeys = lib.filter (name: builtins.match "[0-9a-f]{64}" repos.${name}.signingKey == null) (lib.attrNames repos);

  debloat = config.android.debloat;
  debloatNotNames = lib.filter (id: !isPackageName id) debloat;
  debloatTwice = lib.unique (lib.filter (id: lib.count (other: other == id) debloat > 1) debloat);
  debloatInstalled = lib.unique (lib.filter (id: lib.elem id allIds) debloat);

  apps = config.android.apps;
  appsNotNames = lib.filter (id: !isPackageName id) (lib.attrNames apps);
  # Each default (browser, dialer, ...) can belong to one app only.
  claims = lib.concatLists (lib.mapAttrsToList (id: app: map (role: { inherit id role; }) app.defaultFor) apps);
  roleClash = lib.filter (role: lib.length (lib.filter (c: c.role == role) claims) > 1) (lib.unique (map (c: c.role) claims));
  badLanguages = lib.filterAttrs (id: app:
    app.language != null && app.language != "system"
    && builtins.match "[a-z]{2,3}(-[A-Za-z0-9]{2,8})*" app.language == null) apps;

  check = assertion: message: { inherit assertion message; };
in
{
  imports = [ ./system.nix ];

  options = {
    assertions = mkOption {
      type = types.listOf (types.submodule {
        options.assertion = mkOption { type = types.bool; };
        options.message = mkOption { type = types.str; };
      });
      default = [ ];
      internal = true;
      description = "Checks that must hold, or the build stops with their messages.";
    };

    warnings = mkOption {
      type = types.listOf types.str;
      default = [ ];
      internal = true;
      description = "Messages shown while the config builds.";
    };

    android.darkMode = mkOption {
      type = types.nullOr types.bool;
      default = null;
      description = "Dark mode on (true) or off (false); null leaves it as the user sets it.";
    };

    android.repos = mkOption {
      type = types.attrsOf (types.submodule repoModule);
      default = { };
      example = lib.literalExpression ''
        {
          byarchitect.url = "https://fdroid.byarchitect.org/fdroid/repo?fingerprint=50941BDA…";
        }
      '';
      description = ''
        F-Droid repositories, by a name of your choosing; fdroid (f-droid.org) is always
        there. Each one's apps are named in pkgs.<name> and listed in android.packages.<name>.
      '';
    };

    android.packages = mkOption {
      type = types.attrsOf (types.listOf (types.oneOf [ types.str repoApp releasesApp ]));
      default = { };
      example = lib.literalExpression ''
        {
          fdroid = with pkgs.fdroid; [ com.byarchitect.dejavu ];
          obtainium = [ { id = "dev.imranr.obtainium"; url = "https://github.com/ImranR98/Obtainium"; apkFilter = "^(?!.*fdroid)"; } ];
        }
      '';
      description = ''
        Apps installed from each repository (android.packages.<repo>): names from
        pkgs.<repo>, or package names as strings (a name with a Nix keyword in it,
        like "in.example.app", needs one). android.packages.obtainium takes apps from
        their own releases instead, as Obtainium does: { id = …; url = …; }. An app can
        come from one source only.
      '';
    };

    android.settings = mkOption {
      type = types.attrsOf (types.submodule {
        options.autoupdatePackages = mkOption {
          type = types.bool;
          default = false;
          description = ''
            Update the repository's apps (android.packages.<repo>) by themselves: the phone
            looks for updates every 6 hours on Wi-Fi and installs them, with notifications.
            When false it only tells, in a notification, that there are updates; the app's
            Updates tab installs them.
          '';
        };
      });
      default = { };
      description = "Settings of each repository (android.settings.<repo>).";
    };

    android.debloat = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "org.lineageos.jelly" "com.android.egg" ];
      description = ''
        Apps that came with the phone (the ROM's own) to remove, by package name: each is
        uninstalled for the user, its data kept, so taking it out of the list (or removing the
        module) brings it back as it was. A name this phone does not have is passed over, so one
        list fits several phones. Parts Android needs (System UI, Settings, the phone app, the
        package installer, the current launcher and keyboard, ...) are refused.
        android.apps.<app>.enabled = false disables an app instead.
      '';
    };

    android.apps = mkOption {
      type = types.attrsOf (types.submodule ./app.nix);
      default = { };
      example = lib.literalExpression ''
        {
          "org.example.app" = {
            permissions.camera = false;
            battery = "restricted";
          };
        }
      '';
      description = "Settings of installed apps, by package name (nix/app.nix lists them).";
    };
  };

  config.android.repos.fdroid = {
    url = lib.mkDefault "https://f-droid.org/repo";
    fingerprint = lib.mkDefault "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab";
  };

  config.assertions = [
    (check (unknownRepos == [ ]) (lib.concatMapStringsSep "\n" (name:
      "android.packages.${name} / android.settings.${name}: there is no repository named ${name} (android.repos.${name}.url = \"…\";)."
    ) unknownRepos))
    (check (twice == [ ]) (lib.concatMapStringsSep "\n" (t:
      "android.packages.${t.repo} lists ${t.id} more than once: list each app once."
    ) twice))
    (check (inSeveral == [ ]) (lib.concatMapStringsSep "\n" (id:
      "${id} is listed under more than one repository ("
      + lib.concatStringsSep ", " (lib.attrNames (lib.filterAttrs (_: list: lib.elem id list) ids)) + "): pick one."
    ) inSeveral))
    (check (notNames == [ ]) "android.packages: ${lib.concatStringsSep ", " notNames} is not a package name (like org.example.app).")
    (check (wrongRepo == [ ]) (lib.concatMapStringsSep "\n" (w:
      "android.packages.${w.repo}: ${w.id} is from pkgs.${w.from}; list it under android.packages.${w.from}."
    ) wrongRepo))
    (check (notReleasesApps == [ ]) "android.packages.obtainium: ${lib.concatStringsSep ", " notReleasesApps} needs its releases: { id = \"…\"; url = \"https://github.com/…\"; }.")
    (check (misplacedReleasesApps == [ ]) (lib.concatMapStringsSep "\n" (m:
      "android.packages.${m.repo}: ${m.id} comes with a url; list it under android.packages.obtainium."
    ) misplacedReleasesApps))
    (check (badReleaseUrls == [ ]) "android.packages.obtainium: ${lib.concatStringsSep ", " badReleaseUrls} needs an https:// url of its repository, like https://github.com/<owner>/<repo>.")
    (check (badSigners == [ ]) "android.packages.obtainium: the signer of ${lib.concatStringsSep ", " badSigners} is not a SHA-256 (64 hex digits).")
    (check (lib.elem "obtainium" (lib.attrNames repos) == false) "android.repos.obtainium: that name is taken by android.packages.obtainium (apps from their own releases); name the repository otherwise.")
    (check (badRepoNames == [ ]) "android.repos: ${lib.concatStringsSep ", " badRepoNames} is not a name like byarchitect (a letter, then letters and digits).")
    (check (notHttps == [ ]) "android.repos: ${lib.concatStringsSep ", " notHttps} needs an https:// address.")
    (check (badKeys == [ ]) (lib.concatMapStringsSep "\n" (name:
      "android.repos.${name}: its fingerprint (the SHA-256 of its signing key: 64 hex digits, in ?fingerprint= or in fingerprint) is missing or not right."
    ) badKeys))
    (check (debloatNotNames == [ ]) "android.debloat: ${lib.concatStringsSep ", " debloatNotNames} is not a package name (like \"org.example.app\").")
    (check (debloatTwice == [ ]) "android.debloat lists ${lib.concatStringsSep ", " debloatTwice} more than once: list each app once.")
    (check (debloatInstalled == [ ]) "${lib.concatStringsSep ", " debloatInstalled}: in android.packages and in android.debloat; pick one.")
    (check (appsNotNames == [ ]) "android.apps: ${lib.concatStringsSep ", " appsNotNames} is not a package name (like \"org.example.app\").")
    (check (roleClash == [ ]) (lib.concatMapStringsSep "\n" (role:
      "android.apps: more than one app is the default for ${role} ("
      + lib.concatStringsSep ", " (map (c: c.id) (lib.filter (c: c.role == role) claims)) + "): pick one."
    ) roleClash))
    (check (badLanguages == { }) (lib.concatStringsSep "\n" (lib.mapAttrsToList (id: app:
      "android.apps.\"${id}\".language: \"${app.language}\" is not a language like \"tr-TR\" (or \"system\")."
    ) badLanguages)))
  ];

  config.warnings = lib.concatLists (lib.mapAttrsToList (id: app:
    lib.optional (app.permissions.preciseLocation != null && lib.elem app.permissions.location [ null false "ask" ])
      "android.apps.\"${id}\": preciseLocation does nothing while location is ${builtins.toJSON app.permissions.location}."
    ++ lib.optional (lib.elem id debloat)
      "android.apps.\"${id}\": ${id} is in android.debloat, so its settings do nothing."
  ) apps);
}
