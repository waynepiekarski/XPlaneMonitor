#!/bin/bash

cd `dirname $0`
OUT=app/src/main/java/net/waynepiekarski/xplanemonitor/XPlaneData.java

echo "package net.waynepiekarski.xplanemonitor;" > $OUT
echo "class XPlaneData {" >> $OUT
echo "  public static final String[] names = {" >> $OUT
cat xplane-row.csv | tr '|' '\n' | tr -d ' ' | tr -d '\000' | tr -d '\r' | tr -s '_' | awk -F',' '{ print "\"" $1 "\"," }' | grep -v '""' >> $OUT
echo "  };" >> $OUT
echo >> $OUT
echo "  public static final String[] units = {" >> $OUT
cat xplane-row.csv | tr '|' '\n' | tr -d ' ' | tr -d '\000' | tr -d '\r' | tr -s '_' | awk -F',' '{ print "\"" $2 "\"," }' | grep -v '""' >> $OUT
echo "  };" >> $OUT
echo "}" >> $OUT
