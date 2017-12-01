(ns bitwalden-node.channels)

; ensures we have bittorrent wire swarms for all
; of the channels we are supposed to have
(defn update [clients]
  (-> clients
      identity))

; connect to a swarm and send a message to peers
(defn push [clients channel-name message]
  ; connect and wait for 8 wires or timeout
  ; send message to all wires
  ; return with number of wires we sent to
  )

(defn pull [clients channel-name]
  ; add this key + uid to listeners for this infohash
  ; return uid
  )

(defn respond [clients channel-name responder]
  ; add this key + uid to responders for this infohash
  ; return uid
  )

