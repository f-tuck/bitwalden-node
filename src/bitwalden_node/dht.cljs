(ns bitwalden-node.dht
  (:require [bitwalden-node.utils :refer [<<< buffer serialize-error sha1]]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! timeout chan close! put!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(nodejs/enable-util-print!)

; nodejs requirements
(defonce debug ((nodejs/require "debug") "bitwalden-node.dht"))
(defonce DHT (nodejs/require "bittorrent-dht"))
(defonce bs58 (nodejs/require "bs58"))
(defonce bencode (nodejs/require "bencode"))
(defonce nacl (nodejs/require "tweetnacl"))

; compute a dht address hash
(defn address [k salt]
  (sha1 (js/Buffer.concat #js [(js/Buffer. (bs58.decode k)) (js/Buffer. salt)])))

; get a value from a DHT address (BEP44)
(defn get-value [dht address-hash salt]
  (go
    (let [address-hash (js/Buffer. address-hash "hex")
          [err response] (<! (<<< #(.get dht address-hash (clj->js {:salt salt
                                                                    :verify (fn [signature message public-key] (.verify (.. nacl -sign -detached) message signature public-key))
                                                                    :nocache true}) %)))
          response (js->clj response)
          response (if response
                     {"k" (-> response (get "k") (bs58.encode))
                      "seq" (-> response (get "seq"))
                      "v" (-> response (get "v") (.toString))
                      "s.dht" (-> response (get "sig") (.toString "hex"))})]
      (if err
        (serialize-error err)
        response))))

; put a value into the DHT (BEP44)
(defn put-value [dht value public-key-b58 salt seq-id signature-hex]
  (go
    (let [put-params {"k" (-> public-key-b58 (bs58.decode) (js/Buffer.))
                      "salt" (js/Buffer. salt)
                      "seq" seq-id
                      ;:cas (- seq-id 1)
                      "v" (js/Buffer. value)
                      "sign" (fn [buf] (js/Buffer. signature-hex "hex"))}
          [err address-hash put-nodes-count] (<! (<<< #(.put dht (clj->js put-params) %)))
          address-hash (if address-hash (.toString address-hash "hex"))]
      (if err
        (serialize-error err)
        {"addresshash" address-hash
         "nodecount" put-nodes-count}))))

