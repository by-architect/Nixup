## Aim Of The Project
This project aims to close the gap in Android's Nix system. You can install the Nix system on a phone as Nixos, but this doesn't support every phone. Nix on Droid, on the other hand, doesn't provide the necessary permissions to access Android. Some things that are needed in Android are missing. The missing elements I've identified are: a native application backup system and configurations (yes, NeoBackup does this well, but the Nix system gives it the advantage of scripting), downloading applications from systems like fdroid using configuration, stable background services and scripts, application settings, etc. This project is designed to address all of these issues.

## How it works
I will explain in 6 steps
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
