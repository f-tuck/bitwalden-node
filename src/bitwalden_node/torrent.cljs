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
                  infoHash (.-infoHash pre-torrent)
                  path (str "content/" infoHash "/" (.-name content))]
              (if (.get bt infoHash)
                (do
                  (debug "Already seeding" infoHash downloads-dir)
                  (put! c {"infohash" infoHash "path" path "duplicate" true})
                  (close! c))
                (.seed bt content #js {:path (str downloads-dir "/" infoHash) :createdBy constants/created-by}
                       (fn [torrent]
                         (debug "Seeding" infoHash downloads-dir)
                         ; write torrent file
                         (debug "Writing" (str downloads-dir "/" infoHash ".torrent"))
                         ; TODO: replace this with API which automatically sends the torrent file
                         (.writeFile fs (str downloads-dir "/" infoHash ".torrent") torrent-blob)
                         (put! c {"infohash" (.-infoHash torrent) "path" path})
                         (close! c)))))))))
    c))

; fetch a torrent in one hit without sending incremental download stats
; use this for <1mb downloads
(defn fetch [bt infoHash downloads-dir]
  (let [c (chan)
        path (str downloads-dir "/" infoHash)
        success-hash {"download" "done" "path" (str "content/" infoHash "/")}]
    (go
      (let [existing-torrent (.get bt infoHash)]
        (if existing-torrent
          (do
            (debug "Already added" infoHash downloads-dir)
            (put! c (merge success-hash {"files" (get-torrent-files existing-torrent)}))
            (close! c))
          (let [opts #js {:path path}]
            (debug "Adding" infoHash downloads-dir)
            (.add bt infoHash opts
                  (fn [torrent]
                    (.on torrent "done"
                         (fn []
                           (put! c (merge success-hash {"files" (get-torrent-files torrent)}))
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
(defn re-seed [bt infoHash torrent-files path]
  (debug "Re-seeding" infoHash)
  (let [c (chan)]
    (.seed bt (clj->js torrent-files) #js {:path path}
           (fn [torrent]
             ; TODO: replace this with API which automatically sends the torrent file
             (debug "Writing" (str path ".torrent"))
             (.writeFile fs (str path ".torrent") (.-torrentFile torrent)
                         (fn [] (debug "Wrote" (str path ".torrent"))))
             (put! c torrent)))))

