## Setup ##

	./bootstrap.sh

## Develop ##

Console 1:

	rlwrap lein figwheel

Console 2:

	node target/server_dev/sharewode_node.js

## Deploy ##

	lein cljsbuild once prod

## Thanks ##

	https://github.com/malyn/figwheel-node-template
	https://github.com/feross/webtorrent

