;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.helpers.firmware-test
  "Unit tests for firmware transfer handling.

   Tests ArtFirmwareMaster block processing, header length validation,
   and session cleanup after errors."
  (:require
    [clj-artnet.impl.protocol.firmware :as firmware]
    [clojure.test :refer [deftest is]])
  (:import
    (java.nio ByteBuffer)))

(defn- session-key
  [{:keys [host port]}]
  [(some-> host
           str) (int (or port 0x1936))])

(deftest handle-block-chunk-handler-error
  (let [state (firmware/initial-state
                {:on-chunk (fn [_] {:status :error, :reply-status :fail})})
        packet {:stage           :first
                :transfer        :firmware
                :block-id        0
                :firmware-length 0
                :data-length     0
                :data            (ByteBuffer/allocate 0)}
        sender {:host "192.168.50.10", :port 6454}
        result (firmware/handle-block state packet sender {})]
    (is (= :error (:status result)))
    (is (= :chunk-handler (:error result)))
    (is (= :fail (get-in result [:actions 0 :packet :status])))))

(deftest handle-block-detects-header-length-mismatch
  (let [now (System/nanoTime)
        sender {:host "192.168.50.20", :port 6454}
        key (session-key sender)
        firmware-words 1000
        session {:transfer           :firmware
                 :firmware-length    firmware-words
                 :total-bytes        (* 2 firmware-words)
                 :received-bytes     firmware/firmware-header-length
                 :received-blocks    1
                 :expected-block-id  0
                 :header-buffer      (byte-array firmware/firmware-header-length)
                 :header-received    firmware/firmware-header-length
                 :header-total-bytes (+ firmware/firmware-header-length 256)
                 :payload-sum        0
                 :payload-bytes      0
                 :started-at         now
                 :updated-at         now}
        state (-> (firmware/initial-state {})
                  (assoc :sessions {key session}))
        packet {:stage           :last
                :transfer        :firmware
                :block-id        0
                :firmware-length firmware-words
                :data-length     0
                :data            (ByteBuffer/allocate 0)}
        result (firmware/handle-block state packet sender {})]
    (is (= :error (:status result)))
    (is (= :firmware-length-mismatch (:error result)))
    (is (empty? (get-in result [:state :sessions])))
    (is (= :fail (get-in result [:actions 0 :packet :status])))))
