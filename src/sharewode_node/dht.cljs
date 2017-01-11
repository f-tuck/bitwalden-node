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

; announce an infohash to the network
(defn announce [dht infoHash torrent-server-port]
  ; make the DHT announcement
  (<<< #(.announce (dht :dht) infoHash torrent-server-port %)))

; look up peers for an infoHash
(defn lookup [dht infoHash]
  (let [infoHash (buffer infoHash)
        incoming-peer-chan (<<< #(.on (dht :dht) "peer" %))
        peers-found-chan (chan)]
    ; set up a listener for peers who match this infohash to return on the chan
    (go-loop [] (let [[peer infoHash-incoming from] (<! incoming-peer-chan)
                      peer (js->clj peer)
                      infoHash-incoming-text (if infoHash-incoming (.toString infoHash-incoming "hex") nil)
                      peer-host (peer "host")
                      peer-port (peer "port")]
                  ;(debug "Got peer:" peer-host peer-port infoHash-incoming-text (js->clj from))
                  (if (and
                        (= infoHash infoHash-incoming)
                        (put! peers-found-chan [[peer-host peer-port] infoHash-incoming from]))
                      (recur)
                      ; make sure the channel is closed and discontinue looping
                      (close! peers-found-chan))))
    ; perform the actual lookup
    (.lookup (dht :dht) infoHash)
    peers-found-chan))

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
    (<<< #(.put dht
                (clj->js put-params)
                %))))

