# Apps from Google Play, downloaded as Aurora Store downloads them: android.packages.playstore names
# them by package name, in quotes (Google Play has no list of its apps to name them from, as
# pkgs.fdroid does), and each build installs and updates them as it does the repositories' apps.
#   android.packages.playstore = [ "com.whatsapp" "org.telegram.messenger" ];
#   android.playstore.account = "you@gmail.com";
# The phone checks in with Google Play as itself (its Android, processor, screen, languages), so
# Play gives it the split APKs made for it, as a Google account's downloads (the paid apps it bought
# come too). The account signs in once on the phone, on Google's own page in the Seraph app (⋮ →
# Google Play account), which keeps its token where root alone reads it; or tokenFile gives the token
# (seraph-play-login makes one on a PC), so a phone needs no sign-in. A build that lists apps here
# checks the account first: with none, or one whose token Google refuses, it stops before changing
# anything and says to sign in. The engine does the rest (PlayStore.kt).
{ config, lib, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.android.playstore;
  secretFiles = import ./secret-files.nix { inherit lib; };
in
{
  options.android.playstore = {
    account = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "you@gmail.com";
      description = ''
        The Google account the apps of android.packages.playstore are downloaded with, by its email
        address: signed in once on the phone (the Seraph app: ⋮ → Google Play account), or with its
        tokenFile. null: the account signed in on the phone (a build stops when there is none). Better an
        account of its own for this: Google may block an account that downloads with an unofficial
        client, as Aurora Store's own FAQ warns.
      '';
    };

    tokenFile = mkOption {
      type = secretFiles.type;
      default = null;
      example = lib.literalExpression "./google-play.token";
      description = ''
        The account's token (an AAS token, aas_et/…) in a file: one from the config's folder (a path,
        without quotes), which each build puts on the phone readable by root alone, or the full path of one
        on the phone; or the token itself, as text from a file of secrets. The phone then needs no sign-in.
        seraph-play-login makes one on a PC (nix run github:by-architect/Seraph#seraph-play-login), and the
        Seraph app shows the one it signed in with (⋮ → Google Play account → Show token). The token acts
        as the account itself: keep it out of any repository others can read.
      '';
    };
  };

  config = {
    android.secrets = secretFiles.secrets "android.playstore.tokenFile" cfg.tokenFile;

    # For the profile: the account, and where its token is on the phone (null: the phone's sign-in).
    system.build.playstore = {
      inherit (cfg) account;
      tokenFile = secretFiles.path config "android.playstore.tokenFile" cfg.tokenFile;
    };

    assertions = [
      {
        assertion = cfg.account == null || builtins.match "[^@ ]+@[^@ ]+[.][^@ ]+" cfg.account != null;
        message = "android.playstore.account: \"${toString cfg.account}\" is not an email address (like \"you@gmail.com\").";
      }
      {
        assertion = cfg.tokenFile == null || cfg.account != null;
        message = "android.playstore.tokenFile: name the account it is the token of too: android.playstore.account = \"you@gmail.com\";";
      }
      { assertion = secretFiles.valid cfg.tokenFile; message = secretFiles.problem "android.playstore.tokenFile" cfg.tokenFile; }
    ];
  };
}
