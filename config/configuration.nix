# This phone's config. Edit it, then press Build in the Seraph app (or the
# module's Action button in Magisk): Nix on the phone checks it and applies it.
#
# Options:
#   android.repos.<name>     an F-Droid repository besides f-droid.org (fdroid):
#                              android.repos.mine.url = "https://…/repo?fingerprint=…";
#   android.packages.<repo>  its apps, by package name (an app comes from one repo only):
#                              with pkgs.fdroid; [ org.example.app ]
#                            a name with a Nix keyword in it (in, if, ...) goes in quotes:
#                              [ "in.example.app" ]
#   android.settings.repo.<repo>.autoupdatePackages   its apps update by themselves
#   android.settings.display, .sound, .time, .language, .network, .lockScreen, ...
#                            the phone's own settings (every one: nix/system.nix)
#   android.settings.lineage.*   LineageOS's own settings, on ROMs based on it (nix/lineage.nix)
#   android.settings.raw     any other of Android's settings, by its name
#   android.apps."<package>" an app's settings (every one: nix/app.nix); debloat = true removes
#                            one that came with the phone (its data kept)
#   services.<name>          programs root keeps running, with NixOS's names and options (from
#                            Nix packages): services.tailscale.enable = true; (nix/services.nix,
#                            nix/syncthing.nix)
#   systemd.services.<name>  your own services, as on NixOS (nix/units.nix)
#   environment.systemPackages   Nix packages, for root's shell: su -c seraph-shell
#   networking.hostName, time.timeZone, i18n.defaultLocale
#                            the phone's name, time zone and language, with NixOS's names
#                            (nix/global.nix); an android.settings one set too wins
#   a secret file (services.tailscale.authKeyFile = ./tailscale.key;, written without quotes)
#                            comes from the config's folder: each build puts it on the phone,
#                            readable by root alone, never in the profile or Nix's store
#
# Built on the phone (the app's Build), or on a PC and applied on the phone over adb or ssh:
#   nix run github:by-architect/Seraph -- switch --flake .#Logos
#
# With flake.nix next to it, this folder is a flake (as /etc/nixos can be): the app's Config
# picks which one a build reads, this file alone or the flake.
{ config, lib, pkgs, ... }:
{
  # The phone's name, as NixOS's: its device name, and its name on the tailnet.
  networking.hostName = "Logos";

  # Tailscale, run by root (tailscaled), next to the Tailscale app: userspace mode takes no VPN
  # slot. The first build shows a link to log in with; authKeyFile would log in without one.
  # NixOS's own options for it (nix/services.nix), so a NixOS module can set them too.
  services.tailscale = {
    enable = true;
    # authKeyFile = "/data/adb/seraph/secrets/tailscale.key";
    # extraUpFlags = [ "--ssh" ];
  };

  # The phone's own settings, as they were on it on 2026-11-01. null: not set on the phone, so
  # Android takes its own default (a value there would set it). A setting this phone does not
  # have is told at build time, and left as it is. Every option: nix/system.nix.

  android.settings.display = {
    darkMode = true;
    fontScale = 1.15;                   # font size: 1.0 is normal
    boldText = true;
    density = 320;                      # display size, in dpi (this phone's own is 400)
    screenTimeout = 30;                 # seconds
    autoRotate = false;
    autoBrightness = true;
    peakRefreshRate = null;             # "Infinity" on the phone: the highest the screen has
    minRefreshRate = 120;               # Hz
    colorMode = "natural";              # "natural", "boosted", "saturated", "adaptive"
    alwaysOn = null;                    # this phone has none
    wakeForNotifications = false;
    doubleTapToWake = false;
    liftToWake = true;
    nightLight.schedule = null;         # "none", "sunsetToSunrise" or "custom"; now switched by hand
    nightLight.from = null;             # like "22:00", with schedule = "custom"
    nightLight.to = null;               # like "06:30"
    nightLight.temperature = null;      # in kelvin: lower is warmer
    screenSaver.enable = false;
    screenSaver.whileCharging = true;
    screenSaver.whileDocked = false;
  };

  android.settings.navigation.mode = "gestures";   # or "threeButton"

  android.settings.sound = {
    touchSounds = false;
    touchVibration = true;
    dialpadTones = true;
    screenLockSound = true;
    chargingSound = true;
    chargingVibration = true;
    vibrateForCalls = true;
    rampingRinger = false;      # vibrate first, then ring louder
    keyboardVibration = true;
  };

  android.settings.time = {
    automatic = true;
    automaticTimeZone = true;
    timeZone = null;            # from the network now; a zone like "Europe/Istanbul" sets it by hand
    format24h = true;
  };

  android.settings.language = "en-US";   # or several, in order: [ "tr-TR" "en-US" ]

  android.settings.keyboard = {
    default = "com.android.inputmethod.latin/.LatinIME";   # `ime list -a -s` lists the phone's
    showWithPhysicalKeyboard = false;
    spellChecker = null;
  };

  android.settings.network = {
    privateDns = null;             # "off", "automatic", or a provider like "dns.quad9.net"
    dataSaver = false;             # on, it also cuts the background data of VPN apps like Tailscale
    wifiScanning = false;
    bluetoothScanning = null;
    captivePortalCheck = null;
    openWifiNotification = true;
    wifiWakeup = true;
  };

  android.settings.location = {
    enable = false;
    assistedGps = true;
  };

  android.settings.lockScreen = {
    notifications = false;
    sensitiveNotifications = true;
    silentNotifications = false;
    lockAfter = null;                # seconds after the screen turns off
    wallet = true;
    deviceControls = false;
    qrCodeScanner = false;
  };

  android.settings.notifications = {
    dots = true;
    bubbles = true;
    snooze = null;
    history = true;
    popUp = false;              # heads-up notifications
    light = false;
    flash.camera = false;
    flash.screen = false;
    mediaResume = null;
    mediaOnLockScreen = null;
  };

  android.settings.gestures = {
    oneHandedMode = false;
    swipeDownForNotifications = false;
    holdPower = "assistant";             # or "powerMenu"
    powerAndVolumeUp = "powerMenu";      # "nothing", "mute" or "powerMenu"
    preventRinging = "vibrate";          # "off", "vibrate" or "mute"
  };

  android.settings.power = {
    batterySaverAt = null;    # battery percent; 0: never
    adaptiveBattery = null;
  };

  android.settings.developer = {
    enable = true;
    usbDebugging = true;         # false turns adb off, also for run-config-build.sh
    stayAwake = false;
    animationScale = 1;          # 0.5: twice as fast, 0: off
    showTaps = false;
    pointerLocation = null;
    windowBlurs = false;
    mobileDataAlwaysOn = true;
    verifyAdbApps = false;
    freeformWindows = null;
    forceResizable = null;
  };

  android.settings.accessibility = {
    colorInversion = null;
    highContrastText = false;
    colorCorrection = null;     # "off", "deuteranomaly", "protanomaly", "tritanomaly", "grayscale"
    magnification = false;      # triple-tap to zoom
    touchAndHoldDelay = 400;    # milliseconds: 400 short, 1000 medium, 1500 long
    monoAudio = null;
    audioBalance = null;        # -1 left, 0 middle, 1 right
  };

  android.settings.privacy.showPasswords = null;

  android.settings.autofill.service = "com.x8bit.bitwarden/com.x8bit.bitwarden.Autofill.AutofillService";

  android.settings.quickSettings.tiles = [
    "internet"
    "bt"
    "hotspot"
    "location"
    "airplane"
    "caffeine"
    "rotation"
    "usb_tether"
    "flashlight"
    "cast"
    "screenrecord"
    "custom(org.lineageos.settings/.display.CabcTileService)"
    "battery"
    "saver"
    "dnd"
    "dark"
    "night"
    "mictoggle"
    "cameratoggle"
    "custom(org.lineageos.settings/.fpsinfo.FPSTileService)"
    "vpn"
    "custom(dev.theolm.wwc/.quicktile.ChatTile)"
  ];

  android.settings.statusBar.hiddenIcons = [ "rotate" ];

  # Any other of Android's settings, by its name, as `settings put <table> <name> <value>`.
  android.settings.raw = {
    system = { };
    secure = { };
    global = { };
  };

  # LineageOS's own settings, on ROMs based on it, like this one (every one: nix/lineage.nix).

  android.settings.lineage.statusBar = {
    clockPosition = "left";      # "right", "center" or "left"
    clockAutoHide = false;
    amPm = "hidden";
    batteryStyle = "icon";       # "icon", "circle", "dottedCircle" or "text"
    batteryPercent = "inside";   # "hidden", "inside" or "nextTo"
    doubleTapToSleep = true;
  };

  android.settings.lineage.quickSettings = {
    quickPulldown = "off";         # "off", "right" or "left"
    autoBrightnessButton = true;
  };

  android.settings.lineage.buttons = {
    volumeWake = false;
    volumeAnswerCall = false;
    volumeMusicControls = true;     # hold a volume button to skip tracks
    swapVolumeOnRotation = false;
    torchLongPressPower = false;
    torchTimeout = null;            # seconds; 0: never
    partialScreenshot = true;
  };

  android.settings.lineage.sound = {
    increasingRing = false;
    volumePanelOnLeft = true;
  };

  android.settings.lineage.navigation.gestureHint = true;

  android.settings.lineage.display = {
    proximityOnWake = true;       # the phone reports no proximity sensor now
    pureBlackDarkTheme = false;
  };

  android.settings.lineage.lockScreen = {
    rotation = false;
    scramblePin = null;
    mediaVisualizer = false;
  };

  android.settings.lineage.powerMenu = {
    actions = [ "emergency" "lockdown" "power" "restart" "screenshot" "bugreport" "logout" ];
    advancedRestart = true;
  };

  android.settings.lineage.chargingControl = {
    enable = false;
    mode = "auto";      # "auto", "custom" or "limit"
    limit = 80;         # percent, with mode = "limit"
    start = "22:00";    # with mode = "custom"
    target = "06:00";
  };

  android.settings.lineage.wakeOnPlug = false;

  android.settings.lineage.statsCollection = false;

  android.packages.fdroid = with pkgs.fdroid; [
    com.zorinos.zorin_connect
  ];
android.packages.obtainium = [
  { id = "dev.imranr.obtainium"; url = "https://github.com/ImranR98/Obtainium"; apkFilter = "^(?!.*fdroid)"; }
];
android.settings.repo.obtainium.autoupdatePackages = true;

  # My own repository.
  android.repos.byarchitect.url =
    "https://fdroid.byarchitect.org/fdroid/repo?fingerprint=50941BDAC56B056A520ABFA3FC7DAAB789DEE5B83B295DA54A1961C7BC8A91C9";
  android.packages.byarchitect = with pkgs.byarchitect; [
    com.byarchitect.dejavu
  ];

  # Every app setting, on one app, to see what each one does.
  android.apps."com.zorinos.zorin_connect" = {
    permissions = {
      camera = false;
      microphone = "ask";
      location = "whileUsing";
      preciseLocation = false;
      contacts = true;
      calendar = false;
      phone = "ask";
      callLogs = false;
      sms = false;
      nearbyDevices = true;
      physicalActivity = false;
      bodySensors = false;
      notifications = true;
      photosAndVideos = false;
      musicAndAudio = true;
      files = false;
      mediaLocation = false;
    };

    battery = "unrestricted";

    network = {
      backgroundData = false;
      unrestrictedData = true;
      wifi = true;
      mobile = false;
      vpn = false;
    };

    access = {
      displayOverOtherApps = true;
      modifySystemSettings = false;
      installUnknownApps = false;
      allFilesAccess = false;
      usageAccess = false;
      alarmsAndReminders = true;
      pictureInPicture = false;
      mediaManagement = false;
      fullScreenNotifications = false;
      turnScreenOn = false;
      wifiControl = false;
      restrictedSettings = true;
      notificationAccess = true;
      accessibility = true;
    };

    pauseIfUnused = false;
    enabled = true;       # false would disable it (it would not run, and is hidden)
    paused = false;       # true would pause it (it could not be opened)
    openLinks = true;
    language = "tr-TR";
    # defaultFor = [ "browser" ];   # only for an app that can be one; Zorin Connect cannot
  };
}
