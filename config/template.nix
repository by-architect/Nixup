# Logos's config: a POCO X3 NFC on crDroid (Android 16, based on LineageOS), as it is set up now
# (read from the phone on 2026-11-02). It builds as it is, with no flake:
#   on the phone   Nixup app: Config, pick this file, then Build (or Git: clone the repository
#                  it is in; Pull and build brings its new commits); or as root in a shell:
#                  nixup-rebuild switch --file ./configuration.nix (dry-activate, --rollback, ...)
#   on a PC        nix run github:by-architect/Nixup -- switch --file ./configuration.nix
#                  (it asks which of the phones adb sees)
#
# A config of a phone's own, from what it has now (its settings and apps):
#   nix run github:by-architect/Nixup#nixup-config -- create
#
# As a start for another phone it builds there too: what that phone does not have (an app given
# settings here, a LineageOS setting, a sensor) is told while building and left out. Put in your
# own secrets first (marked "secret" below); they never go into this file.
#
# null: not managed, the phone keeps what you set by hand. An option taken out again goes back
# to the value it had before Nixup changed it. Every option, explained: nix/*.nix in
# github:by-architect/Nixup.
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

  # Apps from Google Play, by package name in quotes, downloaded as Aurora Store does, with the
  # Google account signed in on the phone (Nixup app: ⋮ → Google Play account): a build that lists
  # some stops until one is. No Play Store or GApps needed.
  android.packages.playstore = [
    # "com.whatsapp"                              # WhatsApp: messenger
  ];

  android.playstore = {
    account = null;                               # "you@gmail.com": builds stop if another one is signed in
    tokenFile = null;                             # ./google-play.token (nixup-play-login on a PC): no sign-in needed (secret)
  };

  # Updates of each source's apps: true installs them by itself (every 6 hours, on Wi-Fi), false
  # only notifies (the app's Updates tab installs them).
  android.settings.repo = {
    fdroid.autoupdatePackages = false;
    izzyondroid.autoupdatePackages = false;
    guardianproject.autoupdatePackages = false;
    byarchitect.autoupdatePackages = false;
    obtainium.autoupdatePackages = true;
    playstore.autoupdatePackages = false;
  };

  # The phone's other apps (sideloaded, debug builds, and Google Play apps not listed above) come
  # from no repository here, so Nixup leaves them as they are. F-Droid has its own builds of
  # Tailscale, KDE Connect, LocalSend, Organic Maps, Proton VPN and addy.io, signed with another
  # key than the ones installed: moving to them means reinstalling (the app's data goes).

  # Or strict: the phone has the apps of the lists above and no others. Each build removes the apps
  # installed by hand that no list has, nor keep, keeping their data and a copy of their APKs, so
  # enable = false again (or keep, or a list) brings one back as it was. Nixup's app, the root
  # solution's (Magisk, KernelSU, APatch) and the apps the config needs (users', DAVx5) stay.
  android.strict = {
    enable = false;                               # true: remove the apps no list has
    keep = [ ];                                   # apps to keep all the same, like "com.example.bank"
  };

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
    backup = null;                                # true, false, or { include = [ "data" ]; when = "manual"; ... }
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
    usbDebugging = true;                          # adb (false cuts it, also for nixup-rebuild)
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
  # Each app's APK, data, accounts and permissions, and folders, backed up by the phone itself into
  # a restic repository (encrypted, each file stored once). An app is backed up after it was used, a
  # build that installs it brings its data back, and an app not on any list comes back from its
  # backup. By hand, any app (its APK, its data) or folder: the app's Backups tab, or su -c
  # nixup-backup (list, status, now, restore, restic ...). Off while repository is null.
  android.backup = {
    repository = null;                            # "/storage/<card>/Backup", "sftp:me@pc:/backup/phone", ...
    passwordFile = null;                          # ./backup-password: keep a copy off the phone
    environmentFile = null;                       # ./backup-env: a remote repository's keys (NAME=value)
    initialize = true;                            # make the repository at the first backup
    apps = [ ];                                   # [ "org.example.notes" ]: backed up with the defaults
    folders = [ ];                                # [ "/storage/emulated/0/DCIM" ]: once a schedule
    defaults = {
      apk = true;                                 # its APK too: installable from the backup
      data = true;                                # false: the APK alone
      include = [ "data" "deviceData" "storage" "accounts" ];   # also "media", "obb"; "accounts": the ones it adds to Android (DAVx5's)
      when = "auto";                              # "auto": after use; "schedule"; "manual"
      autoRestore = false;                        # true (a new phone): data back when a build installs it, and backed-up apps reinstalled; never an app removed here
    };
    schedule = "daily";                           # "hourly", "daily", "weekly", "6h", ...
    quietTime = "10m";                            # "auto": this long in the background first
    keep = { last = 3; hourly = 0; daily = 7; weekly = 4; monthly = 0; yearly = 0; };
    onlyWhileCharging = false;                    # by itself only on the charger
  };

  # ==== 5. Scripts and services ================================================================
  # Services with NixOS's names and options, run from Nix packages, which the phone fetches from
  # cache.nixos.org (nixos-unstable) and runs where /nix exists. Logs: the app's Logs tab
  # (/data/adb/nixup/units). A service that stops by itself or fails is told in a notification, and
  # so is Tailscale's connection; the SSH server and adb, the phone's security, never are.

  services.tailscale = {
    enable = true;                                # Tailscale run by root, next to the Tailscale app (no VPN slot)
    package = pkgs.tailscale;                     # which build
    port = 41641;                                 # UDP port for direct connections; 0: any
    interfaceName = "userspace-networking";       # the only mode on a phone
    authKeyFile = "/data/adb/nixup/secrets/tailscale.key";   # secret: auth key, logs in without a link
    # (or ./tailscale.key: a file next to this one; or the key itself, read from elsewhere, like
    #  hostConfig.tailscale.key from a JSON of secrets; or pkgs.writeText "key" …, as a module shared
    #  with NixOS makes it: each build puts any of them on the phone, root-only)
    authKeyParameters = {                         # with an OAuth client secret as the key:
      ephemeral = null;                           #   gone from the tailnet when offline
      preauthorized = null;                       #   needs no approval
      baseURL = null;                             #   the API's address
    };
    extraUpFlags = [ ];                           # more `tailscale up` flags, like "--ssh"
    extraSetFlags = [ ];                          # `tailscale set` flags, like "--advertise-exit-node"
    extraDaemonFlags = [ ];                       # more tailscaled flags
    relayServerPort = null;                       # peer relay's UDP port, like 40000; "": off; null: unmanaged
    permitCertUid = null;                         # user that may fetch TLS certificates
    disableTaildrop = false;                      # true: no sending files
    disableUpstreamLogging = false;               # true: no logs to Tailscale
    openFirewall = false;                         # nothing to open on a phone
    useRoutingFeatures = "none";                  # exit node, subnet router: flags above do it here
  };

  services.syncthing = {
    enable = false;                               # true: Syncthing, run by root
    package = pkgs.syncthing;                     # which build
    cert = null;                                  # secret: ./syncthing/cert.pem, a fixed device ID (or the PEM, or pkgs.writeText's file)
    key = null;                                   # secret: ./syncthing/key.pem, with cert
    guiPasswordFile = null;                       # secret: the GUI's password
    guiAddress = "unix:///run/syncthing/gui.sock";   # GUI for root only (or its path alone); "127.0.0.1:8384": a browser
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
    relay.enable = false;                         # relay server: not on a phone (its other options, as on NixOS, are taken unused)
  };

  # DAVx⁵'s calendar and contacts accounts (DAVx⁵ is in the apps above), Nixup's own: NixOS has no DAVx⁵, an Android
  # app. A new account gets DAVx⁵'s login screen, opened on the phone filled in: tap Login, then Finish.
  services.davx5 = {
    enable = false;                               # true: the accounts below, set at each build (no service runs)
    install = null;                               # a repository to install DAVx⁵ from, like "fdroid"; null: android.packages lists it
    syncCollections = "all";                      # what DAVx⁵ selects for syncing as it finds it: "all" or "none"
    accounts = {
      # home = {
      #   url = "https://dav.example.org/";       # the server (DAVx⁵ finds its calendars and address books)
      #   userName = "neo";
      #   passwordFile = ./dav-password;          # secret: a file next to this one, put on the phone root-only
      #   accountName = null;                     # its name in DAVx⁵; null: as its login names it (the email address)
      #   automatic = false;                      # true: added with no taps (DAVx⁵ 4.5.17 to 4.5.21; else the login screen)
      # };
    };
  };

  # Snapcast, multi-room audio in sync. snapserver as NixOS's; snapclient (NixOS has none) plays a
  # server's stream through Android's audio, as music. nixpkgs' snapcast comes with PipeWire,
  # PulseAudio and Avahi: about 780 MB on the phone.
  services.snapserver = {
    enable = false;                               # true: serve streams to the network's clients
    package = pkgs.snapcast;                      # which build
    settings = {                                  # snapserver.conf's sections and keys, as on NixOS
      stream.source = [ "pipe:///run/snapserver/snapfifo?name=default" ];   # its streams: pipe, librespot, airplay, tcp, ...
      stream.sampleformat = "48000:16:2";         # rate:bits:channels
      stream.codec = "flac";                      # flac, ogg, opus, pcm
      stream.buffer = 1000;                       # ms the clients keep: the delay heard, and what covers a slow network
      tcp-streaming.port = 1704;                  # where clients connect
      tcp-control.enabled = true;                 # control (JSON-RPC) on 1705
      http.enabled = true;                        # Snapweb, a player and control page, on 1780
    };
    openFirewall = false;                         # nothing to open on a phone
  };

  services.snapclient = {
    enable = false;                               # true: play a snapserver's stream on the phone (speaker, headphones, Bluetooth)
    package = pkgs.snapcast;                      # which build
    host = "192.168.1.10";                        # the server's name or address (a phone cannot look for one); or "ws://server:1780"
    port = 1704;                                  # its tcp-streaming.port
    hostID = null;                                # its ID on the server; null: networking.hostName, or the device name
    instance = 1;                                 # another number for a second client
    latency = 0;                                  # ms to play earlier, on top of Android's own (measured): a Bluetooth speaker's
    sampleFormat = "48000:16:2";                  # what it plays (the stream is resampled to it)
    mixer = "software";                           # the server's volume for it; "none": Android's alone
    extraFlags = [ ];                             # more snapclient flags
  };

  # adb, Android's debugging bridge: on, over the network too (from each boot on), and the computers it lets in
  # without asking "Allow USB debugging?". NixOS has nothing like it, so it is Android's own: android.adb.
  android.adb = {
    enable = true;                                # adb on (USB debugging); null: not managed
    port = null;                                  # 5555: over the network too, from boot (adb connect <phone>:5555)
    trustedKeys = [ ];                            # computers let in without asking: each one's ~/.android/adbkey.pub ("QAAAA…= neo@laptop")
    trustedKeyFiles = [ ];                        # the same from files: ./laptop-adbkey.pub, or "/data/adb/nixup/secrets/adb_keys" on the phone
    forgetUnused = null;                          # false: keep every computer (Android forgets one unused for 7 days)
  };

  # SSH, as NixOS's services.openssh: logins get root's shell with the Nix packages below (environment.systemPackages),
  # the one the app's terminal opens. root is the phone's only user, and logs in with keys only.
  services.openssh = {
    enable = false;                               # sshd, at boot and with each build
    package = pkgs.openssh;                       # which build
    ports = [ 22 ];                               # its ports
    listenAddresses = [ { addr = "127.0.0.1"; port = 22; } ];   # 127.0.0.1: the tailnet reaches it (through Tailscale), Wi-Fi does not
    openFirewall = true;                          # nothing to open on a phone
    allowSFTP = true;                             # file transfers (file managers, sshfs)
    sftpServerExecutable = "${pkgs.openssh}/libexec/sftp-server";   # or "internal-sftp"
    sftpFlags = [ ];                              # flags for it
    hostKeys = [                                  # the server's keys, made on its first start and kept
      { type = "rsa"; bits = 4096; path = "/etc/ssh/ssh_host_rsa_key"; }
      { type = "ed25519"; path = "/etc/ssh/ssh_host_ed25519_key"; }
    ];
    banner = null;                                # text before a login
    authorizedKeysFiles = [ ];                    # more key files (in a folder only root can write to)
    authorizedKeysInHomedir = true;               # ~/.ssh/authorized_keys too (/data/adb/nixup/home/.ssh)
    authorizedKeysCommand = "none";               # a program that gives keys: not on the phone yet
    authorizedKeysCommandUser = "nobody";
    moduliFile = "${pkgs.openssh}/etc/ssh/moduli";   # Diffie-Hellman groups
    startWhenNeeded = false;                      # start on a first connection: not on the phone
    settings = {                                  # sshd_config's settings, by their names there
      PasswordAuthentication = false;             # true: users with a password (users.users.<name>.hashedPassword) log in with it, through PAM
      KbdInteractiveAuthentication = false;
      PermitRootLogin = "prohibit-password";      # root with keys only
      X11Forwarding = false;
      UseDns = false;                             # look up clients' names
      StrictModes = true;                         # refuse key files others can write to
      LogLevel = "INFO";                          # its log: /data/adb/nixup/units/sshd/log
      GatewayPorts = "no";
      PrintMotd = false;
      # UsePAM: on by itself with password logins (pam_unix, the view's /etc/shadow)
      Ciphers = null;                             # null: OpenSSH's own lists
      KexAlgorithms = null;
      Macs = null;
      AllowUsers = null;                          # only these may log in
      DenyUsers = null;
      AllowGroups = null;
      DenyGroups = null;
    };
    extraConfig = "";                             # more sshd_config lines
  };

  # Who may log in over SSH, as on NixOS: root, with these public keys.
  users.users.root.openssh.authorizedKeys = {
    keys = [ "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleOnlyPutYourOwnKeyHere neo@laptop" ];   # public keys (an example)
    keyFiles = [ ];                               # ./laptop.pub, or "/data/adb/nixup/secrets/ssh_keys" on the phone
  };

  # A user that logs in over SSH, as NixOS's users.users (an example): an Android app's own uid makes a user that
  # shares that app's files, storage access and su grant. Nixup's own app needs no other app: su works as Nixup's.
  users.users.niobe = {
    isNormalUser = true;                          # a user that logs in
    androidApp = "dev.nixup.manager";            # the uid of this app, looked up at each build (or uid = 10355;)
    description = "Niobe";                        # full name
    home = "/home/niobe";                         # its home: Nixup's own, there from the phone's start on
    createHome = true;                            # make the home, owned by it
    group = "niobe";                              # its own group (made with its uid)
    extraGroups = [ "inet" "everybody" "external_storage" ];   # network, shared storage (users.groups has Android's)
    openssh.authorizedKeys.keys = [ "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleOnlyPutYourOwnKeyHere neo@laptop" ];   # an example
    enable = true;                                # false: as if the user were not in the config
    hashedPassword = null;                        # its password, hashed with mkpasswd (for ssh with PasswordAuthentication)
    password = null;                              # or as text, which the phone hashes (in the profile, readable by root alone)
    hashedPasswordFile = null;                    # or ./niobe.hash: a file with its hash, put on the phone readable by root alone (secret)
    expires = null;                               # "2027-01-31": no logins from that day on
    linger = null;                                # false: its own units (systemd.user, Home Manager's) do not run
    autoSubUidGidRange = true;                    # 65536 subordinate ids of its own in /etc/subuid and /etc/subgid
    subUidRanges = [ ];                           # or its own: [ { startUid = 100001; count = 65534; } ]
    subGidRanges = [ ];
  };
  users.defaultUserHome = "/home";                # where normal users' homes are

  environment.extraInit = "";                     # what every shell session runs first, like 'export EDITOR=nano'

  # The app's terminal, as Nix-on-Droid's terminal options: its font (a file of a Nix package) and colors.
  terminal.font = null;                           # like "${pkgs.jetbrains-mono}/share/fonts/truetype/JetBrainsMono-Regular.ttf"
  terminal.colors = { };                          # like { background = "#000000"; foreground = "#FFFFFF"; cursor = "#FFFFFF"; color1 = "#CC0000"; }

  # Your own services, as NixOS's systemd.services. Example: the Download folder as a web page for
  # your tailnet. Bound to 127.0.0.1, Wi-Fi cannot reach it, but Nixup's Tailscale passes it on:
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
    unitConfig.RequiresMountsFor = [ "/storage/emulated/0/Download" ];   # storage it needs: it starts once that is mounted
    notifications = true;                         # Nixup's own: tell when it stops by itself, fails, or runs again
    serviceConfig = {
      Type = "simple";                            # keeps running ("oneshot": runs once)
      ExecStart = "${pkgs.darkhttpd}/bin/darkhttpd $SHARE --addr 127.0.0.1 --port 8080";   # what it runs (or script)
      Restart = "on-failure";                     # start again when it fails: "no", "on-failure", "always"
      RestartSec = 5;                             # seconds before that
      SuccessExitStatus = "3";                    # exit codes that are no failure
      RestartForceExitStatus = "3";               # exit codes that start it again whatever Restart says
      WorkingDirectory = "/var/lib/web-share";    # where it runs
      StateDirectory = "web-share";               # /var/lib/web-share, kept
      RuntimeDirectory = "web-share";             # /run/web-share, gone when it stops
      Environment = [ "TZ=Europe/Istanbul" ];     # more variables, "NAME=value"
      Nice = 10;                                  # priority: -20 first … 19 last; services run below the apps on the screen (10, on the background cores)
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

  # A service of its own user, with more of NixOS's unit settings (nix/units.nix lists all the phone takes).
  systemd.services.notes = {
    enable = false;                               # true: run it
    description = "Notes over HTTP, for the tailnet";
    wantedBy = [ "multi-user.target" ];
    serviceConfig = {
      DynamicUser = true;                         # its own user (uid 400–999: Android lets it on the network, not into the shared storage)
      StateDirectory = "notes";                   # /var/lib/notes, its user's ("CacheDirectory", "LogsDirectory" alike)
      LoadCredential = "token:/data/adb/nixup/secrets/notes-token";   # a file only it may read: $CREDENTIALS_DIRECTORY/token
      EnvironmentFile = "-/data/adb/nixup/secrets/notes.env";        # variables from a file ("-": it may be missing)
      ExecStart = "${pkgs.python3}/bin/python3 -m http.server 8081 --bind 127.0.0.1 --directory /var/lib/notes";
      UMask = "0027";                             # its files' mode
      LimitNOFILE = 4096;                         # its limits (Limit*)
      PrivateTmp = false;                         # true: a /tmp of its own (the others share one, as on NixOS)
      ProtectSystem = "strict";                   # what only hardens it on NixOS: taken, and nothing done
    };
    unitConfig.ConditionPathExists = "/data/adb/nixup/secrets/notes-token";   # starts only when this is there
  };

  # NixOS's own service modules, from nixpkgs, as they are: the ones Nixup has tried (nix/nixos.nix lists them) with
  # all their NixOS options. Each runs as its own user, so one that needs the phone's shared storage (a music folder)
  # gets root: systemd.services.<name>.serviceConfig.User = lib.mkForce "root". The small files a module makes (its
  # settings) the phone builds itself.
  services.ntfy-sh = {
    enable = false;                               # true: run it (push notifications, ntfy.sh's own server)
    settings.base-url = "http://logos:2586";      # its settings, as on NixOS
    settings.listen-http = "127.0.0.1:2586";
  };
  services.gotify.enable = false;                 # another: Gotify's push server
  # More of nixpkgs' modules, as on NixOS ({ modulesPath, ... }: at the top):
  #   imports = [ "${modulesPath}/services/web-apps/foo.nix" ];
  #   disabledModules = [ "services/misc/ntfy-sh.nix" ];   # leaves one of the list out, for a module of your own

  # Home Manager, as on NixOS (its own NixOS module, with all of its options): a user's files, programs and services
  # from here. With this file alone the phone fetches Home Manager, as it fetches nixpkgs (a flake: give it
  # home-manager.nixosModules.home-manager in its modules instead of the import):
  #   imports = [ <home-manager/nixos> ];
  #   home-manager.useGlobalPkgs = true;          # the phone's pkgs
  #   home-manager.backupFileExtension = "backup";   # a file in the way is moved aside, not refused
  #   home-manager.users.niobe = { pkgs, ... }: {
  #     home.stateVersion = "26.05";
  #     programs.git.enable = true;
  #     services.gpg-agent.enable = true;         # runs as niobe's own service (user-niobe-gpg-agent)
  #     systemd.user.paths.inbox = {              # starts systemd.user.services.inbox when ~/inbox changes
  #       Path.PathChanged = "%h/inbox";          # %h: niobe's home (systemd's %t, %S, %C, %E work too)
  #       Install.WantedBy = [ "paths.target" ];
  #     };
  #   };

  environment.etc = { };                          # files in Nixup's /etc, like { "foo/foo.conf".text = "a = 1"; }
  environment.variables = { };                    # variables of every shell session, like { EDITOR = "nano"; }

  # The shells, as on NixOS (with Nix packages; their files are NixOS's own: /etc/profile, /etc/bashrc, /etc/zshrc, ...).
  environment.shellAliases = { };                # aliases of every shell, like { ll = "ls -l"; } (ls, ll and l are there)
  environment.interactiveShellInit = "";          # lines every interactive shell runs, like 'set -o vi'
  environment.loginShellInit = "";                # lines every login shell runs
  environment.shellInit = "";                     # lines every shell runs
  environment.localBinInPath = false;             # true: ~/.local/bin on PATH
  environment.homeBinInPath = false;              # true: ~/bin on PATH
  # users.defaultUserShell = pkgs.zsh;            # the users' shell (needs programs.zsh.enable); not set: bash
  programs.bash.completion.enable = true;         # bash completes commands' arguments
  programs.zsh.enable = false;                    # zsh, with /etc/zshrc (a user whose shell is zsh needs it)
  programs.fish.enable = false;                   # fish, with /etc/fish (a user whose shell is fish needs it)
  # Programs, as NixOS's programs.<name> (nixpkgs' own modules, with Nix packages; nix/programs.nix lists them):
  programs.tmux.enable = false;                   # true: tmux, with /etc/tmux.conf (programs.tmux.extraConfig, ...)
  programs.git.enable = false;                    # git, with /etc/gitconfig (programs.git.config)
  programs.direnv.enable = false;                 # direnv's hook in the shells
  programs.starship.enable = false;               # starship's prompt in the shells
  # environment.defaultPackages = with pkgs; [ rsync ];   # more packages every Nix shell has (NixOS's: perl rsync strace)
  systemd.tmpfiles.rules = [ ];                   # folders the services need, like [ "d /var/lib/foo 0750 foo foo -" ]
  networking.hosts = { };                         # names in Nixup's /etc/hosts, like { "192.168.1.10" = [ "nas" ]; }
  networking.nameservers = [ ];                   # DNS servers of Nixup's services ([ ]: the network's), like [ "9.9.9.9" ]
  networking.hostFiles = [ ];                     # more files of names in Nixup's /etc/hosts, like [ "${pkgs.stevenblack-blocklist}/hosts" ]
  networking.stevenblack = {
    enable = false;                               # StevenBlack's blocklist of ad and tracker names in Nixup's /etc/hosts (its services and shells; not Android's apps)
    block = [ ];                                  # more lists: "fakenews" "gambling" "porn" "social"
    whitelist = [ ];                              # names left unblocked, like [ "s.click.aliexpress.com" ]
  };
  networking.proxy.default = null;                # the proxy of shells and Nix's downloads, like "http://127.0.0.1:3128"
  networking.proxy.noProxy = null;                # names that go without it, like "127.0.0.1,localhost,.home.arpa"

  environment.systemPackages = with pkgs; [ ];    # Nix packages for root's shell (the app's terminal, ssh, su -c nixup-shell), like htop rsync

  # Nix itself in the shells, as NixOS's nix.* options: nix shell nixpkgs#htop, nix profile add nixpkgs#htop,
  # nix-shell -p htop, nix-env -iA nixpkgs.htop (installed for the user, on PATH), from this config's nixpkgs.
  nix.enable = true;                              # Nix's commands in the terminal, ssh and nixup-shell (false: none)
  nix.settings = {                                # /etc/nix/nix.conf, as on NixOS
    experimental-features = [ "nix-command" "flakes" ];   # the nix command and flakes (on by default)
    substituters = [ ];                           # more binary caches (cache.nixos.org is always one)
    trusted-public-keys = [ ];                    # their keys, like "nix-community.cachix.org-1:mB9F…"
    max-jobs = "auto";                            # builds at once ("auto": one per core)
    sandbox = false;                              # Nix's sandbox: Android's kernels cannot (builds run as nixbld users)
  };
  nix.extraOptions = "";                          # more nix.conf lines, like "keep-outputs = true"
  nix.nrBuildUsers = 32;                          # nixbld users builds run as (a uid from 400 to 999 each)
  nix.gc = {                                      # remove what nothing uses (Nixup's own packages stay)
    automatic = false;                            # true: at the times below
    dates = "weekly";                             # when, like "03:15" or "weekly"
    options = "--delete-older-than 30d";          # what nix-collect-garbage gets
  };
  nix.optimise.automatic = false;                 # files the store has twice become one, at night
  nix.registry = { };                             # flakes by name, like { mine.flake = inputs.mine; } (nixpkgs: this config's)
  nix.channel.enable = true;                      # nix-channel, for channels of your own
  # nix.package = pkgs.nixVersions.latest;        # another Nix, from nixpkgs (not set: the module's own, nothing to download)
  nixpkgs.flake.setFlakeRegistry = true;          # nixpkgs#… in the shells is this config's nixpkgs (the phone keeps it)
  nixpkgs.flake.setNixPath = true;                # and <nixpkgs> (nix-shell -p, nix-env -iA nixpkgs.…)

  nixpkgs.config = { };                           # nixpkgs settings, like { allowUnfree = true; }
  nixpkgs.overlays = [ ];                         # changed packages (only what cache.nixos.org has)
  nixpkgs.hostPlatform = "aarch64-linux";         # the phone's processor: 64-bit ARM

  # Builds by themselves, as NixOS's system.autoUpgrade: the config built again with everything at its newest.
  system.autoUpgrade = {
    enable = false;                               # true: build again at dates
    dates = "04:40";                              # when, like "daily", "Sun 03:00" (systemd's calendar events)
    randomizedDelaySec = "0";                     # a random wait before each, like "45min"
    fixedRandomDelay = false;                     # true: the same wait each time
    persistent = true;                            # a time missed while the phone was off comes once it is on
    upgrade = true;                               # false: build the config as flake.lock locks it
    flake = null;                                 # another config, like "github:you/phone#logos"; null: the picked one (a git clone gets its new commits first)
    channel = null;                               # nixpkgs of a configuration.nix alone, like "https://channels.nixos.org/nixos-26.05"
    runGarbageCollection = false;                 # true: then frees up space (the app's Free up space)
  };
  android.autoUpgrade = {
    onlyOnUnmetered = true;                       # build only on Wi-Fi (never on mobile data)
    onlyWhileCharging = false;                    # true: only while charging
  };

  # Machines that build the packages cache.nixos.org has not got (an overlay's, an unfree one), as NixOS's: over ssh.
  nix.distributedBuilds = false;                  # true: use nix.buildMachines; false: such a package stops the build
  nix.buildMachines = [
    # {
    #   hostName = "builder.example.org";         # its address (a tailnet name works)
    #   sshUser = "builder";                      # one of its nix.settings.trusted-users
    #   sshKey = "/data/adb/nixup/secrets/builder_ed25519";   # a key on the phone, readable by root (secret)
    #   systems = [ "aarch64-linux" ];            # the phone's packages' system (a PC builds it with binfmt emulation)
    #   publicHostKey = "c3NoLWVkMjU1MTkg…";      # base64 -w0 /etc/ssh/ssh_host_ed25519_key.pub on it
    #   maxJobs = 4;
    # }
  ];

  # Scripts, as NixOS's: each build and each start of the phone runs system.activationScripts as root (in Nixup's view,
  # before the services start); system.preSwitchChecks run before a build changes anything (one that fails stops it);
  # each normal user runs system.userActivationScripts. Examples (each would run on the phone, so they are comments):
  #   system.activationScripts.stay-awake = {
  #     text = "settings put global stay_on_while_plugged_in 3";   # what it does (bash): idempotent and fast
  #     deps = [ "users" ];                                        # the scripts it runs after (NixOS's names too)
  #     supportsDryActivation = false;                             # true: a dry run runs it too (NIXOS_ACTION=dry-activate)
  #     undo = "settings put global stay_on_while_plugged_in 0";   # Nixup's own: puts it back when it leaves the config, or Nixup goes
  #   };
  #   system.preSwitchChecks.battery = ''[ "$(cat /sys/class/power_supply/battery/capacity)" -ge 20 ]'';   # $1: the profile, $2: switch or dry-activate
  #   system.userActivationScripts.notes = "mkdir -p ~/notes";   # as each normal user, when it changes and at each start
  system.activationScripts = { };                 # scripts at each build and start, as root
  system.preSwitchChecks = { };                   # checks before a build changes anything
  system.userActivationScripts = { };             # scripts each normal user runs

  # The system's version and where it was built from, as on NixOS: the app's Generations shows each one's label and
  # the config's commit, nixup-version tells them, and /etc/os-release has them (nix/version.nix).
  system.stateVersion = "26.05";                  # the release of your first build: leave it there (NixOS's modules keep its defaults)
  system.nixos.tags = [ ];                        # words before the version in each generation's label, like [ "travel" ]
  system.configurationRevision = null;            # the config's git commit; in a flake: self.rev or self.dirtyRev or null
  system.copySystemConfiguration = false;         # true: each generation keeps a copy of this configuration.nix (not with a flake)

  # Other versions of this config, built with it: switch with ⋮ → Specialisations (no new build),
  # or build.sh / nixup-rebuild --specialisation work. The phone keeps the one picked, also after
  # a restart, until a build picks another (or none: this config itself).
  specialisation = {
    # work.configuration = {                      # this config, with these lines over it
    #   services.syncthing.enable = false;
    #   android.settings.display.darkMode = true;
    # };
    # bare = {
    #   inheritParentConfig = false;              # only its own lines, not this config's
    #   configuration = { };
    # };
  };
}
