(ns bitwalden-node.torrent
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [chan put! <! close! timeout]]
            [bitwalden-node.utils :refer [buf-hex <<<]]
            [bitwalden-node.constants :as constants]
            ["webtorrent" :as wt]
            ["create-torrent" :as create-torrent]
            ["parse-torrent" :as parse-torrent]
            ["bencode/lib" :as bencode]
            ["debug/node" :as debug-fn]
            ["fs" :as fs])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(nodejs/enable-util-print!)

; nodejs requirements
(defonce debug (debug-fn "bitwalden-node.bittorrent"))

; get a list of torrent hash directories from the downloads directory
(defn get-disk-torrent-list [downloads-dir]
  (let [c (chan)]
    (go
      (.readdir fs downloads-dir
                (fn [err files]
                  (put! c (filter #(re-find constants/re-sha1 %) files)))))
    c))

; check if there are a set of files in a certain torrent path
(defn get-torrent-file-list [torrent-path]
  (let [c (chan)]
    (go
      (.readdir fs torrent-path
                (fn [err files]
                  (put! c (map #(str torrent-path "/" %) files)))))
    c))

; set of currently active torrent hashes
(defn get-current-torrent-hashes [bt]
  (set (map #(aget % "infoHash") (.-torrents bt))))

; extract salient file fields from torrent
(defn get-torrent-files [torrent]
  (map
    (fn [f]
      (into {} (map (fn [field] [field (aget f field)]) ["name" "path" "length"]))) 
    (.-files torrent)))

; seed some content
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
                  infoHash (.-infoHash pre-torrent)]
              (if (.get bt infoHash)
                (do
                  (debug "Already seeding" infoHash downloads-dir)
                  (put! c {"infohash" infoHash "duplicate" true})
                  (close! c))
                (.seed bt content #js {:path (str downloads-dir "/" infoHash) :createdBy constants/created-by}
                       (fn [torrent]
                         (debug "Seeding" infoHash downloads-dir)
                         (put! c {"infohash" (.-infoHash torrent)})
                         (close! c)))))))))
    c))

; get a download link to some torrent
(defn add [bt infoHash downloads-dir]
  (let [c (chan)
        path (str downloads-dir "/" infoHash)]
    (go
      (let [existing-torrent (.get bt infoHash)]
        (if existing-torrent
          (do
            (debug "Already added" infoHash downloads-dir)
            (put! c {"download" "done" "files" (get-torrent-files existing-torrent)}))
          (let [opts #js {:path path}]
            (debug "Adding" infoHash downloads-dir)
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
                              (put! c {"download" "done" "files" (get-torrent-files torrent)})
                              (close! c))))))))))))
    c))

; re-seed a torrent we have on disk already
(defn re-seed [bt infohash torrent-files path]
  (debug "Re-seeding" infohash)
  (<<< #(.seed bt (clj->js torrent-files) #js {:path path})))

