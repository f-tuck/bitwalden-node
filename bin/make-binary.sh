#!/bin/sh

cat bin/binary-unpack-header.sh > bitwalden-daemon
gzip -c build/bitwalden-bundled.js >> bitwalden-daemon
chmod 755 bitwalden-daemon
