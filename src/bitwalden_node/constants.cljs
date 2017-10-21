(ns bitwalden-node.constants)

(def version "0001")
(def client-string (str "-BW" version "-"))
(def public-pool-name "bitwalden:v0:public-node")
(def web-api-port 8923)
(def channel-timeout-max (* 60 5 1000))
(def created-by (str "Bitwalden/" version))

; every hour
(def dht-refresh-interval-ms (* 60 60 1000))
; for one month
(def dht-refresh-count (* 24 31))

; web rpc return codes
(def authentication-error {:code 401 :message "Authentication failure."})
