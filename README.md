Bitwalden infrastructure mesh node.

### Quick start

	npm install f-tuck/bitwalden-node
	./node_modules/.bin/bitwalden-node

Then browse to `http://your-server:8923/bw/info` and check the logs in `~/.bitwalden/log/access.log`.

Tested with node `v7.2.0`.

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

## Thanks

	https://github.com/malyn/figwheel-node-template
	https://github.com/feross/webtorrent

