;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.codec.domain.firmware
  "Encode/decode for Firmware family packets: ArtFirmwareMaster, ArtFirmwareReply."
  (:require
    [clj-artnet.impl.protocol.codec.constants :as const]
    [clj-artnet.impl.protocol.codec.primitives :as prim])
  (:import
    (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(defn- firmware-type-info
  "Get firmware type info from code."
  [code]
  (get const/firmware-master-type->info
       code
       {:block-type :unknown, :transfer :firmware, :stage :unknown}))

(defn decode-artfirmwaremaster
  "Decode ArtFirmwareMaster packet from buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artfirmwaremaster-header-size)
    (throw (ex-info "Truncated ArtFirmwareMaster" {:limit (.limit buf)})))
  (let [type-code (prim/safe-ubyte buf 14)
        block-id (prim/safe-ubyte buf 15)
        firmware-length (long (bit-or
                                (bit-shift-left (prim/safe-ubyte buf 16) 24)
                                (bit-shift-left (prim/safe-ubyte buf 17) 16)
                                (bit-shift-left (prim/safe-ubyte buf 18) 8)
                                (prim/safe-ubyte buf 19)))
        info (firmware-type-info type-code)
        total (.limit buf)
        payload-length (max 0 (- total const/artfirmwaremaster-header-size))]
    (when (> payload-length const/artfirmwaremaster-max-bytes)
      (throw (ex-info "ArtFirmwareMaster data exceeds maximum block size"
                      {:payload payload-length
                       :max     const/artfirmwaremaster-max-bytes})))
    (let [data-view (.duplicate buf)]
      (.position data-view const/artfirmwaremaster-header-size)
      (.limit data-view total)
      (let [slice (.asReadOnlyBuffer (.slice data-view))]
        {:op              :artfirmwaremaster
         :type-code       type-code
         :block-type      (:block-type info)
         :transfer        (:transfer info)
         :stage           (:stage info)
         :block-id        block-id
         :firmware-length firmware-length
         :data-length     payload-length
         :data            slice}))))

(defn encode-artfirmwarereply!
  "Encode ArtFirmwareReply packet into a buffer."
  [^ByteBuffer buf {:keys [status status-code], :as packet}]
  (let [code (or (const/firmware-reply-status->code status)
                 (when status-code (bit-and status-code 0xFF)))]
    (when-not (contains? const/firmware-reply-code->status code)
      (throw (ex-info "Unsupported ArtFirmwareReply status" {:packet packet})))
    (.clear buf)
    (doto buf
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :artfirmwarereply))
      (prim/put-u16-be! const/protocol-version)
      (.put (byte 0))
      (.put (byte 0))
      (.put (unchecked-byte code)))
    (dotimes [_ 21] (.put buf (byte 0)))
    (.flip buf)))

(defn decode-artfirmwarereply
  "Decode ArtFirmwareReply packet from the buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artfirmwarereply-length)
    (throw (ex-info "Truncated ArtFirmwareReply" {:limit (.limit buf)})))
  (let [code (prim/safe-ubyte buf 14)
        status (get const/firmware-reply-code->status code :unknown)]
    {:op :artfirmwarereply, :status status, :status-code code}))
