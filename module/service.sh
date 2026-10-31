# Runs as root at every boot (Magisk's late_start service): once Android has
# started, makes the phone match the profile again.
MODDIR=${0%/*}

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sh "$MODDIR/apply.sh" boot
# The app schedules its update check (it keeps it, but a new install has none yet).
. "$MODDIR/common.sh"
tell_app schedule
