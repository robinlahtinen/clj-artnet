(ns clj-artnet.codec-test
  (:require
    [clj-artnet.fixtures.builders :as builders]
    [clj-artnet.fixtures.data :as fixtures]
    [clj-artnet.impl.protocol.codec.constants :as const]
    [clj-artnet.impl.protocol.codec.dispatch :as dispatch]
    [clj-artnet.impl.protocol.codec.domain.common :as common]
    [clj-artnet.impl.protocol.codec.domain.config :as config]
    [clj-artnet.support.helpers :refer [thrown-with-msg?]]
    [clojure.test :refer [deftest is]]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop])
  (:import
    (clojure.lang ExceptionInfo)
    (java.nio ByteBuffer)
    (java.nio.charset StandardCharsets)
    (java.util Arrays)))

(def ^:private artnet-id-bytes
  (.getBytes "Art-Net\u0000" StandardCharsets/US_ASCII))

(def gen-net (gen/choose 0 127))
(def gen-sub-net (gen/choose 0 15))
(def gen-universe (gen/choose 0 15))
(def gen-byte (gen/choose 0 255))
(def gen-u16 (gen/choose 0 0xFFFF))

(defn gen-fixed-vector [g n] (gen/fmap vec (gen/vector g n)))

(defn gen-ascii-string
  [max-length]
  (let [ch (gen/choose 32 126)]
    (gen/bind
      (gen/choose 0 max-length)
      (fn [len] (gen/fmap (fn [chars] (apply str chars)) (gen/vector ch len))))))

(def gen-artpollreply-overrides
  (gen/let [ip (gen-fixed-vector gen-byte 4)
            bind-ip (gen/one-of [(gen/return nil)
                                 (gen-fixed-vector gen-byte 4)])
            port (gen/choose 0 0xFFFF)
            version-hi gen-byte
            version-lo gen-byte
            net-switch (gen/choose 0 0x7F)
            sub-switch (gen/choose 0 0x0F)
            oem gen-u16
            ubea-version gen-byte
            status1 gen-byte
            short-name (gen-ascii-string 16)
            long-name (gen-ascii-string 48)
            node-report (gen-ascii-string 48)
            num-ports (gen/choose 0 4)
            port-types (gen-fixed-vector gen-byte 4)
            good-input (gen-fixed-vector gen-byte 4)
            good-output-a (gen-fixed-vector gen-byte 4)
            good-output-b (gen-fixed-vector gen-byte 4)
            sw-in (gen-fixed-vector gen-byte 4)
            sw-out (gen-fixed-vector gen-byte 4)
            acn-priority gen-byte
            sw-macro gen-byte
            sw-remote gen-byte
            style gen-byte
            mac (gen-fixed-vector gen-byte 6)
            bind-index (gen/choose 1 255)
            status2 gen-byte
            status3 gen-byte
            default-responder (gen-fixed-vector gen-byte 6)
            user-hi gen-byte
            user-lo gen-byte
            refresh-rate gen-u16
            background-queue-policy (gen/choose 0 0x0F)]
    (->
      {:ip                      ip
       :port                    port
       :version-hi              version-hi
       :version-lo              version-lo
       :net-switch              net-switch
       :sub-switch              sub-switch
       :oem                     oem
       :ubea-version            ubea-version
       :status1                 status1
       :short-name              short-name
       :long-name               long-name
       :node-report             node-report
       :num-ports               num-ports
       :port-types              port-types
       :good-input              good-input
       :good-output-a           good-output-a
       :good-output-b           good-output-b
       :sw-in                   sw-in
       :sw-out                  sw-out
       :acn-priority            acn-priority
       :sw-macro                sw-macro
       :sw-remote               sw-remote
       :style                   style
       :mac                     mac
       :bind-index              bind-index
       :status2                 status2
       :status3                 status3
       :default-responder       default-responder
       :user-hi                 user-hi
       :user-lo                 user-lo
       :refresh-rate            refresh-rate
       :background-queue-policy background-queue-policy}
      (assoc :bind-ip bind-ip))))

(def gen-dmx-payload
  (gen/bind (gen/choose 0 const/max-dmx-channels)
            (fn [length]
              (gen/fmap (fn [bytes] (byte-array (map unchecked-byte bytes)))
                        (gen/vector gen-byte length)))))

(def gen-nzs-start-code
  (gen/such-that #(and (pos? %) (not= % 0xCC)) gen-byte 100))

(def gen-nzs-payload
  (gen/bind (gen/choose 1 const/max-dmx-channels)
            (fn [length]
              (gen/fmap (fn [bytes] (byte-array (map unchecked-byte bytes)))
                        (gen/vector gen-byte length)))))

(def gen-vlc-payload
  (gen/bind (gen/choose 0 const/artvlc-max-payload)
            (fn [length]
              (gen/fmap (fn [bytes]
                          (let [arr (byte-array (map unchecked-byte bytes))
                                source (if (even? length)
                                         arr
                                         (doto (ByteBuffer/wrap arr)
                                           (.limit length)))]
                            {:source source, :bytes arr}))
                        (gen/vector gen-byte length)))))

(def gen-artinput-disabled (gen/vector gen/boolean 4))

(def generic-pass-through-ops
  [:artmedia :artmediapatch :artmediacontrol :artmediacontrolreply :arttimesync
   :artdirectory :artdirectoryreply :artvideosetup :artvideopalette
   :artvideodata :artmacmaster :artmacslave :artfiletnmaster :artfilefnmaster
   :artfilefnreply])

(def gen-generic-op (gen/elements generic-pass-through-ops))

(def gen-generic-bytes
  (gen/bind (gen/choose 0 1024)
            (fn [length]
              (gen/fmap (fn [bytes] (byte-array (map unchecked-byte bytes)))
                        (gen/vector gen-byte length)))))

(def gen-timecode
  (gen/let [stream-id gen-byte
            frames (gen/choose 0 29)
            seconds (gen/choose 0 59)
            minutes (gen/choose 0 59)
            hours (gen/choose 0 23)
            type (gen/choose 0 3)]
    {:stream-id stream-id
     :frames    frames
     :seconds   seconds
     :minutes   minutes
     :hours     hours
     :type      type}))

(def compose-split-prop
  (prop/for-all [net gen-net sub-net gen-sub-net universe gen-universe]
    (= {:net net, :sub-net sub-net, :universe universe}
       (common/split-port-address
         (common/compose-port-address net sub-net universe)))))

(def encode-decode-artdmx-prop
  (prop/for-all [sequence gen-byte physical gen-byte net gen-net sub-net
                 gen-sub-net universe gen-universe payload gen-dmx-payload]
    (let [^bytes payload-bytes payload
          packet {:op       :artdmx
                  :sequence sequence
                  :physical physical
                  :net      net
                  :sub-net  sub-net
                  :universe universe
                  :data     payload-bytes}
          buf (dispatch/encode packet)
          decoded (dispatch/decode buf)
          ^ByteBuffer payload-view (:payload decoded)
          ^ByteBuffer data-view (:data decoded)
          payload-bytes' (byte-array (.remaining payload-view))
          data-bytes' (byte-array (.remaining data-view))]
      (.get payload-view payload-bytes')
      (.get data-view data-bytes')
      (and (= :artdmx (:op decoded))
           (= sequence (:sequence decoded))
           (= physical (:physical decoded))
           (= net (:net decoded))
           (= sub-net (:sub-net decoded))
           (= universe (:universe decoded))
           (= (common/compose-port-address net sub-net universe)
              (:port-address decoded))
           (= (alength payload-bytes) (:length decoded))
           (Arrays/equals payload-bytes payload-bytes')
           (Arrays/equals payload-bytes data-bytes')))))

(def encode-decode-artnzs-prop
  (prop/for-all
    [sequence gen-byte start-code gen-nzs-start-code net gen-net sub-net
     gen-sub-net universe gen-universe payload gen-nzs-payload]
    (let [^bytes payload-bytes payload
          packet {:op         :artnzs
                  :sequence   sequence
                  :start-code start-code
                  :net        net
                  :sub-net    sub-net
                  :universe   universe
                  :data       payload-bytes}
          buf (dispatch/encode packet
                               (ByteBuffer/allocate (+ const/artnzs-header-size
                                                       (alength payload-bytes))))
          decoded (dispatch/decode buf)
          ^ByteBuffer payload-view (:payload decoded)
          payload-bytes' (byte-array (.remaining payload-view))]
      (.get payload-view payload-bytes')
      (and (= :artnzs (:op decoded))
           (= sequence (:sequence decoded))
           (= start-code (:start-code decoded))
           (= net (:net decoded))
           (= sub-net (:sub-net decoded))
           (= universe (:universe decoded))
           (= (common/compose-port-address net sub-net universe)
              (:port-address decoded))
           (= (alength payload-bytes) (:length decoded))
           (Arrays/equals payload-bytes payload-bytes')))))

(def encode-decode-artvlc-prop
  (prop/for-all
    [sequence gen-byte net gen-net sub-net gen-sub-net universe gen-universe
     payload gen-vlc-payload transaction gen-u16 slot-address gen-u16 depth
     gen-byte frequency gen-u16 modulation gen-u16 payload-language gen-u16
     beacon-repeat gen-u16 reserved gen-byte ieee? gen/boolean reply? gen/boolean
     beacon? gen/boolean]
    (let [{:keys [source bytes]} payload
          ^bytes payload-bytes bytes
          packet {:op       :artvlc
                  :sequence sequence
                  :net      net
                  :sub-net  sub-net
                  :universe universe
                  :vlc      {:payload          source
                             :transaction      transaction
                             :slot-address     slot-address
                             :depth            depth
                             :frequency        frequency
                             :modulation       modulation
                             :payload-language payload-language
                             :beacon-repeat    beacon-repeat
                             :reserved         reserved
                             :ieee?            ieee?
                             :reply?           reply?
                             :beacon?          beacon?}}
          buf (dispatch/encode packet)
          decoded (dispatch/decode buf)]
      (if-let [vlc (:vlc decoded)]
        (let [payload-view (:payload vlc)
              payload' (byte-array (.remaining payload-view))]
          (.get payload-view payload')
          (let [expected-flags
                (-> 0
                    (cond-> ieee? (bit-or const/artvlc-flag-ieee))
                    (cond-> reply? (bit-or const/artvlc-flag-reply))
                    (cond-> beacon? (bit-or const/artvlc-flag-beacon))
                    (bit-and const/artvlc-flags-mask))]
            (and (= :artvlc (:op decoded))
                 (= sequence (:sequence decoded))
                 (= const/artvlc-start-code (:start-code decoded))
                 (= net (:net decoded))
                 (= sub-net (:sub-net decoded))
                 (= universe (:universe decoded))
                 (= (common/compose-port-address net sub-net universe)
                    (:port-address decoded))
                 (= (+ const/artvlc-header-size (alength payload-bytes))
                    (:length decoded))
                 (= expected-flags (:flags vlc))
                 (= ieee? (:ieee? vlc))
                 (= reply? (:reply? vlc))
                 (= beacon? (:beacon? vlc))
                 (= transaction (:transaction vlc))
                 (= slot-address (:slot-address vlc))
                 (= (alength payload-bytes) (:payload-count vlc))
                 (= depth (:depth vlc))
                 (= frequency (:frequency vlc))
                 (= modulation (:modulation vlc))
                 (= payload-language (:payload-language vlc))
                 (= beacon-repeat (:beacon-repeat vlc))
                 (= reserved (:reserved vlc))
                 (Arrays/equals payload-bytes payload'))))
        false))))

(def encode-decode-artinput-prop
  (prop/for-all
    [bind-index gen-byte num-ports (gen/choose 0 4) disabled
     gen-artinput-disabled]
    (let [packet {:op         :artinput
                  :bind-index bind-index
                  :num-ports  num-ports
                  :disabled   disabled}
          buf (dispatch/encode packet
                               (ByteBuffer/allocate const/artinput-length))
          decoded (dispatch/decode buf)
          expected-inputs (mapv #(if % const/artinput-disable-bit 0) disabled)]
      (and (= :artinput (:op decoded))
           (= bind-index (:bind-index decoded))
           (= num-ports (:num-ports decoded))
           (= expected-inputs (:inputs decoded))
           (= (mapv boolean disabled) (:disabled decoded))))))

(def encode-decode-generic-op-prop
  (prop/for-all
    [op gen-generic-op payload gen-generic-bytes use-buffer? gen/boolean
     override-version (gen/one-of [(gen/return nil) gen-u16])]
    (let [^bytes payload-bytes payload
          payload-buffer (doto (ByteBuffer/wrap payload-bytes)
                           (.limit (alength payload-bytes)))
          packet (cond-> {:op op}
                         override-version (assoc :protocol-version override-version)
                         use-buffer? (assoc :data payload-buffer)
                         (not use-buffer?) (assoc :data payload-bytes))
          buf (dispatch/encode packet)
          decoded (dispatch/decode buf)
          ^ByteBuffer data-view (.duplicate ^ByteBuffer (:data decoded))
          data-copy (byte-array (.remaining data-view))]
      (.get data-view data-copy)
      (and (= op (:op decoded))
           (= (or override-version const/protocol-version)
              (:protocol-version decoded))
           (= (alength payload-bytes) (:length decoded))
           (Arrays/equals payload-bytes data-copy)))))

(defn- assert-property
  [prop-name prop checks]
  (let [result (tc/quick-check checks prop)]
    (is (true? (:result result)) (str prop-name " failed: " (pr-str result)))))

(defn- control-frame
  [opcode byte12 byte13]
  (let [buf (ByteBuffer/allocate 14)
        id-bytes (.getBytes "Art-Net\u0000" StandardCharsets/US_ASCII)]
    (.put buf id-bytes)
    (.put buf (unchecked-byte (bit-and opcode 0xFF)))
    (.put buf
          (unchecked-byte (bit-and (unsigned-bit-shift-right opcode 8) 0xFF)))
    (.put buf (unchecked-byte 0))
    (.put buf (unchecked-byte const/protocol-version))
    (.put buf (unchecked-byte byte12))
    (.put buf (unchecked-byte byte13))
    (.flip buf)
    buf))

(defn- artpoll-frame
  [{:keys [flags diag target-top target-bottom esta oem]}]
  (let [buf (ByteBuffer/allocate 22)
        id-bytes (.getBytes "Art-Net\u0000" StandardCharsets/US_ASCII)]
    (.put buf id-bytes)
    (.put buf (byte 0))
    (.put buf (unchecked-byte 0x20))
    (.put buf (byte 0))
    (.put buf (unchecked-byte const/protocol-version))
    (.put buf (unchecked-byte (or flags 0)))
    (.put buf (unchecked-byte (or diag 0)))
    (.put buf
          (unchecked-byte
            (bit-and (unsigned-bit-shift-right (or target-top 0) 8) 0xFF)))
    (.put buf (unchecked-byte (bit-and (or target-top 0) 0xFF)))
    (.put buf
          (unchecked-byte
            (bit-and (unsigned-bit-shift-right (or target-bottom 0) 8) 0xFF)))
    (.put buf (unchecked-byte (bit-and (or target-bottom 0) 0xFF)))
    (.put buf
          (unchecked-byte (bit-and (unsigned-bit-shift-right (or esta 0) 8)
                                   0xFF)))
    (.put buf (unchecked-byte (bit-and (or esta 0) 0xFF)))
    (.put buf
          (unchecked-byte (bit-and (unsigned-bit-shift-right (or oem 0) 8)
                                   0xFF)))
    (.put buf (unchecked-byte (bit-and (or oem 0) 0xFF)))
    (.flip buf)
    buf))

(defn- put-byte!
  [^bytes arr idx value]
  (aset arr idx (unchecked-byte (bit-and value 0xFF))))

(defn- base-frame
  [opcode length]
  (let [^bytes arr (byte-array length)
        id (.getBytes "Art-Net\u0000" StandardCharsets/US_ASCII)]
    (System/arraycopy id 0 arr 0 (alength id))
    (put-byte! arr 8 (bit-and opcode 0xFF))
    (put-byte! arr 9 (bit-and (unsigned-bit-shift-right opcode 8) 0xFF))
    (put-byte! arr 10 0)
    (put-byte! arr 11 const/protocol-version)
    arr))

(defn- buffer->octets
  [^ByteBuffer buf]
  (let [dup (.duplicate buf)
        arr (byte-array (.remaining dup))]
    (.get dup arr)
    (mapv #(bit-and 0xFF %) arr)))

(def ^:private known-artnet-opcodes (set (vals const/keyword->opcode)))

(def gen-unknown-opcode
  (gen/such-that #(not (contains? known-artnet-opcodes %))
                 (gen/choose 0 0xFFFF)
                 200))

(def ^:private truncated-opcode-specs
  [{:opcode   (const/keyword->opcode :artpollreply)
    :required const/artpollreply-length}
   {:opcode (const/keyword->opcode :artdmx), :required const/artdmx-header-size}
   {:opcode (const/keyword->opcode :artnzs), :required const/artnzs-header-size}
   {:opcode (const/keyword->opcode :artvlc), :required const/artvlc-header-size}
   {:opcode (const/keyword->opcode :artsync), :required 14}
   {:opcode   (const/keyword->opcode :arttimecode)
    :required const/arttimecode-length}
   {:opcode   (const/keyword->opcode :artdiagdata)
    :required const/artdiagdata-header-size}
   {:opcode (const/keyword->opcode :artinput), :required const/artinput-length}
   {:opcode   (const/keyword->opcode :artaddress)
    :required const/artaddress-length}
   {:opcode   (const/keyword->opcode :artipprog)
    :required const/artipprog-min-length}
   {:opcode   (const/keyword->opcode :arttodrequest)
    :required const/arttodrequest-base-length}
   {:opcode   (const/keyword->opcode :arttoddata)
    :required const/arttoddata-header-size}
   {:opcode   (const/keyword->opcode :arttodcontrol)
    :required const/arttodcontrol-length}
   {:opcode (const/keyword->opcode :artrdm), :required const/artrdm-header-size}
   {:opcode   (const/keyword->opcode :artrdmsub)
    :required const/artrdmsub-header-size}
   {:opcode   (const/keyword->opcode :artcommand)
    :required const/artcommand-header-size}
   {:opcode   (const/keyword->opcode :arttrigger)
    :required const/arttrigger-header-size}
   {:opcode   (const/keyword->opcode :artfirmwaremaster)
    :required const/artfirmwaremaster-header-size}
   {:opcode   (const/keyword->opcode :artfirmwarereply)
    :required const/artfirmwarereply-length}
   {:opcode   (const/keyword->opcode :artdatarequest)
    :required const/artdatarequest-length}
   {:opcode   (const/keyword->opcode :artdatareply)
    :required const/artdatareply-header-size}])

(def gen-truncated-frame
  (gen/let [{:keys [opcode required]} (gen/elements truncated-opcode-specs)
            limit (gen/choose 12 (dec required))]
    {:type     :truncated
     :opcode   opcode
     :required required
     :bytes    (base-frame opcode limit)}))

(def gen-unsupported-frame
  (gen/fmap (fn [opcode]
              {:type :unknown, :opcode opcode, :bytes (base-frame opcode 12)})
            gen-unknown-opcode))

(def gen-invalid-id-frame
  (let [id-length (alength artnet-id-bytes)]
    (gen/let [{:keys [opcode required]} (gen/elements truncated-opcode-specs)
              position (gen/choose 0 (dec id-length))
              candidate gen-byte]
      (let [bytes (base-frame opcode required)
            original (bit-and 0xFF (aget artnet-id-bytes position))
            corrupt (if (= candidate original)
                      (bit-and (inc candidate) 0xFF)
                      candidate)]
        (put-byte! bytes position corrupt)
        {:type     :invalid-id
         :opcode   opcode
         :position position
         :bytes    bytes}))))

(def gen-droppable-frame
  (gen/one-of [gen-truncated-frame gen-unsupported-frame gen-invalid-id-frame]))

(defn- artaddress-buffer
  []
  (let [^bytes arr (base-frame 0x6000 const/artaddress-length)
        short (.getBytes "Stage" StandardCharsets/US_ASCII)
        long (.getBytes "Main Floor Node" StandardCharsets/US_ASCII)]
    (put-byte! arr 12 0x85)
    (put-byte! arr 13 0x02)
    (System/arraycopy short 0 arr 14 (alength short))
    (System/arraycopy long 0 arr 32 (alength long))
    (doseq [[offset value] (map-indexed (fn [i v] [(+ 96 i) v])
                                        [0x81 0x00 0x00 0x00])]
      (put-byte! arr offset value))
    (doseq [[offset value] (map-indexed (fn [i v] [(+ 100 i) v])
                                        [0x82 0x00 0x00 0x00])]
      (put-byte! arr offset value))
    (put-byte! arr 104 0x83)
    (put-byte! arr 105 0x64)
    (put-byte! arr 106 0x02)
    (doto (ByteBuffer/wrap arr) (.limit const/artaddress-length))))

(defn- artipprog-buffer
  []
  (let [length (max const/artipprog-min-length 32)
        ^bytes arr (base-frame 0xF800 length)
        ip [10 0 0 5]
        mask [255 255 0 0]
        gateway [10 0 0 1]
        port 0x1234
        port-hi (bit-and (unsigned-bit-shift-right port 8) 0xFF)
        port-lo (bit-and port 0xFF)]
    (put-byte! arr 14 0x8F)
    (doseq [[i v] (map-indexed vector ip)] (put-byte! arr (+ 16 i) v))
    (doseq [[i v] (map-indexed vector mask)] (put-byte! arr (+ 20 i) v))
    (put-byte! arr 24 port-hi)
    (put-byte! arr 25 port-lo)
    (doseq [[i v] (map-indexed vector gateway)] (put-byte! arr (+ 26 i) v))
    (doto (ByteBuffer/wrap arr) (.limit length))))

(defn- build-artdiagdata-octets
  [{:keys [priority logical-port text message]}]
  (let [priority (bit-and (int (or priority 0x10)) 0xFF)
        logical-port (bit-and (int (or logical-port 0)) 0xFF)
        content (or text message "")
        source (.getBytes ^String content StandardCharsets/US_ASCII)
        copy-length (min 511 (alength source))
        payload-length (inc copy-length)
        total-length (+ const/artdiagdata-header-size payload-length)
        arr (byte-array total-length)
        opcode (const/keyword->opcode :artdiagdata)
        opcode-lo (bit-and opcode 0xFF)
        opcode-hi (bit-and (unsigned-bit-shift-right opcode 8) 0xFF)
        length-hi (bit-and (unsigned-bit-shift-right payload-length 8) 0xFF)
        length-lo (bit-and payload-length 0xFF)]
    (System/arraycopy artnet-id-bytes 0 arr 0 (alength artnet-id-bytes))
    (aset arr 8 (unchecked-byte opcode-lo))
    (aset arr 9 (unchecked-byte opcode-hi))
    (aset arr 10 (byte 0))
    (aset arr 11 (unchecked-byte const/protocol-version))
    (aset arr 12 (byte 0))
    (aset arr 13 (unchecked-byte priority))
    (aset arr 14 (unchecked-byte logical-port))
    (aset arr 15 (byte 0))
    (aset arr 16 (unchecked-byte length-hi))
    (aset arr 17 (unchecked-byte length-lo))
    (when (pos? copy-length)
      (System/arraycopy source 0 arr const/artdiagdata-header-size copy-length))
    (aset arr (+ const/artdiagdata-header-size copy-length) (byte 0))
    (mapv #(bit-and 0xFF %) arr)))

(defn- normalize-ip-bytes
  [v]
  (->> (concat (or v []) (repeat 0))
       (take 4)
       (mapv #(bit-and (int %) 0xFF))))

(defn- build-artipprogreply-octets
  [{:keys [ip subnet-mask gateway port dhcp?]
    :or   {subnet-mask [255 0 0 0], gateway [0 0 0 0], port 0x1936}}]
  (let [ip-bytes (normalize-ip-bytes ip)
        mask-bytes (normalize-ip-bytes subnet-mask)
        gateway-bytes (normalize-ip-bytes gateway)
        port-val (bit-and (int port) 0xFFFF)
        port-hi (bit-and (unsigned-bit-shift-right port-val 8) 0xFF)
        port-lo (bit-and port-val 0xFF)
        status (if dhcp? 0x40 0)
        arr (byte-array const/artipprogreply-length)
        opcode (const/keyword->opcode :artipprogreply)
        opcode-lo (bit-and opcode 0xFF)
        opcode-hi (bit-and (unsigned-bit-shift-right opcode 8) 0xFF)
        version-hi (bit-and (unsigned-bit-shift-right const/protocol-version 8)
                            0xFF)
        version-lo (bit-and const/protocol-version 0xFF)]
    (System/arraycopy artnet-id-bytes 0 arr 0 (alength artnet-id-bytes))
    (aset arr 8 (unchecked-byte opcode-lo))
    (aset arr 9 (unchecked-byte opcode-hi))
    (aset arr 10 (unchecked-byte version-hi))
    (aset arr 11 (unchecked-byte version-lo))
    (dotimes [idx 4] (aset arr (+ 12 idx) (byte 0)))
    (dotimes [idx 4] (aset arr (+ 16 idx) (unchecked-byte (nth ip-bytes idx))))
    (dotimes [idx 4]
      (aset arr (+ 20 idx) (unchecked-byte (nth mask-bytes idx))))
    (aset arr 24 (unchecked-byte port-hi))
    (aset arr 25 (unchecked-byte port-lo))
    (aset arr 26 (unchecked-byte status))
    (aset arr 27 (byte 0))
    (dotimes [idx 4]
      (aset arr (+ 28 idx) (unchecked-byte (nth gateway-bytes idx))))
    (aset arr 32 (byte 0))
    (aset arr 33 (byte 0))
    (mapv #(bit-and 0xFF %) arr)))

(defn- normalize-uid-bytes
  [uid]
  (if (and (sequential? uid) (= 6 (count uid)))
    (mapv #(bit-and (int %) 0xFF) uid)
    (throw (ex-info "ArtTodData fixture UID must be six octets" {:uid uid}))))

(defn- build-arttoddata-octets
  [{:keys [rdm-version port bind-index net command-response address uid-total
           block-count tod]
    :or   {rdm-version      1
           port             1
           bind-index       1
           net              0
           command-response 0
           address          0
           block-count      0}}]
  (let [uids (mapv normalize-uid-bytes (or tod []))
        uid-count (count uids)
        total (or uid-total uid-count)
        payload (* 6 uid-count)
        arr (byte-array (+ const/arttoddata-header-size payload))
        opcode (const/keyword->opcode :arttoddata)
        opcode-lo (bit-and opcode 0xFF)
        opcode-hi (bit-and (unsigned-bit-shift-right opcode 8) 0xFF)
        version-hi (bit-and (unsigned-bit-shift-right const/protocol-version 8)
                            0xFF)
        version-lo (bit-and const/protocol-version 0xFF)
        uid-total-hi (bit-and (unsigned-bit-shift-right total 8) 0xFF)
        uid-total-lo (bit-and total 0xFF)]
    (System/arraycopy artnet-id-bytes 0 arr 0 (alength artnet-id-bytes))
    (aset arr 8 (unchecked-byte opcode-lo))
    (aset arr 9 (unchecked-byte opcode-hi))
    (aset arr 10 (unchecked-byte version-hi))
    (aset arr 11 (unchecked-byte version-lo))
    (aset arr 12 (unchecked-byte (bit-and (int rdm-version) 0xFF)))
    (aset arr 13 (unchecked-byte (bit-and (int port) 0xFF)))
    (dotimes [idx 6] (aset arr (+ 14 idx) (byte 0)))
    (aset arr 20 (unchecked-byte (bit-and (int bind-index) 0xFF)))
    (aset arr 21 (unchecked-byte (bit-and (int net) 0xFF)))
    (aset arr 22 (unchecked-byte (bit-and (int command-response) 0xFF)))
    (aset arr 23 (unchecked-byte (bit-and (int address) 0xFF)))
    (aset arr 24 (unchecked-byte uid-total-hi))
    (aset arr 25 (unchecked-byte uid-total-lo))
    (aset arr 26 (unchecked-byte (bit-and (int block-count) 0xFF)))
    (aset arr 27 (unchecked-byte (bit-and uid-count 0xFF)))
    (doseq [[idx uid] (map-indexed vector uids)]
      (doseq [[byte-idx value] (map-indexed vector uid)]
        (aset arr
              (+ const/arttoddata-header-size (* idx 6) byte-idx)
              (unchecked-byte value))))
    (mapv #(bit-and 0xFF %) arr)))

(defn- arttodrequest-buffer
  []
  (let [addresses [0x12 0x34]
        length (+ const/arttodrequest-base-length (count addresses))
        ^bytes arr (base-frame 0x8000 length)]
    (put-byte! arr 21 0x04)
    (put-byte! arr 22 0x00)
    (put-byte! arr 23 (count addresses))
    (doseq [[idx value] (map-indexed vector addresses)]
      (put-byte! arr (+ 24 idx) value))
    (doto (ByteBuffer/wrap arr) (.limit length))))

(defn- arttodcontrol-buffer
  []
  (let [^bytes arr (base-frame 0x8200 const/arttodcontrol-length)]
    (put-byte! arr 21 0x04)
    (put-byte! arr 22 0x01)
    (put-byte! arr 23 0x56)
    (doto (ByteBuffer/wrap arr) (.limit const/arttodcontrol-length))))

(defn- artrdm-buffer
  []
  (let [payload (byte-array [1 2 3 4 5])
        length (+ const/artrdm-header-size (alength payload))
        ^bytes arr (base-frame 0x8300 length)]
    (put-byte! arr 12 0x01)
    ;; filler (byte 13) left as zero
    (doseq [idx (range 14 19)] (put-byte! arr idx 0))
    (put-byte! arr 19 0x08)
    (put-byte! arr 20 0x10)
    (put-byte! arr 21 0x04)
    (put-byte! arr 22 0x00)
    (put-byte! arr 23 0x56)
    (System/arraycopy payload 0 arr const/artrdm-header-size (alength payload))
    (doto (ByteBuffer/wrap arr) (.limit length))))

(defn- artfirmwaremaster-buffer
  [{:keys [type-code block-id firmware-length data-length]
    :or
    {type-code 0x00, block-id 0, firmware-length 0x00008212, data-length 16}}]
  (let [payload (byte-array data-length)
        length (+ const/artfirmwaremaster-header-size (alength payload))
        ^bytes arr (base-frame 0xF200 length)]
    (put-byte! arr 12 0x00)
    (put-byte! arr 13 0x00)
    (put-byte! arr 14 type-code)
    (put-byte! arr 15 block-id)
    (put-byte! arr
               16
               (bit-and (unsigned-bit-shift-right firmware-length 24) 0xFF))
    (put-byte! arr
               17
               (bit-and (unsigned-bit-shift-right firmware-length 16) 0xFF))
    (put-byte! arr
               18
               (bit-and (unsigned-bit-shift-right firmware-length 8) 0xFF))
    (put-byte! arr 19 (bit-and firmware-length 0xFF))
    ;; spare bytes already default to zero
    (System/arraycopy payload
                      0
                      arr
                      const/artfirmwaremaster-header-size
                      (alength payload))
    (doto (ByteBuffer/wrap arr) (.limit length))))

(defn- artfirmwarereply-buffer
  [status-code]
  (let [^bytes arr (base-frame 0xF300 const/artfirmwarereply-length)]
    (put-byte! arr 12 0x00)
    (put-byte! arr 13 0x00)
    (put-byte! arr 14 status-code)
    (doto (ByteBuffer/wrap arr) (.limit const/artfirmwarereply-length))))

(deftest compose-split-roundtrip
  (assert-property :port-address compose-split-prop 500))

(deftest encode-decode-artdmx
  (assert-property :artdmx encode-decode-artdmx-prop 300))

(deftest encode-decode-artnzs
  (assert-property :artnzs encode-decode-artnzs-prop 300))

(deftest encode-decode-artvlc
  (assert-property :artvlc encode-decode-artvlc-prop 200))

(deftest encode-decode-artinput
  (assert-property :artinput encode-decode-artinput-prop 200))

(deftest encode-decode-generic-ops
  (assert-property :generic-op encode-decode-generic-op-prop 300))

(deftest encode-decode-artcommand
  (let [text "SwoutText=Playback&"
        packet {:op :artcommand, :esta-man 0x1234, :text text}
        buf (dispatch/encode packet)
        decoded (dispatch/decode buf)
        ^ByteBuffer payload (:payload decoded)
        bytes (byte-array (.remaining payload))]
    (.get payload bytes)
    (is (= :artcommand (:op decoded)))
    (is (= 0x1234 (:esta-man decoded)))
    (is (= text (:text decoded)))
    (is (= (inc (.length text)) (:length decoded)))
    (is (zero? (aget bytes (dec (:length decoded))))))
  (let [text "SwinText=Record&"
        raw (.getBytes text StandardCharsets/US_ASCII)
        packet {:op :artcommand, :data (ByteBuffer/wrap raw)}
        buf (dispatch/encode packet
                             (ByteBuffer/allocate const/artcommand-max-length))
        decoded (dispatch/decode buf)]
    (is (= text (:text decoded)))
    (is (= (inc (.length text)) (:length decoded)))))

(deftest encode-decode-artrdmsub
  (let [packet {:op           :artrdmsub
                :rdm-version  2
                :uid          [0 1 2 3 4 5]
                :command      :get
                :parameter-id 0x1234
                :sub-device   0x0001
                :sub-count    4}
        buf (dispatch/encode packet)
        decoded (dispatch/decode buf)
        ^ByteBuffer payload (:data decoded)]
    (is (= :artrdmsub (:op decoded)))
    (is (= 2 (:rdm-version decoded)))
    (is (= [0 1 2 3 4 5] (:uid decoded)))
    (is (= :get (:command decoded)))
    (is (= 0x20 (:command-class decoded)))
    (is (= 0x1234 (:parameter-id decoded)))
    (is (= 1 (:sub-device decoded)))
    (is (= 4 (:sub-count decoded)))
    (is (zero? (:payload-length decoded)))
    (is (= 0 (.remaining payload))))
  (let [values [0x1111 0x2222 0x3333]
        packet {:op           :artrdmsub
                :rdm-version  5
                :uid          [10 11 12 13 14 15]
                :command      :get-response
                :parameter-id 0x4321
                :sub-device   0x0002
                :sub-count    (count values)
                :values       values}
        buf (dispatch/encode packet)
        decoded (dispatch/decode buf)
        ^ByteBuffer payload (:data decoded)
        payload-bytes (byte-array (.remaining payload))
        expected (byte-array (* 2 (count values)))]
    (.get payload payload-bytes)
    (doseq [[idx value] (map-indexed vector values)]
      (let [offset (* idx 2)
            hi (bit-and (unsigned-bit-shift-right value 8) 0xFF)
            lo (bit-and value 0xFF)]
        (aset expected offset (unchecked-byte hi))
        (aset expected (inc offset) (unchecked-byte lo))))
    (is (= :artrdmsub (:op decoded)))
    (is (= :get-response (:command decoded)))
    (is (= 0x21 (:command-class decoded)))
    (is (= values (:values decoded)))
    (is (= (* 2 (count values)) (:payload-length decoded)))
    (is (Arrays/equals expected payload-bytes))))

(deftest encode-decode-artrdm
  (let [payload (byte-array (map #(unchecked-byte %) (range 1 30)))
        packet {:op             :artrdm
                :rdm-version    2
                :fifo-available 3
                :fifo-max       5
                :net            7
                :command        0
                :address        0x11
                :rdm-packet     (ByteBuffer/wrap payload)}
        buf (dispatch/encode packet)
        decoded (dispatch/decode buf)
        ^ByteBuffer payload-view (:rdm-packet decoded)
        bytes (byte-array (.remaining payload-view))]
    (.get payload-view bytes)
    (is (= :artrdm (:op decoded)))
    (is (= 2 (:rdm-version decoded)))
    (is (= 3 (:fifo-available decoded)))
    (is (= 5 (:fifo-max decoded)))
    (is (= 7 (:net decoded)))
    (is (= 0 (:command decoded)))
    (is (= 0x11 (:address decoded)))
    (is (= (alength payload) (:payload-length decoded)))
    (is (Arrays/equals payload bytes))))

(deftest encode-decode-artdatarequest
  (let [packet {:op           :artdatarequest
                :esta-man     0x1A2B
                :oem          0x3456
                :request-type :dr-url-support}
        buf (dispatch/encode packet
                             (ByteBuffer/allocate const/artdatarequest-length))
        decoded (dispatch/decode buf)]
    (is (= :artdatarequest (:op decoded)))
    (is (= 0x1A2B (:esta-man decoded)))
    (is (= 0x3456 (:oem decoded)))
    (is (= 0x0003 (:request decoded)))
    (is (= :dr-url-support (:request-type decoded)))))

(deftest encode-decode-artdatareply
  (let [url "https://example.invalid/support"
        packet {:op           :artdatareply
                :esta-man     0x1234
                :oem          0x5678
                :request-type :dr-url-support
                :text         url}
        buf (dispatch/encode packet)
        decoded (dispatch/decode buf)
        ^ByteBuffer payload (:data decoded)
        bytes (byte-array (.remaining payload))]
    (.get payload bytes)
    (is (= :artdatareply (:op decoded)))
    (is (= url (:text decoded)))
    (is (= :dr-url-support (:request-type decoded)))
    (is (= (inc (.length url)) (:payload-length decoded)))
    (is (zero? (aget bytes (dec (:payload-length decoded))))))
  (let [raw (.getBytes "custom-data" StandardCharsets/US_ASCII)
        packet {:op :artdatareply, :request 0x8001, :data (ByteBuffer/wrap raw)}
        buf (dispatch/encode
              packet
              (ByteBuffer/allocate
                (+ const/artdatareply-header-size (alength raw) 1)))
        decoded (dispatch/decode buf)
        ^ByteBuffer payload (:data decoded)
        bytes (byte-array (.remaining payload))]
    (.get payload bytes)
    (is (= :dr-man-spec (:request-type decoded)))
    (is (= (inc (alength raw)) (:payload-length decoded)))
    (is (= 0 (aget bytes (dec (:payload-length decoded)))))))

(deftest encode-arttoddata-fixture-matches-octets
  (let [tod [[0x09 0x0A 0x0B 0x0C 0x0D 0x0E] [0xAA 0xBB 0xCC 0xDD 0xEE 0xFF]
             [0x10 0x20 0x30 0x40 0x50 0x60]]
        packet {:op               :arttoddata
                :rdm-version      2
                :port             3
                :bind-index       4
                :net              5
                :command-response 0xA5
                :address          0x7C
                :uid-total        5
                :block-count      1
                :tod              tod}
        buf (dispatch/encode packet
                             (ByteBuffer/allocate (+
                                                    const/arttoddata-header-size
                                                    (* 6 (count tod)))))
        arr (byte-array (.remaining buf))
        _ (.get buf arr)
        encoded (mapv #(bit-and 0xFF %) arr)
        expected (build-arttoddata-octets packet)]
    (is (= expected encoded))))

(deftest encode-decode-arttrigger
  (let [payload (byte-array [0x7F 0x00 0xAA 0x10])
        packet {:op       :arttrigger
                :oem      0x1234
                :key-type :key-macro
                :sub-key  0x09
                :data     payload}
        buf (dispatch/encode packet
                             (ByteBuffer/allocate const/arttrigger-length))
        decoded (dispatch/decode buf)
        ^ByteBuffer view (:payload decoded)
        bytes (byte-array (.remaining view))]
    (.get view bytes)
    (let [decoded-ints (vec (map #(bit-and 0xFF %) bytes))
          payload-ints (vec (map #(bit-and 0xFF %) payload))]
      (is (= :arttrigger (:op decoded)))
      (is (= 0x1234 (:oem decoded)))
      (is (= 1 (:key decoded)))
      (is (= :key-macro (:key-type decoded)))
      (is (= 0x09 (:sub-key decoded)))
      (is (= const/arttrigger-max-data (:data-length decoded)))
      (is (= payload-ints (subvec decoded-ints 0 (alength payload))))
      (is (every? zero? (subvec decoded-ints (alength payload)))))))

(deftest artinput-packet-builder
  (let [packet (config/artinput-packet
                 {:bind-index 7, :num-ports 3, :disable-ports #{0 2}})]
    (is (= :artinput (:op packet)))
    (is (= 7 (:bind-index packet)))
    (is (= 3 (:num-ports packet)))
    (is (= [true false true false] (:disabled packet)))
    (is (= [const/artinput-disable-bit 0 const/artinput-disable-bit 0]
           (:inputs packet)))
    (let [buf (dispatch/encode packet
                               (ByteBuffer/allocate const/artinput-length))
          decoded (dispatch/decode buf)]
      (is (= (:inputs packet) (:inputs decoded)))
      (is (= (:disabled packet) (:disabled decoded)))
      (is (= 7 (:bind-index decoded)))
      (is (= 3 (:num-ports decoded))))))

(deftest decode-artpoll
  (let [buf (artpoll-frame {:flags         0x2C             ; target + diag request + unicast
                            :diag          0x40
                            :target-top    0x0010
                            :target-bottom 0x0001
                            :esta          0x1234
                            :oem           0x5678})
        packet (dispatch/decode buf)]
    (is (= :artpoll (:op packet)))
    (is (= 0x2C (:talk-to-me packet)))
    (is (= 0x40 (:priority packet)))
    (is (:target-enabled? packet))
    (is (= 0x0010 (:target-top packet)))
    (is (= 0x0001 (:target-bottom packet)))
    (is (:diag-request? packet))
    (is (:diag-unicast? packet))
    (is (= 0x1234 (:esta-man packet)))
    (is (= 0x5678 (:oem packet)))))

(deftest decode-artsync
  (let [buf (control-frame 0x5200 0x01 0x02)
        packet (dispatch/decode buf)]
    (is (= :artsync (:op packet)))
    (is (= 0x01 (:aux1 packet)))
    (is (= 0x02 (:aux2 packet)))))

(defn- arttimecode-buffer
  ([^bytes bytes] (arttimecode-buffer bytes (alength bytes)))
  ([^bytes bytes limit]
   (let [max-length (alength bytes)
         length (int (max 0 (min limit max-length)))
         copy (Arrays/copyOf bytes length)]
     (doto (ByteBuffer/wrap copy) (.limit length)))))

(deftest encode-arttimecode-fixture-matches-octets
  (let [packet (assoc fixtures/arttimecode-default-frame :op :arttimecode)
        buf (dispatch/encode packet
                             (ByteBuffer/allocate const/arttimecode-length))
        encoded (buffer->octets buf)]
    (is (= const/arttimecode-length (count encoded)))
    (is (= (builders/build-arttimecode-octets
             fixtures/arttimecode-default-frame)
           encoded))))

(def encode-decode-arttimecode-prop
  (prop/for-all
    [payload gen-timecode]
    (let [packet (assoc payload :op :arttimecode)
          buf (dispatch/encode packet
                               (ByteBuffer/allocate const/arttimecode-length))
          decoded (dispatch/decode buf)
          fields [:stream-id :frames :seconds :minutes :hours :type]
          roundtrip (dispatch/encode decoded
                                     (ByteBuffer/allocate
                                       const/arttimecode-length))]
      (and (= :arttimecode (:op decoded))
           (= const/protocol-version (:proto decoded))
           (= 0 (:unused decoded))
           (= (select-keys decoded fields) (select-keys packet fields))
           (= (buffer->octets buf) (buffer->octets roundtrip))))))

(deftest encode-decode-arttimecode-roundtrip
  (assert-property :arttimecode encode-decode-arttimecode-prop 200))

(deftest decode-arttimecode-frame
  (let [buf (arttimecode-buffer (builders/build-arttimecode-bytes
                                  fixtures/arttimecode-nonzero-unused-frame))
        packet (dispatch/decode buf)
        expected fixtures/arttimecode-nonzero-unused-frame
        field-keys [:stream-id :frames :seconds :minutes :hours :type]
        encoded (dispatch/encode packet
                                 (ByteBuffer/allocate const/arttimecode-length))
        encoded-octets (buffer->octets encoded)]
    (is (= :arttimecode (:op packet)))
    (is (= (:proto expected) (:proto packet)))
    (is (= (:unused expected) (:unused packet)))
    (is (= (select-keys expected field-keys) (select-keys packet field-keys)))
    (is (= 0 (nth encoded-octets 12)))
    (is (= (builders/build-arttimecode-octets
             fixtures/arttimecode-default-frame)
           encoded-octets))))

(deftest decode-arttimecode-malformed-critical-fields
  (doseq [[limit label] [[12 "filler"] [13 "StreamId"] [18 "Type"]]]
    (let [buf (arttimecode-buffer (builders/build-arttimecode-bytes
                                    fixtures/arttimecode-default-frame)
                                  limit)]
      (is (thrown-with-msg? ExceptionInfo
                            #"Truncated ArtTimeCode"
                            (dispatch/decode buf))
          (str "Missing " label " should raise")))))

(def ^:private decode-arttimecode-fuzz-prop
  (prop/for-all [{:keys [stream-id frames seconds minutes hours type]}
                 gen-timecode unused gen-byte proto gen-u16]
    (let [opcode (const/keyword->opcode :arttimecode)
          frame (base-frame opcode const/arttimecode-length)
          proto-hi (bit-and (unsigned-bit-shift-right proto 8) 0xFF)
          proto-lo (bit-and proto 0xFF)]
      (put-byte! frame 10 proto-hi)
      (put-byte! frame 11 proto-lo)
      (put-byte! frame 12 unused)
      (put-byte! frame 13 stream-id)
      (put-byte! frame 14 frames)
      (put-byte! frame 15 seconds)
      (put-byte! frame 16 minutes)
      (put-byte! frame 17 hours)
      (put-byte! frame 18 type)
      (let [buf (doto (ByteBuffer/wrap frame)
                  (.limit const/arttimecode-length))
            decoded (dispatch/decode buf)]
        (and (= :arttimecode (:op decoded))
             (= proto (:proto decoded))
             (= unused (:unused decoded))
             (= stream-id (:stream-id decoded))
             (= frames (:frames decoded))
             (= seconds (:seconds decoded))
             (= minutes (:minutes decoded))
             (= hours (:hours decoded))
             (= type (:type decoded)))))))

(deftest decode-arttimecode-frame-fuzz
  (assert-property :decode-arttimecode-frame decode-arttimecode-fuzz-prop 200))

(def decode-artsync-prop
  (prop/for-all [aux1 gen-byte aux2 gen-byte]
    (let [opcode (const/keyword->opcode :artsync)
          buf (control-frame opcode aux1 aux2)
          packet (dispatch/decode buf)]
      (and (= :artsync (:op packet))
           (= aux1 (:aux1 packet))
           (= aux2 (:aux2 packet))))))

(deftest decode-artsync-randomized
  (assert-property :artsync decode-artsync-prop 200))

(deftest encode-decode-artdiagdata
  (let [text "Hello diagnostics"
        packet {:op :artdiagdata, :priority 0x80, :logical-port 7, :text text}
        buf (dispatch/encode packet (ByteBuffer/allocate 1024))
        decoded (dispatch/decode buf)]
    (is (= :artdiagdata (:op decoded)))
    (is (= 0x80 (:priority decoded)))
    (is (= 7 (:logical-port decoded)))
    (is (= text (:text decoded)))
    (is (= (inc (.length text)) (:length decoded)))))

(deftest encode-artdiagdata-fixture-matches-octets
  (let [packet {:op           :artdiagdata
                :priority     0xCC
                :logical-port 0x21
                :text         "Diagnostics: PSU voltage low"}
        buf (dispatch/encode packet
                             (ByteBuffer/allocate
                               (+ const/artdiagdata-header-size 512)))
        arr (byte-array (.remaining buf))
        _ (.get buf arr)
        encoded (mapv #(bit-and 0xFF %) arr)
        expected (build-artdiagdata-octets packet)]
    (is (= expected encoded))))

(deftest decode-artaddress-packet
  (let [packet (dispatch/decode (artaddress-buffer))]
    (is (= :artaddress (:op packet)))
    (is (= 0x85 (:net-switch packet)))
    (is (= 2 (:bind-index packet)))
    (is (= "Stage" (:short-name packet)))
    (is (= "Main Floor Node" (:long-name packet)))
    (is (= [0x81 0 0 0] (:sw-in packet)))
    (is (= [0x82 0 0 0] (:sw-out packet)))
    (is (= 0x83 (:sub-switch packet)))
    (is (= 0x64 (:acn-priority packet)))
    (is (= 0x02 (:command packet)))))

(deftest decode-artipprog-packet
  (let [packet (dispatch/decode (artipprog-buffer))]
    (is (= :artipprog (:op packet)))
    (is (= 0x8F (:command packet)))
    (is (= [10 0 0 5] (:prog-ip packet)))
    (is (= [255 255 0 0] (:prog-sm packet)))
    (is (= 0x1234 (:prog-port packet)))
    (is (= [10 0 0 1] (:prog-gateway packet)))))

(deftest encode-artipprogreply-fixture-matches-octets
  (let [packet {:op          :artipprogreply
                :ip          [10 0 0 20]
                :subnet-mask [255 255 0 0]
                :gateway     [10 0 0 1]
                :port        0x1357
                :dhcp?       true}
        buf (dispatch/encode packet
                             (ByteBuffer/allocate const/artipprogreply-length))
        arr (byte-array (.remaining buf))
        _ (.get buf arr)
        encoded (mapv #(bit-and 0xFF %) arr)
        expected (build-artipprogreply-octets packet)]
    (is (= const/artipprogreply-length (alength arr)))
    (is (= expected encoded))))

(deftest decode-arttodrequest
  (let [packet (dispatch/decode (arttodrequest-buffer))]
    (is (= :arttodrequest (:op packet)))
    (is (= 0x04 (:net packet)))
    (is (= 2 (:add-count packet)))
    (is (= [0x12 0x34] (:addresses packet)))))

(deftest encode-decode-arttoddata
  (let [tod [[0 1 2 3 4 5] [10 11 12 13 14 15]]
        packet {:op               :arttoddata
                :rdm-version      1
                :port             2
                :bind-index       3
                :net              4
                :command-response 0
                :address          0x56
                :uid-total        2
                :block-count      0
                :tod              tod}
        buf (dispatch/encode packet (ByteBuffer/allocate 256))
        decoded (dispatch/decode buf)]
    (is (= :arttoddata (:op decoded)))
    (is (= 1 (:rdm-version decoded)))
    (is (= 2 (:port decoded)))
    (is (= 3 (:bind-index decoded)))
    (is (= 4 (:net decoded)))
    (is (= 0x56 (:address decoded)))
    (is (= 2 (:uid-count decoded)))
    (is (= tod (:tod decoded)))
    (is (= 2 (:uid-total decoded)))))

(deftest decode-arttodcontrol
  (let [packet (dispatch/decode (arttodcontrol-buffer))]
    (is (= :arttodcontrol (:op packet)))
    (is (= 0x04 (:net packet)))
    (is (= 0x01 (:command packet)))
    (is (= 0x56 (:address packet)))))

(deftest decode-artrdm
  (let [packet (dispatch/decode (artrdm-buffer))
        ^ByteBuffer payload (:rdm-packet packet)
        bytes (byte-array (.remaining payload))]
    (.get payload bytes)
    (is (= :artrdm (:op packet)))
    (is (= 0x08 (:fifo-available packet)))
    (is (= 0x10 (:fifo-max packet)))
    (is (= 0x04 (:net packet)))
    (is (= 0x00 (:command packet)))
    (is (= 0x56 (:address packet)))
    (is (= [1 2 3 4 5] (map #(bit-and 0xFF %) bytes)))))

(deftest decode-artfirmwaremaster
  (let [buf (artfirmwaremaster-buffer {:type-code       0x00
                                       :block-id        0x05
                                       :firmware-length 0x00008212
                                       :data-length     32})
        packet (dispatch/decode buf)
        ^ByteBuffer data (:data packet)
        bytes (byte-array (.remaining data))]
    (.get data bytes)
    (is (= :artfirmwaremaster (:op packet)))
    (is (= :firmware-first (:block-type packet)))
    (is (= :first (:stage packet)))
    (is (= 0x05 (:block-id packet)))
    (is (= 0x00008212 (:firmware-length packet)))
    (is (= 32 (:data-length packet)))
    (is (= 32 (alength bytes)))))

(deftest encode-artfirmwarereply-fixture-matches-octets
  (let [packet {:op :artfirmwarereply, :status :all-good}
        buf (dispatch/encode packet
                             (ByteBuffer/allocate
                               const/artfirmwarereply-length))
        arr (byte-array (.remaining buf))
        _ (.get buf arr)
        encoded (mapv #(bit-and 0xFF %) arr)
        fixture (artfirmwarereply-buffer 0x01)
        expected-arr (byte-array (.remaining fixture))
        _ (.get fixture expected-arr)
        expected (mapv #(bit-and 0xFF %) expected-arr)]
    (is (= const/artfirmwarereply-length (alength arr)))
    (is (= expected encoded)))
  (let [packet {:op :artfirmwarereply, :status-code 0xFF}
        buf (dispatch/encode packet
                             (ByteBuffer/allocate
                               const/artfirmwarereply-length))
        arr (byte-array (.remaining buf))
        _ (.get buf arr)
        encoded (mapv #(bit-and 0xFF %) arr)
        fixture (artfirmwarereply-buffer 0xFF)
        expected-arr (byte-array (.remaining fixture))
        _ (.get fixture expected-arr)
        expected (mapv #(bit-and 0xFF %) expected-arr)]
    (is (= expected encoded))))

(deftest decode-artfirmwarereply
  (let [packet (dispatch/decode (artfirmwarereply-buffer 0x01))]
    (is (= :artfirmwarereply (:op packet)))
    (is (= :all-good (:status packet)))
    (is (= 0x01 (:status-code packet)))))

(defn- clamp-fixed-string
  [value width]
  (let [limit (max 0 (dec width))
        s (or value "")]
    (if (> (count s) limit) (subs s 0 limit) s)))

(defn- normalize-artpollreply-page
  [page]
  (-> page
      (dissoc :port-addresses :ports)
      (update :bind-ip #(or % (:ip page)))
      (update :short-name clamp-fixed-string 18)
      (update :long-name clamp-fixed-string 64)
      (update :node-report clamp-fixed-string 64)))

(def ^:private artpollreply-field-keys
  (vec (keys (normalize-artpollreply-page fixtures/artpollreply-page))))

(deftest encode-artpollreply-fixture-matches-octets
  (doseq [page fixtures/artpollreply-pages]
    (let [expected-octets (builders/build-artpollreply-octets page)
          packet (assoc page :op :artpollreply)
          buf (dispatch/encode packet
                               (ByteBuffer/allocate const/artpollreply-length))
          arr (byte-array (.remaining buf))
          _ (.get buf arr)
          encoded (mapv #(bit-and 0xFF %) arr)]
      (is (= const/artpollreply-length (alength arr)))
      (is (= expected-octets encoded)))))

(def encode-artpollreply-random-prop
  (prop/for-all [overrides gen-artpollreply-overrides]
    (let [page (merge fixtures/artpollreply-page overrides)
          packet (assoc page :op :artpollreply)
          buf (dispatch/encode packet
                               (ByteBuffer/allocate
                                 const/artpollreply-length))
          arr (byte-array (.remaining buf))
          _ (.get buf arr)
          encoded (mapv #(bit-and 0xFF %) arr)
          expected (vec (builders/build-artpollreply-octets page))]
      (= expected encoded))))

(deftest encode-artpollreply-randomized-obeys-builder
  (assert-property :artpollreply-random encode-artpollreply-random-prop 200))

(deftest encode-artpollreply-includes-goodoutputb-and-status3
  (let [packet {:op                      :artpollreply
                :ip                      [2 0 0 10]
                :bind-ip                 [10 0 0 5]
                :port                    0x1936
                :version-hi              1
                :version-lo              2
                :net-switch              0x01
                :sub-switch              0x02
                :oem                     0x1234
                :ubea-version            3
                :status1                 0x04
                :esta-man                0x4567
                :short-name              "node"
                :long-name               "Multi Port Node"
                :node-report             "#0001 [0001] OK"
                :num-ports               2
                :port-types              [0xC0 0 0 0]
                :good-input              [0x80 0 0 0]
                :good-output-a           [0x90 0 0 0]
                :good-output-b           [0xAA 0x55 0xFF 0x00]
                :sw-in                   [0x01 0 0 0]
                :sw-out                  [0x02 0 0 0]
                :acn-priority            120
                :sw-macro                0x11
                :sw-remote               0x22
                :style                   0x33
                :mac                     [0xDE 0xAD 0xBE 0xEF 0x00 0x01]
                :bind-index              2
                :status2                 0x5A
                :status3                 0xA5
                :default-responder       [1 2 3 4 5 6]
                :user-hi                 0x12
                :user-lo                 0x34
                :refresh-rate            0x0123
                :background-queue-policy 0x07}
        buf (dispatch/encode packet
                             (ByteBuffer/allocate const/artpollreply-length))
        arr (byte-array (.remaining buf))
        dup (.duplicate buf)
        scan (.duplicate buf)
        field-lengths [8 2 4 2 1 1 1 1 2 1 1 2 18 64 64 2 4 4 4 4 4 1 1 1 3 1 6
                       4]
        skip-total (reduce + field-lengths)]
    (.get dup arr)
    (is (= const/artpollreply-length (alength arr)))
    (.position scan skip-total)
    (is (= 2 (bit-and 0xFF (.get scan))))
    (is (= 0x5A (bit-and 0xFF (.get scan))))
    (let [good-output-b (byte-array 4)]
      (.get scan good-output-b)
      (is (= [0xAA 0x55 0xFF 0x00] (map #(bit-and 0xFF %) good-output-b))))
    (is (= 0xA5 (bit-and 0xFF (.get scan))))
    (let [default-resp (byte-array 6)]
      (.get scan default-resp)
      (is (= [1 2 3 4 5 6] (map #(bit-and 0xFF %) default-resp))))
    (is (= 0x12 (bit-and 0xFF (.get scan))))
    (is (= 0x34 (bit-and 0xFF (.get scan))))
    (is (= 0x01 (bit-and 0xFF (.get scan))))
    (is (= 0x23 (bit-and 0xFF (.get scan))))
    (is (= 0x07 (bit-and 0xFF (.get scan))))))

(deftest decode-artpollreply-fixtures-roundtrip
  (doseq [page fixtures/artpollreply-pages]
    (let [octets (builders/build-artpollreply-octets page)
          bytes (byte-array (map unchecked-byte octets))
          packet (dispatch/decode (ByteBuffer/wrap bytes))
          expected (normalize-artpollreply-page page)
          actual (select-keys packet artpollreply-field-keys)
          roundtrip (dispatch/encode packet
                                     (ByteBuffer/allocate
                                       const/artpollreply-length))]
      (is (= expected actual))
      (is (= octets (buffer->octets roundtrip))))))

(def decode-artpollreply-prop
  (prop/for-all [overrides gen-artpollreply-overrides]
    (let [page (normalize-artpollreply-page
                 (merge fixtures/artpollreply-page overrides))
          packet (assoc page :op :artpollreply)
          buf (dispatch/encode packet
                               (ByteBuffer/allocate
                                 const/artpollreply-length))
          decoded (dispatch/decode buf)
          actual (select-keys decoded artpollreply-field-keys)
          roundtrip (dispatch/encode decoded
                                     (ByteBuffer/allocate
                                       const/artpollreply-length))]
      (and (= page actual)
           (= (buffer->octets buf) (buffer->octets roundtrip))))))

(deftest decode-artpollreply-randomized-roundtrip
  (assert-property :decode-artpollreply decode-artpollreply-prop 200))

(def artpollreply-frame-roundtrip-prop
  (prop/for-all [overrides gen-artpollreply-overrides]
    (let [raw-page (merge fixtures/artpollreply-page overrides)
          normalized (normalize-artpollreply-page raw-page)
          octets (vec (builders/build-artpollreply-octets raw-page))
          bytes (byte-array (map unchecked-byte octets))
          decoded (dispatch/decode (ByteBuffer/wrap bytes))
          re-encoded (dispatch/encode decoded
                                      (ByteBuffer/allocate
                                        const/artpollreply-length))]
      (and (= normalized
              (select-keys decoded artpollreply-field-keys))
           (= octets (buffer->octets re-encoded))))))

(deftest decode-artpollreply-frame-roundtrip
  (assert-property :artpollreply-frame artpollreply-frame-roundtrip-prop 150))

(def ^:private malformed-frame-fuzz-prop
  (prop/for-all [frames (gen/vector gen-droppable-frame 64)]
    (every? (fn [{:keys [^bytes bytes type opcode position]}]
              (let [buf (ByteBuffer/wrap bytes)]
                (try (dispatch/decode buf)
                     false
                     (catch ExceptionInfo ex
                       (let [data (ex-data ex)]
                         (case type
                           :unknown (= opcode (:opcode data))
                           :truncated (map? data)
                           :invalid-id (= position (:position data))
                           false))))))
            frames)))

(deftest malformed-frame-fuzzing
  (assert-property :malformed-frame malformed-frame-fuzz-prop 200))

(def ^:private unsupported-opcode-decode-prop
  (prop/for-all [opcode gen-unknown-opcode]
    (let [bytes (base-frame opcode 12)
          buf (doto (ByteBuffer/wrap bytes) (.limit 12))]
      (try (dispatch/decode buf)
           false
           (catch ExceptionInfo ex
             (= opcode (:opcode (ex-data ex))))))))

(deftest unsupported-opcode-assertions
  (is (thrown-with-msg? ExceptionInfo
                        #"Unsupported opcode"
                        (dispatch/encode {:op :not-a-real-op})))
  (assert-property :unsupported-opcode unsupported-opcode-decode-prop 200))

;; These tests ensure codec.clj constants stay aligned with impl/codec/spec.clj
;; declarative packet definitions. This guards against drift between the
;; hand-crafted codec and the data-driven specs.

(require '[clj-artnet.impl.protocol.codec.spec :as spec])

(deftest codec-constants-match-spec-sizes
  ;; Verify packet size constants match spec-computed sizes
  (is (= const/artpollreply-length
         (spec/spec-header-size spec/art-poll-reply-spec))
      "ArtPollReply size mismatch")
  (is (= const/artdmx-header-size (spec/spec-header-size spec/art-dmx-spec))
      "ArtDmx header size mismatch")
  (is (= const/artnzs-header-size (spec/spec-header-size spec/art-nzs-spec))
      "ArtNzs header size mismatch")
  (is (= const/artinput-length (spec/spec-header-size spec/art-input-spec))
      "ArtInput size mismatch")
  (is (= const/artaddress-length (spec/spec-header-size spec/art-address-spec))
      "ArtAddress size mismatch")
  (is (= const/arttimecode-length
         (spec/spec-header-size spec/art-timecode-spec))
      "ArtTimeCode size mismatch")
  (is (= const/arttoddata-header-size
         (spec/spec-header-size spec/art-tod-data-spec))
      "ArtTodData header size mismatch")
  (is (= const/arttodcontrol-length
         (spec/spec-header-size spec/art-tod-control-spec))
      "ArtTodControl size mismatch")
  (is (= const/artrdm-header-size (spec/spec-header-size spec/art-rdm-spec))
      "ArtRdm header size mismatch")
  (is (= const/artrdmsub-header-size
         (spec/spec-header-size spec/art-rdm-sub-spec))
      "ArtRdmSub header size mismatch")
  (is (= const/artfirmwarereply-length
         (spec/spec-header-size spec/art-firmware-reply-spec))
      "ArtFirmwareReply size mismatch")
  (is (= const/artipprogreply-length
         (spec/spec-header-size spec/art-ip-prog-reply-spec))
      "ArtIpProgReply size mismatch")
  (is (= const/artdiagdata-header-size
         (spec/spec-header-size spec/art-diag-data-spec))
      "ArtDiagData header size mismatch")
  (is (= const/arttrigger-header-size
         (spec/spec-header-size spec/art-trigger-spec))
      "ArtTrigger header size mismatch")
  (is (= const/artcommand-header-size
         (spec/spec-header-size spec/art-command-spec))
      "ArtCommand header size mismatch")
  (is (= const/artdatarequest-length
         (spec/spec-header-size spec/art-data-request-spec))
      "ArtDataRequest size mismatch")
  (is (= const/artdatareply-header-size
         (spec/spec-header-size spec/art-data-reply-spec))
      "ArtDataReply header size mismatch"))

(deftest codec-opcodes-match-spec-opcodes
  ;; Verify opcode values match between codec and spec
  (is (= (const/keyword->opcode :artpoll) spec/op-poll)
      "ArtPoll opcode mismatch")
  (is (= (const/keyword->opcode :artpollreply) spec/op-poll-reply)
      "ArtPollReply opcode mismatch")
  (is (= (const/keyword->opcode :artdmx) spec/op-dmx) "ArtDmx opcode mismatch")
  (is (= (const/keyword->opcode :artnzs) spec/op-nzs) "ArtNzs opcode mismatch")
  (is (= (const/keyword->opcode :artsync) spec/op-sync)
      "ArtSync opcode mismatch")
  (is (= (const/keyword->opcode :artaddress) spec/op-address)
      "ArtAddress opcode mismatch")
  (is (= (const/keyword->opcode :artinput) spec/op-input)
      "ArtInput opcode mismatch")
  (is (= (const/keyword->opcode :artdiagdata) spec/op-diag-data)
      "ArtDiagData opcode mismatch")
  (is (= (const/keyword->opcode :arttimecode) spec/op-timecode)
      "ArtTimeCode opcode mismatch")
  (is (= (const/keyword->opcode :arttrigger) spec/op-trigger)
      "ArtTrigger opcode mismatch")
  (is (= (const/keyword->opcode :artrdm) spec/op-rdm) "ArtRdm opcode mismatch")
  (is (= (const/keyword->opcode :artrdmsub) spec/op-rdm-sub)
      "ArtRdmSub opcode mismatch")
  (is (= (const/keyword->opcode :artipprog) spec/op-ip-prog)
      "ArtIpProg opcode mismatch")
  (is (= (const/keyword->opcode :artipprogreply) spec/op-ip-prog-reply)
      "ArtIpProgReply opcode mismatch")
  (is (= (const/keyword->opcode :artfirmwaremaster) spec/op-firmware-master)
      "ArtFirmwareMaster opcode mismatch")
  (is (= (const/keyword->opcode :artfirmwarereply) spec/op-firmware-reply)
      "ArtFirmwareReply opcode mismatch"))

(deftest codec-protocol-version-matches-spec
  (is (= const/protocol-version spec/protocol-version)
      "Protocol version mismatch between codec and spec"))
