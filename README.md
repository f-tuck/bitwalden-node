Bitwalden infrastructure mesh node.

If you're looking for the [client where you can log in and try out Bitwalden go here](https://github.com/f-tuck/bitwalden-reference-client/).

### Quick start

Tested with node `v7.2.0`.

	curl -L https://github.com/f-tuck/bitwalden-node/releases/download/v0.1-pre-alpha/bitwalden-daemon > bitwalden-daemon
	chmod 755 bitwalden-daemon
	./bitwalden-daemon &

Then browse to `http://your-server:8923/bw/info` to check if it's running.

Find the logs in:

 * `~/.bitwalden/log/bitwalden.log` - bitwalden library functions log here.
 * `~/.bitwalden/log/access.log` - HTTP access log in combined log format.

# Hacking

## Setup dependencies

	make

## Build

	make build

## Develop

Make sure you have [Leiningen](https://github.com/technomancy/leiningen/#installation) installed.

Console 1:

	rlwrap lein figwheel

Console 2:

	./build/node/bin/node target/server_dev/bitwalden_node.js

### Tmux wrapper

Or use the `tmux` wrapper script to launch both development processes:

	./bin/tmux-dev-rig.sh

## Thanks

	https://github.com/malyn/figwheel-node-template
	https://github.com/feross/webtorrent

