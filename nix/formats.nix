# Settings files as nixpkgs' pkgs.formats makes them, without the programs it makes some with: JSON as
# builtins.toJSON writes it (nixpkgs runs jq), YAML as JSON (which is YAML too, and which YAML's readers read;
# nixpkgs runs remarshal, which brings Python: 250 MB on a phone), TOML by a writer of its own (remarshal too). Each
# format's type is nixpkgs' own, and each file is a small one the phone builds (see nix/build.nix).
{ lib, pkgs, formats }:
let
  # TOML (toml.io, v1.0): a table's keys, then its tables ([a.b]) and lists of tables ([[a.b]]); nulls, which TOML
  # has not got, left out.
  toTOML = value:
    let
      isTable = v: builtins.isAttrs v && !lib.isDerivation v;
      isTables = v: builtins.isList v && v != [ ] && lib.all isTable v;
      key = k: if builtins.match "[A-Za-z0-9_-]+" k != null then k else builtins.toJSON k;
      dotted = path: lib.concatMapStringsSep "." key path;
      float = f: let text = builtins.toJSON f; in
        if lib.hasInfix "." text || lib.hasInfix "e" text || lib.hasInfix "n" text then text else "${text}.0";
      inline = v:
        if builtins.isBool v then lib.boolToString v
        else if builtins.isInt v then toString v
        else if builtins.isFloat v then float v
        else if builtins.isList v then "[ ${lib.concatMapStringsSep ", " inline v} ]"
        else if isTable v then
          "{ ${lib.concatStringsSep ", " (lib.mapAttrsToList (k: x: "${key k} = ${inline x}") (lib.filterAttrs (_: x: x != null) v))} }"
        else builtins.toJSON (toString v);
      body = path: attrs:
        let
          present = lib.filterAttrs (_: v: v != null) attrs;
          plain = lib.filterAttrs (_: v: !isTable v && !isTables v) present;
          tables = lib.filterAttrs (_: v: isTable v) present;
          lists = lib.filterAttrs (_: v: isTables v) present;
          header = k: dotted (path ++ [ k ]);
        in
        lib.concatStrings (lib.mapAttrsToList (k: v: "${key k} = ${inline v}\n") plain)
        + lib.concatStrings (lib.mapAttrsToList (k: v: "\n[${header k}]\n${body (path ++ [ k ]) v}") tables)
        + lib.concatStrings (lib.mapAttrsToList (k: vs:
            lib.concatMapStrings (v: "\n[[${header k}]]\n${body (path ++ [ k ]) v}") vs) lists);
    in
    body [ ] value;

  json = name: value: pkgs.writeText name (builtins.toJSON value);
  # A format of nixpkgs with another generate.
  generating = format: generate: args: format args // { inherit generate; };
in
formats // {
  json = generating formats.json json;
  yaml = generating formats.yaml json;
  toml = generating formats.toml (name: value: pkgs.writeText name (toTOML value));
}
// lib.optionalAttrs (formats ? yaml_1_1) { yaml_1_1 = generating formats.yaml_1_1 json; }
// lib.optionalAttrs (formats ? yaml_1_2) { yaml_1_2 = generating formats.yaml_1_2 json; }
