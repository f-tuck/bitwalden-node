#!/bin/sh

cat bin/binary-unpack-header.sh > bitwalden-daemon
gzip -c bitwalden.js >> bitwalden-daemon
chmod 755 bitwalden-daemon
