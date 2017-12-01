(ns bitwalden-node.pool
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [chan put! <! close! timeout]]
            [bitwalden-node.utils :refer [buf-hex <<<]]
            ["url-exists" :as url-exists]
            ["range_check" :as ip-range-check]
            ["webtorrent" :as wt]
            ["bencode/lib" :as bencode]
            ["debug/node" :as debug-fn]))

(nodejs/enable-util-print!)

; nodejs requirements
(defonce debug (debug-fn "bitwalden-node.pool"))

(def EXT "bw_pool")
(def check-path "/bw/info")

(defn split-address [addr]
  (let [last-colon-index (.lastIndexOf addr ":")
        ip (.slice addr 0 last-colon-index)
        ip (.storeIP ip-range-check ip)
        ip (if (.isV6 ip-range-check ip) (str "[" ip "]") ip) ; convert ipv6 URLs
        port (.slice addr (+ last-colon-index 1))]
    (debug "split-address" addr ip port)
    [ip port]))

(defn receive-message [wire message]
  (let [decoded-js (.decode bencode (.toString message))
        decoded (js->clj decoded-js)]
    (debug "message:" decoded)))

(defn handle-handshake [peers their-url wire addr handshake]
  (debug "handle-handshake" (.. wire -peerId) addr handshake)
  (let [url (.. handshake -URL)
        url (if url (.toString url "utf8"))
        [ip port] (split-address addr)]
    (debug "url" url)
    (when (and
            (.. handshake -m)
            (aget (.. handshake -m) EXT)
            url)
      ; if they only sent a port then use the IP address
      (let [public-url (if (= (.indexOf url ":") 0)
                         (str "http://" ip url)
                         url)
            check-url (str public-url check-path)]
        (debug "pool peer" public-url)
        ; try to connect to their web API and if it works then
        ; add to the peer list
        (url-exists
          check-url
          (fn [err exists]
            (debug "url-exists test" check-url err exists)
            (if (and (not err) exists)
              (do
                (reset! their-url public-url)
                (swap! peers assoc-in [public-url] {}))
              (debug "url-exists check failed"))))))))

(defn wire-fn [URL wire]
  (set! (.. wire -extendedHandshake -URL) URL))

(defn attach-extension-protocol [public-url peers their-url wire addr]
  (let [t (partial wire-fn public-url)]
    ; yuck javascript
    (set! (.. t -prototype -name) EXT)
    (set! (.. t -prototype -onExtendedHandshake) (partial handle-handshake peers their-url wire addr))
    (set! (.. t -prototype -onMessage) (partial receive-message wire))
    t))

(defn detach-wire [peers their-url wire]
  (when @their-url
    (swap! peers dissoc @their-url)
    (debug "closed" @their-url)))

(defn attach-wire [public-url peers wire addr]
  (let [their-url (atom nil)]
    (debug "saw wire" (.-peerId wire))
    (.use wire (attach-extension-protocol public-url peers their-url wire addr))
    (.on wire "close" (partial detach-wire peers their-url wire))))

(defn connect [wt channel-name public-url peers]
  (let [content (js/Buffer. #js [0])]
    (set! (.. content -name) channel-name)
    (.on wt "torrent" #(debug "pool/connect torrent" (.-infoHash %)))
    (let [torrent (.seed wt content #(js/console.log "pool/connect" (.-infoHash %)))]
      (.on torrent "wire" (partial attach-wire public-url peers))
      peers)))
