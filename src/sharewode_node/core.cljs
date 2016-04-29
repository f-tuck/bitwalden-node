(ns sharewode-node.core
  (:require [sharewode-node.utils :refer [<<<]]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(nodejs/enable-util-print!)

(def client-string "-SW0001-")
(def sharewode-dht-address "sharewode:v1:public-node")

; nodejs requirements
(defonce DHT (nodejs/require "bittorrent-dht"))
(defonce fs (nodejs/require "fs"))
(defonce os (nodejs/require "os"))
(defonce crypto (nodejs/require "crypto"))

; load the configuration file
(defonce config-filename (str (.homedir os) "/.sharewode-node.json"))

(defn make-exit-fn [configuration]
  (fn [options err]
    (if (:cleanup options) (print "Cleaning up."))
    (if err (.log js/console (.-stack err)))
    (when (:exit options) (print "Exiting") (.exit js/process))

    (fs.writeFileSync
      config-filename
      (js/JSON.stringify
        (clj->js @configuration))
      "utf8")))

(defn sha256 [x]
  (.digest (.update (.createHash crypto "sha256") x "utf8")))

(defn -main []
  (println "Sharewode node start.")
  (let [configuration (atom (try (js->clj (js/JSON.parse (fs.readFileSync config-filename))) (catch js/Error e {})))
        nodeId (or (@configuration "nodeId") (.toString (.randomBytes crypto 20) "hex"))
        peerId (or (@configuration "peerId") (.toString (js/Buffer. (+ client-string (.randomBytes crypto 6))) "hex"))
        exit-fn (make-exit-fn configuration) 
        dht (DHT. {:nodeId nodeId})]
    
    (swap! configuration assoc-in ["nodeId"] nodeId)
    (swap! configuration assoc-in ["peerId"] peerId)

    (print "nodeId:" nodeId)
    (print "peerId:" peerId)
    
    (go (<! (<<< #(.on dht "ready" %)))
        (print "DHT ready!")
        (print "DHT port:" (.-port (.address dht)))
        ; tell the world we're ready to accept connections
        (print "Announcing sharewode pool.")
        (let [error (<! (<<< #(.announce dht (sha256 sharewode-dht-address) 8000 %)))]
          (if error
            (print "DHT announce error.")
            (print "DHT announce success."))))
    
    (go (let [[peer infoHash from] (<! (<<< #(.on dht "peer" %)))]
        (print "Got peer:" peer (.toString infoHash "hex") from)))
    
    (go (let [[peer infoHash] (<! (<<< #(.on dht "announce" %)))]
          (print "Announce:" peer (.toString infoHash "hex"))))
    
    ; (.lookup dht test-hash (fn [err node-count] (print "DHT lookup node count:" node-count)))
    
    ; (.on dht "node" (fn [node] (print "DHT node: "  (.toString (aget node "id") "hex") " " (aget node "host") ":" (aget node "port") " " (aget node "distance"))))
    ; (.on dht "warning" (fn [err] (print "DHT warning: " err)))
    
    ;  {"peers" (.toArray dht)
    ;  "nodeId" (.toString (.-nodeId dht) "hex")}
    
    ; https://stackoverflow.com/a/14032965
    ; do something when app is closing
    (.on js/process "exit" (.bind exit-fn nil {:cleanup true}))
    ; catches ctrl+c event
    (.on js/process "SIGINT" (.bind exit-fn nil {:exit true}))
    ; catches uncaught exceptions
    (.on js/process "uncaughtException" (.bind exit-fn nil {:exit true}))))

(set! *main-cli-fn* -main)
