(ns clj-artnet.fixtures.builders
  "Packet construction utilities for testing.

   Provides builder functions that take fixture data and produce
   encoded packets suitable for injection into tests.

   Design Philosophy:
   - Builders are pure functions that transform data to encoded forms
   - All builders handle sensible defaults for optional parameters
   - Encoded results match Art-Net 4 wire format

   Migrated from: fixtures.clj, artpollreply_fixtures.clj, arttimecode_fixtures.clj"
  (:require [clj-artnet.impl.protocol.codec.constants :as const]
            [clj-artnet.impl.protocol.codec.dispatch :as dispatch])
  (:import (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)))

(def ^:private artnet-id
  "Art-Net protocol identifier bytes."
  (vec (map #(bit-and 0xFF %)
            (.getBytes "Art-Net\u0000" StandardCharsets/US_ASCII))))

(defn- emit-byte [builder value] (conj! builder (bit-and (int value) 0xFF)))

(defn- emit-bytes [builder values] (reduce emit-byte builder values))

(defn- emit-u16-le
  [builder value]
  (-> builder
      (emit-byte value)
      (emit-byte (unsigned-bit-shift-right value 8))))

(defn- emit-u16-be
  [builder value]
  (-> builder
      (emit-byte (unsigned-bit-shift-right value 8))
      (emit-byte value)))

(defn- emit-fixed-string
  [builder s width]
  (let [^String value (or s "")
        ^bytes data (.getBytes value StandardCharsets/US_ASCII)
        copy-length (min (dec width) (alength data))
        builder (loop [acc builder
                       idx 0]
                  (if (< idx copy-length)
                    (recur (emit-byte acc (aget data idx)) (inc idx))
                    acc))
        builder (emit-byte builder 0)
        remaining (- width (inc copy-length))]
    (loop [acc builder
           n remaining]
      (if (zero? n) acc (recur (emit-byte acc 0) (dec n))))))

(defn- width-values
  [coll width]
  (->> (concat (or coll []) (repeat 0))
       (take width)))

(defn artdmx-packet
  "Create an ArtDmx packet map for testing.

   Options:
     :sequence  - DMX sequence number (default: 1)
     :physical  - Physical port index (default: 0)
     :net       - Art-Net net (default: 0)
     :sub-net   - Art-Net sub-net (default: 0)
     :universe  - Art-Net universe (default: 0)
     :data      - DMX data as byte-array (required)"
  [data &
   {:keys [sequence physical net sub-net universe],
    :or   {sequence 1, physical 0, net 0, sub-net 0, universe 0}}]
  {:op       :artdmx,
   :sequence sequence,
   :physical physical,
   :net      net,
   :sub-net  sub-net,
   :universe universe,
   :data     data})

(defn artdmx-buffer
  "Create an encoded ArtDmx packet as a ByteBuffer for testing.

   Encodes an ArtDmx packet with the given DMX data and returns
   a decoded packet ready for injection into the flow graph."
  [data &
   {:keys [sequence physical net sub-net universe],
    :or   {sequence 1, physical 0, net 0, sub-net 0, universe 0}}]
  (-> (artdmx-packet data
                     :sequence sequence
                     :physical physical
                     :net net
                     :sub-net sub-net
                     :universe universe)
      (dispatch/encode (ByteBuffer/allocate 1024))
      dispatch/decode))

(defn build-artpollreply-octets
  "Build raw octet vector for ArtPollReply packet from page data.

   Takes a page map containing ArtPollReply fields and returns
   a vector of unsigned byte values matching the wire format."
  [page]
  (let [bind-ip (or (:bind-ip page) (:ip page))
        builder (->
                  (transient [])
                  (emit-bytes artnet-id)
                  (emit-u16-le (const/keyword->opcode :artpollreply))
                  (emit-bytes (width-values (:ip page) 4))
                  (emit-u16-le (:port page))
                  (emit-byte (:version-hi page))
                  (emit-byte (:version-lo page))
                  (emit-byte (:net-switch page))
                  (emit-byte (:sub-switch page))
                  (emit-u16-be (:oem page))
                  (emit-byte (:ubea-version page))
                  (emit-byte (:status1 page))
                  (emit-u16-be (:esta-man page))
                  (emit-fixed-string (:short-name page) 18)
                  (emit-fixed-string (:long-name page) 64)
                  (emit-fixed-string (:node-report page) 64)
                  (emit-u16-be (:num-ports page))
                  (emit-bytes (width-values (:port-types page) 4))
                  (emit-bytes (width-values (:good-input page) 4))
                  (emit-bytes (width-values (:good-output-a page) 4))
                  (emit-bytes (width-values (:sw-in page) 4))
                  (emit-bytes (width-values (:sw-out page) 4))
                  (emit-byte (:acn-priority page))
                  (emit-byte (:sw-macro page))
                  (emit-byte (:sw-remote page))
                  (emit-byte 0)
                  (emit-byte 0)
                  (emit-byte 0)
                  (emit-byte (:style page))
                  (emit-bytes (width-values (:mac page) 6))
                  (emit-bytes (width-values bind-ip 4))
                  (emit-byte (:bind-index page))
                  (emit-byte (:status2 page))
                  (emit-bytes (width-values (:good-output-b page) 4))
                  (emit-byte (:status3 page))
                  (emit-bytes (width-values (:default-responder page) 6))
                  (emit-byte (:user-hi page))
                  (emit-byte (:user-lo page))
                  (emit-u16-be (:refresh-rate page))
                  (emit-byte (:background-queue-policy page)))]
    (loop [acc builder
           count 10]
      (if (zero? count)
        (persistent! acc)
        (recur (emit-byte acc 0) (dec count))))))

(defn build-artpollreply-bytes
  "Build byte-array for ArtPollReply packet from page data.

   Validates that the output matches the expected packet length."
  [page]
  (let [octets (vec (build-artpollreply-octets page))]
    (when (not= const/artpollreply-length (count octets))
      (throw (ex-info "ArtPollReply fixture length mismatch"
                      {:expected const/artpollreply-length,
                       :actual   (count octets),
                       :page     (:bind-index page)})))
    (byte-array (map unchecked-byte octets))))

(defn build-arttimecode-octets
  "Return a vector of bytes matching the ArtTimeCode frame described by `fields`.

   Fields:
     :proto     - Protocol version (default: const/protocol-version)
     :unused    - Filler byte (default: 0)
     :stream-id - Timecode stream ID
     :frames    - Frame count (0-29)
     :seconds   - Seconds (0-59)
     :minutes   - Minutes (0-59)
     :hours     - Hours (0-23)
     :type      - Timecode type (0-3)"
  [{:keys [proto unused stream-id frames seconds minutes hours type],
    :as   fields}]
  (let [opcode (const/keyword->opcode :arttimecode)
        proto (bit-and (int (or proto const/protocol-version)) 0xFFFF)
        filler (bit-and (int (or unused 0)) 0xFF)
        builder (-> (transient [])
                    (emit-bytes artnet-id)
                    (emit-u16-le opcode)
                    (emit-u16-be proto)
                    (emit-byte filler)
                    (emit-byte (or stream-id 0))
                    (emit-byte (or frames 0))
                    (emit-byte (or seconds 0))
                    (emit-byte (or minutes 0))
                    (emit-byte (or hours 0))
                    (emit-byte (or type 0)))
        octets (persistent! builder)]
    (when (not= const/arttimecode-length (count octets))
      (throw (ex-info "ArtTimeCode fixture length mismatch"
                      {:expected const/arttimecode-length,
                       :actual   (count octets),
                       :fields   fields})))
    octets))

(defn build-arttimecode-bytes
  "Build byte-array for ArtTimeCode packet from frame data."
  [frame]
  (byte-array (map unchecked-byte (build-arttimecode-octets frame))))

(comment
  (require '[clj-artnet.fixtures.data :as data])
  ;; Example: Build ArtDmx packet
  (artdmx-packet (byte-array [255 128 64]))
  ;; => {:op :artdmx, :sequence 1, ...}
  ;; Example: Build encoded ArtDmx buffer
  (artdmx-buffer (byte-array [1 2 3]))
  ;; => decoded packet map. Example: Build ArtPollReply octets
  (count (build-artpollreply-octets data/artpollreply-page))
  ;; => 239. Example: Build ArtTimeCode bytes
  (alength (build-arttimecode-bytes data/arttimecode-default-frame))
  ;; => 19
  :rcf)
