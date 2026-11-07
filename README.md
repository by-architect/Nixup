# Nixup

Configure a rooted Android phone with Nix, as NixOS does: apps, app settings, system settings, services and
backups, all from one `configuration.nix`.

> **Warning:** this project is still in development. Back up everything first. I am not responsible for any
> data loss.

**[Read the documentation →](https://by-architect.github.io/Nixup/docs.html)**

## Aim Of The Project

This project aims to close the gap between Android and Nix. You can install NixOS on a phone, but it does
not support every phone. Nix-on-Droid, on the other hand, does not have the permissions needed to manage
Android itself. What I found missing: a native app backup system driven by the config, installing apps from
F-Droid-like repositories from a config, stable background services and scripts, app settings, and more. Nixup
is built to cover all of these.

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
- [Generations](https://by-architect.github.io/Nixup/docs.html#building) you can roll back to, built on the
  phone or [from a PC](https://by-architect.github.io/Nixup/docs.html#from-a-pc)

```nix
{ pkgs, ... }: {
  android.packages.fdroid = with pkgs.fdroid; [ com.termux ];
  android.settings.display.darkMode = true;
  services.tailscale.enable = true;
}
```

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
(MIT), ships Nix (LGPL 2.1) and uses Aurora OSS's gplayapi (GPL-3.0) for Google Play; see [NOTICE](NOTICE).
