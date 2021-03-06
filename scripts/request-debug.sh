#!/bin/bash

HOSTNAME=$1
if [ "$HOSTNAME" == "" ]; then
  echo "Specify the hostname to transmit the requests to"
  exit 1
fi

# Calculate list of datarefs requested
cd `dirname $0`
REFS=`cat ../app/src/main/java/net/waynepiekarski/xplanemonitor/MainActivity.kt | grep "name == " | tr '"' '\n' | grep "/" | grep -v "//" | sort`

# Add some extra information
REFS="$REFS sim/aircraft/view/acf_descrip sim/aircraft/view/acf_author sim/aircraft/view/acf_notes sim/aircraft/view/acf_tailnum"

# Protocol for X-Plane ExtPanel plugin from https://github.com/vranki/ExtPlane
set -xv
for each in $REFS; do
  echo "sub $each"
done | nc $HOSTNAME 51000 | awk -F' ' '{ cmd="echo \042"$3"\042 | base64 --decode 2> /dev/null"; cmd | getline v; close(cmd); printf("[%-40s] [%-35s] --> [%s]\n", $2, $3, v) }'
