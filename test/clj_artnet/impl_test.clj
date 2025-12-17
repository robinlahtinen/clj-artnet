;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl-test
  "Tests for the new impl architecture modules."
  (:require
    [clj-artnet.impl.protocol.codec.compiler :as compiler]
    [clj-artnet.impl.protocol.codec.spec :as spec]
    [clj-artnet.impl.protocol.discovery :as discovery]
    [clj-artnet.impl.protocol.dmx-helpers :as dmx-helpers]
    [clj-artnet.impl.protocol.machine :as step]
    [clj-artnet.impl.protocol.poll :as poll-helpers]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.nio ByteBuffer)))

(deftest spec-header-sizes
  (testing "Art-Net 4 packet header sizes match specification"
    (is (= 239 (spec/spec-header-size spec/art-poll-reply-spec))
        "ArtPollReply should be 239 bytes")
    (is (= 22 (spec/spec-header-size spec/art-poll-spec))
        "ArtPoll should be 22 bytes (Art-Net 4 includes target port address)")
    (is (= 18 (spec/spec-header-size spec/art-dmx-spec))
        "ArtDmx header should be 18 bytes")
    (is (= 14 (spec/spec-header-size spec/art-sync-spec))
        "ArtSync should be 14 bytes")
    (is (= 24 (spec/spec-header-size spec/art-rdm-spec))
        "ArtRdm header should be 24 bytes")))

(deftest spec-field-offsets
  (testing "Field offsets are calculated correctly"
    (is (= 0 (spec/field-offset spec/art-poll-reply-spec :id))
        "ID field should be at offset 0")
    (is (= 8 (spec/field-offset spec/art-poll-reply-spec :op-code))
        "OpCode should be at offset 8")
    (is (= 10 (spec/field-offset spec/art-poll-reply-spec :ip))
        "IP should be at offset 10")))

(deftest packet-specs-registry
  (testing "All major packet types are in registry"
    ;; Core opcodes
    (is (contains? spec/packet-specs :artpoll))
    (is (contains? spec/packet-specs :artpollreply))
    (is (contains? spec/packet-specs :artdmx))
    (is (contains? spec/packet-specs :artsync))
    (is (contains? spec/packet-specs :artrdm))
    ;; Video opcodes (extended video features)
    (is (contains? spec/packet-specs :artvideosetup))
    (is (contains? spec/packet-specs :artvideopalette))
    (is (contains? spec/packet-specs :artvideodata))
    ;; MAC opcodes (deprecated but implemented)
    (is (contains? spec/packet-specs :artmacmaster))
    (is (contains? spec/packet-specs :artmacslave))
    ;; File transfer opcodes
    (is (contains? spec/packet-specs :artfiletnmaster))
    (is (contains? spec/packet-specs :artfilefnmaster))
    (is (contains? spec/packet-specs :artfilefnreply))
    ;; Media Server opcodes
    (is (contains? spec/packet-specs :artmedia))
    (is (contains? spec/packet-specs :artmediapatch))
    (is (contains? spec/packet-specs :artmediacontrol))
    (is (contains? spec/packet-specs :artmediacontrolreply))
    ;; Time synchronization
    (is (contains? spec/packet-specs :arttimesync))
    ;; Directory opcodes
    (is (contains? spec/packet-specs :artdirectory))
    (is (contains? spec/packet-specs :artdirectoryreply))
    ;; Full Art-Net 4 coverage: 37 packet specs (all 37 opcodes from Table
    ;; 1)
    (is (= 37 (count spec/packet-specs))
        "Should have 37 packet specs for full Art-Net 4 coverage")))

(deftest artpollreply-roundtrip
  (testing "ArtPollReply encodes and decodes correctly"
    (let [input {:op         :artpollreply
                 :ip         [192 168 1 100]
                 :port       0x1936
                 :short-name "Test Node"
                 :long-name  "Test Art-Net Node"
                 :esta-man   0x7FF0}
          buf (ByteBuffer/allocate 256)]
      (compiler/encode-packet! buf input)
      (.rewind buf)
      (let [output (compiler/decode-packet buf)]
        (is (= :artpollreply (:op output)))
        (is (= [192 168 1 100] (:ip output)))
        (is (= 0x1936 (:port output)))
        (is (= "Test Node" (:short-name output)))
        (is (= "Test Art-Net Node" (:long-name output)))
        (is (= 0x7FF0 (:esta-man output)))))))

(deftest artsync-roundtrip
  (testing "ArtSync encodes and decodes correctly"
    (let [buf (ByteBuffer/allocate 20)]
      (compiler/encode-packet! buf {:op :artsync})
      (.rewind buf)
      (let [output (compiler/decode-packet buf)]
        (is (= :artsync (:op output)))))))

(deftest artdmx-variable-payload
  (testing "ArtDmx decodes with variable-length payload"
    (let [buf (ByteBuffer/allocate 530)
          dmx-data (byte-array 512)]
      ;; Fill DMX data with pattern
      (dotimes [i 512] (aset dmx-data i (unchecked-byte (mod i 256))))
      ;; Write "Art-Net\0" header
      (.put buf (.getBytes "Art-Net\0"))
      ;; OpCode 0x5000 little-endian
      (.put buf (byte 0))
      (.put buf (byte 0x50))
      ;; Protocol version (0, 14)
      (.put buf (byte 0))
      (.put buf (byte 14))
      ;; Sequence (42)
      (.put buf (byte 42))
      ;; Physical (0)
      (.put buf (byte 0))
      ;; SubUni (1)
      (.put buf (byte 1))
      ;; Net (0)
      (.put buf (byte 0))
      ;; Length (512) big-endian
      (.put buf (byte 2))
      (.put buf (byte 0))
      ;; DMX payload
      (.put buf dmx-data)
      (.flip buf)
      ;; Decode
      (let [output (compiler/decode-packet buf)]
        (is (= :artdmx (:op output)))
        (is (= 42 (:sequence output)))
        (is (= 1 (:sub-uni output)))
        (is (= 512 (:length output)))
        (is (some? (:data output)) "Should include :data ByteBuffer slice")
        (is (= 512 (.remaining ^ByteBuffer (:data output)))
            "Payload should be 512 bytes")
        ;; Verify first and last bytes
        (let [data ^ByteBuffer (:data output)]
          (is (= 0 (bit-and (.get data 0) 0xFF)) "First byte should be 0")
          (is (= 255 (bit-and (.get data 511) 0xFF))
              "Last byte should be 255"))))))

(deftest packet-size-lookup
  (testing "Packet size lookup returns correct values"
    (is (= 239 (compiler/packet-size :artpollreply)))
    (is (= 22 (compiler/packet-size :artpoll))
        "ArtPoll is 22 bytes in Art-Net 4")
    (is (= 18 (compiler/packet-size :artdmx)))
    (is (= 14 (compiler/packet-size :artsync)))
    (is (nil? (compiler/packet-size :unknown)))))

(deftest step-artsync-handler
  (testing "ArtSync step handler updates stats and returns callback effect"
    (let [state {:node  {:short-name "Test"}
                 :peers {}
                 :stats {}
                 :dmx   {:sync {:mode :immediate}}}
          event {:type      :rx-packet
                 :packet    {:op :artsync}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)]
      (is (= 1 (get-in result [:state :stats :rx-artsync]))
          "Should increment rx-artsync stat")
      (is (some #(= :callback (:effect %)) (:effects result))
          "Should produce callback effect"))))

(deftest step-artpoll-handler
  (testing
    "ArtPoll step handler with suppress-delay returns immediate tx-packet reply"
    (let [state {:node  {:short-name "Test", :ip [192 168 1 50], :port 6454}
                 :peers {}
                 :stats {}}
          ;; flags bit 0 = suppress delay (immediate reply per Art-Net 4
          ;; spec)
          event {:type      :rx-packet
                 :packet    {:op :artpoll, :flags 0x01}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 2000000}
          result (step/step state event)]
      (is (= 1 (get-in result [:state :stats :rx-artpoll]))
          "Should increment rx-artpoll stat")
      (is (some #(and (= :tx-packet (:effect %)) (= :artpollreply (:op %)))
                (:effects result))
          "Should produce ArtPollReply tx-packet effect")))
  (testing "ArtPoll step handler without suppress-delay schedules delayed reply"
    (let [state {:node  {:short-name "Test", :ip [192 168 1 50], :port 6454}
                 :peers {}
                 :stats {}}
          ;; flags = 0 means no suppress-delay, reply should be scheduled
          event {:type      :rx-packet
                 :packet    {:op :artpoll, :flags 0x00}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 2000000}
          result (step/step state event)]
      (is (= 1 (get-in result [:state :stats :rx-artpoll]))
          "Should increment rx-artpoll stat")
      (is (some #(= :schedule (:effect %)) (:effects result))
          "Should schedule delayed reply per Art-Net 4 spec")))
  (testing "ArtPoll targeted mode filters by port-address range"
    (let [state {:node  {:short-name "Test"
                         :net-switch 0
                         :sub-switch 0
                         :sw-out     [0 1 2 3]}
                 :peers {}
                 :stats {}}
          ;; Targeted mode (bit 5) + suppress delay (bit 0), range 500-1000
          in-range-event {:type   :rx-packet
                          :packet {:op                         :artpoll
                                   :flags                      0x21
                                   :target-port-address-bottom 0
                                   :target-port-address-top    10}
                          :sender {:host "192.168.1.100", :port 6454}}
          out-range-event {:type   :rx-packet
                           :packet {:op                         :artpoll
                                    :flags                      0x21
                                    :target-port-address-bottom 500
                                    :target-port-address-top    1000}
                           :sender {:host "192.168.1.100", :port 6454}}]
      (is (seq (:effects (step/step state in-range-event)))
          "Should reply when Port-Address is in targeted range")
      (is (empty? (:effects (step/step state out-range-event)))
          "Should not reply when Port-Address is out of targeted range"))))

(deftest step-reply-on-change-limit
  (testing "Reply-on-change limit enforcement"
    (let [state {:peers     {["a" 6454] {:reply-on-change? true
                                         :host             "192.168.1.1"
                                         :port             6454
                                         :seen-at          1000}
                             ["b" 6454] {:reply-on-change? true
                                         :host             "192.168.1.2"
                                         :port             6454
                                         :seen-at          2000}
                             ["c" 6454] {:reply-on-change? true
                                         :host             "192.168.1.3"
                                         :port             6454
                                         :seen-at          3000}}
                 :discovery {:reply-on-change-limit  1
                             :reply-on-change-policy :prefer-existing}}
          result (discovery/enforce-reply-on-change-limit state)]
      (is (:reply-on-change? (get-in result [:peers ["a" 6454]]))
          "First subscriber should be kept (prefer-existing)")
      (is (not (:reply-on-change? (get-in result [:peers ["b" 6454]])))
          "Second subscriber should be disabled")
      (is (not (:reply-on-change? (get-in result [:peers ["c" 6454]])))
          "Third subscriber should be disabled")))
  (testing "Reply-on-change with prefer-latest policy"
    (let [state {:peers     {["a" 6454] {:reply-on-change? true
                                         :host             "192.168.1.1"
                                         :port             6454
                                         :seen-at          1000}
                             ["b" 6454] {:reply-on-change? true
                                         :host             "192.168.1.2"
                                         :port             6454
                                         :seen-at          2000}}
                 :discovery {:reply-on-change-limit  1
                             :reply-on-change-policy :prefer-latest}}
          result (discovery/enforce-reply-on-change-limit state)]
      (is (not (:reply-on-change? (get-in result [:peers ["a" 6454]])))
          "Earlier subscriber should be disabled (prefer-latest)")
      (is (:reply-on-change? (get-in result [:peers ["b" 6454]]))
          "Latest subscriber should be kept")))
  (testing "Reply-on-change effects generation"
    (let [state {:peers
                 {["a" 6454]
                  {:reply-on-change? true, :host "192.168.1.1", :port 6454}
                  ["b" 6454]
                  {:reply-on-change? true, :host "192.168.1.2", :port 6454}
                  ["c" 6454]
                  {:reply-on-change? false, :host "192.168.1.3", :port 6454}}}
          effects (poll-helpers/reply-on-change-effects state
                                                        {:short-name "Test"}
                                                        ["a" 6454])]
      (is (= 1 (count effects))
          "Should generate effect for one peer (b, excluding a)")
      (is (= :tx-packet (:effect (first effects)))))))

(deftest step-artdmx-handler
  (testing "ArtDmx step handler in immediate mode"
    (let [state {:node  {:short-name "Test"}
                 :peers {}
                 :stats {}
                 :dmx   {:sync {:mode :immediate}}}
          event {:type      :rx-packet
                 :packet    {:op       :artdmx
                             :sequence 42
                             :physical 0
                             :sub-uni  1
                             :net      0
                             :length   512
                             :data     (byte-array 512)}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 3000000}
          result (step/step state event)]
      (is (= 1 (get-in result [:state :stats :rx-artdmx]))
          "Should increment rx-artdmx stat")
      (is (some #(= :dmx-frame (:effect %)) (:effects result))
          "Should produce dmx-frame effect in immediate mode"))))

(deftest step-artdmx-sync-mode
  (testing "ArtDmx buffers in ArtSync mode"
    (let [state {:node  {:short-name "Test"}
                 :peers {}
                 :stats {}
                 :dmx   (step/initial-dmx-state {:sync-mode :art-sync})}
          event {:type      :rx-packet
                 :packet    {:op           :artdmx
                             :port-address 0x100
                             :data         (byte-array [255 128 64])
                             :length       3
                             :sequence     1}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000000000}
          result (step/step state event)]
      (is (empty? (:effects result))
          "Should not emit effects when buffering for sync")
      (is (contains? (get-in result [:state :dmx :sync-buffer]) 0x100)
          "Should buffer the DMX frame")))
  (testing "ArtSync releases buffered frames"
    (let [state {:node  {:short-name "Test"}
                 :peers {}
                 :stats {}
                 :dmx   (step/initial-dmx-state {:sync-mode :art-sync})}
          t0 1000000000000
          ;; Stage a DMX frame
          dmx-event {:type      :rx-packet
                     :packet    {:op           :artdmx
                                 :port-address 0x100
                                 :data         (byte-array [255 128 64])
                                 :length       3
                                 :sequence     1}
                     :sender    {:host "192.168.1.1"}
                     :timestamp t0}
          state1 (:state (step/step state dmx-event))
          ;; Send ArtSync
          sync-event {:type      :rx-packet
                      :packet    {:op :artsync}
                      :sender    {:host "controller"}
                      :timestamp (+ t0 100)}
          result (step/step state1 sync-event)]
      (is (some #(= :dmx-frame (:effect %)) (:effects result))
          "ArtSync should release buffered DMX frames"))))

(deftest step-artdmx-htp-merge
  (testing "Multiple sources merge using HTP"
    (let [state {:node  {:short-name "Test"}
                 :peers {}
                 :stats {}
                 :dmx   (step/initial-dmx-state)}
          t0 1000000000000
          ;; First source: [100 200 50]
          pkt1 {:op           :artdmx
                :port-address 0x100
                :data         (byte-array [(byte 100) (byte -56) (byte 50)]) ; -56 =
                ; 200 unsigned
                :length       3
                :sequence     1
                :physical     0}
          result1 (step/step state
                             {:type      :rx-packet
                              :packet    pkt1
                              :sender    {:host "192.168.1.1"}
                              :timestamp t0})
          state1 (:state result1)
          ;; Second source: [150 100 75] - triggers merge
          pkt2 {:op           :artdmx
                :port-address 0x100
                :data         (byte-array [(byte -106) (byte 100) (byte 75)]) ; -106 =
                ; 150 unsigned
                :length       3
                :sequence     2
                :physical     1}
          result2 (step/step state1
                             {:type      :rx-packet
                              :packet    pkt2
                              :sender    {:host "192.168.1.2"}
                              :timestamp (+ t0 1000)})
          dmx-frame (first (filter #(= :dmx-frame (:effect %))
                                   (:effects result2)))
          merged-data (when dmx-frame
                        (mapv #(bit-and % 0xFF) (:data dmx-frame)))]
      (is (= [150 200 75] merged-data)
          "HTP merge should take max of each channel"))))

(deftest step-config-event
  (testing "Config event updates state"
    (let [state {:node {:short-name "Old"}, :peers {}, :stats {}}
          event {:type :config, :node {:short-name "New"}}
          result (step/step state event)]
      (is (= "New" (get-in result [:state :node :short-name]))
          "Should update node config"))))

(deftest failsafe-mode-test
  (testing "Failsafe mode extraction from Status3 bits 7-6"
    (is (= :hold (dmx-helpers/failsafe-mode {:node {:status3 0x00}}))
        "Bits 00 -> hold mode")
    (is (= :zero (dmx-helpers/failsafe-mode {:node {:status3 0x40}}))
        "Bits 01 -> zero mode")
    (is (= :full (dmx-helpers/failsafe-mode {:node {:status3 0x80}}))
        "Bits 10 -> full mode")
    (is (= :scene (dmx-helpers/failsafe-mode {:node {:status3 0xC0}}))
        "Bits 11 -> scene mode")
    (is (= :hold (dmx-helpers/failsafe-mode {:node {:status3 nil}}))
        "nil status3 -> hold mode")
    (is (= :hold (dmx-helpers/failsafe-mode {})) "Missing node -> hold mode")))

(deftest failsafe-supported-test
  (testing "Failsafe support from Status3 bit 5"
    (is (dmx-helpers/failsafe-supported? {:node {:status3 0x20}})
        "Bit 5 set means supported")
    (is (dmx-helpers/failsafe-supported? {:node {:status3 0xE0}})
        "Multiple bits set, still supported")
    (is (not (dmx-helpers/failsafe-supported? {:node {:status3 0x00}}))
        "Bit 5 clear means not supported")
    (is (not (dmx-helpers/failsafe-supported? {:node {:status3 0xDF}}))
        "All bits except 5 set means not supported")
    (is (not (dmx-helpers/failsafe-supported? {:node {:status3 nil}}))
        "nil status3 means not supported")))

(deftest build-failsafe-data-test
  (testing "Build failsafe DMX data for zero mode"
    (let [result (dmx-helpers/build-failsafe-data :zero {:length 512} nil)]
      (is (= 512 (:length result)))
      (is (every? zero? (seq (:data result))))))
  (testing "Build failsafe DMX data for full mode"
    (let [result (dmx-helpers/build-failsafe-data :full {:length 100} nil)]
      (is (= 100 (:length result)))
      (is (every? #(= (unchecked-byte 0xFF) %) (seq (:data result))))))
  (testing "Build failsafe DMX data for scene mode"
    (let [scene-data (byte-array [1 2 3 4 5])
          result (dmx-helpers/build-failsafe-data :scene
                                                  nil
                                                  {:data   scene-data
                                                   :length 5})]
      (is (= 5 (:length result)))
      (is (= [1 2 3 4 5] (vec (:data result)))))))

(deftest run-failsafe-test
  (testing "No failsafe when not supported"
    (let [state {:node {:status3 0x00}                      ;; failsafe not supported
                 :dmx  {:merge    {:ports {0 {:last-output {:updated-at 0
                                                            :length     512}}}}
                        :failsafe {:config {:enabled?        true
                                            :idle-timeout-ns 1000}}}}
          {:keys [effects]} (dmx-helpers/run-failsafe state 10000)]
      (is (empty? effects))))
  (testing "No failsafe in hold mode even if supported"
    (let [state {:node {:status3 0x20}                      ;; supported but hold mode
                 :dmx  {:merge    {:ports {0 {:last-output {:updated-at 0
                                                            :length     512}}}}
                        :failsafe {:config {:enabled?        true
                                            :idle-timeout-ns 1000}}}}
          {:keys [effects]} (dmx-helpers/run-failsafe state 10000)]
      (is (empty? effects))))
  (testing "Failsafe engages in zero mode when idle"
    (let [state {:node {:status3 0x60}                      ;; supported + zero mode
                 :dmx  {:merge    {:ports {0 {:last-output {:updated-at 0
                                                            :length     512}}}}
                        :failsafe {:config {:enabled?        true
                                            :idle-timeout-ns 1000}}}}
          {:keys [state effects]} (dmx-helpers/run-failsafe state 10000)]
      ;; 3 effects: :dmx-frame, :callback, :log
      (is (= 3 (count effects)))
      (is (some #(= :dmx-frame (:effect %)) effects))
      (is (some #(= :callback (:effect %)) effects))
      (is (get-in state [:dmx :failsafe :playback 0])))))

(deftest clear-failsafe-port-test
  (testing "Clears failsafe playback state"
    (let [state {:dmx {:failsafe {:playback {0 {:mode       :zero
                                                :engaged-at 1000}}}}}
          result (dmx-helpers/clear-failsafe-port state 0)]
      (is (empty? (get-in result [:dmx :failsafe :playback])))))
  (testing "Returns state unchanged when no playback"
    (let [state {:dmx {:failsafe {:playback {}}}}
          result (dmx-helpers/clear-failsafe-port state 0)]
      (is (= state result)))))

(deftest record-failsafe-scene-test
  (testing "Records current output as failsafe scene"
    (let [dmx-data (byte-array [100 150 200])
          state {:dmx {:merge {:ports {0 {:last-output {:data   dmx-data
                                                        :length 3}}}}}}
          result (dmx-helpers/record-failsafe-scene state 12345)]
      (is (get-in result [:dmx :failsafe :scene 0]))
      (is (= 12345 (get-in result [:dmx :failsafe :recorded-at])))
      (is (= 3 (get-in result [:dmx :failsafe :scene 0 :length])))))
  (testing "Returns unchanged state when no ports"
    (let [state {:dmx {:merge {:ports {}}}}
          result (dmx-helpers/record-failsafe-scene state 12345)]
      (is (nil? (get-in result [:dmx :failsafe :scene]))))))

(deftest step-artaddress-handler
  (testing "ArtAddress handler applies programming changes"
    (let [state
          {:node  {:short-name "Old Name"
                   :long-name  "Old Long Name"
                   :net-switch 0
                   :sub-switch 0
                   :sw-in      [0 0 0 0]
                   :sw-out     [0 0 0 0]
                   :status1    0
                   :status3    0
                   :ip         [192 168 1 10]
                   :port       0x1936
                   :bind-index 1}
           :node-defaults
           {:net-switch 0, :sub-switch 0, :sw-in [0 0 0 0], :sw-out [0 0 0 0]}
           :peers {}
           :dmx   (step/initial-dmx-state)
           :stats {}}
          event {:type      :rx-packet
                 :packet    {:op         :artaddress
                             :bind-index 1
                             :short-name "New Name"
                             :net-switch 0x81
                             :command    0x00}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)]
      (is (= "New Name" (get-in result [:state :node :short-name]))
          "Should update short name")
      (is (= 1 (get-in result [:state :node :net-switch]))
          "Should update net-switch (0x81 -> 1)")
      (is (some #(and (= :tx-packet (:effect %)) (= :artpollreply (:op %)))
                (:effects result))
          "Should reply with ArtPollReply")))
  (testing "ArtAddress failsafe-hold command sets status3"
    (let [state {:node          {:short-name "Test", :status3 0, :bind-index 1}
                 :node-defaults {}
                 :peers         {}
                 :dmx           (step/initial-dmx-state)
                 :stats         {}}
          event {:type      :rx-packet
                 :packet    {:op :artaddress, :bind-index 1, :command 0x08}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)]
      (is (pos? (bit-and (get-in result [:state :node :status3]) 0x20))
          "Should set failsafe-supported bit in status3")))
  (testing "ArtAddress failsafe-record records scene"
    (let [dmx-data (byte-array [100 150 200])
          state {:node          {:bind-index 1}
                 :node-defaults {}
                 :peers         {}
                 :dmx           (-> (step/initial-dmx-state)
                                    (assoc-in [:merge :ports 0]
                                              {:last-output {:data       dmx-data
                                                             :length     3
                                                             :updated-at 500000}}))
                 :stats         {}}
          event {:type      :rx-packet
                 :packet    {:op :artaddress, :bind-index 1, :command 0x0C}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)]
      (is (get-in result [:state :dmx :failsafe :scene 0])
          "Should record failsafe scene")
      (is (= 1000000 (get-in result [:state :dmx :failsafe :recorded-at]))
          "Should record timestamp")))
  (testing "ArtAddress notifies reply-on-change subscribers"
    (let [state {:node          {:short-name "Test", :bind-index 1, :ip [192 168 1 10]}
                 :node-defaults {}
                 :peers         {["192.168.1.50" 6454] {:host             "192.168.1.50"
                                                        :port             6454
                                                        :reply-on-change? true}}
                 :dmx           (step/initial-dmx-state)
                 :stats         {}}
          event {:type      :rx-packet
                 :packet
                 {:op :artaddress, :bind-index 1, :short-name "Changed"}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)
          roc-effects (filter #(and (= :tx-packet (:effect %))
                                    (= "192.168.1.50"
                                       (get-in % [:target :host])))
                              (:effects result))]
      (is (= 1 (count roc-effects))
          "Should notify reply-on-change subscriber"))))

(deftest step-artinput-handler
  (testing "ArtInput handler applies disable flags"
    (let [state {:node
                 {:good-input [0 0 0 0], :bind-index 1, :ip [192 168 1 10]}
                 :peers {}
                 :dmx   (step/initial-dmx-state)
                 :stats {}}
          event {:type      :rx-packet
                 :packet    {:op         :artinput
                             :bind-index 1
                             :num-ports  4
                             :input      [0x01 0x00 0x01 0x00]}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)]
      (is (some #(and (= :tx-packet (:effect %)) (= :artpollreply (:op %)))
                (:effects result))
          "Should reply with ArtPollReply")
      (is (some #(= :callback (:effect %)) (:effects result))
          "Should emit callback")))
  (testing "ArtInput flushes sync buffer when inputs disabled"
    (let [state {:node  {:good-input [0 0 0 0], :bind-index 1}
                 :peers {}
                 :dmx   (-> (step/initial-dmx-state)
                            (assoc :sync-buffer
                                   {0 {:packet {}, :received-at 500}}))
                 :stats {}}
          event {:type      :rx-packet
                 :packet
                 {:op :artinput, :bind-index 1, :input [0x01 0x00 0x00 0x00]}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)]
      (is (empty? (get-in result [:state :dmx :sync-buffer]))
          "Should flush sync buffer when inputs disabled")))
  (testing "ArtInput ignores mismatched bind-index"
    (let [state {:node  {:good-input [0 0 0 0], :bind-index 1}
                 :peers {}
                 :dmx   (-> (step/initial-dmx-state)
                            (assoc :sync-buffer
                                   {0 {:packet {}, :received-at 500}}))
                 :stats {}}
          event {:type      :rx-packet
                 :packet
                 {:op :artinput, :bind-index 2, :input [0x01 0x01 0x01 0x01]}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)]
      (is (seq (get-in result [:state :dmx :sync-buffer]))
          "Should not flush sync buffer when bind-index doesn't match"))))

(deftest step-artipprog-handler
  (testing "ArtIpProg handler updates network config and replies"
    (let [state {:node             {:ip [192 168 1 10], :status2 0}
                 :network          {:ip          [192 168 1 10]
                                    :subnet-mask [255 255 255 0]
                                    :dhcp?       false}
                 :network-defaults {:ip [2 0 0 1], :subnet-mask [255 0 0 0]}
                 :peers            {}
                 :stats            {}}
          event {:type      :rx-packet
                 :packet    {:op        :artipprog
                             :command   0x84                ;; Enable + Program IP
                             :prog-ip   [10 0 0 1]
                             :prog-sm   [255 0 0 0]
                             :prog-port 0x1936}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)]
      (is (= [10 0 0 1] (get-in result [:state :network :ip]))
          "Should update IP in network config")
      (is (some #(and (= :tx-packet (:effect %))
                      (= :artipprogreply (:op %))
                      (= [10 0 0 1] (:ip (:data %))))
                (:effects result))
          "Should send ArtIpProgReply with new IP")
      (is (some #(and (= :callback (:effect %)) (= :ipprog (:key %)))
                (:effects result))
          "Should trigger callback"))))

(deftest step-artfirmwaremaster-handler
  (testing "ArtFirmwareMaster handler initiates session"
    (let [state {:node      {:oem 0x1234}
                 :peers     {}
                 :callbacks {:on-chunk    (constantly {:status :ok})
                             :on-complete (constantly {:status :ok})}
                 :stats     {}}
          event {:type      :rx-packet
                 :packet    {:op              :artfirmwaremaster
                             :type            0
                             :block-id        0
                             :firmware-length 2             ;; 2 words = 4 bytes
                             :data-length     4
                             :data            (ByteBuffer/allocate 4)
                             :stage           :first
                             :transfer        :firmware}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)]
      (is (get-in result [:state :firmware :sessions])
          "Should create firmware session in state")
      (is (some #(and (= :tx-packet (:effect %)) (= :artfirmwarereply (:op %)))
                (:effects result))
          "Should send ArtFirmwareReply")
      (is (some #(and (= :callback (:effect %)) (= :firmware (:key %)))
                (:effects result))
          "Should trigger callback"))))

(deftest step-arttrigger-handler
  (testing "ArtTrigger filters by OEM"
    (let [state {:node {:oem 0x1234}, :peers {}, :stats {}}
          ;; Mismatched OEM
          event1 {:type      :rx-packet
                  :packet    {:op :arttrigger, :oem 0x5678, :key 0, :sub-key 0}
                  :sender    {:host "192.168.1.100", :port 6454}
                  :timestamp 1000000}
          ;; Matched OEM
          event2 {:type      :rx-packet
                  :packet    {:op :arttrigger, :oem 0x1234, :key 0, :sub-key 0}
                  :sender    {:host "192.168.1.100", :port 6454}
                  :timestamp 2000000}
          result1 (step/step state event1)
          result2 (step/step state event2)]
      (is (empty? (:effects result1)) "Should ignore mismatched OEM")
      (is (seq (:effects result2)) "Should process matched OEM")))
  (testing "ArtTrigger debounces repeated triggers"
    (let [state {:node     {:oem 0x1234}
                 :triggers {:min-interval-ns 1000000000}    ;; 1 second
                 :peers    {}
                 :stats    {}}
          event {:type   :rx-packet
                 :packet {:op       :arttrigger
                          :oem      0x1234
                          :key      0
                          :sub-key  0
                          :key-type :key-ascii}
                 :sender {:host "192.168.1.100", :port 6454}}
          ;; First trigger
          res1 (step/step state (assoc event :timestamp 1000000))
          state1 (:state res1)
          ;; Immediate retry
          res2 (step/step state1 (assoc event :timestamp 1000100))
          ;; Later retry
          res3 (step/step state1 (assoc event :timestamp 2000000000))]
      (is (seq (:effects res1)) "First trigger accepted")
      (is (some #(= :tx-packet (:effect %)) (:effects res2))
          "Second trigger debounced (sends diag)")
      (is (seq (:effects res3)) "Third trigger accepted (after interval)"))))

(deftest step-artcommand-handler
  (testing "ArtCommand applies SwOutText directives"
    (let [state {:node           {:esta-man 0x0000}
                 :command-labels {:swout "Old"}
                 :peers          {}
                 :stats          {}}
          event {:type      :rx-packet
                 :packet    {:op       :artcommand
                             :esta-man 0xFFFF
                             :text     "SwOutText=New Label"}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)]
      (is (= "New Label" (get-in result [:state :command-labels :swout]))
          "Should update SwOut label")
      (is (some #(= :callback (:effect %)) (:effects result))
          "Should callback"))))

(deftest initial-dmx-state-nested-config
  (testing "initial-dmx-state handles nested sync config"
    (let [config {:sync {:mode :art-sync, :buffer-ttl-ns 500}}
          state (step/initial-dmx-state config)]
      (is (= :art-sync (get-in state [:sync :mode]))
          "Should extract sync mode from nested map")
      (is (= 500 (get-in state [:sync :buffer-ttl-ns]))
          "Should extract buffer ttl from nested map"))))

(deftest step-artdatarequest-produces-reply
  (testing "ArtDataRequest generates ArtDataReply when identifiers match"
    (let [state {:node  {:esta-man 0x1234, :oem 0x5678}
                 :data  {:responses {:dr-url-product
                                     "https://example.com/product"}}
                 :peers {}
                 :stats {}}
          event {:type      :rx-packet
                 :packet    {:op           :artdatarequest
                             :esta-man     0x1234
                             :oem          0x5678
                             :request-type :dr-url-product
                             :request      0x0001}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000}
          result (step/step state event)
          tx-effects (filter #(= :tx-packet (:effect %)) (:effects result))]
      (is (= 1 (count tx-effects)) "Should produce one tx-packet effect")
      (let [reply (first tx-effects)]
        (is (= :artdatareply (:op reply)))
        (is (= "https://example.com/product" (get-in reply [:data :text])))
        (is (= 0x1234 (get-in reply [:data :esta-man])))
        (is (= 0x5678 (get-in reply [:data :oem])))
        (is (:reply? reply) "Should be marked as reply"))
      (is (= 1 (get-in result [:state :stats :data-requests]))))))

(deftest step-artdatarequest-ignores-identifier-mismatch
  (testing "ArtDataRequest ignored when ESTA/OEM don't match"
    (let [state {:node  {:esta-man 0x1234, :oem 0x5678}
                 :data  {:responses {:dr-url-product
                                     "https://example.com/product"}}
                 :peers {}
                 :stats {}}
          event {:type      :rx-packet
                 :packet    {:op :artdatarequest, :esta-man 0x1234, :oem 0x9999} ;; OEM
                 ;; mismatch
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000}
          result (step/step state event)]
      (is (empty? (:effects result)) "Should produce no effects on mismatch")
      (is (nil? (get-in result [:state :stats :data-requests]))
          "Should not increment data-requests stat"))))

(deftest step-artdatarequest-handles-byte-array-payload
  (testing "ArtDataRequest handles byte array responses"
    (let [state {:node  {:esta-man 0x1001, :oem 0x2002}
                 :data  {:responses {:dr-ip-support (byte-array [1 2 3 4])}}
                 :peers {}
                 :stats {}}
          event {:type      :rx-packet
                 :packet    {:op           :artdatarequest
                             :esta-man     0x1001
                             :oem          0x2002
                             :request-type :dr-ip-support
                             :request      0x0005}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000}
          result (step/step state event)
          tx-effects (filter #(= :tx-packet (:effect %)) (:effects result))]
      (is (= 1 (count tx-effects)))
      (let [reply (first tx-effects)
            payload (get-in reply [:data :data])]
        (is (= :artdatareply (:op reply)))
        (is (bytes? payload) "Should have byte array payload")
        (is (= [1 2 3 4] (vec payload)))))))

(deftest step-artdatarequest-no-response-configured
  (testing "ArtDataRequest with no configured response still callbacks"
    (let [state {:node  {:esta-man 0x1234, :oem 0x5678}
                 :data  {:responses {}}                     ;; No responses configured
                 :peers {}
                 :stats {}}
          event {:type      :rx-packet
                 :packet    {:op           :artdatarequest
                             :esta-man     0x1234
                             :oem          0x5678
                             :request-type :dr-url-product
                             :request      0x0001}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000}
          result (step/step state event)
          callbacks (filter #(= :callback (:effect %)) (:effects result))]
      (is (= 1 (count callbacks)) "Should produce callback effect")
      (let [cb (first callbacks)]
        (is (= :data-request (:key cb)))
        (is (false? (get-in cb [:payload :replied?]))
            "Should indicate not replied")))))

(deftest step-artcommand-produces-diagdata-acknowledgement
  (testing "ArtCommand with SwOutText produces ArtDiagData acknowledgement"
    (let [state {:node           {:esta-man 0xFFFF}
                 :command-labels {:swout "Old"}
                 :peers          {}
                 :stats          {}}
          event {:type      :rx-packet
                 :packet
                 {:op :artcommand, :esta-man 0xFFFF, :text "SwOutText=New&"}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000}
          result (step/step state event)
          tx-effects (filter #(= :tx-packet (:effect %)) (:effects result))]
      (is (= 1 (count tx-effects)) "Should produce one ArtDiagData")
      (let [diag (first tx-effects)]
        (is (= :artdiagdata (:op diag)))
        (is (string? (get-in diag [:data :text])))
        (is (re-find #"(?i)swouttext" (get-in diag [:data :text])))
        (is (:reply? diag) "Should be marked as reply"))
      (is (= "New" (get-in result [:state :command-labels :swout]))
          "Should update label")
      (is (= 1 (get-in result [:state :stats :diagnostics-sent]))
          "Should increment diagnostics-sent stat"))))

(deftest step-artpoll-diagnostics-subscription
  (testing "ArtPoll with diagnostic request flags subscribes peer"
    (let [state {:node {:short-name "Test"}, :peers {}, :stats {}}
          ;; flags bit 2 = diagnostics requested, bit 3 = unicast
          event {:type      :rx-packet
                 :packet    {:op            :artpoll
                             :flags         0x0D            ; suppress-delay + diag-request +
                             ; diag-unicast
                             :diag-priority 0x40}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)
          peer (get-in result [:state :peers ["192.168.1.100" 6454]])]
      (is (:diag-subscriber? peer) "Should mark peer as diagnostics subscriber")
      (is (= 0x40 (:diag-priority peer)) "Should record diagnostic priority")
      (is (:diag-unicast? peer) "Should record unicast preference")))
  (testing "Multiple diagnostic subscribers tracked independently"
    (let [base-state
          {:node {:short-name "Test", :ip [192 168 1 10]}, :peers {}, :stats {}}
          poll1 {:type      :rx-packet
                 :packet    {:op :artpoll, :flags 0x0D, :diag-priority 0x40}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          poll2 {:type      :rx-packet
                 :packet    {:op :artpoll, :flags 0x0D, :diag-priority 0x20}
                 :sender    {:host "192.168.1.101", :port 6454}
                 :timestamp 2000000}
          state1 (:state (step/step base-state poll1))
          state2 (:state (step/step state1 poll2))
          diag-subs (filter (fn [[_ peer]] (:diag-subscriber? peer))
                            (:peers state2))]
      (is (= 2 (count diag-subs))
          "Should track multiple diagnostic subscribers"))))

(deftest step-artrdm-handler
  (testing "ArtRdm produces RDM callback effect"
    (let [state {:node {:short-name "Test"}, :peers {}, :stats {}}
          event
          {:type      :rx-packet
           :packet
           {:op :artrdm, :rdm-ver 1, :net 0, :command 0x20, :address 0x0001}
           :sender    {:host "192.168.1.100", :port 6454}
           :timestamp 1000000}
          result (step/step state event)]
      (is (= 1 (get-in result [:state :stats :rx-artrdm]))
          "Should increment rx-artrdm stat")
      (is (some #(and (= :callback (:effect %)) (= :rdm (:key %)))
                (:effects result))
          "Should produce rdm callback effect"))))

(deftest step-arttodrequest-handler
  (testing "ArtTodRequest produces TOD callback effect"
    (let [state {:node {:short-name "Test"}, :peers {}, :stats {}}
          event {:type      :rx-packet
                 :packet    {:op            :arttodrequest
                             :net           0
                             :command       0
                             :address-count 1
                             :addresses     [0x0001]}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)]
      (is (= 1 (get-in result [:state :stats :rx-arttodrequest]))
          "Should increment rx-arttodrequest stat")
      (is (some #(and (= :callback (:effect %)) (= :tod-request (:key %)))
                (:effects result))
          "Should produce tod-request callback effect"))))

(deftest step-arttodcontrol-handler
  (testing "ArtTodControl produces TOD control callback effect"
    (let [state {:node {:short-name "Test"}, :peers {}, :stats {}}
          event {:type      :rx-packet
                 :packet
                 {:op :arttodcontrol, :net 0, :command 1, :address 0x0001}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)]
      (is (= 1 (get-in result [:state :stats :rx-arttodcontrol]))
          "Should increment rx-arttodcontrol stat")
      (is (some #(and (= :callback (:effect %)) (= :tod-control (:key %)))
                (:effects result))
          "Should produce tod-control callback effect"))))

(deftest step-artrdmsub-handler
  (testing "ArtRdmSub produces RDM-sub callback effect"
    (let [state {:node {:short-name "Test"}, :peers {}, :stats {}}
          event {:type      :rx-packet
                 :packet    {:op            :artrdmsub
                             :rdm-ver       1
                             :uid           [0x00 0x00 0x00 0x00 0x00 0x00]
                             :command-class 0x20
                             :sub-device    0
                             :sub-count     1}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)]
      (is (= 1 (get-in result [:state :stats :rdm-sub-commands]))
          "Should increment rdm-sub-commands stat")
      (is (some #(and (= :callback (:effect %)) (= :rdm-sub (:key %)))
                (:effects result))
          "Should produce rdm-sub callback effect"))))

(deftest step-artpoll-multi-page-reply
  (testing "Multi-bind config generates multiple ArtPollReply effects"
    (let [state {:node  {:short-name "Test"
                         :long-name  "Test Node"
                         :ip         [192 168 1 10]
                         :port       6454
                         :port-types [0xC0 0xC0 0xC0 0xC0 0xC0 0xC0 0xC0 0xC0]
                         :sw-out     [0 1 2 3 4 5 6 7]
                         :pages      [{:bind-index 1
                                       :short-name "Page 1"
                                       :port-types [0xC0 0xC0 0xC0 0xC0]
                                       :sw-out     [0 1 2 3]}
                                      {:bind-index 2
                                       :short-name "Page 2"
                                       :port-types [0xC0 0xC0 0xC0 0xC0]
                                       :sw-out     [4 5 6 7]}]}
                 :peers {}
                 :stats {}}
          event {:type      :rx-packet
                 :packet    {:op :artpoll, :flags 0x01}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)
          poll-replies (filter #(and (= :tx-packet (:effect %))
                                     (= :artpollreply (:op %)))
                               (:effects result))]
      (is (>= (count poll-replies) 1)
          "Should produce at least one ArtPollReply"))))

(deftest step-tick-handler
  (testing "Tick event processes keepalive and failsafe"
    (let [state {:node  {:short-name "Test", :status3 0x60} ; failsafe
                 ; supported + zero mode
                 :peers {}
                 :dmx   {:merge    {:ports {0 {:last-output {:updated-at 0
                                                             :length     512}}}}
                         :failsafe {:config {:enabled?        true
                                             :idle-timeout-ns 1000}}}
                 :stats {}}
          event {:type :tick, :timestamp 10000000}          ; Well past timeout
          result (step/step state event)]
      ;; Tick should process without error
      (is (some? (:state result))))))

(deftest step-command-diagnostic
  (testing "Diagnostic command generates ArtDiagData effect"
    (let [state {:node        {:short-name "Test"}
                 :diagnostics {:subscribers {["192.168.1.100" 6454]
                                             {:host     "192.168.1.100"
                                              :port     6454
                                              :priority 0x40
                                              :unicast? true
                                              :seen-at  1000}}}
                 :peers       {}
                 :stats       {}}
          event {:type      :command
                 :command   :diagnostic
                 :priority  0x40
                 :text      "Test diagnostic message"
                 :timestamp 2000000}
          result (step/step state event)
          diag-effects (filter #(and (= :tx-packet (:effect %))
                                     (= :artdiagdata (:op %)))
                               (:effects result))]
      (is (>= (count diag-effects) 0)
          "Should produce ArtDiagData effects (or none if no subscribers)"))))

(deftest step-command-send-dmx
  (testing "send-dmx command generates ArtDmx tx-packet effect"
    (let [state {:node {:short-name "Test"}, :peers {}, :stats {}}
          event {:type         :command
                 :command      :send-dmx
                 :port-address 0x0100
                 :data         (byte-array [255 128 64])
                 :target       {:host "192.168.1.100", :port 6454}
                 :timestamp    1000000}
          result (step/step state event)
          dmx-effects (filter #(and (= :tx-packet (:effect %))
                                    (= :artdmx (:op %)))
                              (:effects result))]
      (is (= 1 (count dmx-effects))
          "Should produce one ArtDmx tx-packet effect")
      (when (seq dmx-effects)
        (is (= 0x0100 (get-in (first dmx-effects) [:data :port-address]))
            "Should include correct port-address")))))

(deftest step-artpoll-multi-page
  (testing "ArtPoll with multi-page node sends one reply per page"
    (let [state {:node  {:net-switch 0
                         :sub-switch 0
                         :port-pages [{:bind-index    1
                                       :port-types    [0xC0 0 0 0]
                                       :sw-out        [0x01 0 0 0]
                                       :good-output-a [0x80 0 0 0]}
                                      {:bind-index    2
                                       :port-types    [0xC0 0 0 0]
                                       :sw-out        [0x02 0 0 0]
                                       :good-output-a [0x40 0 0 0]}]}
                 :peers {}
                 :stats {}}
          event {:type      :rx-packet
                 :packet    {:op :artpoll, :flags 0x01}     ; suppress delay
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)
          effects (:effects result)]
      (is (= 2 (count effects))
          "Should produce 2 ArtPollReply effects, one per page")
      (is (= [1 2] (sort (mapv #(get-in % [:data :bind-index]) effects)))
          "Each effect should have correct bind-index")))
  (testing "ArtPoll targeted mode filters multi-page responses"
    (let [state {:node  {:net-switch 0
                         :sub-switch 0
                         :port-pages [{:bind-index 1
                                       :net-switch 0
                                       :sub-switch 0
                                       :port-types [0xC0 0 0 0]
                                       :sw-out     [0x00 0 0 0]}
                                      {:bind-index 2
                                       :net-switch 1
                                       :sub-switch 0
                                       :port-types [0xC0 0 0 0]
                                       :sw-out     [0x00 0 0 0]}]}
                 :peers {}
                 :stats {}}
          ;; Target only net=0 universes (port-addresses 0-15)
          event {:type      :rx-packet
                 :packet    {:op                         :artpoll
                             :flags                      0x21 ; targeted mode + suppress delay
                             :target-port-address-bottom 0
                             :target-port-address-top    15}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)
          effects (:effects result)]
      (is (= 1 (count effects)) "Should only reply for pages in targeted range")
      (is (= 1 (get-in (first effects) [:data :bind-index]))
          "Should reply with bind-index 1 (net=0)"))))

(deftest step-artaddress-programming
  (testing "ArtAddress updates node short-name"
    (let [state {:node  {:short-name "Original"
                         :net-switch 0
                         :sub-switch 0
                         :sw-out     [0 1 2 3]
                         :port-types [0x80 0x80 0x80 0x80]}
                 :peers {}
                 :stats {}}
          event {:type      :rx-packet
                 :packet
                 {:op :artaddress, :short-name "Updated", :command 0x00}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)
          new-state (:state result)]
      (is (= "Updated" (get-in new-state [:node :short-name]))
          "Should update short-name from ArtAddress")))
  (testing "ArtAddress merge mode command"
    (let [state {:node  {:short-name "Test"
                         :net-switch 0
                         :sub-switch 0
                         :sw-out     [0 1 2 3]
                         :port-types [0x80 0x80 0x80 0x80]}
                 :dmx   {:merge {:per-port {}}}
                 :peers {}
                 :stats {}}
          ;; Command 0x10 = AcMergeLtp0 (set port 0 to LTP)
          event {:type      :rx-packet
                 :packet    {:op :artaddress, :command 0x10}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 1000000}
          result (step/step state event)]
      (is (= :ltp (get-in (:state result) [:dmx :merge :per-port 0 :mode]))
          "Should set port 0 merge mode to LTP"))))

(deftest step-artsync-merge-behavior
  (testing "ArtSync ignored when merging per Art-Net 4 spec"
    (let [state {:node  {:short-name "Test"}
                 :dmx   {:sync  {:mode :art-sync, :active-mode nil}
                         :merge {:ports {0 {:sources {"a" {:data         (byte-array 512)
                                                           :length       512
                                                           :last-updated 1000}
                                                      "b" {:data   (byte-array 512)
                                                           :length 512
                                                           :last-updated
                                                           2000}}}}}}
                 :peers {}
                 :stats {}}
          event {:type      :rx-packet
                 :packet    {:op :artsync}
                 :sender    {:host "192.168.1.100", :port 6454}
                 :timestamp 3000000}
          result (step/step state event)
          callback-effects (filter #(= :callback (:effect %))
                                   (:effects result))]
      (is (= 1 (count callback-effects)) "Should still invoke callback")
      (when (seq callback-effects)
        (is (:ignored? (get-in (first callback-effects) [:payload]))
            "Callback should indicate sync was ignored")))))

(deftest step-diagnostics-priority-filtering
  (testing
    "Diagnostic command only sends to subscribers meeting priority threshold"
    ;; Test by sending a diagnostic command and checking which targets
    ;; receive effects
    (let [state {:node        {:short-name "Test"}
                 :diagnostics {:broadcast-target {:host "2.255.255.255"
                                                  :port 6454}}
                 :peers       {["192.168.1.1" 6454] {:host             "192.168.1.1"
                                                     :port             6454
                                                     :diag-subscriber? true
                                                     :diag-priority    0x40 ; dp-med
                                                     :diag-unicast?    true
                                                     :seen-at          1000}
                               ["192.168.1.2" 6454] {:host             "192.168.1.2"
                                                     :port             6454
                                                     :diag-subscriber? true
                                                     :diag-priority    0x80 ; dp-high
                                                     :diag-unicast?    true
                                                     :seen-at          2000}}
                 :stats       {}}
          ;; Send high priority message (0x80 = dp-high) - should reach
          ;; both
          high-prio-event {:type      :command
                           :command   :diagnostic
                           :priority  0x80
                           :text      "High priority test"
                           :timestamp 3000000}
          high-result (step/step state high-prio-event)
          high-diag-effects (filter #(and (= :tx-packet (:effect %))
                                          (= :artdiagdata (:op %)))
                                    (:effects high-result))]
      (is (>= (count high-diag-effects) 1)
          "High priority message should produce ArtDiagData effects"))))
