(ns sharewode-node.core
  (:require [sharewode-node.utils :refer [<<< sha1 to-json buf-hex timestamp-now]]
            [sharewode-node.config :as config]
            [sharewode-node.dht :as dht :refer [put-value get-value]]
            [sharewode-node.torrent :as torrent]
            [sharewode-node.web :as web]
            [sharewode-node.pool :as pool]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! put! timeout alts! close!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

; nodejs requirements
(.install (nodejs/require "source-map-support"))
(defonce debug ((nodejs/require "debug") "sharewode-node.core"))
(defonce crypto (nodejs/require "crypto"))
(defonce bencode (nodejs/require "bencode"))

(def client-string "-SW0001-")
(def sharewode-swarm-identifier "sharewode:v-alpha-2:public-node")
(def infoHash-sharewode-dht-address (sha1 sharewode-swarm-identifier))
(def default-profile-name "sharewode.profile")

(def ideal-peer-count 8)
(def re-announce-frequency-hours 1)
(def peer-gather-timeout 300000)

(nodejs/enable-util-print!)

;*** entry point ***;

(defn -main []
  (let [configfile (config/default-config-filename)
        configuration (config/load configfile)
        nodeId (config/get-or-set! configuration "nodeId" (.toString (.randomBytes crypto 20) "hex"))
        peerId (config/get-or-set! configuration "peerId" (.toString (js/Buffer. (+ client-string (.randomBytes crypto 6))) "hex"))
        torrentPort (config/get-or-set! configuration "torrentPort" 6881)

        ; data structures
        ;swarms (atom {}) ; infoHash -> :last-announce timestamp :peer-candidate-ids [] :peer-ids [] :client-pubkeys []
        peer-candidates (atom {}) ; [infoHash host port] -> :timestamp t
        peers (atom {}) ; [infoHash peerId] -> :infoHash i :peerId p :host h :port p :from-peer chan :to-peer chan
        listeners (atom {}) ; infoHash -> [chans...]
        client-queues (atom {}) ; clientKey -> [{:timestamp ... :message ...} ...]
        test-clients (atom {})
        public-peers (atom {})
        
        ; our service components
        bt (torrent/make-client)
        dht (bt :dht)
        ; TODO: this arg shouldn't be hardcoded
        web (web/make configuration "/tmp/webtorrent" public-peers)
        
        public-url (if (not (@configuration :private)) (or (@configuration :URL) (str ":" (web :port))))
        
        ; make sure we have a downloads dir
        downloads-dir (ensure-downloads-dir)
        
        node-pool (pool/connect (bt :client) sharewode-swarm-identifier public-url public-peers)]

    (print "Sharewode server started.")
    (print "Bittorrent nodeId:" nodeId)
    (print "Bittorrent peerId:" peerId)
    ;(print "Bittorrent DHT port:" (dht :port))
    ;(print "Bittorrent port:" (bittorrent :port))
    (print "WebAPI port:" (web :port))
    (print "Downloads dir:" downloads-dir)
    
    ; when we exit we want to save the config
    (config/install-exit-handler configuration configfile)

    ; use-cases:
    ; * keeping a list of sharewode peers to share publically
    ; * gossip-sending some message to a given swarm
    ; * receiving messages from some given swarm by gossipi

    ; * setting the profile of some pubkey + salt + sig
    ; * getting the profile of some pubkey + salt
    ; * getting the content of some infoHash
    ; * seeding the content of some infoHash

    ; swarm/make -> returns swarm-atom
    ; swarm/send-to swarm-atom infoHash message -> returns a chan, receives number of peers updated and closes
    ; swarm/receive-from swarm-atom infoHash -> returns a chan, recieves messages from the swarm - close chan to stop listening
    ; swarm/get-peer-list swarm-atom infoHash

    ; handle any messages that come in from bittorrent swarms

    (go-loop [] (let [[infoHash peerId addr buf] (<! (bt :channel-receive))]
                  (let [decoded (.decode bencode buf "utf8")]
                    (print infoHash peerId addr "*** <<< sw_gossip message:")
                    (js/console.log "\t" decoded))
                  (recur)))

    (go
      (let [sharewode-infohash (<! (torrent/join bt sharewode-swarm-identifier))]
        (print "sharewode-infohash:" sharewode-infohash)
        (loop []
          (<! (timeout (* (js/Math.random) 5000)))
          (if (> (count (get (deref (bt :channels-send)) sharewode-infohash)) 0)
            (let [v (str (js/Math.random))]
              (js/console.log "sending:" v)
              (torrent/send-to-swarm bt sharewode-infohash v)))
          (recur))))

    ; handle json-rpc web requests
    (go-loop [] (let [[call params req res result-chan res] (<! (web :requests-chan))]
                  (print "web client recv:" call)
                  (go
                    (cond
                      ; simple test to see if the web interface is up
                      (= call "ping") (put! result-chan [200 "pong"])
                      ; test whether a particular signature verifies with supercop
                      (= call "authenticate") (put! result-chan [200 (web/authenticate params)])
                      ; TODO: ask for POW height details
                      ; client test rig
                      (= call "client-test") (if (web/authenticate params) ;(and (web/authenticate req) (web/pow-check req))
                                               (let [pkey (get params "k")
                                                     uuid (get params "u")
                                                     client (web/ensure-client-chan! client-queues uuid pkey)]
                                                 ; launch a test client
                                                 ; (swap! test-clients update-in k web/make-test-client (client :chan))
                                                 (go (<! (timeout 5000))
                                                     (put! (client :chan-to-client) (get params "p")))
                                                 (put! result-chan [200 true]))
                                               (put! result-chan [403 false]))
                      ; Download a blob from bittorrent
                      (= call "retrieve") (if (web/authenticate params)
                                            (let [pkey (get params "k")
                                                  uuid (get params "u")
                                                  client (web/ensure-client-chan! client-queues uuid pkey)
                                                  retrieval-chan (torrent/add bt (get params "infohash") downloads-dir)]
                                              (go
                                                (loop []
                                                  (let [download-update (<! retrieval-chan)]
                                                    (when download-update
                                                      (put! (client :chan-to-client)
                                                            download-update)
                                                      (if (not= (get download-update "download") "done")
                                                        (recur))))))
                                              (put! result-chan [200 true]))
                                            (put! result-chan [403 false]))
                      ; Seed a blob in bittorrent
                      (= call "seed") (if (web/authenticate params)
                                        (let [pkey (get params "k")
                                              uuid (get params "u")
                                              client (web/ensure-client-chan! client-queues uuid pkey)]
                                          (go (put! (client :chan-to-client)
                                                    (<! (torrent/seed bt
                                                                      (get params "name")
                                                                      (get params "content")))))
                                          (put! result-chan [200 true]))
                                        (put! result-chan [403 false]))
                      ; TODO: append a new value to a namespace hash
                      ; TODO: request values from a namespace hash
                      ; DHT put (BEP 0044)
                      (= call "dht-put") (if (web/authenticate params)
                                           (let [pkey (get params "k")
                                                 uuid (get params "u")
                                                 client (web/ensure-client-chan! client-queues uuid pkey)]
                                             (go (put! (client :chan-to-client)
                                                       (<! (put-value dht
                                                                      (get params "v")
                                                                      (get params "k")
                                                                      (get params "salt")
                                                                      (get params "seq")
                                                                      (get params "s.dht")))))
                                             (put! result-chan [200 true]))
                                           (put! result-chan [403 false]))
                      ; do a dht get (BEP 0044)
                      (= call "dht-get") (if (web/authenticate params)
                                           (let [pkey (get params "k")
                                                 uuid (get params "u")
                                                 client (web/ensure-client-chan! client-queues uuid pkey)]
                                             (go (put! (client :chan-to-client)
                                                       (<! (get-value dht (get params "infohash")))))
                                             (put! result-chan [200 true]))
                                           (put! result-chan [403 false]))
                      ; the RPC call to return the current contents of the queue
                      ; if the queue is empty this will block until a value arrives in the queue or the client socket closes
                      (= call "get-queue") (if (web/authenticate params)
                                             (let [pkey (get params "k")
                                                   uuid (get params "u")
                                                   client (web/ensure-client-chan! client-queues uuid pkey)]
                                               (let [c (web/client-chan-listen! (get params "after") client)
                                                     r (<! c)]
                                                 ;(print "about to send:" r)
                                                 (if (put! result-chan [200 r])
                                                   (print "top level: sent value")
                                                   (print "top level: send failed!"))))
                                             (put! result-chan [403 false]))
                      :else (put! result-chan [404 false]))))
             (recur))))

(set! *main-cli-fn* -main)
