CLJS=$(wildcard src/**/**.cljs)
NPM=./build/node/bin/npm
NODE=./build/node/bin/node
NODE_VERSION=4.4.6
NODEENV_VERSION=0.13.6

default: deps build

deps: build/node/bin/node node_modules

build: build/sharewode-server-node.js

.PHONY: clean

build/nodeenv-src/nodeenv.py:
	git clone --branch $(NODEENV_VERSION) https://github.com/ekalinin/nodeenv.git build/nodeenv-src

$(NODE) $(NPM): build/nodeenv-src/nodeenv.py
	python ./build/nodeenv-src/nodeenv.py --node=$(NODE_VERSION) --prebuilt build/node
	find build/node -exec touch {} \;

node_modules: build/node/bin/npm package.json
	./build/node/bin/npm install

node_modules/webtorrent/webtorrent.min.js: node_modules

build/sharewode-server-node.js: $(CLJS) node_modules/webtorrent/webtorrent.min.js
	lein cljsbuild once prod

clean:
	lein clean
	rm -rf node_modules
