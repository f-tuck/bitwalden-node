#!/usr/bin/env bash

mydir="$( dirname "${BASH_SOURCE[0]}" )"
parentdir=$( readlink -f $mydir/.. )
cd "$mydir"

if [ "$1" = "" ]
then
  echo "Deploys the Bitwalden server node to the server specified."
  echo "Usage: $0 DESTINATION-HOST[,DESTINATION-HOST-2,...] [PATH]"
  echo "e.g. $0 my-server.net,my-other-server.net ~/bin/contrib/bitwalden-node"
else
  extravars="src=${parentdir}/"
  host="${1}"
  if [ "${2}" != "" ]
  then
    extravars="${extravars} dest=${1}"
  fi
  ansible-playbook -i $host, deploy.yml --extra-vars="${extravars}"
fi

