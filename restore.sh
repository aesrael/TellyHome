#!/bin/sh
# Full restore: stock home screen + re-enable everything disabled by debloat.
# Nothing was uninstalled, so this returns the TV to factory state (minus accounts/data).
set -e
TV=${1:-192.168.1.22}
HERE="$(dirname "$0")"
adb connect $TV:5555
SERIAL=$(adb devices | awk '$2=="device" {print $1; exit}')
[ -z "$SERIAL" ] && { echo "TV not reachable"; exit 1; }
echo "Using $SERIAL"
for p in com.tcl.appmarket2 com.tcl.esticker com.tcl.bi com.tcl.usercenter \
         com.tcl.useragreement com.google.android.tvrecommendations \
         com.google.android.leanbacklauncher.recommendations com.google.android.music \
         com.tcl.tvweishi com.tcl.partnercustomizer com.google.android.tvlauncher; do
  adb -s $SERIAL shell cmd package install-existing $p
  adb -s $SERIAL shell pm enable $p
done
adb -s $SERIAL shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
echo "Restored: stock home + all packages re-enabled."
