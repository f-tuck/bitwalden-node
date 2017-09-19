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
      OIFS="${IFS}"
      IFS=','
      for server in ${1}
      do
        echo "Syncing to ${server}:"
        rsync -avz ./ ${server}:~/bitwalden-node/ --delete --recursive --exclude 'node*' --exclude '.*.swp' --exclude '.git/'
      done
      IFS=${OIFS}
      echo "Done."
    fi
    sleep 0.5;
  done
fi
