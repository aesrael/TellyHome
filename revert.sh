#!/bin/sh
# Back to the stock TCL/Google home screen. Nothing is uninstalled.
set -e
TV=${1:-192.168.1.22}
adb connect $TV:5555
adb -s $TV:5555 shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
echo "Reverted to stock home screen."
