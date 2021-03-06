CLJS=$(wildcard src/**/**.cljs)
NPM=./deps/node/bin/npm
NODE=./deps/node/bin/node
NODE_VERSION=7.2.0
NODEENV_VERSION=0.13.6

all: bitwalden.js bitwalden-daemon

bitwalden-daemon: build/bitwalden-bundled.js bin/binary-unpack-header.sh bin/make-binary.sh
	./bin/make-binary.sh

build/bitwalden-bundled.js: build/bitwalden-server-node.js
	echo "#!/usr/bin/env node" > $@
	./node_modules/.bin/browserify --node $< | sed -e "s+`pwd`++" | ./node_modules/.bin/uglifyjs > $@
	chmod 755 $@

bitwalden.js: build/bitwalden-server-node.js
	cp $< $@
	chmod 755 $@

build/bitwalden-server-node.js: $(CLJS) $(NODE) node_modules node_modules/webtorrent/webtorrent.min.js package.json project.clj
	lein cljsbuild once prod

deps/nodeenv-src/nodeenv.py:
	git clone --branch $(NODEENV_VERSION) https://github.com/ekalinin/nodeenv.git deps/nodeenv-src

$(NODE) $(NPM): deps/nodeenv-src/nodeenv.py
	python ./deps/nodeenv-src/nodeenv.py --node=$(NODE_VERSION) --prebuilt deps/node
	find deps/node -exec touch {} \;

node_modules: deps/node/bin/npm package.json
	. ./deps/node/bin/activate && npm install

node_modules/webtorrent/webtorrent.min.js: node_modules

.PHONY: clean clean-build

clean-build:
	lein clean
	rm -rf bitwalden-daemon bitwalden-daemon.js bitwalden.js build/bitwalden-bundled.js build/bitwalden-server-node.js

clean: clean-build
	rm -rf node_modules deps build
