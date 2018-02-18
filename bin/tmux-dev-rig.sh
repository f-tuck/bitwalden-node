#!/bin/sh

. ./deps/node/bin/activate
tmux new-session -d "echo 'Starting figwheel...'; rlwrap lein figwheel"
wait_for_figwheel='echo "Waiting for figwheel..."; while [ "`netstat -ntlp 2>/dev/null | grep 3450`" == "" ]; do sleep 2; done; sleep 3'
tmux split-window -v "$wait_for_figwheel; while [ 1 ]; do ./bin/start-server-node.sh --dev; sleep 3; done"
tmux select-layout even-vertical
tmux -2 attach-session -d
