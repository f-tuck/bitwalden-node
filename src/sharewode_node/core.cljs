(ns sharewode-node.core
  (:require [cljs.nodejs :as nodejs]))

(defonce DHT (nodejs/require "bittorrent-dht"))
(defonce fs (nodejs/require "fs"))
(defonce os (nodejs/require "os"))
(defonce crypto (nodejs/require "crypto"))
(defonce config-filename (str (os.homedir) "/.sharewode-node.json"))

(def client-string "-SW0001-")

; (def test-hash "fd4928492a15e77b3661e523a4b81f656cdc04d8")
(def test-hash "b0282bf0571958845c22b01b9a7430d860018a11")

(nodejs/enable-util-print!)

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

(defn -main []
  (println "Sharewode node start.")
  (let [configuration (atom (try (js->clj (js/JSON.parse (fs.readFileSync config-filename))) (catch js/Error e {})))
        nodeId (or (@configuration "nodeId") (.toString (.randomBytes crypto 20) "hex"))
        peerId (or (@configuration "peerId") (str client-string (.toString (.randomBytes crypto 6) "hex")))
        exit-fn (make-exit-fn configuration) 
        dht (DHT. {:nodeId nodeId})]
    
    (swap! configuration assoc-in ["nodeId"] nodeId)
    (swap! configuration assoc-in ["peerId"] peerId)

    (print "nodeId:" nodeId)
    (print "peerId:" peerId)

    (.on dht "ready"
         (fn []
           (print "DHT bootstrapped.")
           
           (.lookup dht test-hash (fn [err node-count] (print "Lookup: " node-count)))
           (.on dht "peer" (fn [peer infoHash from] (print "on peer: " peer "infoHash:" (.toString infoHash "hex") "from:" from)))

           (.on dht "announce" (fn [peer infoHash] (print "on announce (peer):" peer (.toString infoHash "hex"))))
           ; (.on dht "node" (fn [node] (print "DHT node: "  (.toString (aget node "id") "hex") " " (aget node "host") ":" (aget node "port") " " (aget node "distance"))))
           ; (.on dht "warning" (fn [err] (print "DHT warning: " err)))

           (js/setInterval
             (fn []
               (.announce dht test-hash (fn [error] (if error
                                                      (print "DHT announce error.")
                                                      (print "DHT announce success.")))))
             120000)))
    
    (doseq [node (aget @configuration "peers")]
      (print "adding:" node)
      ; (.addNode dht node)
      )
    
    (.listen dht (fn [] (print "DHT listening.")))

    ; https://stackoverflow.com/a/14032965
    ; do something when app is closing
    (.on js/process "exit" (.bind exit-fn nil {:cleanup true}))
    ; catches ctrl+c event
    (.on js/process "SIGINT" (.bind exit-fn nil {:exit true}))
    ; catches uncaught exceptions
    (.on js/process "uncaughtException" (.bind exit-fn nil {:exit true}))))

(set! *main-cli-fn* -main)
