(ns bitwalden-node.core
  (:require [bitwalden-node.utils :refer [<<< sha1 to-json buf-hex timestamp-now]]
            [bitwalden-node.config :as config]
            [bitwalden-node.dht :as dht]
            [bitwalden-node.torrent :as torrent]
            [bitwalden-node.web :as web]
            [bitwalden-node.pool :as pool]
            [bitwalden-node.constants :as const]
            [bitwalden-node.contracts :as contracts]
            [bitwalden-node.validation :as validation]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! put! chan timeout alts! close!]]
            ["webtorrent" :as wt]
            ["debug/node" :as debug-fn])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

; nodejs requirements
; (.install source-map-support)
(defonce debug (debug-fn "bitwalden-node.core"))
(defonce crypto (nodejs/require "crypto"))

(nodejs/enable-util-print!)

; reloadable code hack
(defonce api-atom (atom {}))
(defonce web-api-atom (atom {}))

; JSON-RPC API

(def api
  {:ping
   (fn [params]
     (merge {:pong true} params))

   :authenticate
   (fn [params]
     (or
       (web/authenticate params)
       true))

   :client-test
   (fn [params clients]
     (or (web/authenticate params)
         (validation/check params
                           {"u" [:exists? :string? :max1k?]
                            "p" [:exists? :string? :max1k?]})
         (do
           ; wait 3 seconds and pass a value via client queue
           (go (<! (timeout 3000))
               (let [k (params "k")
                     uid (params "u")]
                 (swap! clients web/send-to-client k uid (params "p"))))
           ; immediately ACK
           (params "u"))))

   :dht-get
   (fn [params clients bt]
     (or (web/authenticate params)
         (validation/check params
                           {"addresshash" [:exists? :hex-sha1?]
                            "salt" [:exists? :string? :max1k?]})
         (dht/get-value (.. bt -dht) (params "addresshash") (params "salt"))))

   :dht-put
   (fn [params clients bt]
     (or (web/authenticate params)
         (validation/check params
                           {"v" [:exists? :string? :max1k?]
                            "seq" [:exists? :int?]
                            "salt" [:exists? :string? :max1k?]
                            "s.dht" [:exists? :hex-signature?]})
         (go (let [result (<! (dht/put-value
                                (.. bt -dht)
                                (params "v")
                                (params "k")
                                (params "salt")
                                (params "seq")
                                (params "s.dht")))]
               ; queue this dht-put contract up for refresh
               (if (and (not (result "error")) (result "addresshash"))
                 (swap! clients contracts/dht-add (timestamp-now) params))
               result))))

   :torrent-seed
   (fn [params clients bt content-dir]
     (or (web/authenticate params)
         (validation/check params
                           {"name" [:exists? :string?]
                            "content" [:exists? :string?]})
         (torrent/seed bt
                       (params "name")
                       (params "content")
                       content-dir)))

   :torrent-fetch
   (fn [params clients bt content-dir]
     (or (web/authenticate params)
         (validation/check params
                           {"u" [:exists? :string? :max1k?]
                            "infohash" [:exists? :hex-sha1?]})
         (let [k (params "k")
               uid (params "u")
               retrieval-chan (torrent/add bt (params "infohash") content-dir)]
           (go
             (loop []
               (let [download-update (<! retrieval-chan)]
                 (when download-update
                   (swap! clients web/send-to-client k uid download-update)
                   (if (not= (download-update "download") "done")
                     (recur))))))
           (get params "u"))))

   :channel-send
   (fn [params clients bt content-dir])

   :channel-listen
   (fn [params clients bt content-dir])

   :channel-respond
   (fn [params clients bt content-dir])

   :get-queue
   (fn [params clients]
     (or (web/authenticate params)
         (validation/check params
                           {"u" [:exists? :string? :max1k?]})
         (go (let [k (params "k")
                   uid (params "u")
                   c (chan)
                   channel-timeout (or (params "timeout") const/channel-timeout-max)]
               (swap! clients web/add-queue-listener k uid c (params "after"))
               ; timeout and close the chan after 5 minutes max to clean up
               (go (<! (timeout (js/Math.min channel-timeout const/channel-timeout-max)))
                   (close! c))
               (let [response (<! c)]
                 (swap! clients web/remove-queue-listener k uid c)
                 ; return valid empty queue if there was no response
                 (or response []))))))})

; hack for reloadable code
(reset! api-atom api)

; JSON API

(def web-api
  {"/" (fn [] true)
   "/bw/info" (fn [] {:bitwalden const/version})
   "/bw/peers" (fn [public-peers] @public-peers)})

; hack for reloadable code
(reset! web-api-atom web-api)

; long running threads

(defn run-dht-contracts [bt clients]
  (go
    (let [contracts-to-refresh (contracts/dht-get-due @clients)
          updated-contracts (<! (contracts/dht-refresh-data bt contracts-to-refresh))]
      (when (> (count updated-contracts) 0)
        (debug "Updated" (count updated-contracts) "contracts.")
        (doall (for [[k salt updated remaining] updated-contracts]
                 (swap! clients contracts/dht-add (timestamp-now) updated remaining)))))))

; core app data structure

(def clients-struct
  {:queues {} ; clientKey -> uuid = timestamped-messages
   :contracts {} ; clientKey -> uuid = contract-details
   ; not persisted:
   :listeners {}}) ; clientKey -> uuid = chan

(defonce clients
  (atom clients-struct))

;*** entry point ***;

(defn -main []
  (let [queues-file (config/make-filename "queues.json")
        contracts-file (config/make-filename "contracts.json")
        config-file (config/make-filename "node-config.json")
        configuration (atom (config/load-to-clj config-file))
        downloads-dir (config/ensure-dir (or (@configuration "downloads-dir") (config/make-filename "downloads")))
        log-dir (config/ensure-dir (or (@configuration "log-dir") (config/make-filename "log")))
        peerId (str (.toString (js/Buffer. const/client-string) "hex") (.toString (js/Buffer. (.randomBytes crypto 12)) "hex"))
        ; data structures
        public-peers (atom {}) ; list of URLs of known friends
        ; service components
        bt (wt. {:peerId peerId :path downloads-dir})
        web (web/make downloads-dir log-dir api-atom web-api-atom bt clients public-peers)
        public-url (if (not (@configuration :private)) (or (@configuration :URL) (str ":" (web :port))))
        node-pool (pool/connect bt const/public-pool-name public-url public-peers)]
    ; load our persisted datastructures
    (swap! clients assoc
           :queues (config/load-to-clj queues-file)
           :contracts (config/load-to-clj contracts-file))
    
    ; when we exit we want to save datastructures we want to persist
    (config/install-exit-handler
      [[queues-file clients [:queues]]
       [contracts-file clients [:contracts]]])
    
    ; thread that runs every minute and updates refresher contracts
    (go-loop []
             ; TODO: rather than run every minute schedule based on queue contents
             (<! (timeout 1000))
             (<! (run-dht-contracts bt clients))
             (recur))

    ; thread that runs every minute and flushes old message queues
    (go-loop []
             (<! (timeout 1000))
             ;(debug "Flushing client queues.")
             ; TODO: this.
             (recur))

    (print "Bitwalden server node started.")
    (print "Bittorrent nodeId:" (.. bt -nodeId))
    (print "Bittorrent peerId:" (.. bt -peerId))
    (print "WebAPI port:" (web :port))
    (print "Downloads dir:" downloads-dir)))

(set! *main-cli-fn* -main)
