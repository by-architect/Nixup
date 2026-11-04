# Changelog

What changed in each release of Seraph. The newest comes first. While a release is being made its
changes are under "Unreleased": the release build (scripts/build-magisk-release.sh) names that
section after the version, and its text is what the Magisk app shows as the update's changelog.

## Unreleased

- Cloning a git repository has a "Clone its submodules too" box (off by default); pulls and builds
  then include them, and a private submodule uses the same token.
- The app is installed for the phone's owner only, and removed from app clones and work profiles,
  where Magisk refuses root without asking; opened there, it says so.
- Fixed: picking up broken-off runs could start a second build next to one still running, which
  deleted its new profile, so the build applied but the phone kept the old profile. A run still
  going is now left alone, a second build, update or backup waits for the first, and each run has
  its own temporary profile.
- Fixed: a flake with no `seraphConfigurations` now says so instead of failing with no reason.
- Tailscale says when its auth key is refused, instead of an empty login link.

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
