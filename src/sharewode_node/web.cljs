(ns sharewode-node.web
  (:require [sharewode-node.utils :refer [<<< to-json buf-hex timestamp-now pr-thru]]
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

(defonce port 8923)
(defonce path "/sw")

(defn write-header [res code & [headers]]
  (.writeHead res code (clj->js (merge {"Content-Type" "application/json"} headers))))

(defn make [configuration content-dir public-peers]
  (let [app (express)
        requests-chan (chan)]
    
    ; thread that runs every second and flushes old messages and clients
    (go-loop []
             (<! (timeout 1000))
             ;(debug "Flushing client queues.")
             ; TODO: this.
             (recur))
    
    ; parse incoming data
    (.use app (cookie))
    ;(.use app (.json body-parser))

    (.use app "/sw/content" (.static express content-dir))

    ; handle requests
    (let [root-chan (<<< #(.get app "/" %))
          info-chan (<<< #(.get app "/sw/info" %))
          peers-chan (<<< #(.get app "/sw/peers" %))
          request-chan (<<< #(.all app path (.json body-parser) %))]
      
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
    (.listen app port)
    
    {:server app :port port :requests-chan requests-chan}))

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

(defn get-pending-messages [q after]
  (filter #(> (% :timestamp) after) q))

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

