# The app's terminal, as Nix-on-Droid's terminal module sets its own (options and their meaning from
# nix-on-droid's modules/terminal.nix, https://github.com/nix-community/nix-on-droid, MIT License; see NOTICE):
# its font and its colors. The engine writes them for the app (/data/adb/nixup/etc/nixup/terminal.json), which
# takes them each time the terminal opens; the font's package is fetched with the build's other Nix packages.
{ config, lib, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.terminal;

  validColornames = [ "background" "foreground" "cursor" ] ++ map (n: "color${toString n}") (lib.range 0 15);

  # The font as a path in the store, and the package that holds it (the phone fetches that).
  font = if cfg.font == null then null else builtins.unsafeDiscardStringContext "${cfg.font}";
  fontPackage = if font == null then null else builtins.match "(${builtins.storeDir}/[^/]+).*" font;
in
{
  options.terminal = {
    font = mkOption {
      type = types.nullOr (types.either types.path types.str);
      default = null;
      example = lib.literalExpression ''"''${pkgs.jetbrains-mono}/share/fonts/truetype/JetBrainsMono-Regular.ttf"'';
      description = ''
        Font used for the terminal: a TrueType or OpenType file of a Nix package (cache.nixos.org has it), which the
        phone fetches; null: Android's monospace.
      '';
    };
    colors = mkOption {
      type = types.lazyAttrsOf types.str;
      default = { };
      example = lib.literalExpression ''
        {
          background = "#000000";
          foreground = "#FFFFFF";
          cursor = "#FFFFFF";
        }
      '';
      description = ''
        Colorscheme used for the terminal, as #RRGGBB. Acceptable attribute names are: background, foreground,
        cursor and color0-color15 (the 16 colors programs name; color0-7 dim, color8-15 bright).
      '';
    };
  };

  config = {
    assertions = [
      {
        assertion = lib.all (name: lib.elem name validColornames) (lib.attrNames cfg.colors);
        message = "terminal.colors only accepts the following attributes: background, foreground, cursor and color0-color15.";
      }
      {
        assertion = !(builtins.isPath cfg.font);
        message = ''
          terminal.font: a file of the config's folder does not reach the phone (it fetches Nix packages from
          cache.nixos.org): use a font of a package, like "''${pkgs.jetbrains-mono}/share/fonts/truetype/JetBrainsMono-Regular.ttf".
        '';
      }
      {
        assertion = font == null || fontPackage != null;
        message = "terminal.font: ${toString font} is not in a Nix package (a path in ${builtins.storeDir}).";
      }
    ];

    system.build.terminal = { inherit font; inherit (cfg) colors; };
    system.build.terminalStorePaths = lib.optionals (fontPackage != null) fontPackage;
  };
}
