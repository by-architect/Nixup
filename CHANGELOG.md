# Changelog

What changed in each release of Seraph. The newest comes first. While a release is being made its
changes are under "Unreleased": the release build (scripts/build-magisk-release.sh) names that
section after the version, and its text is what the Magisk app shows as the update's changelog.

## Unreleased

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
