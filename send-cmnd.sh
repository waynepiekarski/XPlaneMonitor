#!/bin/bash

if [ "$1" == "" ]; then
  echo "Requires X-Plane CMND name"
  echo "  example: sim/instruments/EFIS_mode_dn"
  echo "  example: sim/instruments/map_zoom_in"
  exit 1
fi

set -xv
printf "CMND\0$1\0" | nc -v -b -u 255.255.255.255 47000
