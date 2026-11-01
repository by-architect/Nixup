# This phone's config. Edit it, then press Build in the Andronix app (or the
# module's Action button in Magisk): Nix on the phone checks it and applies it.
#
# Options:
#   android.darkMode         true (on), false (off), or null (left as you set it)
#   android.repos.<name>     an F-Droid repository besides f-droid.org (fdroid):
#                              android.repos.mine.url = "https://…/repo?fingerprint=…";
#   android.packages.<repo>  its apps, by package name (an app comes from one repo only):
#                              with pkgs.fdroid; [ org.example.app ]
#                            a name with a Nix keyword in it (in, if, ...) goes in quotes:
#                              [ "in.example.app" ]
#   android.settings.<repo>.autoupdatePackages   its apps update by themselves
#   android.debloat          apps that came with the phone, to remove (their data is kept)
#   android.display, .sound, .time, .language, .network, .location, .lockScreen, ...
#                            the phone's own settings (every one: nix/system.nix)
#   android.rawSettings      any other of Android's settings, by its name
#   android.apps."<package>" an app's settings (every one: nix/app.nix)
{ pkgs, ... }:
{
  android.darkMode = true;

  # The phone's own settings, as they were on it on 2026-11-01. null: not set on the phone, so
  # Android takes its own default (a value there would set it).
  android.display = {
    fontScale = 1.15;            # font size: 1.0 is normal
    boldText = true;
    density = 320;               # display size, in dpi (this phone's own is 400)
    screenTimeout = 30;          # seconds
    autoRotate = false;
    autoBrightness = true;
    peakRefreshRate = null;      # "Infinity" on the phone: the highest the screen has
    minRefreshRate = 120;        # Hz
    alwaysOn = null;
    wakeForNotifications = false;
    doubleTapToWake = false;
    liftToWake = true;
    nightLight = {
      schedule = null;           # "none", "sunsetToSunrise" or "custom"; now switched by hand
      from = null;               # like "22:00", with schedule = "custom"
      to = null;                 # like "06:30"
      temperature = null;        # in kelvin: lower is warmer
    };
  };

  android.navigation.mode = "gestures";   # or "threeButton"

  android.sound = {
    touchSounds = false;
    touchVibration = true;
    dialpadTones = true;
    screenLockSound = true;
    chargingSound = true;
    chargingVibration = true;
  };

  android.time = {
    automatic = true;
    automaticTimeZone = true;
    timeZone = null;             # from the network now; a zone like "Europe/Istanbul" sets it by hand
    format24h = true;
  };

  android.language = "en-US";    # or several, in order: [ "tr-TR" "en-US" ]

  android.network = {
    privateDns = null;           # "off", "automatic", or a provider like "dns.quad9.net"
    dataSaver = false;           # on, it also cuts the background data of VPN apps like Tailscale
    wifiScanning = false;
    bluetoothScanning = null;
    captivePortalCheck = null;
  };

  android.location.enable = false;

  android.lockScreen = {
    notifications = false;
    sensitiveNotifications = true;
    lockAfter = null;            # seconds after the screen turns off
  };

  android.notifications = {
    dots = true;
    bubbles = true;
    snooze = null;
    history = true;
  };

  android.power = {
    batterySaverAt = null;       # battery percent; 0: never
    adaptiveBattery = null;
  };

  android.developer = {
    enable = true;
    usbDebugging = true;         # false turns adb off, also for run-config-build.sh
    stayAwake = false;
    animationScale = 1;          # 0.5: twice as fast, 0: off
  };

  android.accessibility = {
    colorInversion = null;
    highContrastText = false;
  };

  android.statusBar.hiddenIcons = [ "rotate" ];

  android.deviceName = "Logos";

  # Any other of Android's settings, by its name, as `settings put <table> <name> <value>`.
  android.rawSettings = {
    system = { };
    secure = { };
    global = { };
  };

  android.packages.fdroid = with pkgs.fdroid; [
    com.zorinos.zorin_connect
  ];
android.packages.obtainium = [
  { id = "dev.imranr.obtainium"; url = "https://github.com/ImranR98/Obtainium"; apkFilter = "^(?!.*fdroid)"; }
];
android.settings.obtainium.autoupdatePackages = true;

  # My own repository: the test versions of Dejavu.
  android.repos.byarchitect.url =
    "https://fdroid.byarchitect.org/fdroid/repo?fingerprint=50941BDAC56B056A520ABFA3FC7DAAB789DEE5B83B295DA54A1961C7BC8A91C9";
  android.packages.byarchitect = with pkgs.byarchitect; [
    com.byarchitect.dejavu.test
  ];

  # Apps that came with the phone to remove, like "org.lineageos.jelly" (their data is kept:
  # taken out of the list, they come back). None for now.
  android.debloat = [ ];

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
