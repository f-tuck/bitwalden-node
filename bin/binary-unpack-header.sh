#!/bin/sh

found=`type node`
if [ "$?" = "0" ]
then
  mkdir -p ~/.bitwalden/logs
  sed -e '0,/^#GZIPPED-BINARY-FOLLOWS#$/d' $0 | gunzip -c | node >~/.bitwalden/log/bitwalden.log 2>&1
  exit $?
else
  echo "Can't find node binary in the PATH."
  exit 1
fi
#GZIPPED-BINARY-FOLLOWS#
