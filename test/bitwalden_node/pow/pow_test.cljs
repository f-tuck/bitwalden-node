(ns bitwalden-node.pow.pow-test
  (:require [cljs.test :refer-macros [deftest is]]
            [bitwalden-node.pow :as pow]))

(deftest make-token-hmac
  (is (= 2 (pow/make-token))))

