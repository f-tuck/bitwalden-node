(ns sharewode-node.web
  (:require [sharewode-node.utils :refer [<<< to-json]]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! put! timeout chan]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(nodejs/enable-util-print!)

; nodejs requirements
(defonce crypto (nodejs/require "crypto"))
(defonce debug ((nodejs/require "debug") "sharewode-node.web"))
(defonce express (nodejs/require "express"))
(defonce url (nodejs/require "url"))
(defonce cookie (nodejs/require "cookie-parser"))
(defonce body-parser (nodejs/require "body-parser"))

(defonce port 8923)
(defonce path "/sw")

(defn write-header [res code & [headers]]
  (print "writeHead" code)
  (.writeHead res code (clj->js (merge {"Content-Type" "application/json"} headers))))

(defn param [req k]
  (or (aget (.-query req) k)
      (aget (.-body req) k)))

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
                     result-chan (chan)]
                 ; tell listening channel we have an incoming client request
                 (put! requests-chan [(param req "c") req result-chan res])
                 ; check if there is anything on the return channel for this client and copy it into their outgoing queue
                 (let [[code response & [headers]] (<! result-chan)]
                   (write-header res code headers)
                   (.end res (to-json response))) 
                 (recur))))
    
    ; serve
    (.listen app port)
    
    {:server app :port port :requests-chan requests-chan}))

