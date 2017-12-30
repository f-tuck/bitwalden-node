(ns bitwalden-node.constants)

(def version "0001")
(def client-string (str "-BW" version "-"))
(def public-pool-name "bitwalden:v0:public-node")
(def web-api-port 8923)
(def channel-timeout-max (* 60 5 1000))
(def created-by (str "Bitwalden/" version))

(def re-sha1 #"\b[0-9a-f]{5,40}\b")

; every hour
(def dht-refresh-interval-ms (* 60 60 1000))
; for one month
(def dht-refresh-count (* 24 31))
