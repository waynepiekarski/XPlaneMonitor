#!/bin/bash

cd `dirname $0`
OUT=app/src/main/java/net/waynepiekarski/xplanemonitor/XPlaneData.kt

echo "package net.waynepiekarski.xplanemonitor" > $OUT
echo "internal object XPlaneData {" >> $OUT
echo "  val names = arrayOf(" >> $OUT
cat xplane-row.csv | tr '|' '\n' | tr -d ' ' | tr -d '\000' | tr -d '\r' | tr -s '_' | awk -F',' '{ print "\"" $1 "\"," }' | grep -v '""' | sed '$ s/,$//g' >> $OUT
echo "  )" >> $OUT
echo >> $OUT
echo "  val units = arrayOf(" >> $OUT
cat xplane-row.csv | tr '|' '\n' | tr -d ' ' | tr -d '\000' | tr -d '\r' | tr -s '_' | awk -F',' '{ print "\"" $2 "\"," }' | grep -v '""' | sed '$ s/,$//g' >> $OUT
echo "  )" >> $OUT
echo "}" >> $OUT
