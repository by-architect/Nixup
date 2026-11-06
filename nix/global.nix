# Options with NixOS's own names, so that one module can set them on a phone and on a NixOS machine
# alike: each means here what it means there, with NixOS's type. Where a phone needs another
# default, the option says so (NixOS names a new machine "nixos"; a phone keeps the name it has).
# They come down to the phone's own settings as those settings' defaults, so an android.settings
# option set too wins: networking.hostName is android.settings.deviceName unless that is set.
#
# environment.systemPackages are Nix packages the phone fetches (nix/nixpkgs.nix). The services,
# with NixOS's names too, are in nix/units.nix and nix/services.nix; the scripts
# (system.activationScripts, userActivationScripts, preSwitchChecks) in nix/activation.nix; the shells'
# environment (environment.variables, extraInit, shellAliases, ..., programs.bash, zsh, fish) in nix/shells.nix.
{ config, lib, options, ... }:
let
  inherit (lib) mkOption types;

  hostName = config.networking.hostName;
  # Whether the config gives the phone a name (its default is only for NixOS's modules that read one).
  hostNameSet = options.networking.hostName.highestPrio < (lib.mkOptionDefault null).priority;
  locale = config.i18n.defaultLocale;

  # A locale as NixOS writes one (language[_TERRITORY][.codeset][@modifier], like "tr_TR.UTF-8") as
  # the language tag Android takes ("tr-TR"); @latin and @cyrillic name the script ("sr-Latn-RS"),
  # other modifiers (@euro) change nothing. "C" and "POSIX" name no language: null.
  noLanguage = locale != null && builtins.match "(C|POSIX)([.].*)?" locale != null;
  parts = if locale == null then null
    else builtins.match "([a-z]{2,3})(_([A-Z]{2}|[0-9]{3}))?([.][^@]*)?(@([A-Za-z]+))?" locale;
  part = n: builtins.elemAt parts n;
  script = if part 5 == null then null else { latin = "Latn"; cyrillic = "Cyrl"; }.${part 5} or null;
  language =
    if parts == null then null
    else lib.concatStringsSep "-" (lib.filter (p: p != null) [ (part 0) script (part 2) ]);
in
{
  options = {
    networking.hostName = mkOption {
      # NixOS's type: a DNS label (letters, digits and -, starting and ending with a letter or digit,
      # at most 63 characters), or "".
      type = types.strMatching "^$|^[[:alnum:]]([[:alnum:]_-]{0,61}[[:alnum:]])?$";
      default = "localhost";
      example = "phone";
      description = ''
        The phone's name, as NixOS's networking.hostName: letters, digits and -, starting and
        ending with a letter or digit, at most 63 characters. It is the phone's device name
        (android.settings.deviceName, unless that is set; About phone shows it, and other devices
        over Bluetooth) and its name on the tailnet (services.tailscale). Not set, the phone keeps the
        name it has, and NixOS's modules that read a name (Apache's ServerName) get "localhost", the
        name of the phone's kernel (NixOS's default would be "nixos").
      '';
    };

    time.timeZone = mkOption {
      type = types.nullOr (types.addCheck types.str (zone: !lib.hasInfix " " zone)) // {
        description = "null or string without spaces";
      };
      default = null;
      example = "Europe/Istanbul";
      description = ''
        The time zone, as NixOS's time.timeZone: a name from the tz database, like
        "Europe/Istanbul". It is android.settings.time.timeZone, unless that is set (and so turns
        automaticTimeZone off). null: not managed, the phone keeps the zone it takes now.
      '';
    };

    i18n.defaultLocale = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "tr_TR.UTF-8";
      description = ''
        The language, as NixOS's i18n.defaultLocale: a locale like "tr_TR.UTF-8", which is
        android.settings.language as "tr-TR", unless that is set (it can also give several
        languages, in order). "C" names no language, so it changes nothing. null: not managed
        (NixOS's default would be "en_US.UTF-8").
      '';
    };

    environment.systemPackages = mkOption {
      type = types.listOf types.package;
      default = [ ];
      example = lib.literalExpression "with pkgs; [ htop rsync ]";
      description = ''
        Nix packages (from nixpkgs) for the phone, as NixOS's environment.systemPackages: each build
        fetches them from cache.nixos.org into Nixup's store, for the phone's own commands to use.
      '';
    };

  };

  config = {
    android.settings = {
      deviceName = lib.mkIf (hostNameSet && hostName != "") (lib.mkDefault hostName);
      time.timeZone = lib.mkIf (config.time.timeZone != null) (lib.mkDefault config.time.timeZone);
      language = lib.mkIf (language != null) (lib.mkDefault language);
    };

    assertions = [
      {
        assertion = locale == null || noLanguage || parts != null;
        message = "i18n.defaultLocale: \"${toString locale}\" is not a locale like \"tr_TR.UTF-8\" (or set android.settings.language, like \"tr-TR\").";
      }
    ];

    warnings = lib.optional (locale != null && noLanguage)
      "i18n.defaultLocale: \"${locale}\" names no language, so the phone's stays as it is.";
  };
}
