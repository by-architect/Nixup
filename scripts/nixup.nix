# nixup's Nix half (scripts/nixup, the guided setup): where a config's module file (configuration.nix, a host's
# default.nix, any) writes each option nixup asks about, and what it gives it, so that nixup changes an option where it
# is written (keeping the rest of the file as it is: its comments, its order, its layout) or adds it inside the
# attribute set it belongs to. The file is read as Nix reads it, not searched as text: an option's place is where Nix
# found its definition (builtins.unsafeGetAttrPos), and nothing is evaluated but the file's own attribute sets, so
# pkgs, config and the repositories' indexes are never needed. And the config as a build evaluates it (look, check):
# a flake's configuration (flakeRef, configName: the flake's own inputs and arguments, its other modules), or the file
# alone with Nixup's options (nixupDir).
#
# pathsFile is a JSON file: a list of options, each a list of names ([ [ "android" "updates" "onlyOnUnmetered" ] ... ]).
# What it prints, one fact a line, its fields split by tabs:
#   shape <set|module|other>       the file is a set of options, a module (a function of { config, lib, pkgs, ... }),
#                                  or written another way (lib.mkMerge at its top, ...), whose options nixup leaves alone
#   form <short|full>              its options at its top, or (full) in its config = { … } (with options, imports): the
#                                  places below are then the paths with "config" in front
#   at <i> <depth> <line> <column> where the binding that defines the first <depth> names of path i starts (all of
#                                  them: the option itself; fewer: the set it is in, when that is written)
#   value <i> <kind> <text>        path i's value as written: bool, int, string, null, path, list (of strings, joined by
#                                  the unit separator), items (a list of other things, like pkgs' packages: how many),
#                                  unset, or other (an expression: lib.mkForce …, a variable, …)
#   sibling <i> <line> <column>    path i is not written, but its set has other names: where the last of them (in the
#                                  file) starts, which a new line for it can follow
#   source <name> <count> <ids>    a list of android.packages: how many apps, and their package names (none when they
#                                  are names of pkgs.<repo>, which only the phone's indexes resolve)
# With look = true, each path as the config gives it, through its modules (the file's imports, a flake's other
# modules, Nixup's own):
#   looked yes                     the config evaluated
#   nixup <version>                the Nixup it is built with (a flake's nixup input), when that is known
#   has <i> <yes|no>               that Nixup has the option
#   eff <i> <kind> <text>          its value, as value's (unset: no value, as an account no module adds)
#   from <i> <file>                a file that sets it, a line each: relative to the flake, <input>:<file> in an input's
#                                  (nixup:nix/openssh.nix), or whole
#   prio <i> <priority>            its definitions' priority: 100 a plain one, 1000 lib.mkDefault's, 50 lib.mkForce's
# With info = true, of a flake (flakeRef): its phones (nixupConfigurations) and machines (nixosConfigurations), each
# "phones <names>" and "machines <names>", and "nixup <version>".
# With check = true it prints "ok" instead, once each path evaluates with Nixup's options (the file's other definitions
# of it included, so a second one shows); Nix's error otherwise. In a flake's configuration the file (relFile, in the
# flake) is replaced by its changed copy (file), as a build of the changed flake would have it; with no file, the
# configuration as it is. withPkgs: with nixpkgs' packages (the PC's), for an option that names them (alone).
# missingOk: a path the config has no value at is no error (a user or an account it does not have yet), as for the file
# before nixup changed it.
#
# With packagesFile (a JSON list of package names, like "htop" or "python3Packages.requests"), each package as the
# phone would get it: from nixpkgsPath for aarch64-linux, with no nixpkgs config of this computer's. A line each:
#   nixpkgs <version>              which nixpkgs this was
#   package <name> ok <path>       there, for aarch64-linux: its store path (its first output), for nixup to look up
#                                  in cache.nixos.org, since the phone builds no package itself
#   package <name> missing <names> nixpkgs has none by that name; up to three names like it
#   package <name> notPackage <type>
#                                  it is something else: a set of packages (python3Packages), a function, ...
#   package <name> platform <systems>
#                                  not for aarch64-linux: the systems it is for (Linux ones first, four at most)
#   package <name> broken          marked broken, for aarch64-linux
#   package <name> unfree          an unfree licence: cache.nixos.org never has those
#   package <name> insecure <why>  known to be insecure: its first known vulnerability
#   package <name> hydra           nixpkgs' cache does not build it for aarch64-linux (meta.hydraPlatforms)
#   package <name> error           nixpkgs stops on it (removed, renamed, only for x86, ...): Nix's error says why
{ file ? "", pathsFile ? "", check ? false, nixupDir ? "", withPkgs ? false, missingOk ? false
, look ? false, info ? false, flakeRef ? "", configName ? "", relFile ? ""
, packagesFile ? "", nixpkgsPath ? "", lib ? import <nixpkgs/lib> }:
let
  inherit (lib) concatMapStringsSep concatStringsSep imap0 length take last range;
  wanted = lib.importJSON pathsFile;
  tab = concatStringsSep "\t";
  # Between a list's strings.
  unitSeparator = builtins.fromJSON ''"\u001f"'';
  # One line of text: a tab or a newline would split the fact.
  oneLine = builtins.replaceStrings [ "\t" "\n" "\r" ] [ " " " " " " ];
  try = x: let r = builtins.tryEval x; in if r.success then r.value else null;

  imported = import (/. + file);
  isModule = builtins.isFunction imported;
  # The arguments the module asks for: lib as it is; the others are never read, so they are never evaluated.
  args = lib.mapAttrs (name: _: if name == "lib" then lib else throw "nixup: ${name} is not read here") (builtins.functionArgs imported);
  written = if isModule then try (imported args) else imported;
  # A set as written, not lib.mkIf's or lib.mkForce's.
  plain = v: builtins.isAttrs v && !(v ? _type);
  # A module in its full form has its options in config = { … }: the paths are looked for there.
  full = plain written && (written ? config || written ? options);
  top = written;
  wantedHere = map (path: lib.optional full "config" ++ path) wanted;
  shape = if !(plain written) || (full && !(plain (try written.config))) then "other" else if isModule then "module" else "set";

  # The set the first n names of path are in (the file's own, for n = 1), when it is written as a set.
  parentOf = path: n: if n == 1 then top else try (lib.attrByPath (take (n - 1) path) null top);
  placeOf = path: n:
    let
      parent = parentOf path n;
      name = builtins.elemAt path (n - 1);
      place = builtins.unsafeGetAttrPos name parent;
    in
    if plain parent && parent ? ${name} && place != null && place.file == toString (/. + file) then place else null;

  # A value, as its kind and text (see value above): never forced further than its kind shows.
  describe = v:
    let
      type = try (builtins.typeOf v);
      kind =
        if type == "list" then (if lib.all (x: try (builtins.isString x) == true) v then "list" else "items")
        else if lib.elem type [ "bool" "int" "string" "null" "path" ] && try (builtins.seq v true) == true then type
        else "other";
    in
    {
      inherit kind;
      text = {
        bool = if v then "true" else "false";
        int = toString v;
        string = oneLine v;
        path = toString v;
        list = concatMapStringsSep unitSeparator oneLine v;
        items = toString (length v);
      }.${kind} or "";
    };
  valueOf = path:
    let
      parent = parentOf path (length path);
      name = last path;
    in
    if !(plain parent && parent ? ${name}) then { kind = "unset"; text = ""; } else describe parent.${name};

  # Where the last binding of path's set starts (another name of it), when path itself is not written.
  siblingOf = path:
    let
      parent = parentOf path (length path);
      others = if plain parent then lib.filter (n: n != last path) (builtins.attrNames parent) else [ ];
      found = lib.filter (p: p != null && p.file == toString (/. + file)) (map (n: builtins.unsafeGetAttrPos n parent) others);
      later = a: b: if a == null || b.line > a.line || (b.line == a.line && b.column > a.column) then b else a;
    in
    if (valueOf path).kind == "unset" then lib.foldl' later null found else null;

  # The lists of android.packages, as written (android.packages.fdroid = …, or android.packages = { … }).
  packages = let p = try (lib.attrByPath (lib.optional full "config" ++ [ "android" "packages" ]) null top); in if plain p then p else { };
  appId = x: if builtins.isString x then x else if builtins.isAttrs x && builtins.isString (x.id or null) then x.id else null;
  sourceLine = name:
    let
      list = try packages.${name};
      ids = if builtins.isList list then map (x: try (appId x)) list else [ ];
      known = builtins.isList list && lib.all (id: id != null) ids;
    in
    tab [ "source" name (if builtins.isList list then toString (length list) else "?") (if known then concatStringsSep unitSeparator ids else "") ];

  places = concatStringsSep "\n" (
    [ (tab [ "shape" shape ]) (tab [ "form" (if full then "full" else "short") ]) ]
    ++ lib.optionals (shape != "other") (
      lib.concatLists (imap0 (i: path:
        lib.concatMap (n:
          let place = placeOf path n; in
          lib.optional (place != null) (tab [ "at" (toString i) (toString n) (toString place.line) (toString place.column) ])
        ) (range 1 (length path))
        ++ [ (let v = valueOf path; in tab [ "value" (toString i) v.kind v.text ]) ]
        ++ (let s = siblingOf path; in lib.optional (s != null) (tab [ "sibling" (toString i) (toString s.line) (toString s.column) ]))
      ) wantedHere)
      ++ map sourceLine (builtins.attrNames packages))
  ) + "\n";

  yes = x: try x == true;

  # --- The config as a build evaluates it: a flake's configuration, with the flake's inputs, arguments and other
  # modules (the file replaced by its changed copy, when there is one), or the file alone with Nixup's options.
  flake = builtins.getFlake flakeRef;
  configurations = flake.nixupConfigurations or flake.seraphConfigurations or { };
  alone = withNixpkgs: import (/. + nixupDir + "/nix/eval.nix") {
    inherit lib;
    nixpkgs = if withNixpkgs then <nixpkgs> else null;
    modules = [ (/. + file) ];
  };
  evaluated = withNixpkgs:
    if flakeRef == "" then alone withNixpkgs
    else if file == "" then configurations.${configName}
    else configurations.${configName}.extendModules {
      modules = [ { disabledModules = [ "${toString flake.outPath}/${relFile}" ]; } (/. + file) ];
    };

  # The values as a build reads them, through Nixup's options; a package by its name (its whole closure would be a
  # lot to evaluate, and its name is what can be wrong).
  checked =
    let
      config = (evaluated withPkgs).config;
      force = v:
        if lib.isDerivation v then v.name
        else if builtins.isList v then map force v
        else if builtins.isAttrs v then lib.mapAttrs (_: force) v
        else v;
      values = map (path:
        force (lib.attrByPath path (if missingOk then null else throw "nixup: ${concatStringsSep "." path} is no option of Nixup's") config)
      ) wanted;
    in
    builtins.deepSeq values "ok\n";

  # Which Nixup a flake's configurations are built with: its nixup input's commit (or day).
  nixupOfFlake = let n = try (flake.inputs.nixup or flake.inputs.seraph or null); in
    if n == null then "" else toString (n.shortRev or n.lastModifiedDate or "");

  flakeInfo = concatStringsSep "\n" [
    (tab [ "phones" (concatStringsSep " " (let n = try (builtins.attrNames configurations); in if n == null then [ ] else n)) ])
    (tab [ "machines" (concatStringsSep " " (let n = try (builtins.attrNames (flake.nixosConfigurations or { })); in if n == null then [ ] else n)) ])
    (tab [ "nixup" nixupOfFlake ])
  ] + "\n";

  # Each path as the config gives it, and the files that set it.
  looked =
    let
      c = evaluated true;
      root = if flakeRef != "" then toString flake.outPath + "/" else "";
      # A file of one of the flake's inputs (Nixup's own modules, Home Manager's) as <input>:<file>.
      inputs = if flakeRef != "" then lib.mapAttrsToList (name: input: { inherit name; root = toString input.outPath + "/"; })
          (lib.filterAttrs (_: input: builtins.isAttrs input && input ? outPath) flake.inputs)
        else [ { name = "nixup"; root = nixupDir + "/"; } ];
      where = f:
        let
          s = toString f;
          owner = lib.findFirst (input: lib.hasPrefix input.root s) null inputs;
        in
        if root != "" && lib.hasPrefix root s then lib.removePrefix root s
        else if owner != null then "${owner.name}:${lib.removePrefix owner.root s}"
        else s;
      # The deepest option on a path, and the names below it (in a set of submodules: android.settings.repo, with
      # fdroid.autoupdatePackages below it).
      optionOn = path:
        let
          go = node: rest:
            if lib.isOption node then { option = node; inherit rest; }
            else if rest == [ ] || !(builtins.isAttrs node) || !(node ? ${builtins.head rest}) then null
            else go node.${builtins.head rest} (builtins.tail rest);
        in
        go c.options path;
      facts = i: path:
        let
          at = toString i;
          o = try (optionOn path);
          defs = try (lib.filter (d: o.rest == [ ] || yes (lib.hasAttrByPath o.rest d.value)) o.option.definitionsWithLocations);
          v = describe (lib.attrByPath path null c.config);
        in
        [ (tab [ "has" at (if o != null then "yes" else "no") ]) ]
        ++ lib.optionals (o != null) (
          [ (if yes (lib.hasAttrByPath path c.config) then tab [ "eff" at v.kind v.text ] else tab [ "eff" at "unset" "" ]) ]
          ++ map (d: tab [ "from" at (where d.file) ]) (if builtins.isList defs then defs else [ ])
          ++ [ (tab [ "prio" at (toString (let p = try o.option.highestPrio; in if p == null then 100 else p)) ]) ]);
    in
    concatStringsSep "\n" ([ (tab [ "looked" "yes" ]) ]
      ++ lib.optional (flakeRef != "" && nixupOfFlake != "") (tab [ "nixup" nixupOfFlake ])
      ++ lib.concatLists (imap0 facts wanted)) + "\n";
  # Each package as the phone gets it: built for aarch64-linux, and by nixpkgs' cache (it builds none itself). Every
  # look at a package is tried, since nixpkgs throws on some for this system (an x86 one's dependencies).
  packageChecks =
    let
      pkgs = import nixpkgsPath { system = "aarch64-linux"; config = { }; overlays = [ ]; };
      platform = pkgs.stdenv.hostPlatform;
      yes = x: try x == true;
      # Names like a missing one: its first letter, one letter apart first, then two.
      like = parent: name:
        let
          names = lib.filter (n: n != name && builtins.substring 0 1 n == builtins.substring 0 1 name) (try (builtins.attrNames parent));
          near = lib.filter (lib.strings.levenshteinAtMost 1 name) names;
          nearish = lib.filter (n: !(lib.elem n near) && lib.strings.levenshteinAtMost 2 name n) names;
        in
        if builtins.isAttrs parent then lib.take 3 (near ++ nearish) else [ ];
      systems = ps:
        let strings = lib.filter builtins.isString ps; in
        concatStringsSep " " (lib.take 4 (lib.filter (lib.hasSuffix "-linux") strings ++ lib.filter (s: !(lib.hasSuffix "-linux" s)) strings));
      line = name:
        let
          path = lib.splitString "." name;
          parent = if length path == 1 then pkgs else try (lib.attrByPath (lib.init path) null pkgs);
          leaf = last path;
          got = builtins.tryEval parent.${leaf};
          v = got.value;
          meta = let m = try (v.meta or { }); in if builtins.isAttrs m then m else { };
          vulnerabilities = try (meta.knownVulnerabilities or [ ]);
          hydra = try (meta.hydraPlatforms or null);
          out = builtins.tryEval v.outPath;
          says = what: rest: tab ([ "package" name what ] ++ rest);
        in
        if !(builtins.isAttrs parent && yes (parent ? ${leaf})) then says "missing" [ (concatStringsSep " " (like parent leaf)) ]
        else if !got.success then says "error" [ ]
        else if !(yes (lib.isDerivation v)) then says "notPackage" [ (toString (try (builtins.typeOf v))) ]
        else if (try (lib.meta.availableOn platform v)) == null then says "error" [ ]
        else if !(yes (lib.meta.availableOn platform v)) then says "platform" [ (systems (let ps = try (meta.platforms or [ ]); in if builtins.isList ps then ps else [ ])) ]
        else if yes (meta.broken or false) then says "broken" [ ]
        else if yes (lib.any (l: !(l.free or true)) (lib.toList (meta.license or [ ]))) then says "unfree" [ ]
        else if builtins.isList vulnerabilities && vulnerabilities != [ ] then says "insecure" [ (oneLine (toString (builtins.head vulnerabilities))) ]
        else if builtins.isList hydra && lib.all builtins.isString hydra && !(lib.elem "aarch64-linux" hydra) then says "hydra" [ ]
        else if out.success then says "ok" [ out.value ]
        else says "error" [ ];
    in
    concatStringsSep "\n" ([ (tab [ "nixpkgs" (try (import (nixpkgsPath + "/lib")).version) ]) ] ++ map line (lib.importJSON packagesFile)) + "\n";
in
if packagesFile != "" then packageChecks
else if info then flakeInfo
else if look then looked
else if check then checked
else places
