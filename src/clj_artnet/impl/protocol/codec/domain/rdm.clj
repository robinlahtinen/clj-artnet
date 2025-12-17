;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.codec.domain.rdm
  "Encode/decode for RDM family packets: ArtRdm, ArtRdmSub, ArtTodRequest, ArtTodData, ArtTodControl."
  (:require
    [clj-artnet.impl.protocol.codec.constants :as const]
    [clj-artnet.impl.protocol.codec.domain.common :as common]
    [clj-artnet.impl.protocol.codec.primitives :as prim])
  (:import
    (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(defn- rdmsub-values->bytes
  "Convert RDM sub values to a byte array."
  ^bytes [values]
  (let [cnt (count values)
        dest (byte-array (* 2 cnt))]
    (doseq [[idx value] (map-indexed vector values)]
      (let [v (bit-and (int value) 0xFFFF)
            offset (* idx 2)]
        (aset dest offset (unchecked-byte (unsigned-bit-shift-right v 8)))
        (aset dest (inc offset) (unchecked-byte (bit-and v 0xFF)))))
    dest))

(defn- rdmsub-payload-bytes
  "Prepare ArtRdmSub payload bytes."
  ^bytes [{:keys [values data]} command-class sub-count]
  (let [^bytes raw (cond (seq values) (rdmsub-values->bytes values)
                         data (prim/payload-bytes data)
                         :else (byte-array 0))
        length (alength raw)
        expected (case (int command-class)
                   0x20 0
                   0x31 0
                   0x21 (* 2 (max 0 sub-count))
                   0x30 (* 2 (max 0 sub-count))
                   nil)]
    (when (pos? length)
      (when (odd? length)
        (throw (ex-info "ArtRdmSub payload must be 16-bit aligned"
                        {:length length}))))
    (when (and expected (not= length expected))
      (throw (ex-info "ArtRdmSub payload length does not match command class"
                      {:command-class command-class
                       :expected      expected
                       :actual        length
                       :sub-count     sub-count})))
    (when (> length const/artrdmsub-max-bytes)
      (throw (ex-info "ArtRdmSub payload exceeds maximum length"
                      {:length length, :max const/artrdmsub-max-bytes})))
    raw))

(defn encode-artrdm!
  "Encode ArtRdm packet into buffer."
  [^ByteBuffer buf
   {:keys [rdm-version fifo-available fifo-max net command address rdm-packet]
    :or   {rdm-version 1, fifo-available 0, fifo-max 0, net 0, address 0}
    :as   packet}]
  (when-not (some? command)
    (throw (ex-info "ArtRdm packet requires :command"
                    {:packet (dissoc packet :rdm-packet)})))
  (when-not rdm-packet
    (throw (ex-info "ArtRdm packet requires :rdm-packet"
                    {:packet (dissoc packet :rdm-packet)})))
  (let [rdm-bytes (prim/payload-bytes rdm-packet)
        payload-length (alength ^bytes rdm-bytes)
        ^ByteBuffer target
        (prim/prepare-target buf (+ const/artrdm-header-size payload-length))]
    (.clear target)
    (doto target
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :artrdm))
      (prim/put-u16-be! const/protocol-version)
      (.put (unchecked-byte (bit-and (int rdm-version) 0xFF))))
    (dotimes [_ 6] (.put target (byte 0)))
    (doto target
      (.put (unchecked-byte (bit-and (int fifo-available) 0xFF)))
      (.put (unchecked-byte (bit-and (int fifo-max) 0xFF)))
      (.put (unchecked-byte (bit-and (int net) 0x7F)))
      (.put (unchecked-byte (bit-and (int command) 0xFF)))
      (.put (unchecked-byte (bit-and (int address) 0xFF))))
    (when (pos? payload-length) (.put target ^bytes rdm-bytes 0 payload-length))
    (.flip target)))

(defn decode-artrdm
  "Decode ArtRdm packet from buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artrdm-header-size)
    (throw (ex-info "Truncated ArtRdm" {:limit (.limit buf)})))
  (let [payload-length (max 0 (- (.limit buf) const/artrdm-header-size))
        view (.duplicate buf)]
    (.position view const/artrdm-header-size)
    (.limit view (.limit buf))
    (let [payload (.asReadOnlyBuffer (.slice view))]
      {:op             :artrdm
       :rdm-version    (prim/safe-ubyte buf 12)
       :fifo-available (prim/safe-ubyte buf 19)
       :fifo-max       (prim/safe-ubyte buf 20)
       :net            (prim/safe-ubyte buf 21)
       :command        (prim/safe-ubyte buf 22)
       :address        (prim/safe-ubyte buf 23)
       :payload-length payload-length
       :rdm-packet     payload})))

(defn encode-artrdmsub!
  "Encode ArtRdmSub packet into buffer."
  [^ByteBuffer buf
   {:keys [rdm-version uid parameter-id sub-device sub-count], :as packet}]
  (let [rdm-ver (bit-and (int (or rdm-version 1)) 0xFF)
        uid-bytes (byte-array (map unchecked-byte (common/normalize-uid uid)))
        command-class (common/rdmsub-command-code packet)
        pid (bit-and (int (or parameter-id 0)) 0xFFFF)
        sub-device (bit-and (int (or sub-device 0)) 0xFFFF)
        sub-count (int (or sub-count 1))
        _ (when (<= sub-count 0)
            (throw (ex-info "ArtRdmSub requires positive :sub-count"
                            {:sub-count sub-count})))
        payload (rdmsub-payload-bytes packet command-class sub-count)
        payload-length (alength ^bytes payload)
        ^ByteBuffer target (prim/prepare-target buf
                                                (+ const/artrdmsub-header-size
                                                   payload-length))]
    (.clear target)
    (doto target
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :artrdmsub))
      (prim/put-u16-be! const/protocol-version)
      (.put (unchecked-byte rdm-ver))
      (.put (byte 0)))
    (doseq [b uid-bytes] (.put target (unchecked-byte b)))
    (doto target
      (.put (byte 0))
      (.put (unchecked-byte command-class))
      (prim/put-u16-be! pid)
      (prim/put-u16-be! sub-device)
      (prim/put-u16-be! sub-count)
      (.put (byte 0))
      (.put (byte 0))
      (.put (byte 0))
      (.put (byte 0)))
    (when (pos? payload-length) (.put target ^bytes payload 0 payload-length))
    (.flip target)))

(defn decode-artrdmsub
  "Decode ArtRdmSub packet from buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artrdmsub-header-size)
    (throw (ex-info "Truncated ArtRdmSub" {:limit (.limit buf)})))
  (let [protocol (prim/safe-uint16-be buf 10)
        rdm-ver (prim/safe-ubyte buf 12)
        uid (prim/read-octets buf 14 6)
        command-class (prim/safe-ubyte buf 21)
        pid (prim/safe-uint16-be buf 22)
        sub-device (prim/safe-uint16-be buf 24)
        sub-count (prim/safe-uint16-be buf 26)
        _ (when (<= sub-count 0)
            (throw (ex-info "ArtRdmSub :sub-count must be > 0"
                            {:sub-count sub-count})))
        available (max 0 (- (.limit buf) const/artrdmsub-header-size))
        _ (when (odd? available)
            (throw (ex-info "ArtRdmSub payload must be 16-bit aligned"
                            {:length available})))
        expected (case (int command-class)
                   0x20 0
                   0x31 0
                   0x21 (* 2 sub-count)
                   0x30 (* 2 sub-count)
                   available)
        _ (when (not= available expected)
            (throw (ex-info "ArtRdmSub payload length mismatch"
                            {:command-class command-class
                             :expected      expected
                             :actual        available
                             :sub-count     sub-count})))
        ^ByteBuffer view (.duplicate buf)]
    (.position view const/artrdmsub-header-size)
    (.limit view (.limit buf))
    (let [payload (.asReadOnlyBuffer (.slice view))
          ^bytes bytes (byte-array available)]
      (when (pos? available) (.get (.duplicate payload) bytes 0 available))
      (let [values (vec (for [idx (range 0 available 2)]
                          (bit-or
                            (bit-shift-left (bit-and (aget bytes idx) 0xFF) 8)
                            (bit-and (aget bytes (inc idx)) 0xFF))))]
        {:op               :artrdmsub
         :protocol-version protocol
         :rdm-version      rdm-ver
         :uid              uid
         :command-class    command-class
         :command          (get const/rdm-command-class->keyword command-class)
         :parameter-id     pid
         :sub-device       sub-device
         :sub-count        sub-count
         :payload-length   available
         :values           values
         :data             payload}))))

(defn decode-arttodrequest
  "Decode ArtTodRequest packet from buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/arttodrequest-base-length)
    (throw (ex-info "Truncated ArtTodRequest" {:limit (.limit buf)})))
  (let [net (prim/safe-ubyte buf 21)
        command (prim/safe-ubyte buf 22)
        add-count (min const/arttodrequest-max-addresses
                       (prim/safe-ubyte buf 23))
        required (+ const/arttodrequest-base-length add-count)]
    (when (> required (.limit buf))
      (throw (ex-info "Incomplete ArtTodRequest"
                      {:required required, :limit (.limit buf)})))
    (let [addresses (vec (for [idx (range add-count)]
                           (prim/safe-ubyte buf (+ 24 idx))))]
      {:op        :arttodrequest
       :net       net
       :command   command
       :add-count add-count
       :addresses addresses})))

(defn encode-arttoddata!
  "Encode ArtTodData packet into a buffer."
  [^ByteBuffer buf
   {:keys [rdm-version port bind-index net command-response address uid-total
           block-count tod]
    :or   {rdm-version      1
           port             1
           bind-index       1
           net              0
           command-response 0
           address          0
           block-count      0}}]
  (let [uids (mapv common/normalize-uid (or tod []))
        uid-count (count uids)
        _ (when (> uid-count const/max-tod-uids-per-packet)
            (throw (ex-info "Too many UIDs for single ArtTodData packet"
                            {:uid-count uid-count
                             :max       const/max-tod-uids-per-packet})))
        total (or uid-total uid-count)
        payload-bytes (* 6 uid-count)
        ^ByteBuffer target
        (prim/prepare-target buf (+ const/arttoddata-header-size payload-bytes))
        uid-total-hi (bit-and (unsigned-bit-shift-right total 8) 0xFF)
        uid-total-lo (bit-and total 0xFF)]
    (.clear target)
    (doto target
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :arttoddata))
      (prim/put-u16-be! const/protocol-version)
      (.put (unchecked-byte rdm-version))
      (.put (unchecked-byte port))
      ;; six spare bytes
      (.put (byte 0))
      (.put (byte 0))
      (.put (byte 0))
      (.put (byte 0))
      (.put (byte 0))
      (.put (byte 0))
      (.put (unchecked-byte bind-index))
      (.put (unchecked-byte net))
      (.put (unchecked-byte command-response))
      (.put (unchecked-byte address))
      (.put (unchecked-byte uid-total-hi))
      (.put (unchecked-byte uid-total-lo))
      (.put (unchecked-byte block-count))
      (.put (unchecked-byte uid-count)))
    (when (pos? uid-count) (prim/put-uids! target uids))
    (.flip target)))

(defn decode-arttoddata
  "Decode ArtTodData packet from a buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/arttoddata-header-size)
    (throw (ex-info "Truncated ArtTodData" {:limit (.limit buf)})))
  (let [uid-count (prim/safe-ubyte buf 27)
        required (+ const/arttoddata-header-size (* 6 uid-count))]
    (when (> required (.limit buf))
      (throw (ex-info "Incomplete ArtTodData payload"
                      {:required required, :limit (.limit buf)})))
    (let [uids (loop [idx 0
                      acc []
                      offset const/arttoddata-header-size]
                 (if (>= idx uid-count)
                   acc
                   (let [uid (mapv #(prim/safe-ubyte buf (+ offset %))
                                   (range 6))]
                     (recur (inc idx) (conj acc uid) (+ offset 6)))))
          uid-total (bit-or (bit-shift-left (prim/safe-ubyte buf 24) 8)
                            (prim/safe-ubyte buf 25))]
      {:op               :arttoddata
       :rdm-version      (prim/safe-ubyte buf 12)
       :port             (prim/safe-ubyte buf 13)
       :bind-index       (prim/safe-ubyte buf 20)
       :net              (prim/safe-ubyte buf 21)
       :command-response (prim/safe-ubyte buf 22)
       :address          (prim/safe-ubyte buf 23)
       :uid-total        uid-total
       :block-count      (prim/safe-ubyte buf 26)
       :uid-count        uid-count
       :tod              uids})))

(defn decode-arttodcontrol
  "Decode ArtTodControl packet from the buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/arttodcontrol-length)
    (throw (ex-info "Truncated ArtTodControl" {:limit (.limit buf)})))
  {:op      :arttodcontrol
   :net     (prim/safe-ubyte buf 21)
   :command (prim/safe-ubyte buf 22)
   :address (prim/safe-ubyte buf 23)})
