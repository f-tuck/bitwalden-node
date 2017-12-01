(ns bitwalden-node.torrent
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [chan put! <! close! timeout]]
            [bitwalden-node.utils :refer [buf-hex <<<]]
            [bitwalden-node.constants :as constants]
            ["webtorrent" :as wt]
            ["create-torrent" :as create-torrent]
            ["parse-torrent" :as parse-torrent]
            ["bencode/lib" :as bencode]
            ["debug/node" :as debug-fn])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(nodejs/enable-util-print!)

; nodejs requirements
(defonce debug (debug-fn "bitwalden-node.bittorrent"))

; seed some content as well as listening out for gossip messages
(defn seed [bt content-name contents downloads-dir]
  (let [c (chan)]
    (go
      (let [content (js/Buffer. contents)]
        (set! (.-name content) content-name)
        ; have to do the create-torrent parse-torrent dance to get the infoHash
        ; so that we have the correct download folder
        (create-torrent
          content
          (fn [err torrent-blob]
            (let [pre-torrent (parse-torrent torrent-blob)
                  infoHash (.-infoHash pre-torrent)
                  torrent (.seed bt content #js {:path (str downloads-dir "/" infoHash) :createdBy constants/created-by}
                                 (fn [torrent]
                                   (debug "Seeding" infoHash downloads-dir)
                                   (put! c {"infohash" (.-infoHash torrent)})
                                   (close! c)))])))))
    c))

; get a download link to some torrent
(defn add [bt infoHash downloads-dir]
  (let [c (chan)
        path (str downloads-dir "/" infoHash)]
    (go
      (debug "Adding" infoHash downloads-dir)
      (let [opts #js {:path path}]
        (.add bt infoHash opts
              (fn [torrent]
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
                          (close! c))))))))))
    c))

