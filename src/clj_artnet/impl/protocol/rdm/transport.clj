;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.rdm.transport
  "ArtRdm and ArtRdmSub transport validation and helpers per Art-Net 4 spec.

   Art-Net 4 §ArtRdm:
   - Transport all non-discovery RDM messages
   - Unicast only (broadcast no longer allowed in Art-Net 4)
   - Command field 0x00 = ArProcess (process RDM packet)
   - Validate command class in RDM payload (byte 20)

   Art-Net 4 §ArtRdmSub:
   - Compressed sub-device data transfer
   - Get (0x20): 0 data bytes
   - Set (0x30): SubCount * 2 data bytes
   - GetResponse (0x21): SubCount * 2 data bytes
   - SetResponse (0x31): 0 data bytes

   This module provides pure functions for:
   - RDM command class validation
   - ArtRdmSub packet validation
   - Sub-device range and entry helpers
   - Payload normalization for outbound commands"
  (:require [clj-artnet.impl.protocol.codec.domain.common :as common])
  (:import (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(def ^:const min-rdm-payload-length
  "Minimum RDM payload length per spec (excludes start code)."
  24)

(def ^:const max-rdm-payload-length "Maximum RDM payload length per spec." 255)

(def valid-command-classes
  "Valid RDM command classes that ArtRdm/ArtRdmSub accept.
   0x20 = GET_COMMAND
   0x21 = GET_COMMAND_RESPONSE
   0x30 = SET_COMMAND
   0x31 = SET_COMMAND_RESPONSE"
  #{0x20 0x21 0x30 0x31})

(def request-command-classes
  "Command classes representing requests (Get/Set)."
  #{0x20 0x30})

(def response-command-classes
  "Command classes representing responses (GetResponse/SetResponse)."
  #{0x21 0x31})

(defn payload-command-class
  "Extract command class byte from the RDM payload.
   The command class is at byte offset 20 in the RDM packet.
   Returns nil if the payload is too short or invalid type."
  [payload]
  (cond (instance? ByteBuffer payload)
        (let [buf (.duplicate ^ByteBuffer payload)]
          (.clear buf)
          (when (> (.limit buf) 20) (bit-and (int (.get buf 20)) 0xFF)))
        (bytes? payload) (let [data ^bytes payload]
                           (when (> (alength data) 20)
                             (bit-and (int (aget data 20)) 0xFF)))
        :else nil))

(defn valid-command-class?
  "Check if command class is valid for ArtRdm/ArtRdmSub."
  [command-class]
  (contains? valid-command-classes (bit-and (int (or command-class 0)) 0xFF)))

(defn request?
  "Check if the command class is a request (Get=0x20 or Set=0x30)."
  [command-class]
  (contains? request-command-classes (bit-and (int command-class) 0xFF)))

(defn response?
  "Check if the command class is a response (GetResponse=0x21 or SetResponse=0x31)."
  [command-class]
  (contains? response-command-classes (bit-and (int command-class) 0xFF)))

(defn expected-data-length
  "Calculate the expected data array size for ArtRdmSub based on CommandClass.
   Per spec:
   - Get (0x20): Array Size 0
   - Set (0x30): Array Size SubCount * 2 bytes
   - GetResponse (0x21): Array Size SubCount * 2 bytes
   - SetResponse (0x31): Array Size 0

   Returns nil for unknown command class."
  [{:keys [command-class sub-count]}]
  (let [count (max 0 (int (or sub-count 0)))
        class (bit-and (int (or command-class 0)) 0xFF)]
    (case class
      0x20 0                                                ; Get - no data
      0x31 0                                                ; SetResponse - no data
      0x21 (* 2 count)                                      ; GetResponse - SubCount values
      0x30 (* 2 count)                                      ; Set - SubCount values
      nil)))

(defn valid-rdmsub-packet?
  "Validate ArtRdmSub packet structure per spec.
   Checks:
   - SubCount > 0 (zero is illegal per spec)
   - CommandClass is valid
   - PayloadLength matches expected for CommandClass
   - PayloadLength is even (16-bit values)"
  [{:keys [command-class payload-length sub-count], :as packet}]
  (let [class (bit-and (int (or command-class 0)) 0xFF)
        length (int (or payload-length 0))
        expected (expected-data-length packet)
        count (int (or sub-count 0))]
    (and (> count 0)
         (contains? valid-command-classes class)
         (some? expected)
         (even? length)
         (= length expected))))

(defn sub-device-range
  "Extract sub-device range from ArtRdmSub packet.
   Returns {:first <start> :count <n> :last <end>}."
  [{:keys [sub-device sub-count]}]
  (let [first-sub (bit-and (int (or sub-device 0)) 0xFFFF)
        count (max 0 (int (or sub-count 0)))
        last-sub
        (if (pos? count) (bit-and 0xFFFF (+ first-sub (dec count))) first-sub)]
    {:first first-sub, :count count, :last last-sub}))

(defn sub-devices
  "Generate a vector of sub-device addresses from ArtRdmSub packet.
   Handles 16-bit wrap-around."
  [{:keys [sub-device sub-count]}]
  (let [start (bit-and (int (or sub-device 0)) 0xFFFF)
        count (max 0 (int (or sub-count 0)))]
    (vec (take count (iterate #(bit-and 0xFFFF (inc %)) start)))))

(defn sub-device-entries
  "Parse ArtRdmSub into indexed entries with a sub-device and optional value.
   Returns [{:index 0 :sub-device n :value v} ...]."
  [{:keys [values], :as packet}]
  (let [vals (vec (or values []))]
    (->> (sub-devices packet)
         (map-indexed (fn [idx sub-dev]
                        {:index      idx,
                         :sub-device sub-dev,
                         :value      (when (< idx (count vals)) (nth vals idx))}))
         vec)))

(defn normalize-payload-bytes
  "Convert payload to a byte array for transmission.
   Accepts ByteBuffer, byte[], or seq of integers."
  [payload]
  (cond (instance? ByteBuffer payload) (let [^ByteBuffer buf
                                             (.duplicate ^ByteBuffer payload)
                                             length (.remaining buf)
                                             copy (byte-array length)]
                                         (.get buf copy)
                                         copy)
        (bytes? payload) (aclone ^bytes payload)
        (sequential? payload) (byte-array (map #(unchecked-byte (int %))
                                               payload))
        :else (throw (ex-info "Unsupported RDM payload container"
                              {:type (type payload)}))))

(defn normalize-payload-buffer
  "Convert payload to read-only ByteBuffer for transmission."
  [payload]
  (-> payload
      normalize-payload-bytes
      ByteBuffer/wrap
      .asReadOnlyBuffer))

(defn validate-payload-length
  "Validate RDM payload length is within spec bounds.
   Throws ex-info if invalid.
   Returns the payload length if valid."
  [payload-length]
  (cond (< payload-length min-rdm-payload-length)
        (throw (ex-info "RDM payload shorter than minimum length"
                        {:length payload-length, :min min-rdm-payload-length}))
        (> payload-length max-rdm-payload-length)
        (throw (ex-info "RDM payload exceeds maximum length"
                        {:length payload-length, :max max-rdm-payload-length}))
        :else payload-length))

(defn normalize-target
  "Normalize RDM target address for unicast transmission.
   ArtRdm requires unicast in Art-Net 4.
   Returns {:host h :port p} or throws if the host is missing."
  [target]
  (if (and (map? target) (:host target))
    {:host (:host target), :port (int (or (:port target) 0x1936))}
    (throw (ex-info "send-rdm requires :target with :host" {:target target}))))

(defn build-artrdm-packet
  "Construct an ArtRdm packet and the associated send action.
   Handles packet normalization, validation, and packet field population.
   Throws an exception if validation fails.
   Returns action map."
  [{:keys [target rdm-packet rdm-version fifo-available fifo-max address],
    :as   command-msg}]
  (let [target' (normalize-target target)]
    (when-not rdm-packet
      (throw (ex-info "send-rdm requires :rdm-packet" {:command :send-rdm})))
    (let [^ByteBuffer packet-buffer (normalize-payload-buffer rdm-packet)
          packet-length (.remaining packet-buffer)]
      (validate-payload-length packet-length)
      (let [command-class (payload-command-class packet-buffer)]
        (when-not (valid-command-class? command-class)
          (throw (ex-info "Unsupported RDM command class"
                          {:command-class command-class})))
        (let [{:keys [net _subnet _universe port-address]}
              (if-let [addr (:port-address command-msg)]
                (assoc (common/split-port-address addr) :port-address addr)
                (let [n (bit-and (or (:net command-msg) 0) 0x7F)
                      s (bit-and (or (:sub-net command-msg) 0) 0x0F)
                      u (bit-and (or (:universe command-msg) 0) 0x0F)]
                  {:net          n,
                   :sub-net      s,
                   :universe     u,
                   :port-address (common/compose-port-address n s u)}))
              low-byte (bit-and (int port-address) 0xFF)
              address-byte (bit-and (int (or address low-byte)) 0xFF)
              command-byte (bit-and (int (or (:command-code command-msg)
                                             (:rdm-command command-msg)
                                             0))
                                    0xFF)
              packet {:op                :artrdm,
                      :rdm-version       (bit-and (int (or rdm-version 1)) 0xFF),
                      :fifo-available    (bit-and (int (or fifo-available 0))
                                                  0xFF),
                      :fifo-max          (bit-and (int (or fifo-max 0)) 0xFF),
                      :net               (bit-and (int net) 0x7F),
                      :command           command-byte,
                      :address           address-byte,
                      :rdm-packet        packet-buffer,
                      :rdm-packet-length packet-length}]
          {:type :send, :target target', :packet packet})))))
