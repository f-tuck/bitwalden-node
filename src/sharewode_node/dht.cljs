(ns sharewode-node.dht
  (:require [sharewode-node.utils :refer [<<< buffer serialize-error sha1]]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! timeout chan close! put!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(nodejs/enable-util-print!)

; nodejs requirements
(defonce debug ((nodejs/require "debug") "sharewode-node.dht"))
(defonce DHT (nodejs/require "bittorrent-dht"))
(defonce bs58 (nodejs/require "bs58"))
(defonce bencode (nodejs/require "bencode"))

; compute a dht address hash
(defn address [k salt]
  (sha1 (js/Buffer.concat #js [(js/Buffer. (bs58.decode k)) (js/Buffer. salt)])))

; get a value from a DHT address (BEP44)
(defn get-value [dht infoHash]
  (go
    (let [infoHash (js/Buffer. infoHash "hex")
          [err response] (<! (<<< #(.get dht infoHash %)))
          response (js->clj response)
          response (if response
                     {"k" (-> response (get "k") (bs58.encode))
                      "salt" (-> response (get "salt") (.toString))
                      "seq" (-> response (get "seq"))
                      "v" (-> response (get "v") (.toString))
                      "s.dht" (-> response (get "sig") (.toString "hex"))})]
      [(serialize-error err) response])))

; put a value into the DHT (BEP44)
(defn put-value [dht value public-key-b58 salt seq-id signature-hex]
  (go
    (let [put-params {"k" (-> public-key-b58 (bs58.decode) (js/Buffer.))
                      "salt" (js/Buffer. salt)
                      "seq" seq-id
                      ;:cas (- seq-id 1)
                      "v" (js/Buffer. value)
                      "sign" (fn [buf] (js/Buffer. signature-hex "hex"))}
          [err infoHash put-nodes-count] (<! (<<< #(.put dht (clj->js put-params) %)))
          infoHash (if infoHash (.toString infoHash "hex"))]
      [(serialize-error err) infoHash put-nodes-count])))

