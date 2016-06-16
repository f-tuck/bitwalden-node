#!/usr/bin/env bash

cd "$( dirname "${BASH_SOURCE[0]}" )/.."

if [ "$1" = "" ]
then
  echo "Development tool to watch for local changes, build, and deploy to the server[s]."
  echo "Usage: $0 DESTINATION-HOST[,DESTINATION-HOST-2]"
  echo "e.g. $0 my-server.net,my-other-server.net"
else
  while true;
  do
    if ! make -q;
    then
      date;
      make;
      echo ./bin/deploy-to.sh ${1}
      ./bin/deploy-to.sh ${1}
      echo "Done."
    fi
    sleep 0.5;
  done
fi
