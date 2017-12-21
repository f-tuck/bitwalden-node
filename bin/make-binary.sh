#!/bin/sh

cat bin/binary-unpack-header.sh > bitwalden-daemon
gzip -c bitwalden-bundled.js >> bitwalden-daemon
chmod 755 bitwalden-daemon
