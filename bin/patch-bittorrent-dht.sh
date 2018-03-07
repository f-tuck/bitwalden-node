#!/bin/sh

modules=`find node_modules -path "*/bittorrent-dht/client.js"`
for m in $modules;
do
  patch $m < src/bittorrent-dht.patch
done
