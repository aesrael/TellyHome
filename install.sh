#!/bin/sh
# Install the custom launcher on the TV at $1 (default 192.168.1.22).
# Revert any time: ./revert.sh [ip]
set -e
TV=${1:-192.168.1.22}
HERE="$(dirname "$0")"
adb connect $TV:5555
adb -s $TV:5555 install -r "$HERE/out/Home-signed.apk"
echo "Installed. Now on the TV pick Home as the home app when asked,"
echo "or run: adb -s $TV:5555 shell cmd package set-home-activity home.custom.tv/.HomeActivity"
