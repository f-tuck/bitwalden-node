(ns sharewode-node.dht
  (:require [sharewode-node.utils :refer [<<< buffer]]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! timeout chan close! put!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(nodejs/enable-util-print!)

; nodejs requirements
(defonce debug ((nodejs/require "debug") "sharewode-node.dht"))
(defonce DHT (nodejs/require "bittorrent-dht"))
(defonce bs58 (nodejs/require "bs58"))

; get a value from a DHT address (BEP44)
(defn get-value [dht infoHash]
  (let [infoHash (js/Buffer. infoHash "hex")]
    (<<< #(.get dht infoHash %))))

; put a value into the DHT (BEP44)
(defn put-value [dht value public-key-b58 salt seq-id signature-b64]
  (let [put-params {:k (-> public-key-b58 (bs58.decode) (js/Buffer.))
                    :salt (js/Buffer. salt)
                    :seq seq-id
                    :v (js/Buffer. value)
                    :sign (fn [buf] (js/Buffer. signature-b64 "base64"))}]
    (<<< #(.put dht (clj->js put-params) %))))

