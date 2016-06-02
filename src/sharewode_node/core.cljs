(ns sharewode-node.core
  (:require [sharewode-node.utils :refer [<<< sha1 to-json buf-hex]]
            [sharewode-node.config :as config]
            [sharewode-node.dht :as dht]
            ;[sharewode-node.bt :as bt]
            [sharewode-node.web :as web]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! put! timeout]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

; nodejs requirements
(.install (nodejs/require "source-map-support"))
(defonce debug ((nodejs/require "debug") "sharewode-node.core"))
(defonce crypto (nodejs/require "crypto"))

(def client-string "-SW0001-")
(def sharewode-dht-address (sha1 "sharewode:v-alpha-1:public-node"))
(def default-profile-name "sharewode.profile")

(nodejs/enable-util-print!)

;*** web API - unauthenticated ***;
  
(defn get-nodes
  "Returns a list of 8 randomly selected known Sharewode nodes.
  Immediate return. Unauthenticated."
  [])

(defn difficulty
  "Returns this node's current difficulty (hashrate).
  Immediate return. Unauthenticated."
  [])

(defn get-queue
  "Returns the messages for the client.
  Immediate return. Unauthenticated."
  [])

;*** web API - authenticated ***;

(defn register
  "Register a pubkey with this node.
  Immediate return."
  [pubkey pow])

(defn get-profile
  "Request the profile state of some pubkey.
  Pending return."
  [pubkey pow target-pubkey & {:keys [name-salt] :or [name-salt default-profile-name]}])

(defn subscribe-to-profile
  "Ask the node to subscribe to a pubkey and send us updates.
  Immediate return. (updates come on pending return)."
  [pubkey pow target-pubkey & {:keys [name-salt] :or [name-salt default-profile-name]}])

(defn set-profile
  "Set some profile state for some pubkey to some content. Keep fresh for n hours.
  Immediate return."
  [pubkey pow content & {:keys [hours name-salt] :or [hours 1 name-salt default-profile-name]}])

(defn get-content
  "Get the content of some torrent by infoHash.
  Pending return."
  [pubkey pow info-hash])

(defn seed-content
  "Seed some content to the bittorrent network for n hours.
  Pending return."
  [pubkey pow content-size content & {:keys [hours] :or [hours 1]}])

;*** bittorrent API ***;

(defn notify
  "Notify this Sharewode node of an updated profile. Unauthenticated"
  [target-pubkey seqnum sig & {:keys [name-salt] :or [name-salt "sharewode.profile"]}])

;*** entry point ***;

(defn -main []
  (let [configfile (config/default-config-filename)
        configuration (config/load configfile)
        nodeId (config/get-or-set! configuration "nodeId" (.toString (.randomBytes crypto 20) "hex"))
        peerId (config/get-or-set! configuration "peerId" (.toString (js/Buffer. (+ client-string (.randomBytes crypto 6))) "hex"))
        ; data structures
        announced-hashes-map (atom {}) ; infoHash -> :last timestamp
        
        ; our service components
        dht (dht/make configuration)
        ;bt (bt/make configuration)
        web (web/make configuration)]
    
    (print "Sharewode server started.")
    (print "Bittorrent nodeId:" nodeId)
    (print "Bittorrent peerId:" peerId)
    (print "Bittorrent DHT port:" (dht :port))
    ;(print "Bittorrent port:" (bt :port))
    (print "WebAPI port:" (web :port))
    
    ; when we exit we want to save the config
    (config/install-exit-handler configuration configfile)
    
    ; wait for the DHT to become ready
    (go (<! (dht :ready-chan))
        (print "DHT ready.")
        ; tell the world we are available for connections and find some peers
        (let [[error result-code] (<! (dht/announce dht sharewode-dht-address (web :port)))]
          (if error
            (debug "DHT announce error:" error)
            (debug "DHT announce success:" result-code)))
        ; lookup peers with the sharewode address
        (let [sharewode-peers-chan (dht/lookup dht sharewode-dht-address)]
          (go-loop []
                   (let [[peer infoHash from] (<! sharewode-peers-chan)]
                     (print "Found peer: " (buf-hex infoHash) peer))
                   (recur))))
    
    ; handle json-rpc web requests
    (go-loop [] (let [[call req result-chan res] (<! (web :clients-chan))]
                  (print "web client recv:" call)
                  (cond
                    (= call "get-nodes") (put! result-chan [200 {:result "good"}])
                    :else (put! result-chan [404 false]))
                  (recur)))
    
    ; handle bittorrent peer requests
    ))

(set! *main-cli-fn* -main)
