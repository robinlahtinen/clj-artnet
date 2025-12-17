;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.codec.domain.common
  "Common utilities shared across domain encode/decode modules."
  (:require
    [clj-artnet.impl.protocol.codec.constants :as const]
    [clj-artnet.impl.protocol.codec.primitives :as prim])
  (:import
    (java.net InetAddress)
    (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(defn compose-port-address
  "Compose a 15-bit port address from Net/Sub-Net/Universe values."
  ^long [net sub-net universe]
  (bit-or (bit-shift-left (bit-and (long net) 0x7F) 8)
          (bit-shift-left (bit-and (long sub-net) 0x0F) 4)
          (bit-and (long universe) 0x0F)))

(defn split-port-address
  "Split a 15-bit port address into {:net :sub-net :universe}."
  [port-address]
  {:net      (bit-and (unsigned-bit-shift-right (long port-address) 8) 0x7F)
   :sub-net  (bit-and (unsigned-bit-shift-right (long port-address) 4) 0x0F)
   :universe (bit-and (long port-address) 0x0F)})

(defn normalize-ip
  "Normalize IP address to vector of 4 bytes."
  [ip]
  (cond (nil? ip) [0 0 0 0]
        (instance? InetAddress ip) (vec (.getAddress ^InetAddress ip))
        (and (sequential? ip) (= 4 (count ip))) (vec ip)
        :else (throw (ex-info "Invalid IP address representation" {:ip ip}))))

(defn normalize-mac
  "Normalize MAC address to vector of 6 bytes."
  [mac]
  (cond (nil? mac) [0 0 0 0 0 0]
        (and (sequential? mac) (= 6 (count mac))) (vec mac)
        :else (throw (ex-info "Invalid MAC representation" {:mac mac}))))

(defn normalize-array
  "Normalize a collection to vector of specified length."
  [coll len]
  (let [v (vec coll)]
    (when (not= len (count v))
      (throw (ex-info "Incorrect field width"
                      {:expected len, :actual (count v)})))
    v))

(defn normalize-uid
  "Normalize RDM UID to a vector of 6 bytes."
  [uid]
  (cond (nil? uid) (repeat 6 0)
        (bytes? uid) (mapv #(bit-and % 0xFF) uid)
        (instance? ByteBuffer uid)
        (let [dup (.duplicate ^ByteBuffer uid)
              length (.remaining dup)]
          (when (not= length 6)
            (throw (ex-info "ByteBuffer UID must be 6 bytes" {:length length})))
          (let [data (byte-array 6)]
            (.get dup data 0 6)
            (mapv #(bit-and % 0xFF) data)))
        (and (sequential? uid) (= 6 (count uid))) (mapv #(bit-and (int %) 0xFF)
                                                        uid)
        (integer? uid)
        (mapv (fn [shift]
                (bit-and (unsigned-bit-shift-right (long uid) shift) 0xFF))
              [40 32 24 16 8 0])
        :else (throw (ex-info "Unsupported UID representation" {:uid uid}))))

(defn datarequest-type
  "Resolve data request type from code."
  [code]
  (or (get const/datarequest-code->keyword code)
      (when (>= code 0x8000) :dr-man-spec)))

(defn resolve-datarequest-code
  "Resolve data request code from packet map."
  [{:keys [request request-code request-type], :as packet}]
  (cond (some? request) (bit-and (int request) 0xFFFF)
        (some? request-code) (bit-and (int request-code) 0xFFFF)
        (some? request-type)
        (let [resolved (get const/datarequest-keyword->code request-type)]
          (if (some? resolved)
            resolved
            (throw (ex-info "Unknown ArtDataRequest type"
                            {:request-type request-type
                             :packet       (dissoc packet :payload :data :text)}))))
        :else (throw (ex-info "ArtDataRequest packet requires :request code"
                              {:packet (dissoc packet :payload :data :text)}))))

(defn rdmsub-command-code
  "Resolve RDM sub command code from a packet map."
  [{:keys [command-class command]}]
  (cond (some? command-class) (bit-and (int command-class) 0xFF)
        (keyword? command) (let [value (get const/rdm-command-keyword->class
                                            command)]
                             (if (some? value)
                               value
                               (throw (ex-info "Unknown RDM sub command keyword"
                                               {:command command}))))
        (some? command) (bit-and (int command) 0xFF)
        :else (throw (ex-info "ArtRdmSub requires :command-class or :command"
                              {}))))

(defn trigger-key-byte
  "Resolve trigger key byte from packet map."
  [{:keys [key key-type], :as packet}]
  (cond (some? key) (bit-and (int key) 0xFF)
        (some? key-type)
        (let [resolved (get const/arttrigger-keyword->key key-type)]
          (if (some? resolved)
            resolved
            (throw (ex-info "Unknown ArtTrigger key-type"
                            {:packet (dissoc packet :data :payload)}))))
        :else (throw (ex-info "ArtTrigger packet requires :key or :key-type"
                              {:packet (dissoc packet :data :payload)}))))

(defn encode-vlc-flags
  "Encode VLC flags from map."
  [{:keys [flags ieee? reply? beacon?]}]
  (let [base (bit-and (or flags 0) const/artvlc-flags-mask)
        base (cond-> base
                     ieee? (bit-or const/artvlc-flag-ieee)
                     reply? (bit-or const/artvlc-flag-reply)
                     beacon? (bit-or const/artvlc-flag-beacon))]
    (bit-and base const/artvlc-flags-mask)))

(defn flag-set?
  "Check if the flag bit is set."
  [flags mask]
  (pos? (bit-and flags mask)))

(defn decode-generic-payload
  "Decode a generic Art-Net packet with an opaque payload."
  [op ^ByteBuffer buf]
  (when (< (.limit buf) const/artnet-base-header-size)
    (throw (ex-info "Truncated Art-Net packet" {:op op, :limit (.limit buf)})))
  (let [protocol (prim/safe-uint16-be buf 10)
        length (max 0 (- (.limit buf) const/artnet-base-header-size))
        view (.duplicate buf)]
    (.position view const/artnet-base-header-size)
    (.limit view (.limit buf))
    (let [data (.asReadOnlyBuffer (.slice view))]
      {:op op, :protocol-version protocol, :length length, :data data})))

(defn encode-generic-packet!
  "Encode a generic Art-Net packet with an opaque payload."
  [^ByteBuffer buf op {:keys [data], :as packet}]
  (let [length (if data (prim/payload-length data) 0)
        ^ByteBuffer target
        (prim/prepare-target buf (+ const/artnet-base-header-size length))
        override-version (:protocol-version packet)
        version (int (or override-version const/protocol-version))]
    (.clear target)
    (.put target ^"[B" const/artnet-id-bytes)
    (prim/put-u16-le! target (const/keyword->opcode op))
    (prim/put-u16-be! target version)
    (prim/write-payload! target data)
    (.flip target)))
