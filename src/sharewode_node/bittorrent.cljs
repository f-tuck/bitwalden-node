(ns sharewode-node.bittorrent
  (:require [sharewode-node.utils :refer [<<< buffer buf-hex]]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :refer [<! timeout chan close! put! alts!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(nodejs/enable-util-print!)

(def connection-timeout 10000)

; nodejs requirements
(defonce debug ((nodejs/require "debug") "sharewode-node.bittorrent"))
(defonce bittorrent (nodejs/require "bittorrent-protocol"))
(defonce net (nodejs/require "net"))
(defonce bencode (nodejs/require "bencode"))

(defn catch-socket-errors [socket host port]
  ; if any exit condition occurs on the socket, close and destroy it
  (go (alts! (for [n ["error" "timeout" "close" "end"]] (<<< #(.once socket n %))))
      (print host port "socket closing because of close/error")
      (.destroy socket))
  socket)

(defn destroy-peer [host port to-peer-chan from-peer-chan socket wire]
  (print host port "destroy wire")
  ;(print host port "destroy - closing chans")
  (put! from-peer-chan {:type :close})
  (close! to-peer-chan)
  (close! from-peer-chan)
  ;(print host port "destroy - socket")
  (.destroy socket)
  ;(print host port "destroy - wire")
  (.destroy wire))

; ugh hideous
; http://stackoverflow.com/a/12428716/2131094
; TODO: rewrite this using reify or deftype:
; https://github.com/clojure/clojurescript/wiki/Working-with-Javascript-classes
(defn make-sw-extension [host port to-peer-chan from-peer-chan socket wire destroy]
  (let [sw-extension (fn [in-wire]
                       (print host port "created sw_gossip extension")
                       (print host port "same wire test:" (= in-wire wire)))]
    (set! (.. sw-extension -prototype -name) "sw_gossip")
    (set! (.. sw-extension -prototype -onMessage)
          (fn [buf]
            (print host port "sw_gossip onMessage")
            (let [decoded (.decode bencode buf "utf8")]
              (print host port "*** <<< sw_gossip message:")
              (js/console.log "\t" decoded))))
    (set! (.. sw-extension -prototype -onHandshake)
          (fn [infoHash peerId extensions]
            (print host port "sw_gossip onHandshake" (buf-hex infoHash) (buf-hex peerId))
            (js/console.log "\t" extensions)))
    (set! (.. sw-extension -prototype -onExtendedHandshake)
          (fn [handshake]
            (print host port "sw_gossip onExtendedHandshake")
            (js/console.log "\t" handshake)
            (when (.. handshake -m -sw_gossip)
              ; randomly send sharewode messages
              (go
                ;(.unchoke wire)
                ;(.interested wire)
                (loop []
                  (<! (timeout (* 10000 (js/Math.random))))
                  (if (not (.-destroyed wire))
                    (let [sendval {"random-number" (int (* 65536 (js/Math.random)))}]
                      (print host port "*** >>> sw_gossip message:" sendval)
                      (.extended wire "sw_gossip" (.encode bencode (clj->js sendval)))
                      (recur))
                    (print host port "exiting handshake loop")))))))
    sw-extension))

(defn make-peer [received-peers-chan socket peerId-mine infoHash-outgoing]
  (let [wire (bittorrent.)
        to-peer-chan (chan)
        from-peer-chan (chan)
        host (.-remoteAddress socket)
        port (.-remotePort socket)
        destroy (partial destroy-peer host port to-peer-chan from-peer-chan socket wire)
        sw-extension (make-sw-extension host port to-peer-chan from-peer-chan socket wire destroy)]
    ; catch socket errors (again) and destroy
    (go (alts! (for [n ["error" "timeout" "close" "end"]] (<<< #(.once socket n %))))
        (print host port "destroying wire/socket because of socket error")
        (destroy))
    ; install sharewode bittorrent extension
    (.use wire sw-extension)
    ; lol javascript
    (-> socket (.pipe wire) (.pipe socket))
    ; set the keepalive message
    ;(.setKeepAlive wire true)
    ;(.on wire "keep-alive" (fn [] host port "got keepalive"))
    ; loop to process data being sent to the peer from us
    (go-loop []
             (let [message-to-peer (<! to-peer-chan)
                   what (and message-to-peer (message-to-peer :type))]
               ; keep going as long as the send channel is open, clean up if closed
               (if (and message-to-peer wire (not (.-destroyed wire)))
                 (do
                   (cond
                     ; if we're asked to handshake the other peer
                     (and (= what :handshake) (message-to-peer :infoHash)) (.handshake wire (message-to-peer :infoHash) peerId-mine)
                     ; var buf = bencode.encode(dict)
                     ; (.extend wire "sw_gossip" buf)
                     ; if we're asked to send a sharewode payload to the other peer
                     ; (= what :sharewode) 
                     :else (debug "Unknown type in message-to-peer:" message-to-peer))
                   (recur))
                 (destroy))))
    ; when we receive a handshake
    (go
      (let [[infoHash peerId-them extensions] (<! (<<< #(.once wire "handshake" %)))]
        (print host port (buf-hex infoHash) (buf-hex peerId-them) "got handshake")
        ;(print "peerId match?" (buf-hex peerId-mine) (buf-hex peerId-them))
        (if (= peerId-mine peerId-them)
          ; connected to myself, bailing
          (do
            (print host port (buf-hex infoHash) (buf-hex peerId-them) "connected to myself - destroying socket")
            (destroy))
          ; process the handshake
          (do
            (print host port (buf-hex infoHash) (buf-hex peerId-them) "got to handshake stage with peer")
            (print host port (buf-hex infoHash) (buf-hex peerId-them) "handshake extensions:" extensions)
            ;  sending handshake back if we weren't the handshake initiator
            (if (nil? infoHash-outgoing)
              (.handshake wire infoHash peerId-mine #js {:extended true :dht false}))
            ; pass the message on to listeners
            (put! received-peers-chan {:type :handshake :infoHash infoHash :peerId peerId-them :host host :port port :from-peer from-peer-chan :to-peer to-peer-chan})))))
    
    ; TODO: use ut_pex
    ; addPeer(host:port)
    ; 
    ; TODO: dht port message if extensions.dht is true
    ; // send your port to the peer
    ; wire.port(dhtPort)
    ;wire.on('port', function (dhtPort) {
    ;    // peer has sent a port to us
    ;})
    ; TODO: throttle abusive peers by checking:
    ; wire.uploaded // number of bytes uploaded
    ; wire.downloaded // number of bytes downloaded
    
    ; catch choke and interested events
    (.on wire "choke" (fn [] (print host port "peer choked us")))
    (.on wire "unchoke" (fn [] (print host port "peer unchoked us")))
    (.on wire "interested" (fn [] (print host port "peer is interested in us")))
    (.on wire "uninterested" (fn [] (print host port "peer is uninterested in us")))
    
    ; if this is an outgoing peer we know the infoHash, so handshake already
    (go
      (when infoHash-outgoing
        (print host port (buf-hex infoHash-outgoing) "sending handshake")
        (.handshake wire infoHash-outgoing peerId-mine #js {:extended true :dht false})))
    (print host port (if infoHash-outgoing (buf-hex infoHash-outgoing) "") "make-peer done")))

(defn make [configuration]
  (let [nodeId (@configuration "nodeId")
        peerId (@configuration "peerId")
        torrentPort (@configuration "torrentPort")
        received-peers-chan (chan)
        bittorrent {:received-peers-chan received-peers-chan
                    :port torrentPort}
        connections-chan (chan)]
    ; loop processing incoming connections
    (go-loop []
             (let [socket (<! connections-chan)
                   host (.-remoteAddress socket)
                   port (.-remotePort socket)
                   socket (catch-socket-errors socket host port)]
               (print host port "incoming socket connected")
               (make-peer received-peers-chan socket peerId nil) 
               (recur)))
    ; set up the nodejs server using bittorrent protocol
    (-> (.createServer net #(put! connections-chan %))
        (.listen torrentPort))
    bittorrent))

(defn connect-to-peer [host port infoHash peerId]
  (let [received-peers-chan (chan)
        socket (.connect net #js {:host host :port port})]
    (print host port (buf-hex infoHash) "connecting outgoing socket")
    ; once the socket connects turn it into a wire and handshake
    (go
      (catch-socket-errors socket host port)
      (let [connect-chan (<<< #(.once socket "connect" %))
            timeout-chan (timeout connection-timeout)]
        (let [[v p] (alts! [connect-chan timeout-chan])]
          (if (= p connect-chan)
            (do (print host port (buf-hex infoHash) "outgoing socket connected")
                (make-peer received-peers-chan socket peerId infoHash))
            (do (print host port (buf-hex infoHash) "connection timed out")
                (close! received-peers-chan))))))
    received-peers-chan))
