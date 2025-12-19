;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.codec.domain.poll
  "Encode/decode for Poll family packets: ArtPoll, ArtPollReply."
  (:require
    [clj-artnet.impl.protocol.codec.constants :as const]
    [clj-artnet.impl.protocol.codec.domain.common :as common]
    [clj-artnet.impl.protocol.codec.primitives :as prim])
  (:import
    (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(def ^:private default-pollreply
  {:ip                      [0 0 0 0]
   :port                    0x1936
   :version-hi              0
   :version-lo              0
   :net-switch              0
   :sub-switch              0
   :oem                     0xFFFF
   :ubea-version            0
   :status1                 0
   :esta-man                const/esta-man-prototype-id
   :short-name              "clj-artnet"
   :long-name               "clj-artnet node"
   :node-report             "#0001 [0001] Startup"
   :num-ports               1
   :port-types              [0 0 0 0]
   :good-input              [0 0 0 0]
   :good-output-a           [0 0 0 0]
   :good-output-b           [0 0 0 0]
   :sw-in                   [0 0 0 0]
   :sw-out                  [0 0 0 0]
   :acn-priority            100
   :sw-macro                0
   :sw-remote               0
   :style                   0
   :mac                     [0 0 0 0 0 0]
   :bind-ip                 nil
   :bind-index              1
   :status2                 0
   :status3                 0
   :default-responder       [0 0 0 0 0 0]
   :user-hi                 0
   :user-lo                 0
   :refresh-rate            0
   :background-queue-policy 0})

(defn- normalize-pollreply
  "Normalize ArtPollReply fields with defaults and validation."
  [m]
  (let [m (merge default-pollreply m)
        {ip                :ip
         bind-ip           :bind-ip
         mac               :mac
         port-types        :port-types
         good-input        :good-input
         good-output-a     :good-output-a
         good-output-b     :good-output-b
         sw-in             :sw-in
         sw-out            :sw-out
         default-responder :default-responder}
        m]
    (-> m
        (assoc :ip (common/normalize-ip ip))
        (assoc :bind-ip (common/normalize-ip (or bind-ip ip)))
        (assoc :mac (common/normalize-mac mac))
        (assoc :port-types (common/normalize-array port-types 4))
        (assoc :good-input (common/normalize-array good-input 4))
        (assoc :good-output-a (common/normalize-array good-output-a 4))
        (assoc :good-output-b (common/normalize-array good-output-b 4))
        (assoc :sw-in (common/normalize-array sw-in 4))
        (assoc :sw-out (common/normalize-array sw-out 4))
        (assoc :default-responder
               (common/normalize-array default-responder 6)))))

(defn encode-artpollreply!
  "Encode ArtPollReply packet into a buffer."
  [^ByteBuffer buf fields]
  (let [{:keys [ip port version-hi version-lo net-switch sub-switch oem
                ubea-version status1 esta-man short-name long-name node-report
                num-ports port-types good-input good-output-a good-output-b
                sw-in sw-out acn-priority sw-macro sw-remote style mac bind-ip
                bind-index status2 status3 default-responder user-hi user-lo
                refresh-rate background-queue-policy]}
        (normalize-pollreply fields)]
    (.clear buf)
    (doto buf
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :artpollreply))
      (prim/put-bytes! ip)
      (prim/put-u16-le! port)
      (.put (unchecked-byte version-hi))
      (.put (unchecked-byte version-lo))
      (.put (unchecked-byte net-switch))
      (.put (unchecked-byte sub-switch))
      (prim/put-u16-be! oem)
      (.put (unchecked-byte ubea-version))
      (.put (unchecked-byte status1))
      (prim/put-u16-be! esta-man)
      (prim/put-fixed-string! short-name 18)
      (prim/put-fixed-string! long-name 64)
      (prim/put-fixed-string! node-report 64)
      (prim/put-u16-be! num-ports)
      (prim/put-bytes! port-types)
      (prim/put-bytes! good-input)
      (prim/put-bytes! good-output-a)
      (prim/put-bytes! sw-in)
      (prim/put-bytes! sw-out)
      (.put (unchecked-byte acn-priority))
      (.put (unchecked-byte sw-macro))
      (.put (unchecked-byte sw-remote))
      (.put (unchecked-byte 0))
      (.put (unchecked-byte 0))
      (.put (unchecked-byte 0))
      (.put (unchecked-byte style))
      (prim/put-bytes! mac)
      (prim/put-bytes! (or bind-ip ip))
      (.put (unchecked-byte bind-index))
      (.put (unchecked-byte status2))
      (prim/put-bytes! good-output-b)
      (.put (unchecked-byte status3))
      (prim/put-bytes! default-responder)
      (.put (unchecked-byte user-hi))
      (.put (unchecked-byte user-lo))
      (prim/put-u16-be! refresh-rate)
      (.put (unchecked-byte background-queue-policy)))
    (dotimes [_ 10] (.put buf (unchecked-byte 0)))
    (.flip buf)))

(defn encode-artpoll!
  "Encode ArtPoll packet into a buffer."
  [^ByteBuffer buf fields]
  (let [{:keys [protocol-version diag-priority target-top target-bottom esta-man
                oem suppress-delay? reply-on-change? diag-request? diag-unicast?
                vlc-transmission-disabled? target-enabled? flags]}
        fields
        protocol (or protocol-version const/protocol-version)
        f (or flags
              (cond-> 0
                      suppress-delay? (bit-or 0x01)
                      reply-on-change? (bit-or 0x02)
                      diag-request? (bit-or 0x04)
                      diag-unicast? (bit-or 0x08)
                      vlc-transmission-disabled? (bit-or 0x10)
                      target-enabled? (bit-or 0x20)))]
    (.clear buf)
    (doto buf
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :artpoll))
      (.put (unchecked-byte 0))
      (.put (unchecked-byte protocol))
      (.put (unchecked-byte f))
      (.put (unchecked-byte (or diag-priority 0)))
      (prim/put-u16-be! (or target-top 0))
      (prim/put-u16-be! (or target-bottom 0))
      (prim/put-u16-be! (or esta-man 0))
      (prim/put-u16-be! (or oem 0)))
    (.flip buf)))

(defn decode-artpoll
  "Decode ArtPoll packet from buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) 14)
    (throw (ex-info "Truncated ArtPoll" {:limit (.limit buf)})))
  (let [protocol (prim/safe-uint16-be buf 10)
        flags (prim/safe-ubyte buf 12)
        diag (prim/safe-ubyte buf 13)
        target-top (prim/safe-uint16-be buf 14)
        target-bottom (prim/safe-uint16-be buf 16)
        esta-man (if (>= (.limit buf) 20) (prim/safe-uint16-be buf 18) 0)
        oem (if (>= (.limit buf) 22) (prim/safe-uint16-be buf 20) 0)]
    {:op                         :artpoll
     :protocol-version           protocol
     :flags                      flags
     :talk-to-me                 flags
     :priority                   diag
     :diag-priority              diag
     :suppress-delay?            (bit-test flags 0)
     :reply-on-change?           (bit-test flags 1)
     :diag-request?              (bit-test flags 2)
     :diag-unicast?              (bit-test flags 3)
     :vlc-transmission-disabled? (bit-test flags 4)
     :target-enabled?            (bit-test flags 5)
     :target-top                 target-top
     :target-bottom              target-bottom
     :esta-man                   esta-man
     :oem                        oem}))

(defn decode-artpollreply
  "Decode ArtPollReply packet from the buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artpollreply-length)
    (throw (ex-info "Truncated ArtPollReply" {:limit (.limit buf)})))
  (let [ip (prim/read-octets buf 10 4)
        port (prim/uint16-le buf 14)
        version-hi (prim/safe-ubyte buf 16)
        version-lo (prim/safe-ubyte buf 17)
        net-switch (prim/safe-ubyte buf 18)
        sub-switch (prim/safe-ubyte buf 19)
        oem (prim/safe-uint16-be buf 20)
        ubea-version (prim/safe-ubyte buf 22)
        status1 (prim/safe-ubyte buf 23)
        esta-man (prim/safe-uint16-be buf 24)
        short-name (prim/read-ascii buf 26 18)
        long-name (prim/read-ascii buf 44 64)
        node-report (prim/read-ascii buf 108 64)
        num-ports (prim/safe-uint16-be buf 172)
        port-types (prim/read-octets buf 174 4)
        good-input (prim/read-octets buf 178 4)
        good-output-a (prim/read-octets buf 182 4)
        sw-in (prim/read-octets buf 186 4)
        sw-out (prim/read-octets buf 190 4)
        acn-priority (prim/safe-ubyte buf 194)
        sw-macro (prim/safe-ubyte buf 195)
        sw-remote (prim/safe-ubyte buf 196)
        style (prim/safe-ubyte buf 200)
        mac (prim/read-octets buf 201 6)
        bind-ip (prim/read-octets buf 207 4)
        bind-index (prim/safe-ubyte buf 211)
        status2 (prim/safe-ubyte buf 212)
        good-output-b (prim/read-octets buf 213 4)
        status3 (prim/safe-ubyte buf 217)
        default-responder (prim/read-octets buf 218 6)
        user-hi (prim/safe-ubyte buf 224)
        user-lo (prim/safe-ubyte buf 225)
        refresh-rate (prim/safe-uint16-be buf 226)
        background-queue-policy (prim/safe-ubyte buf 228)]
    {:op                      :artpollreply
     :ip                      ip
     :port                    port
     :version-hi              version-hi
     :version-lo              version-lo
     :net-switch              net-switch
     :sub-switch              sub-switch
     :oem                     oem
     :ubea-version            ubea-version
     :status1                 status1
     :esta-man                esta-man
     :short-name              short-name
     :long-name               long-name
     :node-report             node-report
     :num-ports               num-ports
     :port-types              port-types
     :good-input              good-input
     :good-output-a           good-output-a
     :good-output-b           good-output-b
     :sw-in                   sw-in
     :sw-out                  sw-out
     :acn-priority            acn-priority
     :sw-macro                sw-macro
     :sw-remote               sw-remote
     :style                   style
     :mac                     mac
     :bind-ip                 bind-ip
     :bind-index              bind-index
     :status2                 status2
     :status3                 status3
     :default-responder       default-responder
     :user-hi                 user-hi
     :user-lo                 user-lo
     :refresh-rate            refresh-rate
     :background-queue-policy background-queue-policy}))
