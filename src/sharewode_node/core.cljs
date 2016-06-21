(ns sharewode-node.core
  (:require [sharewode-node.utils :refer [<<< sha1 to-json buf-hex timestamp-now]]
            [sharewode-node.config :as config]
            [sharewode-node.dht :as dht]
            [sharewode-node.bittorrent :as bittorrent]
            [sharewode-node.web :as web]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! put! timeout alts! close!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

; nodejs requirements
(.install (nodejs/require "source-map-support"))
(defonce debug ((nodejs/require "debug") "sharewode-node.core"))
(defonce crypto (nodejs/require "crypto"))

(def client-string "-SW0001-")
(def infoHash-sharewode-dht-address (sha1 "sharewode:v-alpha-1:public-node"))
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
        ;subscribed-hashes (atom {}) ; infoHash -> :last-announce timestamp :peer-ids [] :client-pubkeys []
        peer-candidates (atom {}) ; [infoHash host port] -> :timestamp t
        peers (atom {}) ; [infoHash peerId] -> :infoHash i :peerId p :host h :port p :from-peer chan :to-peer chan
        listeners (atom {}) ; infoHash -> [chans...]
        client-queues (atom {}) ; clientKey -> [{:timestamp ... :message ...} ...]
        test-clients (atom {})
        
        ; our service components
        dht (dht/make configuration)
        bittorrent (bittorrent/make configuration)
        web (web/make configuration)]
    
    (print "Sharewode server started.")
    (print "Bittorrent nodeId:" nodeId)
    (print "Bittorrent peerId:" peerId)
    (print "Bittorrent DHT port:" (dht :port))
    (print "Bittorrent port:" (bittorrent :port))
    (print "WebAPI port:" (web :port))
    
    ; when we exit we want to save the config
    (config/install-exit-handler configuration configfile)
    
    ; use-cases:
    ; * keeping a list of sharewode peers to share publically
    ; * gossip-sending some message to a given swarm
    ; * receiving messages from some given swarm by gossip
    
    ; * getting the profile of some pubkey + salt
    ; * getting the content of some infoHash
    ; * seeding the content of some infoHash
    
    ; swarm/make -> returns swarm-atom
    ; swarm/send-to swarm-atom infoHash message -> returns a chan, receives number of peers updated and closes
    ; swarm/receive-from swarm-atom infoHash -> returns a chan, recieves messages from the swarm - close chan to stop listening
    ; swarm/get-peer-list swarm-atom infoHash
    
    (defn receive-peer [peer]
      (print (peer :host) (peer :port) "receive-peer")
      (let [peer-tuple (map peer [:infoHash :peerId])]
        (if (nil? (@peers peer-tuple))
          (do
            (swap! peers assoc peer-tuple peer)
            (print "*** peer made it through handshake" peer)
            (print (count @peers) "peers connected"))
          (do
            (print "*** already have this peer - destroying" peer)
            (close! (peer :to-peer))
            (close! (peer :from-peer))))))
    
    ; loop that watches peer list

    ; wait for the DHT to become ready
    (go (<! (dht :ready-chan))
        (print "DHT ready.")
        
        ; tell the world we are available for connections and find some peers
        (let [[error result-code] (<! (dht/announce dht infoHash-sharewode-dht-address (bittorrent :port)))]
          (if error
            (debug "DHT announce error:" error)
            (debug "DHT announce success:" result-code)))
        
        ; lookup peers with the sharewode address
        (let [dht-peers-chan (dht/lookup dht infoHash-sharewode-dht-address)]
          (go
            (loop []
              (let [[[peer-host peer-port] infoHash-incoming from] (<! dht-peers-chan)
                    peer-lookup {:host peer-host :port peer-port :infoHash infoHash-incoming}]
                ; if the count is greater than 8 peers already, close the lookup channel
                ; ...
                (if (and
                  (= infoHash-sharewode-dht-address infoHash-incoming)
                  (nil? (@peer-candidates peer-lookup)))
                  (do
                    (swap! peer-candidates assoc peer-lookup {:timestamp (timestamp-now)})
                    (print peer-host peer-port (buf-hex infoHash-incoming) "found new peer from DHT lookup")
                    ; fork off and wait for the connection or for timeout
                    ; TODO check if it's my own listening torrentPort and ip and don't bother connecting
                    (go (let [peer (<! (bittorrent/connect-to-peer peer-host peer-port infoHash-incoming peerId))]
                          (if peer
                            (receive-peer peer)))))))
              (recur)))))
    
    ; periodically find old peer-candidates the DHT found and remove them
    
    ; handle any peers that come in from bittorrent connections
    (go-loop [] (let [peer (<! (bittorrent :received-peers-chan))]
                  (receive-peer peer)
                  (recur)))
    
    ; handle json-rpc web requests
    (go-loop [] (let [[call req result-chan res] (<! (web :requests-chan))]
                  (print "web client recv:" call)
                  (go
                    (cond
                      ; the "get-nodes" test call
                      (= call "ping") (if (put! result-chan [200 {:result "pong!"}]) (print "ping sent") (print "ping failed"))
                      ; the RPC call to return the current contents of the queue
                      ; if the queue is empty this will block until a value arrives in the queue or the client socket closes
                      (= call "get-queue") (when true ; (and (web/authenticate req) (web/pow-check req))
                                             (let [k (web/param req "k")
                                                   client (web/ensure-client-chan! client-queues k)]
                                               ; launch a test client
                                               (swap! test-clients update-in k web/make-test-client (client :chan))
                                               (let [r (<! (web/tap-client-chan (web/param req "after") (deref (client :queue)) (client :mult)))]
                                                 (print "about to send:" r)
                                                 (if (put! result-chan [200 r])
                                                   (print "top level: sent value")
                                                   (print "top level: send failed!"))))
                                             ;(web/snip-client-queue! client-queues req)
                                             )
                      :else (put! result-chan [404 false])))
                  (recur)))))

(set! *main-cli-fn* -main)
