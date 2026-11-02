# Logos's config: a POCO X3 NFC on crDroid (Android 16, based on LineageOS), as it is set up now
# (read from the phone on 2026-11-02). It builds as it is, with no flake:
#   on the phone   Seraph app: Config, pick this file, then Build
#   on a PC        nix run github:by-architect/Seraph -- switch --file ./configuration.nix
#
# As a start for another phone it builds there too: what that phone does not have (an app given
# settings here, a LineageOS setting, a sensor) is told while building and left out. Put in your
# own secrets first (marked "secret" below); they never go into this file.
#
# null: not managed, the phone keeps what you set by hand. An option taken out again goes back
# to the value it had before Seraph changed it. Every option, explained: nix/*.nix in
# github:by-architect/Seraph.
#
#   1. Apps, packages and repositories
#   2. App settings
#   3. System settings
#   4. Backups
#   5. Scripts and services
{ config, lib, pkgs, ... }:
{
  # ==== 1. Apps, packages and repositories =====================================================

  # F-Droid repositories, by a name of your choosing: each one's apps are pkgs.<name>.
  android.repos = {
    fdroid = {                                                    # f-droid.org: always there
      url = "https://f-droid.org/repo";                           # address
      fingerprint = "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab";   # its signing key (SHA-256)
    };
    izzyondroid.url = "https://apt.izzysoft.de/fdroid/repo?fingerprint=3BF0D6ABFEAE2F401707B6D966BE743BF0EEE49C2561B9BA39073711F628937A";   # address?fingerprint=key
    guardianproject.url = "https://guardianproject.info/fdroid/repo?fingerprint=B7C2EEFD8DAC7806AF67DFCD92EB18126BC08312A7F2D6F3862E46013C7A6135";
    byarchitect.url = "https://fdroid.byarchitect.org/fdroid/repo?fingerprint=50941BDAC56B056A520ABFA3FC7DAAB789DEE5B83B295DA54A1961C7BC8A91C9";
  };

  # Apps to install and keep up to date, each from one repository. An app installed already must
  # be signed with the key the repository's builds have (the build says so when it is not).
  android.packages.fdroid = with pkgs.fdroid; [
    com.termux                                    # Termux: terminal
  ];

  android.packages.izzyondroid = with pkgs.izzyondroid; [
    at.bitfire.davdroid                           # DAVx5: calendar and contacts sync
    "in.sunilpaulmathew.izzyondroid"              # IzzyOnDroid client (in quotes: "in" is a Nix word)
    moe.shizuku.privileged.api                    # Shizuku: system access for apps
    org.wisso.newpipematerial                     # WizeStream: NewPipe-based player
  ];

  android.packages.guardianproject = with pkgs.guardianproject; [
    org.thoughtcrime.securesms                    # Signal: messenger
  ];

  android.packages.byarchitect = with pkgs.byarchitect; [
    com.byarchitect.dejavu                        # Dejavu: browser
  ];

  # Apps from their developers' own releases (GitHub, GitLab, Codeberg, Forgejo), as Obtainium does.
  android.packages.obtainium = [
    {
      id = "dev.imranr.obtainium";                # package name
      url = "https://github.com/ImranR98/Obtainium";   # where its releases are
      apkFilter = "^(?!.*fdroid)";                # which APK: names without "fdroid"
      signer = "B3:53:60:1F:6A:1D:5F:D6:60:3A:E2:F5:0B:E8:0C:F3:01:36:7B:86:B6:AB:8B:1F:66:24:3D:A9:6C:D5:73:62";   # its key: others refused
      prerelease = false;                         # true: pre-releases too
    }
  ];

  # Updates of each source's apps: true installs them by itself (every 6 hours, on Wi-Fi), false
  # only notifies (the app's Updates tab installs them).
  android.settings.repo = {
    fdroid.autoupdatePackages = false;
    izzyondroid.autoupdatePackages = false;
    guardianproject.autoupdatePackages = false;
    byarchitect.autoupdatePackages = false;
    obtainium.autoupdatePackages = true;
  };

  # The phone's other apps (from Google Play through Aurora Store, sideloaded, debug builds) come
  # from no repository here, so Seraph leaves them as they are. F-Droid has its own builds of
  # Tailscale, KDE Connect, LocalSend, Organic Maps, Proton VPN and addy.io, signed with another
  # key than the ones installed: moving to them means reinstalling (the app's data goes).

  # ==== 2. App settings ========================================================================
  # By package name; an app that is not installed is told while building and left out.

  # Every app setting, on one app, as this phone has it.
  android.apps."com.zorinos.zorin_connect" = {
    permissions = {
      camera = false;                             # true: allowed, false: denied, "ask"
      microphone = "ask";
      location = "whileUsing";                    # "always", "whileUsing", false, "ask"
      preciseLocation = false;                    # false: approximate only
      contacts = true;
      calendar = false;
      phone = "ask";                              # calls, phone state
      callLogs = false;
      sms = false;
      nearbyDevices = true;                       # Bluetooth, Wi-Fi devices nearby
      physicalActivity = false;
      bodySensors = false;                        # heart rate, health sensors
      notifications = true;
      photosAndVideos = false;
      musicAndAudio = null;                       # (it does not ask for it)
      files = false;                              # shared storage (apps for Android 12 and older)
      mediaLocation = false;                      # places saved in photos
    };
    battery = "unrestricted";                     # "unrestricted", "optimized", "restricted"
    network = {
      backgroundData = false;                     # mobile data in the background
      unrestrictedData = true;                    # mobile data even with Data Saver on
      wifi = true;                                # network over Wi-Fi (LineageOS)
      mobile = false;                             # network over mobile data (LineageOS)
      vpn = false;                                # network over a VPN (LineageOS)
    };
    access = {
      displayOverOtherApps = true;                # draw over other apps
      modifySystemSettings = false;
      installUnknownApps = false;                 # install APKs
      allFilesAccess = false;
      usageAccess = false;                        # see which apps are used
      alarmsAndReminders = null;                  # exact alarms (it does not ask for them)
      pictureInPicture = false;
      mediaManagement = false;                    # change media files without asking
      fullScreenNotifications = false;
      turnScreenOn = false;
      wifiControl = false;
      restrictedSettings = true;                  # restricted settings allowed (sideloaded apps)
      notificationAccess = true;                  # read all notifications
      accessibility = true;                       # control the screen
    };
    pauseIfUnused = false;                        # pause it when unused for months
    enabled = true;                               # false: disabled and hidden
    paused = false;                               # true: paused, cannot be opened
    debloat = false;                              # true: remove an app the phone came with (data kept)
    openLinks = null;                             # open its web links in it (it has none)
    language = "tr-TR";                           # its own language; "system": the phone's
    defaultFor = [ ];                             # "browser", "dialer", "sms", "home", "assistant", "callScreening"
  };

  # ==== 3. System settings =====================================================================
  # Android's own settings; a phone without what one needs is told while building, and skipped.

  # With NixOS's names, so a module shared with a NixOS machine sets them on both.
  networking.hostName = "Logos";                  # phone name: device name, tailnet name
  time.timeZone = null;                           # like "Europe/Istanbul"; null: from the network
  i18n.defaultLocale = "en_US.UTF-8";             # language ("en-US")
  # The same settings with Android's names (one set here wins over the one above):
  # android.settings.deviceName = "Logos";              # any text, like "Neo's phone"
  # android.settings.language = [ "en-US" "tr-TR" ];     # several languages, in order
  # android.settings.time.timeZone = "Europe/Istanbul";  # turns automaticTimeZone off

  android.settings.display = {
    darkMode = true;                              # dark theme
    fontScale = 1.15;                             # font size: 1.0 normal
    boldText = true;                              # bold text everywhere
    density = 320;                                # display size in dpi: smaller fits more (this phone's own: 400)
    screenTimeout = 30;                           # seconds until the screen goes off
    autoRotate = false;                           # turn with the phone
    autoBrightness = true;                        # brightness from the light around
    peakRefreshRate = null;                       # highest Hz (unset: the screen's highest)
    minRefreshRate = 120;                         # lowest Hz: 120 always smooth
    colorMode = "natural";                        # "natural", "boosted", "saturated", "adaptive"
    alwaysOn = null;                              # always-on display (this phone has none)
    wakeForNotifications = false;                 # screen on for new notifications
    doubleTapToWake = false;                      # double-tap the screen to wake
    liftToWake = null;                            # wake when picked up (no sensor on this phone)
    nightLight = {                                # warmer screen at night
      schedule = null;                            # "none", "sunsetToSunrise", "custom" (now by hand)
      from = null;                                # "22:00", with schedule = "custom"
      to = null;                                  # "06:30"
      temperature = null;                         # kelvin: lower is warmer
    };
    screenSaver = {
      enable = false;                             # clock or photos instead of a dark screen
      whileCharging = true;
      whileDocked = false;
    };
  };

  android.settings.navigation.mode = "gestures";  # "gestures", "threeButton", "twoButton"

  android.settings.sound = {
    touchSounds = false;                          # tap sounds
    touchVibration = true;                        # vibrate on taps
    dialpadTones = true;
    screenLockSound = true;                       # sound on lock and unlock
    chargingSound = true;
    chargingVibration = true;
    vibrateForCalls = true;
    rampingRinger = false;                        # vibrate first, then ring louder
    keyboardVibration = true;
  };

  android.settings.time = {
    automatic = true;                             # time from the network
    automaticTimeZone = true;                     # time zone from the network
    format24h = true;                             # 24-hour clock
  };

  android.settings.keyboard = {
    default = "com.android.inputmethod.latin/.LatinIME";   # keyboard in use (`ime list -a -s` lists them)
    showWithPhysicalKeyboard = false;             # on-screen keyboard with a real one
    spellChecker = null;
  };

  android.settings.network = {
    privateDns = null;                            # "off", "automatic", or a host like "dns.quad9.net"
    dataSaver = false;                            # less background data (cuts VPN apps too)
    wifiScanning = false;                         # Wi-Fi scans for location, even when off
    bluetoothScanning = null;                     # same for Bluetooth
    captivePortalCheck = null;                    # look for sign-in pages (asks a server)
    openWifiNotification = true;                  # tell about open networks
    wifiWakeup = true;                            # Wi-Fi on near saved networks
  };

  android.settings.location = {
    enable = false;                               # location on or off
    assistedGps = true;                           # faster GPS with network help
  };

  android.settings.lockScreen = {
    notifications = false;                        # notifications on the lock screen
    sensitiveNotifications = true;                # what they say, too
    silentNotifications = false;
    lockAfter = null;                             # seconds after the screen goes off
    wallet = true;
    deviceControls = false;                       # smart home controls
    qrCodeScanner = false;
  };

  android.settings.notifications = {
    dots = true;                                  # dots on app icons
    bubbles = true;                               # floating chat bubbles
    snooze = null;
    history = true;                               # keep a history
    popUp = false;                                # pop up on the screen (heads-up)
    light = false;                                # notification light
    flash.camera = false;                         # flash the camera light
    flash.screen = false;                         # flash the screen
    mediaResume = null;                           # keep the media player after the app closes
    mediaOnLockScreen = null;                     # media player on the lock screen
  };

  android.settings.gestures = {
    oneHandedMode = false;                        # bring the top of the screen down
    swipeDownForNotifications = false;            # swipe down at the bottom for notifications
    holdPower = "assistant";                      # "assistant" or "powerMenu"
    powerAndVolumeUp = "powerMenu";               # "nothing", "mute", "powerMenu"
    preventRinging = "vibrate";                   # "off", "vibrate", "mute"
  };

  android.settings.power = {
    batterySaverAt = null;                        # battery saver at this percent (0: never)
    adaptiveBattery = null;                       # limit apps not used often
  };

  android.settings.developer = {
    enable = true;                                # Developer options shown
    usbDebugging = true;                          # adb (false cuts it, also for seraph-rebuild)
    stayAwake = false;                            # screen on while charging
    animationScale = 1;                           # 0.5: twice as fast, 0: off
    showTaps = false;
    pointerLocation = null;                       # touch position over the screen
    windowBlurs = false;                          # blur behind panels
    mobileDataAlwaysOn = true;                    # mobile data ready while on Wi-Fi
    verifyAdbApps = false;                        # check apps installed over adb
    freeformWindows = null;                       # floating windows
    forceResizable = null;                        # every app resizable
  };

  android.settings.accessibility = {
    colorInversion = null;
    highContrastText = false;
    colorCorrection = null;                       # "off", "deuteranomaly", "protanomaly", "tritanomaly", "grayscale"
    magnification = false;                        # triple-tap to zoom
    touchAndHoldDelay = 400;                      # long press in ms: 400 short, 1000 medium, 1500 long
    monoAudio = null;
    audioBalance = null;                          # -1 left, 0 middle, 1 right
  };

  android.settings.privacy.showPasswords = null;  # show a password's last letter while typing

  android.settings.autofill.service = "com.x8bit.bitwarden/com.x8bit.bitwarden.Autofill.AutofillService";   # Bitwarden fills passwords

  android.settings.quickSettings.tiles = [        # Quick Settings tiles, in order
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
    "custom(dev.theolm.wwc/.quicktile.ChatTile)"  # an app's own tile
  ];

  android.settings.statusBar.hiddenIcons = [ "rotate" ];   # status bar icons hidden

  # Any other setting of Android's tables, by its own name (as `settings put` takes it).
  android.settings.raw = {
    system = { };                                 # like { screen_off_timeout = 60000; }
    secure = { };
    global = { };
    lineage = {                                   # LineageOS's own tables
      system = { };
      secure = { };
      global = { };
    };
  };

  # LineageOS's own settings (on ROMs based on it, like crDroid).
  android.settings.lineage.statusBar = {
    clockPosition = "left";                       # "right", "center", "left"
    clockAutoHide = false;                        # hide the clock on the home screen
    amPm = "hidden";                              # "normal", "small", "hidden"
    batteryStyle = "icon";                        # "icon", "circle", "dottedCircle", "text"
    batteryPercent = "inside";                    # "hidden", "inside", "nextTo"
    doubleTapToSleep = true;                      # double-tap the status bar: screen off
  };

  android.settings.lineage.quickSettings = {
    quickPulldown = "off";                        # one-swipe Quick Settings at an edge: "off", "right", "left"
    autoBrightnessButton = true;                  # auto-brightness button by the slider
  };

  android.settings.lineage.buttons = {
    volumeWake = false;                           # volume buttons wake the phone
    volumeAnswerCall = false;                     # volume buttons answer calls
    volumeMusicControls = true;                   # hold volume: next or last track
    swapVolumeOnRotation = false;                 # volume up stays up when turned
    torchLongPressPower = false;                  # hold power: flashlight
    torchTimeout = null;                          # flashlight off after seconds (0: never)
    partialScreenshot = true;                     # power + volume down press: part of the screen
  };

  android.settings.lineage.sound = {
    increasingRing = false;                       # ringtone starts quiet
    volumePanelOnLeft = true;                     # volume panel on the left
  };

  android.settings.lineage.navigation.gestureHint = true;   # the gesture bar at the bottom

  android.settings.lineage.display = {
    proximityOnWake = null;                       # no waking in a pocket (no sensor on this phone)
    pureBlackDarkTheme = false;                   # black instead of dark grey
  };

  android.settings.lineage.lockScreen = {
    rotation = false;                             # lock screen turns with the phone
    scramblePin = null;                           # PIN digits in a new order each time
    mediaVisualizer = false;                      # music visualizer
  };

  android.settings.lineage.powerMenu = {
    actions = [ "emergency" "lockdown" "power" "restart" "screenshot" "bugreport" "logout" ];   # power menu buttons
    advancedRestart = true;                       # restart into recovery or bootloader
  };

  android.settings.lineage.chargingControl = {
    enable = false;                               # charge less, or later, to spare the battery
    mode = "auto";                                # "auto" (full by the alarm), "custom", "limit"
    limit = 80;                                   # stop at percent, with mode = "limit"
    start = "22:00";                              # with mode = "custom": from
    target = "06:00";                             # full by
  };

  android.settings.lineage.wakeOnPlug = false;     # screen on when the charger goes in or out
  android.settings.lineage.statsCollection = false;   # anonymous statistics to LineageOS

  # ==== 4. Backups =============================================================================
  # Not in Seraph yet. Planned: a backup of each app's APK and data, made by itself when the app
  # goes to the background (or on a schedule), and restored when a build installs the app again:
  #   android.apps."org.thoughtcrime.securesms".backup = true;
  #   android.backup = { path = "/storage/EF27-E719/Backup"; keep = { last = 3; daily = 7; weekly = 4; }; };
  # Until then this phone's apps are backed up by Neo Backup (above), and its folders are copied
  # to the PCs over SSH.

  # ==== 5. Scripts and services ================================================================
  # Services with NixOS's names and options, run by root from Nix packages, which the phone fetches
  # from cache.nixos.org (nixos-unstable) and runs where /nix exists. Logs: /data/adb/seraph/units.

  services.tailscale = {
    enable = true;                                # Tailscale run by root, next to the Tailscale app (no VPN slot)
    package = pkgs.tailscale;                     # which build
    port = 41641;                                 # UDP port for direct connections; 0: any
    interfaceName = "userspace-networking";       # the only mode on a phone
    authKeyFile = "/data/adb/seraph/secrets/tailscale.key";   # secret: auth key, logs in without a link
    # (or ./tailscale.key: a file next to this one, put on the phone by each build, root-only)
    authKeyParameters = {                         # with an OAuth client secret as the key:
      ephemeral = null;                           #   gone from the tailnet when offline
      preauthorized = null;                       #   needs no approval
      baseURL = null;                             #   the API's address
    };
    extraUpFlags = [ ];                           # more `tailscale up` flags, like "--ssh"
    extraSetFlags = [ ];                          # `tailscale set` flags, like "--advertise-exit-node"
    extraDaemonFlags = [ ];                       # more tailscaled flags
    permitCertUid = null;                         # user that may fetch TLS certificates
    disableTaildrop = false;                      # true: no sending files
    disableUpstreamLogging = false;               # true: no logs to Tailscale
    openFirewall = false;                         # nothing to open on a phone
    useRoutingFeatures = "none";                  # exit node, subnet router: flags above do it here
  };

  services.syncthing = {
    enable = false;                               # true: Syncthing, run by root
    package = pkgs.syncthing;                     # which build
    cert = null;                                  # secret: ./syncthing/cert.pem, a fixed device ID
    key = null;                                   # secret: ./syncthing/key.pem, with cert
    guiPasswordFile = null;                       # secret: the GUI's password
    guiAddress = "unix:///run/syncthing/gui.sock";   # GUI for root only; "127.0.0.1:8384": a browser
    overrideDevices = true;                       # remove devices not listed here
    overrideFolders = true;                       # remove folders not listed here
    settings = {
      options = { };                              # Syncthing's own, like { urAccepted = -1; }
      devices.laptop = {                          # a device, by a name of your choosing (example)
        id = "7CFNTQM-IMTJBHJ-3UWRDIU-ZGQJFR6-VCXZ3NB-XUH3KZO-N52ITXR-LAIYUAU";   # its device ID
        addresses = [ "dynamic" ];                # where to find it
        autoAcceptFolders = false;                # take the folders it shares
      };
      folders.documents = {                       # a folder, by its ID (example)
        enable = true;
        path = "/storage/emulated/0/Documents";   # full path on the phone
        label = "Documents";                      # its name in the GUI
        type = "sendreceive";                     # "sendreceive", "sendonly", "receiveonly", "receiveencrypted"
        devices = [ "laptop" ];                   # shared with
        versioning = null;                        # old versions kept: { type = "trashcan"; params.cleanoutDays = "30"; }
        copyOwnershipFromParent = false;
        ignorePerms = true;                       # no file permissions (the phone's storage has none)
        ignorePatterns = null;                    # files left out, like [ "*.tmp" ]
      };
    };
    extraFlags = [ ];                             # more `syncthing serve` flags
    all_proxy = null;                             # proxy, like "socks5://127.0.0.1:1055"
    openDefaultPorts = false;                     # nothing to open on a phone
    systemService = true;                         # always one here
    user = "syncthing";                           # NixOS only: root runs it here
    group = "syncthing";
    dataDir = "/var/lib/syncthing";               # its files (fixed here)
    configDir = "/var/lib/syncthing";
    databaseDir = "/var/lib/syncthing";
    relay.enable = false;                         # relay server: not on a phone
  };

  # Your own services, as NixOS's systemd.services. Example: the Download folder as a web page for
  # your tailnet. Bound to 127.0.0.1, Wi-Fi cannot reach it, but Seraph's Tailscale passes it on:
  # http://<the phone's tailnet name>:8080 (like logos-1).
  systemd.services.web-share = {
    enable = false;                               # true: run it
    description = "Download folder on the tailnet";   # a few words about it
    wantedBy = [ "multi-user.target" ];           # start at boot and with each build
    after = [ "tailscaled.service" ];             # start after these
    wants = [ "tailscaled.service" ];             # start these with it
    path = [ pkgs.darkhttpd ];                    # programs on its PATH
    environment.SHARE = "/storage/emulated/0/Download";   # its variables
    preStart = ''test -d "$SHARE"'';              # commands before each start
    postStart = "echo 'web-share: port 8080'";    # commands once started
    restartTriggers = [ ];                        # values whose change restarts it
    serviceConfig = {
      Type = "simple";                            # keeps running ("oneshot": runs once)
      ExecStart = "${pkgs.darkhttpd}/bin/darkhttpd $SHARE --addr 127.0.0.1 --port 8080";   # what it runs (or script)
      Restart = "on-failure";                     # start again when it fails: "no", "on-failure", "always"
      RestartSec = 5;                             # seconds before that
      WorkingDirectory = "/var/lib/web-share";    # where it runs
      StateDirectory = "web-share";               # /var/lib/web-share, kept
      RuntimeDirectory = "web-share";             # /run/web-share, gone when it stops
      Environment = [ "TZ=Europe/Istanbul" ];     # more variables, "NAME=value"
    };
  };

  # A one-time unit: says whether web-share answers, at each build (and at boot).
  systemd.services.web-share-check = {
    enable = false;                               # true: run it (with web-share on)
    description = "Check that web-share answers";
    wantedBy = [ "multi-user.target" ];
    requires = [ "web-share.service" ];           # needs it: starts after it, or not at all
    before = [ ];                                 # start before these
    path = [ pkgs.curl ];
    script = ''
      curl -fsS -o /dev/null http://127.0.0.1:8080/ && echo "web-share answers on port 8080"
    '';                                           # bash commands (or serviceConfig.ExecStart)
    serviceConfig = {
      Type = "oneshot";                           # runs once, to its end
      RemainAfterExit = true;                     # counts as running once done
      ExecStartPre = "${pkgs.coreutils}/bin/sleep 1";   # a command before it
      ExecStartPost = "${pkgs.coreutils}/bin/true";     # a command after it
      ExecStop = "${pkgs.coreutils}/bin/true";          # a command when it stops
    };
  };

  environment.systemPackages = with pkgs; [ ];    # Nix packages for root's shell (su -c seraph-shell), like htop rsync

  nixpkgs.config = { };                           # nixpkgs settings, like { allowUnfree = true; }
  nixpkgs.overlays = [ ];                         # changed packages (only what cache.nixos.org has)
  nixpkgs.hostPlatform = "aarch64-linux";         # the phone's processor: 64-bit ARM

  system.activationScripts = { };                 # scripts at each build: not on the phone yet
}
