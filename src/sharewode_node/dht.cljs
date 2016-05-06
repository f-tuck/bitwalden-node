(ns sharewode-node.dht
  (:require [sharewode-node.utils :refer [<<<]]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! timeout]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(nodejs/enable-util-print!)

; nodejs requirements
(defonce debug ((nodejs/require "debug") "sharewode-node.dht"))
(defonce DHT (nodejs/require "bittorrent-dht"))
(defonce crypto (nodejs/require "crypto"))

(defn sha1 [x]
  (.digest (.update (.createHash crypto "sha1") x "utf8")))

(def sharewode-dht-address (or (get js/process.argv 2) (sha1 "sharewode:v-alpha-1:public-node")))

(defn add-peer! [dht infoHash peer]
  (let [peer-struct (js->clj peer)
        infohash-string (.toString infoHash "hex")]
    (swap! (dht :peers) update-in [infohash-string]
           (fn [old-value]
             (if old-value
               (conj old-value peer-struct)
               #{peer-struct})))
    (debug "peers for" infohash-string (clj->js @(dht :peers)))))

(defn make-dht! [configuration]
  (let [nodeId (@configuration "nodeId")
        peerId (@configuration "peerId")
        nodes (atom (or (@configuration "nodes") []))
        dht-obj (DHT. #js {:nodeId nodeId})
        dht {:dht dht-obj
             :port (or (@configuration "port") nil)
             :peers (atom {})}]
    
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

(defn install-listeners! [dht configuration]
  (let [dht-obj (dht :dht)]
    (go (<! (<<< #(.once dht-obj "ready" %)))
        (debug "DHT ready!")
        (debug "DHT port:" (.-port (.address dht-obj)))
        (swap! configuration assoc-in ["dht" "port"] (.-port (.address dht-obj)))
        ; TODO - save the port and re-use it next time
        ; tell the world we're ready to accept connections
        ;(js/console.log "DHT state" (.toJSON dht))
        (swap! configuration assoc-in ["dht" "nodes"] (.toJSON dht-obj))
        (debug "Announcing sharewode pool.")
        (let [[error result-code] (<! (<<< #(.announce dht-obj sharewode-dht-address 8923 %)))]
          (if error
            (debug "DHT announce error:" error)
            (debug "DHT announce success:" result-code))))

    (let [node-chan (<<< #(.on dht-obj "node" %))
          peer-chan (<<< #(.on dht-obj "peer" %))
          announce-chan (<<< #(.on dht-obj "announce" %))
          error-chan (<<< #(.on dht-obj "error" %))
          warning-chan (<<< #(.on dht-obj "warning" %))]

      (go-loop [] (let [[node] (<! node-chan)]
                    ;(js/console.log "Got node:" node)
                    (swap! configuration assoc-in ["dht" "nodes"] (.toJSON dht-obj))
                    (recur)))

      (go-loop [] (let [[peer infoHash from] (<! peer-chan)]
                    ; (debug "Got peer:" (.toString peer) (if infoHash (.toString infoHash "hex") "infoHash?") (js->clj from))
                    (add-peer! dht infoHash peer)
                    (recur)))

      (go-loop [] (let [[peer infoHash] (<! announce-chan)]
                    (debug "Announce:" peer (if infoHash (.toString infoHash "hex") "infoHash?"))
                    (recur)))

      (go-loop [] (let [[err] (<! error-chan)]
                    (debug "DHT error:" err)
                    (recur)))

      (go-loop [] (let [[err] (<! warning-chan)]
                    (debug "DHT warning:" err)
                    (recur))))))
