;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.codec.domain.data
  "Encode/decode for Data family packets: ArtDataRequest, ArtDataReply."
  (:require [clj-artnet.impl.protocol.codec.constants :as const]
            [clj-artnet.impl.protocol.codec.domain.common :as common]
            [clj-artnet.impl.protocol.codec.primitives :as prim])
  (:import (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(defn- datareply-payload-bytes
  "Prepare ArtDataReply payload bytes from a packet."
  ^bytes [{:keys [text data]}]
  (cond (some? text)
        (let [^String value (or text "")
              ^bytes ascii (.getBytes value StandardCharsets/US_ASCII)
              length (alength ascii)
              _ (when (> (inc length) const/artdatareply-max-bytes)
                  (throw (ex-info "ArtDataReply text exceeds maximum length"
                                  {:max    (dec const/artdatareply-max-bytes),
                                   :length length})))
              dest (byte-array (inc length))]
          (System/arraycopy ascii 0 dest 0 length)
          dest)
        data (let [source data
                   ^bytes bytes (prim/payload-bytes source)
                   _ (when (> (alength bytes) const/artdatareply-max-bytes)
                       (throw (ex-info
                                "ArtDataReply payload exceeds maximum length"
                                {:max    const/artdatareply-max-bytes,
                                 :length (alength bytes)})))]
               (prim/ensure-null-terminated bytes const/artdatareply-max-bytes))
        :else (byte-array 1)))

(defn encode-artdatarequest!
  "Encode ArtDataRequest packet into buffer."
  [^ByteBuffer buf {:keys [esta-man oem], :as packet}]
  (let [esta (bit-and (int (or esta-man 0)) 0xFFFF)
        oem (bit-and (int (or oem 0)) 0xFFFF)
        request-code (common/resolve-datarequest-code packet)
        ^ByteBuffer target (prim/prepare-target buf
                                                const/artdatarequest-length)]
    (.clear target)
    (doto target
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :artdatarequest))
      (prim/put-u16-be! const/protocol-version)
      (prim/put-u16-be! esta)
      (prim/put-u16-be! oem)
      (prim/put-u16-be! request-code))
    (dotimes [_ 22] (.put target (byte 0)))
    (.flip target)))

(defn decode-artdatarequest
  "Decode ArtDataRequest packet from the buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artdatarequest-length)
    (throw (ex-info "Truncated ArtDataRequest" {:limit (.limit buf)})))
  (let [protocol (prim/safe-uint16-be buf 10)
        esta (prim/safe-uint16-be buf 12)
        oem (prim/safe-uint16-be buf 14)
        request (prim/safe-uint16-be buf 16)]
    {:op               :artdatarequest,
     :protocol-version protocol,
     :esta-man         esta,
     :oem              oem,
     :request          request,
     :request-type     (common/datarequest-type request)}))

(defn encode-artdatareply!
  "Encode ArtDataReply packet into the buffer."
  [^ByteBuffer buf {:keys [esta-man oem text data], :as packet}]
  (let [esta (bit-and (int (or esta-man 0xFFFF)) 0xFFFF)
        oem (bit-and (int (or oem 0xFFFF)) 0xFFFF)
        request-code (common/resolve-datarequest-code packet)
        payload-bytes (datareply-payload-bytes {:text text, :data data})
        payload-length (alength ^bytes payload-bytes)
        ^ByteBuffer target (prim/prepare-target
                             buf
                             (+ const/artdatareply-header-size payload-length))]
    (.clear target)
    (doto target
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :artdatareply))
      (prim/put-u16-be! const/protocol-version)
      (prim/put-u16-be! esta)
      (prim/put-u16-be! oem)
      (prim/put-u16-be! request-code)
      (prim/put-u16-be! payload-length))
    (.put target ^bytes payload-bytes 0 payload-length)
    (.flip target)))

(defn decode-artdatareply
  "Decode ArtDataReply packet from the buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artdatareply-header-size)
    (throw (ex-info "Truncated ArtDataReply" {:limit (.limit buf)})))
  (let [protocol (prim/safe-uint16-be buf 10)
        esta (prim/safe-uint16-be buf 12)
        oem (prim/safe-uint16-be buf 14)
        request (prim/safe-uint16-be buf 16)
        payload-length (prim/safe-uint16-be buf 18)
        _ (when (> payload-length const/artdatareply-max-bytes)
            (throw (ex-info "ArtDataReply payload exceeds maximum length"
                            {:length payload-length,
                             :max    const/artdatareply-max-bytes})))
        required (+ const/artdatareply-header-size payload-length)
        limit (.limit buf)
        _ (when (> required limit)
            (throw (ex-info "Incomplete ArtDataReply payload"
                            {:required required, :limit limit})))
        ^ByteBuffer view (.duplicate buf)]
    (.position view const/artdatareply-header-size)
    (.limit view (int required))
    (let [payload (.asReadOnlyBuffer (.slice view))
          ^bytes bytes (byte-array payload-length)]
      (.get (.duplicate payload) bytes 0 payload-length)
      (let [text-length (loop [idx 0]
                          (cond (>= idx payload-length) payload-length
                                (zero? (aget bytes idx)) idx
                                :else (recur (inc idx))))
            text (String. ^bytes bytes
                          0
                          (int text-length)
                          StandardCharsets/US_ASCII)]
        {:op               :artdatareply,
         :protocol-version protocol,
         :esta-man         esta,
         :oem              oem,
         :request          request,
         :request-type     (common/datarequest-type request),
         :payload-length   payload-length,
         :text             text,
         :data             payload}))))
