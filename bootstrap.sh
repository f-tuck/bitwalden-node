#!/usr/bin/env sh

git clone https://github.com/ekalinin/nodeenv.git nodeenv-src
python ./nodeenv-src/nodeenv.py --prebuilt nodeenv
. ./nodeenv/bin/activate
npm install
