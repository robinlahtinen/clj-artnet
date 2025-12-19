;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.conformance-test
  "Art-Net 4 Conformance verification tests.

   These tests validate wire-level protocol conformance to ensure
   packets are encoded correctly for interoperability with ACT
   (Art-Net Conformance Tester) and other Art-Net implementations."
  (:require
    [clj-artnet.impl.protocol.codec.constants :as const]
    [clj-artnet.impl.protocol.codec.dispatch :as dispatch]
    [clj-artnet.impl.protocol.lifecycle :as lifecycle]
    [clj-artnet.impl.protocol.node-state :as state]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(deftest artdiagdata-opcode-is-little-endian
  (testing "ArtDiagData OpCode 0x2300 is written as 0x00 0x23 (little-endian)"
    (let [packet {:op :artdiagdata, :priority 0x10, :text "ACT Test"}
          ^ByteBuffer buf (dispatch/encode packet (ByteBuffer/allocate 600))
          ^bytes arr (byte-array (.remaining buf))]
      (.get buf arr)
      ;; Bytes 8-9 contain the OpCode in little-endian format
      (is (= 0x00 (bit-and (aget arr 8) 0xFF)) "OpCode low byte should be 0x00")
      (is (= 0x23 (bit-and (aget arr 9) 0xFF))
          "OpCode high byte should be 0x23"))))

(deftest artdiagdata-protocol-version-is-big-endian
  (testing "Protocol version 14 is written as 0x00 0x0E (big-endian)"
    (let [packet {:op :artdiagdata, :priority 0x10, :text "ACT Test"}
          ^ByteBuffer buf (dispatch/encode packet (ByteBuffer/allocate 600))
          ^bytes arr (byte-array (.remaining buf))]
      (.get buf arr)
      ;; Bytes 10-11 contain ProtVer in big-endian format
      (is (= 0x00 (bit-and (aget arr 10) 0xFF))
          "ProtVer high byte should be 0x00")
      (is (= const/protocol-version (bit-and (aget arr 11) 0xFF))
          "ProtVer low byte should be 14"))))

(deftest artpollreply-length-is-exactly-239-bytes
  (testing "ArtPollReply must be exactly 239 bytes per Art-Net 4 specification"
    (let [node (state/normalize-node {:short-name "ACT"})
          packet (assoc node :op :artpollreply)
          ^ByteBuffer buf (dispatch/encode packet (ByteBuffer/allocate 300))]
      (is (= const/artpollreply-length (.remaining buf))
          "ArtPollReply must be exactly 239 bytes"))))

(deftest artpollreply-opcode-is-little-endian
  (testing "ArtPollReply OpCode 0x2100 is written as 0x00 0x21 (little-endian)"
    (let [node (state/normalize-node {:short-name "ACT"})
          packet (assoc node :op :artpollreply)
          ^ByteBuffer buf (dispatch/encode packet (ByteBuffer/allocate 300))
          ^bytes arr (byte-array (.remaining buf))]
      (.get buf arr)
      (is (= 0x00 (bit-and (aget arr 8) 0xFF)) "OpCode low byte should be 0x00")
      (is (= 0x21 (bit-and (aget arr 9) 0xFF))
          "OpCode high byte should be 0x21"))))

(deftest arttoddata-opcode-is-little-endian
  (testing "ArtTodData OpCode 0x8100 is written as 0x00 0x81 (little-endian)"
    (let [packet {:op               :arttoddata
                  :rdm-version      1
                  :port             1
                  :net              0
                  :address          0
                  :command-response 0xFF
                  :uid-total        0
                  :block-count      0
                  :tod              []}
          ^ByteBuffer buf (dispatch/encode packet (ByteBuffer/allocate 64))
          ^bytes arr (byte-array (.remaining buf))]
      (.get buf arr)
      (is (= 0x00 (bit-and (aget arr 8) 0xFF)) "OpCode low byte should be 0x00")
      (is (= 0x81 (bit-and (aget arr 9) 0xFF))
          "OpCode high byte should be 0x81"))))

(deftest status2-rdm-artaddress-bit-is-set
  (testing "Status2 bit 7 (RDM via ArtAddress) should be set by default"
    (let [state' (lifecycle/initial-state {})
          status2 (get-in state' [:node :status2])]
      (is (pos? (bit-and status2 state/status2-rdm-artaddress-bit))
          "Status2 bit 7 should be SET to indicate RDM ArtAddress support"))))

(deftest status2-extended-port-bit-is-set
  (testing "Status2 bit 3 (15-bit Port-Address) should be set"
    (let [state' (lifecycle/initial-state {})
          status2 (get-in state' [:node :status2])]
      (is (pos? (bit-and status2 state/status2-extended-port-bit))
          "Status2 bit 3 should be SET for 15-bit Port-Address support"))))

(deftest status2-output-style-bit-is-set
  (testing "Status2 bit 6 (Output style switching) should be set"
    (let [state' (lifecycle/initial-state {})
          status2 (get-in state' [:node :status2])]
      (is (pos? (bit-and status2 state/status2-output-style-bit))
          "Status2 bit 6 should be SET for output style switching"))))

(deftest status2-dhcp-capable-bit-is-set
  (testing "Status2 bit 2 (DHCP capable) should be set"
    (let [state' (lifecycle/initial-state {})
          status2 (get-in state' [:node :status2])]
      (is (pos? (bit-and status2 state/status2-dhcp-capable-bit))
          "Status2 bit 2 should be SET to indicate DHCP capability"))))

(deftest status2-value-per-specification
  (testing "Default Status2 value should be 0xCC (bits 7,6,3,2 set)"
    (let [state' (lifecycle/initial-state {})
          status2 (get-in state' [:node :status2])
          expected (bit-or state/status2-rdm-artaddress-bit ; 0x80
                           state/status2-output-style-bit   ; 0x40
                           state/status2-extended-port-bit  ; 0x08
                           state/status2-dhcp-capable-bit)] ; 0x04
      ;; Expected: 0x80 + 0x40 + 0x08 + 0x04 = 0xCC (204)
      (is (= expected status2)
          (str "Default Status2 should be 0x" (Integer/toHexString expected)
               " but got 0x" (Integer/toHexString status2))))))

(deftest packet-sizes-match-specification
  (testing "All core packet sizes match Art-Net 4 specification"
    ;; These are the minimum/fixed sizes per Art-Net 4 specification
    (is (= 239 const/artpollreply-length) "ArtPollReply should be 239 bytes")
    (is (= 18 const/artdmx-header-size) "ArtDmx header should be 18 bytes")
    (is (= 18 const/artdiagdata-header-size)
        "ArtDiagData header should be 18 bytes")
    (is (= 19 const/arttimecode-length) "ArtTimeCode should be 19 bytes")
    (is (= 28 const/arttoddata-header-size)
        "ArtTodData header should be 28 bytes")
    (is (= 24 const/arttodrequest-base-length)
        "ArtTodRequest base should be 24 bytes")
    (is (= 107 const/artaddress-length) "ArtAddress should be 107 bytes")
    (is (= 20 const/artinput-length) "ArtInput should be 20 bytes")
    (is (= 34 const/artipprogreply-length)
        "ArtIpProgReply should be 34 bytes")))

(deftest mac-default-is-zeros-when-not-detected
  (testing "Default MAC address is [0 0 0 0 0 0] when auto-detection fails"
    (let [node (state/normalize-node {})
          mac (:mac node)]
      (is (= [0 0 0 0 0 0] mac) "Default MAC should be all zeros"))))

(deftest mac-explicit-configuration-preserved
  (testing "Explicit MAC address configuration is preserved"
    (let [explicit-mac [0xDE 0xAD 0xBE 0xEF 0x00 0x01]
          node (state/normalize-node {:mac explicit-mac})
          mac (:mac node)]
      (is (= explicit-mac mac) "Explicit MAC should be preserved"))))

(deftest arttoddata-empty-tod-uses-todnak
  (testing "ArtTodData with empty TOD uses command-response 0xFF (TodNak)"
    ;; Per Art-Net 4: When TOD is empty or discovery is in progress,
    ;; command-response should be 0xFF (TodNak)
    (let [packet {:op               :arttoddata
                  :rdm-version      1
                  :port             1
                  :net              0
                  :address          0
                  :command-response 0xFF                    ; TodNak
                  :uid-total        0
                  :block-count      0
                  :tod              []}
          ^ByteBuffer buf (dispatch/encode packet (ByteBuffer/allocate 64))
          ^bytes arr (byte-array (.remaining buf))]
      (.get buf arr)
      ;; Byte 22 is the command-response field
      ;; (After: ID[8], OpCode[2], ProtVer[2], RdmVer[1], Port[1],
      ;; Spare[6], BindIdx[1], Net[1])
      (is (= 0xFF (bit-and (aget arr 22) 0xFF))
          "Empty TOD should use TodNak (0xFF) command-response"))))

(comment
  (require '[clj-artnet.conformance-test] :reload)
  (clojure.test/run-tests 'clj-artnet.conformance-test)
  :rcf)
