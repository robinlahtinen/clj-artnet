;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.codec.domain.dmx
  "Encode/decode for DMX family packets: ArtDmx, ArtNzs, ArtVlc, ArtSync.
   These are the hot-path packets that use flyweight types for zero allocation."
  (:require [clj-artnet.impl.protocol.codec.constants :as const]
            [clj-artnet.impl.protocol.codec.domain.common :as common]
            [clj-artnet.impl.protocol.codec.primitives :as prim]
            [clj-artnet.impl.protocol.codec.types :as types])
  (:import (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(defn encode-artdmx!
  "Encode ArtDmx packet into buffer."
  [^ByteBuffer buf
   {:keys [sequence physical net sub-net universe data],
    :or   {sequence 0, physical 0, net 0, sub-net 0, universe 0}}]
  (let [payload (prim/as-buffer data)
        length (.remaining payload)]
    (when (or (neg? length) (> length const/max-dmx-channels))
      (throw (ex-info "Invalid ArtDmx payload length" {:length length})))
    (.clear buf)
    (doto buf
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :artdmx))
      (prim/put-u16-be! const/protocol-version)
      (.put (unchecked-byte sequence))
      (.put (unchecked-byte physical))
      (.put (unchecked-byte (bit-or (bit-shift-left (bit-and sub-net 0x0F) 4)
                                    (bit-and universe 0x0F))))
      (.put (unchecked-byte net))
      (prim/put-u16-be! length))
    (.put buf payload)
    (.flip buf)))

(defn decode-artdmx
  "Decode ArtDmx packet from buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artdmx-header-size)
    (throw (ex-info "Truncated ArtDmx" {:limit (.limit buf)})))
  (let [length (prim/uint16-be buf 16)]
    (when (or (neg? length) (> length const/max-dmx-channels))
      (throw (ex-info "DMX payload exceeds 512 bytes" {:length length})))
    (when (> (+ const/artdmx-header-size length) (.limit buf))
      (throw (ex-info "Incomplete ArtDmx payload"
                      {:expected (+ const/artdmx-header-size length),
                       :limit    (.limit buf)})))
    (let [sequence (prim/ubyte (.get buf 12))
          physical (prim/ubyte (.get buf 13))
          sub-uni (prim/ubyte (.get buf 14))
          net (prim/ubyte (.get buf 15))
          sub-net (bit-and (bit-shift-right sub-uni 4) 0x0F)
          universe (bit-and sub-uni 0x0F)
          port-address (common/compose-port-address net sub-net universe)
          ^ByteBuffer view (.asReadOnlyBuffer buf)]
      (.position view 0)
      (.limit view (int (.limit buf)))
      (types/->ArtDmxPacket view
                            0
                            length
                            sequence
                            physical
                            net
                            sub-net
                            universe
                            port-address
                            nil))))

(defn encode-artnzs!
  "Encode ArtNzs packet into buffer."
  [^ByteBuffer buf
   {:keys [sequence start-code net sub-net universe data],
    :or   {sequence 0, net 0, sub-net 0, universe 0}}]
  (let [payload (prim/as-buffer data)
        length (.remaining payload)
        start-code (or start-code
                       (throw (ex-info "ArtNzs requires :start-code"
                                       {:packet {:op :artnzs}})))
        start-code (bit-and (int start-code) 0xFF)]
    (when (or (<= length 0) (> length const/max-dmx-channels))
      (throw (ex-info "Invalid ArtNzs payload length"
                      {:length length, :min 1, :max const/max-dmx-channels})))
    (when (or (zero? start-code) (= start-code 0xCC))
      (throw (ex-info "ArtNzs start code must be non-zero and not RDM"
                      {:start-code start-code})))
    (.clear buf)
    (doto buf
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :artnzs))
      (prim/put-u16-be! const/protocol-version)
      (.put (unchecked-byte sequence))
      (.put (unchecked-byte start-code))
      (.put (unchecked-byte (bit-or (bit-shift-left (bit-and sub-net 0x0F) 4)
                                    (bit-and universe 0x0F))))
      (.put (unchecked-byte net))
      (prim/put-u16-be! length))
    (.put buf payload)
    (.flip buf)))

(defn encode-artvlc!
  "Encode ArtVlc packet (transmitted as ArtNzs with start code 0x91)."
  [^ByteBuffer buf
   {:keys [sequence net sub-net universe vlc],
    :or   {sequence 0, net 0, sub-net 0, universe 0},
    :as   packet}]
  (when-not vlc
    (throw (ex-info "ArtVlc packet requires :vlc details"
                    {:packet (dissoc packet :vlc)})))
  (let [{:keys [payload transaction slot-address depth frequency modulation
                payload-language beacon-repeat reserved],
         :as   vlc-info}
        vlc
        payload (or payload
                    (throw (ex-info "ArtVlc requires :vlc/:payload"
                                    {:packet (dissoc packet :vlc)})))
        payload-bytes (prim/payload-bytes payload)
        payload-count (alength payload-bytes)
        _ (when (> payload-count const/artvlc-max-payload)
            (throw (ex-info "ArtVlc payload exceeds maximum supported length"
                            {:length payload-count,
                             :max    const/artvlc-max-payload})))
        total-length (+ const/artvlc-header-size payload-count)
        declared-count (:payload-count vlc-info)
        _ (when (and declared-count (not= (int declared-count) payload-count))
            (throw (ex-info "ArtVlc :payload-count does not match payload bytes"
                            {:declared declared-count, :actual payload-count})))
        checksum (prim/sum-bytes-16 payload-bytes)
        flags-byte (common/encode-vlc-flags vlc-info)
        transaction (bit-and (or transaction 0) 0xFFFF)
        slot-address (bit-and (or slot-address 0) 0xFFFF)
        depth (bit-and (or depth 0) 0xFF)
        frequency (bit-and (or frequency 0) 0xFFFF)
        modulation (bit-and (or modulation 0) 0xFFFF)
        payload-language (bit-and (or payload-language 0) 0xFFFF)
        beacon-repeat (bit-and (or beacon-repeat 0) 0xFFFF)
        reserved (bit-and (or reserved 0) 0xFF)
        ^ByteBuffer target
        (prim/prepare-target buf (+ const/artnzs-header-size total-length))]
    (.clear target)
    (doto target
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :artnzs))
      (prim/put-u16-be! const/protocol-version)
      (.put (unchecked-byte sequence))
      (.put (unchecked-byte const/artvlc-start-code))
      (.put (unchecked-byte (bit-or (bit-shift-left (bit-and sub-net 0x0F) 4)
                                    (bit-and universe 0x0F))))
      (.put (unchecked-byte net))
      (prim/put-u16-be! total-length)
      (.put (unchecked-byte 0x41))
      (.put (unchecked-byte 0x4C))
      (.put (unchecked-byte 0x45))
      (.put (unchecked-byte flags-byte))
      (prim/put-u16-be! transaction)
      (prim/put-u16-be! slot-address)
      (prim/put-u16-be! payload-count)
      (prim/put-u16-be! checksum)
      (.put (unchecked-byte reserved))
      (.put (unchecked-byte depth))
      (prim/put-u16-be! frequency)
      (prim/put-u16-be! modulation)
      (prim/put-u16-be! payload-language)
      (prim/put-u16-be! beacon-repeat))
    (.put target ^bytes payload-bytes)
    (.flip target)))

(defn- artvlc-frame?
  "Check if buffer contains VLC frame (based on start code and magic bytes)."
  [^ByteBuffer buf length start-code]
  (and (= start-code const/artvlc-start-code)
       (>= length const/artvlc-header-size)
       (every? true?
               (map-indexed
                 (fn [idx expected]
                   (= expected
                      (prim/ubyte
                        (.get buf (int (+ const/artnzs-header-size idx))))))
                 const/artvlc-magic))))

(defn- decode-artvlc
  "Decode VLC data from ArtNzs frame."
  [^ByteBuffer buf ^ByteBuffer view length sequence net sub-net universe
   port-address]
  (let [base const/artnzs-header-size
        flags (prim/ubyte (.get buf (int (+ base 3))))
        transaction (prim/uint16-be buf (+ base 4))
        slot-address (prim/uint16-be buf (+ base 6))
        payload-count (prim/uint16-be buf (+ base 8))
        payload-checksum (prim/uint16-be buf (+ base 10))
        reserved (prim/ubyte (.get buf (int (+ base 12))))
        depth (prim/ubyte (.get buf (int (+ base 13))))
        frequency (prim/uint16-be buf (+ base 14))
        modulation (prim/uint16-be buf (+ base 16))
        payload-language (prim/uint16-be buf (+ base 18))
        beacon-repeat (prim/uint16-be buf (+ base 20))
        available (- length const/artvlc-header-size)]
    (when (< available 0)
      (throw (ex-info "ArtVlc header exceeds data length" {:length length})))
    (when (not= payload-count available)
      (throw (ex-info "ArtVlc payload-count mismatch"
                      {:declared payload-count, :actual available})))
    (let [payload-start (+ base const/artvlc-header-size)
          payload-end (+ payload-start payload-count)
          ^ByteBuffer payload-view (.duplicate buf)]
      (.position payload-view (int payload-start))
      (.limit payload-view (int payload-end))
      (let [^ByteBuffer payload-slice (.asReadOnlyBuffer (.slice payload-view))
            checksum (prim/sum-bytes-16 (prim/payload-bytes payload-slice))]
        (when (not= checksum payload-checksum)
          (throw (ex-info "ArtVlc payload checksum mismatch"
                          {:expected payload-checksum, :actual checksum})))
        (let [vlc {:flags            flags,
                   :ieee?            (common/flag-set? flags const/artvlc-flag-ieee),
                   :reply?           (common/flag-set? flags const/artvlc-flag-reply),
                   :beacon?          (common/flag-set? flags const/artvlc-flag-beacon),
                   :transaction      transaction,
                   :slot-address     slot-address,
                   :payload-count    payload-count,
                   :payload-checksum payload-checksum,
                   :reserved         reserved,
                   :depth            depth,
                   :frequency        frequency,
                   :modulation       modulation,
                   :payload-language payload-language,
                   :beacon-repeat    beacon-repeat,
                   :payload          payload-slice}]
          (types/->ArtNzsPacket :artvlc
                                view
                                0
                                length
                                sequence
                                const/artvlc-start-code
                                net
                                sub-net
                                universe
                                port-address
                                vlc
                                nil))))))

(defn decode-artnzs
  "Decode ArtNzs packet from buffer (including VLC detection)."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artnzs-header-size)
    (throw (ex-info "Truncated ArtNzs" {:limit (.limit buf)})))
  (let [length (prim/uint16-be buf 16)]
    (when (or (<= length 0) (> length const/max-dmx-channels))
      (throw (ex-info "ArtNzs payload length out of range"
                      {:length length, :min 1, :max const/max-dmx-channels})))
    (when (> (+ const/artnzs-header-size length) (.limit buf))
      (throw (ex-info "Incomplete ArtNzs payload"
                      {:expected (+ const/artnzs-header-size length),
                       :limit    (.limit buf)})))
    (let [sequence (prim/ubyte (.get buf 12))
          start-code (prim/ubyte (.get buf 13))
          sub-uni (prim/ubyte (.get buf 14))
          net (prim/ubyte (.get buf 15))
          sub-net (bit-and (bit-shift-right sub-uni 4) 0x0F)
          universe (bit-and sub-uni 0x0F)
          port-address (common/compose-port-address net sub-net universe)
          ^ByteBuffer view (.asReadOnlyBuffer buf)]
      (.position view 0)
      (.limit view (int (.limit buf)))
      (if (artvlc-frame? buf length start-code)
        (decode-artvlc buf
                       view
                       length
                       sequence
                       net
                       sub-net
                       universe
                       port-address)
        (types/->ArtNzsPacket :artnzs
                              view
                              0
                              length
                              sequence
                              start-code
                              net
                              sub-net
                              universe
                              port-address
                              nil
                              nil)))))

(defn decode-artsync
  "Decode ArtSync packet from buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) 14)
    (throw (ex-info "Truncated ArtSync" {:limit (.limit buf)})))
  {:op   :artsync,
   :aux1 (prim/ubyte (.get buf 12)),
   :aux2 (prim/ubyte (.get buf 13))})
