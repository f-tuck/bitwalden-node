(ns sharewode-node.json-rpc
  (:require [cljs.nodejs :as nodejs]))

(defonce j (nodejs/require "jayson"))
(def port 8923)

;*** RPC calls ***/

(defn get-nodes
  "Returns a list of known Sharewode nodes. Unauthenticated."
  [])

(defn difficulty
  "Returns this node's current difficulty (hashrate). Unauthenticated."
  []
  [nil 10])

(defn register
  "Register a pubkey with this node."
  [pubkey pow]
  [nil [true pubkey pow]])

(defn get-state
  "Request the profile state of some pubkey."
  [pubkey pow target-pubkey name-salt])

(defn set-state
  "Set some profile state for some pubkey to some content."
  [pubkey pow name-salt content])

(defn get-content
  "Get the content of some torrent by infoHash."
  [pubkey pow info-hash])

(defn seed-content
  "Seed some content to the bittorrent network."
  [pubkey pow content])

;*** Hook up server ***;

(defn make []
  (let [json-rpc-server
        (.server j
                 (clj->js (into {} (for 
                                     [[n f] {'get-nodes get-nodes
                                             'difficulty difficulty
                                             'register register
                                             'get-state get-state
                                             'set-state set-state
                                             'get-content get-content
                                             'seed-content seed-content}]
                                       [n (fn [args callback]
                                                   (print "JSON RPC call:" n args)
                                                   (if (nil? (.-length args))
                                                     (callback true)
                                                     (apply callback (clj->js (apply f args)))))]))))]
    {:server json-rpc-server :port port}))

(defn listen [s]
  (.listen
    (.http (s :server))
    (s :port)))

