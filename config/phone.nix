# This phone's config. Edit it, then press Build in the Andronix app (or the
# module's Action button in Magisk): Nix on the phone checks it and applies it.
#
# Options:
#   android.darkMode         true (on), false (off), or null (left as you set it)
#   android.repos.<name>     an F-Droid repository besides f-droid.org (fdroid):
#                              android.repos.mine.url = "https://…/repo?fingerprint=…";
#   android.packages.<repo>  its apps, by package name (an app comes from one repo only):
#                              with pkgs.fdroid; [ org.example.app ]
#                            a name with a Nix keyword in it (in, if, ...) goes in quotes:
#                              [ "in.example.app" ]
#   android.settings.<repo>.autoupdatePackages   its apps update by themselves
#   android.apps."<package>" an app's settings (every one: nix/app.nix)
{ pkgs, ... }:
{
  android.darkMode = true;

  android.packages.fdroid = with pkgs.fdroid; [
    com.zorinos.zorin_connect
  ];

  # My own repository: the test versions of Dejavu.
  android.repos.byarchitect.url =
    "https://fdroid.byarchitect.org/fdroid/repo?fingerprint=50941BDAC56B056A520ABFA3FC7DAAB789DEE5B83B295DA54A1961C7BC8A91C9";
  android.packages.byarchitect = with pkgs.byarchitect; [
    com.byarchitect.dejavu.test
  ];

  # Every app setting, on one app, to see what each one does.
  android.apps."com.zorinos.zorin_connect" = {
    permissions = {
      camera = false;
      microphone = "ask";
      location = "whileUsing";
      preciseLocation = false;
      contacts = true;
      calendar = false;
      phone = "ask";
      callLogs = false;
      sms = false;
      nearbyDevices = true;
      physicalActivity = false;
      bodySensors = false;
      notifications = true;
      photosAndVideos = false;
      musicAndAudio = true;
      files = false;
      mediaLocation = false;
    };

    battery = "unrestricted";

    network = {
      backgroundData = false;
      unrestrictedData = true;
      wifi = true;
      mobile = false;
      vpn = false;
    };

    access = {
      displayOverOtherApps = true;
      modifySystemSettings = false;
      installUnknownApps = false;
      allFilesAccess = false;
      usageAccess = false;
      alarmsAndReminders = true;
      pictureInPicture = false;
      mediaManagement = false;
      fullScreenNotifications = false;
      turnScreenOn = false;
      wifiControl = false;
      restrictedSettings = true;
      notificationAccess = true;
      accessibility = true;
    };

    pauseIfUnused = false;
    enabled = true;       # false would disable it (it would not run, and is hidden)
    paused = false;       # true would pause it (it could not be opened)
    openLinks = true;
    language = "tr-TR";
    # defaultFor = [ "browser" ];   # only for an app that can be one; Zorin Connect cannot
  };
}
