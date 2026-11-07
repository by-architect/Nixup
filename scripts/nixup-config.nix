# nixup-config create's Nix half: what nixup-config read from a phone (phoneDir, its files: see nixup-config) as a
# configuration.nix, the text. The settings go back through Nixup's own mappings (system.build.settingMappings,
# nix/system.nix): each option gets the value whose stored form is what the phone has now, so a build of the config
# changes nothing. Apps by their package names as strings, so it evaluates without the repositories' indexes.
{ nixupDir, phoneDir, lib ? import <nixpkgs/lib> }:
let
  inherit (lib) concatStringsSep concatMapStringsSep filter head optional optionals;
  nixup = import (/. + nixupDir + "/nix/eval.nix") { inherit lib; modules = [ ]; };

  # --- What was read.
  read = name: let file = /. + phoneDir + "/${name}"; in if builtins.pathExists file then builtins.readFile file else "";
  lines = name: filter (line: line != "") (map (lib.removeSuffix "\r") (lib.splitString "\n" (read name)));
  pairs = re: name: lib.listToAttrs (lib.concatMap (line:
    let m = builtins.match re line; in if m == null then [ ] else [ (lib.nameValuePair (builtins.elemAt m 0) (builtins.elemAt m 1)) ]
  ) (lines name));
  # `settings list` (name=value, the first = splits; a value of null is none) and LineageOS's tables
  # (content query: "Row: 0 name=..., value=...").
  settingsTable = name: lib.filterAttrs (_: value: value != "null") (pairs "([^=]+)=(.*)" name);
  lineageTable = name: pairs "Row: [0-9]+ name=([^,]*), value=(.*)" name;
  tables = {
    system = settingsTable "settings-system.txt";
    secure = settingsTable "settings-secure.txt";
    global = settingsTable "settings-global.txt";
    "lineage.system" = lineageTable "lineage-system.txt";
    "lineage.secure" = lineageTable "lineage-secure.txt";
    "lineage.global" = lineageTable "lineage-global.txt";
  };
  facts = pairs "([A-Za-z]+)=(.*)" "facts.txt";
  fact = name: facts.${name} or "";

  # --- Values, as Nix writes them.
  str = s: lib.replaceStrings [ "\${" ] [ "\\\${" ] (builtins.toJSON s);
  render = v:
    if builtins.isBool v then (if v then "true" else "false")
    else if builtins.isInt v then toString v
    else if builtins.isFloat v then (let whole = builtins.floor v; in if whole * 1.0 == v then "${toString whole}.0" else builtins.toJSON v)
    else if builtins.isList v then "[ ${concatMapStringsSep " " render v} ]"
    else str v;
  number = s: if builtins.match "-?(0|[1-9][0-9]*)([.][0-9]+)?" s != null then builtins.fromJSON s else null;
  isInt = s: builtins.match "-?(0|[1-9][0-9]*)" s != null;

  # --- The settings, back through the mappings.
  mappings = nixup.config.system.build.settingMappings;
  present = m: (tables.${m.table} or { }) ? ${m.key};
  raw = m: tables.${m.table}.${m.key};
  # How the build stores a value: the mapping's own way, else true and false as 1 and 0, the rest as it is.
  stored = m: v: (m.store or (x: if builtins.isBool x then (if x then 1 else 0) else x)) v;
  same = s: r:
    if builtins.isBool s then (if s then "1" else "0") == r
    else if (builtins.isInt s || builtins.isFloat s) then (let n = number r; in n != null && n * 1.0 == s * 1.0)
    else if builtins.isString s then s == r
    else false;
  inner = type: if type.name == "nullOr" then type.nestedTypes.elemType else type;
  enumValues = type:
    let p = type.functor.payload or null; in
    if type.name != "enum" then [ ] else if builtins.isList p then p else if builtins.isAttrs p && p ? values then p.values else [ ];
  # "22:30" from milliseconds or seconds after midnight (Night Light's times, LineageOS's charging control).
  clock = seconds:
    let h = seconds / 3600; m = (seconds - h * 3600) / 60; pad = n: if n < 10 then "0${toString n}" else toString n; in
    if seconds >= 0 && seconds < 86400 && seconds - h * 3600 - m * 60 == 0 then [ "${pad h}:${pad m}" ] else [ ];
  # The values an option could have, for a setting's value r: each of the option's type kept (true and false, its
  # enum's values, r as a number (also in seconds and minutes, as some are stored in ms), r as text, as a time of
  # day, as a list), and the values of its other settings (private DNS: its host).
  candidates = type: others: r:
    let
      t = inner type;
      n = number r;
      ints = optionals (isInt r) (let i = number r; in [ i (i / 1000) (i / 60) ]);
      times = optionals (isInt r) (clock (number r / 1000) ++ clock (number r));
      lists = map (sep: filter (x: x != "") (lib.splitString sep r)) [ "," "|" ";" ];
      all = [ true false ] ++ enumValues t ++ optional (n != null) n ++ ints ++ [ r ] ++ times ++ lists ++ others ++ [ "off" "automatic" ];
    in
    lib.unique (filter (v: v != null && t.check v) all);
  optionAt = name: lib.attrByPath (lib.splitString "." name) null nixup.options;
  byOption = lib.groupBy (m: m.option) mappings;
  # An option's value: the first that stores to every setting of it the phone has, else to its first one.
  valueOf = name: ms:
    let
      have = filter present ms;
      option = optionAt name;
      cands = candidates option.type (map raw (lib.drop 1 have)) (raw (head have));
      fits = v: m: same (stored m v) (raw m);
      strict = filter (v: lib.all (fits v) have) cands;
      loose = filter (v: fits v (head have)) cands;
    in
    if have == [ ] || option == null then null
    else if strict != [ ] then head strict
    else if loose != [ ] then head loose
    else null;
  fromSettings = lib.filterAttrs (_: v: v != null) (lib.mapAttrs valueOf byOption);

  # --- The settings a command tells, not a table (nix/system.nix: density, navigation, language, ...).
  locales = let l = fact "locales"; in if l != "" && l != "null" then l else fact "localeProp";
  localeList = filter (x: x != "") (lib.splitString "," locales);
  density = let forced = fact "densityForced"; over = fact "densityOverride"; d = if isInt forced then forced else over; in
    # One that is the screen's own (One UI keeps its resolution's there) is not set.
    if isInt d && d != fact "densityPhysical" then number d else null;
  navigation = { gestural = "gestures"; threebutton = "threeButton"; twobutton = "twoButton"; }.${fact "navigation"} or null;
  autoZone = tables.global.auto_time_zone or "1";
  fromCommands = lib.filterAttrs (_: v: v != null) {
    "android.settings.display.darkMode" = { yes = true; no = false; }.${fact "darkMode"} or null;
    "android.settings.display.density" = density;
    "android.settings.navigation.mode" = navigation;
    "android.settings.language" = if localeList == [ ] then null else if lib.length localeList == 1 then head localeList else localeList;
    "android.settings.time.timeZone" = if autoZone == "0" && fact "timeZone" != "" then fact "timeZone" else null;
    "android.settings.keyboard.default" =
      if builtins.match "[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+" (fact "inputMethod") != null then fact "inputMethod" else null;
    "android.settings.network.dataSaver" = { enabled = true; disabled = false; }.${fact "dataSaver"} or null;
    "android.settings.location.enable" = { "true" = true; "false" = false; }.${fact "location"} or null;
    "android.adb.port" = let p = fact "adbPort"; in if isInt p && number p > 0 then number p else null;
  };
  settings = fromSettings // fromCommands;
  # By the part of the phone they are about (display, sound, ...), each part with a line of its own.
  partOf = name: let parts = lib.splitString "." name; in
    if lib.take 2 parts == [ "android" "settings" ] && lib.length parts > 3 then builtins.elemAt parts 2
    else if lib.take 2 parts == [ "android" "settings" ] then "other" else builtins.elemAt parts 1;
  settingsText = concatStringsSep "\n" (lib.mapAttrsToList (part: names:
    "  # ${part}\n" + concatMapStringsSep "\n" (name: "  ${name} = ${render settings.${name}};") (lib.sort lib.lessThan names)
  ) (lib.groupBy partOf (lib.attrNames settings)));

  # --- Apps.
  packageOf = line: let m = builtins.match "package:([^ ]+).*" line; in if m == null then null else head m;
  idsIn = name: filter (x: x != null) (map packageOf (lines name));
  # Installed by the user (pm list packages -3 -i: "package:<id>  installer=<installer>").
  userApps = lib.listToAttrs (lib.concatMap (line:
    let m = builtins.match "package:([^ ]+)( +installer=([^ ]*))?" line; in
    if m == null then [ ] else [ (lib.nameValuePair (builtins.elemAt m 0) (let i = builtins.elemAt m 2; in if i == null || i == "null" then "" else i)) ]
  ) (lines "apps-user.txt"));
  # Not the config's to install: Nixup's own app (its module's) and Magisk.
  own = [ "dev.nixup.manager" "com.topjohnwu.magisk" ];
  apps = removeAttrs userApps own;
  # Where each was found (repos.txt: "<id>\t<repository>": in the phone's Nixup indexes, or on F-Droid's or
  # IzzyOnDroid's site).
  repoOf = pairs "([^\t]+)\t(.+)" "repos.txt";
  known = {
    fdroid = null;
    izzyondroid = "https://apt.izzysoft.de/fdroid/repo?fingerprint=3BF0D6ABFEAE2F401707B6D966BE743BF0EEE49C2561B9BA39073711F628937A";
    guardianproject = "https://guardianproject.info/fdroid/repo?fingerprint=B7C2EEFD8DAC7806AF67DFCD92EB18126BC08312A7F2D6F3862E46013C7A6135";
  };
  # The repositories the phone's Nixup has (its profile's android.repos), when it has a build.
  phoneProfile = let text = read "nixup-profile.json"; in if text == "" then { } else builtins.fromJSON text;
  phoneRepos = lib.mapAttrs (_: repo: repo.url + lib.optionalString ((repo.fingerprint or null) != null && !(lib.hasInfix "fingerprint=" repo.url))
    "?fingerprint=${repo.fingerprint}") (phoneProfile.android.repos or { });
  urlOf = repo: if phoneRepos ? ${repo} then phoneRepos.${repo} else known.${repo} or null;
  found = lib.filterAttrs (id: repo: apps ? ${id} && (repo == "fdroid" || urlOf repo != null)) repoOf;
  byRepo = lib.groupBy (id: found.${id}) (lib.attrNames found);
  notFound = filter (id: !(found ? ${id})) (lib.attrNames apps);
  play = [ "com.android.vending" "com.aurora.store" ];
  obtainium = [ "dev.imranr.obtainium" "dev.imranr.obtainium.fdroid" ];
  fromPlay = filter (id: lib.elem apps.${id} play) notFound;
  fromObtainium = filter (id: lib.elem apps.${id} obtainium) notFound;
  elsewhere = filter (id: !(lib.elem apps.${id} (play ++ obtainium))) notFound;
  list = ids: concatMapStringsSep "\n" (id: "    ${str id}") (lib.sort lib.lessThan ids);
  commented = ids: concatMapStringsSep "\n" (id: "  #   ${str id}${lib.optionalString (apps.${id} != "") "   # installed by ${apps.${id}}"}") (lib.sort lib.lessThan ids);
  # Apps whose source is not known here, as comments under what to do with them.
  block = header: ids: optional (ids != [ ]) (concatMapStringsSep "\n" (line: "  # ${line}") header + "\n" + commented ids);
  appsText = concatStringsSep "\n\n" (
    lib.mapAttrsToList (repo: _: "  android.repos.${repo}.url = ${str (urlOf repo)};") (removeAttrs byRepo [ "fdroid" ])
    ++ lib.mapAttrsToList (repo: ids: "  android.packages.${repo} = [\n${list ids}\n  ];") byRepo
    ++ block [ "From Google Play: android.packages.playstore = [ ... ] installs them, with an account (android.playstore;"
               "nixup-play-login gets its token)." ] fromPlay
    ++ block [ "From Obtainium: each is { id = \"...\"; url = \"its releases' page\"; } in android.packages.obtainium." ] fromObtainium
    ++ block [ "Not found in F-Droid, IzzyOnDroid or the phone's Nixup repositories: list each under the"
               "android.packages.<repository> that has it (android.repos.<name>.url for another repository)." ] elsewhere);
  # Apps that came with the phone: removed for the user (debloat), disabled (enabled = false).
  installed = idsIn "apps-system.txt" ++ lib.attrNames userApps;
  # (Not the overlays of the system's look, which are packages too: cmd overlay list names them.)
  overlays = filter (x: x != null) (map (line: let m = builtins.match "[[].[]] ([^ ]+)" line; in if m == null then null else head m) (lines "overlays.txt"));
  removed = filter (id: !(lib.elem id installed) && !(lib.elem id overlays)) (idsIn "apps-system-all.txt");
  disabled = filter (id: lib.elem id installed) (idsIn "apps-disabled.txt");
  # Battery unrestricted by the user (dumpsys deviceidle whitelist: "user,<id>,<uid>"); default apps (roles).
  unrestricted = filter (id: lib.elem id installed) (map (line: builtins.elemAt (lib.splitString "," line) 1)
    (filter (line: lib.hasPrefix "user," line) (lines "deviceidle.txt")));
  roles = lib.filterAttrs (_: id: id != "" && lib.elem id installed) (pairs "([A-Za-z]+)=(.*)" "roles.txt");
  defaultFor = lib.groupBy (role: roles.${role}) (lib.attrNames roles);
  appLine = id: what: "  android.apps.${str id}.${what};";
  systemAppsText = concatStringsSep "\n" (
    map (id: appLine id "debloat = true") (lib.sort lib.lessThan removed)
    ++ map (id: appLine id "enabled = false") (lib.sort lib.lessThan disabled));
  appSettingsText = concatStringsSep "\n" (
    map (id: appLine id "battery = \"unrestricted\"") (lib.sort lib.lessThan unrestricted)
    ++ lib.mapAttrsToList (id: rs: appLine id "defaultFor = ${render (lib.sort lib.lessThan rs)}") defaultFor);

  section = title: text: lib.optionalString (text != "") "\n  # ==== ${title}\n${text}\n";
  source = fact "nixupSource";
  header = [
    "# This phone's config, made by `nixup-config create` from ${fact "model"} (${fact "serial"}, Android ${fact "android"})"
    "# on ${fact "date"}: what the phone had then. Building it changes nothing on that phone; from then on the phone is"
    "# as this file says. A setting left out is not managed: the phone keeps what is set on it."
    "#"
    "# Not read from the phone: apps' permissions and their other settings (but battery and default apps), Wi-Fi"
    "# networks, accounts, the wallpaper and the home screen, and what Nixup has no option for. Nix packages, services"
    "# and users (as NixOS's) are yours to add: Nixup's template has every option (templates/default)."
    "#"
    "# Apply it: nixup-rebuild switch --file configuration.nix (on a PC), or pick it in the app's Config and Build."
  ] ++ optionals (source != "") [ "#" "# The phone's Nixup builds ${source} now (the app's Config): that config may say more than this one." ];
in
concatStringsSep "\n" header + "\n{ config, lib, pkgs, ... }:\n{"
+ section "Apps installed on the phone, by where they come from" appsText
+ section "Apps that came with the phone: not installed for you (removed, or never installed for you, like another\n  # region's: debloat keeps them off either way), and disabled" systemAppsText
+ section "Apps' own settings" appSettingsText
+ section "The phone's settings" settingsText
+ "}\n"
