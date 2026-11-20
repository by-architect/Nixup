# nixup's Nix half (scripts/nixup, the guided setup): where a configuration.nix writes each option nixup asks about,
# and what it gives it, so that nixup changes an option where it is written (keeping the rest of the file as it is: its
# comments, its order, its layout) or adds it inside the attribute set it belongs to. The file is read as Nix reads it,
# not searched as text: an option's place is where Nix found its definition (builtins.unsafeGetAttrPos), and nothing is
# evaluated but the file's own attribute sets, so pkgs, config and the repositories' indexes are never needed.
#
# pathsFile is a JSON file: a list of options, each a list of names ([ [ "android" "updates" "onlyOnUnmetered" ] ... ]).
# What it prints, one fact a line, its fields split by tabs:
#   shape <set|module|other>       the file is a set of options, a module (a function of { config, lib, pkgs, ... }),
#                                  or written another way (lib.mkMerge at its top, ...), whose options nixup leaves alone
#   at <i> <depth> <line> <column> where the binding that defines the first <depth> names of path i starts (all of
#                                  them: the option itself; fewer: the set it is in, when that is written)
#   value <i> <kind> <text>        path i's value as written: bool, int, string, null, path, list (of strings, joined by
#                                  the unit separator), unset, or other (an expression: lib.mkForce …, a variable, …)
#   sibling <i> <line> <column>    path i is not written, but its set has other names: where the last of them (in the
#                                  file) starts, which a new line for it can follow
#   source <name> <count> <ids>    a list of android.packages: how many apps, and their package names (none when they
#                                  are names of pkgs.<repo>, which only the phone's indexes resolve)
# With check = true it prints "ok" instead, once each path evaluates with Nixup's options (the file's other definitions
# of it included, so a second one shows); Nix's error otherwise. withPkgs: with nixpkgs' packages (the PC's), for an
# option that names them (environment.systemPackages). missingOk: a path the config has no value at is no error (a
# user or an account it does not have yet), as for the file before nixup changed it.
{ file, pathsFile, check ? false, nixupDir ? "", withPkgs ? false, missingOk ? false, lib ? import <nixpkgs/lib> }:
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
  top = if isModule then try (imported args) else imported;
  # A set as written, not lib.mkIf's or lib.mkForce's.
  plain = v: builtins.isAttrs v && !(v ? _type);
  shape = if !(plain top) then "other" else if isModule then "module" else "set";

  # The set the first n names of path are in (the file's own, for n = 1), when it is written as a set.
  parentOf = path: n: if n == 1 then top else try (lib.attrByPath (take (n - 1) path) null top);
  placeOf = path: n:
    let
      parent = parentOf path n;
      name = builtins.elemAt path (n - 1);
      place = builtins.unsafeGetAttrPos name parent;
    in
    if plain parent && parent ? ${name} && place != null && place.file == toString (/. + file) then place else null;

  valueOf = path:
    let
      parent = parentOf path (length path);
      name = last path;
      v = parent.${name};
      type = try (builtins.typeOf v);
      kind =
        if !(plain parent && parent ? ${name}) then "unset"
        else if type == "list" then (if lib.all (x: try (builtins.isString x) == true) v then "list" else "other")
        else if lib.elem type [ "bool" "int" "string" "null" "path" ] then type
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
      }.${kind} or "";
    };

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
  packages = let p = try (lib.attrByPath [ "android" "packages" ] null top); in if plain p then p else { };
  appId = x: if builtins.isString x then x else if builtins.isAttrs x && builtins.isString (x.id or null) then x.id else null;
  sourceLine = name:
    let
      list = try packages.${name};
      ids = if builtins.isList list then map (x: try (appId x)) list else [ ];
      known = builtins.isList list && lib.all (id: id != null) ids;
    in
    tab [ "source" name (if builtins.isList list then toString (length list) else "?") (if known then concatStringsSep unitSeparator ids else "") ];

  places = concatStringsSep "\n" (
    [ (tab [ "shape" shape ]) ]
    ++ lib.optionals (shape != "other") (
      lib.concatLists (imap0 (i: path:
        lib.concatMap (n:
          let place = placeOf path n; in
          lib.optional (place != null) (tab [ "at" (toString i) (toString n) (toString place.line) (toString place.column) ])
        ) (range 1 (length path))
        ++ [ (let v = valueOf path; in tab [ "value" (toString i) v.kind v.text ]) ]
        ++ (let s = siblingOf path; in lib.optional (s != null) (tab [ "sibling" (toString i) (toString s.line) (toString s.column) ]))
      ) wanted)
      ++ map sourceLine (builtins.attrNames packages))
  ) + "\n";

  # The values as a build reads them, through Nixup's options; a package by its name (its whole closure would be a
  # lot to evaluate, and its name is what can be wrong).
  checked =
    let
      nixup = import (/. + nixupDir + "/nix/eval.nix") {
        inherit lib;
        nixpkgs = if withPkgs then <nixpkgs> else null;
        modules = [ (/. + file) ];
      };
      force = v:
        if lib.isDerivation v then v.name
        else if builtins.isList v then map force v
        else if builtins.isAttrs v then lib.mapAttrs (_: force) v
        else v;
      values = map (path:
        force (lib.attrByPath path (if missingOk then null else throw "nixup: ${concatStringsSep "." path} is no option of Nixup's") nixup.config)
      ) wanted;
    in
    builtins.deepSeq values "ok\n";
in
if check then checked else places
