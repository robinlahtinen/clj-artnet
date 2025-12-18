;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.codec.primitives
  "Low-level buffer read/write primitives for Art-Net codec.
   All functions operate on java.nio.ByteBuffer instances."
  (:require
    [clj-artnet.impl.protocol.codec.constants :as const])
  (:import
    (java.nio ByteBuffer)
    (java.nio.charset StandardCharsets)
    (java.util Arrays)))

(set! *warn-on-reflection* true)

(defn ubyte
  "Convert signed byte to unsigned int (0-255)."
  ^long [v]
  (bit-and (long v) 0xFF))

(defn safe-ubyte
  "Read unsigned byte at index, returning 0 if out of bounds."
  ^long [^ByteBuffer buf ^long idx]
  (let [i (int idx)] (if (< i (.limit buf)) (ubyte (.get buf i)) 0)))

(defn safe-uint16-be
  "Read big-endian uint16 at index, returning 0 if out of bounds."
  ^long [^ByteBuffer buf ^long idx]
  (let [i (int idx)
        limit (.limit buf)]
    (if (< (+ i 1) limit)
      (bit-or (bit-shift-left (ubyte (.get buf i)) 8)
              (ubyte (.get buf (inc i))))
      0)))

(defn uint16-le
  "Read little-endian uint16 at index."
  ^long [^ByteBuffer buf ^long idx]
  (let [i (int idx)
        j (inc i)]
    (bit-or (ubyte (.get buf i)) (bit-shift-left (ubyte (.get buf j)) 8))))

(defn uint16-be
  "Read big-endian uint16 at index."
  ^long [^ByteBuffer buf ^long idx]
  (let [i (int idx)
        j (inc i)]
    (bit-or (bit-shift-left (ubyte (.get buf i)) 8) (ubyte (.get buf j)))))

(defn read-ascii
  "Read null-terminated ASCII string from buffer at offset with max length."
  ^String [^ByteBuffer buf ^long offset ^long length]
  (when (> (+ offset length) (.limit buf))
    (throw (ex-info "String exceeds buffer bounds"
                    {:offset offset, :length length, :limit (.limit buf)})))
  (let [data (byte-array length)]
    (.position buf (int offset))
    (.get buf data 0 (int length))
    (let [end (loop [idx 0]
                (if (or (>= idx length) (zero? (aget data (int idx))))
                  idx
                  (recur (inc idx))))]
      (String. data 0 (int end) StandardCharsets/US_ASCII))))

(defn read-octets
  "Read byte slice as a vector of unsigned integers."
  [^ByteBuffer buf ^long offset ^long length]
  (when (> (+ offset length) (.limit buf))
    (throw (ex-info "Octet slice exceeds buffer bounds"
                    {:offset offset, :length length, :limit (.limit buf)})))
  (let [data (byte-array length)]
    (.position buf (int offset))
    (.get buf data 0 (int length))
    (mapv #(bit-and % 0xFF) data)))

(defn put-u16-le!
  "Write little-endian uint16 to buffer at the current position."
  [^ByteBuffer buf ^long v]
  (.put buf (unchecked-byte (bit-and v 0xFF)))
  (.put buf (unchecked-byte (bit-and (unsigned-bit-shift-right v 8) 0xFF))))

(defn put-u16-be!
  "Write big-endian uint16 to buffer at the current position."
  [^ByteBuffer buf ^long v]
  (.put buf (unchecked-byte (bit-and (unsigned-bit-shift-right v 8) 0xFF)))
  (.put buf (unchecked-byte (bit-and v 0xFF))))

(defn put-bytes!
  "Write a collection of bytes to buffer at current position."
  [^ByteBuffer buf coll]
  (doseq [b coll] (.put buf (unchecked-byte b))))

(defn put-fixed-string!
  "Write null-terminated ASCII string padded to fixed width."
  [^ByteBuffer buf s ^long width]
  (let [^String value (or s "")
        data (.getBytes value StandardCharsets/US_ASCII)
        copy-length (min (dec width) (alength ^bytes data))]
    (.put buf data 0 copy-length)
    (.put buf (byte 0))
    (dotimes [_ (- width (inc copy-length))] (.put buf (byte 0)))))

(defn put-uids!
  "Write a sequence of RDM UIDs (6-byte sequences) to the buffer."
  [^ByteBuffer buf uids]
  (doseq [uid uids b uid] (.put buf (unchecked-byte b))))

(defn prepare-target
  "Prepare a ByteBuffer for writing with a specified capacity."
  ^ByteBuffer [^ByteBuffer buf ^long size]
  (let [^ByteBuffer target (or buf (ByteBuffer/allocateDirect (int size)))]
    (when (< (.capacity target) size)
      (throw (ex-info "Insufficient buffer capacity"
                      {:required size, :capacity (.capacity target)})))
    (.clear target)
    (.limit target (int size))
    target))

(defn ensure-header!
  "Validate Art-Net ID bytes at start of buffer. Throws if invalid."
  [^ByteBuffer buf]
  (when (< (.limit buf) const/artnet-id-length)
    (throw (ex-info "Buffer shorter than Art-Net ID" {:limit (.limit buf)})))
  (let [^"[B" id-bytes const/artnet-id-bytes]
    (dotimes [idx const/artnet-id-length]
      (when-not (= (.get buf (int idx)) (aget id-bytes (int idx)))
        (throw (ex-info "Invalid Art-Net ID" {:position idx}))))))

(def ^Class byte-array-class
  "Class object for byte arrays."
  (class (byte-array 0)))

(defn coerce-to-bytes
  "Coerce payload to a byte-array.
   Accepts byte[], ByteBuffer, or sequential collection of integers."
  ^bytes [payload]
  (cond (bytes? payload)
        payload
        (instance? ByteBuffer payload)
        (let [^ByteBuffer buf (.duplicate ^ByteBuffer payload)
              arr (byte-array (.remaining buf))]
          (.get buf arr)
          arr)
        (sequential? payload)
        (byte-array (map #(unchecked-byte (int %)) payload))
        :else
        (throw (ex-info "Unsupported payload container"
                        {:type    (class payload)
                         :allowed [ByteBuffer byte-array-class 'sequential?]
                         :hint    "Provide byte-array, ByteBuffer, or seq of integers"}))))

(defn as-buffer
  "Convert payload to ByteBuffer."
  ^ByteBuffer [payload]
  (cond (instance? ByteBuffer payload)
        (.duplicate ^ByteBuffer payload)
        (bytes? payload)
        (ByteBuffer/wrap ^bytes payload)
        (sequential? payload)
        (ByteBuffer/wrap (coerce-to-bytes payload))
        :else
        (throw (ex-info "Unsupported payload container"
                        {:type    (class payload)
                         :allowed [ByteBuffer byte-array-class 'sequential?]
                         :hint    "Provide byte-array, ByteBuffer, or seq of integers"}))))

(defn payload-length
  "Get the length of payload (ByteBuffer, byte array, or sequential)."
  ^long [payload]
  (cond (instance? ByteBuffer payload)
        (.remaining (.duplicate ^ByteBuffer payload))
        (bytes? payload)
        (alength ^bytes payload)
        (sequential? payload)
        (count payload)
        :else
        (throw (ex-info "Unsupported payload container"
                        {:type    (class payload)
                         :allowed [ByteBuffer byte-array-class 'sequential?]
                         :hint    "Provide byte-array, ByteBuffer, or seq of integers"}))))

(defn payload-bytes
  "Extract payload as a byte array."
  ^bytes [payload]
  (let [^ByteBuffer buf (as-buffer payload)
        length (.remaining buf)
        data (byte-array length)
        ^ByteBuffer dup (.duplicate buf)]
    (.get dup data)
    data))

(defn write-payload!
  "Write payload (ByteBuffer, byte array, or sequential) to the target buffer."
  [^ByteBuffer buf payload]
  (when payload
    (cond (instance? ByteBuffer payload)
          (let [^ByteBuffer src payload
                ^ByteBuffer dup (.duplicate src)]
            (.position dup 0)
            (.limit dup (.remaining src))
            (.put buf dup))
          (bytes? payload)
          (.put buf ^bytes payload)
          (sequential? payload)
          (.put buf ^bytes (coerce-to-bytes payload))
          :else
          (throw (ex-info "Unsupported payload container"
                          {:type    (class payload)
                           :allowed [ByteBuffer byte-array-class 'sequential?]
                           :hint    "Provide byte-array, ByteBuffer, or seq of integers"}))))
  buf)

(defn ensure-null-terminated
  "Ensure a byte array is null-terminated, adding terminator if needed."
  ^bytes [^bytes data ^long max-length]
  (let [length (alength data)]
    (cond (zero? length) (byte-array 1)
          (zero? (aget data (dec length))) data
          (< length max-length) (let [dest (byte-array (inc length))]
                                  (System/arraycopy data 0 dest 0 length)
                                  (aset dest length (byte 0))
                                  dest)
          :else (throw (ex-info "Payload must be null-terminated"
                                {:max max-length, :length length})))))

(defn clamp-bytes
  "Clamp byte array to maximum length."
  ^bytes [^bytes data ^long max-length]
  (let [length (min max-length (alength data))]
    (if (= length (alength data)) data (Arrays/copyOf data (int length)))))

(defn sum-bytes-16
  "Sum byte values as 16-bit checksum."
  ^long [^bytes data]
  (loop [idx 0
         acc 0]
    (if (< idx (alength data))
      (let [value (bit-and (aget data idx) 0xFF)
            next-acc (bit-and (+ acc value) 0xFFFF)]
        (recur (inc idx) next-acc))
      acc)))

(comment
  (require '[clj-artnet.impl.protocol.codec.primitives :as prim] :reload)
  ;; Coerce vector to bytes
  (vec (prim/coerce-to-bytes [255 0 128]))
  ;; => [-1 0 -128]  ; signed byte representation
  ;; Coerce to buffer
  (prim/as-buffer [1 2 3])
  ;; => #object[java.nio.HeapByteBuffer ...]
  ;; Get length of sequential
  (prim/payload-length [1 2 3 4 5])
  ;; => 5
  :rcf)
