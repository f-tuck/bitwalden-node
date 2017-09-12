(ns sharewode-node.core
  (:require [sharewode-node.utils :refer [<<< sha1 to-json buf-hex timestamp-now ensure-downloads-dir]]
            [sharewode-node.config :as config]
            [sharewode-node.dht :as dht]
            [sharewode-node.torrent :as torrent]
            [sharewode-node.web :as web]
            [sharewode-node.pool :as pool]
            [sharewode-node.constants :as const]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! put! chan timeout alts! close!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

; nodejs requirements
(.install (nodejs/require "source-map-support"))
(defonce debug ((nodejs/require "debug") "sharewode-node.core"))
(defonce crypto (nodejs/require "crypto"))
(defonce wt (nodejs/require "webtorrent"))

(nodejs/enable-util-print!)

(defonce api-atom (atom {}))

(def api
  {:ping
   (fn [params]
     (merge {:pong true} params))

   :authenticate
   (fn [params]
     (web/authenticate params))

   :client-test
   (fn [params clients]
     ; wait 3 seconds and pass a value via client queue
     (go (<! (timeout 3000))
         (let [[k uid] (web/ids params)]
           (swap! clients web/send-to-client k uid (get params "p"))))
     ; immediately ACK
     true)

   :dht-get
   (fn [params clients bt]
     (dht/get-value (.. bt -dht) (get params "infohash")))

   :dht-put
   (fn [params clients bt]
     ; TODO: contract to repeatedly update this
     (dht/put-value
       (.. bt -dht)
       (get params "v")
       (get params "k")
       (get params "salt")
       (get params "seq")
       (get params "s.dht")))

   :torrent-seed
   (fn [params clients bt content-dir]
     (torrent/seed bt
                   (get params "name")
                   (get params "content")
                   content-dir))

   :get-queue
   (fn [params clients]
     (go (let [[k uid] (web/ids params)
               c (chan)
               channel-timeout (or (get params "timeout") const/channel-timeout-max)]
           (swap! clients web/add-queue-listener k uid c (get params "after"))
           ; timeout and close the chan after 5 minutes max to clean up
           (go (<! (timeout (js/Math.min channel-timeout const/channel-timeout-max)))
               (close! c))
           (let [response (<! c)]
             ; return valid empty queue if there was no response
             (or response [])))))})

; hack for reloadable code
(reset! api-atom api)

(def clients-struct
  {:queues {} ; clientKey -> uuid = timestamped-messages
   :contracts {} ; infoHash -> clientKey -> uuid = contract-details
   :listeners {}}) ; clientKey -> uuid = chan

(defonce clients
  (atom clients-struct))

;*** entry point ***;

(defn -main []
  (let [configfile (config/default-config-filename)
        configuration (atom (config/load-to-clj configfile))
        downloads-dir (ensure-downloads-dir)
        peerId (str (.toString (js/Buffer. const/client-string) "hex") (.toString (js/Buffer. (.randomBytes crypto 12)) "hex"))
        ; data structures
        client-queues (atom {}) 
        contracts (atom {}) 
        client-listeners (atom []) 
        public-peers (atom {}) ; list of URLs of known friends
        ; service components
        bt (wt. {:peerId peerId :path downloads-dir})
        web (web/make configuration api-atom bt clients downloads-dir public-peers)
        public-url (if (not (@configuration :private)) (or (@configuration :URL) (str ":" (web :port))))
        node-pool (pool/connect bt const/public-pool-name public-url public-peers)]
    
    (print "Sharewode server started.")
    (print "Bittorrent nodeId:" (.. bt -nodeId))
    (print "Bittorrent peerId:" (.. bt -peerId))
    (print "WebAPI port:" (web :port))
    (print "Downloads dir:" downloads-dir)
    
    ; when we exit we want to save the config
    (config/install-exit-handler configuration configfile)
    
    ; thread that runs every second and flushes old messages and clients
    (go-loop []
             (<! (timeout 1000))
             ;(debug "Flushing client queues.")
             ; TODO: this.
             (recur))
    
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
                      ; TODO: request POW HMAC token
                      ; TODO: send message to a particular hash
                      ; TODO: collect on a particular hash
                      ; TODO: set responder on a particular hash
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
                                                                      (get params "content")
                                                                      downloads-dir))))
                                          (put! result-chan [200 true]))
                                        (put! result-chan [403 false]))
                      ; DHT put (BEP 0044)
                      (= call "dht-put") (if (web/authenticate params)
                                           (let [pkey (get params "k")
                                                 uuid (get params "u")
                                                 client (web/ensure-client-chan! client-queues uuid pkey)]
                                             ; TODO: contract to repeatedly update this 
                                             (go (put! (client :chan-to-client)
                                                       (<! (dht/put-value (.. bt -dht)
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
                                                       (<! (dht/get-value (.. bt -dht) (get params "infohash")))))
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
