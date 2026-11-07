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

  # Lists of android.packages that are not F-Droid repositories: apps from their releases, apps from
  # Google Play (nix/playstore.nix), and pkgs, which is refused below with where Nix packages go
  # (environment.systemPackages, as on NixOS).
  builtinSources = [ "obtainium" "playstore" "pkgs" ];

  repos = config.android.repos;
  packages = config.android.packages;
  ids = lib.mapAttrs (_: map idOf) packages;
  allIds = lib.concatLists (lib.attrValues ids);
  unknownRepos = lib.filter (name: !(repos ? ${name}) && !(lib.elem name builtinSources)) (lib.attrNames packages)
    ++ lib.filter (name: !(repos ? ${name}) && !(lib.elem name [ "obtainium" "playstore" ])) (lib.attrNames config.android.settings.repo);
  # Google Play's apps go by their package names: an app from releases ({ id; url; }) is not one of them.
  playstoreNotNames = map (app: app.id or "an attribute set") (lib.filter (app: builtins.isAttrs app && !(app ? _id)) (packages.playstore or [ ]));
  releasesApps = packages.obtainium or [ ];
  notReleasesApps = map idOf (lib.filter (app: !isReleasesApp app) releasesApps);
  misplacedReleasesApps = lib.concatLists (lib.mapAttrsToList (repo: list:
    map (app: { inherit repo; inherit (app) id; }) (lib.filter isReleasesApp list)
  ) (removeAttrs packages builtinSources));
  badReleaseUrls = map (app: app.id) (lib.filter (app: isReleasesApp app && builtins.match "https://[^/]+/[^/]+/[^/]+.*" app.url == null) releasesApps);
  badSigners = map (app: app.id) (lib.filter (app: isReleasesApp app && app.signer != null
    && builtins.match "[0-9a-fA-F]{64}|([0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}" app.signer == null) releasesApps);
  twice = lib.concatLists (lib.mapAttrsToList (repo: list:
    map (id: { inherit repo id; }) (lib.unique (lib.filter (id: lib.count (other: other == id) list > 1) list))
  ) ids);
  inSeveral = lib.filter (id: lib.length (lib.filter (list: lib.elem id list) (lib.attrValues ids)) > 1) (lib.unique allIds);
  # The apps that came with the phone to remove (android.apps.<app>.debloat = true).
  debloat = lib.attrNames (lib.filterAttrs (_: app: app.debloat == true) config.android.apps);
  debloatInstalled = lib.filter (id: lib.elem id allIds) debloat;
  # Whether an app sets anything besides debloat.
  anySet = value:
    if builtins.isAttrs value then lib.any anySet (lib.attrValues value)
    else if builtins.isList value then value != [ ]
    else value != null;
  notNames = lib.filter (id: !isPackageName id) allIds;
  wrongRepo = lib.concatLists (lib.mapAttrsToList (repo: list:
    map (app: { inherit repo; id = app._id; from = app._repo; })
      (lib.filter (app: !builtins.isString app && app ? _repo && app._repo != repo) list)
  ) packages);
  badRepoNames = lib.filter (name: builtins.match "[a-z][a-zA-Z0-9]*" name == null) (lib.attrNames repos);
  notHttps = lib.filter (name: !lib.hasPrefix "https://" repos.${name}.address) (lib.attrNames repos);
  badKeys = lib.filter (name: builtins.match "[0-9a-f]{64}" repos.${name}.signingKey == null) (lib.attrNames repos);


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
  imports = [ ./nixpkgs.nix ./system.nix ./lineage.nix ./global.nix ./units.nix ./etc.nix ./tmpfiles.nix ./nixos-compat.nix ./shells.nix ./programs.nix ./nix.nix ./nixos.nix ./home-manager.nix ./services.nix ./syncthing.nix ./snapcast.nix ./davx5.nix ./users.nix ./terminal.nix ./openssh.nix ./adb.nix ./backup.nix ./playstore.nix ./auto-upgrade.nix ./secrets.nix ./specialisation.nix ./version.nix ./renamed.nix ./activation.nix ./build.nix ];

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
          playstore = [ "com.whatsapp" ];
        }
      '';
      description = ''
        Apps installed from each repository (android.packages.<repo>): names from
        pkgs.<repo>, or package names as strings (a name with a Nix keyword in it,
        like "in.example.app", needs one). android.packages.obtainium takes apps from
        their own releases instead, as Obtainium does: { id = …; url = …; }; and
        android.packages.playstore apps from Google Play, as Aurora Store does, by their
        package names as strings (android.playstore has the account). An app can come
        from one source only. An app that came with the phone is removed with
        android.apps.<app>.debloat = true. Nix packages (from nixpkgs) are
        environment.systemPackages, as on NixOS.
      '';
    };

    android.settings.repo = mkOption {
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
      description = ''
        Settings of each repository's apps, by the repository's name
        (android.settings.repo.<repo>), of the apps from their releases
        (android.settings.repo.obtainium) and of those from Google Play
        (android.settings.repo.playstore). The phone's own settings are the other groups of
        android.settings (nix/system.nix, nix/lineage.nix).
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
      "android.packages.${name} / android.settings.repo.${name}: there is no repository named ${name} (android.repos.${name}.url = \"…\";)."
    ) unknownRepos))
    (check (twice == [ ]) (lib.concatMapStringsSep "\n" (t:
      "android.packages.${t.repo} lists ${t.id} more than once: list each app once."
    ) twice))
    (check (inSeveral == [ ]) (lib.concatMapStringsSep "\n" (id:
      "${id} is in more than one list of android.packages ("
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
    (check (lib.elem "playstore" (lib.attrNames repos) == false) "android.repos.playstore: that name is taken by android.packages.playstore (apps from Google Play); name the repository otherwise.")
    (check (playstoreNotNames == [ ]) "android.packages.playstore: ${lib.concatStringsSep ", " playstoreNotNames}: Google Play's apps go by their package names, in quotes, like \"com.whatsapp\" (apps from releases go in android.packages.obtainium).")
    (check (lib.elem "pkgs" (lib.attrNames repos) == false) "android.repos.pkgs: that name is nixpkgs' own (pkgs.pkgs); name the repository otherwise.")
    (check (!(packages ? pkgs)) "android.packages.pkgs: Nix packages (from nixpkgs) go in environment.systemPackages, as on NixOS; they are not on the phone yet.")
    (check (badRepoNames == [ ]) "android.repos: ${lib.concatStringsSep ", " badRepoNames} is not a name like byarchitect (a letter, then letters and digits).")
    (check (notHttps == [ ]) "android.repos: ${lib.concatStringsSep ", " notHttps} needs an https:// address.")
    (check (badKeys == [ ]) (lib.concatMapStringsSep "\n" (name:
      "android.repos.${name}: its fingerprint (the SHA-256 of its signing key: 64 hex digits, in ?fingerprint= or in fingerprint) is missing or not right."
    ) badKeys))
    (check (debloatInstalled == [ ]) (lib.concatMapStringsSep "\n" (id:
      "android.apps.\"${id}\".debloat: ${id} is also in android.packages, to install; pick one."
    ) debloatInstalled))
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
    ++ lib.optional (app.debloat == true && anySet (removeAttrs app [ "debloat" "_module" ]))
      "android.apps.\"${id}\": debloat = true removes it, so its other settings do nothing."
  ) apps);
}
