(ns bitwalden-node.validation
  (:require [cljs.nodejs :as nodejs]
            ["bs58" :as bs58]))

(def check-fns {:exists?
                (fn [p n] 
                  (when (nil? (p n))
                    "must be present."))

                :base58-key?
                (fn [p n]
                  (let [k (try (bs58/decode (p n)) (catch :default e nil))]
                    (cond (nil? k)
                          "must be base58 encoded."
                          (not= (.-length k) 32)
                          "must be 32 bytes when decoded.")))

                :string?
                (fn [p n]
                  (when (not= (type (p n)) js/String)
                    "must be a string."))

                :max1k?
                (fn [p n]
                  (when (>= (.-length (p n)) 1000)
                    "must be less than 1000 bytes."))

                :hex-sha1?
                (fn [p n]
                  (let [k (try (js/Buffer. (p n) "hex") (catch :default e nil))]
                    (cond
                      (nil? k)
                      "must be hex encoded."
                      (not= (.-length (p n)) 40)
                      "must be the correct length for a hex encoded sha1 hash.")))

                :hex-signature?
                (fn [p n]
                  (let [k (try (js/Buffer. (p n) "hex") (catch :default e nil))]
                    (cond
                      (nil? k)
                      "must be hex encoded."
                      (not= (.-length (p n)) 128)
                      "must be the correct length for a hex encoded signature.")))

                :int?
                (fn [p n]
                  (let [k (p n)]
                    (when (and
                            (not= (type k) js/Number) 
                            (= (int k) k))
                      "must be an integer")))})

(defn check [params param-checks]
  (some (fn [[k checks]]
          (some
            (fn [f] (let [msg ((check-fns f) params k)]
                      (when msg {:error true :code 400 :message (str "Parameter '" k "' " msg)})))
            checks))
        param-checks))
