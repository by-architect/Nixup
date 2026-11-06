# Snapcast, synchronous multi-room audio (see nix/services.nix for how services are made):
# services.snapserver as NixOS's, and services.snapclient, which NixOS has not got: named as it would
# be, with snapclient's own options. Both run snapcast from its Nix package, which nixpkgs builds with
# PipeWire, PulseAudio and Avahi: its files on the phone come to about 780 MB.
#
# snapserver serves its streams (stream.source: a pipe, librespot, airplay, tcp, ...) to the clients
# of the network, as on NixOS: its config is written from settings at each start, into
# /run/snapserver/snapserver.conf (the phone builds no files), and it keeps its clients and groups
# in /var/lib/snapserver.
#
# snapclient plays a server's stream on the phone: speaker, headphones or Bluetooth, as any app's
# music, with Android's volume. Android has no ALSA, PulseAudio or PipeWire for it to play into, so
# it writes the sound into a pipe (its "file" player), which Nixup's engine plays with Android's
# audio (AudioTrack); the time Android's audio takes is measured at each start and given to it as
# its latency, so it plays in time with the other rooms.
{ config, lib, pkgs, ... }:
let
  inherit (lib) mkOption types;
  server = config.services.snapserver;
  client = config.services.snapclient;

  # pkgs.formats.ini { listsAsDuplicateKeys = true; }, as NixOS's module has it, from lib alone: a
  # config without Nix packages has no pkgs.formats.
  iniAtom = types.nullOr (types.oneOf [ types.bool types.int types.float types.str types.path ]);
  iniSettings = types.attrsOf (types.attrsOf (types.either iniAtom (types.listOf iniAtom)));
  # A list is a key given once for each value (stream.source = [ a b ]); null leaves a key out.
  configText = lib.generators.toINI { listsAsDuplicateKeys = true; }
    (lib.mapAttrs (_: lib.filterAttrs (_: value: value != null)) server.settings);
  configFile = "/run/snapserver/snapserver.conf";

  # Its name on the server: hostID, or networking.hostName, or the phone's device name.
  clientID =
    if client.hostID != null then lib.escapeShellArg client.hostID
    else if config.networking.hostName != "" then lib.escapeShellArg config.networking.hostName
    else ''"$(/system/bin/settings get global device_name)"'';
  # The server's address as snapclient takes it: tcp://host:port, unless host is one already.
  serverUrl = if lib.hasInfix "://" (toString client.host) then client.host else "tcp://${client.host}:${toString client.port}";
  # snapclient resamples to a rate and bits, never to other channels: those are the stream's.
  resampleTo = lib.concatStringsSep ":" (lib.take 2 (lib.splitString ":" client.sampleFormat)) + ":*";
in
{
  imports = [
    # NixOS's own renames: a config written for an older NixOS builds here too.
    (lib.mkRenamedOptionModule [ "services" "snapserver" "listenAddress" ] [ "services" "snapserver" "settings" "tcp-streaming" "bind_to_address" ])
    (lib.mkRenamedOptionModule [ "services" "snapserver" "port" ] [ "services" "snapserver" "settings" "tcp-streaming" "port" ])
    (lib.mkRenamedOptionModule [ "services" "snapserver" "sampleFormat" ] [ "services" "snapserver" "settings" "tcp-streaming" "sampleformat" ])
    (lib.mkRenamedOptionModule [ "services" "snapserver" "codec" ] [ "services" "snapserver" "settings" "tcp-streaming" "codec" ])
    (lib.mkRenamedOptionModule [ "services" "snapserver" "streamBuffer" ] [ "services" "snapserver" "settings" "tcp-streaming" "chunk_ms" ])
    (lib.mkRenamedOptionModule [ "services" "snapserver" "buffer" ] [ "services" "snapserver" "settings" "tcp-streaming" "buffer" ])
    (lib.mkRenamedOptionModule [ "services" "snapserver" "send" ] [ "services" "snapserver" "settings" "tcp-streaming" "chunk_ms" ])
    (lib.mkRenamedOptionModule [ "services" "snapserver" "controlPort" ] [ "services" "snapserver" "settings" "tcp-control" "port" ])
    (lib.mkRenamedOptionModule [ "services" "snapserver" "tcp" "enable" ] [ "services" "snapserver" "settings" "tcp-control" "enabled" ])
    (lib.mkRenamedOptionModule [ "services" "snapserver" "tcp" "listenAddress" ] [ "services" "snapserver" "settings" "tcp-control" "bind_to_address" ])
    (lib.mkRenamedOptionModule [ "services" "snapserver" "tcp" "port" ] [ "services" "snapserver" "settings" "tcp-control" "port" ])
    (lib.mkRenamedOptionModule [ "services" "snapserver" "http" "enable" ] [ "services" "snapserver" "settings" "http" "enabled" ])
    (lib.mkRenamedOptionModule [ "services" "snapserver" "http" "listenAddress" ] [ "services" "snapserver" "settings" "http" "bind_to_address" ])
    (lib.mkRenamedOptionModule [ "services" "snapserver" "http" "port" ] [ "services" "snapserver" "settings" "http" "port" ])
    (lib.mkRenamedOptionModule [ "services" "snapserver" "http" "docRoot" ] [ "services" "snapserver" "settings" "http" "doc_root" ])
    (lib.mkRemovedOptionModule [ "services" "snapserver" "streams" ] "Configure `services.snapserver.settings.stream.source` instead")
  ];

  options.services.snapserver = {
    enable = lib.mkEnableOption "snapserver";

    package = lib.mkPackageOption pkgs "snapcast" { };

    settings = mkOption {
      default = { };
      description = ''
        Snapserver configuration, as on NixOS: snapserver.conf's sections and keys. Refer to the
        [example configuration](https://github.com/badaix/snapcast/blob/develop/server/etc/snapserver.conf)
        for possible options. A pipe it reads is best in /run/snapserver (its /tmp is its own).
      '';
      type = types.submodule {
        freeformType = iniSettings;
        options = {
          stream = {
            source = mkOption {
              type = with types; either str (listOf str);
              example = "pipe:///run/snapserver/snapfifo?name=default";
              description = "One or multiple URIs to PCM input streams.";
            };
          };

          tcp-streaming = {
            enabled = lib.mkEnableOption "streaming via TCP" // { default = true; };
            bind_to_address = mkOption {
              default = "::";
              description = "Address to listen on for snapclient connections.";
            };
            port = mkOption {
              type = types.port;
              default = 1704;
              description = "Port to listen on for snapclient connections.";
            };
          };

          tcp-control = {
            enabled = lib.mkEnableOption "the TCP JSON-RPC";
            bind_to_address = mkOption {
              default = "::";
              description = "Address to listen on for control connections.";
            };
            port = mkOption {
              type = types.port;
              default = 1705;
              description = "Port to listen on for control connections.";
            };
          };

          http = {
            enabled = lib.mkEnableOption "the HTTP JSON-RPC";
            bind_to_address = mkOption {
              default = "::";
              description = "Address to listen on for HTTP connections.";
            };
            port = mkOption {
              type = types.port;
              default = 1780;
              description = "Port to listen on for HTTP connections.";
            };
            doc_root = mkOption {
              type = with types; nullOr path;
              default = pkgs.snapweb;
              defaultText = lib.literalExpression "pkgs.snapweb";
              description = "Path to serve from the HTTP server's root (Snapweb, a player and control page).";
            };
          };
        };
      };
    };

    openFirewall = mkOption {
      type = types.bool;
      default = false;
      description = "Open the ports in the firewall, as on NixOS. A phone has no firewall in its way, so here it does nothing.";
    };
  };

  options.services.snapclient = {
    enable = lib.mkEnableOption ''
      snapclient, Snapcast's client: plays a snapserver's stream on the phone (speaker, headphones,
      Bluetooth) through Android's audio, as music, in time with the server's other clients'';

    package = lib.mkPackageOption pkgs "snapcast" { };

    host = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "192.168.1.10";
      description = ''
        The snapserver: its name or IP address (a tailnet's too, when the phone reaches it), or its
        whole address, like "ws://server:1780". A phone cannot look for servers by itself (mDNS), so
        it is needed.
      '';
    };

    port = mkOption {
      type = types.port;
      default = 1704;
      description = "The snapserver's port for clients (its tcp-streaming.port).";
    };

    hostID = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "logos";
      description = ''
        The client's ID on the server, under which it keeps its name, group and volume (snapclient's
        --hostID). null: networking.hostName, or the phone's device name.
      '';
    };

    instance = mkOption {
      type = types.ints.positive;
      default = 1;
      description = "Its instance number, for more than one client on the phone (each with its own ID).";
    };

    latency = mkOption {
      type = types.int;
      default = 0;
      example = 120;
      description = ''
        Milliseconds to play earlier, on top of the time Android's audio takes, which is measured at
        each start: for a Bluetooth speaker, which adds its own.
      '';
    };

    sampleFormat = mkOption {
      type = types.strMatching "[0-9]+:(16|24|32):[12]";
      default = "48000:16:2";
      description = ''
        What it plays, as rate:bits:channels: the stream is resampled to its rate and bits when they
        differ; its channels are the stream's own (the server's sampleformat), 2 almost always.
        24 and 32 bits need Android 12 or newer.
      '';
    };

    mixer = mkOption {
      type = types.str;
      default = "software";
      description = ''
        How the volume the server sets for this client is applied: "software" (in the sound itself),
        or "none" (the server's volume is left aside; Android's alone counts).
      '';
    };

    extraFlags = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "--logfilter=*:debug" ];
      description = "More snapclient flags.";
    };
  };

  config = lib.mkMerge [
    (lib.mkIf server.enable {
      # As with --daemon on NixOS: its clients, groups and volumes kept in /var/lib/snapserver.
      services.snapserver.settings.server.datadir = lib.mkDefault "/var/lib/snapserver";

      systemd.services.snapserver = {
        after = [ "network.target" "nss-lookup.target" ];
        description = "Snapserver";
        wantedBy = [ "multi-user.target" ];
        before = [ "mpd.service" "mopidy.service" "snapclient.service" ];
        environment.HOME = "/var/lib/snapserver";
        # Its config, written at each start: the phone builds no files (NixOS's is /etc/snapserver.conf).
        preStart = ''
          cat > ${configFile} << 'NIXUP_SNAPSERVER_CONF'
          ${configText}
          NIXUP_SNAPSERVER_CONF
        '';
        serviceConfig = {
          ExecStart = "${lib.getExe' server.package "snapserver"} --config=${configFile}";
          Restart = "on-failure";
          RestartSec = 5;
          RuntimeDirectory = "snapserver";
          StateDirectory = "snapserver";
        };
      };
    })

    (lib.mkIf client.enable {
      systemd.services.snapclient = {
        description = "Snapcast's client: ${toString client.host}'s stream on the phone";
        wantedBy = [ "multi-user.target" ];
        after = [ "tailscaled.service" "snapserver.service" ];
        environment.HOME = "/var/lib/snapclient";
        # Android's audio (the engine's audio-out, from Nixup's module) plays what snapclient writes
        # into a pipe; the pipe is opened both ways, so neither side waits for the other to open it.
        # When one of them ends, so does the other, and the unit starts them again.
        script = ''
          pipe=/run/snapclient/sound latency=/run/snapclient/latency
          module=''${NIXUP_MODULE:-/data/adb/modules/nixup}
          rm -f "$pipe" "$latency"
          mkfifo "$pipe"
          CLASSPATH="$module/app.apk" /system/bin/app_process /system/bin dev.nixup.manager.engine.Main \
            audio-out --format=${client.sampleFormat} --latency-file="$latency" <> "$pipe" &
          player=$!
          client=
          trap 'kill $player $client 2> /dev/null; wait; exit 0' TERM INT
          for _ in $(seq 100); do
            [ -s "$latency" ] && break
            kill -0 $player 2> /dev/null || { wait $player; exit 1; }
            sleep 0.1
          done
          [ -s "$latency" ] || { echo "Android's audio did not start in 10 seconds"; kill $player; exit 1; }
          snapclient --player=file:filename=stdout --sampleformat=${resampleTo} \
            --latency=$(( $(cat "$latency") + ${toString client.latency} )) --hostID=${clientID} \
            --instance=${toString client.instance} --mixer=${lib.escapeShellArg client.mixer} --logsink=stderr \
            ${lib.escapeShellArgs client.extraFlags} ${lib.escapeShellArg serverUrl} > "$pipe" &
          client=$!
          # (bash -e: the first to end must not end the script before the other is stopped.)
          status=0
          wait -n $player $client || status=$?
          kill $player $client 2> /dev/null || true
          wait || true
          exit $status
        '';
        path = [ client.package ];
        serviceConfig = {
          Restart = "always";
          RestartSec = 5;
          RuntimeDirectory = "snapclient";
          StateDirectory = "snapclient";
        };
      };
    })

    {
      assertions = [
        {
          assertion = !client.enable || client.host != null;
          message = "services.snapclient.host: the snapserver's name or address; a phone cannot look for servers by itself (mDNS).";
        }
      ];
    }
  ];
}
