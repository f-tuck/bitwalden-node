(ns sharewode-node.torrent
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [chan put! <! close! timeout]]
            [sharewode-node.utils :refer [buf-hex <<<]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(nodejs/enable-util-print!)

; nodejs requirements
(defonce debug ((nodejs/require "debug") "sharewode-node.bittorrent"))
(defonce wt (nodejs/require "webtorrent"))
(defonce bencode (nodejs/require "bencode"))

(defn make-client [opts]
  (let [client (wt. opts)]
    (.on client "ready" (fn [] (js/console.log "torrent client ready")))
    (.on client "torrent" (fn [torrent] (js/console.log "torrent client added:" (.-infoHash torrent))))
    {:client client
     :dht (.-dht client)
     :channel-receive (chan)
     :channels-send (atom {})})) ; infoHash -> channel list

; bittorrent extension protocol
(defn make-protocol [bt infoHash wire addr]
  (let [peerId (buf-hex (.-peerId wire))
        protocol-extension (fn [wire] (print infoHash peerId addr "created gossip extension."))
        channel-receive (chan)]
    (set! (.. protocol-extension -prototype -name) "sw_gossip")
    (set! (.. protocol-extension -prototype -onMessage)
          (fn [buf]
            (print infoHash peerId addr "extension onMessage")
            (put! (bt :channel-receive) [infoHash peerId addr buf])))
    (set! (.. protocol-extension -prototype -onHandshake)
              (fn [infoHash peerId extensions]
                (print infoHash peerId addr "extension onHandshake")
                (js/console.log "\t" extensions)))
    (set! (.. protocol-extension -prototype -onExtendedHandshake)
              (fn [handshake]
                (print infoHash peerId addr "extension onExtendedHandshake")
                (js/console.log "\t" handshake)
                (when (.. handshake -m -sw_gossip)
                  (print "Got sw_gossip handshake")
                  (swap! (bt :channels-send) assoc-in [infoHash peerId] channel-receive)
                  (print "channels send updated:" (deref (bt :channels-send)))
                  (go
                    (loop []
                      ; TODO: also timeout
                      (let [message (<! channel-receive)]
                        (print "Sending message:" message)
                        (if (not (.-destroyed wire))
                          (do
                            (.extended wire "sw_gossip" (.encode bencode (clj->js message)))
                            (recur))
                          (do
                            (print "Removing peer channel" infoHash peerId)
                            (swap! (bt :channels-send) update-in [infoHash] dissoc peerId)
                            (print infoHash peerId addr "exiting handshake loop")))))))))
    protocol-extension))

; seed some content as well as listening out for gossip messages
(defn seed [bt content-name contents]
  (let [c (chan)]
    (go
      (let [content (js/Buffer. contents)
            _ (set! (.-name content) content-name)
            torrent (.seed (bt :client) content ;#js {:name content-name :createdBy "sharewode"}
                           (fn [torrent]
                             (js/console.log "seeding:" (.-infoHash torrent))
                             (put! c (.-infoHash torrent))
                             (close! c)))]
        (.on torrent "wire"
             (fn [wire addr]
               (print (str "New wire" addr (.-peerId wire)))
               (.use wire (make-protocol bt (.-infoHash torrent) wire addr))))))
    c))

; join a name-only party
(defn join [bt identifier]
  (seed bt identifier "\0"))

; get a download link to some torrent
(defn add [bt infoHash downloads-dir]
  (let [c (chan)
        path (str downloads-dir "/" infoHash)]
    (go
      (print "Adding" infoHash downloads-dir)
      (.add (bt :client) infoHash (clj->js {"path" (str downloads-dir "/" infoHash)})
            (fn [torrent]
              (print "Got torrent" (.-infoHash torrent))
              (put! c {"download" "starting"})
              (go
                ; TODO: timeout if it appears to be hanging
                (loop []
                  ;(print "Checking download progress")
                  (when (put! c {"download" "progress" "value" (.-progress torrent)})
                    (<! (timeout 1000))
                    (if (< (.-progress torrent) 1.0)
                      (recur)
                      (do
                        ;(put! c {"download" "progress" "value" 1})
                        (put! c {"download" "done" "files" (map (fn [f] (into {} (map (fn [field] [field (aget f field)]) ["name" "path" "length"]))) (.-files torrent))})
                        (close! c)))))))))
    c))

(defn send-to-swarm [bt infoHash message]
  (doseq [[peerId c] (get (deref (bt :channels-send)) infoHash)]
    (put! c message)))
