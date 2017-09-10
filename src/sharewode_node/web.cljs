(ns sharewode-node.web
  (:require [sharewode-node.utils :refer [<<< to-json buf-hex timestamp-now pr-thru]]
            [sharewode-node.constants :as const]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! put! timeout chan sliding-buffer close! mult tap untap]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(nodejs/enable-util-print!)

; nodejs requirements
(defonce crypto (nodejs/require "crypto"))
(defonce debug ((nodejs/require "debug") "sharewode-node.web"))
(defonce express (nodejs/require "express"))
(defonce url (nodejs/require "url"))
(defonce cookie (nodejs/require "cookie-parser"))
(defonce body-parser (nodejs/require "body-parser"))
(defonce ed (nodejs/require "ed25519-supercop"))
(defonce bs58 (nodejs/require "bs58"))
(defonce bencode (nodejs/require "bencode"))
(defonce jayson (nodejs/require "jayson"))

(defn write-header [res code & [headers]]
  (.writeHead res code (clj->js (merge {"Content-Type" "application/json"} headers))))

(defn jsonrpc-router [api-atom clients bt method params]
  (let [api-call (@api-atom (keyword method))]
    (if api-call
      (fn [params callback]
        (go
          (debug "JSON RPC call:" method (clj->js params))
          (let [result (api-call (js->clj params) clients bt method)
                result (if (implements? cljs.core.async.impl.protocols/Channel result) (<! result) result)]
            (callback nil (clj->js result))))))))

(defn make-json-rpc-server [api clients bt]
  (.middleware (.server jayson #js {} #js {:router (partial jsonrpc-router api clients bt)})))

(defn make [configuration api-atom bt clients content-dir public-peers]
  (let [app (express)
        requests-chan (chan)]
    
    ; parse incoming data
    (.use app (cookie))

    (.use app (.urlencoded body-parser #js {:extended true :limit "1mb"}))

    (.use app "/sw/content" (.static express content-dir))

    (.post app "/sw/rpc" (.json body-parser #js {:limit "1mb" :type "*/*" }) (make-json-rpc-server api-atom clients bt))
    
    ; handle requests
    (let [root-chan (<<< #(.get app "/" %))
          info-chan (<<< #(.get app "/sw/info" %))
          peers-chan (<<< #(.get app "/sw/peers" %))
          request-chan (<<< #(.all app "/sw" (.json body-parser) %))]
      
      (go-loop []
               (let [[req res cb] (<! root-chan)]
                 (write-header res 200)
                 (.end res (to-json true))
                 (recur)))
      
      (go-loop []
               (let [[req res cb] (<! peers-chan)]
                 (write-header res 200)
                 (.end res (to-json @public-peers))
                 (recur)))
      
      (go-loop []
               (let [[req res cb] (<! info-chan)]
                 (write-header res 200)
                 (.end res (to-json {:bitwalden true}))
                 (recur)))
      
      (go-loop []
               (let [[req res cb] (<! request-chan)
                     result-chan (chan)
                     params (js->clj (.-body req))]
                 (.on req "close"
                      (fn []
                        (print "Closing result-chan")
                        (close! result-chan)))
                 ; tell listening channel we have an incoming client request
                 (put! requests-chan [(get params "c") params req res result-chan])
                 ; check if there is anything on the return channel for this client and copy it into their outgoing queue
                 (go
                   (let [[code response & [headers]] (<! result-chan)]
                     (when (and code res)
                       (write-header res code headers)
                       (.end res (to-json response)))))
                 (recur))))
    
    ; serve
    (.listen app const/web-api-port)
    
    {:server app :port const/web-api-port :requests-chan requests-chan}))

(defn ids [params]
  [(get params "k") (get params "u")])

(defn get-pending-messages [q after]
  (filter #(> (% :timestamp) after) q))

(defn remove-listeners [listeners to-remove]
  (remove #(contains? (set to-remove) %) listeners))

(defn send-to [listeners packet]
  (doall (map #(put! % [packet]) listeners)))

(defn truncate-messages [clients k uid messages]
  (assoc-in clients [:queues k uid] messages))

(defn add-queue-listener [clients k uid c after]
  (let [pending (get-pending-messages (get-in clients [:queues k uid]) after)
        clients (truncate-messages clients k uid pending)]
    ; if we have pending messages already send directly
    (if (> (count pending) 0)
      (do (put! c pending)
          (close! c)
          (assoc-in clients [:listeners k uid] nil))
      ; add the listener
      (update-in clients [:listeners k uid] conj c))))

(defn send-to-client [clients k uid payload]
  (let [packet {:payload payload :timestamp (timestamp-now)}
        listeners (get-in clients [:listeners k uid])]
    (if (> (count listeners) 0)
      ; if we have listeners send directly
      (do
        (send-to listeners packet)
        (doall (map #(close! %) listeners))
        (assoc-in clients [:listeners k uid] nil))
      ; otherwise queue this packet up
      (update-in clients [:queues k uid] conj packet))))

(defn pow-check [req]
  true)

(defn authenticate [params]
  (let [public-key (-> params
                       (get "k")
                       (bs58.decode)
                       (js/Buffer.))
        signature (-> params
                      (get "s")
                      (js/Buffer. "base64")
                      (.toString "hex"))
        packet (-> params
                   (dissoc "s")
                   (clj->js)
                   (bencode.encode)
                   (js/Buffer.))]
    (.verify ed signature packet public-key)))


; --- remove this stuff after refactor ---


(defn get-or-create-client! [existing-client]
  (print "existing-client?" (if existing-client "yes" "no"))
  (or
    existing-client
    (let [c (chan)
          q (atom []) 
          listeners (atom #{})]
      ;(print "creating new client chan")
      (go-loop []
               ;(print "client chan waiting for message")
               (let [message (<! c)
                     queued-message {:payload message :timestamp (timestamp-now)}]
                 (if message
                   (do ;(print "client chan got" message)
                       (swap! q conj queued-message)
                       ; also send the message through to any listening channels
                       ;(print "listeners" @listeners)
                       (doall (map #(put! % [queued-message]) @listeners))
                       (recur))
                   ; somebody has closed the client channel
                   (do
                     ;(print "destroying client chan")
                     (close! c)))))
      {:chan-to-client c :chans-from-client listeners :queue q})))

(defn ensure-client-chan! [client-queues-atom uuid pkey]
  ; insert a newly created client or the old one and return it
  (let [k [pkey uuid]]
    (print "Client:" k)
    (get
      (swap! client-queues-atom update-in [k] get-or-create-client!)
      k)))

(defn close-client-listener! [listeners c]
  (close! c)
  (swap! listeners disj c))

(defn client-chan-listen! [after client]
  (print "client-chan-listen!")
  (let [q (client :queue)
        listeners (client :chans-from-client)
        c (chan)
        ; clip the previous values on the queue
        messages (swap! q get-pending-messages after)]
    ; add our listener chan to the client set of listeners
    (swap! listeners conj c)
    (print "listeners:" listeners)
    (print "messages waiting:" (count messages))
    ; if there are messages waiting on the queue for this client
    (if (> (count messages) 0)
      ; send them through and close
      (do (put! c messages)
          (close-client-listener! listeners c)))
    ; insurance - wait for a timeout and close and remove the channel we made
    (go
      (<! (timeout 30000))
      (close-client-listener! listeners c)) 
    c))

