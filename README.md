# Nixup

Configure a rooted Android phone with Nix, as NixOS does: apps, app settings, system settings, services and
backups, all from one `configuration.nix`.

> **Warning:** this project is still in development. Back up everything first. I am not responsible for any
> data loss.

**[Read the documentation →](https://by-architect.github.io/Nixup/docs.html)**

**ALL OPTIONS: https://github.com/by-architect/Nixup/blob/main/templates/default/configuration.nix**

## Aim Of The Project

This project aims to close the gap between Android and Nix. You can install NixOS on a phone, but it does
not support every phone. Nix-on-Droid, on the other hand, does not have the permissions needed to manage
Android itself. What I found missing: a native app backup system driven by the config, installing apps from
F-Droid-like repositories from a config, stable background services and scripts, app settings, and more. Nixup
is built to cover all of these.

## FEATURES
- Smart Backup: if new version of apk exist, it backups apk. Checks data changes of apps, if data changes, auto backups.
- Smart app install: Installs from play store, fdroid, obtanium and other fdroid stores. With option, it installs apks from backup with data in build, if only new version of that app exist, it installs from store.
- Backup Options: You can backup to remote device and restore from there. Imagine that, you don't have any data in your phone, just building nix system from github, and all your data comes from your server and being built. 
- Background services those run stable in background and start automaticly on boot: Tailscale, ssh, adb, caldav, ntfy, snapcast, snapclient and custom services like nixos does. Best way is that, option syntax is same with nixos. So one service file will run in both device. But careful about arm based packages. 
- Packages of nixos: Run nix-shell commands, install packages via config, use the terminal with your way just like nix-on-droid.
- App Settings: Change any app settings via config. No need to setup everything from stratch.
- Generations: Roll back, built on the phone or in pc. 

## What It Manages

- [Apps](https://by-architect.github.io/Nixup/docs.html#apps) from F-Droid-style repositories, developers'
  releases (GitHub, GitLab, Codeberg) and [Google Play](https://by-architect.github.io/Nixup/docs.html#google-play)
- [App settings](https://by-architect.github.io/Nixup/docs.html#app-settings) and
  [system settings](https://by-architect.github.io/Nixup/docs.html#system-settings)
- [Packages and a terminal](https://by-architect.github.io/Nixup/docs.html#packages), users and
  [Home Manager](https://by-architect.github.io/Nixup/docs.html#home-manager)
- [Services](https://by-architect.github.io/Nixup/docs.html#services) and
  [NixOS's own service modules](https://by-architect.github.io/Nixup/docs.html#nixos-modules)
- Encrypted [backups](https://by-architect.github.io/Nixup/docs.html#backups) of apps and folders
- Calendars, contacts and tasks from CalDAV/CardDAV servers, synced by Nixup's app itself with DAVx⁵'s sync
- Notifications from [ntfy](https://ntfy.sh) topics, on ntfy.sh or a server of your own, with no ntfy app; and
  UnifiedPush for apps like Element, with Nixup as their push distributor
- [Generations](https://by-architect.github.io/Nixup/docs.html#building) you can roll back to, built on the
  phone or [from a PC](https://by-architect.github.io/Nixup/docs.html#from-a-pc)

## Examples

Adding something is one name in a list. An app's package name is in its store page's address
(`f-droid.org/packages/com.termux`, `play.google.com/store/apps/details?id=com.whatsapp`):

```nix
{ pkgs, ... }: {
  # F-Droid: package names as they are, after "with pkgs.fdroid;"
  android.packages.fdroid = with pkgs.fdroid; [
    com.termux                    # Termux
    org.schabi.newpipe            # NewPipe
    de.danoeh.antennapod          # AntennaPod
    org.videolan.vlc              # VLC
    org.mozilla.fennec_fdroid     # Fennec (Firefox)
    com.aurora.store              # Aurora Store
  ];

  # Google Play: package names in quotes
  android.packages.playstore = [
    "com.whatsapp"                # WhatsApp
    "org.telegram.messenger"      # Telegram
    "com.spotify.music"           # Spotify
    "com.instagram.android"       # Instagram
    "com.discord"                 # Discord
    "com.duolingo"                # Duolingo
  ];

  # Linux programs from nixpkgs, for the terminal and services
  environment.systemPackages = with pkgs; [ htop git vim rsync python3 ffmpeg ];
}
```

Apps from F-Droid and other F-Droid-style repositories:

```nix
{ pkgs, ... }: {
  # f-droid.org is always there, as "fdroid"
  android.packages.fdroid = with pkgs.fdroid; [ com.termux ];

  # Another repository: a name you choose, and its address with its key
  android.repos.izzyondroid.url =
    "https://apt.izzysoft.de/fdroid/repo?fingerprint=3BF0D6ABFEAE2F401707B6D966BE743BF0EEE49C2561B9BA39073711F628937A";
  android.packages.izzyondroid = with pkgs.izzyondroid; [ at.bitfire.davdroid ];

  # Updates installed by themselves (every 6 hours, on Wi-Fi); false only notifies
  android.settings.repo.fdroid.autoupdatePackages = true;
}
```

Apps from Google Play, downloaded by the phone itself (no Play Store or GApps needed; sign in once in the app:
⋮ → Google Play account):

```nix
{
  android.packages.playstore = [ "com.whatsapp" "org.telegram.messenger" ];

  android.playstore.account = "you@gmail.com";        # optional: builds stop if another account is signed in
  android.playstore.tokenFile = ./google-play.token;  # optional: no sign-in on the phone needed
  android.settings.repo.playstore.autoupdatePackages = true;
}
```

Services, with NixOS's own options:

```nix
{
  # Tailscale, run by root in userspace mode (no VPN slot; the Tailscale app can stay)
  services.tailscale = {
    enable = true;
    authKeyFile = ./tailscale.key;                    # logs in by itself
  };

  # Syncthing, set up from the config
  services.syncthing = {
    enable = true;
    settings.devices.laptop.id = "7CFNTQM-IMTJBHJ-3UWRDIU-ZGQJFR6-VCXZ3NB-XUH3KZO-N52ITXR-LAIYUAU";
    settings.folders.documents = {
      path = "/storage/emulated/0/Documents";
      devices = [ "laptop" ];
    };
  };

  # Calendars and contacts from a CalDAV/CardDAV server, synced by Nixup's app itself (DAVx⁵'s sync)
  services.caldav = {
    enable = true;
    accounts.home = {
      url = "https://dav.example.org/";
      userName = "neo";
      passwordFile = ./dav-password;
    };
  };

  # Notifications from ntfy topics: send one from anywhere with curl -d "Backup done" ntfy.sh/phone-alerts-x7q2
  services.ntfy = {
    enable = true;
    topics = [ "phone-alerts-x7q2" "ntfy.example.org/backups" ];
  };

  # SSH: logins get root's shell with your Nix packages; 127.0.0.1 keeps it to the tailnet
  services.openssh = {
    enable = true;
    listenAddresses = [ { addr = "127.0.0.1"; port = 22; } ];
  };
  users.users.root.openssh.authorizedKeys.keys = [ "ssh-ed25519 AAAA… you@laptop" ];
}
```

App and system settings:

```nix
{
  android.apps."org.thoughtcrime.securesms" = {
    permissions.camera = true;
    battery = "unrestricted";
  };
  networking.hostName = "phone";
  time.timeZone = "Europe/Istanbul";
  android.settings.display.darkMode = true;
}
```

More, with every option: [the documentation](https://by-architect.github.io/Nixup/docs.html).

## Requirements

- A rooted phone: Magisk (tested), KernelSU or APatch.
- Android 11 or newer, 64-bit ARM (arm64).

## Install

1. Download `nixup-v<version>.zip` from the [releases](https://github.com/by-architect/Nixup/releases).
2. Magisk → Modules → Install from storage → pick the zip, then restart the phone.
3. Open **Nixup**, allow root and notifications, and press **New** for a starter config.

Then follow [Your first build](https://by-architect.github.io/Nixup/docs.html#first-build).

## License

[GPL-3.0](LICENSE). Nixup includes Termux's terminal code (Apache 2.0) and Nix-on-Droid's terminal options
(MIT), DAVx⁵'s synctools (GPL-3.0) and dav4jvm (MPL 2.0) for the calendar and contacts sync, ships Nix
(LGPL 2.1) and uses Aurora OSS's gplayapi (GPL-3.0) for Google Play; see [NOTICE](NOTICE).
