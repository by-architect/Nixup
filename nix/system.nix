# The phone's own settings, under android.settings. The options with plain names
# (android.settings.display.fontScale, ...) come down to Android's settings tables,
# android.settings.raw, where any other setting can be set too
# (as `settings put` would); a few take a command instead (display density, navigation mode,
# language, time zone, keyboard, Data Saver, location). An option left null is not managed: it
# stays as the user sets it, and one taken out of the config gets back the value it had before.
#
# These are Android's own settings, which phones of every make have. A phone that lacks what one
# needs (an always-on display, a second refresh rate, a vibrator, a newer Android, ...) is told at
# build time, and the setting is left as it is. LineageOS's own settings are in nix/lineage.nix.
{ config, lib, ... }:
let
  inherit (lib) mkOption mkIf mkMerge types;
  cfg = config.android.settings;

  # An option that is not managed while null.
  option = type: description: mkOption { type = types.nullOr type; default = null; inherit description; };
  bit = b: if b then 1 else 0;
  same = x: x;
  float = x: x * 1.0;
  enum = values: value: values.${value};

  settingValue = types.oneOf [ types.str types.int types.float types.bool ] // {
    description = "string, number or boolean";
  };
  settingsTable = name: mkOption {
    type = types.attrsOf settingValue;
    default = { };
    example = lib.literalExpression ''{ "screen_off_timeout" = 60000; }'';
    description = ''
      ${name} settings, by their own names; true and false are stored as 1 and 0. The plain
      options set some of these; setting one of the same names here too, to another value, is
      an error.
    '';
  };

  # "22:30" as milliseconds after midnight, how Android keeps Night Light's times.
  clock = types.strMatching "([01][0-9]|2[0-3]):[0-5][0-9]";
  millisOfDay = time:
    let parts = map lib.toIntBase10 (lib.splitString ":" time);
    in (builtins.elemAt parts 0 * 60 + builtins.elemAt parts 1) * 60000;

  # Each plain option, the setting it comes down to (table and name), and how its value is stored.
  # Several options may set one setting, and one option several settings.
  mappings = [
    { option = "display.fontScale"; table = "system"; key = "font_scale"; value = cfg.display.fontScale; }
    { option = "display.boldText"; table = "secure"; key = "font_weight_adjustment"; value = cfg.display.boldText; store = b: if b then 300 else 0; }
    { option = "display.screenTimeout"; table = "system"; key = "screen_off_timeout"; value = cfg.display.screenTimeout; store = s: s * 1000; }
    { option = "display.autoRotate"; table = "system"; key = "accelerometer_rotation"; value = cfg.display.autoRotate; }
    { option = "display.autoBrightness"; table = "system"; key = "screen_brightness_mode"; value = cfg.display.autoBrightness; }
    { option = "display.peakRefreshRate"; table = "system"; key = "peak_refresh_rate"; value = cfg.display.peakRefreshRate; store = float; }
    { option = "display.minRefreshRate"; table = "system"; key = "min_refresh_rate"; value = cfg.display.minRefreshRate; store = float; }
    { option = "display.colorMode"; table = "system"; key = "display_color_mode"; value = cfg.display.colorMode;
      store = enum { natural = 0; boosted = 1; saturated = 2; adaptive = 3; }; }
    { option = "display.alwaysOn"; table = "secure"; key = "doze_always_on"; value = cfg.display.alwaysOn; }
    { option = "display.wakeForNotifications"; table = "secure"; key = "doze_enabled"; value = cfg.display.wakeForNotifications; }
    { option = "display.doubleTapToWake"; table = "secure"; key = "double_tap_to_wake"; value = cfg.display.doubleTapToWake; }
    { option = "display.liftToWake"; table = "secure"; key = "wake_gesture_enabled"; value = cfg.display.liftToWake; }
    { option = "display.nightLight.schedule"; table = "secure"; key = "night_display_auto_mode"; value = cfg.display.nightLight.schedule;
      store = enum { none = 0; custom = 1; sunsetToSunrise = 2; }; }
    { option = "display.nightLight.from"; table = "secure"; key = "night_display_custom_start_time"; value = cfg.display.nightLight.from; store = millisOfDay; }
    { option = "display.nightLight.to"; table = "secure"; key = "night_display_custom_end_time"; value = cfg.display.nightLight.to; store = millisOfDay; }
    { option = "display.nightLight.temperature"; table = "secure"; key = "night_display_color_temperature"; value = cfg.display.nightLight.temperature; }
    { option = "display.screenSaver.enable"; table = "secure"; key = "screensaver_enabled"; value = cfg.display.screenSaver.enable; }
    { option = "display.screenSaver.whileCharging"; table = "secure"; key = "screensaver_activate_on_sleep"; value = cfg.display.screenSaver.whileCharging; }
    { option = "display.screenSaver.whileDocked"; table = "secure"; key = "screensaver_activate_on_dock"; value = cfg.display.screenSaver.whileDocked; }

    { option = "sound.touchSounds"; table = "system"; key = "sound_effects_enabled"; value = cfg.sound.touchSounds; }
    { option = "sound.touchVibration"; table = "system"; key = "haptic_feedback_enabled"; value = cfg.sound.touchVibration; }
    { option = "sound.dialpadTones"; table = "system"; key = "dtmf_tone"; value = cfg.sound.dialpadTones; }
    { option = "sound.screenLockSound"; table = "system"; key = "lockscreen_sounds_enabled"; value = cfg.sound.screenLockSound; }
    { option = "sound.chargingSound"; table = "secure"; key = "charging_sounds_enabled"; value = cfg.sound.chargingSound; }
    { option = "sound.chargingVibration"; table = "secure"; key = "charging_vibration_enabled"; value = cfg.sound.chargingVibration; }
    { option = "sound.vibrateForCalls"; table = "system"; key = "vibrate_when_ringing"; value = cfg.sound.vibrateForCalls; }
    { option = "sound.rampingRinger"; table = "system"; key = "apply_ramping_ringer"; value = cfg.sound.rampingRinger; }
    { option = "sound.keyboardVibration"; table = "system"; key = "keyboard_vibration_enabled"; value = cfg.sound.keyboardVibration; }

    { option = "time.automatic"; table = "global"; key = "auto_time"; value = cfg.time.automatic; }
    { option = "time.automaticTimeZone"; table = "global"; key = "auto_time_zone"; value = cfg.time.automaticTimeZone; }
    { option = "time.format24h"; table = "system"; key = "time_12_24"; value = cfg.time.format24h; store = b: if b then "24" else "12"; }

    { option = "keyboard.showWithPhysicalKeyboard"; table = "secure"; key = "show_ime_with_hard_keyboard"; value = cfg.keyboard.showWithPhysicalKeyboard; }
    { option = "keyboard.spellChecker"; table = "secure"; key = "spell_checker_enabled"; value = cfg.keyboard.spellChecker; }

    { option = "network.privateDns"; table = "global"; key = "private_dns_mode"; value = dns;
      store = d: if d == "off" then "off" else if d == "automatic" then "opportunistic" else "hostname"; }
    { option = "network.privateDns"; table = "global"; key = "private_dns_specifier"; value = if dnsHost then dns else null; }
    { option = "network.wifiScanning"; table = "global"; key = "wifi_scan_always_enabled"; value = cfg.network.wifiScanning; }
    { option = "network.bluetoothScanning"; table = "global"; key = "ble_scan_always_enabled"; value = cfg.network.bluetoothScanning; }
    { option = "network.captivePortalCheck"; table = "global"; key = "captive_portal_mode"; value = cfg.network.captivePortalCheck; }
    { option = "network.openWifiNotification"; table = "global"; key = "wifi_networks_available_notification_on"; value = cfg.network.openWifiNotification; }
    { option = "network.wifiWakeup"; table = "global"; key = "wifi_wakeup_enabled"; value = cfg.network.wifiWakeup; }

    { option = "location.assistedGps"; table = "global"; key = "assisted_gps_enabled"; value = cfg.location.assistedGps; }

    { option = "lockScreen.notifications"; table = "secure"; key = "lock_screen_show_notifications"; value = cfg.lockScreen.notifications; }
    { option = "lockScreen.sensitiveNotifications"; table = "secure"; key = "lock_screen_allow_private_notifications"; value = cfg.lockScreen.sensitiveNotifications; }
    { option = "lockScreen.silentNotifications"; table = "secure"; key = "lock_screen_show_silent_notifications"; value = cfg.lockScreen.silentNotifications; }
    { option = "lockScreen.lockAfter"; table = "secure"; key = "lock_screen_lock_after_timeout"; value = cfg.lockScreen.lockAfter; store = s: s * 1000; }
    { option = "lockScreen.wallet"; table = "secure"; key = "lockscreen_show_wallet"; value = cfg.lockScreen.wallet; }
    { option = "lockScreen.deviceControls"; table = "secure"; key = "lockscreen_show_controls"; value = cfg.lockScreen.deviceControls; }
    { option = "lockScreen.qrCodeScanner"; table = "secure"; key = "lock_screen_show_qr_code_scanner"; value = cfg.lockScreen.qrCodeScanner; }

    { option = "notifications.dots"; table = "secure"; key = "notification_badging"; value = cfg.notifications.dots; }
    { option = "notifications.bubbles"; table = "secure"; key = "notification_bubbles"; value = cfg.notifications.bubbles; }
    { option = "notifications.snooze"; table = "secure"; key = "show_notification_snooze"; value = cfg.notifications.snooze; }
    { option = "notifications.history"; table = "secure"; key = "notification_history_enabled"; value = cfg.notifications.history; }
    { option = "notifications.popUp"; table = "global"; key = "heads_up_notifications_enabled"; value = cfg.notifications.popUp; }
    { option = "notifications.light"; table = "system"; key = "notification_light_pulse"; value = cfg.notifications.light; }
    { option = "notifications.flash.camera"; table = "system"; key = "camera_flash_notification"; value = cfg.notifications.flash.camera; }
    { option = "notifications.flash.screen"; table = "system"; key = "screen_flash_notification"; value = cfg.notifications.flash.screen; }
    { option = "notifications.mediaResume"; table = "secure"; key = "media_controls_resume"; value = cfg.notifications.mediaResume; }
    { option = "notifications.mediaOnLockScreen"; table = "secure"; key = "media_controls_lock_screen"; value = cfg.notifications.mediaOnLockScreen; }

    { option = "gestures.oneHandedMode"; table = "secure"; key = "one_handed_mode_enabled"; value = cfg.gestures.oneHandedMode; }
    { option = "gestures.swipeDownForNotifications"; table = "secure"; key = "swipe_bottom_to_notification_enabled"; value = cfg.gestures.swipeDownForNotifications; }
    { option = "gestures.holdPower"; table = "global"; key = "power_button_long_press"; value = cfg.gestures.holdPower;
      store = enum { powerMenu = 1; assistant = 5; }; }
    { option = "gestures.powerAndVolumeUp"; table = "global"; key = "key_chord_power_volume_up"; value = cfg.gestures.powerAndVolumeUp;
      store = enum { nothing = 0; mute = 1; powerMenu = 2; }; }
    { option = "gestures.preventRinging"; table = "secure"; key = "volume_hush_gesture"; value = cfg.gestures.preventRinging;
      store = enum { off = 0; vibrate = 1; mute = 2; }; }

    { option = "power.batterySaverAt"; table = "global"; key = "low_power_trigger_level"; value = cfg.power.batterySaverAt; }
    # Battery Saver by the battery level, not by Android's guess of when it runs out.
    { option = "power.batterySaverAt"; table = "global"; key = "automatic_power_save_mode"; value = cfg.power.batterySaverAt; store = _: 0; }
    { option = "power.adaptiveBattery"; table = "global"; key = "adaptive_battery_management_enabled"; value = cfg.power.adaptiveBattery; }

    { option = "developer.enable"; table = "global"; key = "development_settings_enabled"; value = cfg.developer.enable; }
    { option = "developer.usbDebugging"; table = "global"; key = "adb_enabled"; value = cfg.developer.usbDebugging; }
    # Charging by AC, USB and wireless (1 + 2 + 4).
    { option = "developer.stayAwake"; table = "global"; key = "stay_on_while_plugged_in"; value = cfg.developer.stayAwake; store = b: if b then 7 else 0; }
    { option = "developer.animationScale"; table = "global"; key = "window_animation_scale"; value = cfg.developer.animationScale; store = float; }
    { option = "developer.animationScale"; table = "global"; key = "transition_animation_scale"; value = cfg.developer.animationScale; store = float; }
    { option = "developer.animationScale"; table = "global"; key = "animator_duration_scale"; value = cfg.developer.animationScale; store = float; }
    { option = "developer.showTaps"; table = "system"; key = "show_touches"; value = cfg.developer.showTaps; }
    { option = "developer.pointerLocation"; table = "system"; key = "pointer_location"; value = cfg.developer.pointerLocation; }
    { option = "developer.windowBlurs"; table = "global"; key = "disable_window_blurs"; value = cfg.developer.windowBlurs; store = b: bit (!b); }
    { option = "developer.mobileDataAlwaysOn"; table = "global"; key = "mobile_data_always_on"; value = cfg.developer.mobileDataAlwaysOn; }
    { option = "developer.verifyAdbApps"; table = "global"; key = "verifier_verify_adb_installs"; value = cfg.developer.verifyAdbApps; }
    { option = "developer.freeformWindows"; table = "global"; key = "enable_freeform_support"; value = cfg.developer.freeformWindows; }
    { option = "developer.forceResizable"; table = "global"; key = "force_resizable_activities"; value = cfg.developer.forceResizable; }

    { option = "accessibility.colorInversion"; table = "secure"; key = "accessibility_display_inversion_enabled"; value = cfg.accessibility.colorInversion; }
    { option = "accessibility.highContrastText"; table = "secure"; key = "high_text_contrast_enabled"; value = cfg.accessibility.highContrastText; }
    { option = "accessibility.colorCorrection"; table = "secure"; key = "accessibility_display_daltonizer_enabled";
      value = cfg.accessibility.colorCorrection; store = c: bit (c != "off"); }
    { option = "accessibility.colorCorrection"; table = "secure"; key = "accessibility_display_daltonizer";
      value = if cfg.accessibility.colorCorrection == "off" then null else cfg.accessibility.colorCorrection;
      store = enum { grayscale = 0; protanomaly = 11; deuteranomaly = 12; tritanomaly = 13; }; }
    { option = "accessibility.magnification"; table = "secure"; key = "accessibility_display_magnification_enabled"; value = cfg.accessibility.magnification; }
    { option = "accessibility.touchAndHoldDelay"; table = "secure"; key = "long_press_timeout"; value = cfg.accessibility.touchAndHoldDelay; }
    { option = "accessibility.monoAudio"; table = "system"; key = "master_mono"; value = cfg.accessibility.monoAudio; }
    { option = "accessibility.audioBalance"; table = "system"; key = "master_balance"; value = cfg.accessibility.audioBalance; store = float; }

    { option = "privacy.showPasswords"; table = "system"; key = "show_password"; value = cfg.privacy.showPasswords; }
    { option = "autofill.service"; table = "secure"; key = "autofill_service"; value = cfg.autofill.service; }
    { option = "quickSettings.tiles"; table = "secure"; key = "sysui_qs_tiles"; value = cfg.quickSettings.tiles; store = lib.concatStringsSep ","; }
    { option = "statusBar.hiddenIcons"; table = "secure"; key = "icon_blacklist"; value = cfg.statusBar.hiddenIcons; store = lib.concatStringsSep ","; }
    { option = "deviceName"; table = "global"; key = "device_name"; value = cfg.deviceName; }
  ];

  # A mapping's stored value: what it says, else true and false as 1 and 0, numbers and strings as they are.
  stored = m: (m.store or (v: if builtins.isBool v then bit v else v)) m.value;
  set = lib.filter (m: m.value != null) (mappings ++ config.android.settingMappings);

  languages = if builtins.isString cfg.language then [ cfg.language ] else if cfg.language == null then [ ] else cfg.language;
  badLanguages = lib.filter (tag: builtins.match "[a-z]{2,3}(-[A-Za-z0-9]{2,8})*" tag == null) languages;
  dns = cfg.network.privateDns;
  dnsHost = dns != null && dns != "off" && dns != "automatic";
  inRange = low: high: value: value == null || (value >= low && value <= high);
  isComponent = value: value == null || builtins.match "[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+" value != null;
  badRawKeys = lib.concatLists (lib.mapAttrsToList (table: settings:
    map (key: "${table}.${key}") (lib.filter (key: builtins.match "[A-Za-z0-9_.:-]+" key == null) (lib.attrNames settings))
  ) (removeAttrs cfg.raw [ "lineage" ] // lib.mapAttrs' (t: v: lib.nameValuePair "lineage.${t}" v) cfg.raw.lineage));
  check = assertion: message: { inherit assertion message; };
in
{
  options.android = {
    settingMappings = mkOption {
      type = types.listOf types.attrs;
      default = [ ];
      internal = true;
      description = "Mappings of other modules' plain options to settings, like those above (nix/lineage.nix).";
    };

    settingLabels = mkOption {
      type = types.attrsOf types.str;
      default = { };
      internal = true;
      description = "For each setting a plain option sets (\"table/name\"), that option, for the build's messages.";
    };
  };

  options.android.settings = {
    raw = {
      system = settingsTable "Android's system";
      secure = settingsTable "Android's secure";
      global = settingsTable "Android's global";
      lineage = {
        system = settingsTable "LineageOS's system (on ROMs based on LineageOS)";
        secure = settingsTable "LineageOS's secure (on ROMs based on LineageOS)";
        global = settingsTable "LineageOS's global (on ROMs based on LineageOS)";
      };
    };

    display = {
      darkMode = option types.bool "Dark mode on (true) or off (false).";
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
      colorMode = option (types.enum [ "natural" "boosted" "saturated" "adaptive" ]) "The screen's colours, on a phone that has these modes.";
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
      screenSaver = {
        enable = option types.bool "The screen saver (the clock, photos, ...) instead of a dark screen.";
        whileCharging = option types.bool "Start the screen saver while charging.";
        whileDocked = option types.bool "Start the screen saver while docked.";
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
      vibrateForCalls = option types.bool "Vibrate for incoming calls.";
      rampingRinger = option types.bool "For calls, vibrate first, then ring louder little by little (Android 13 and newer).";
      keyboardVibration = option types.bool "Vibration of the on-screen keyboard (Android 14 and newer).";
    };

    time = {
      automatic = option types.bool "Take the time from the network.";
      automaticTimeZone = option types.bool "Take the time zone from the network or location (false when timeZone is set).";
      timeZone = option types.str ''
        The time zone, like "Europe/Istanbul" (it needs automaticTimeZone off, which it then is by
        default). time.timeZone, NixOS's name, sets it too.
      '';
      format24h = option types.bool "24-hour time (false: 12-hour).";
    };

    language = option (types.either types.str (types.listOf types.str)) ''
      The phone's language, like "tr-TR", or its languages in order of preference, like
      [ "tr-TR" "en-US" ] (apps take the first one they have). i18n.defaultLocale, NixOS's name,
      sets one too ("tr_TR.UTF-8" is "tr-TR").
    '';

    keyboard = {
      default = option types.str ''
        The keyboard to type with, as its input method's name, like
        "com.android.inputmethod.latin/.LatinIME" (`ime list -a -s` lists the phone's).
      '';
      showWithPhysicalKeyboard = option types.bool "Show the on-screen keyboard while a physical keyboard is connected.";
      spellChecker = option types.bool "The spell checker.";
    };

    network = {
      privateDns = option types.str ''
        Private DNS: "off", "automatic" (encrypted when the network's DNS can), or the hostname of
        a provider, like "dns.quad9.net".
      '';
      dataSaver = option types.bool ''
        Data Saver: apps use mobile data in the background only when let
        (android.apps.<app>.network.unrestrictedData); VPN apps like Tailscale too.
      '';
      wifiScanning = option types.bool "Let apps and services look for Wi-Fi networks for location even while Wi-Fi is off.";
      bluetoothScanning = option types.bool "Let apps and services look for Bluetooth devices for location even while Bluetooth is off.";
      captivePortalCheck = option types.bool ''
        Check each network for a sign-in page (as in hotels), which asks a server of the ROM's
        maker; false never checks.
      '';
      openWifiNotification = option types.bool "Tell when an open Wi-Fi network of good quality is near.";
      wifiWakeup = option types.bool "Turn Wi-Fi on by itself near saved networks of good quality.";
    };

    location = {
      enable = option types.bool "Location for the whole phone, on or off.";
      assistedGps = option types.bool "Assisted GPS: data from the network to find the location faster.";
    };

    lockScreen = {
      notifications = option types.bool "Show notifications on the lock screen.";
      sensitiveNotifications = option types.bool "Show what notifications say on the lock screen (false: only that there is one).";
      silentNotifications = option types.bool "Show silent notifications on the lock screen too.";
      lockAfter = option types.ints.unsigned "Seconds after the screen turns off before the phone locks (with a screen lock set).";
      wallet = option types.bool "Show the wallet on the lock screen (Android 12 and newer).";
      deviceControls = option types.bool "Device controls (smart home) on the lock screen (Android 12 and newer).";
      qrCodeScanner = option types.bool "A QR code scanner on the lock screen (Android 13 and newer, where the phone has one).";
    };

    notifications = {
      dots = option types.bool "Dots on app icons that have notifications.";
      bubbles = option types.bool "Conversations can show as floating bubbles.";
      snooze = option types.bool "Notifications can be snoozed.";
      history = option types.bool "Keep a history of notifications.";
      popUp = option types.bool "Notifications pop up on the screen (heads-up); false: only in the status bar.";
      light = option types.bool "Blink the notification light, on a phone that has one.";
      flash = {
        camera = option types.bool "Flash the camera light for notifications (Android 14 and newer).";
        screen = option types.bool "Flash the screen for notifications (Android 14 and newer).";
      };
      mediaResume = option types.bool "Keep the media player in Quick Settings after the app closes, to play on.";
      mediaOnLockScreen = option types.bool "Show the media player on the lock screen (Android 13 and newer).";
    };

    gestures = {
      oneHandedMode = option types.bool "One-handed mode: swipe down at the bottom to bring the top of the screen down (Android 12 and newer).";
      swipeDownForNotifications = option types.bool "Swipe down at the bottom of the screen to show the notifications (with one-handed mode's support).";
      holdPower = option (types.enum [ "powerMenu" "assistant" ]) "What holding the power button does (Android 12 and newer).";
      powerAndVolumeUp = option (types.enum [ "nothing" "mute" "powerMenu" ]) "What pressing power and volume up together does (Android 12 and newer).";
      preventRinging = option (types.enum [ "off" "vibrate" "mute" ]) "The prevent-ringing shortcut (power and volume up): what it does to a ringing phone.";
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
      showTaps = option types.bool "Show where the screen is touched.";
      pointerLocation = option types.bool "Show the touch position and paths over the screen.";
      windowBlurs = option types.bool "Blur behind windows and panels, on a phone that can (Android 12 and newer).";
      mobileDataAlwaysOn = option types.bool "Keep mobile data on while on Wi-Fi, to switch networks faster.";
      verifyAdbApps = option types.bool "Check apps installed over USB (adb) for harm.";
      freeformWindows = option types.bool "Apps can open in floating windows.";
      forceResizable = option types.bool "Let every app be resized in split screen and floating windows.";
    };

    accessibility = {
      colorInversion = option types.bool "Invert the screen's colours.";
      highContrastText = option types.bool "High contrast text.";
      colorCorrection = option (types.enum [ "off" "deuteranomaly" "protanomaly" "tritanomaly" "grayscale" ]) ''
        Colour correction, for red-green (deuteranomaly, protanomaly) or blue-yellow (tritanomaly)
        colour blindness, or grayscale.
      '';
      magnification = option types.bool "Magnify the screen with a triple tap.";
      touchAndHoldDelay = option (types.ints.between 100 5000) "Milliseconds a touch is held to count as a long press (400 short, 1000 medium, 1500 long).";
      monoAudio = option types.bool "Mono audio: both ears hear everything.";
      audioBalance = option types.number "Audio balance between left (-1) and right (1); 0 is the middle.";
    };

    privacy.showPasswords = option types.bool "Show the last character of a password for a moment while typing it.";

    autofill.service = option types.str ''
      The autofill service (a password manager), as its component, like
      "com.x8bit.bitwarden/com.x8bit.bitwarden.Autofill.AutofillService".
    '';

    quickSettings.tiles = option (types.listOf types.str) ''
      The Quick Settings tiles, in order, by their names in Android, like [ "internet" "bt"
      "flashlight" "dnd" ] (the names a phone has differ; an app's own is "custom(…)").
    '';

    statusBar.hiddenIcons = option (types.listOf types.str) ''
      Icons the status bar does not show, by their names in Android, like [ "rotate" "volume"
      "alarm_clock" "headset" ] (the names a phone has differ).
    '';

    deviceName = option types.str ''
      The phone's name, shown in About phone and to other devices. networking.hostName, NixOS's
      name, sets it too; this one can also have spaces and other letters, like "Neo's phone".
    '';
  };

  config = {
    android.settings.time.automaticTimeZone = mkIf (cfg.time.timeZone != null) (lib.mkDefault false);

    android.settings.raw = mkMerge (map (m: lib.setAttrByPath (lib.splitString "." m.table ++ [ m.key ]) (stored m)) set);
    android.settingLabels = lib.listToAttrs (map (m: lib.nameValuePair "${m.table}/${m.key}" "android.settings.${m.option}") set);

    assertions = [
      (check (badRawKeys == [ ]) "android.settings.raw: ${lib.concatStringsSep ", " badRawKeys} is not a setting's name (letters, digits, _ . : -).")
      (check (inRange 0.5 2 cfg.display.fontScale) "android.settings.display.fontScale: ${toString cfg.display.fontScale} is not between 0.5 and 2.")
      (check (inRange 72 1000 cfg.display.density) "android.settings.display.density: ${toString cfg.display.density} dpi is not a display size (phones have about 300 to 600).")
      (check (inRange 5 86400 cfg.display.screenTimeout) "android.settings.display.screenTimeout: give the seconds, 5 or more.")
      (check (inRange 0 10 cfg.developer.animationScale) "android.settings.developer.animationScale: ${toString cfg.developer.animationScale} is not between 0 and 10.")
      (check (inRange (-1) 1 cfg.accessibility.audioBalance) "android.settings.accessibility.audioBalance: ${toString cfg.accessibility.audioBalance} is not between -1 (left) and 1 (right).")
      (check (cfg.display.nightLight.schedule == "custom" || (cfg.display.nightLight.from == null && cfg.display.nightLight.to == null))
        "android.settings.display.nightLight: from and to are the times of schedule = \"custom\".")
      (check (!(cfg.time.timeZone != null && cfg.time.automaticTimeZone == true))
        "android.settings.time: the time zone is set by hand (timeZone, or time.timeZone), so automaticTimeZone cannot be true; pick one.")
      (check (cfg.time.timeZone == null || builtins.match "[A-Za-z_]+(/[A-Za-z0-9_+-]+)*" cfg.time.timeZone != null)
        "android.settings.time.timeZone (or time.timeZone): \"${toString cfg.time.timeZone}\" is not a time zone like \"Europe/Istanbul\".")
      (check (badLanguages == [ ]) "android.settings.language: ${lib.concatStringsSep ", " badLanguages} is not a language like \"tr-TR\".")
      (check (cfg.language == null || languages != [ ]) "android.settings.language: give at least one language.")
      (check (!dnsHost || builtins.match "([A-Za-z0-9-]+[.])+[A-Za-z]{2,}" dns != null)
        "android.settings.network.privateDns: \"${toString dns}\" is not \"off\", \"automatic\" or a hostname like \"dns.quad9.net\".")
      (check (isComponent cfg.keyboard.default) "android.settings.keyboard.default: \"${toString cfg.keyboard.default}\" is not an input method like \"com.android.inputmethod.latin/.LatinIME\".")
      (check (isComponent cfg.autofill.service) "android.settings.autofill.service: \"${toString cfg.autofill.service}\" is not a component like \"org.example.app/.AutofillService\".")
      (check (cfg.quickSettings.tiles == null || lib.all (tile: tile != "" && !lib.hasInfix "," tile) cfg.quickSettings.tiles)
        "android.settings.quickSettings.tiles: a tile's name cannot be empty or have a comma in it.")
      (check (cfg.statusBar.hiddenIcons == null || lib.all (icon: builtins.match "[A-Za-z0-9_.-]+" icon != null) cfg.statusBar.hiddenIcons)
        "android.settings.statusBar.hiddenIcons: an icon's name is letters, digits, _ . -, like \"alarm_clock\".")
      (check (cfg.deviceName == null || cfg.deviceName != "") "android.settings.deviceName: the name cannot be empty.")
    ];
  };
}
