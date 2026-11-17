#!/system/bin/sh
# JL10 QuickStep module, while the phone boots.
#
# Boot-loop guard, second half (the first is in post-fs-data.sh, which counts boots that never
# finish). A boot can also fail without ever rebooting: system_server crashing and restarting
# over and over inside one boot. Android's Rescue Party then resets settings and eventually asks
# for a factory reset, so this has to act quickly: system_server restarting twice before the boot
# finishes, or the boot not finishing within 6 minutes, and this module is switched off (the root
# manager's "disable") and the phone reboots without it.
#
# Once the boot finishes, the count starts over.
MODDIR=${0%/*}
LOG="$MODDIR/boot.log"

give_up() {
  echo "$(date) boot-loop guard: $1 - module switched off, rebooting without it" >> "$LOG"
  touch "$MODDIR/disable"
  echo 0 > "$MODDIR/boot_count"
  sync
  sleep 2
  reboot
  exit 0
}

last=""
restarts=0
waited=0
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  pid=$(pidof system_server 2>/dev/null)
  if [ -n "$pid" ] && [ -n "$last" ] && [ "$pid" != "$last" ]; then
    restarts=$((restarts + 1))
    echo "$(date) system_server restarted during boot ($restarts)" >> "$LOG"
    [ "$restarts" -ge 2 ] && give_up "system_server keeps restarting"
  fi
  [ -n "$pid" ] && last="$pid"
  sleep 2
  waited=$((waited + 2))
  [ "$waited" -ge 360 ] && give_up "boot not finished after 6 minutes"
done

# (a little longer: a phone that boots, then falls over straight away, still counts)
sleep 30
echo 0 > "$MODDIR/boot_count"
echo "$(date) boot finished" >> "$LOG"

# JL10 uninstalled (the module stays behind): the phone's own launcher, switched off by the setup,
# comes back so there's a home screen, and this module removes itself at the next boot.
if ! pm path com.bb10.launcher >/dev/null 2>&1; then
  if [ -f "$MODDIR/old_launcher" ]; then
    pm enable "$(cat "$MODDIR/old_launcher")" >/dev/null 2>&1
    echo "$(date) JL10 is gone: $(cat "$MODDIR/old_launcher") switched back on" >> "$LOG"
  fi
  touch "$MODDIR/remove"
fi
