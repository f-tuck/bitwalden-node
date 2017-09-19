#!/usr/bin/env bash

cd "$( dirname "${BASH_SOURCE[0]}" )/.."

# check for --dev flag
for arg in "$@"
do
  case $arg in
    --dev)
      DEVMODE=1
      shift
      ;;
  esac
done

. ./deps/node/bin/activate

if [ "$DEVMODE" = "1" ]
then
  echo "Starting server in dev mode."
  node target/server_dev/bitwalden_node.js $@
else
  node build/bitwalden-server-node.js $@
fi
