(ns sharewode-node.core
  (:require [cljs.nodejs :as nodejs]))

(defonce DHT (nodejs/require "bittorrent-dht"))
(def test-hash "fd4928492a15e77b3661e523a4b81f656cdc04d8")

(nodejs/enable-util-print!)

(defn -main []
  (println "Sharewode node start.")
  (let [dht (DHT.)]
    (.listen dht 20000 (fn [] (print "DHT listening.")))
    (.lookup dht test-hash)
    (.on dht "announce" (fn [peer infoHash] (print "announce: " peer infoHash)))
    (.on dht "peer" (fn [peer infoHash from] (print "peer: " peer infoHash from)))
    (.announce dht test-hash)))

(set! *main-cli-fn* -main)
