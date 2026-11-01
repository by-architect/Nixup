# The settings of one app, under android.apps."<package name>". A setting left
# null is not managed: the phone keeps what the user set, and a setting that
# goes back to null gets the value it had before the module changed it.
{ lib, ... }:
let
  inherit (lib) mkOption types;

  permission = what: mkOption {
    type = types.nullOr (types.either types.bool (types.enum [ "ask" ]));
    default = null;
    description = ''
      Whether the app may use ${what}: true (allowed), false (denied, and it may
      not ask again) or "ask" (it asks when it needs it).
    '';
  };

  toggle = description: mkOption {
    type = types.nullOr types.bool;
    default = null;
    inherit description;
  };
in
{
  options = {
    permissions = {
      camera = permission "the camera";
      microphone = permission "the microphone";
      location = mkOption {
        type = types.nullOr (types.either types.bool (types.enum [ "ask" "always" "whileUsing" ]));
        default = null;
        description = ''
          Location: "always", "whileUsing" (or true: only while the app is in
          use), false (denied, and it may not ask again) or "ask".
        '';
      };
      preciseLocation = toggle "When location is allowed: precise (true) or only approximate (false).";
      contacts = permission "the contacts";
      calendar = permission "the calendar";
      phone = permission "the phone (making and managing calls, the phone's state)";
      callLogs = permission "the call logs";
      sms = permission "SMS and MMS";
      nearbyDevices = permission "nearby devices (Bluetooth, Wi-Fi, UWB)";
      physicalActivity = permission "physical activity";
      bodySensors = permission "body sensors and heart rate";
      notifications = permission "notifications";
      photosAndVideos = permission "photos and videos";
      musicAndAudio = permission "music and audio";
      files = permission "the files on shared storage (apps made for Android 12 and older)";
      mediaLocation = permission "the location saved in photos and videos";
    };

    battery = mkOption {
      type = types.nullOr (types.enum [ "unrestricted" "optimized" "restricted" ]);
      default = null;
      description = ''
        Battery use, as in the app's settings: "unrestricted" (runs in the
        background freely), "optimized" (Android's default) or "restricted" (no
        running in the background).
      '';
    };

    network = {
      backgroundData = toggle "Mobile data while the app is in the background.";
      unrestrictedData = toggle "Mobile data even while Data Saver is on.";
      wifi = toggle "Network access over Wi-Fi (LineageOS-based systems).";
      mobile = toggle "Network access over mobile data (LineageOS-based systems).";
      vpn = toggle "Network access over a VPN (LineageOS-based systems).";
    };

    access = {
      displayOverOtherApps = toggle "Display over other apps.";
      modifySystemSettings = toggle "Modify system settings.";
      installUnknownApps = toggle "Install unknown apps.";
      allFilesAccess = toggle "All files access.";
      usageAccess = toggle "Usage access (which apps are used, and how often).";
      alarmsAndReminders = toggle "Alarms and reminders (exact alarms).";
      pictureInPicture = toggle "Picture-in-picture.";
      mediaManagement = toggle "Media management (change and delete media files without asking).";
      fullScreenNotifications = toggle "Full-screen notifications.";
      turnScreenOn = toggle "Turn the screen on.";
      wifiControl = toggle "Wi-Fi control.";
      restrictedSettings = toggle "Restricted settings (needed by sideloaded apps for accessibility and notification access).";
      notificationAccess = toggle "Notification access (read and act on all notifications).";
      accessibility = toggle "Its accessibility services (control the screen).";
    };

    pauseIfUnused = toggle "Pause the app's activity (its permissions, notifications) when it is not used for months.";
    enabled = toggle "The app enabled (true) or disabled (false: it does not run and is hidden).";
    paused = toggle "The app paused (true: it cannot be opened or notify until unpaused).";
    debloat = toggle ''
      Remove the app, one that came with the phone (the ROM's own), for the user. Its data is
      kept, so false, taking this out again, or removing the module brings it back as it was.
      Parts Android needs (System UI, Settings, the phone app, the package installer, the
      launcher and keyboard in use, ...) are refused, and an app this phone does not have is
      passed over, so one config fits several phones. enabled = false disables an app instead.
    '';
    openLinks = toggle "Open the links the app supports (its web addresses) in it.";

    language = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "tr-TR";
      description = ''The app's language, like "tr-TR" or "en-US"; "system" follows the phone's.'';
    };

    defaultFor = mkOption {
      type = types.listOf (types.enum [ "browser" "dialer" "sms" "home" "assistant" "callScreening" ]);
      default = [ ];
      example = [ "browser" ];
      description = "What the app is the default app for.";
    };
  };
}
