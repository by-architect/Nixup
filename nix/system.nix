# The phone's own settings. The options with plain names (android.display.fontScale, ...) come
# down to Android's settings tables, android.rawSettings, where any other setting can be set too
# (as `settings put` would); a few take a command instead (display density, navigation mode,
# language, time zone, Data Saver, location). An option left null is not managed: it stays as the
# user sets it, and one taken out of the config gets back the value it had before the module.
{ config, lib, ... }:
let
  inherit (lib) mkOption mkIf mkMerge types;
  cfg = config.android;

  # An option that is not managed while null.
  option = type: description: mkOption { type = types.nullOr type; default = null; inherit description; };
  # A switch stored as 1 or 0.
  bit = b: if b then 1 else 0;
  # A setting from an option, while the option is set.
  setting = table: key: value: f: mkIf (value != null) { ${table}.${key} = f value; };
  same = x: x;

  settingValue = types.oneOf [ types.str types.int types.float types.bool ] // {
    description = "string, number or boolean";
  };
  settingsTable = name: mkOption {
    type = types.attrsOf settingValue;
    default = { };
    example = lib.literalExpression ''{ "screen_off_timeout" = 60000; }'';
    description = ''
      Android's ${name} settings, by their own names (as `settings put ${name} <name> <value>`);
      true and false are stored as 1 and 0. The plain options (android.display.fontScale, ...)
      set some of these; setting one of the same names here too, to another value, is an error.
    '';
  };

  # "22:30" as milliseconds after midnight, how Android keeps Night Light's times.
  clock = types.strMatching "([01][0-9]|2[0-3]):[0-5][0-9]";
  millisOfDay = time:
    let parts = map lib.toIntBase10 (lib.splitString ":" time);
    in (builtins.elemAt parts 0 * 60 + builtins.elemAt parts 1) * 60000;

  languages = if builtins.isString cfg.language then [ cfg.language ] else if cfg.language == null then [ ] else cfg.language;
  badLanguages = lib.filter (tag: builtins.match "[a-z]{2,3}(-[A-Za-z0-9]{2,8})*" tag == null) languages;
  dns = cfg.network.privateDns;
  dnsHost = dns != null && dns != "off" && dns != "automatic";
  inRange = low: high: value: value == null || (value >= low && value <= high);
  badRawKeys = lib.concatLists (lib.mapAttrsToList (table: settings:
    map (key: "${table}.${key}") (lib.filter (key: builtins.match "[A-Za-z0-9_.:-]+" key == null) (lib.attrNames settings))
  ) cfg.rawSettings);
  check = assertion: message: { inherit assertion message; };
in
{
  options.android = {
    rawSettings = {
      system = settingsTable "system";
      secure = settingsTable "secure";
      global = settingsTable "global";
    };

    display = {
      fontScale = option types.number "Font size: 1.0 is the normal size, 1.15 larger, 0.85 smaller (0.5 to 2).";
      boldText = option types.bool "Bold text everywhere (Android 12 and newer).";
      density = option types.ints.positive ''
        Display size, in dots per inch (`wm density`): a smaller number fits more on the screen. The
        phone's own is its physical density (`wm density` shows it).
      '';
      screenTimeout = option types.ints.positive "Seconds before the screen turns off when the phone is not used.";
      autoRotate = option types.bool "Turn the screen with the phone.";
      autoBrightness = option types.bool "Adaptive brightness: the phone sets the brightness by the light around it.";
      peakRefreshRate = option types.number "Highest refresh rate, in Hz (like 120), on a screen that has more than one.";
      minRefreshRate = option types.number "Lowest refresh rate, in Hz: the same as peakRefreshRate keeps the screen at that rate.";
      alwaysOn = option types.bool "Always-on display: the time and notifications on the screen while the phone is locked.";
      wakeForNotifications = option types.bool "Wake the screen for new notifications.";
      doubleTapToWake = option types.bool "Double-tap the screen to wake it, on a phone that can.";
      liftToWake = option types.bool "Wake the screen when the phone is picked up, on a phone that can.";
      nightLight = {
        schedule = option (types.enum [ "none" "sunsetToSunrise" "custom" ]) ''
          When Night Light (a warmer screen) turns on by itself: never, from sunset to sunrise, or
          from `from` to `to`.
        '';
        from = option clock "When Night Light turns on, like \"22:00\" (with schedule = \"custom\").";
        to = option clock "When Night Light turns off, like \"06:30\" (with schedule = \"custom\").";
        temperature = option types.ints.positive "Night Light's colour, in kelvin: lower is warmer (phones take about 2600 to 4100).";
      };
    };

    navigation.mode = option (types.enum [ "gestures" "threeButton" "twoButton" ]) ''
      How to go home and back: swipe gestures, or a bar with three buttons (two buttons: up to
      Android 11, and some ROMs).
    '';

    sound = {
      touchSounds = option types.bool "Sounds for taps.";
      touchVibration = option types.bool "Vibration for taps and presses (haptic feedback).";
      dialpadTones = option types.bool "Tones for the dial pad.";
      screenLockSound = option types.bool "A sound when the screen locks and unlocks.";
      chargingSound = option types.bool "A sound when charging starts.";
      chargingVibration = option types.bool "A vibration when charging starts.";
    };

    time = {
      automatic = option types.bool "Take the time from the network.";
      automaticTimeZone = option types.bool "Take the time zone from the network or location (false when timeZone is set).";
      timeZone = option types.str "The time zone, like \"Europe/Istanbul\" (it needs automaticTimeZone off, which it then is by default).";
      format24h = option types.bool "24-hour time (false: 12-hour).";
    };

    language = option (types.either types.str (types.listOf types.str)) ''
      The phone's language, like "tr-TR", or its languages in order of preference, like
      [ "tr-TR" "en-US" ] (apps take the first one they have).
    '';

    network = {
      privateDns = option types.str ''
        Private DNS: "off", "automatic" (encrypted when the network's DNS can), or the hostname of
        a provider, like "dns.quad9.net".
      '';
      dataSaver = option types.bool "Data Saver: apps use mobile data in the background only when let (android.apps.<app>.network).";
      wifiScanning = option types.bool "Let apps and services look for Wi-Fi networks for location even while Wi-Fi is off.";
      bluetoothScanning = option types.bool "Let apps and services look for Bluetooth devices for location even while Bluetooth is off.";
      captivePortalCheck = option types.bool ''
        Check each network for a sign-in page (as in hotels), which asks a server of the ROM's
        maker; false never checks.
      '';
    };

    location.enable = option types.bool "Location for the whole phone, on or off.";

    lockScreen = {
      notifications = option types.bool "Show notifications on the lock screen.";
      sensitiveNotifications = option types.bool "Show what notifications say on the lock screen (false: only that there is one).";
      lockAfter = option types.ints.unsigned "Seconds after the screen turns off before the phone locks (with a screen lock set).";
    };

    notifications = {
      dots = option types.bool "Dots on app icons that have notifications.";
      bubbles = option types.bool "Conversations can show as floating bubbles.";
      snooze = option types.bool "Notifications can be snoozed.";
      history = option types.bool "Keep a history of notifications.";
    };

    power = {
      batterySaverAt = option (types.ints.between 0 100) "Turn Battery Saver on by itself at this battery level, in percent (0: never).";
      adaptiveBattery = option types.bool "Adaptive Battery: limit the apps not used often.";
    };

    developer = {
      enable = option types.bool "Show Developer options in Settings.";
      usbDebugging = option types.bool ''
        USB debugging (adb). False turns it off, also for the computer the config may be
        pushed from with adb.
      '';
      stayAwake = option types.bool "Keep the screen on while charging.";
      animationScale = option types.number "Speed of animations: 1 is normal, 0.5 twice as fast, 0 off (window, transition and animator scales).";
    };

    accessibility = {
      colorInversion = option types.bool "Invert the screen's colours.";
      highContrastText = option types.bool "High contrast text.";
    };

    statusBar.hiddenIcons = option (types.listOf types.str) ''
      Icons the status bar does not show, by their names in Android, like [ "rotate" "volume"
      "alarm_clock" "headset" ] (the names a phone has differ).
    '';

    deviceName = option types.str "The phone's name, shown in About phone and to other devices.";
  };

  config = {
    android.time.automaticTimeZone = mkIf (cfg.time.timeZone != null) (lib.mkDefault false);

    android.rawSettings = mkMerge [
      (setting "system" "font_scale" cfg.display.fontScale same)
      (setting "secure" "font_weight_adjustment" cfg.display.boldText (b: if b then 300 else 0))
      (setting "system" "screen_off_timeout" cfg.display.screenTimeout (s: s * 1000))
      (setting "system" "accelerometer_rotation" cfg.display.autoRotate bit)
      (setting "system" "screen_brightness_mode" cfg.display.autoBrightness bit)
      (setting "system" "peak_refresh_rate" cfg.display.peakRefreshRate (hz: hz * 1.0))
      (setting "system" "min_refresh_rate" cfg.display.minRefreshRate (hz: hz * 1.0))
      (setting "secure" "doze_always_on" cfg.display.alwaysOn bit)
      (setting "secure" "doze_enabled" cfg.display.wakeForNotifications bit)
      (setting "secure" "double_tap_to_wake" cfg.display.doubleTapToWake bit)
      (setting "secure" "wake_gesture_enabled" cfg.display.liftToWake bit)
      (setting "secure" "night_display_auto_mode" cfg.display.nightLight.schedule
        (s: { none = 0; custom = 1; sunsetToSunrise = 2; }.${s}))
      (setting "secure" "night_display_custom_start_time" cfg.display.nightLight.from millisOfDay)
      (setting "secure" "night_display_custom_end_time" cfg.display.nightLight.to millisOfDay)
      (setting "secure" "night_display_color_temperature" cfg.display.nightLight.temperature same)

      (setting "system" "sound_effects_enabled" cfg.sound.touchSounds bit)
      (setting "system" "haptic_feedback_enabled" cfg.sound.touchVibration bit)
      (setting "system" "dtmf_tone" cfg.sound.dialpadTones bit)
      (setting "system" "lockscreen_sounds_enabled" cfg.sound.screenLockSound bit)
      (setting "secure" "charging_sounds_enabled" cfg.sound.chargingSound bit)
      (setting "secure" "charging_vibration_enabled" cfg.sound.chargingVibration bit)

      (setting "global" "auto_time" cfg.time.automatic bit)
      (setting "global" "auto_time_zone" cfg.time.automaticTimeZone bit)
      (setting "system" "time_12_24" cfg.time.format24h (b: if b then "24" else "12"))

      (setting "global" "private_dns_mode" dns (d: if d == "off" then "off" else if d == "automatic" then "opportunistic" else "hostname"))
      (mkIf dnsHost { global.private_dns_specifier = dns; })
      (setting "global" "wifi_scan_always_enabled" cfg.network.wifiScanning bit)
      (setting "global" "ble_scan_always_enabled" cfg.network.bluetoothScanning bit)
      (setting "global" "captive_portal_mode" cfg.network.captivePortalCheck bit)

      (setting "secure" "lock_screen_show_notifications" cfg.lockScreen.notifications bit)
      (setting "secure" "lock_screen_allow_private_notifications" cfg.lockScreen.sensitiveNotifications bit)
      (setting "secure" "lock_screen_lock_after_timeout" cfg.lockScreen.lockAfter (s: s * 1000))

      (setting "secure" "notification_badging" cfg.notifications.dots bit)
      (setting "secure" "notification_bubbles" cfg.notifications.bubbles bit)
      (setting "secure" "show_notification_snooze" cfg.notifications.snooze bit)
      (setting "secure" "notification_history_enabled" cfg.notifications.history bit)

      (setting "global" "low_power_trigger_level" cfg.power.batterySaverAt same)
      # Battery Saver by the battery level, not by Android's guess of when it runs out.
      (mkIf (cfg.power.batterySaverAt != null) { global.automatic_power_save_mode = 0; })
      (setting "global" "adaptive_battery_management_enabled" cfg.power.adaptiveBattery bit)

      (setting "global" "development_settings_enabled" cfg.developer.enable bit)
      (setting "global" "adb_enabled" cfg.developer.usbDebugging bit)
      # Charging by AC, USB and wireless (1 + 2 + 4).
      (setting "global" "stay_on_while_plugged_in" cfg.developer.stayAwake (b: if b then 7 else 0))
      (setting "global" "window_animation_scale" cfg.developer.animationScale (x: x * 1.0))
      (setting "global" "transition_animation_scale" cfg.developer.animationScale (x: x * 1.0))
      (setting "global" "animator_duration_scale" cfg.developer.animationScale (x: x * 1.0))

      (setting "secure" "accessibility_display_inversion_enabled" cfg.accessibility.colorInversion bit)
      (setting "secure" "high_text_contrast_enabled" cfg.accessibility.highContrastText bit)

      (setting "secure" "icon_blacklist" cfg.statusBar.hiddenIcons (lib.concatStringsSep ","))
      (setting "global" "device_name" cfg.deviceName same)
    ];

    assertions = [
      (check (badRawKeys == [ ]) "android.rawSettings: ${lib.concatStringsSep ", " badRawKeys} is not a setting's name (letters, digits, _ . : -).")
      (check (inRange 0.5 2 cfg.display.fontScale) "android.display.fontScale: ${toString cfg.display.fontScale} is not between 0.5 and 2.")
      (check (inRange 72 1000 cfg.display.density) "android.display.density: ${toString cfg.display.density} dpi is not a display size (phones have about 300 to 600).")
      (check (inRange 5 86400 cfg.display.screenTimeout) "android.display.screenTimeout: give the seconds, 5 or more.")
      (check (inRange 0 10 cfg.developer.animationScale) "android.developer.animationScale: ${toString cfg.developer.animationScale} is not between 0 and 10.")
      (check (cfg.display.nightLight.schedule == "custom" || (cfg.display.nightLight.from == null && cfg.display.nightLight.to == null))
        "android.display.nightLight: from and to are the times of schedule = \"custom\".")
      (check (!(cfg.time.timeZone != null && cfg.time.automaticTimeZone == true))
        "android.time: timeZone is set by hand, so automaticTimeZone cannot be true; pick one.")
      (check (cfg.time.timeZone == null || builtins.match "[A-Za-z_]+(/[A-Za-z0-9_+-]+)*" cfg.time.timeZone != null)
        "android.time.timeZone: \"${toString cfg.time.timeZone}\" is not a time zone like \"Europe/Istanbul\".")
      (check (badLanguages == [ ]) "android.language: ${lib.concatStringsSep ", " badLanguages} is not a language like \"tr-TR\".")
      (check (cfg.language == null || languages != [ ]) "android.language: give at least one language.")
      (check (!dnsHost || builtins.match "([A-Za-z0-9-]+[.])+[A-Za-z]{2,}" dns != null)
        "android.network.privateDns: \"${toString dns}\" is not \"off\", \"automatic\" or a hostname like \"dns.quad9.net\".")
      (check (cfg.statusBar.hiddenIcons == null || lib.all (icon: builtins.match "[A-Za-z0-9_.-]+" icon != null) cfg.statusBar.hiddenIcons)
        "android.statusBar.hiddenIcons: an icon's name is letters, digits, _ . -, like \"alarm_clock\".")
      (check (cfg.deviceName == null || cfg.deviceName != "") "android.deviceName: the name cannot be empty.")
    ];
  };
}
