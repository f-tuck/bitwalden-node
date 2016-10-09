(ns sharewode-node.web
  (:require [sharewode-node.utils :refer [<<< to-json buf-hex timestamp-now]]
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

(defn make [configuration]
  (let [app (express)
        requests-chan (chan)]
    
    ; thread that runs every second and flushes old messages and clients
    (go-loop []
             (<! (timeout 1000))
             ;:w(debug "Flushing client queues.")
             ; TODO: this.
             (recur))
    
    ; parse incoming data
    (.use app (cookie))
    (.use app (.json body-parser))
    
    ; handle requests
    (let [root-chan (<<< #(.get app "/" %))
          request-chan (<<< #(.all app path %))]

      ; TODO: static-serve the HTML5 sharewode client software, if available
      (go-loop []
               (let [[req res cb] (<! root-chan)]
                 (write-header res 200)
                 (.end res "Sharewode node.")
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
  (or
    existing-client
    (let [c (chan)
          m (mult c)
          q (atom [])
          client-tap-chan (chan)
          client-tap (tap m client-tap-chan)]
      (print "creating new client chan")
      (go-loop []
               (print "pre-message")
               (let [message (<! client-tap)]
                 (print "for client" message)
                 (swap! q conj (assoc message :timestamp (timestamp-now)))
                 (if message
                   (recur)
                   (do
                     (print "destroying client")
                     (untap m client-tap)
                     (close! client-tap)
                     (close! client-tap-chan)))))
      {:chan c :mult m :queue q})))

(defn ensure-client-chan! [client-queues-atom k]
  ; insert a newly created client or the old one and return it
  (get
    (swap! client-queues-atom update-in [k] get-or-create-client!)
    k))

(defn make-test-client [existing-client send-chan]
  (print "hullo")
  (when (not existing-client)
    ; launch our psuedo thingy once
    (print "creating test client!")
    (go-loop []
             (let [howlong (* (js/Math.random) 10000)
                   howmany (+ (int (* (js/Math.random) 3)) 1)]
               ;(print "how long?" howlong "how many?" howmany)
               (<! (timeout howlong))
               (let [v (js/Math.random)]
                 (print "sending" v)
                 (if (put! send-chan {:test-hello v})
                   (recur)
                   (print "test-client: couldn't send!"))))))
  true)

(defn get-pending-messages [after q]
  (filter #(> (% :timestamp) after) q))

(defn tap-client-chan [after q m]
  (print "tap client chan")
  (let [c (chan)
        messages (get-pending-messages after q)]
    (if messages (print "messages waiting:" messages))
    ; if there are messages waiting on the queue for this client
    (if (> (count messages) 0)
      ; send them through
      (put! c messages)
      ; otherwise wait for messages or a timeout to send
      (let [t (timeout 30000)
            client-dup-chan (chan)
            client-tap (tap m client-dup-chan)]
        (print "creating tap chan")
        (go
          (let [[v p] (alts! [client-tap t])]
            (if v
              (if (put! c v)
                (print "tap-client send success")
                (print "tap-client send failed")))
            (print "destroying tap chan")
            (untap m client-tap)
            (close! client-dup-chan)
            (close! client-tap)
            (close! c)))))
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

