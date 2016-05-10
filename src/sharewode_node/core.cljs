(ns sharewode-node.core
  (:require [sharewode-node.utils :refer [<<<]]
            [sharewode-node.config :as config]
            [sharewode-node.dht :refer [make-dht! install-listeners!]]
            [sharewode-node.json-rpc :as rpc]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! timeout]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defonce crypto (nodejs/require "crypto"))

(nodejs/enable-util-print!)

(def client-string "-SW0001-")

(defn -main []
  (let [configuration (config/load config/default-config-filename)
        nodeId (config/get-or-set! configuration "nodeId" (.toString (.randomBytes crypto 20) "hex"))
        peerId (config/get-or-set! configuration "peerId" (.toString (js/Buffer. (+ client-string (.randomBytes crypto 6))) "hex"))
        ;dht (make-dht! configuration)
        json-rpc (rpc/make)]
    ;(install-listeners! dht configuration)
    (rpc/listen json-rpc)
    (print "Sharewode server started.")
    (print "nodeId:" nodeId)
    (print "peerId:" peerId)
    ;(print "DHT port:" (dht :port))
    (print "JSON RPC port:" (json-rpc :port))))

(defn hello []
  (print "hello!"))

(set! *main-cli-fn* -main)
