# Nixup

Configure an Android phone with Nix, as NixOS does: apps, app settings, system settings, services and
backups, all from one `configuration.nix`.

> **Warning:** this project is still in development. Back up everything first. I am not responsible for any
> data loss.

## Aim Of The Project

This project aims to close the gap between Android and Nix. You can install NixOS on a phone, but it does
not support every phone. Nix-on-Droid, on the other hand, does not have the permissions needed to manage
Android itself. What I found missing: a native app backup system driven by the config (NeoBackup does
backups well, but Nix adds scripting to it), installing apps from F-Droid-like repositories from a config,
stable background services and scripts, app settings, and more. Nixup is built to cover all of these.

## Requirements

- A rooted phone: Magisk (tested), KernelSU or APatch.
- Android 11 or newer, 64-bit ARM (arm64).
- A network connection for builds.
- Nix on a PC only if you want to build there (optional).

## How It Works

You write a config, press **Build** in the Nixup app, and the phone is changed to match it. Every build is
checked before anything changes, and saved as a **generation** you can go back to. Take a line out of the
config and the next build puts that setting back the way it was before Nixup.

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
in once: Nixup app → ⋮ → **Google Play account** → **Sign in** (Google's own sign-in page). Then **Show
token** gives the account's token, to copy or save to Downloads, for a config on a PC. Or sign in on a PC:

```sh
nix run github:by-architect/Nixup#nixup-play-login     # a sign-in window opens; then it saves ./google-play.token
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
- If the sign-in page says the app has no internet while the phone is online, the Nixup app's network access
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

  # The terminal's look, with Nix-on-Droid's options:
  terminal.font = "${pkgs.jetbrains-mono}/share/fonts/truetype/JetBrainsMono-Regular.ttf";
  terminal.colors = { background = "#000000"; foreground = "#FFFFFF"; cursor = "#FFFFFF"; };

  # A user that logs in over SSH, with no other app needed (it can use su like Nixup):
  users.users.niobe = { isNormalUser = true; androidApp = "dev.nixup.manager"; };
}
```

- Packages are downloaded from cache.nixos.org; nothing is compiled on the phone.
- They are for the shell and services, not phone apps (apps come from repositories, above).
- The **terminal** (the icon in Nixup's top bar) is built into the app (no Termux needed): a root shell with
  these packages. The same shell: `su -c nixup-shell` from adb, or `ssh` with `services.openssh`.
- `terminal.font` must be a file from a Nix package; `terminal.colors` takes `background`, `foreground`,
  `cursor` and `color0` to `color15`.
- A normal user's home is `/home/<name>`, ready right after the phone starts.
- A user's own packages (`users.users.<name>.packages`) are in its profile, `/etc/profiles/per-user/<name>`, on its
  `PATH` before `environment.systemPackages`, as on NixOS.

</details>

<details>
<summary><b>Home Manager</b></summary>

[Home Manager](https://github.com/nix-community/home-manager) works as on NixOS, through its own NixOS module, with
all of its options: `programs.*`, `services.*`, `home.file`, `xdg.*`, `home.packages`, `home.activation`. One module
file can configure a user on a NixOS machine and on the phone.

```nix
{ pkgs, ... }: {
  # A flake: inputs.home-manager.url = "github:nix-community/home-manager";
  #          modules = [ home-manager.nixosModules.home-manager ./configuration.nix ];
  # A configuration.nix alone: the phone fetches Home Manager (as it fetches nixpkgs):
  imports = [ <home-manager/nixos> ];

  users.users.niobe = { isNormalUser = true; androidApp = "dev.nixup.manager"; };

  home-manager.useGlobalPkgs = true;            # the phone's pkgs (one evaluation of nixpkgs, not two)
  home-manager.backupFileExtension = "backup";  # a file of yours in the way is moved aside, not refused
  home-manager.users.niobe = { pkgs, ... }: {
    home.stateVersion = "26.05";
    home.packages = [ pkgs.ripgrep ];
    programs.git = { enable = true; settings.user.name = "Niobe"; };
    programs.bash.enable = true;
    services.gpg-agent = { enable = true; enableSshSupport = true; };
  };
}
```

- Each user's Home Manager runs at each build that changes it and at the phone's start, as the user: it links the
  user's files into its home and runs each module's steps (`onChange`, `home.activation`). The Services tab shows it
  as `home-manager-<name>`, with its log.
- The services Home Manager makes (every `services.*` module's, and `systemd.user.services` of your own) run as the
  user's own, named `user-<name>-<service>`, after its files are linked: with `%t` its `/run/user/<uid>`,
  `StateDirectory` in `~/.local/state`, and its sockets its own, as its `systemd --user` would run them.
- Its packages are the user's profile (`/etc/profiles/per-user/<name>`), whatever `useUserPackages` says: a user
  cannot install into the phone's store.
- Taking a user out of `home-manager.users` takes Home Manager's links out of its home again.
- On a phone some defaults differ, to save downloads: no manual page, no desktop file types (`xdg.mime`), and only
  the UTF-8 locales; each can be turned on again.
- Not on the phone: a desktop session (what only `graphical-session.target` starts is not started, and the build says
  so), a D-Bus session bus (the services that need one fail), and `startAsUserService`. Home Manager's files are
  links into `/nix/store`, so they are there in Nixup's view of the phone (SSH, the app's terminal, the services), not
  for Android's apps.

Nix itself is in the same shells, as on NixOS, for packages you want without changing the config:

```sh
nix shell nixpkgs#htop          # a shell with htop (nix run nixpkgs#cowsay -- hi runs one program)
nix profile add nixpkgs#htop    # installed for you, on PATH from then on (or: nix-env -iA nixpkgs.htop)
nix-shell -p htop               # a shell with nixpkgs' build tools too (a big download the first time)
nix-collect-garbage -d          # frees up space; the config's own packages stay
```

- `nixpkgs` there is your config's own nixpkgs (the registry's `nixpkgs` and `<nixpkgs>`, as on a NixOS built
  from a flake), so nothing fetches nixpkgs again. The phone keeps it; a build from a PC sends it once when
  the phone has not got it.
- The commands are the module's own Nix, so nothing is downloaded for them. Settings: `nix.settings`
  (`/etc/nix/nix.conf`, flakes on), `nix.registry`, `nix.gc`, `nix.optimise`, `nix.package`, as on NixOS;
  `nix.enable = false` takes it all away.
- Builds run as the `nixbld` users, without Nix's sandbox (Android's kernels cannot do it). Other users that
  log in (`isNormalUser`) use Nix through its daemon, as on NixOS.
- A user of an Android app (`androidApp`) has that app's network, which Android cuts while the app is in the
  background: `nix-shell -p` and `nix-env -iA nixpkgs.…` work anyway (the daemon downloads), but `nix shell
  nixpkgs#…` first fetches Nix's registry itself, which then fails.

</details>

<details>
<summary><b>Services And Scripts</b></summary>

There is no real systemd on Android: Nixup runs NixOS-style `systemd.services` itself, with the same
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

  # A job at set times, as NixOS's startAt (systemd.time(7): "daily", "Mon..Fri 07:30", "*:0/15")
  systemd.services.photos-copy = {
    startAt = "*-*-* 03:00:00";
    serviceConfig.Type = "oneshot";
    script = "cp -ru /storage/emulated/0/DCIM /storage/1234-5678/Photos";
  };
}
```

- Supported: NixOS's unit options (`script`, `preStart`, `postStart`, `preStop`, `postStop`, `environment`,
  `path`, `wantedBy`, `requiredBy`, `after`, `before`, `wants`, `requires`, `bindsTo`, `restartTriggers`,
  `systemd.targets`, ...) and systemd's keys for commands (`ExecStart` and the other `Exec…`, `Type`: simple,
  exec, oneshot, forking, notify, idle, dbus; `Restart` and its exit statuses, `KillMode`, `TimeoutStopSec`,
  `PIDFile`), its environment (`Environment`, `EnvironmentFile`, `WorkingDirectory`), its user (`User`,
  `Group`, `SupplementaryGroups`, `DynamicUser`, `AmbientCapabilities`, `UMask`, `Limit…`), its folders
  (`StateDirectory`, `CacheDirectory`, `LogsDirectory`, `RuntimeDirectory`, `ConfigurationDirectory`,
  `BindPaths`), its secrets (`LoadCredential`, `SetCredential`) and conditions (`ConditionPathExists`, ...).
  What only hardens a service on NixOS (`ProtectSystem`, `PrivateDevices`, `SystemCallFilter`, ...) is taken
  and does nothing; anything else stops the build with a message. [nix/units.nix](nix/units.nix) lists it all.
- Timers: a service's `startAt`, and `systemd.timers` with `OnCalendar` (systemd's calendar events, with the
  same times as `systemd-analyze calendar`), `OnBootSec`, `OnActiveSec`, `OnUnitActiveSec`, `OnUnitInactiveSec`,
  `Persistent` and `RandomizedDelaySec`. A phone asleep runs nothing: a time that passed while it slept comes
  when it wakes. The Services tab shows when each one runs next.
- Sockets (`systemd.sockets`: TCP, UDP and unix sockets): the phone opens them and hands them to their service
  as systemd does (`LISTEN_FDS`), so nixpkgs' modules that use them (harmonia) run as they are, and a port below
  1024 can be a service user's. A service nothing else wants starts at its first connection; with
  `Accept = true` each connection goes to a run of its own of the socket's template service (`foo@`).
- Templates (`systemd.services."foo@"`) run as their instances (`foo@bar`, with `%i`), path units
  (`systemd.paths`: `PathExists`, `PathChanged`, ...) start their service when a file is there or changes,
  units in packages (`systemd.packages`) are read from the package as on NixOS, and users' own units
  (`systemd.user.services`) run as each normal user.
- `Type = "notify"` services count as started once they say `READY=1`; with `WatchdogSec` one that stops
  saying `WATCHDOG=1` is ended and started again (`Restart = "on-watchdog"`). `LoadCredentialEncrypted` takes
  what `systemd-creds encrypt` made on the phone (with the phone's own key: add `pkgs.systemd` to
  `environment.systemPackages` and run it in the app's terminal).
- A service with a user of its own (`User`, `DynamicUser`, as NixOS's modules give them) runs as that user,
  with a uid from 400 to 999 the phone keeps for it. Android lets those on the network; the shared storage
  (`/storage`, `/sdcard`), which Android lets only apps and root open, such a service gets when its user is in
  `media_rw` (`serviceConfig.SupplementaryGroups = [ "media_rw" ];`, or the user's `extraGroups`), in its own
  view of the phone. Or it runs as root (`serviceConfig.User = lib.mkForce "root";`).
- `environment.etc` (files in Nixup's `/etc`), `systemd.tmpfiles`, `networking.hosts` and
  `networking.nameservers` work as on NixOS, in Nixup's view of the phone, where `/etc/localtime` is the
  phone's time zone (cron's jobs and every program keep the phone's time).
- Services start again at boot, run below the app on the screen (so the phone does not stutter), and send a
  notification when they stop by themselves.
- Start, stop, restart and logs: the app's **Services** tab.

Scripts, as NixOS's `system.activationScripts`, `system.preSwitchChecks` and `system.userActivationScripts`:

```nix
{
  # Runs as root with each build and each start of the phone, before the services start
  system.activationScripts.stay-awake = {
    text = "settings put global stay_on_while_plugged_in 3";
    undo = "settings put global stay_on_while_plugged_in 0";   # Nixup's own: when it leaves the config
  };

  # Runs before a build changes anything: when it fails, nothing changes
  system.preSwitchChecks.battery = ''[ "$(cat /sys/class/power_supply/battery/capacity)" -ge 20 ]'';

  # Runs as each normal user, when it changes and at each start of the phone
  system.userActivationScripts.notes = "mkdir -p ~/notes";
}
```

- They run in Nixup's view of the phone (Nix packages and Android's commands both there), with bash, in the
  order their `deps` say; NixOS's own names (`users`, `etc`, ...) can be in `deps`.
- A dry run runs only the scripts with `supportsDryActivation = true` (with `NIXOS_ACTION=dry-activate`),
  and the pre-switch checks. A script that fails is told, and the others still run, as on NixOS.
- `undo` is Nixup's own: it runs once when the script leaves the config, and when Nixup is removed, so the
  phone gets back what the script changed.

</details>

<details>
<summary><b>Ready-Made Services</b></summary>

NixOS's own service modules run on the phone as they are, from your nixpkgs: `services.ntfy-sh.enable = true;`
runs nixpkgs' module, with its options, its user and its settings file, as on NixOS. The ones Nixup has tried
are AdGuard Home, aria2, at, Audiobookshelf, Bazarr, Blocky, Caddy, calibre-web, code-server, Conduit (Matrix),
CoreDNS, CouchDB, cron, Deluge, dnscrypt-proxy, dnsmasq, endlessh, File Browser, Forgejo, FreshRSS, Gitea, gonic,
GoToSocial, Gotify, Grafana, harmonia, Headscale, Homepage, i2pd, InfluxDB, Jellyfin, Kavita, Komga, Lidarr,
lighttpd, linkding, llama.cpp, Loki, MariaDB/MySQL, Mealie, MiniDLNA, Mosquitto, Navidrome, nginx, Node-RED, ntfy,
Ollama, Apache httpd, Privoxy, Prometheus, Prowlarr, qBittorrent, Radarr, Radicale, Readarr, Redis, Soft Serve,
Sonarr, Stirling-PDF, Tor, Transmission, ttyd, Unbound, Uptime Kuma, Vaultwarden, vnStat and WebDAV
([nix/nixos.nix](nix/nixos.nix)); PostgreSQL is not, as it needs shared memory Android's kernel has not got (a
module's SQLite database is on the phone). Another of nixpkgs' modules can be
added by hand, as on NixOS: `imports = [ "${modulesPath}/services/web-apps/foo.nix" ];` (what it needs that the
phone has not got stops the build and says so). The small files a module makes (its settings) the phone builds
itself from what cache.nixos.org has; a package itself it never builds.

Others need a bridge of their own, which Nixup has for Tailscale, Syncthing, SSH, Snapcast (server and client)
and adb. Except for snapclient (which NixOS does not have), they take the same options as on NixOS, so one module
file can work on a NixOS machine and on the phone.

```nix
{ pkgs, ... }: {
  # nixpkgs' own module for ntfy (push notifications), as on NixOS
  services.ntfy-sh = {
    enable = true;
    settings.base-url = "http://phone:2586";
    settings.listen-http = "127.0.0.1:2586";    # tailnet only, through Tailscale
  };

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
  or `su -c "nixup-backup restore org.example.app"`. An app that is not on the phone comes back whole: its
  APK first, then its data. The APK comes from that backup, or from another backup of the app when that one
  has none (`android.backup.defaults.apk = false` backs up data only). If no backup has the APK, the restore
  says "app not found": install the app first, then restore its data.
- **New phone:** `android.backup.defaults.autoRestore = true;` reinstalls backed-up apps with their data at
  the next build. An app you removed on this phone is never brought back by itself.
- **Keep `passwordFile` somewhere besides the phone:** without it, the backups cannot be read.

</details>

<details>
<summary><b>Building, Switching, Going Back</b></summary>

It works like `nixos-rebuild`:

| NixOS | Nixup |
|---|---|
| `nixos-rebuild switch` | **Build** in the app |
| `nixos-rebuild dry-activate` | **Dry run** |
| `nixos-rebuild switch --upgrade` | **Upgrade** |
| `nixos-rebuild switch --rollback` | **Generations** → pick one → Switch to it |
| `nixos-rebuild switch --specialisation work` | ⋮ → **Specialisations** → pick one (no new build), or `--specialisation work` with `build.sh` or `nixup-rebuild` |
| `nixos-rebuild --target-host` | `nixup-rebuild switch --flake .#phone` on a PC |

- A build reads and checks everything first; if anything is wrong, **nothing changes**.
- Each build that changes something is saved as a generation, with what it was built from.
- `specialisation.<name>.configuration` makes other versions of the config (a "work" or "travel" one) with
  each build, as on NixOS. The phone switches to one and back without building again, and keeps the one picked
  across restarts, until a build picks another.
- The config can be a `configuration.nix`, a `flake.nix` (with `nixupConfigurations.<name>`), or a git
  repository the app clones (**Git** button: Pull, Pull and build, Reclone).
- You get a notification when a build, update or backup ends, and every run's log is in the **Logs** tab.
- The **Updates**, **Backups**, **Services** and **Logs** tabs each have a search box, and so does every log
  you open: type part of a name, a package or a message to see only what matches (any case, with or without
  accents).

</details>

## Install And Setup

### Install

1. Root the phone (see [Requirements](#requirements)).
2. Download `nixup-v<version>.zip` from the [releases](https://github.com/by-architect/Nixup/releases).
3. Magisk → Modules → Install from storage → pick the zip.
4. Restart the phone.
5. Open **Nixup**, allow root and notifications.

### Write A Config

Start from the [template](templates/default), which has every option, or create one on a PC with:

```sh
nix flake init -t github:by-architect/Nixup
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

Build on the PC and apply it on the phone (the flake needs `nixupConfigurations.phone`):

```sh
nix run github:by-architect/Nixup -- switch --flake .#phone          # dry-activate: dry run; --upgrade: newest versions
nix run github:by-architect/Nixup -- switch --flake .#phone --target-host phone   # over ssh instead
```

Or let the phone build its own config:

```sh
adb push configuration.nix /sdcard/Nixup/configuration.nix
adb shell su -c "echo /storage/emulated/0/Nixup/configuration.nix > /data/adb/nixup/source"
adb shell su -c "sh /data/adb/modules/nixup/build.sh adb"          # or: build.sh adb dry
```

### Good To Know

Nixup's commands over adb (as root):

```sh
adb shell su -c nixup-shell                                           # root shell with your Nix packages
adb shell su -c "nixup-backup status"                                 # backups: list, status, now, restore
adb shell su -c "nixup-backup restore org.example.app"
adb shell su -c "sh /data/adb/modules/nixup/units.sh adb list"        # services: start, stop, restart, log
adb shell su -c "sh /data/adb/modules/nixup/update.sh adb check"      # app updates: check, now
adb shell su -c "tail -n 50 /data/adb/nixup/apply.log"                # the log
```

`nixup-shell` and `nixup-backup` work after the first restart with the module installed.

## Updating

When a new release is out, Magisk shows an **Update** for Nixup, and Nixup's **Updates** tab shows it with
an **Install** button. The new version is used after a restart.

## Uninstall

1. Magisk → Modules → Nixup → **Remove**. (If Remove is grayed out, an update is waiting: restart first.)
2. Restart the phone.

At that restart every setting Nixup changed goes back to how it was before Nixup, and apps it removed come
back. **Apps Nixup installed are uninstalled** (their data is kept). What stays: your config, your
backups, and apps you installed yourself.

## License

[GPL-3.0](LICENSE). Nixup includes Termux's terminal code (Apache 2.0) and Nix-on-Droid's terminal options
(MIT), ships Nix (LGPL 2.1) and uses Aurora OSS's gplayapi (GPL-3.0) for Google Play; see [NOTICE](NOTICE).
