CLJS=$(wildcard src/**/**.cljs)
NPM=./deps/node/bin/npm
NODE=./deps/node/bin/node
NODE_VERSION=7.2.0
NODEENV_VERSION=0.13.6

bitwalden.js: build/bitwalden-server-node.js
	echo "#!/usr/bin/env node" > $@
	cat $< $@
	chmod 755 $@

build/bitwalden-server-node.js: $(CLJS) $(NODE) node_modules node_modules/webtorrent/webtorrent.min.js
	lein cljsbuild once prod

.PHONY: clean

deps/nodeenv-src/nodeenv.py:
	git clone --branch $(NODEENV_VERSION) https://github.com/ekalinin/nodeenv.git deps/nodeenv-src

$(NODE) $(NPM): deps/nodeenv-src/nodeenv.py
	python ./deps/nodeenv-src/nodeenv.py --node=$(NODE_VERSION) --prebuilt deps/node
	find deps/node -exec touch {} \;

node_modules: deps/node/bin/npm package.json
	. ./deps/node/bin/activate && npm install

node_modules/webtorrent/webtorrent.min.js: node_modules

clean:
	lein clean
	rm -rf node_modules deps build
