# Changelog

What changed in each release of Seraph. The newest comes first. While a release is being made its
changes are under "Unreleased": the release build (scripts/build-magisk-release.sh) names that
section after the version, and its text is what the Magisk app shows as the update's changelog.

## Unreleased

- Specialisations, as on NixOS: `specialisation.<name>.configuration` makes other versions of the config (a
  "work" or "travel" one) with each build. ⋮ → Specialisations switches to one, or back to the config itself,
  without building again; `build.sh` and `seraph-rebuild` take `--specialisation <name>`. The phone keeps the
  one picked, across restarts too, until a build picks another, and each generation remembers its own.
- NixOS's own service modules run on the phone as they are, from your nixpkgs: `services.ntfy-sh.enable = true;`
  runs nixpkgs' module, with its options, its user and its settings file, as on NixOS. The 67 Seraph has tried
  (AdGuard Home, Caddy, cron, Forgejo, Gitea, Gotify, Grafana, harmonia, Jellyfin, Navidrome, nginx, ntfy, Redis,
  Sonarr, Tor, Transmission, Uptime Kuma, Vaultwarden, ...) are listed in nix/nixos.nix (PostgreSQL is not: it needs
  shared memory Android's kernel has not got);
  another can be added as on NixOS, `imports = [ "${modulesPath}/services/…" ];`.
- Services can run as their own user (`User`, `Group`, `DynamicUser`, as NixOS's modules give them): a user with
  no uid gets one from 400 to 999, which the phone keeps. Android lets those on the network; one in the
  `media_rw` group (`serviceConfig.SupplementaryGroups = [ "media_rw" ];`) gets the shared storage too (your
  files and SD cards, as `/storage` and `/sdcard`), in its own view of the phone.
- Timers, as on NixOS: a service's `startAt`, and `systemd.timers` with `OnCalendar` (systemd's calendar
  events: "daily", "Mon..Fri 07:30", "*:0/15", with a time zone or the phone's), `OnBootSec`, `OnActiveSec`,
  `OnUnitActiveSec`, `OnUnitInactiveSec`, `Persistent` (a time missed while the phone was off comes at its
  start) and `RandomizedDelaySec`. The Services tab shows when each service a timer starts runs next.
- Sockets, as on NixOS (`systemd.sockets`, TCP, UDP and unix): the phone opens them and hands them to their
  service as systemd does, so nixpkgs' modules that use them run as they are (harmonia, a binary cache of the
  phone's store, is on the list), and a port below 1024 can be a service user's. A service nothing else wants
  starts at its first connection (the Services tab says "listening" until then); with `Accept = true` each
  connection goes to a run of its own of the socket's template service.
- Templates (`foo@`) and their instances (`foo@bar`, `%i`), path units (`systemd.paths`), units in packages
  (`systemd.packages`: static-web-server's runs as nixpkgs' module makes it) and users' own units
  (`systemd.user.services`, `timers`, `sockets`, `paths`: each normal user's, run as that user), as on NixOS.
- `Type = "notify"`: a service counts as started once it says `READY=1`, and with `WatchdogSec` it is ended and
  started again (`Restart = "on-watchdog"`) when it stops saying `WATCHDOG=1`.
- `LoadCredentialEncrypted` and `SetCredentialEncrypted`, as `systemd-creds encrypt` makes them on the phone
  (with its own key); Seraph's `/etc` has a `machine-id`, as NixOS's has.
- A unit's `Environment` values have their specifiers filled in (`%S`, `%N`, `%d`, `%h`, ...), as systemd does.
- A module that adds `boot.kernelModules` (nginx's `tls`) builds, with a warning: the phone loads no modules.
- Fixed: stopping a service took up to minutes on a phone with many processes (each of them was looked at with
  a command of its own); one look at all of them takes a moment now.
- Fixed: a build did not wait for a forking service to have started before it said so (it looked before the
  service's loop was there); a notify service that never says it is ready is told as not started.
- Fixed: services kept the DNS servers of the network of the last build or start, which another network (mobile
  data, another Wi-Fi) may not answer: Seraph's /etc/resolv.conf now follows the phone's network.
- Fixed: services started with whatever environment the build or start came with (its TMPDIR, HOSTNAME, TERM, an
  adb session's ...); they start clean now, as systemd starts them, with Android's own variables and root's HOME
  for root's units.
- Fixed: a notify service that restarted could kill another process (one with the pid its listener had before).
- Fixed: a service's stop or restart could end another service's processes: Android gives a new view the number a
  view that just ended had, and the stopped service's loop took the new one's processes for its own. Each view
  now keeps its number while its loop looks.
- Fixed: the "runs again" notice could come for a service stopped in the minute after it had stopped by itself,
  once another process had its old process number.
- Fixed: the app was not told when a service stopped by itself, or when it ran again.
- A unit's own Environment wins over its user's HOME and USER (NixOS's couchdb), /etc/hosts has the phone's own
  name, an empty LoadCredential is taken as systemd takes it (ttyd), an empty environment.etc file is written
  (homepage-dashboard), a command's lines continued with a backslash are one (prometheus), and a user's socket
  with Accept = true runs its connections as that user.
- Seraph's /etc has every time zone of Android's data in /etc/zoneinfo, as NixOS has, with /etc/localtime a link to
  the phone's (programs know its name by it: .NET's Kavita needs it) and TZDIR set for the services.
- A command's @ prefix (another name for the program, as systemd takes it: NixOS's Apache) works.
- networking.hostName not set is "localhost" for NixOS's modules that read a name (Apache's ServerName); the phone
  keeps its own name, as before.
- Services and the jobs cron starts have the phone's time zone in Seraph's `/etc/localtime`, out of Android's
  time zone data, so summer time comes too; cron's jobs ran in UTC before.
- `system.path` (cron's `PATH`) is made by Nix itself, without Perl.
- A service nothing starts at boot (one a timer starts, or one to start by hand) is in the Services tab after
  each build, ready to start.
- `BindPaths` are mounted before the service goes into its `WorkingDirectory`, as systemd does.
- More of systemd's unit settings: `EnvironmentFile`, `LoadCredential` and `SetCredential`, the cache, logs and
  configuration folders, `BindPaths`, conditions and `ExecCondition`, `Type = "forking"` with `PIDFile`,
  `KillMode`, `TimeoutStopSec`, `ExecStopPost` (and `preStop`, `postStop`), every `Restart` value, `UMask` and
  `Limit…`, targets of `systemd.targets`. What only hardens a service on NixOS (`ProtectSystem`, ...) is taken
  and does nothing.
- `environment.etc`, `systemd.tmpfiles`, `networking.hosts`, `networking.nameservers` and
  `environment.variables`, as on NixOS, in Seraph's view of the phone.
- The phone builds the small files a config makes (a service's settings file, a script) from what
  cache.nixos.org has, and a build on a PC sends their recipes along; a package itself it still never builds.
  YAML, JSON and TOML files are written without Python.
- sshd stops alone when a build restarts it, as on NixOS: SSH sessions, and a build run through one, stay.
- Fixed: a build started over adb or ssh through Seraph's own Tailscale stopped halfway when it restarted
  Tailscale (the session went, and the build with it): a build now goes on to its end, with all of it in
  its log, when the session it came from is cut.
- Seraph's view has `/var/run`, `/var/lock` (as `/run`'s) and `/var/tmp`, as NixOS has them; cron needs them.
- After this update every running service starts again once.
- A module that tells NixOS from a phone by `options ? networking.firewall` now finds that option on the phone
  too (NixOS's modules need it): check `options ? android` instead.
- Services that need the phone's storage say so with `unitConfig.RequiresMountsFor`, as on NixOS: they
  start once it is mounted (an SD card, or what Android mounts once the phone is first unlocked), waiting
  quietly till then instead of failing; one whose storage came after its start starts again in a fresh
  view, and one whose storage goes away stops to wait for it. The Services tab shows "waiting".
- `serviceConfig.SuccessExitStatus` and `RestartForceExitStatus`, as on NixOS: Syncthing restarting itself
  (exit 3) is no longer told as a failure.
- `services.syncthing` waits for its folders' storage, so it no longer fails at every start before the
  phone is unlocked.
- Fixed: an automatic backup at the phone's first unlock failed ("the repository cannot be opened"): it
  checked that the SD card's folder was there, which Android makes before it mounts the card. It now
  checks the card is mounted, and waits for the next check when it is not.
- `users.users.<name>.androidApp`: a user with the uid of an app, looked up on the phone at each build (an app
  installed again gets another one). `androidApp = "dev.seraph.manager"` is Seraph's own: an SSH login that
  may use su as Seraph may, with no other app (no Termux).
- A normal user's home is `/home/<name>` (Seraph's own folder), made by the build and there from the phone's
  start on, before it is first unlocked.
- `terminal.font` and `terminal.colors`, as Nix-on-Droid has them: the app's terminal takes its font (a file
  of a Nix package) and colors from the config each time it opens.
- Services get Android's time zone, so the dates their programs write are the phone's local time.
- Fixed: the phone took four minutes longer to start, also after the module was removed. Android relabels every
  file of /data/adb at each start, and Seraph's Nix store has hundreds of thousands; now each start runs
  restorecon on Seraph's folder, which leaves the mark that makes Android skip it from then on.
- A newer Seraph whose services' view changed starts the running services again in the new one (sshd saw
  no /home until it did).
- Apps from Google Play: `android.packages.playstore = [ "com.whatsapp" ];` installs and updates
  them as Aurora Store would download them, with the split APKs made for the phone; no Play Store
  or GApps needed. A Google account signs in once in the app (⋮ → Google Play account), which then
  shows its token to copy or save, or `android.playstore.tokenFile` gives the token, which
  `seraph-play-login` makes on a PC. A build that lists such apps stops first when no account is
  signed in, or Google refuses its token, and says to sign in.
- Cloning a git repository has a "Clone its submodules too" box (off by default); pulls and builds
  then include them, and a private submodule uses the same token.
- The app is installed for the phone's owner only, and removed from app clones and work profiles,
  where Magisk refuses root without asking; opened there, it says so.
- Search: the Updates, Backups, Services and Logs tabs have a search box that keeps the rows with
  what is typed in them (any case, with or without accents: "sifre" finds "şifre"), each log has
  one that keeps the lines with it, and Back up… → Apps finds an app by its name or package.
- Fixed: picking up broken-off runs could start a second build next to one still running, which
  deleted its new profile, so the build applied but the phone kept the old profile. A run still
  going is now left alone, a second build, update or backup waits for the first, and each run has
  its own temporary profile.
- Fixed: a flake with no `seraphConfigurations` now says so instead of failing with no reason.
- Tailscale says when its auth key is refused, instead of an empty login link.
- Fixed: a service runs again when a secret file it reads changes (a new Tailscale auth key, an
  ssh host key); before, only a change to the service itself restarted it, so a new key waited
  for a restart.
- Fixed: restoring an app that is no longer on the phone failed with "not installed" when its
  backup held only its data (as with `android.backup.defaults.apk = false`). Such an app now comes
  back whole: its APK first, from that backup or another backup of it, then its data; when no backup
  has its APK, the restore says "app not found". `seraph-backup restore <app>` takes the newest
  backup with data, not a newer one that holds only the APK.

## 0.1.0 (2026-11-03)

The first public release.

- A Magisk module and an app that make an Android phone match a Nix config, as NixOS does: a
  `configuration.nix`, or a `flake.nix` with `seraphConfigurations`, kept wherever you like or
  cloned from a git repository, built on the phone or on a PC (`seraph-rebuild`, over adb or ssh).
- Apps from F-Droid repositories and from their developers' releases (GitHub, GitLab, Codeberg),
  installed and kept up to date, with notifications; apps that came with the phone removed.
- Each app's settings (permissions, battery, network, special access, language, defaults) and the
  phone's own settings (Android's, LineageOS's), each given back when it leaves the config.
- Generations, as NixOS has them: dry runs, upgrades, and going back to an earlier one.
- Nix packages on the phone from cache.nixos.org, `environment.systemPackages` and a terminal with
  them, and services with NixOS's names: `systemd.services`, `services.tailscale`,
  `services.syncthing`, `services.openssh`, `services.snapserver` and `services.snapclient`, plus
  `android.adb` and `users.users`.
- Backups of apps (APK and data) and folders into a restic repository, made by the phone itself,
  checked before they start, and restored by hand from the app (or by a build, when turned on).
- Notifications for builds, updates, backups, services and Tailscale's connection, a Logs tab, and
  work that a restart broke off picked up again.
- Updates of Seraph itself: from the Magisk app, or from Seraph's Updates tab.
