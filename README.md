## Aim Of The Project
This project aims to close the gap in Android's Nix system. You can install the Nix system on a phone as Nixos, but this doesn't support every phone. Nix on Droid, on the other hand, doesn't provide the necessary permissions to access Android. Some things that are needed in Android are missing. The missing elements I've identified are: a native application backup system and configurations (yes, NeoBackup does this well, but the Nix system gives it the advantage of scripting), downloading applications from systems like fdroid using configuration, stable background services and scripts, application settings, etc. This project is designed to address all of these issues.

## How it works
#### Repos And Android Apps
The system works like that: You write package name into repo's pkgs, and build the config
Currently fdroid, izzyondroid, obtanium and custom repos supported. Aurora/Play store is not supported yet, which is the hardest and maybe most important part of this project. (Helps will be accepted about that)
```nix
{ pkgs, ... }: {
  # 1. Repositories: F-Droid-style app stores, each under a name you choose.
  #    f-droid.org is always there as "fdroid"; add others with url?fingerprint=<its key>.
  android.repos.izzyondroid.url =
    "https://apt.izzysoft.de/fdroid/repo?fingerprint=3BF0D6ABFEAE2F401707B6D966BE743BF0EEE49C2561B9BA39073711F628937A";

  # 2. Apps from each repository: pkgs.<repo name>.<package name>
  android.packages.fdroid = with pkgs.fdroid; [
    com.termux
  ];
  android.packages.izzyondroid = with pkgs.izzyondroid; [
    at.bitfire.davdroid
  ];

  # 3. Apps from their developers' own releases (GitHub, GitLab, Codeberg), as Obtainium does
  android.packages.obtainium = [
    { id = "dev.imranr.obtainium"; url = "https://github.com/ImranR98/Obtainium"; }
  ];

  # 4. Updates: true installs them by itself (every 6 hours, on Wi-Fi), false only notifies
  android.settings.repo.fdroid.autoupdatePackages = true;

  # 5. One app's settings (optional)
  android.apps."com.termux".battery = "unrestricted";
}
```
What a build does with it:

1. Fetches the repositories' indexes. Each repository's app list is downloaded to the phone and checked against its fingerprint, so a fake repository can't slip in. Indexes are refreshed at most once a day; Upgrade forces it.
2. Evaluates the config. pkgs.izzyondroid.at.bitfire.davdroid only exists if that repository really has that app, so a typo stops the build with a clear error before anything changes.
3. Plans. For each app it picks the version to install (the repository's suggested one, signed with the right key) and compares it with what's on the phone: install, update, or already up to date. Releases apps get their newest GitHub/GitLab release. Dry run stops after this step and shows the plan.
4. Checks. An app already on the phone must have the same signing key as the repository's build; if not, the build says so and changes nothing. It also checks there's enough space.
5. Installs. It downloads each APK, verifies its checksum (from the signed index) and its signing key, and installs it, with progress notifications. Apps that updaters manage (F-Droid, Obtainium) go first.
6. Removes. An app Seraph installed that you've taken out of the config is uninstalled, keeping its data. Apps you installed yourself are never touched.
7. Applies app settings, then records the versions in a new generation.

After the build:
- Every 6 hours the phone checks for newer versions. Repositories set to autoupdatePackages = true update by themselves; the others only notify you, and the Updates tab installs them.
- Apps from Google Play, sideloaded apps and your own builds aren't in any repository, so Seraph leaves them alone (unless you give them settings with android.apps."<id>").
- A package name starting with a Nix keyword needs quotes, for example "in.sunilpaulmathew.izzyondroid".

#### App Settings
Settings spesific to android apps. (you can still change settings, but every build will revert changes)
```nix
{
  # Settings, by package name: any installed app, from a repository or not
  android.apps."org.thoughtcrime.securesms" = {
    permissions = {
      camera = true;              # true: allowed, false: denied, "ask": asks each time
      microphone = "ask";
      location = false;           # "always", "whileUsing", false, "ask"
      contacts = true;
    };
    battery = "unrestricted";     # "unrestricted", "optimized", "restricted"
    network.backgroundData = true;
  };

  # A default app: browser, dialer, sms, home, assistant, callScreening
  android.apps."org.mozilla.fennec_fdroid".defaultFor = [ "browser" ];

  # Special access, and the app's own language
  android.apps."com.termux" = {
    access.displayOverOtherApps = true;
    language = "en-US";           # "system": the phone's language
  };

  # Remove an app the phone came with (its data kept; brought back when the line goes)
  android.apps."org.lineageos.etar".debloat = true;

  # Disable one (hidden, can't run)
  android.apps."com.android.contacts".enabled = false;
}
```
What a build does:
1. Reads the current value of every setting you named. The first time, it records that value as the "original", the one given back later.
2. Plans: it compares each value with the config. It changes only what differs; the rest shows as "as the config says". Dry run stops here.
3. Checks:
   - An app that isn't installed is reported and left out, not an error; its settings apply once it's installed.
   - A setting the phone can't have, like a LineageOS-only one on another ROM or a permission the app never asks for, is reported and skipped.
   - Two apps both claiming defaultFor = [ "browser" ] stop the build.
4. Applies, then reads each value back to confirm it took. Anything that didn't take is reported, and building again retries it.
5. Later:
   - Take a setting out of the config and the next build restores its original value.
   - Remove the module and all of them go back.
   - Change one by hand and the next build sets it back to the config's value.

For an app the build is installing right now, its settings are applied right after the install.

#### System Settings
Almost all important android settings available. You can check template for all options.
```nix
{
  # With NixOS's names: a module shared with a NixOS machine sets them on both
  networking.hostName = "phone";                 # device name (also its tailnet name)
  time.timeZone = "Europe/Istanbul";             # null: from the network
  i18n.defaultLocale = "en_US.UTF-8";            # phone language

  android.settings.display = {
    darkMode = true;
    fontScale = 1.1;                             # 1.0 normal
    screenTimeout = 60;                          # seconds
    autoRotate = false;
  };

  android.settings.navigation.mode = "gestures"; # "threeButton", "twoButton"

  android.settings.sound = {
    touchSounds = false;
    touchVibration = true;
  };

  android.settings.time.format24h = true;

  # Anything else, by Android's own name (what `adb shell settings put` takes)
  android.settings.raw.global.stay_on_while_plugged_in = 0;

  # LineageOS-based ROMs only (crDroid, ...)
  android.settings.lineage.statusBar.batteryPercent = "inside";
}
```
The groups:
- NixOS names: networking.hostName, time.timeZone, i18n.defaultLocale. The Android-named versions (android.settings.deviceName, .language, .time.timeZone) override them.
- android.settings:
  - display: dark mode, font size, density, timeout, refresh rate, night light, screen saver;
  - navigation, sound, time and keyboard;
  - notifications, lockScreen, location, network (Wi-Fi scanning, Data Saver);
  - gestures, accessibility and developer (USB debugging, animation speed);
  - quickSettings (the tile list) and statusBar;
  - autofill.
- android.settings.raw: any setting in Android's system, secure and global tables, or LineageOS's, by its exact name, for anything without a friendly option.
- android.settings.lineage: status bar, quick settings, buttons, power menu, charging control, lock screen, and more, on LineageOS-based ROMs.

What a build does:
1. Reads each setting's current value. The first time it manages a setting, it records that value as the original.
2. Plans: it changes only what differs, so most lines read "as the config says". Dry run stops here.
3. Checks:
   - A setting the phone can't have (needs a newer Android, LineageOS, or hardware like an always-on display) is reported while building and skipped, so the same config works on other phones.
   - A wrong value (a typo, a numuild before anything changes.
4. Applies, then reads each value back to confirm. Settings that need it (density, navigation
   mode, language) are applied th not just written to the table.
5. Later:
   - Take a line out of the confi original value back.
   - Remove the module and every managed setting goes back.
   - Change a managed setting by each boot, for dark mode) sets itback to the config.
  
#### Packages And Terminal
```nix
{ pkgs, ... }: {
  # Programs for root's shell: the app's terminal, ssh sessions, su -c seraph-shell
  environment.systemPackages = with pkgs; [
    htop
    rsync
    git
    vim
    python3
  ];

  # Optional: nixpkgs' own settings, as on NixOS
  nixpkgs.config.allowUnfree = false;

  # Optional: what every shell session runs first
  environment.extraInit = ''export EDITOR=vim'';
}
```
What a build does with packages:
1. Gets nixpkgs.
   - A plain configuration.nix gets the phone's own copy of nixos-unstable, fetched the first time a config uses packages and refreshed by Upgrade.
   - A flake uses its own nixpkgs input, pinned by flake.lock.
2. Evaluates. pkgs.htop and the rest become exact store paths for the phone's processor (aarch64-linux). A package that doesn't exist stops the build with Nix's error.
3. Fetches from cache.nixos.org into Seraph's store on the phone (/data/adb/seraph/nix), with everything each package needs. Nothing is compiled on the phone: a package the cache doesn't have stops the build before anything changes. That includes unfree packages, overlays, or a package you changed. Dry run lists what would be downloaded.
4. Sets up the shell: root's shell gets the packages on PATH, plus bash from Nix.
5. Keeps them: the current build's packages are protected from cleanup. Free up space in the menu removes ones no build uses any more, such as packages from older generations or a service you turned off.

Android's own folders have no /nix, so the packages run inside Seraph's view of the phone. It's the same phone (/data, /storage, /system, your files, Android's commands), with /nix, Seraph's own /etc (DNS, certificates) and its own /var and /run added. Services run in the same view.

The terminal:
- Opening it:
  - the terminal icon in Seraph's top bar opens it;
  - from adb or any root shell, su -c seraph-shell gives the same shell;
  - ssh root@phone too, with services.openssh.
- What you get:
  - a root bash with your environment.systemPackages, then Android's commands;
  - its home is /data/adb/seraph/home;
  - its prompt shows the phone's
- Sessions keep running when you close the terminal, with a notification, and you can oseveral.
- Keys: an extra row with Ctrl, Esc, Tab and the arrows; Ctrl+C interrupts.            Seraph's own commands work ther
  - seraph-backup list, status, now, restore…                                          - nix itself, against Seraph's
                                                                                       ings to know:
- Packages are for the shell and services, not phone apps; apps come from repositories (android.packages).
- Programs built for desktop Linux mostly work in the terminal; ones that need a screen (X11 or Wayland) don't.
- A package's first download can be large. Python, for example, brings its whole runtime; later builds only fetch what changed.

#### Services And Scripts
Custom Services belong to nix. There is no a real systemd working. A bridge converts service to android service system.
```nix
{ pkgs, ... }: {
  # A service that keeps running
  systemd.services.web-share = {
    description = "Download folder as a web page";
    wantedBy = [ "multi-user.target" ];          # start at boot and with each build
    after = [ "tailscaled.service" ];            # start after these (when they start too)
    wants = [ "tailscaled.service" ];            # start these with it
    path = [ pkgs.darkhttpd ];                   # programs on its PATH
    environment.SHARE = "/storage/emulated/0/Download";
    serviceConfig = {
      ExecStart = "${pkgs.darkhttpd}/bin/darkhttpd $SHARE --addr 127.0.0.1 --port 8080";
      Restart = "on-failure";                    # "no", "on-failure", "always"
      RestartSec = 5;
      StateDirectory = "web-share";              # /var/lib/web-share, kept
    };
  };

  # A one-time job: runs to its end at each build and boot
  systemd.services.say-hello = {
    wantedBy = [ "multi-user.target" ];
    serviceConfig.Type = "oneshot";
    script = ''
      echo "built at $(date)" > /var/lib/hello.txt
    '';
  };
}
```

What a unit can say (anything else stops the build with a message):
- description, script, preStart, postStart, environment, path
- wantedBy (only multi-user.target: start at boot and with each build), after, before, wants, requires, restartTriggers, enable
- serviceConfig:
  - ExecStart, ExecStartPre, ExecStartPost, ExecStop;
  - Type (simple, exec, oneshot), RemainAfterExit;
  - Restart, RestartSec;
  - WorkingDirectory, StateDirectory, RuntimeDirectory;
  - Environment and Nice.
- Seraph's own: notifications = false; turns off its notifications.


```nix
{ pkgs, ... }: {
  # Tailscale: tailscaled run by root, in userspace mode (no VPN slot, the Tailscale app can stay)
  services.tailscale = {
    enable = true;
    authKeyFile = ./tailscale.key;      # logs in by itself; without it, the build shows a login link
    extraUpFlags = [ "--ssh" ];         # more `tailscale up` flags
  };

  # Syncthing, set up from the config through its API, as on NixOS
  services.syncthing = {
    enable = true;
    settings.devices.laptop.id = "ABCDEFG-…";
    settings.folders.documents = { path = "/storage/emulated/0/Documents"; devices = [ "laptop" ]; };
  };

  # SSH: logins get root's shell with your Nix packages
  services.openssh = {
    enable = true;
    listenAddresses = [ { addr = "  # tailnet only, through Tailscale
  };
  users.users.root.openssh.authori9 AAAA… you@laptop" ];

  # Snapcast: play a server's streer, headphones or Bluetooth
  services.snapclient = { enable = true; host = "192.168.1.10"; };
  # services.snapserver = { enablerce = ["pipe:///run/snapserver/fifo?name=default" ]; };

  # adb (Android's own, not NixOS's): over the network from boot, trusted computers
  android.adb = { enable = true; p"QAAAA…= you@laptop" ]; };
}
```

What a build does:
1. Checks: names, types, options the phone doesn't have, and that commands are full paths. Problems stop the build before anything changes.
2. Fetches every Nix package the units name from cache.nixos.org.
3. Plans: units that are new or changed (start), removed (stop), or unchanged and running (left alone). Dry run stops here.
4. Starts them in order (after, before, requires). A service counts as up if it's still running after 1.5 seconds; a oneshot must finish with exit 0 (within 10 minutes).
5. At every boot, the units the last build left start again. One that fails at boot is retried after 30 seconds, 1.5 and 4 minutes.

Systemd services that belongs to nixos are not directly supported. There is no global bridge for that. Every service needs its own bridge. Currently available services: tailscale, syncthing, ssh, adb, snapserver, snapclient. Except snapclient, other services have same options with nixos services. One service file will work in main nixos and seraph's android nix system.

#### Backups
```nix
{
  android.backup = {
    repository = "/storage/EF27-E719/Backup/Seraph";   # SD card, phone storage, or "sftp:me@pc:/backup/phone", "s3:…", "rclone:…"
    passwordFile = ./backup-password;                  # next to your config; keep a copy off the phone
    apps = [ "org.thoughtcrime.securesms" "at.bitfire.davdroid" ];
    folders = [ "/storage/emulated/0/DCIM" ];
  };

  # Optional: one app its own way
  android.apps."com.termux".backup = { include = [ "data" ]; when = "manual"; };
}
```

What a backup takes:
- For an app:
  - its APK (with splits), so it can be reinstalled from the backup;
  - its data: data (its private files), deviceData, storage (Android/data), and optionally media and obb;
  - caches are left out.
- For a folder: its files.
- Each backup is tagged with the app's version and the parts it has.

When it happens (checked every 15 minutes by the app's job):
- when = "auto" (default): after you used the app, once it's been in the background for quietTime (10 min), at most once an hour, and at least once per schedule (daily).
- "schedule": once per schedule. "manual": only by hand.
- An app is also backed up again after it's updated.
- Folders: once per schedule.
- Only what changed is read and stored again.

Before each backup:                                                                      - What's needed is checked:
  - the repository's storage is there (SD card inserted);                                  - it has room for what's new, wpt free;
  - for automatic ones, the phone isn't short of memory or hot.                          - If not: it waits and tries agaiups tab says why, and a full ormissing card also sends a notification.                                                - An app that's idle is paused foits files are consistent. An app in use is copied as it is, so the phone never stutters.                                   - Backups run at the lowest priorcores.
- A restart in the middle: the backup picks up again after you unlock the phone.         
Notifications: "Automatic backup: backing up 3 apps", then "Automatic backup finished: 3 succeeded, 1 failed". Details are

Restoring, always by hand:
- The Backups tab:
  - each config app and folder, wy backup in the repository;
  - Restore: pick the backup, then the parts (APK, data, Android/data…);
  - folders go back into themselv
- Shell or adb:
  - su -c "seraph-backup restore  newest backup: its data, and itsAPK if it isn't installed;
  - add a backup ID, or --parts=a
  - seraph-backup list, status, now and restic … do the rest.
- A backup from a newer app versirefused, since an older app can'tread newer data.
- Restored files get the app's ow

Automatic restore: off unless youautoRestore = true;, meant forsetting up a new phone. A build then reinstalls backed-up apps and brings their data back. An app
this phone backed up and that youht back by itself.

By hand anytime: Back up all, or hoosing its parts) or any folder.

More settings:
- defaults: apk, data, include, when, autoRestore;
- schedule, quietTime, keep (like= 7; weekly = 4; }), andonlyWhileCharging;
- environmentFile (a remote repos(makes the repository at the firstbackup).

Keep passwordFile somewhere besides the phone: without it, the backups can't be read. Backups stay in the repository when the module or the option is removed.

#### Nix Building, Switching, Etc.

What a build does, in order:
1. Reads the config you picked: a file, a flake folder, a flake address (github:you/phone), or a Git clone. Pull and build fetches new commits first.
2. Refreshes the app repositories' indexes, as channels are refreshed.
3. Evaluates with Nix on the phone: option types, assertions and NixOS-named options. Any error stops here, and nothing has changed.
4. Plans and checks everything: apps (signing keys, space), app settings, system settings, secrets, services, adb and backups. Dry run ends here, showing exactly what would change.
5. Fetches the Nix packages from cache.nixos.org; nothing is compiled on the phone.
6. Switches:
   - secret files first, then apps (installed, updated, or removed if Seraph installed them), with data restored if you turned that on;
   - then app settings, debloat, system settings and adb;
   - then services stopped or started.
   - Each setting is read back to confirm it took.
7. Saves a generation if anything changed. It records the profile, the app versions, what it was built from (file, flake and commit, or the PC it came from) and the flake's lock.

You get a notification with a summary ("Built generation 74: 2 apps installed, system settings"), and the build's own log is in the Logs tab.

Going back: Generations lists every build with its date, source and PC.
- Dry run shows what switching back to one would change.
- Switch to it applies that generation's profile again, without evaluating anything. Its apps come back at their newest versions; their exact old versions aren't kept.
- Switching back makes a new generation, as nixos-rebuild --rollback does.

Reverting single settings: delete a line from the config and build. Seraph puts back the value that setting had before Seraph first changed it (it records that original). The same goes for an app Seraph installed (removed, data kept) and an app it debloated (brought back).

Reverting everything: remove the module in Magisk and restart. Every setting goes back and
Seraph's apps are removed; your c

Built elsewhere: seraph-rebuild erofile and secrets over adb or ssh, and the phone applies it as if it had built it. If the last build came from a PC, the app's
Build asks whether to apply that ig on the phone.

Interrupted: a build cut off by alf once the phone is unlocked andonline. If it's cut off again, you get a notification instead, so a bad build can't loop.       
Where things live: everything is in /data/adb/seraph: generations, the profile in use, logs and Nix's store. Your config is never flake.lock when you upgrade it.

## Install And Setup

Install
1. Root the phone with Magisk, KernelSU or APatch. It needs Android 11 or newer, 64-bit ARM.
2. Download seraph-v<version>.zip from the GitHub releases.
3. Magisk → Modules → Install from storage → pick the zip.
4. Restart the phone.
5. Open Seraph, allow root, allow notifications.

Set up on the phone
1. Build tab → New, which writes a starter configuration.nix; or Pick file, or Git for your repository.
2. Edit the config. config/template.nix has every option.
3. Dry run to see what would change.
4. Build to apply it.
5. After that: edit, then Build again. Generations goes back.

Set up from a PC over adb
1. Turn on USB debugging, connect, allow the PC.
2. Build on the PC and apply on the phone:
nix run github:by-architect/Seraph -- switch --flake .#phone
   Use dry-activate instead of switch for a dry run, and add --upgrade for the newest versions.
3. Or let the phone build its own config:
adb push configuration.nix /sdcard/Seraph/configuration.nix
adb shell su -c "echo /storage/emulated/0/Seraph/configuration.nix > /data/adb/seraph/source"
adb shell su -c "sh /data/adb/modules/seraph/build.sh adb"         # add: dry, update
4. Over ssh instead: seraph-rebuild switch --flake .#phone --target-host phone.

Seraph's commands over adb (as root):
adb shell su -c seraph-shell                      # root shell with your Nix packages
adb shell su -c "seraph-backup status"            # backups: list, status, now, restore, restic
adb shell su -c "seraph-backup restore org.example.app"
adb shell su -c "sh /data/adb/modules/seraph/units.sh adb list"       # services: start, stop, restart, log
adb shell su -c "sh /data/adb/modules/seraph/update.sh adb check"     # app updates: check, now
adb shell su -c "tail -n 50 /data/adb/seraph/apply.log"               # the log
seraph-shell and seraph-backup work after the first restart with the module installed.
