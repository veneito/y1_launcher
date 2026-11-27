#!/usr/bin/env bash
# Install a CI build of this fork on a rooted Y1 over ADB, without reflashing.
#   scripts/adb-install.sh <github-run-id>          (repo: veneito/y1_launcher, artifact apk-maloja)
# Copies the APK to /system/app AND its native libs to /system/lib (without the libs JJ crash-loops), checks
# every file by MD5, keeps the previous APK as /data/local/tmp/com.themoon.y1.apk.prev, ejects the music
# storage cleanly, then reboots. Needs: gh, adb (ADB env var or F:/Tools/platform-tools/adb.exe), unzip, md5sum.
set -euo pipefail
RUN=${1:?usage: adb-install.sh <github-run-id>}
export MSYS_NO_PATHCONV=1
A=${ADB:-/f/Tools/platform-tools/adb.exe}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

gh run download "$RUN" -R veneito/y1_launcher -n apk-maloja -D "$WORK"
mkdir "$WORK/libs" && unzip -q -j "$WORK/app-debug.apk" 'lib/armeabi-v7a/*.so' -d "$WORK/libs"

winpath() { command -v cygpath >/dev/null && cygpath -m "$1" || echo "$1"; }
put() { # verified copy: push to a temp file, check it, write into place, check again
  local src=$1 dst=$2 tmp=/data/local/tmp/y1put want
  want=$(md5sum < "$src" | cut -d' ' -f1)
  "$A" push "$(winpath "$src")" $tmp >/dev/null
  [ "$want" = "$("$A" shell md5 $tmp | cut -d' ' -f1)" ] || { echo "upload mismatch: $dst" >&2; exit 1; }
  "$A" shell "cat $tmp > '$dst' && chmod 644 '$dst' && rm $tmp"
  [ "$want" = "$("$A" shell md5 "'$dst'" | cut -d' ' -f1)" ] || { echo "install mismatch: $dst" >&2; exit 1; }
  echo "ok $(basename "$dst")"
}

"$A" wait-for-device
until [ "$("$A" shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 1; done
"$A" shell mount -o rw,remount /system
"$A" shell cp /system/app/com.themoon.y1.apk /data/local/tmp/com.themoon.y1.apk.prev
put "$WORK/app-debug.apk" /system/app/com.themoon.y1.apk
for f in "$WORK"/libs/*.so; do put "$f" "/system/lib/$(basename "$f")"; done

"$A" shell sync
"$A" shell vdc volume unmount /storage/sdcard0 >/dev/null || true  # eject first: a hard reboot dirties the FAT
"$A" shell sync
"$A" reboot
sleep 8; "$A" wait-for-device
until [ "$("$A" shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
sleep 30
p1=$("$A" shell ps | grep themoon | awk '{print $2}'); sleep 15; p2=$("$A" shell ps | grep themoon | awk '{print $2}')
[ -n "$p1" ] && [ "$p1" = "$p2" ] && echo "JJ running (pid $p1)" || { echo "JJ not stable ($p1 / $p2): check y1_crash_log.txt" >&2; exit 1; }
