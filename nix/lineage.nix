# LineageOS's own settings, kept in its own settings tables (content://lineagesettings): there on
# LineageOS and the many ROMs based on it, on phones of every make. A build on a ROM without them
# says so, and leaves these as they are; so does one on a phone without what a setting needs (a
# flashlight, a proximity sensor, charging control), and one for a setting the phone has no value
# for yet: a LineageOS setting cannot be taken out again safely, so it could not go back to having
# none (setting it once in Settings gives it one). Only settings LineageOS documents (in its
# lineage-sdk) are here, not those of one ROM. As with nix/system.nix, null is not managed.
{ config, lib, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.android.settings.lineage;

  option = type: description: mkOption { type = types.nullOr type; default = null; inherit description; };
  enum = values: value: values.${value};

  # "22:00" as seconds after midnight, how LineageOS keeps charging control's times.
  clock = types.strMatching "([01][0-9]|2[0-3]):[0-5][0-9]";
  secondsOfDay = time:
    let parts = map lib.toIntBase10 (lib.splitString ":" time);
    in (builtins.elemAt parts 0 * 60 + builtins.elemAt parts 1) * 60;

  system = "lineage.system";
  secure = "lineage.secure";
  global = "lineage.global";
in
{
  options.android.settings.lineage = {
    statusBar = {
      clockPosition = option (types.enum [ "right" "center" "left" ]) "Where the status bar's clock is.";
      clockAutoHide = option types.bool "Hide the clock while the home screen is shown.";
      amPm = option (types.enum [ "normal" "small" "hidden" ]) "AM and PM next to a 12-hour clock.";
      batteryStyle = option (types.enum [ "icon" "circle" "dottedCircle" "text" ]) "How the battery shows.";
      batteryPercent = option (types.enum [ "hidden" "inside" "nextTo" ]) "The battery percentage: hidden, inside the battery icon, or next to it.";
      doubleTapToSleep = option types.bool "Double-tap the status bar to turn the screen off.";
    };

    quickSettings = {
      quickPulldown = option (types.enum [ "off" "right" "left" ]) "Pull down at the right or left edge of the status bar to open Quick Settings at once.";
      autoBrightnessButton = option types.bool "The auto-brightness button next to the brightness slider.";
    };

    buttons = {
      volumeWake = option types.bool "The volume buttons wake the phone.";
      volumeAnswerCall = option types.bool "The volume buttons answer a ringing call.";
      volumeMusicControls = option types.bool "Holding a volume button while the screen is off skips to the next or last track.";
      swapVolumeOnRotation = option types.bool "Swap the volume buttons when the screen turns, so up is up.";
      torchLongPressPower = option types.bool "Holding the power button while the screen is off turns the flashlight on.";
      torchTimeout = option (types.ints.between 0 3600) "Seconds after which a flashlight turned on with the power button turns off (0: never).";
      partialScreenshot = option types.bool "Pressing (not holding) power and volume down takes a screenshot of part of the screen.";
    };

    sound = {
      increasingRing = option types.bool "The ringtone starts quiet and gets louder.";
      volumePanelOnLeft = option types.bool "The volume panel shows on the left of the screen (false: on the right).";
    };

    navigation.gestureHint = option types.bool "Show the gesture bar (the line at the bottom) in gesture navigation.";

    display = {
      proximityOnWake = option types.bool "Do not wake the screen while the proximity sensor is covered (in a pocket).";
      pureBlackDarkTheme = option types.bool "A pure black dark theme, instead of dark grey.";
    };

    lockScreen = {
      rotation = option types.bool "The lock screen turns with the phone.";
      scramblePin = option types.bool "Scramble the digits of the PIN pad each time.";
      mediaVisualizer = option types.bool "Show a visualizer of the music playing on the lock screen.";
    };

    powerMenu = {
      actions = option (types.listOf (types.strMatching "[a-z]+")) ''
        The power menu's buttons, in order, like [ "power" "restart" "screenshot" "lockdown"
        "emergency" "bugreport" "logout" "airplane" "users" ] (the ones a ROM has differ).
      '';
      advancedRestart = option types.bool "Restart into recovery or the bootloader from the power menu.";
    };

    chargingControl = {
      enable = option types.bool "Charging control: protect the battery by charging less, or later (on a phone that can).";
      mode = option (types.enum [ "auto" "custom" "limit" ]) ''
        How: "auto" charges fully by the next alarm, "custom" fully by `target` once it is `start`,
        "limit" stops at `limit` percent.
      '';
      limit = option (types.ints.between 70 100) "Battery percent to stop charging at (mode = \"limit\").";
      start = option clock "When charging control starts, like \"22:00\" (mode = \"custom\").";
      target = option clock "When the battery is full, like \"06:00\" (mode = \"custom\").";
    };

    wakeOnPlug = option types.bool "Wake the screen when the charger is plugged in or out.";
    statsCollection = option types.bool "Send LineageOS anonymous statistics (the device and version).";
  };

  config.android.settingMappings = [
    { option = "lineage.statusBar.clockPosition"; table = system; key = "status_bar_clock"; value = cfg.statusBar.clockPosition;
      store = enum { right = 0; center = 1; left = 2; }; }
    { option = "lineage.statusBar.clockAutoHide"; table = system; key = "status_bar_clock_auto_hide"; value = cfg.statusBar.clockAutoHide; }
    { option = "lineage.statusBar.amPm"; table = system; key = "status_bar_am_pm"; value = cfg.statusBar.amPm;
      store = enum { normal = 0; small = 1; hidden = 2; }; }
    { option = "lineage.statusBar.batteryStyle"; table = system; key = "status_bar_battery_style"; value = cfg.statusBar.batteryStyle;
      store = enum { icon = 0; circle = 1; text = 2; dottedCircle = 3; }; }
    { option = "lineage.statusBar.batteryPercent"; table = system; key = "status_bar_show_battery_percent"; value = cfg.statusBar.batteryPercent;
      store = enum { hidden = 0; inside = 1; nextTo = 2; }; }
    { option = "lineage.statusBar.doubleTapToSleep"; table = system; key = "double_tap_sleep_gesture"; value = cfg.statusBar.doubleTapToSleep; }

    { option = "lineage.quickSettings.quickPulldown"; table = system; key = "qs_quick_pulldown"; value = cfg.quickSettings.quickPulldown;
      store = enum { off = 0; right = 1; left = 2; }; }
    { option = "lineage.quickSettings.autoBrightnessButton"; table = secure; key = "qs_show_auto_brightness"; value = cfg.quickSettings.autoBrightnessButton; }

    { option = "lineage.buttons.volumeWake"; table = system; key = "volume_wake_screen"; value = cfg.buttons.volumeWake; }
    { option = "lineage.buttons.volumeAnswerCall"; table = system; key = "volume_answer_call"; value = cfg.buttons.volumeAnswerCall; }
    { option = "lineage.buttons.volumeMusicControls"; table = system; key = "volbtn_music_controls"; value = cfg.buttons.volumeMusicControls; }
    { option = "lineage.buttons.swapVolumeOnRotation"; table = system; key = "swap_volume_keys_on_rotation"; value = cfg.buttons.swapVolumeOnRotation; }
    { option = "lineage.buttons.torchLongPressPower"; table = system; key = "torch_long_press_power_gesture"; value = cfg.buttons.torchLongPressPower; }
    { option = "lineage.buttons.torchTimeout"; table = system; key = "torch_long_press_power_timeout"; value = cfg.buttons.torchTimeout; }
    { option = "lineage.buttons.partialScreenshot"; table = system; key = "click_partial_screenshot"; value = cfg.buttons.partialScreenshot; }

    { option = "lineage.sound.increasingRing"; table = system; key = "increasing_ring"; value = cfg.sound.increasingRing; }
    { option = "lineage.sound.volumePanelOnLeft"; table = secure; key = "volume_panel_on_left"; value = cfg.sound.volumePanelOnLeft; }

    { option = "lineage.navigation.gestureHint"; table = system; key = "navigation_bar_hint"; value = cfg.navigation.gestureHint; }

    { option = "lineage.display.proximityOnWake"; table = system; key = "proximity_on_wake"; value = cfg.display.proximityOnWake; }
    { option = "lineage.display.pureBlackDarkTheme"; table = secure; key = "berry_black_theme"; value = cfg.display.pureBlackDarkTheme; }

    { option = "lineage.lockScreen.rotation"; table = system; key = "lockscreen_rotation"; value = cfg.lockScreen.rotation; }
    { option = "lineage.lockScreen.scramblePin"; table = system; key = "lockscreen_scramble_pin_layout"; value = cfg.lockScreen.scramblePin; }
    { option = "lineage.lockScreen.mediaVisualizer"; table = secure; key = "lockscreen_visualizer"; value = cfg.lockScreen.mediaVisualizer; }

    { option = "lineage.powerMenu.actions"; table = secure; key = "power_menu_actions"; value = cfg.powerMenu.actions; store = lib.concatStringsSep "|"; }
    { option = "lineage.powerMenu.advancedRestart"; table = secure; key = "advanced_reboot"; value = cfg.powerMenu.advancedRestart; }

    { option = "lineage.chargingControl.enable"; table = system; key = "charging_control_enabled"; value = cfg.chargingControl.enable; }
    { option = "lineage.chargingControl.mode"; table = system; key = "charging_control_mode"; value = cfg.chargingControl.mode;
      store = enum { auto = 1; custom = 2; limit = 3; }; }
    { option = "lineage.chargingControl.limit"; table = system; key = "charging_control_charging_limit"; value = cfg.chargingControl.limit; }
    { option = "lineage.chargingControl.start"; table = system; key = "charging_control_start_time"; value = cfg.chargingControl.start; store = secondsOfDay; }
    { option = "lineage.chargingControl.target"; table = system; key = "charging_control_target_time"; value = cfg.chargingControl.target; store = secondsOfDay; }

    { option = "lineage.wakeOnPlug"; table = global; key = "wake_when_plugged_or_unplugged"; value = cfg.wakeOnPlug; }
    { option = "lineage.statsCollection"; table = secure; key = "stats_collection"; value = cfg.statsCollection; }
  ];
}
