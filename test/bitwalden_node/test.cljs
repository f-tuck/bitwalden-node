(ns bitwalden-node.test
  (:require [cljs.nodejs :as nodejs]
            [cljs.test :refer-macros [run-all-tests]]
            [bitwalden-node.pow.pow-test]))

(nodejs/enable-util-print!)

(defn ^:export run []
  (run-all-tests #"bitwalden-node.*-test"))

(set! *main-cli-fn* run)
