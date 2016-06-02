(ns sharewode-node.dht
  (:require [sharewode-node.utils :refer [<<< buffer]]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! timeout chan close! put!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(nodejs/enable-util-print!)

; nodejs requirements
(defonce debug ((nodejs/require "debug") "sharewode-node.dht"))
(defonce DHT (nodejs/require "bittorrent-dht"))

; API:
; receive-updates-on hash
; send-update-to hash
; get-node-list hash
; get-client-profile hash
; set-client-profile hash content
; get-torrent hash
; seed-torrent content

(defn make [configuration]
  (let [nodeId (@configuration "nodeId")
        peerId (@configuration "peerId")
        nodes (atom (or (@configuration "nodes") []))
        dht-obj (DHT. #js {:nodeId nodeId})
        dht {:dht dht-obj
             :port (or (@configuration "port") nil)
             :ready-chan (chan)}]
    
    ; listen for the "ready" event and update our config, return on the chan
    (go (<! (<<< #(.once dht-obj "ready" %)))
        (debug "DHT ready!")
        (debug "DHT port:" (.-port (.address dht-obj)))
        (swap! configuration assoc-in ["dht" "port"] (.-port (.address dht-obj)))
        (swap! configuration assoc-in ["dht" "nodes"] (.toJSON dht-obj))
        (close! (dht :ready-chan)))
    
    ; this has to be a regular callback - not core.async
    ; because of the k-rpc architecture being async unfriendly
    ; (binds immediately on a port if we don't do it here)
    (.listen dht-obj (dht :port) (fn [] (debug "listening")))
    
    ; add our previously known nodes to get the DHT ready faster
    (doseq [node @nodes]
      (when (and (node "host") (node "port"))
        ;(debug "Adding previously known node:" node)
        (.addNode dht-obj (clj->js node))))
    
    dht))

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

