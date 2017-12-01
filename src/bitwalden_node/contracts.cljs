(ns bitwalden-node.contracts
  (:require [bitwalden-node.utils :refer [timestamp-now]]
            [bitwalden-node.dht :as dht]
            [bitwalden-node.constants :as const]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :as async :refer [<! chan put!]]
            ["debug/node" :as debug-fn])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defonce debug (debug-fn "bitwalden-node.contracts"))

(defn dht-add [clients timestamp dht-put-params & [remaining]]
  (let [remaining (or remaining const/dht-refresh-count)]
    (if (> remaining 0)
      (let [address [:contracts "refresh" (get dht-put-params "k") (get dht-put-params "salt")]
            ; find the index of existing k,salt refresh contract, if any
            existing (get-in clients address)
            clients-updated (if (or (not existing) (> (get dht-put-params "seq") ))
                              (update-in clients
                                         address
                                         assoc
                                         "put-params" (into {} (for [k ["v" "k" "salt" "seq" "s.dht"]]
                                                                [k (get dht-put-params k)])))
                              clients)]
        (update-in clients-updated address assoc 
                   "last-refresh" timestamp
                   ; TODO: should be kb with POW use
                   ; one month every hour
                   "remaining" remaining))
      (let [clients (update-in clients [:contracts "refresh" (get dht-put-params "k")] dissoc (get dht-put-params "salt"))]
        (if (> (count (get-in clients [:contracts "refresh" (get dht-put-params "k")])) 0)
          clients
          (update-in clients [:contracts "refresh"] dissoc (get dht-put-params "k")))))))

(defn dht-get-due [clients]
  (let [dht-contracts (get-in clients [:contracts "refresh"])]
    (filter (fn [[k salt contract]]
              (< (contract "last-refresh") (- (timestamp-now) const/dht-refresh-interval-ms)))
            (for [[k contracts] dht-contracts [salt contract] contracts]
              [k salt contract (contract "remaining")]))))

(defn dht-refresh-data [bt contracts-to-refresh]
  ; TODO: run these dht gets in parallel instead
  (go-loop [contracts contracts-to-refresh
            results []]
           (if (> (count contracts) 0)
             (let [[k salt contract remaining] (first contracts)
                   put-params (contract "put-params")
                   address (dht/address k salt)
                   get-result (<! (dht/get-value (.. bt -dht) (dht/address k salt) salt))
                   put-params (if (and get-result (not (get-result "error")) (> (get-result "seq") (put-params "seq"))) (assoc get-result "salt" salt) put-params)
                   success-count (if put-params
                                   (let [put-result
                                         (<! (dht/put-value
                                               (.. bt -dht)
                                               (put-params "v")
                                               (put-params "k")
                                               (put-params "salt")
                                               (put-params "seq")
                                               (put-params "s.dht")))]
                                     (debug "dht-refresh contract put" (put-result "addresshash") (put-result "nodecount"))
                                     (put-result "nodecount")))]
               (recur 
                 (rest contracts)
                 (if (and success-count (> success-count 0))
                   (conj results [k salt put-params (dec remaining)])
                   results)))
             results)))

