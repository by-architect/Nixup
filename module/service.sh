# Runs as root at every boot (Magisk's late_start service): once Android has
# started, makes the phone match the profile again.
MODDIR=${0%/*}

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sh "$MODDIR/apply.sh" boot
BY=boot
. "$MODDIR/common.sh"
# The services the last build left running (services.* in the config) start again; the engine
# tells about the ones that do not.
run_open service "Start at boot"
engine services < /dev/null
# Android relabels /data at each start (init.rc: restorecon --recursive --skip-ce /data) and skips a
# folder only by the mark restorecon leaves on one it was run on; /data/adb gets none, so the walk
# went through every file of Seraph's store (233,000: four minutes of every start, also after the
# module's removal, which keeps the data). Run on Seraph's folder, it leaves that mark: from then on
# the start skips it, and this takes no time (after a system update, it walks it once again).
setsid nice -n 19 /system/bin/restorecon -R "$DATA" < /dev/null > /dev/null 2>&1 &
# What the restart broke off (a backup, a build, ...) starts again once the phone is unlocked.
setsid sh "$MODDIR/resume.sh" boot < /dev/null > /dev/null 2>&1 &
# The app schedules its update check (it keeps it, but a new install has none yet).
tell_app schedule
