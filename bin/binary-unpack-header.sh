#!/bin/sh

found=`type node`
if [ "$?" = "0" ]
then
  TMPFILE=$0.js
  LOGFILE=${LOGFILE:-~/.bitwalden/log/bitwalden.log}
  mkdir -p `dirname $LOGFILE`
  sed -e '0,/^#GZIPPED-BINARY-FOLLOWS#$/d' $0 | gunzip -c > $TMPFILE
  echo Bitwalden build `sha256sum $0` > $LOGFILE
  echo Starting at `date` >> $LOGFILE
  DEBUG=${DEBUG:-'bitwalden*'} exec node $TMPFILE >>$LOGFILE 2>&1
  exit $?
else
  echo "Can't find node binary in the PATH."
  exit 1
fi
#GZIPPED-BINARY-FOLLOWS#
