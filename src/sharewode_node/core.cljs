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
     (get params "u"))

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

   :torrent-fetch
   (fn [params clients bt content-dir]
     (let [[k uid] (web/ids params)
           retrieval-chan (torrent/add bt (get params "infohash") content-dir)]
       (go
         (loop []
           (let [download-update (<! retrieval-chan)]
             (when download-update
               (swap! clients web/send-to-client k uid download-update)
               (if (not= (get download-update "download") "done")
                 (recur))))))
       (get params "u")))

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
             (recur))))

(set! *main-cli-fn* -main)
