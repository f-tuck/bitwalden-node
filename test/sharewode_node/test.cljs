(ns sharewode-node.test
  (:require [cljs.nodejs :as nodejs]
            [cljs.test :refer-macros [run-all-tests]]
            [sharewode-node.pow.pow-test]))

(nodejs/enable-util-print!)

(defn ^:export run []
  (run-all-tests #"sharewode-node.*-test"))

(set! *main-cli-fn* run)
