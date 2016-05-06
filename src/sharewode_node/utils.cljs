(ns sharewode-node.utils
  (:require [cljs.core.async :refer [close! put! chan]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn <<< [f & args]
  (let [c (chan)] ()
    (apply f (concat args [(fn
                             ([] (close! c))
                             ([& x] (put! c x)))]))
    c))
