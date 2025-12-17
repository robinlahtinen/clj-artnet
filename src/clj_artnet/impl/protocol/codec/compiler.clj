;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.codec.compiler
  "Spec-driven codec compiler for Art-Net 4.

   This namespace compiles declarative packet specifications from
   `clj-artnet.impl.protocol.codec.spec` into high-performance ByteBuffer operations.

   Key features:
   - Zero-allocation reads via flyweight pattern
   - Primitive type hints (^long, ^bytes) in hot paths
   - Direct ByteBuffer access without intermediate allocations
   - Generated encoders/decoders from spec data"
  {:skip-wiki true}
  (:require
    [clj-artnet.impl.protocol.codec.constants :as const]
    [clj-artnet.impl.protocol.codec.spec :as spec])
  (:import
    (java.nio ByteBuffer)
    (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(defn read-u8
  "Read unsigned byte at offset. Returns long."
  ^long [^ByteBuffer buf ^long offset]
  (bit-and (long (.get buf (int offset))) 0xFF))

(defn read-u16-le
  "Read unsigned 16-bit little-endian at offset. Returns long."
  ^long [^ByteBuffer buf ^long offset]
  (let [i (int offset)
        lo (bit-and (long (.get buf i)) 0xFF)
        hi (bit-and (long (.get buf (inc i))) 0xFF)]
    (bit-or lo (bit-shift-left hi 8))))

(defn read-u16-be
  "Read unsigned 16-bit big-endian at offset. Returns long."
  ^long [^ByteBuffer buf ^long offset]
  (let [i (int offset)
        hi (bit-and (long (.get buf i)) 0xFF)
        lo (bit-and (long (.get buf (inc i))) 0xFF)]
    (bit-or (bit-shift-left hi 8) lo)))

(defn read-ipv4
  "Read a 4-byte IP address at offset. Returns a vector of 4 octets."
  [^ByteBuffer buf ^long offset]
  (let [i (int offset)]
    [(bit-and (long (.get buf i)) 0xFF)
     (bit-and (long (.get buf (unchecked-add-int i 1))) 0xFF)
     (bit-and (long (.get buf (unchecked-add-int i 2))) 0xFF)
     (bit-and (long (.get buf (unchecked-add-int i 3))) 0xFF)]))

(defn read-uid
  "Read 6-byte RDM UID at offset. Returns a vector of 6 octets."
  [^ByteBuffer buf ^long offset]
  (let [i (int offset)]
    [(bit-and (long (.get buf i)) 0xFF)
     (bit-and (long (.get buf (unchecked-add-int i 1))) 0xFF)
     (bit-and (long (.get buf (unchecked-add-int i 2))) 0xFF)
     (bit-and (long (.get buf (unchecked-add-int i 3))) 0xFF)
     (bit-and (long (.get buf (unchecked-add-int i 4))) 0xFF)
     (bit-and (long (.get buf (unchecked-add-int i 5))) 0xFF)]))

(defn read-bytes
  "Read n bytes at offset. Returns a vector of octets."
  [^ByteBuffer buf ^long offset ^long length]
  (let [i (int offset)]
    (vec (for [j (range length)]
           (bit-and (long (.get buf (+ i (int j)))) 0xFF)))))

(defn read-fixed-string
  "Read null-terminated ASCII string at offset with max length."
  [^ByteBuffer buf ^long offset ^long max-length]
  (let [i (int offset)
        ^bytes data (byte-array max-length)
        _ (.position buf i)
        _ (.get buf data 0 (int max-length))
        end (loop [idx 0]
              (if (or (>= idx max-length) (zero? (aget data idx)))
                idx
                (recur (inc idx))))]
    (String. data 0 (int end) StandardCharsets/US_ASCII)))

(defn slice-payload
  "Create a read-only ByteBuffer slice of payload data at offset with a given length.
   Uses flyweight pattern - no data copying. Returns nil if the length is 0."
  [^ByteBuffer buf ^long offset ^long length]
  (when (pos? length)
    (let [saved-pos (.position buf)
          saved-limit (.limit buf)]
      (try (.position buf (int offset))
           (.limit buf (int (+ offset length)))
           (.slice buf)
           (finally (.position buf saved-pos) (.limit buf saved-limit))))))

(defn write-u8!
  "Write unsigned byte at the current position."
  [^ByteBuffer buf ^long value]
  (.put buf (unchecked-byte (bit-and value 0xFF)))
  buf)

(defn write-u16-le!
  "Write unsigned 16-bit little-endian at the current position."
  [^ByteBuffer buf ^long value]
  (.put buf (unchecked-byte (bit-and value 0xFF)))
  (.put buf (unchecked-byte (bit-and (unsigned-bit-shift-right value 8) 0xFF)))
  buf)

(defn write-u16-be!
  "Write unsigned 16-bit big-endian at the current position."
  [^ByteBuffer buf ^long value]
  (.put buf (unchecked-byte (bit-and (unsigned-bit-shift-right value 8) 0xFF)))
  (.put buf (unchecked-byte (bit-and value 0xFF)))
  buf)

(defn write-ipv4!
  "Write 4-byte IP address at current position."
  [^ByteBuffer buf ip]
  (let [[a b c d] (if (sequential? ip) ip [0 0 0 0])]
    (.put buf (unchecked-byte (bit-and (long (or a 0)) 0xFF)))
    (.put buf (unchecked-byte (bit-and (long (or b 0)) 0xFF)))
    (.put buf (unchecked-byte (bit-and (long (or c 0)) 0xFF)))
    (.put buf (unchecked-byte (bit-and (long (or d 0)) 0xFF))))
  buf)

(defn write-uid!
  "Write 6-byte RDM UID at the current position."
  [^ByteBuffer buf uid]
  (let [bytes (if (sequential? uid) uid (repeat 6 0))]
    (doseq [b (take 6 bytes)]
      (.put buf (unchecked-byte (bit-and (long (or b 0)) 0xFF)))))
  buf)

(defn write-bytes!
  "Write byte sequence at current position, padding to length with zeros."
  [^ByteBuffer buf data ^long length]
  (let [src (if (sequential? data) (vec data) [])]
    (dotimes [i length]
      (let [b (get src i 0)]
        (.put buf (unchecked-byte (bit-and (long (or b 0)) 0xFF))))))
  buf)

(defn write-fixed-string!
  "Write null-terminated ASCII string at current position, padding to length."
  [^ByteBuffer buf ^String s ^long length]
  (let [^String value (or s "")
        ^bytes data (.getBytes value StandardCharsets/US_ASCII)
        copy-length (min (dec length) (alength data))]
    (.put buf data 0 (int copy-length))
    (.put buf (byte 0))
    (dotimes [_ (- length (inc copy-length))] (.put buf (byte 0))))
  buf)

(defn read-field
  "Read a single field from the buffer according to its spec."
  [^ByteBuffer buf field ^long offset]
  (case (:type field)
    :u8 (read-u8 buf offset)
    :u16le (read-u16-le buf offset)
    :u16be (read-u16-be buf offset)
    :ipv4 (read-ipv4 buf offset)
    :uid (read-uid buf offset)
    :bytes (read-bytes buf offset (:length field))
    :fixed-string (read-fixed-string buf offset (:length field))
    nil))

(defn decode-by-spec
  "Decode a packet from a buffer using the given spec.
   Returns a map with all field values keyed by the field name."
  [^ByteBuffer buf spec]
  (loop [result {:op (some-> (first (filter #(= :op-code (:name %)) spec))
                             :value
                             spec/packet-specs)}
         offset (long 0)
         fields spec]
    (if-let [[field & remaining] (seq fields)]
      (let [size (spec/field-size field)
            value (read-field buf field offset)
            ;; Skip constant fields (id, op-code, protocol version)
            result' (if (or (contains? field :value)
                            (#{:id :op-code :prot-ver-hi :prot-ver-lo}
                             (:name field)))
                      result
                      (assoc result (:name field) value))]
        (recur result' (unchecked-add offset size) remaining))
      result)))

(defn write-field!
  "Write a single field to the buffer, according to its spec."
  [^ByteBuffer buf field value]
  (let [actual-value (or value (:value field) (:default field))]
    (case (:type field)
      :u8 (write-u8! buf (or actual-value 0))
      :u16le (write-u16-le! buf (or actual-value 0))
      :u16be (write-u16-be! buf (or actual-value 0))
      :ipv4 (write-ipv4! buf actual-value)
      :uid (write-uid! buf actual-value)
      :bytes (write-bytes! buf actual-value (:length field))
      :fixed-string (write-fixed-string! buf actual-value (:length field))))
  buf)

(defn encode-by-spec!
  "Encode a packet map to buffer using the given spec.
   Buffer must be cleared and sized appropriately before calling."
  [^ByteBuffer buf spec packet]
  (doseq [field spec]
    (let [value (get packet (:name field))] (write-field! buf field value)))
  (.flip buf)
  buf)

(defn compile-decoder
  "Compile a spec into a decoder function.
   Returns (fn [^ByteBuffer buf] -> map)."
  [spec op-keyword]
  (let [field-readers (vec (for [field spec
                                 :when (not (or (contains? field :value)
                                                (#{:id :op-code :prot-ver-hi
                                                   :prot-ver-lo}
                                                 (:name field))))]
                             (let [offset (spec/field-offset spec (:name field))
                                   field-name (:name field)
                                   field-type (:type field)
                                   field-length (:length field 0)]
                               [field-name field-type offset field-length])))]
    (fn [^ByteBuffer buf]
      (reduce (fn [result [field-name field-type offset field-length]]
                (let [value (case field-type
                              :u8 (read-u8 buf offset)
                              :u16le (read-u16-le buf offset)
                              :u16be (read-u16-be buf offset)
                              :ipv4 (read-ipv4 buf offset)
                              :uid (read-uid buf offset)
                              :bytes (read-bytes buf offset field-length)
                              :fixed-string
                              (read-fixed-string buf offset field-length)
                              nil)]
                  (assoc result field-name value)))
              {:op op-keyword}
              field-readers))))

(defn compile-encoder
  "Compile a spec into an encoder function.
   Returns (fn [^ByteBuffer buf packet] -> ByteBuffer)."
  [spec]
  (let [total-size (spec/spec-header-size spec)]
    (fn [^ByteBuffer buf packet]
      (when (< (.remaining buf) total-size)
        (throw (ex-info "Buffer too small for packet"
                        {:required total-size, :available (.remaining buf)})))
      (.clear buf)
      (doseq [field spec]
        (let [value (get packet (:name field))] (write-field! buf field value)))
      (.flip buf)
      buf)))

(defn valid-artnet-header?
  "Check if buffer starts with valid Art-Net header."
  [^ByteBuffer buf]
  (when (>= (.limit buf) 8)
    (loop [idx 0]
      (if (>= idx 8)
        true
        (if (= (.get buf (int idx))
               (aget ^"[B" const/artnet-id-bytes (int idx)))
          (recur (inc idx))
          false)))))

(defn read-opcode
  "Read the opcode from an Art-Net packet buffer."
  ^long [^ByteBuffer buf]
  (read-u16-le buf 8))

(def compiled-decoders
  "Map of opcode keyword -> compiled decoder fn"
  (delay (into {}
               (for [[op-kw spec] spec/packet-specs]
                 [op-kw (compile-decoder spec op-kw)]))))

(def compiled-encoders
  "Map of opcode keyword -> compiled encoder fn"
  (delay (into {}
               (for [[op-kw spec] spec/packet-specs]
                 [op-kw (compile-encoder spec)]))))

(def opcode->keyword
  "Map of numeric opcode -> keyword"
  (delay (into {}
               (for [[op-kw spec] spec/packet-specs
                     :let [op-field (spec/field-by-name spec :op-code)
                           opcode (:value op-field)]]
                 [opcode op-kw]))))

(def ^:private variable-payload-packets
  "Packet types with variable-length payloads after the header.
   Maps op-keyword to {:length-field :header-size}"
  {:artdmx    {:length-field :length, :header-size 18}
   :artnzs    {:length-field :length, :header-size 18}
   :artrdm    {:length-field nil, :header-size 24}
   :artrdmsub {:length-field nil, :header-size 32}})

(defn decode-packet
  "Decode a packet from a buffer using compiled decoder.
   Returns nil if opcode is unknown. For packets with variable-length
   payloads (ArtDmx, ArtNzs, ArtRdm), includes :data as ByteBuffer slice."
  [^ByteBuffer buf]
  (when (valid-artnet-header? buf)
    (let [opcode (read-opcode buf)
          op-kw (get @opcode->keyword opcode)]
      (when op-kw
        (let [decoder (get @compiled-decoders op-kw)
              base-packet (when decoder (decoder buf))
              payload-info (get variable-payload-packets op-kw)]
          (if (and base-packet payload-info)
            ;; Packet has variable-length payload
            (let [{:keys [length-field header-size]} payload-info
                  payload-length (if length-field
                                   (get base-packet length-field 0)
                                   (- (.limit buf) (long header-size)))
                  data (when (pos? payload-length)
                         (slice-payload buf header-size payload-length))]
              (cond-> base-packet data (assoc :data data)))
            ;; Fixed-size packet, return as-is
            base-packet))))))

(defn encode-packet!
  "Encode a packet map to buffer using a compiled encoder.
   The :op key determines which encoder to use."
  [^ByteBuffer buf {:keys [op], :as packet}]
  (let [encoder (get @compiled-encoders op)]
    (when-not encoder (throw (ex-info "Unknown packet opcode" {:op op})))
    (encoder buf packet)))

(defn packet-size
  "Get the expected size in bytes for a packet type.
   Returns nil if the packet type is unknown."
  [op-keyword]
  (when-let [spec (get spec/packet-specs op-keyword)]
    (spec/spec-header-size spec)))

(comment
  ;; Test the compiler. Requires reload of spec if modified:
  ;; (require '[clj-artnet.impl.protocol.codec.spec :as spec] :reload)
  ;; Create a test buffer with ArtSync packet
  (def test-sync-buf
    (let [buf (ByteBuffer/allocate 14)]
      (.put buf const/artnet-id-bytes)
      (write-u16-le! buf 0x5200)                            ; OpSync
      (write-u8! buf 0)                                     ; ProtVerHi
      (write-u8! buf 14)                                    ; ProtVerLo
      (write-u8! buf 0)                                     ; Aux1
      (write-u8! buf 0)                                     ; Aux2
      (.flip buf)
      buf))
  ;; Decode it
  (decode-packet test-sync-buf)
  ;; Test ArtPollReply encoding
  (def test-reply-buf (ByteBuffer/allocate 256))
  (encode-packet! test-reply-buf
                  {:op                :artpollreply
                   :ip                [192 168 1 100]
                   :short-name        "Test Node"
                   :long-name         "Test Art-Net Node"
                   :node-report       "#0001 [0001] OK"
                   :num-ports         1
                   :port-types        [0x85 0 0 0]
                   :good-input        [0 0 0 0]
                   :good-output-a     [0x80 0 0 0]
                   :sw-in             [0 0 0 0]
                   :sw-out            [0 0 0 0]
                   :mac               [0xDE 0xAD 0xBE 0xEF 0x00 0x01]
                   :bind-ip           [192 168 1 100]
                   :good-output-b     [0 0 0 0]
                   :default-responder [0 0 0 0 0 0]})
  ;; Decode the encoded packet
  (.position test-reply-buf 0)
  (decode-packet test-reply-buf)
  :rcf)
