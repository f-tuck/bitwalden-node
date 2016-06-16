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

if [ "$DEVMODE" = "1" ]
then
  echo "Starting server in dev mode."
  ./build/node/bin/node target/server_dev/sharewode_node.js $@
else
  ./build/node/bin/node build/sharewode-server-node.js $@
fi
