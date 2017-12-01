(ns bitwalden-node.web
  (:require [bitwalden-node.utils :refer [<<< to-json buf-hex timestamp-now pr-thru]]
            [bitwalden-node.constants :as const]
            [bitwalden-node.config :as config]
            [bitwalden-node.validation :as validation]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! put! timeout chan sliding-buffer close! mult tap untap]]
            ["body-parser" :as body-parser]
            ["jayson" :as jayson]
            ["morgan" :as morgan]
            ["rotating-file-stream" :as rotating-file-stream]
            ["express" :as express]
            ["url" :as url]
            ["tweetnacl" :as nacl]
            ["bs58" :as bs58]
            ["bencode/lib" :as bencode]
            ["debug/node" :as debug-fn])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(nodejs/enable-util-print!)

; nodejs requirements
(defonce crypto (nodejs/require "crypto"))
(defonce debug (debug-fn "bitwalden-node.web"))

(defn write-header [res code & [headers]]
  (.writeHead res code (clj->js (merge {"Content-Type" "application/json"} headers))))

(defn jsonrpc-router [api-atom clients bt content-dir method params]
  (let [api-call (@api-atom (keyword method))]
    (if api-call
      (fn [params callback]
        (go
          (debug "JSON RPC call:" method (clj->js params))
          (let [result (api-call (js->clj params) clients bt content-dir method)
                result (if (implements? cljs.core.async.impl.protocols/Channel result) (<! result) result)]
            (callback nil (clj->js result))))))))

(defn make-json-rpc-server [api clients bt content-dir]
  (.middleware (.server jayson #js {} #js {:router (partial jsonrpc-router api clients bt content-dir)})))

(defn make [content-dir log-dir api-atom web-api-atom bt clients public-peers]
  (let [app (express)
        requests-chan (chan)]

    ; logging
    (.use app (morgan "combined"
                      #js {:stream
                           (rotating-file-stream
                             "access.log"
                             #js {:interval "1d" :path log-dir})}))

    ; allows cross site requests
    (.use app (fn [req res n]
                (.header res "Access-Control-Allow-Origin" "*")
                (.header res "Access-Control-Allow-Headers" "Origin, X-Requested-With, Content-Type, Accept")
                (n)))

    ; allow url encoded requests
    (.use app (.urlencoded body-parser #js {:extended true}))

    ; statically serve content (downloaded torrents) dir
    (.use app "/bw/content" ((aget express "static") content-dir))

    ; hook up the JSON RPC API
    (.post app "/bw/rpc" (.json body-parser #js {:limit "1mb" :type "*/*" }) (make-json-rpc-server api-atom clients bt content-dir))
    
    ; hook up the remaining JSON API calls
    (.use app (fn [req res cb]
                (let [path (.. req -path)
                      call (or (get @web-api-atom path) (get @web-api-atom (str path "/")))]
                  (if (and call (or (= (.. req -method) "GET") (= (.. req -method) "HEAD")))
                    (do
                      (write-header res 200)
                      (.end res (to-json (call public-peers))))
                    (cb)))))
    
    ; serve
    (.listen app const/web-api-port)

    {:server app :port const/web-api-port :requests-chan requests-chan}))

(defn get-pending-messages [q after]
  (vec (filter #(> (% "timestamp") after) q)))

(defn remove-listeners [listeners to-remove]
  (remove #(contains? (set to-remove) %) listeners))

(defn send-to [listeners packet]
  (doall (map #(put! % [packet]) listeners)))

(defn truncate-messages [clients k uid messages]
  (if (> (count messages) 0)
    (assoc-in clients [:queues k uid] messages)
    (let [clients (update-in clients [:queues k] dissoc uid)]
      (if (= (count (get-in clients [:queues k])) 0)
        (update-in clients [:queues] dissoc k)
        clients))))

(defn add-queue-listener [clients k uid c after]
  (let [pending (get-pending-messages (get-in clients [:queues k uid]) after)
        clients (truncate-messages clients k uid pending)]
    ; if we have pending messages already send directly
    (if (> (count pending) 0)
      (do (put! c pending)
          (close! c)
          (assoc-in clients [:listeners k uid] nil) 
          clients)
      ; add the listener
      (update-in clients [:listeners k uid] conj c))))

(defn remove-queue-listener [clients k uid c]
  (let [channels (get-in clients [:listeners k uid])
        clients (if (> (count channels) 1)
                  (update-in clients
                             [:listeners k uid]
                             (fn [channels]
                               (remove #(= % c) channels)))
                  (update-in clients
                             [:listeners k]
                             dissoc uid))]
    (if (> (count (get-in clients [:listeners k])) 0)
      clients
      (update-in clients [:listeners] dissoc k))))

(defn send-to-client [clients k uid payload]
  (let [packet {"payload" payload "timestamp" (timestamp-now)}
        listeners (get-in clients [:listeners k uid])]
    (if (> (count listeners) 0)
      ; if we have listeners send directly
      (do
        (send-to listeners packet)
        (doall (map #(close! %) listeners))
        (assoc-in clients [:listeners k uid] nil))
      ; otherwise queue this packet up
      (update-in clients [:queues k uid] (fnil conj []) packet))))

(defn authenticate [params]
  (or
    (validation/check params
                      {"k" [:exists? :base58-key?]
                       "s" [:exists? :hex-signature?]})
    (let [public-key (-> params
                         (get "k")
                         (bs58/decode)
                         (js/Buffer.))
          signature (-> params
                        (get "s")
                        (js/Buffer. "hex"))
          packet (-> params
                     (dissoc "s")
                     (clj->js)
                     (bencode/encode)
                     (js/Buffer.))]
      (when (not (.verify (.. nacl -sign -detached) packet signature public-key))
        {:error true :code 401 :message "Authentication failure."}))))

