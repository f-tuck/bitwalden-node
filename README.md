
	npm install f-tuck/bitwalden-node
	./node_modules/.bin/bitwalden.js

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

