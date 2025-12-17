;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.helpers.rdm.transport-test
  "Unit tests for the RDM transport module."
  (:require
    [clj-artnet.impl.protocol.rdm.transport :as transport]
    [clj-artnet.support.helpers :refer [thrown-with-msg?]]
    [clojure.test :refer [deftest is testing]])
  (:import
    (clojure.lang ExceptionInfo)
    (java.nio ByteBuffer)))

(deftest valid-command-classes-defined
  (testing "All RDM command classes are defined"
    (is (= #{0x20 0x21 0x30 0x31} transport/valid-command-classes)
        "Should contain GET, GET_RESPONSE, SET, SET_RESPONSE")
    (is (= #{0x20 0x30} transport/request-command-classes)
        "Requests are GET and SET")
    (is (= #{0x21 0x31} transport/response-command-classes)
        "Responses are GET_RESPONSE and SET_RESPONSE")))

(deftest valid-command-class-predicate
  (testing "GET command (0x20)" (is (transport/valid-command-class? 0x20)))
  (testing "GET_RESPONSE command (0x21)"
    (is (transport/valid-command-class? 0x21)))
  (testing "SET command (0x30)" (is (transport/valid-command-class? 0x30)))
  (testing "SET_RESPONSE command (0x31)"
    (is (transport/valid-command-class? 0x31)))
  (testing "Invalid command class"
    (is (not (transport/valid-command-class? 0x99)))
    (is (not (transport/valid-command-class? 0x00)))
    (is (not (transport/valid-command-class? nil)))))

(deftest request-and-response-predicates
  (testing "Request predicates"
    (is (transport/request? 0x20) "GET is a request")
    (is (transport/request? 0x30) "SET is a request")
    (is (not (transport/request? 0x21)) "GET_RESPONSE is not a request")
    (is (not (transport/request? 0x31)) "SET_RESPONSE is not a request"))
  (testing "Response predicates"
    (is (transport/response? 0x21) "GET_RESPONSE is a response")
    (is (transport/response? 0x31) "SET_RESPONSE is a response")
    (is (not (transport/response? 0x20)) "GET is not a response")
    (is (not (transport/response? 0x30)) "SET is not a response")))

(deftest payload-command-class-from-byte-array
  (testing "Extract command class from byte array"
    (let [payload (byte-array 25)]
      (aset-byte payload 20 (byte 0x20))
      (is (= 0x20 (transport/payload-command-class payload))
          "Command class is at byte offset 20")))
  (testing "Payload too short"
    (let [short-payload (byte-array 10)]
      (is (nil? (transport/payload-command-class short-payload))
          "Returns nil for short payloads"))))

(deftest payload-command-class-from-bytebuffer
  (testing "Extract command class from ByteBuffer"
    (let [payload (byte-array 25)
          _ (aset-byte payload 20 (byte 0x30))
          buf (ByteBuffer/wrap payload)]
      (is (= 0x30 (transport/payload-command-class buf))
          "Command class extracted from ByteBuffer"))))

(deftest rdmsub-expected-data-length
  (testing "GET command has 0 data bytes"
    (is (= 0
           (transport/expected-data-length {:command-class 0x20
                                            :sub-count     5}))))
  (testing "SET_RESPONSE has 0 data bytes"
    (is (= 0
           (transport/expected-data-length {:command-class 0x31
                                            :sub-count     5}))))
  (testing "SET command has SubCount * 2 bytes"
    (is (= 10
           (transport/expected-data-length {:command-class 0x30
                                            :sub-count     5}))))
  (testing "GET_RESPONSE has SubCount * 2 bytes"
    (is (= 10
           (transport/expected-data-length {:command-class 0x21
                                            :sub-count     5}))))
  (testing "Unknown command class returns nil"
    (is (nil? (transport/expected-data-length {:command-class 0x99
                                               :sub-count     5})))))

(deftest valid-rdmsub-packet-validation
  (testing "Valid GET packet (0 bytes)"
    (is (transport/valid-rdmsub-packet?
          {:command-class 0x20, :sub-count 3, :payload-length 0})))
  (testing "Valid SET packet (SubCount * 2 bytes)"
    (is (transport/valid-rdmsub-packet?
          {:command-class 0x30, :sub-count 5, :payload-length 10})))
  (testing "Invalid: wrong payload length"
    (is (not (transport/valid-rdmsub-packet?
               {:command-class 0x30, :sub-count 5, :payload-length 8}))))
  (testing "Invalid: sub-count is zero (illegal per spec)"
    (is (not (transport/valid-rdmsub-packet?
               {:command-class 0x20, :sub-count 0, :payload-length 0}))))
  (testing "Invalid: odd payload length"
    (is (not (transport/valid-rdmsub-packet?
               {:command-class 0x30, :sub-count 3, :payload-length 5}))))
  (testing "Invalid: unknown command class"
    (is (not (transport/valid-rdmsub-packet?
               {:command-class 0x99, :sub-count 3, :payload-length 6})))))

(deftest sub-device-range-calculation
  (testing "Calculate sub-device range"
    (is (= {:first 100, :count 5, :last 104}
           (transport/sub-device-range {:sub-device 100, :sub-count 5}))))
  (testing "Single sub-device"
    (is (= {:first 50, :count 1, :last 50}
           (transport/sub-device-range {:sub-device 50, :sub-count 1}))))
  (testing "Wrap-around at 16-bit boundary"
    (is (= {:first 65534, :count 3, :last 0}
           (transport/sub-device-range {:sub-device 65534, :sub-count 3})))))

(deftest sub-devices-vector-generation
  (testing "Generate sub-device list"
    (is (= [10 11 12 13]
           (transport/sub-devices {:sub-device 10, :sub-count 4}))))
  (testing "Empty with zero count"
    (is (= [] (transport/sub-devices {:sub-device 100, :sub-count 0})))))

(deftest sub-device-entries-parsing
  (testing "Parse entries with values"
    (let [entries (transport/sub-device-entries
                    {:sub-device 1, :sub-count 3, :values [100 200 300]})]
      (is (= 3 (count entries)))
      (is (= {:index 0, :sub-device 1, :value 100} (first entries)))
      (is (= {:index 2, :sub-device 3, :value 300} (last entries)))))
  (testing "Entries with missing values"
    (let [entries (transport/sub-device-entries
                    {:sub-device 10, :sub-count 2, :values [42]})]
      (is (= {:index 0, :sub-device 10, :value 42} (first entries)))
      (is (= {:index 1, :sub-device 11, :value nil} (second entries))))))

(deftest normalize-payload-bytes
  (testing "From byte array"
    (let [original (byte-array [1 2 3 4 5])
          result (transport/normalize-payload-bytes original)]
      (is (bytes? result))
      (is (= 5 (alength ^bytes result)))
      (is (not (identical? original result)) "Should be a copy")))
  (testing "From sequential"
    (let [result (transport/normalize-payload-bytes [10 20 30])]
      (is (bytes? result))
      (is (= 3 (alength ^bytes result)))))
  (testing "From ByteBuffer"
    (let [buf (ByteBuffer/wrap (byte-array [7 8 9]))
          result (transport/normalize-payload-bytes buf)]
      (is (bytes? result))
      (is (= 3 (alength ^bytes result))))))

(deftest normalize-payload-buffer
  (testing "Returns read-only ByteBuffer"
    (let [result (transport/normalize-payload-buffer [1 2 3])]
      (is (instance? ByteBuffer result))
      (is (.isReadOnly result)))))

(deftest validate-payload-length-bounds
  (testing "Valid length passes through"
    (is (= 50 (transport/validate-payload-length 50))))
  (testing "Minimum boundary"
    (is (= 24 (transport/validate-payload-length 24))))
  (testing "Maximum boundary"
    (is (= 255 (transport/validate-payload-length 255))))
  (testing "Too short throws"
    (is (thrown-with-msg? ExceptionInfo
                          #"shorter than minimum"
                          (transport/validate-payload-length 10))))
  (testing "Too long throws"
    (is (thrown-with-msg? ExceptionInfo
                          #"exceeds maximum"
                          (transport/validate-payload-length 300)))))

(deftest normalize-target-validation
  (testing "Valid target with host"
    (is (= {:host "192.168.1.1", :port 6454}
           (transport/normalize-target {:host "192.168.1.1", :port 6454}))))
  (testing "Default port"
    (is (= {:host "10.0.0.1", :port 0x1936}
           (transport/normalize-target {:host "10.0.0.1"}))))
  (testing "Missing host throws"
    (is (thrown-with-msg? ExceptionInfo
                          #"requires :target with :host"
                          (transport/normalize-target {})))
    (is (thrown-with-msg? ExceptionInfo
                          #"requires :target with :host"
                          (transport/normalize-target nil)))))
