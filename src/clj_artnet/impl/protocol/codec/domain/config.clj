;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.codec.domain.config
  "Encode/decode for Config family packets: ArtInput, ArtAddress, ArtIpProg, ArtIpProgReply."
  (:require [clj-artnet.impl.protocol.codec.constants :as const]
            [clj-artnet.impl.protocol.codec.domain.common :as common]
            [clj-artnet.impl.protocol.codec.primitives :as prim])
  (:import (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(defn- normalize-disabled-flags
  "Normalize disabled flags from various input formats."
  [disabled disable-ports]
  (cond (seq disabled) (->> (concat disabled (repeat false))
                            (take 4)
                            (mapv boolean))
        (seq disable-ports) (let [indexes (set (map #(int (or % 0))
                                                    disable-ports))]
                              (mapv #(contains? indexes %) (range 4)))
        :else nil))

(defn- normalize-input-bytes
  "Normalize input bytes from various formats."
  [inputs disabled]
  (let [source (cond (seq inputs) inputs
                     (some? disabled)
                     (map #(if (true? %) const/artinput-disable-bit 0) disabled)
                     :else (repeat 0))]
    (->> (concat source (repeat 0))
         (take 4)
         (mapv #(bit-and (int (or % 0)) 0xFF)))))

(defn artinput-packet
  "Construct a stateless ArtInput map from higher-level options.

   Options:
   * `:bind-index`  -> node BindIndex (default 1)
   * `:num-ports`   -> total active ports (default 0)
   * `:disabled`    -> up to 4 booleans in port order
   * `:disable-ports` -> collection of port indexes to disable
   * `:inputs`      -> raw Input[4] bytes (overrides booleans)

   Returns a packet map suitable for `encode`."
  [{:keys [bind-index num-ports disabled disable-ports inputs],
    :or   {bind-index 1, num-ports 0}}]
  (let [flags (normalize-disabled-flags disabled disable-ports)
        input-bytes (normalize-input-bytes inputs flags)]
    (cond-> {:op         :artinput,
             :bind-index bind-index,
             :num-ports  num-ports,
             :inputs     input-bytes}
            flags (assoc :disabled flags))))

(defn encode-artinput!
  "Encode ArtInput packet into a buffer."
  [^ByteBuffer buf {:keys [bind-index num-ports inputs disabled]}]
  (let [bind (bit-and (int (or bind-index 1)) 0xFF)
        ports (bit-and (int (or num-ports 0)) 0xFFFF)
        hi (bit-and (unsigned-bit-shift-right ports 8) 0xFF)
        lo (bit-and ports 0xFF)
        input-bytes (normalize-input-bytes inputs disabled)
        ^ByteBuffer target (prim/prepare-target buf const/artinput-length)]
    (.clear target)
    (doto target
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :artinput))
      (prim/put-u16-be! const/protocol-version)
      (.put (byte 0))
      (.put (unchecked-byte bind))
      (.put (unchecked-byte hi))
      (.put (unchecked-byte lo))
      (prim/put-bytes! input-bytes))
    (.flip target)))

(defn decode-artinput
  "Decode ArtInput packet from the buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artinput-length)
    (throw (ex-info "Truncated ArtInput" {:limit (.limit buf)})))
  (let [protocol (prim/safe-uint16-be buf 10)
        bind-index (prim/safe-ubyte buf 13)
        num-ports (prim/safe-uint16-be buf 14)
        inputs (mapv (fn [idx] (prim/safe-ubyte buf (+ 16 idx))) (range 4))
        disabled (mapv #(pos? (bit-and % const/artinput-disable-bit)) inputs)]
    {:op               :artinput,
     :protocol-version protocol,
     :bind-index       bind-index,
     :num-ports        num-ports,
     :inputs           inputs,
     :disabled         disabled}))

(defn decode-artaddress
  "Decode ArtAddress packet from buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artaddress-length)
    (throw (ex-info "Truncated ArtAddress" {:limit (.limit buf)})))
  {:op               :artaddress,
   :protocol-version (prim/safe-uint16-be buf 10),
   :net-switch       (prim/safe-ubyte buf 12),
   :bind-index       (prim/safe-ubyte buf 13),
   :short-name       (prim/read-ascii buf 14 18),
   :long-name        (prim/read-ascii buf 32 64),
   :sw-in            (prim/read-octets buf 96 4),
   :sw-out           (prim/read-octets buf 100 4),
   :sub-switch       (prim/safe-ubyte buf 104),
   :acn-priority     (prim/safe-ubyte buf 105),
   :command          (prim/safe-ubyte buf 106)})

(defn decode-artipprog
  "Decode ArtIpProg packet from buffer."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artipprog-min-length)
    (throw (ex-info "Truncated ArtIpProg" {:limit (.limit buf)})))
  (let [port-hi (prim/safe-ubyte buf 24)
        port-lo (prim/safe-ubyte buf 25)
        prog-port (bit-or (bit-shift-left port-hi 8) port-lo)]
    {:op               :artipprog,
     :protocol-version (prim/safe-uint16-be buf 10),
     :command          (prim/safe-ubyte buf 14),
     :prog-ip          (prim/read-octets buf 16 4),
     :prog-sm          (prim/read-octets buf 20 4),
     :prog-port        prog-port,
     :prog-gateway     (prim/read-octets buf 26 4)}))

(defn encode-artipprogreply!
  "Encode ArtIpProgReply packet into a buffer."
  [^ByteBuffer buf
   {:keys [ip subnet-mask gateway port dhcp?],
    :or   {subnet-mask [255 0 0 0], gateway [0 0 0 0], port 0x1936}}]
  (let [ip-bytes (common/normalize-ip ip)
        mask-bytes (common/normalize-ip subnet-mask)
        gateway-bytes (common/normalize-ip gateway)
        status (if dhcp? 0x40 0)
        port-val (bit-and (int port) 0xFFFF)
        port-hi (bit-and (unsigned-bit-shift-right port-val 8) 0xFF)
        port-lo (bit-and port-val 0xFF)
        ^ByteBuffer target (prim/prepare-target buf
                                                const/artipprogreply-length)]
    (.clear target)
    (doto target
      (.put ^"[B" const/artnet-id-bytes)
      (prim/put-u16-le! (const/keyword->opcode :artipprogreply))
      (prim/put-u16-be! const/protocol-version)
      (.put (byte 0))
      (.put (byte 0))
      (.put (byte 0))
      (.put (byte 0))
      (prim/put-bytes! ip-bytes)
      (prim/put-bytes! mask-bytes)
      (.put (unchecked-byte port-hi))
      (.put (unchecked-byte port-lo))
      (.put (unchecked-byte status))
      (.put (byte 0))
      (prim/put-bytes! gateway-bytes)
      (.put (byte 0))
      (.put (byte 0)))
    (.flip target)))
