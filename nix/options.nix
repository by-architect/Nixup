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
  idOf = app: if builtins.isString app then app else app._id;

  repos = config.android.repos;
  packages = config.android.packages;
  ids = lib.mapAttrs (_: map idOf) packages;
  allIds = lib.concatLists (lib.attrValues ids);
  unknownRepos = lib.filter (name: !(repos ? ${name})) (lib.unique (lib.attrNames packages ++ lib.attrNames config.android.settings));
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
      type = types.attrsOf (types.listOf (types.either types.str repoApp));
      default = { };
      example = lib.literalExpression "{ fdroid = with pkgs.fdroid; [ com.byarchitect.dejavu ]; }";
      description = ''
        Apps installed from each repository (android.packages.<repo>): names from
        pkgs.<repo>, or package names as strings (a name with a Nix keyword in it,
        like "in.example.app", needs one). An app can come from one repository only.
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
    (check (badRepoNames == [ ]) "android.repos: ${lib.concatStringsSep ", " badRepoNames} is not a name like byarchitect (a letter, then letters and digits).")
    (check (notHttps == [ ]) "android.repos: ${lib.concatStringsSep ", " notHttps} needs an https:// address.")
    (check (badKeys == [ ]) (lib.concatMapStringsSep "\n" (name:
      "android.repos.${name}: its fingerprint (the SHA-256 of its signing key: 64 hex digits, in ?fingerprint= or in fingerprint) is missing or not right."
    ) badKeys))
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
  ) apps);
}
