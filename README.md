# Seraph

Configure an Android phone with Nix, as NixOS does: apps, app settings, system settings, services and
backups, all from one `configuration.nix`.

> **Warning:** this project is still in development. Back up everything first. I am not responsible for any
> data loss.

## Aim Of The Project

This project aims to close the gap between Android and Nix. You can install NixOS on a phone, but it does
not support every phone. Nix-on-Droid, on the other hand, does not have the permissions needed to manage
Android itself. What I found missing: a native app backup system driven by the config (NeoBackup does
backups well, but Nix adds scripting to it), installing apps from F-Droid-like repositories from a config,
stable background services and scripts, app settings, and more. Seraph is built to cover all of these.

## Requirements

- A rooted phone: Magisk (tested), KernelSU or APatch.
- Android 11 or newer, 64-bit ARM (arm64).
- A network connection for builds.
- Nix on a PC only if you want to build there (optional).

## How It Works

You write a config, press **Build** in the Seraph app, and the phone is changed to match it. Every build is
checked before anything changes, and saved as a **generation** you can go back to. Take a line out of the
config and the next build puts that setting back the way it was before Seraph.

<details>
<summary><b>Repos And Android Apps</b></summary>

Write each app's package name into its repository's list, then build. F-Droid, IzzyOnDroid, Obtainium-style
releases (GitHub, GitLab, Codeberg), custom F-Droid repositories and Google Play (as Aurora Store downloads
from it) are supported.

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

  # 4. Apps from Google Play, by package name in quotes (see Google Play below)
  android.packages.playstore = [ "com.whatsapp" "org.telegram.messenger" ];

  # 5. Updates: true installs them by itself (every 6 hours, on Wi-Fi), false only notifies
  android.settings.repo.fdroid.autoupdatePackages = true;
}
```

- Each repository's app list is checked against its fingerprint, and each APK against its checksum and
  signing key, so nothing fake can slip in.
- A typo in a package name stops the build before anything changes.
- An app you take out of the config is uninstalled (its data is kept). Apps you installed yourself are never
  touched.
- A package name starting with a Nix word needs quotes, like `"in.sunilpaulmathew.izzyondroid"`.

</details>

<details>
<summary><b>Google Play</b></summary>

Apps from Google Play are downloaded by the phone itself, as Aurora Store does, and installed and updated like
the other apps. No Play Store, Google Play services or GApps are needed on the phone; a Google account is. Sign
in once: Seraph app → ⋮ → **Google Play account** → **Sign in** (Google's own sign-in page). Then **Show
token** gives the account's token, to copy or save to Downloads, for a config on a PC. Or sign in on a PC:

```sh
nix run github:by-architect/Seraph#seraph-play-login     # opens Google's page, then saves ./google-play.token
```

A build whose config lists apps from Google Play checks the account first: with none, or a token Google
refuses, it stops before changing anything and says to sign in.

```nix
{
  android.packages.playstore = [ "com.whatsapp" "org.telegram.messenger" ];

  android.playstore.account = "you@gmail.com";     # optional: builds stop if another account is signed in
  android.playstore.tokenFile = ./google-play.token; # optional: the account's token, so the phone needs no sign-in

  android.settings.repo.playstore.autoupdatePackages = true;
}
```

- The phone tells Google Play what it is (Android version, processor, screen, languages), so it gets the split
  APKs made for it, and each file is checked against the SHA-256 Google Play lists, its signing key against
  Google Play's.
- The account's token stays on the phone, readable by root only; signing out removes it. Anyone with the token
  can use the account: keep a copy out of any repository others can read.
- If the sign-in page says the app has no internet while the phone is online, the Seraph app's network access
  is off (some ROMs switch it per app): turn it on in its App info; the downloads themselves don't need it.
- Use a Google account made for this rather than your main one: Google may block accounts that download
  with unofficial clients (Aurora Store's FAQ warns about the same).
- Not supported yet: apps that need expansion files (OBB, mostly large games); such an app stops the build
  with a message.
- An app installed from F-Droid (signed with another key) cannot be updated from Google Play: the build says
  so.

</details>

<details>
<summary><b>App Settings</b></summary>

Settings for single apps. You can still change them by hand, but the next build sets them back to the
config.

```nix
{
  android.apps."org.thoughtcrime.securesms" = {
    permissions = {
      camera = true;              # true: allowed, false: denied, "ask": asks each time
      microphone = "ask";
      location = false;           # "always", "whileUsing", false, "ask"
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

  # Remove an app the phone came with (its data is kept; it comes back when the line goes)
  android.apps."org.lineageos.etar".debloat = true;

  # Disable one (hidden, can't run)
  android.apps."com.android.contacts".enabled = false;
}
```

- An app that is not installed is skipped; its settings are applied once it is installed.
- A setting the phone or the app does not have is reported and skipped.

</details>

<details>
<summary><b>System Settings</b></summary>

Almost all important Android settings are available; the [full template](config/template.nix) lists them all.

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
  };

  android.settings.navigation.mode = "gestures"; # "threeButton", "twoButton"
  android.settings.sound.touchSounds = false;
  android.settings.time.format24h = true;

  # Anything else, by Android's own name (what `adb shell settings put` takes)
  android.settings.raw.global.stay_on_while_plugged_in = 0;

  # LineageOS-based ROMs only (crDroid, ...)
  android.settings.lineage.statusBar.batteryPercent = "inside";
}
```

- Each setting is read back after it is changed, to be sure it took.
- A setting the phone cannot have (it needs a newer Android, LineageOS, or some hardware) is skipped, so one
  config works on several phones.

</details>

<details>
<summary><b>Packages And Terminal</b></summary>

Programs from nixpkgs for the phone's shell, as NixOS's `environment.systemPackages`:

```nix
{ pkgs, ... }: {
  environment.systemPackages = with pkgs; [ htop rsync git vim python3 ];
}
```

- Packages are downloaded from cache.nixos.org; nothing is compiled on the phone.
- They are for the shell and services, not phone apps (apps come from repositories, above).
- The **terminal** (the icon in Seraph's top bar) is a root shell with these packages. The same shell:
  `su -c seraph-shell` from adb, or `ssh` with `services.openssh`.

</details>

<details>
<summary><b>Services And Scripts</b></summary>

There is no real systemd on Android: Seraph runs NixOS-style `systemd.services` itself, with the same
syntax.

```nix
{ pkgs, ... }: {
  # A service that keeps running
  systemd.services.web-share = {
    description = "Download folder as a web page";
    wantedBy = [ "multi-user.target" ];          # start at boot and with each build
    after = [ "tailscaled.service" ];            # start after these
    path = [ pkgs.darkhttpd ];                   # programs on its PATH
    serviceConfig = {
      ExecStart = "${pkgs.darkhttpd}/bin/darkhttpd /storage/emulated/0/Download --addr 127.0.0.1 --port 8080";
      Restart = "on-failure";                    # "no", "on-failure", "always"
      RestartSec = 5;
    };
  };

  # A one-time job (a script): runs to its end at each build and boot
  systemd.services.say-hello = {
    wantedBy = [ "multi-user.target" ];
    serviceConfig.Type = "oneshot";
    script = ''
      echo "built at $(date)" > /var/lib/hello.txt
    '';
  };
}
```

- Supported: `script`, `preStart`, `postStart`, `environment`, `path`, `wantedBy`, `after`, `before`,
  `wants`, `requires`, and in `serviceConfig`: `ExecStart`, `ExecStartPre`, `ExecStartPost`, `ExecStop`,
  `Type` (simple, oneshot), `Restart`, `RestartSec`, `WorkingDirectory`, `StateDirectory`,
  `RuntimeDirectory`, `Environment`, `Nice`. Anything else stops the build with a message.
- Services start again at boot, run below the app on the screen (so the phone does not stutter), and send a
  notification when they stop by themselves.
- Start, stop, restart and logs: the app's **Services** tab.

</details>

<details>
<summary><b>Ready-Made Services</b></summary>

NixOS's own services need their own bridge each. Available now: Tailscale, Syncthing, SSH, Snapcast
(server and client) and adb. Except for snapclient (which NixOS does not have), they take the same options as
on NixOS, so one module file can work on a NixOS machine and on the phone.

```nix
{ pkgs, ... }: {
  # Tailscale: run by root in userspace mode (it takes no VPN slot; the Tailscale app can stay)
  services.tailscale = {
    enable = true;
    authKeyFile = ./tailscale.key;               # logs in by itself; without it, the build shows a login link
  };

  # Syncthing, set up from the config, as on NixOS
  services.syncthing = {
    enable = true;
    settings.devices.laptop.id = "ABCDEFG-…";
    settings.folders.documents = { path = "/storage/emulated/0/Documents"; devices = [ "laptop" ]; };
  };

  # SSH: logins get root's shell with your Nix packages
  services.openssh = {
    enable = true;
    listenAddresses = [ { addr = "127.0.0.1"; port = 22; } ];   # tailnet only, through Tailscale
  };
  users.users.root.openssh.authorizedKeys.keys = [ "ssh-ed25519 AAAA… you@laptop" ];

  # Snapcast: play a server's stream through the phone's speaker, headphones or Bluetooth
  services.snapclient = { enable = true; host = "192.168.1.10"; };

  # adb (Android's own, not NixOS's): over the network from boot, and computers it trusts
  android.adb = { enable = true; port = 5555; trustedKeys = [ "QAAAA…= you@laptop" ]; };
}
```

Secret files (like `./tailscale.key`) are copied to the phone readable by root only, and never put in the Nix
store.

</details>

<details>
<summary><b>Backups</b></summary>

Apps (APK and data) and folders are backed up by the phone itself with
[restic](https://restic.net) (encrypted, each file stored once).

```nix
{
  android.backup = {
    repository = "/storage/XXXX-XXXX/Backup";    # SD card, phone storage, or "sftp:me@pc:/backup/phone", ...
    passwordFile = ./backup-password;            # next to your config; keep a copy off the phone
    apps = [ "org.thoughtcrime.securesms" "at.bitfire.davdroid" ];
    folders = [ "/storage/emulated/0/DCIM" ];
  };

  # Optional: one app its own way
  android.apps."com.termux".backup = { include = [ "data" ]; when = "manual"; };
}
```

- **When:** an app is backed up after you used it (once it has been in the background for 10 minutes), and
  at least once a day; folders once a day. Only what changed is stored again.
- **Before each backup** the storage is checked (SD card there, enough room). If something is missing, the
  backup waits and tries again later. A backup cut off by a restart starts again after you unlock the phone.
- **Restore** is always by hand: the app's **Backups** tab (pick the backup, then the parts: APK, data, …),
  or `su -c "seraph-backup restore org.example.app"`.
- **New phone:** `android.backup.defaults.autoRestore = true;` reinstalls backed-up apps with their data at
  the next build. An app you removed on this phone is never brought back by itself.
- **Keep `passwordFile` somewhere besides the phone:** without it, the backups cannot be read.

</details>

<details>
<summary><b>Building, Switching, Going Back</b></summary>

It works like `nixos-rebuild`:

| NixOS | Seraph |
|---|---|
| `nixos-rebuild switch` | **Build** in the app |
| `nixos-rebuild dry-activate` | **Dry run** |
| `nixos-rebuild switch --upgrade` | **Upgrade** |
| `nixos-rebuild switch --rollback` | **Generations** → pick one → Switch to it |
| `nixos-rebuild --target-host` | `seraph-rebuild switch --flake .#phone` on a PC |

- A build reads and checks everything first; if anything is wrong, **nothing changes**.
- Each build that changes something is saved as a generation, with what it was built from.
- The config can be a `configuration.nix`, a `flake.nix` (with `seraphConfigurations.<name>`), or a git
  repository the app clones (**Git** button: Pull, Pull and build, Reclone).
- You get a notification when a build, update or backup ends, and every run's log is in the **Logs** tab.

</details>

## Install And Setup

### Install

1. Root the phone (see [Requirements](#requirements)).
2. Download `seraph-v<version>.zip` from the [releases](https://github.com/by-architect/Seraph/releases).
3. Magisk → Modules → Install from storage → pick the zip.
4. Restart the phone.
5. Open **Seraph**, allow root and notifications.

### Write A Config

Start from the [template](templates/default), which has every option, or create one on a PC with:

```sh
nix flake init -t github:by-architect/Seraph
```

Edit it on the PC or on the phone.

### Set Up On The Phone

1. Build tab → **New** (writes a starter `configuration.nix`), **Pick file**, or **Git** (your repository).
2. Edit the config.
3. **Dry run** to see what would change.
4. **Build** to apply it.
5. From then on: edit, then **Build** again. **Generations** goes back.

### Set Up From A PC Over adb

The module must be installed on the phone first. Turn on USB debugging, connect, and allow the PC.

Build on the PC and apply it on the phone (the flake needs `seraphConfigurations.phone`):

```sh
nix run github:by-architect/Seraph -- switch --flake .#phone          # dry-activate: dry run; --upgrade: newest versions
nix run github:by-architect/Seraph -- switch --flake .#phone --target-host phone   # over ssh instead
```

Or let the phone build its own config:

```sh
adb push configuration.nix /sdcard/Seraph/configuration.nix
adb shell su -c "echo /storage/emulated/0/Seraph/configuration.nix > /data/adb/seraph/source"
adb shell su -c "sh /data/adb/modules/seraph/build.sh adb"          # or: build.sh adb dry
```

### Good To Know

Seraph's commands over adb (as root):

```sh
adb shell su -c seraph-shell                                           # root shell with your Nix packages
adb shell su -c "seraph-backup status"                                 # backups: list, status, now, restore
adb shell su -c "seraph-backup restore org.example.app"
adb shell su -c "sh /data/adb/modules/seraph/units.sh adb list"        # services: start, stop, restart, log
adb shell su -c "sh /data/adb/modules/seraph/update.sh adb check"      # app updates: check, now
adb shell su -c "tail -n 50 /data/adb/seraph/apply.log"                # the log
```

`seraph-shell` and `seraph-backup` work after the first restart with the module installed.

## Updating

When a new release is out, Magisk shows an **Update** for Seraph, and Seraph's **Updates** tab shows it with
an **Install** button. The new version is used after a restart.

## Uninstall

1. Magisk → Modules → Seraph → **Remove**. (If Remove is grayed out, an update is waiting: restart first.)
2. Restart the phone.

At that restart every setting Seraph changed goes back to how it was before Seraph, and apps it removed come
back. **Apps Seraph installed are uninstalled** (their data is kept). What stays: your config, your
backups, and apps you installed yourself.

## License

[GPL-3.0](LICENSE). Seraph includes Termux's terminal code (Apache 2.0), ships Nix (LGPL 2.1) and uses Aurora
OSS's gplayapi (GPL-3.0) for Google Play; see [NOTICE](NOTICE).
