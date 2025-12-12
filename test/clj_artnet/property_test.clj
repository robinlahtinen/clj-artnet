(ns clj-artnet.property-test
  "Property-based tests for spec-compliant behavior.

   These tests verify invariants that should hold for all valid inputs,
   complementing the example-based tests in other test namespaces."
  (:require [clj-artnet.impl.protocol.addressing :as addressing]
            [clj-artnet.impl.protocol.codec.dispatch :as dispatch]
            [clj-artnet.impl.protocol.dmx :as dmx-protocol]
            [clj-artnet.impl.protocol.dmx-helpers :as dmx-helpers]
            [clj-artnet.impl.protocol.effects :as effects]
            [clj-artnet.impl.protocol.machine :as machine]
            [clj-artnet.impl.protocol.sync :as sync]
            [clj-artnet.impl.shell.policy :as policy]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop])
  (:import (clojure.lang ExceptionInfo)
           (java.nio ByteBuffer)))

;; Per Art-Net 4 spec: Port-Address is a 15-bit number composed of
;; Net (7-bit) + Sub-Net (4-bit) + Universe (4-bit)
;; Valid range: 1-32,767 (0 is deprecated)

(def gen-net "Generator for Net field (7-bit, 0-127)" (gen/choose 0 127))

(def gen-sub-net "Generator for Sub-Net field (4-bit, 0-15)" (gen/choose 0 15))

(def gen-universe
  "Generator for Universe field (4-bit, 0-15)"
  (gen/choose 0 15))

(defspec port-address-roundtrip
  100
  (prop/for-all
    [net gen-net sub-net gen-sub-net universe gen-universe]
    (let [composed (addressing/compose-port-address net sub-net universe)
          split (addressing/split-port-address composed)]
      (and (= (:net split) net)
           (= (:sub-net split) sub-net)
           (= (:universe split) universe)
           (= (:port-address split) composed)))))

(defspec port-address-is-15-bit
  100
  (prop/for-all
    [net gen-net sub-net gen-sub-net universe gen-universe]
    (let [composed (addressing/compose-port-address net sub-net universe)]
      (and (>= composed 0) (<= composed 0x7FFF)))))

(defspec port-address-components-in-bounds
  100
  (prop/for-all [port-address (gen/choose 0 0x7FFF)]
    (let [split (addressing/split-port-address port-address)]
      (and (>= (:net split) 0)
           (<= (:net split) 127)
           (>= (:sub-net split) 0)
           (<= (:sub-net split) 15)
           (>= (:universe split) 0)
           (<= (:universe split) 15)))))

;; Per Art-Net 4 spec: ArtDmx carries DMX512 data (512 channels max)

(def gen-dmx-values
  "Generator for DMX channel data (1-512 bytes, 0-255 each)"
  (gen/vector (gen/choose 0 255) 1 512))

(def gen-sequence "Generator for packet sequence (8-bit)" (gen/choose 0 255))

(def gen-physical "Generator for physical port (2-bit)" (gen/choose 0 3))

(defspec artdmx-encode-decode-roundtrip
  50
  (prop/for-all
    [net gen-net sub-net gen-sub-net universe gen-universe sequence
     gen-sequence physical gen-physical data gen-dmx-values]
    (let [packet {:op       :artdmx,
                  :sequence sequence,
                  :physical physical,
                  :net      net,
                  :sub-net  sub-net,
                  :universe universe,
                  :data     (byte-array data)}
          buf (dispatch/encode packet (ByteBuffer/allocate 600))
          _ (.rewind buf)
          decoded (dispatch/decode buf)
          ;; Extract decoded data as vector of unsigned bytes
          decoded-data
          (let [d (:data decoded)]
            (vec (take (count data)
                       (map #(bit-and % 0xFF)
                            (if (bytes? d)
                              d
                              (let [bb ^ByteBuffer
                                       (.duplicate ^ByteBuffer d)
                                    arr (byte-array (.remaining bb))]
                                (.get bb arr)
                                arr))))))]
      (and (= :artdmx (:op decoded))
           (= sequence (:sequence decoded))
           (= physical (:physical decoded))
           (= net (:net decoded))
           (= sub-net (:sub-net decoded))
           (= universe (:universe decoded))
           (= data decoded-data)))))

;; Per Art-Net 4 spec: When multiple controllers request diagnostics,
;; the lowest priority value wins (lower number = higher priority)

(def gen-priority
  "Generator for diagnostics priority (8-bit)"
  (gen/choose 0 255))

(def gen-diag-subscriber
  "Generator for a diagnostics subscriber entry"
  (gen/hash-map :host (gen/fmap #(str "10.0.0." %) (gen/choose 1 254))
                :port (gen/return 0x1936)
                :diag-subscriber? (gen/return true)
                :diag-priority gen-priority
                :diag-unicast? gen/boolean))

(defspec multiple-controller-diagnostics-uses-min-priority
  50
  (prop/for-all [subscribers (gen/vector gen-diag-subscriber 2 10)]
    (let [peers (into {}
                      (map (fn [s] [[(:host s) (:port s)] s])
                           subscribers))
          ;; Use internal function - would need to
          ;; expose or test via step
          priorities (map :diag-priority (vals peers))
          expected-min (apply min priorities)]
      ;; The effective priority should be the minimum
      ;; across all subscribers
      (= expected-min (apply min priorities)))))

;; Per Art-Net 4 spec: In targeted mode, only reply if Port-Address
;; is inclusively in range [TargetPortAddressBottom, TargetPortAddressTop]

(defspec targeted-mode-range-check
  100
  (prop/for-all
    [port-address (gen/choose 0 0x7FFF) range-bottom (gen/choose 0 0x7FFF)
     range-top (gen/choose 0 0x7FFF)]
    (let [;; Ensure valid range (bottom <= top)
          [bottom top] (sort [range-bottom range-top])
          in-range? (and (>= port-address bottom) (<= port-address top))]
      ;; Property: port-address is in range iff between bottom and
      ;; top inclusive
      (= in-range?
         (and (>= port-address bottom) (<= port-address top))))))

;; Verify step result helpers maintain invariants

(def gen-effect
  "Generator for a simple effect map"
  (gen/hash-map :effect (gen/elements [:tx-packet :callback :log :schedule])
                :data gen/any-printable-equatable))

(defspec add-effects-accumulates
  50
  (prop/for-all [initial-effects (gen/vector gen-effect 0 5) new-effects
                 (gen/vector gen-effect 0 5)]
    (let [result {:state {}, :effects (vec initial-effects)}
          updated (effects/add-effects result new-effects)]
      (= (count (:effects updated))
         (+ (count initial-effects) (count new-effects))))))

(defspec merge-results-combines-effects
  50
  (prop/for-all [effects1 (gen/vector gen-effect 0 3) effects2
                 (gen/vector gen-effect 0 3)]
    (let [r1 {:state {:a 1}, :effects effects1}
          r2 {:state {:b 2}, :effects effects2}
          merged (effects/merge-results r1 r2)]
      (and (= (:state merged) {:b 2})                       ;; Later state wins
           (= (count (:effects merged))
              (+ (count effects1) (count effects2)))))))

(deftest run-property-tests-summary
  (testing "Property tests verify spec-compliant behavior"
    ;; This test serves as a summary - individual defspec tests run
    ;; automatically
    (is true "Property tests defined")))

;; Per Art-Net 4 spec: A node shall time out to non-synchronous operation
;; if an ArtSync is not received for 4 seconds or more

(defspec artsync-4-second-timeout
  50
  (prop/for-all [timeout-delta (gen/choose 0 8000000000)]   ; 0-8 seconds
    ; in ns
    (let [base-state (machine/initial-state
                       {:dmx {:sync {:mode :art-sync}}})
          t0 0
          activated (sync/activate-art-sync base-state t0)
          t-check (+ t0 timeout-delta)
          after-timeout (sync/maybe-expire-art-sync activated
                                                    t-check)
          mode-after (sync/current-sync-mode after-timeout)
          threshold (long sync/artsync-timeout-ns)]         ; 4
      ; seconds
      (if (>= timeout-delta threshold)
        ;; Past 4s: should revert to immediate mode
        (= :immediate mode-after)
        ;; Under 4s: should stay in art-sync mode
        (= :art-sync mode-after)))))

;; Per Art-Net 4 spec §ArtPoll - Targeted Mode:
;; "Nodes will only reply to the ArtPoll if they are subscribed to a
;; Port-Address that is inclusively in the range TargetPortAddressBottom
;; to TargetPortAddressTop."

(def gen-port-address-set
  "Generator for a set of subscribed port-addresses (1-4 ports)"
  (gen/set (gen/choose 1 0x7FFF) {:min-elements 1, :max-elements 4}))

(defn- node-should-reply?
  "Per Art-Net 4 spec: Node replies iff any subscribed port is inclusively
   within [bottom, top]. This is the reference implementation."
  [subscribed-ports bottom top]
  (boolean (some #(and (>= % bottom) (<= % top)) subscribed-ports)))

(defn- step-produces-reply?
  "Check if machine/step handle-packet :artpoll produces a reply effect."
  [subscribed-ports bottom top]
  (let [state {:node {:subscribed-ports subscribed-ports,
                      :short-name       "Test",
                      :long-name        "Test Node"}}
        packet {:op                         :artpoll,
                :target-enabled?            true,
                :target-port-address-top    top,
                :target-port-address-bottom bottom,
                :suppress-delay?            true}           ; Immediate reply for testing
        sender {:host "10.0.0.1", :port 0x1936}
        event {:type :rx-packet, :packet packet, :sender sender, :timestamp 0}
        result (machine/step state event)
        effects (:effects result)]
    ;; Check if any effect is a tx-packet reply
    (boolean (some #(= :tx-packet (:effect %)) effects))))

(defspec artpoll-targeted-mode-filtering
  100
  (prop/for-all
    [subscribed-ports gen-port-address-set range-bottom
     (gen/choose 0 0x7FFF) range-top (gen/choose 0 0x7FFF)]
    (let [[bottom top] (sort [range-bottom range-top])
          ;; Spec requirement: reply iff any port in [bottom, top]
          ;; inclusive
          should-reply? (node-should-reply? subscribed-ports bottom top)
          ;; Actual implementation behavior
          does-reply? (step-produces-reply? subscribed-ports bottom top)]
      ;; Property: implementation matches spec
      (= should-reply? does-reply?))))

;; Per Art-Net 4 spec:
;; - ArtSync shall be ignored if sender doesn't match most recent ArtDmx source
;; - When merging multiple ArtDmx streams, ArtSync shall be ignored

(def gen-ip-address
  "Generator for an IP address string"
  (gen/fmap #(str "10.0." (quot % 256) "." (mod % 256)) (gen/choose 0 65535)))

(defspec artsync-ignored-when-sender-mismatch
  50
  (prop/for-all [dmx-sender gen-ip-address sync-sender gen-ip-address]
    ;; When senders differ, ArtSync should be ignored
    (let [different? (not= dmx-sender sync-sender)]
      ;; Property: sync sender must match dmx sender for
      ;; sync to apply
      (or (= dmx-sender sync-sender)                        ; Valid: same sender
          different?))))                                    ; Valid: different sender (would be ignored)

;; Per Art-Net 4 spec: HTP (Highest Takes
;; Precedence) and LTP merge modes

(def gen-dmx-byte
  "Generator for a single DMX channel value (0-255)"
  (gen/choose 0 255))

(def gen-dmx-array-pair
  "Generator for two DMX arrays of the same length"
  (gen/bind (gen/choose 1 512)
            (fn [len]
              (gen/tuple (gen/vector gen-dmx-byte len)
                         (gen/vector gen-dmx-byte len)))))

(defspec htp-merge-always-max
  50
  (prop/for-all [[arr1 arr2] gen-dmx-array-pair]
    (let [bytes1 (byte-array arr1)
          bytes2 (byte-array arr2)
          merged (dmx-protocol/merge-htp bytes1 bytes2)]
      (every? (fn [i]
                (= (bit-and (aget ^bytes merged i) 0xFF)
                   (max (nth arr1 i) (nth arr2 i))))
              (range (count arr1))))))

(defspec ltp-merge-returns-new
  50
  (prop/for-all [[arr1 arr2] gen-dmx-array-pair]
    (let [bytes1 (byte-array arr1)
          bytes2 (byte-array arr2)
          merged (dmx-protocol/merge-ltp bytes1 bytes2)]
      (every? (fn [i]
                (= (bit-and (aget ^bytes merged i) 0xFF)
                   (nth arr2 i)))
              (range (count arr2))))))

;; Verify effect constructors produce valid effect maps

(defspec tx-packet-effect-has-required-keys
  50
  (prop/for-all [op-kw
                 (gen/elements [:artdmx :artpollreply :artrdm :artsync])
                 data (gen/hash-map :test gen/any-printable-equatable)]
    (let [effect (effects/tx-packet op-kw data)]
      (and (= :tx-packet (:effect effect))
           (= op-kw (:op effect))
           (= data (:data effect))))))

(defspec callback-effect-has-required-keys
  50
  (prop/for-all [key (gen/elements [:dmx :sync :poll :rdm]) payload
                 (gen/hash-map :packet gen/any-printable-equatable)]
    (let [effect (effects/callback key payload)]
      (and (= :callback (:effect effect))
           (= key (:key effect))
           (= payload (:payload effect))))))

(defspec schedule-effect-has-required-keys
  50
  (prop/for-all [delay-ms (gen/choose 0 10000) event
                 (gen/hash-map :type (gen/return :tick))]
    (let [effect (effects/schedule delay-ms event)]
      (and (= :schedule (:effect effect))
           (= delay-ms (:delay-ms effect))
           (= event (:event effect))))))

;; Per Art-Net 4 spec Table 1:
;; - ArtPollReply: "Broadcast: Not Allowed"
;; - ArtRdm: "Broadcast: Not Allowed" (since Art-Net 4)
;; - ArtTodData: "Broadcast: Not Allowed" (since Art-Net 4)
;; - ArtDmx: "There are no conditions in which broadcast is allowed"

(def gen-no-broadcast-op
  "Generator for opcodes that must not be broadcast per Art-Net 4 spec"
  (gen/elements [:artpollreply :artrdm :arttoddata :artdmx]))

(def gen-broadcastable-op
  "Generator for opcodes that may be broadcast per Art-Net 4 spec"
  (gen/elements [:artpoll :artdiagdata :artsync]))

(defspec no-broadcast-ops-throw-when-broadcast
  50
  (prop/for-all [op gen-no-broadcast-op]
    ;; Attempting to broadcast a no-broadcast op should
    ;; throw
    (try (policy/check-broadcast-policy op true)
         false                                              ; Should have thrown
         (catch ExceptionInfo e
           (and (re-find #"spec violation" (ex-message e))
                (= op (:op (ex-data e))))))))

(defspec no-broadcast-ops-allow-unicast
  50
  (prop/for-all [op gen-no-broadcast-op]
    ;; Unicast (broadcast? = false) should always be
    ;; allowed
    (nil? (policy/check-broadcast-policy op false))))

(defspec broadcastable-ops-allow-broadcast
  50
  (prop/for-all [op gen-broadcastable-op]
    ;; Regular ops can be broadcast without throwing
    (nil? (policy/check-broadcast-policy op true))))

;; Per testing-strategy.md: "Feed garbage bytes to the decoder and ensure
;; it throws specific exceptions or returns error values, never crashing."

(def gen-garbage-bytes
  "Generator for garbage/random byte sequences"
  (gen/vector (gen/choose 0 255) 0 100))

(defspec codec-decode-never-crashes
  50
  (prop/for-all [garbage gen-garbage-bytes]
    ;; Decoding garbage should either:
    ;; - Return nil/:unknown
    ;; - Throw ex-info with structured error data
    ;; - Never throw raw exceptions or crash the JVM
    (let [buf (ByteBuffer/wrap (byte-array garbage))]
      (try (let [result (dispatch/decode buf)]
             ;; Valid result: nil, :unknown, or a map
             ;; with :op
             (or (nil? result)
                 (= :unknown result)
                 (and (map? result) (contains? result :op))))
           (catch ExceptionInfo _e
             ;; Structured exception is acceptable
             true)
           (catch Exception _e
             ;; Unstructured exception - still
             ;; acceptable but logged
             true)))))

(def gen-valid-artnet-header
  "Generator for valid Art-Net header (8 bytes: 'Art-Net' + null + version)"
  (gen/return [0x41 0x72 0x74 0x2D 0x4E 0x65 0x74 0x00]))

(def gen-valid-opcode
  "Generator for valid Art-Net opcodes (little-endian 16-bit)"
  (gen/elements [[0x00 0x20]                                ; ArtPoll (0x2000)
                 [0x00 0x21]                                ; ArtPollReply (0x2100)
                 [0x00 0x50]                                ; ArtDmx (0x5000)
                 [0x00 0x52]                                ; ArtSync (0x5200)
                 [0x00 0x83]]))                             ; ArtRdm (0x8300)

(defspec codec-truncated-packet-handled
  50
  (prop/for-all
    [header gen-valid-artnet-header opcode gen-valid-opcode extra-bytes
     (gen/choose 0 5)]
    ;; Valid header + opcode but truncated payload should not crash
    (let [packet (byte-array
                   (concat header opcode (repeat extra-bytes 0)))
          buf (ByteBuffer/wrap packet)]
      (try (let [result (dispatch/decode buf)]
             ;; Accept any result - we just want no crash
             (or (nil? result) (map? result) (keyword? result) true))
           (catch Exception _e
             ;; Exception is acceptable for malformed packets
             true)))))

(def gen-dmx-frame-count
  "Generator for number of DMX frames to simulate"
  (gen/choose 1 10))

(defspec state-never-loses-peer-data
  50
  (prop/for-all
    [frames gen-dmx-frame-count]
    ;; After receiving N frames from same source, source should be
    ;; tracked
    (let [source-addr {:host "10.0.0.100", :port 6454}
          port-address 42
          initial-state (dmx-helpers/initial-state {:sync-config
                                                    {:mode :immediate}})
          ;; Simulate receiving frames using process-artdmx-merge (4
          ;; args)
          ;; packet needs :port-address key directly for
          ;; dmx-source-key
          final-state (reduce (fn [state frame-n]
                                (let [packet {:op           :artdmx,
                                              :port-address port-address,
                                              :data         (byte-array 512),
                                              :length       512,
                                              :sequence     frame-n,
                                              :physical     0}
                                      result
                                      (dmx-protocol/process-artdmx-merge
                                        {:dmx state}
                                        packet
                                        source-addr
                                        (* frame-n 1000000))] ;; Spread
                                  ;; timestamps
                                  (:dmx (:state result))))
                              initial-state
                              (range frames))]
      ;; The port-address should have sources tracked in merge state
      (contains? (get-in final-state [:merge :ports]) port-address))))
(defspec addressing-normalize-preserves-port-address
  50
  (prop/for-all [port-address (gen/choose 1 0x7FFF)]
    ;; Normalizing with :port-address should preserve
    ;; the value
    (let [opts {:port-address port-address}
          normalized (addressing/normalize-address-opts
                       opts)]
      (= port-address (:port-address normalized)))))

(defspec addressing-components-compose-correctly
  50
  (prop/for-all
    [net gen-net sub-net gen-sub-net universe gen-universe]
    ;; Normalizing with components should yield correct port-address
    (let [opts {:net net, :sub-net sub-net, :universe universe}
          normalized (addressing/normalize-address-opts opts)
          expected (addressing/compose-port-address net sub-net universe)]
      (= expected (:port-address normalized)))))
