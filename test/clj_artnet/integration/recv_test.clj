;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.integration.recv-test
  "Integration tests for actual UDP packet reception.

   These tests verify that the DatagramChannel.receive() code path works
   correctly by sending real UDP packets and verifying the node processes them.
   Unlike other integration tests that use flow/inject, these tests exercise
   the complete network I/O stack."
  (:require
    [clj-artnet :as core]
    [clj-artnet.support.helpers :as helpers]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.net DatagramPacket DatagramSocket InetAddress)
    (java.nio ByteBuffer)
    (java.nio.charset StandardCharsets)))

;; Use a fixed high port in the dynamic/private range to avoid conflicts.
(def ^:private test-node-port 16456)

(defn- build-artdmx-bytes
  "Build raw ArtDmx packet bytes for UDP transmission.
   This directly constructs the wire format per Art-Net 4 spec."
  [dmx-data]
  (let [data-len (alength dmx-data)
        ;; ArtDmx packet: 18 bytes header + DMX data
        packet-size (+ 18 data-len)
        buf (ByteBuffer/allocate packet-size)]
    ;; Art-Net ID (8 bytes)
    (.put buf (.getBytes "Art-Net\u0000" StandardCharsets/US_ASCII))
    ;; OpCode (2 bytes, little-endian) - ArtDmx = 0x5000
    (.put buf (unchecked-byte 0x00))
    (.put buf (unchecked-byte 0x50))
    ;; Protocol Version (2 bytes, big-endian) - 14
    (.put buf (unchecked-byte 0x00))
    (.put buf (unchecked-byte 14))
    ;; Sequence (1 byte)
    (.put buf (unchecked-byte 1))
    ;; Physical (1 byte)
    (.put buf (unchecked-byte 0))
    ;; SubUni (1 byte) - universe in low 4 bits, sub-net in high 4 bits
    (.put buf (unchecked-byte 0))
    ;; Net (1 byte)
    (.put buf (unchecked-byte 0))
    ;; Length (2 bytes, big-endian)
    (.put buf (unchecked-byte (bit-shift-right data-len 8)))
    (.put buf (unchecked-byte (bit-and data-len 0xFF)))
    ;; DMX Data
    (.put buf ^bytes dmx-data)
    (.flip buf)
    (let [arr (byte-array (.remaining buf))]
      (.get buf arr)
      arr)))

(deftest node-receives-actual-udp-artdmx
  (testing "Node receives real UDP ArtDmx packet and invokes callback"
    (let [dmx-received (promise)
          node (core/start-node! {:bind      {:host "0.0.0.0"
                                              :port test-node-port}
                                  :callbacks {:dmx (fn [{:keys [packet]}]
                                                     (deliver dmx-received
                                                              packet))}})]
      (try
        ;; Wait for flow to be ready
        (helpers/wait-for #(some? (:flow node)) 1000)
        ;; Give the receiver thread time to start blocking on receive()
        (Thread/sleep 300)
        (let [sender (DatagramSocket.)
              dmx-data (byte-array [255 128 64 32 16])
              packet-bytes (build-artdmx-bytes dmx-data)
              pkt (DatagramPacket. ^bytes packet-bytes
                                   (alength packet-bytes)
                                   (InetAddress/getByName "127.0.0.1")
                                   (int test-node-port))]
          (try
            ;; Send ArtDmx to the node
            (.send sender pkt)
            ;; Wait for callback
            (let [result (deref dmx-received 5000 ::timeout)]
              (is (not= ::timeout result)
                  "DMX callback should be invoked for received ArtDmx packet")
              (when (not= ::timeout result)
                (is (= :artdmx (:op result)) "Received packet should be ArtDmx")
                (is (= 5 (:length result))
                    "Received packet should have correct length")))
            (finally (.close sender))))
        (finally ((:stop! node)))))))
