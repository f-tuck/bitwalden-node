(ns bitwalden-node.utils
  (:require [cljs.core.async :refer [close! put! chan]]
            [cljs.nodejs :as nodejs])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defonce crypto (nodejs/require "crypto"))
(defonce os (nodejs/require "os"))

(defn <<< [f & args]
  (let [c (chan)] ()
    (apply f (concat args [(fn
                             ([] (close! c))
                             ([& x] (put! c x)))]))
    c))

(defn pr-thru [x & [message]]
  (print message x)
  x)

(defn sha1 [x]
  (.digest (.update (.createHash crypto "sha1") x "utf8")))

(defn to-json [x]
  (js/JSON.stringify (clj->js x)))

(defn from-json [x]
  (js->clj (js/JSON.parse x)))

(defn buffer [x]
  (if (string? x) (js/Buffer. x "hex") x))

(defn buf-hex [b]
  (.toString b "hex"))

(defn timestamp-now []
  (.getTime (js/Date.)))

(defn serialize-error [e]
  (if e
    {"error" true
     "code" (.. e -code)
     "message" (.. e -message)}))
