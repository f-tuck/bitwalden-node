(ns sharewode-node.pow.pow-test
  (:require [cljs.test :refer-macros [deftest is]]
            [sharewode-node.pow :as pow]))

(deftest make-token-hmac
  (is (= 2 (pow/make-token))))

