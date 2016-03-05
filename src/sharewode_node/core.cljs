(ns sharewode-node.core
  (:require [cljs.nodejs :as nodejs]))

(defonce DHT (nodejs/require "bittorrent-dht"))
(defonce Swarm (nodejs/require "bittorrent-swarm"))
(defonce fs (nodejs/require "fs"))
(defonce os (nodejs/require "os"))
(defonce crypto (nodejs/require "crypto"))
(defonce config-filename (str (os.homedir) "/.sharewode-node.json"))

(def client-string "-SW0001-")

; (def test-hash "fd4928492a15e77b3661e523a4b81f656cdc04d8")
;(def test-hash "b0282bf0571958845c22b01b9a7430d860018a11")
(def test-hash (or (get js/process.argv 2) "b0282bf0571958845c22b01b9a7430d860018a11"))

(nodejs/enable-util-print!)

; https://github.com/feross/webtorrent/blob/master/lib/torrent.js#L194
; https://github.com/feross/webtorrent/blob/master/lib/torrent.js#L563

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
  (println "test-hash:" test-hash)
  (let [configuration (atom (try (js->clj (js/JSON.parse (fs.readFileSync config-filename))) (catch js/Error e {})))
        nodeId (or (@configuration "nodeId") (.toString (.randomBytes crypto 20) "hex"))
        peerId (or (@configuration "peerId") (.toString (js/Buffer. (+ client-string (.randomBytes crypto 6))) "hex"))
        exit-fn (make-exit-fn configuration) 
        swarm (Swarm. test-hash peerId #js {"handshake" {"dht" true}})
        dht (DHT. {:nodeId nodeId})]
    
    (swap! configuration assoc-in ["nodeId"] nodeId)
    (swap! configuration assoc-in ["peerId"] peerId)

    (print "nodeId:" nodeId)
    (print "peerId:" peerId)

    (.on swarm "wire" (fn [wire]
                        (js/console.log "New wire:" (.-peerId wire))
                        (js/console.log "wires:" (.-length (.-wires swarm)))
                        (.on wire "close" (fn [] (print "Wire closed!") (print "wires:" (.-length (.-wires swarm)))))
                        (.on wire "keep-alive" (fn [] (print "Wire keepalive.")))))
    
    (.on swarm "listening" (fn []
                             (js/console.log "Swarm listening.")
                             (print "Swarm port:" (.-port (.address swarm)))))
    (.on swarm "error" (fn [] (js/console.log "Swarm error.")))
    (.on swarm "close" (fn [] (js/console.log "Swarm close.")))
    
    (.on swarm "connect" (fn [] (js/console.log "Swarm connect.")))
    
    (.on dht "ready"
         (fn []
           (print "DHT bootstrapped.")
           ;  {"peers" (.toArray dht)
           ;  "nodeId" (.toString (.-nodeId dht) "hex")}

           ; swarm.removePeer('127.0.0.1:42244') // remove a peer

           ; (.lookup dht test-hash (fn [err node-count] (print "DHT lookup node count:" node-count)))

           (.on dht "peer"
                (fn [peer infoHash from]
                  (print "on peer: " peer "infoHash:" (.toString infoHash "hex") "from:" from)
                  ; (.addPeer swarm (str (aget peer "host") ":6881"))
                  
                  ))

           ;(.announce dht test-hash (fn [error] (if error
           ;                                       (print "DHT announce error.")
           ;                                       (print "DHT announce success."))))

           ; (.on dht "node" (fn [node] (print "DHT node: "  (.toString (aget node "id") "hex") " " (aget node "host") ":" (aget node "port") " " (aget node "distance"))))
           ; (.on dht "warning" (fn [err] (print "DHT warning: " err)))
           (.on dht "announce" (fn [peer infoHash] (print "on announce (peer):" peer (.toString infoHash "hex"))))))

    (.listen dht (fn []
                   (print "DHT listening.")
                   (print "DHT port:" (.-port (.address dht)))))
    (.listen swarm)
    
    ; https://stackoverflow.com/a/14032965
    ; do something when app is closing
    (.on js/process "exit" (.bind exit-fn nil {:cleanup true}))
    ; catches ctrl+c event
    (.on js/process "SIGINT" (.bind exit-fn nil {:exit true}))
    ; catches uncaught exceptions
    (.on js/process "uncaughtException" (.bind exit-fn nil {:exit true}))))

(set! *main-cli-fn* -main)
