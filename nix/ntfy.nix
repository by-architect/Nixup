# Notifications from ntfy topics (ntfy.sh, or a server of one's own): services.ntfy, Nixup's own (NixOS has ntfy's
# server alone, services.ntfy-sh, which runs on the phone too: nix/nixos.nix). The phone listens to the topics, as
# ntfy's Android app does, and each message that comes is a notification of Nixup's app:
#
#   services.ntfy = {
#     enable = true;
#     topics = [ "phone-alerts-x7q2" "ntfy.example.org/backups" ];
#   };
#
# and then, from anywhere: curl -d "Backup done" ntfy.sh/phone-alerts-x7q2
#
# The listening is a service of its own, systemd.services.ntfy (the app's Services tab has it, with its log): Nixup's
# engine, run as root (see the engine's Ntfy.kt), which keeps one connection open to each server, for all of its topics,
# and hands each message to Nixup's app. It starts with the build and at the phone's start, and starts again when it
# stops. A connection that breaks (another network, the phone back from airplane mode, the server restarted) is made
# again, and asks the server for what came in the meantime: nothing is lost that the server still keeps (ntfy.sh: 12
# hours), and nothing comes twice. A topic new to the config starts with what comes after its first build.
#
# A message shows as ntfy's app shows it: its title (else the topic's name) and text, its tags that are emoji short
# codes as emojis, its priority as Android's importance (each priority a channel of its own in Android's notification
# settings, "ntfy messages: …"), its link opened by a tap (else the topic's page on its server), its buttons (view,
# http, broadcast, copy) and its attachment's link. A message that updates another (the same sequence_id) takes its
# place, and one deleted or cleared on the server goes from the phone too.
#
# Anyone who knows a topic's name on ntfy.sh can read it and send to it: pick a name no one guesses, or a server of
# one's own, whose users and access tokens (tokenFile; userName and passwordFile) keep the topics to their owners.
{ config, lib, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.services.ntfy;
  secretFiles = import ./secret-files.nix { inherit lib; };
  app = "dev.nixup.manager";

  server = lib.removeSuffix "/" cfg.server;

  # The login options, for the service (the topics on its server) and for each topic.
  loginOptions = default: {
    tokenFile = mkOption {
      type = secretFiles.type;
      default = null;
      example = lib.literalExpression "./ntfy-token";
      description = ''
        An access token (tk_…, from the server's web app: Account, Access tokens; or `ntfy token add`), from a file:
        one from the config's folder (./ntfy-token, a path, without quotes), which each build puts on the phone readable
        by root alone, never in the profile or Nix's store; the token itself read from elsewhere (a JSON file of
        secrets); a file made with pkgs.writeText; or, as a string, the full path of a file already on the phone.
        ${default}
      '';
    };

    userName = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "neo";
      description = "A user of the server, with passwordFile: instead of tokenFile. ${default}";
    };

    passwordFile = mkOption {
      type = secretFiles.type;
      default = null;
      example = lib.literalExpression "./ntfy-password";
      description = ''
        userName's password, from a file, as tokenFile takes it (its line break at the end is left out).
      '';
    };
  };

  topicModule = { ... }: {
    options = {
      topic = mkOption {
        type = types.str;
        example = "ntfy.example.org/backups";
        description = ''
          The topic: its name alone, on services.ntfy.server ("phone-alerts"); or with its server, as ntfy's own
          `ntfy subscribe` takes it: "ntfy.example.org/backups" (over https://), or a whole address
          ("http://192.168.1.10:2586/backups"). A name is 1 to 64 letters, digits, - and _.
        '';
      };

      name = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "Backups";
        description = ''
          The topic's name on the phone: the title of its messages that have none, and shown above those that have
          one. null: the topic itself (with its server, when that is not services.ntfy.server).
        '';
      };
    } // loginOptions "null with userName null too: services.ntfy's own login, for a topic on services.ntfy.server; none for another server.";
  };

  # A topic's server and name, as `ntfy subscribe` reads them: a name alone is on services.ntfy.server; "host/name" on
  # https://host; a whole address on the server before its last part (which may have a path: a server behind a proxy's
  # folder). Both null when it is none of these.
  locate = topic:
    let
      whole = builtins.match "(https?://[^/]+(/.*)?)/([^/]*)" (if builtins.match "https?://.*" topic != null then topic else "https://${topic}");
    in
    if !lib.hasInfix "/" topic then { inherit server; topicName = topic; }
    else if whole == null then { server = null; topicName = null; }
    else { server = builtins.elemAt whole 0; topicName = builtins.elemAt whole 2; };

  # The topics, with their servers, names on the phone and logins (a topic's own, else the service's for its server).
  topics = map (entry:
    let
      where = locate entry.topic;
      own = entry.tokenFile != null || entry.userName != null || entry.passwordFile != null;
      address = "${toString where.server}/${toString where.topicName}";
      short = lib.removePrefix "https://" (lib.removePrefix "http://" address);
      # A topic's own secrets: named after it, with a hash of its address (two servers may have a topic of one name).
      secret = kind: if !own then "ntfy-${kind}"
        else "ntfy-${toString where.topicName}-${builtins.substring 0 8 (builtins.hashString "sha256" address)}-${kind}";
    in
    {
      inherit (entry) topic;
      inherit (where) server topicName;
      inherit own address;
      login = if own then entry else if where.server == server then cfg else { tokenFile = null; userName = null; passwordFile = null; };
      label = if entry.name != null then lib.replaceStrings [ "\n" ] [ " " ] entry.name
        else if where.server == server then where.topicName else short;
      tokenSecret = secret "token";
      passwordSecret = secret "password";
    }) cfg.topics;

  optionName = topic: "services.ntfy.topics (${topic.topic})";
  validName = name: name != null && builtins.match "[-_A-Za-z0-9]{1,64}" name != null;
  addresses = map (topic: topic.address) topics;
  twice = lib.unique (lib.filter (address: lib.count (other: other == address) addresses > 1) addresses);

  # What the engine's listener takes (its unit's argument): each topic's server, name, name on the phone and login, the
  # secrets by their paths on the phone (a build that changes one starts the service again).
  settings = {
    state = "/var/lib/ntfy/state.json";
    topics = map (topic: lib.filterAttrs (_: value: value != null) {
      inherit (topic) server label;
      topic = topic.topicName;
      token = if topic.login.tokenFile == null then null else secretFiles.path config topic.tokenSecret topic.login.tokenFile;
      user = topic.login.userName;
      password = if topic.login.passwordFile == null then null else secretFiles.path config topic.passwordSecret topic.login.passwordFile;
    }) topics;
  };

  # A login's problems: what it lacks, or holds twice, and its files.
  loginChecks = name: login: [
    {
      assertion = login.tokenFile == null || login.userName == null;
      message = "${name}: tokenFile and userName are two ways to log in: give one.";
    }
    {
      assertion = (login.userName == null) == (login.passwordFile == null);
      message = if login.userName == null
        then "${name}.passwordFile is the password of userName, which is not given."
        else "${name}.userName ${login.userName} needs its password: passwordFile (or an access token in tokenFile instead).";
    }
    {
      assertion = secretFiles.valid login.tokenFile;
      message = secretFiles.problem "${name}.tokenFile" login.tokenFile;
    }
    {
      assertion = secretFiles.valid login.passwordFile;
      message = if builtins.isString login.passwordFile && !(builtins.hasContext login.passwordFile)
        then "${name}.passwordFile: a string is the full path of a file on the phone (/data/…); a file from the config's folder is a path, written without quotes, like ./ntfy-password (a password itself would be in Nix's store, which every user of the machine can read)."
        else secretFiles.problem "${name}.passwordFile" login.passwordFile;
    }
  ];
in
{
  options.services.ntfy = {
    enable = lib.mkEnableOption ''
      notifications from ntfy topics (services.ntfy.topics): the phone listens to them, as ntfy's Android app does, and
      each message is a notification of Nixup's app'' // {
      example = true;
    };

    server = mkOption {
      type = types.str;
      default = "https://ntfy.sh";
      example = "https://ntfy.example.org";
      description = ''
        The server of the topics named alone (ntfy's default-host): ntfy.sh, or a server of one's own (http:// or
        https://; one on the tailnet, or on the phone itself with services.ntfy-sh).
      '';
    };

    topics = mkOption {
      type = types.listOf (types.coercedTo types.str (topic: { inherit topic; }) (types.submodule topicModule));
      default = [ ];
      example = lib.literalExpression ''
        [
          "phone-alerts-x7q2"
          { topic = "ntfy.example.org/backups"; name = "Backups"; tokenFile = ./ntfy-token; }
        ]
      '';
      description = ''
        The topics to listen to: each a name (on services.ntfy.server), or "host/name", or a whole address, as
        `ntfy subscribe` takes them; or { topic = …; } with its own name on the phone (name) and its own login
        (tokenFile; userName and passwordFile).
      '';
    };
  } // loginOptions "null: none (a topic anyone may read).";

  config = lib.mkIf cfg.enable {
    android.secrets = lib.mkMerge ([ (secretFiles.secrets "ntfy-token" cfg.tokenFile // secretFiles.secrets "ntfy-password" cfg.passwordFile) ]
      ++ map (topic: lib.optionalAttrs topic.own (
        secretFiles.secrets topic.tokenSecret topic.login.tokenFile // secretFiles.secrets topic.passwordSecret topic.login.passwordFile
      )) topics);

    # Nixup's app shows the messages: with the notifications permission, never held back by Android's battery saving
    # (a phone's maker may stop an app it finds idle, and with it what the listener hands to it), and never paused for
    # being unused, which would take that permission away (it is seldom opened). Given back when services.ntfy goes.
    android.apps.${app} = {
      permissions.notifications = lib.mkDefault true;
      battery = lib.mkDefault "unrestricted";
      pauseIfUnused = lib.mkDefault false;
    };

    systemd.services.ntfy = lib.mkIf (topics != [ ]) {
      description = "Notifications from ntfy: ${lib.concatMapStringsSep ", " (topic: topic.label) topics}";
      wantedBy = [ "multi-user.target" ];
      # A server on the tailnet answers once Tailscale is up, one on the phone (services.ntfy-sh) once it runs.
      after = [ "tailscaled.service" "ntfy-sh.service" ];
      serviceConfig = {
        Restart = "always";
        RestartSec = 10;
        StateDirectory = "ntfy";
      };
      # Nixup's engine, from the module's app.apk (as services.snapclient's sound): it listens for as long as it runs.
      script = ''
        module=''${NIXUP_MODULE:-/data/adb/modules/nixup}
        CLASSPATH="$module/app.apk" exec /system/bin/app_process /system/bin dev.nixup.manager.engine.Main \
          ntfy ${lib.escapeShellArg (builtins.toJSON settings)}
      '';
    };

    assertions = [
      {
        assertion = builtins.match "https?://[^/]+.*" cfg.server != null;
        message = "services.ntfy.server: \"${cfg.server}\" is not an http:// or https:// address.";
      }
      {
        assertion = twice == [ ];
        message = "services.ntfy.topics: ${lib.concatStringsSep ", " twice} is there more than once.";
      }
    ]
    ++ loginChecks "services.ntfy" cfg
    ++ lib.concatMap (topic: [
      {
        assertion = topic.server != null;
        message = "${optionName topic}: an address needs its topic after the server, like https://ntfy.example.org/backups.";
      }
      {
        assertion = topic.server == null || validName topic.topicName;
        message = "${optionName topic}: \"${toString topic.topicName}\" is not a topic's name: 1 to 64 letters, digits, - and _.";
      }
    ] ++ lib.optionals topic.own (loginChecks (optionName topic) topic.login)) topics;

    warnings = lib.optional (cfg.topics == [ ])
      "services.ntfy is on with no topics (services.ntfy.topics): it listens to nothing."
      ++ lib.optional ((cfg.tokenFile != null || cfg.userName != null) && !lib.any (topic: !topic.own && topic.server == server) topics)
        "services.ntfy.tokenFile and userName are for the topics on services.ntfy.server (${server}), and no topic is there.";
  };
}
