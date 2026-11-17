#!/system/bin/sh
# JL10 QuickStep module. Runs early in boot, before system_server.
#
# 1. Framework overlay: config_recentsComponentName -> JL10 (it becomes the recents
#    provider).
# 2. Privileged install: SystemUI only connects to a recents provider that is a system app, and
#    Android only grants the window-animation permission to privileged apps, so JL10 is
#    added to /product/priv-app, after its privapp-permissions whitelist is in place (a priv-app
#    without one can stop boot).
#
# Nothing on the real partitions changes: folders are copied (with their SELinux labels) and the
# copy bind-mounted over the original, so JL10 sits at a normal path among the stock apps with no
# overlayfs involved. The priv-app folder used is the smallest one (/system, else /system_ext);
# its copy is kept between boots and only redone when the original changes (a system update).
# Only if that's not possible is our folder layered over /product/priv-app with a read-only
# overlayfs, as before. Everything is logged to boot.log next to this script.

MODDIR=${0%/*}
LOG="$MODDIR/boot.log"
SYSFILE=u:object_r:system_file:s0
echo "$(date) start" > "$LOG"

# Boot-loop guard: every boot that gets here counts one, and service.sh (run once the boot has
# finished) sets it back to 0. Two boots in a row that never finished: this module is switched off
# (the root manager's "disable") and nothing is mounted, so the phone boots stock. Reinstalling the
# module from JL10's settings switches it back on.
COUNT="$MODDIR/boot_count"
n=$(cat "$COUNT" 2>/dev/null)
case "$n" in ''|*[!0-9]*) n=0 ;; esac
if [ "$n" -ge 2 ]; then
  echo "boot-loop guard: the last $n boots never finished - module switched off, nothing mounted" >> "$LOG"
  touch "$MODDIR/disable"
  echo 0 > "$COUNT"
  exit 0
fi
echo $((n + 1)) > "$COUNT"

# Root for JL10 once it's a privileged app: Android 17 runs privileged apps in a new SELinux
# domain (priv_app_36) that Magisk's own rules don't let reach its su socket, so JL10's root
# shell (settings sync, Restart System UI...) was refused. KernelSU / APatch don't need this.
if command -v magiskpolicy >/dev/null 2>&1; then
  for d in priv_app priv_app_36 priv_app_37; do
    magiskpolicy --live "allow $d magisk unix_stream_socket connectto" >/dev/null 2>&1 && echo "root allowed from $d" >> "$LOG"
  done
fi

label_of() { ls -Zd "$1" 2>/dev/null | awk '{print $1}'; }

# mirror_add TARGET_DIR SRC NAME
#   Copies TARGET_DIR (with SELinux labels), adds SRC as TARGET_DIR/NAME, bind-mounts the copy.
mirror_add() {
  T="$1"; SRC="$2"; NAME="$3"
  [ -d "$T" ] || { echo "no $T" >> "$LOG"; return 1; }
  if [ -e "$T/$NAME" ]; then echo "$NAME already in $T" >> "$LOG"; return 0; fi
  W="$MODDIR/mirror$(echo $T | tr / _)"
  rm -rf "$W"; mkdir -p "$W" || return 1
  if ! cp -a "$T/." "$W/" 2>>"$LOG"; then echo "copy of $T failed" >> "$LOG"; rm -rf "$W"; return 1; fi
  n1=$(find "$T" | wc -l); n2=$(find "$W" | wc -l)
  if [ "$n1" != "$n2" ]; then echo "incomplete copy of $T ($n1 vs $n2)" >> "$LOG"; rm -rf "$W"; return 1; fi
  (cd "$T" && find .) | while read -r f; do
    c=$(label_of "$T/$f"); [ -n "$c" ] && chcon "$c" "$W/$f" 2>/dev/null
  done
  cp -r "$SRC" "$W/$NAME" || { rm -rf "$W"; return 1; }
  chmod -R a+rX,go-w "$W/$NAME"; chcon -R "$SYSFILE" "$W/$NAME" 2>/dev/null
  if mount -o bind "$W" "$T"; then echo "added $NAME to $T (copy)" >> "$LOG"; return 0; fi
  echo "bind mount on $T failed" >> "$LOG"; return 1
}

# overlay_add TARGET_DIR SRC_DIR NAME
#   For big folders: a read-only overlayfs with SRC_DIR (as NAME) layered over TARGET_DIR. One
#   mount, so every process sees it, and the original entries are the real files underneath.
#   (An earlier version rebuilt the folder from one bind mount per entry: on some kernels
#   system_server saw those entries empty, took every system app there for uninstalled, wiped
#   their data and crashed on every start.) Afterwards the originals are checked through the
#   mount; anything wrong and it is taken down again.
overlay_add() {
  T="$1"; SRC="$2"; NAME="$3"
  [ -d "$T" ] || { echo "no $T" >> "$LOG"; return 1; }
  if [ -e "$T/$NAME" ]; then echo "$NAME already in $T" >> "$LOG"; return 0; fi
  # the layer lives in RAM: overlayfs refuses layers on /data (it is case-insensitive capable)
  L="$MODDIR/layer$(echo $T | tr / _)"
  umount -l "$L" 2>/dev/null; rm -rf "$L"; mkdir -p "$L" || return 1
  mount -t tmpfs -o mode=755 JL10 "$L" 2>>"$LOG" || { echo "no tmpfs for $T" >> "$LOG"; return 1; }
  chcon "$(label_of "$T")" "$L" 2>/dev/null
  cp -r "$SRC" "$L/$NAME" || { umount -l "$L"; return 1; }
  chmod -R a+rX,go-w "$L"; chcon -R "$SYSFILE" "$L/$NAME" 2>/dev/null
  # an app folder with its files, to check the originals are still readable through the mount
  probe=""
  for e in "$T"/*; do
    f=$(ls "$e"/*.apk 2>/dev/null | head -1)
    [ -n "$f" ] && { probe="$f"; break; }
  done
  before=$(ls "$T" | wc -l)
  if ! mount -t overlay JL10 -o ro,lowerdir="$L:$T" "$T" 2>>"$LOG"; then
    # Some kernels' overlayfs can't pass on SELinux labels ("no security xattr handler"). Then
    # the whole overlay can carry one label, which changes nothing if every file under $T already
    # has that very label. Needs sepolicy.rule's "filesystem associate" (loaded by the root manager).
    labels=$(ls -RZ "$T" 2>/dev/null | awk '/u:object_r/ {print $1}' | sort -u)
    if [ "$labels" = "$SYSFILE" ] && mount -t overlay JL10 -o ro,lowerdir="$L:$T",context="$SYSFILE" "$T" 2>>"$LOG"; then
      echo "(overlay with one SELinux label for all, as all of $T has)" >> "$LOG"
    else
      echo "overlay on $T not possible on this kernel (labels there: $(echo $labels))" >> "$LOG"
      umount -l "$L"; return 1
    fi
  fi
  after=$(ls "$T" | wc -l)
  if [ "$after" != "$((before + 1))" ] || { [ -n "$probe" ] && [ ! -s "$probe" ]; }; then
    echo "overlay on $T looks wrong ($before -> $after entries, probe $probe), undoing" >> "$LOG"
    umount -l "$T" 2>/dev/null; umount -l "$L" 2>/dev/null
    return 1
  fi
  echo "added $NAME to $T (overlay)" >> "$LOG"; return 0
}

skeleton_undo() {
  S="$MODDIR/skel$(echo $1 | tr / _)"
  for e in "$S"/*; do umount -l "$e" 2>/dev/null; done
  rm -rf "$S" 2>/dev/null
}

# seen_by_android TARGET_DIR NAME PROBE
#   Looks at TARGET_DIR the way system_server will: from the mount namespace of init (process
#   1), which zygote and so system_server start from. Our folder has to be there, and an original
#   app's APK (PROBE) still readable. This is the check that would have caught the old bug, where
#   the original folders looked fine here but were empty to system_server.
#   0 = fine, 1 = wrong, 2 = can't tell (no nsenter)
seen_by_android() {
  command -v nsenter >/dev/null 2>&1 || return 2
  nsenter -t 1 -m -- sh -c "[ -f '$1/$2/BB10Launcher.apk' ] && { [ -z '$3' ] || [ -s '$3' ]; }" 2>/dev/null
}

# take_down TARGET_DIR WAS_MOUNTPOINT: removes our mount over TARGET_DIR, here and as init sees it
take_down() {
  [ "$2" = 1 ] && return # (it was a mount point before us: never touch that)
  umount -l "$1" 2>/dev/null
  command -v nsenter >/dev/null 2>&1 && nsenter -t 1 -m -- umount -l "$1" 2>/dev/null
}

# priv_add TARGET_DIR SRC_DIR NAME: the overlay, kept only if Android sees it whole
priv_add() {
  T="$1"; SRC="$2"; NAME="$3"
  [ -d "$T" ] || { echo "no $T" >> "$LOG"; return 1; }
  if [ -e "$T/$NAME" ]; then echo "$NAME already in $T" >> "$LOG"; return 0; fi
  probe=""
  for e in "$T"/*; do
    f=$(ls "$e"/*.apk 2>/dev/null | head -1)
    [ -n "$f" ] && { probe="$f"; break; }
  done
  wasmp=0; grep -q " $T " /proc/self/mounts && wasmp=1
  if overlay_add "$T" "$SRC" "$NAME"; then
    seen_by_android "$T" "$NAME" "$probe"; r=$?
    [ "$r" = 0 ] && { echo "checked as Android sees it: complete" >> "$LOG"; return 0; }
    [ "$r" = 2 ] && { echo "(can't check as Android sees it; one overlay mount, kept)" >> "$LOG"; return 0; }
    echo "overlay not seen whole by Android, undoing" >> "$LOG"
    take_down "$T" "$wasmp"; umount -l "$MODDIR/layer$(echo $T | tr / _)" 2>/dev/null
  fi
  # No fallback to a skeleton of bind mounts: on some setups system_server sees its folders empty
  # even though every check from here (and from init's namespace) says they're complete, and in
  # that first boot Android already marks the system apps as uninstalled and wipes their data.
  skeleton_undo "$T" # (left over from versions that used one)
  return 1
}

# bind_priv TARGET_DIR SRC_DIR NAME
#   A full copy of TARGET_DIR (a priv-app folder) with SRC_DIR added as NAME, bind-mounted over
#   it: one plain bind mount of a real folder, no overlayfs. The copy is kept between boots and
#   redone only when TARGET_DIR changes; JL10's own folder in it is renewed every boot. Kept only
#   if Android (init's view) sees our folder and the original apps whole.
BIND_MAX_MB=400
bind_priv() {
  T="$1"; SRC="$2"; NAME="$3"
  [ -d "$T" ] || { echo "no $T" >> "$LOG"; return 1; }
  if [ -e "$T/$NAME" ]; then echo "$NAME already in $T" >> "$LOG"; return 0; fi
  mb=$(du -sm "$T" 2>/dev/null | cut -f1)
  case "$mb" in ''|*[!0-9]*) echo "can't size $T" >> "$LOG"; return 1 ;; esac
  [ "$mb" -le "$BIND_MAX_MB" ] || { echo "$T is $mb MB, too big to copy" >> "$LOG"; return 1; }
  free=$(df /data 2>/dev/null | tail -1 | awk '{print $4}')
  case "$free" in ''|*[!0-9]*) free=0 ;; esac
  [ $((free / 1024)) -ge $((mb * 2 + 200)) ] || { echo "not enough space on /data for a copy of $T" >> "$LOG"; return 1; }
  W="$MODDIR/priv$(echo $T | tr / _)"
  sig=$( (ls -lnR "$T"; ls -ZR "$T") 2>/dev/null | md5sum | cut -d' ' -f1)
  if [ "$(cat "$W.sig" 2>/dev/null)" != "$sig" ] || [ ! -d "$W" ]; then
    rm -rf "$W" "$W.sig"; mkdir -p "$W" || return 1
    if ! cp -a "$T/." "$W/" 2>>"$LOG"; then echo "copy of $T failed" >> "$LOG"; rm -rf "$W"; return 1; fi
    n1=$(find "$T" | wc -l); n2=$(find "$W" | wc -l)
    if [ "$n1" != "$n2" ]; then echo "incomplete copy of $T ($n1 vs $n2)" >> "$LOG"; rm -rf "$W"; return 1; fi
    chcon "$(label_of "$T")" "$W" 2>/dev/null
    (cd "$T" && find .) | while read -r f; do
      c=$(label_of "$T/$f"); [ -n "$c" ] && chcon -h "$c" "$W/$f" 2>/dev/null
    done
    echo "$sig" > "$W.sig"
    echo "copied $T ($mb MB, $n1 entries) for the bind mount" >> "$LOG"
  else
    echo "copy of $T up to date" >> "$LOG"
  fi
  rm -rf "$W/$NAME"
  cp -r "$SRC" "$W/$NAME" || return 1
  chmod -R a+rX,go-w "$W/$NAME"; chcon -R "$SYSFILE" "$W/$NAME" 2>/dev/null
  probe=""
  for e in "$T"/*; do
    f=$(ls "$e"/*.apk 2>/dev/null | head -1)
    [ -n "$f" ] && { probe="$f"; break; }
  done
  before=$(ls "$T" | wc -l)
  wasmp=0; grep -q " $T " /proc/self/mounts && wasmp=1
  mount -o bind "$W" "$T" 2>>"$LOG" || { echo "bind mount on $T failed" >> "$LOG"; return 1; }
  after=$(ls "$T" | wc -l)
  if [ "$after" != "$((before + 1))" ] || { [ -n "$probe" ] && [ ! -s "$probe" ]; }; then
    echo "bind mount on $T looks wrong ($before -> $after entries), undoing" >> "$LOG"
    take_down "$T" "$wasmp"; return 1
  fi
  seen_by_android "$T" "$NAME" "$probe"; r=$?
  if [ "$r" = 1 ]; then echo "bind mount not seen whole by Android, undoing" >> "$LOG"; take_down "$T" "$wasmp"; return 1; fi
  echo "added $NAME to $T (bind mount of a copy)" >> "$LOG"
  return 0
}

# ---------------------------------------------------------------- 1. overlay
STAGE="$MODDIR/stage"
rm -rf "$STAGE"; mkdir -p "$STAGE/BB10QuickstepOverlay" "$STAGE/BB10Launcher"
overlay_done=0
if [ -f "$MODDIR/BB10QuickstepOverlay.apk" ]; then
  cp "$MODDIR/BB10QuickstepOverlay.apk" "$STAGE/BB10QuickstepOverlay/"
  for T in /system_ext/overlay /product/overlay; do
    if mirror_add "$T" "$STAGE/BB10QuickstepOverlay" BB10QuickstepOverlay; then overlay_done=1; break; fi
  done
else
  echo "overlay apk missing" >> "$LOG"
fi

# ---------------------------------------------------------------- 2. privileged install
# Android only takes a priv-app's permission whitelist from its own partition's etc/permissions,
# so the whitelist goes in first, next to whichever priv-app folder JL10 joins.
XML=privapp-permissions-com.bb10.launcher.xml
priv_done=0
if [ "$overlay_done" = 1 ] && [ -f "$MODDIR/BB10Launcher.apk" ] && [ -f "$MODDIR/$XML" ]; then
  cp "$MODDIR/BB10Launcher.apk" "$STAGE/BB10Launcher/BB10Launcher.apk"
  # bind mount of a copy, on the smallest partition that allows it
  for P in /system /system_ext; do
    [ -d "$P/priv-app" ] && [ -d "$P/etc/permissions" ] || continue
    if mirror_add "$P/etc/permissions" "$MODDIR/$XML" "$XML" && [ -f "$P/etc/permissions/$XML" ]; then
      if bind_priv "$P/priv-app" "$STAGE/BB10Launcher" BB10Launcher && [ -f "$P/priv-app/BB10Launcher/BB10Launcher.apk" ]; then
        priv_done=1; break
      fi
    fi
  done
  # (older builds' overlay layer, unused now)
  [ "$priv_done" = 1 ] && rm -rf "$MODDIR/layer_product_priv-app" 2>/dev/null
  # fallback: the overlay on /product/priv-app, as before
  if [ "$priv_done" = 0 ]; then
    echo "bind mount not possible here; using the overlay on /product/priv-app" >> "$LOG"
    if mirror_add /product/etc/permissions "$MODDIR/$XML" "$XML" && [ -f "/product/etc/permissions/$XML" ] \
        && priv_add /product/priv-app "$STAGE/BB10Launcher" BB10Launcher && [ -f /product/priv-app/BB10Launcher/BB10Launcher.apk ]; then
      priv_done=1
    fi
  fi
  if [ "$priv_done" = 1 ]; then
    echo "launcher is now a privileged system app" >> "$LOG"
  else
    echo "priv-app step failed (launcher stays a normal app)" >> "$LOG"
  fi
else
  echo "privileged install skipped (overlay=$overlay_done)" >> "$LOG"
fi
