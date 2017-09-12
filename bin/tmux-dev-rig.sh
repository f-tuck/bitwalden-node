#!/bin/sh

. ./deps/node/bin/activate
tmux new-session -d "rlwrap lein figwheel"
tmux split-window -v "while [ 1 ]; do ./bin/start-server-node.sh --dev; done"
tmux select-layout even-vertical
tmux -2 attach-session -d
