#!/bin/sh
# Raw packet probe through the debug build (H5600ScheduleRunner registers the receiver).
#   tools/watch_raw.sh get 52                       # read request for packet 0x52
#   tools/watch_raw.sh set "52 01 0F 0E 11 ..."     # write a whole packet (hex, spaces ok)
# The reply shows in logcat:  adb logcat | grep -E "Notification from|raw (GET|SET)"
# Always read the packet first and write back the original when the test is done.
# Spaces are turned into commas: adb's remote shell would split the argument otherwise.
[ $# -eq 2 ] || { echo "usage: $0 get|set <hex>"; exit 1; }
hex=$(echo "$2" | tr ' ' ',')
adb shell am broadcast -a org.avmedia.gshockGoogleSync.RAW --es "$1" "$hex" >/dev/null
