;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.codec.domain.diag
  "Encode/decode for Diagnostic family packets: ArtDiagData, ArtCommand, ArtTrigger, ArtTimeCode."
  (:require
    [clj-artnet.impl.protocol.codec.constants :as const]
    [clj-artnet.impl.protocol.codec.domain.common :as common]
    [clj-artnet.impl.protocol.codec.primitives :as prim])
  (:import
    (java.nio ByteBuffer)
    (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(defn encode-artdiagdata!
  "Encode ArtDiagData packet into a buffer."
  [^ByteBuffer buf
   {:keys [priority logical-port text message]
    :or   {priority 0x10, logical-port 0}}]
  (let [content (or text message "")
        encoded (.getBytes ^String content StandardCharsets/US_ASCII)
        ^bytes payload (prim/clamp-bytes encoded 511)
        payload-length (min 512 (inc (alength payload)))
        total-length (+ const/artdiagdata-header-size payload-length)
        ^ByteBuffer target (prim/prepare-target buf total-length)
        copy-length (dec payload-length)]
    (.clear target)
    (doto target
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :artdiagdata))
      (prim/put-u16-be! const/protocol-version)
      (.put (unchecked-byte 0))                             ; filler
      (.put (unchecked-byte (bit-and priority 0xFF)))
      (.put (unchecked-byte logical-port))
      (.put (unchecked-byte 0))                             ; filler
      (prim/put-u16-be! payload-length))
    (.put target payload 0 copy-length)
    (.put target (unchecked-byte 0))                        ; null terminator
    (.flip target)
    target))

(defn decode-artdiagdata
  "Decode ArtDiagData packet from a buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artdiagdata-header-size)
    (throw (ex-info "Truncated ArtDiagData" {:limit (.limit buf)})))
  (let [priority (prim/ubyte (.get buf 13))
        logical-port (prim/ubyte (.get buf 14))
        length (prim/uint16-be buf 16)
        expected (+ const/artdiagdata-header-size length)]
    (when (> expected (.limit buf))
      (throw (ex-info "Incomplete ArtDiagData payload"
                      {:expected expected, :limit (.limit buf)})))
    (let [^bytes payload (byte-array length)]
      (.position buf const/artdiagdata-header-size)
      (.get buf payload 0 length)
      (let [text-length (loop [idx 0]
                          (cond (>= idx length) length
                                (zero? (aget payload idx)) idx
                                :else (recur (inc idx))))
            message
            (String. payload 0 (int text-length) StandardCharsets/US_ASCII)]
        {:op           :artdiagdata
         :priority     priority
         :logical-port logical-port
         :length       length
         :text         message}))))

(defn- artcommand-payload-bytes
  "Prepare ArtCommand payload bytes from packet."
  ^bytes [{:keys [text data]}]
  (cond (some? text)
        (let [^String value (or text "")
              raw (.getBytes value StandardCharsets/US_ASCII)
              copy-length (min (alength raw) (dec const/artcommand-max-bytes))
              payload-length (inc copy-length)
              payload (byte-array payload-length)]
          (when (> (alength raw) (dec const/artcommand-max-bytes))
            (throw (ex-info "ArtCommand text exceeds maximum length"
                            {:max    (dec const/artcommand-max-bytes)
                             :length (alength raw)})))
          (System/arraycopy raw 0 payload 0 copy-length)
          (aset payload copy-length (byte 0))
          payload)
        data (let [bytes (prim/payload-bytes data)
                   _ (when (> (alength bytes) const/artcommand-max-bytes)
                       (throw (ex-info "ArtCommand data exceeds maximum length"
                                       {:max    const/artcommand-max-bytes
                                        :length (alength bytes)})))]
               (prim/ensure-null-terminated bytes const/artcommand-max-bytes))
        :else (byte-array 1)))

(defn encode-artcommand!
  "Encode ArtCommand packet into buffer."
  [^ByteBuffer buf {:keys [esta-man text data]}]
  (let [payload (artcommand-payload-bytes {:text text, :data data})
        payload-length (alength ^bytes payload)
        esta (bit-and (int (or esta-man 0xFFFF)) 0xFFFF)
        ^ByteBuffer target (prim/prepare-target buf
                                                (+ const/artcommand-header-size
                                                   payload-length))]
    (.clear target)
    (doto target
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :artcommand))
      (prim/put-u16-be! const/protocol-version)
      (prim/put-u16-be! esta)
      (prim/put-u16-be! payload-length))
    (.put target ^bytes payload 0 payload-length)
    (.flip target)))

(defn decode-artcommand
  "Decode ArtCommand packet from buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artcommand-header-size)
    (throw (ex-info "Truncated ArtCommand" {:limit (.limit buf)})))
  (let [protocol (prim/safe-uint16-be buf 10)
        esta (prim/safe-uint16-be buf 12)
        length (prim/safe-uint16-be buf 14)]
    (when (> length const/artcommand-max-bytes)
      (throw (ex-info "ArtCommand length exceeds 512 bytes"
                      {:length length, :max const/artcommand-max-bytes})))
    (let [required (+ const/artcommand-header-size length)]
      (when (> required (.limit buf))
        (throw (ex-info "Incomplete ArtCommand payload"
                        {:required required, :limit (.limit buf)})))
      (let [^ByteBuffer view (.duplicate buf)]
        (.position view const/artcommand-header-size)
        (.limit view (int required))
        (let [payload (.asReadOnlyBuffer (.slice view))
              ^bytes bytes (byte-array length)]
          (.get (.duplicate payload) bytes)
          (let [text-length (loop [idx 0]
                              (cond (>= idx length) length
                                    (zero? (aget bytes idx)) idx
                                    :else (recur (inc idx))))
                text (String. ^bytes bytes
                              0
                              (int text-length)
                              StandardCharsets/US_ASCII)]
            {:op               :artcommand
             :protocol-version protocol
             :esta-man         esta
             :length           length
             :text             text
             :payload          payload
             :data             payload}))))))

(defn- trigger-payload-source
  "Normalize trigger payload to bytes or ByteBuffer."
  [payload]
  (cond (nil? payload) (byte-array 0)
        (bytes? payload) payload
        (instance? ByteBuffer payload) payload
        (sequential? payload) (byte-array (map #(unchecked-byte (int %))
                                               payload))
        :else (throw (ex-info
                       "Unsupported ArtTrigger payload container"
                       {:type (class payload)
                        :hint
                        "Provide bytes, ByteBuffer, or a seq of octets"}))))

(defn encode-arttrigger!
  "Encode ArtTrigger packet into buffer."
  [^ByteBuffer buf {:keys [oem sub-key data payload], :as packet}]
  (let [key-byte (common/trigger-key-byte packet)
        sub (bit-and (int (or sub-key 0)) 0xFF)
        oem (bit-and (int (or oem 0xFFFF)) 0xFFFF)
        payload-source (trigger-payload-source (or data payload))
        payload-bytes (prim/payload-bytes payload-source)
        payload-length (alength ^bytes payload-bytes)]
    (when (> payload-length const/arttrigger-max-data)
      (throw (ex-info "ArtTrigger payload exceeds maximum length"
                      {:length payload-length
                       :max    const/arttrigger-max-data})))
    (.clear buf)
    (doto buf
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :arttrigger))
      (prim/put-u16-be! const/protocol-version)
      (.put (byte 0))
      (.put (byte 0))
      (prim/put-u16-be! oem)
      (.put (unchecked-byte key-byte))
      (.put (unchecked-byte sub)))
    (when (pos? payload-length)
      (.put buf ^bytes payload-bytes 0 payload-length))
    (dotimes [_ (- const/arttrigger-max-data payload-length)]
      (.put buf (byte 0)))
    (.flip buf)))

(defn decode-arttrigger
  "Decode ArtTrigger packet from buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/arttrigger-header-size)
    (throw (ex-info "Truncated ArtTrigger" {:limit (.limit buf)})))
  (let [protocol (prim/safe-uint16-be buf 10)
        oem (prim/safe-uint16-be buf 14)
        key (prim/safe-ubyte buf 16)
        sub-key (prim/safe-ubyte buf 17)
        available (- (.limit buf) const/arttrigger-header-size)
        payload-length (max 0 (min const/arttrigger-max-data available))
        view (.duplicate buf)]
    (.position view const/arttrigger-header-size)
    (.limit view (int (+ const/arttrigger-header-size payload-length)))
    (let [payload (.asReadOnlyBuffer (.slice view))]
      {:op               :arttrigger
       :protocol-version protocol
       :oem              oem
       :key              key
       :key-type         (get const/arttrigger-key->keyword key)
       :sub-key          sub-key
       :data-length      payload-length
       :payload          payload
       :data             payload})))

(defn encode-arttimecode!
  "Encode ArtTimeCode packet into a buffer."
  [^ByteBuffer buf
   {:keys [stream-id frames seconds minutes hours type]
    :or   {stream-id 0, frames 0, seconds 0, minutes 0, hours 0, type 0}}]
  (let [^ByteBuffer target (prim/prepare-target buf const/arttimecode-length)]
    (.clear target)
    (doto target
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :arttimecode))
      (prim/put-u16-be! const/protocol-version)
      (.put (byte 0))
      (.put (unchecked-byte stream-id))
      (.put (unchecked-byte frames))
      (.put (unchecked-byte seconds))
      (.put (unchecked-byte minutes))
      (.put (unchecked-byte hours))
      (.put (unchecked-byte type)))
    (.flip target)))

(defn decode-arttimecode
  "Decode ArtTimeCode packet from buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/arttimecode-length)
    (throw (ex-info "Truncated ArtTimeCode" {:limit (.limit buf)})))
  {:op        :arttimecode
   :proto     (prim/safe-uint16-be buf 10)
   :unused    (prim/ubyte (.get buf 12))
   :stream-id (prim/ubyte (.get buf 13))
   :frames    (prim/ubyte (.get buf 14))
   :seconds   (prim/ubyte (.get buf 15))
   :minutes   (prim/ubyte (.get buf 16))
   :hours     (prim/ubyte (.get buf 17))
   :type      (prim/ubyte (.get buf 18))})
