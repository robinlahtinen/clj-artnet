;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.firmware
  "Art-Net firmware upload coordination."
  (:require
    [taoensso.trove :as trove])
  (:import
    (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(def ^:const firmware-header-length 1060)
(def ^:const firmware-timeout-ns (long (* 30 1000000000)))
(def ^:const firmware-header-words (long (quot firmware-header-length 2)))
(def ^:const header-data-length-offset (- firmware-header-length 4))

(defn- session-expired?
  [session now]
  (let [reference (or (:updated-at session) (:started-at session))]
    (and reference (> (- (long now) (long reference)) firmware-timeout-ns))))

(defn- header-data-words
  ^long [^bytes header]
  (let [offset (int header-data-length-offset)
        b0 (bit-and (aget header offset) 0xFF)
        b1 (bit-and (aget header (+ offset 1)) 0xFF)
        b2 (bit-and (aget header (+ offset 2)) 0xFF)
        b3 (bit-and (aget header (+ offset 3)) 0xFF)]
    (bit-or (bit-shift-left b0 24)
            (bit-shift-left b1 16)
            (bit-shift-left b2 8)
            b3)))

(defn- header-total-bytes
  ^long [^bytes header]
  (+ (* 2 firmware-header-words) (* 2 (long (header-data-words header)))))

(defn- wrap16
  [value]
  (loop [sum (long value)]
    (if (< sum 0x10000)
      (bit-and sum 0xFFFF)
      (recur (+ (bit-and sum 0xFFFF) (quot sum 0x10000))))))

(defn- accumulate-sum
  [acc ^ByteBuffer buf byte-count]
  (loop [remaining (long byte-count)
         sum (long acc)]
    (cond (>= remaining 2) (let [hi (bit-and (.get buf) 0xFF)
                                 lo (bit-and (.get buf) 0xFF)
                                 word (bit-or (bit-shift-left hi 8) lo)]
                             (recur (- remaining 2)
                                    (long (wrap16 (+ sum word)))))
          (= remaining 1) (let [hi (bit-and (.get buf) 0xFF)
                                word (bit-shift-left hi 8)]
                            (long (wrap16 (+ sum word))))
          :else (long sum))))

(defn- ensure-integrity-store
  [session]
  (cond-> session
          (nil? (:header-buffer session)) (assoc :header-buffer
                                                 (byte-array firmware-header-length)
                                                 :header-received 0)
          (nil? (:payload-sum session)) (assoc :payload-sum 0)
          (nil? (:payload-bytes session)) (assoc :payload-bytes 0)
          (nil? (:header-received session)) (assoc :header-received 0)))

(defn- header-checksum
  ^long [^bytes header]
  (let [hi (bit-and (aget header 0) 0xFF)
        lo (bit-and (aget header 1) 0xFF)]
    (bit-or (bit-shift-left hi 8) lo)))

(defn- payload-checksum
  [payload-sum]
  (let [sum (long (or payload-sum 0))] (bit-and (bit-not sum) 0xFFFF)))

(defn- expected-total-bytes
  ^long [session]
  (long (or (:total-bytes session)
            (* 2 (max 0 (long (or (:firmware-length session) 0)))))))

(defn- block-validation-error
  [session block-bytes firmware-bytes]
  (let [received (long (or (:received-bytes session) 0))
        header-total (:header-total-bytes session)]
    (cond (odd? block-bytes)
          {:status :error, :reason :unaligned-block, :block-bytes block-bytes}
          (and (pos? firmware-bytes) (> received firmware-bytes))
          {:status   :error
           :reason   :length-overflow
           :expected firmware-bytes
           :received received}
          (and header-total
               (pos? firmware-bytes)
               (not= (long header-total) firmware-bytes))
          {:status           :error
           :reason           :firmware-length-mismatch
           :header-bytes     header-total
           :advertised-bytes firmware-bytes}
          :else nil)))

(defn- final-validation-error
  [session firmware-bytes]
  (let [header-received (long (or (:header-received session) 0))
        actual-total (long (or (:received-bytes session) 0))
        expected-total (long firmware-bytes)
        expected-checksum (:expected-checksum session)]
    (or (when (< header-received firmware-header-length)
          {:status          :error
           :reason          :header-incomplete
           :header-received header-received})
        (when (nil? expected-checksum)
          {:status :error, :reason :checksum-missing})
        (when (and (pos? expected-total) (not= actual-total expected-total))
          {:status   :error
           :reason   :length-mismatch
           :expected expected-total
           :received actual-total})
        (when expected-checksum
          (let [actual-checksum (payload-checksum (:payload-sum session))]
            (when (not= actual-checksum expected-checksum)
              {:status   :error
               :reason   :checksum-mismatch
               :expected expected-checksum
               :actual   actual-checksum}))))))

(defn- update-integrity-tracking
  [session ^ByteBuffer data block-bytes]
  (if (or (nil? data) (<= block-bytes 0))
    session
    (let [base (ensure-integrity-store session)
          ^bytes header (:header-buffer base)
          current (long (or (:header-received base) 0))
          header-needed (max 0 (- firmware-header-length current))
          header-copy (int (min header-needed block-bytes))
          payload-count (- block-bytes header-copy)
          header-updated
          (if (pos? header-copy)
            (do (.get data header current header-copy)
                (assoc base :header-received (+ current header-copy)))
            base)
          header-checksum-ready? (>= (:header-received header-updated) 2)
          checksum-ready
          (if (and header-checksum-ready?
                   (nil? (:expected-checksum header-updated)))
            (assoc header-updated :expected-checksum (header-checksum header))
            header-updated)
          full-header? (>= (:header-received checksum-ready)
                           firmware-header-length)
          metadata-ready
          (if (and full-header? (nil? (:header-total-bytes checksum-ready)))
            (assoc checksum-ready
              :header-data-words (header-data-words header)
              :header-total-bytes (header-total-bytes header))
            checksum-ready)
          payload-view data
          payload-sum (if (pos? payload-count)
                        (accumulate-sum (long (or (:payload-sum metadata-ready)
                                                  0))
                                        payload-view
                                        payload-count)
                        (long (or (:payload-sum metadata-ready) 0)))]
      (cond-> metadata-ready
              (pos? payload-count)
              (-> (assoc :payload-sum payload-sum)
                  (update :payload-bytes (fnil + 0) payload-count))))))

(defn initial-state
  "Builds initial firmware state."
  [{:keys [on-chunk on-complete], :as config}]
  {:sessions  {}
   :callbacks {:on-chunk on-chunk, :on-complete on-complete}
   :config    config})

(defn- now-ns [] (System/nanoTime))

(defn- peer-key
  [{:keys [host port]}]
  [(some-> host
           str) (int (or port 0x1936))])

(defn- target-for [{:keys [host port]}] {:host host, :port (or port 0x1936)})

(defn- reply-action
  [sender status]
  {:type   :send
   :target (target-for sender)
   :packet {:op :artfirmwarereply, :status status}})

(defn- duplicate-data
  ^ByteBuffer [^ByteBuffer data]
  (when data (doto (.duplicate data) (.clear))))

(defn- ensure-status
  [result]
  (cond (nil? result) {:status :ok}
        (map? result) (assoc result :status (or (:status result) :ok))
        (false? result) {:status :error, :reason :handler-returned-false}
        :else {:status :ok}))

(defn- invoke-handler
  [handler payload context]
  (if-not (fn? handler)
    {:status :ok}
    (try
      (ensure-status (handler payload))
      (catch Throwable t
        (trove/log!
          {:level :error, :id context, :msg "Firmware handler threw", :error t})
        {:status :error, :reason :handler-exception, :throwable t}))))

(defn- handler-error? [result] (= :error (:status result)))

(defn- next-block-id [block-id] (bit-and (inc (int block-id)) 0xFF))

(defn- start-session
  [transfer firmware-length now]
  (let [words (long (max 0 (long (or firmware-length 0))))
        total (long (* 2 words))]
    {:transfer          transfer
     :firmware-length   firmware-length
     :total-bytes       total
     :received-bytes    0
     :received-blocks   0
     :expected-block-id 0
     :header-buffer     (byte-array firmware-header-length)
     :header-received   0
     :payload-sum       0
     :payload-bytes     0
     :started-at        now
     :updated-at        now}))

(defn- update-session
  [session block-id block-bytes now]
  (-> session
      (update :received-bytes (fnil + 0) (long block-bytes))
      (update :received-blocks (fnil inc 0))
      (assoc :expected-block-id (next-block-id block-id) :updated-at now)))

(defn- finalize-success
  [state key session stage sender chunk-result complete-result]
  (let [reply-status (or (:reply-status complete-result)
                         (:reply-status chunk-result)
                         (if (= stage :last) :all-good :block-good))
        ack (reply-action sender reply-status)
        extras (-> []
                   (into (or (:actions chunk-result) []))
                   (into (or (:actions complete-result) [])))
        state' (if (= stage :last)
                 (update state :sessions #(dissoc % key))
                 (assoc-in state [:sessions key] session))]
    {:state   state'
     :actions (if (seq extras) (into [ack] extras) [ack])
     :status  :ok}))

(defn- failure
  [state key sender stage reason detail]
  (let [state' (update state :sessions #(dissoc % key))
        reply (reply-action sender (or (:reply-status detail) :fail))]
    (trove/log! {:level :warn
                 :id    ::firmware-transfer-failed
                 :msg   "Firmware transfer failed"
                 :data  {:reason reason
                         :stage  stage
                         :detail (dissoc detail :actions :data)}})
    {:state   state'
     :actions [reply]
     :status  :error
     :error   reason
     :detail  detail}))

(defn- complete-last-block
  [state key session sender stage transfer firmware-length firmware-bytes pkt
   node now on-complete chunk-result]
  (if-let [final-error (final-validation-error session firmware-bytes)]
    (failure state key sender stage (:reason final-error) final-error)
    (let [duration (when-let [started (:started-at session)] (- now started))
          complete-event {:node            node
                          :sender          sender
                          :transfer        transfer
                          :stage           :complete
                          :firmware-length firmware-length
                          :firmware-bytes  firmware-bytes
                          :received-bytes  (:received-bytes session)
                          :received-blocks (:received-blocks session)
                          :duration-ns     duration
                          :session         session
                          :packet          pkt}
          complete-result
          (invoke-handler on-complete complete-event ::on-complete)]
      (if (handler-error? complete-result)
        (failure state key sender stage :complete-handler complete-result)
        (finalize-success state
                          key
                          session
                          stage
                          sender
                          chunk-result
                          complete-result)))))

(defn handle-block
  "Process ArtFirmwareMaster packet."
  [state packet sender node]
  (let [{:keys [sessions callbacks]} state
        {:keys [on-chunk on-complete]} callbacks
        {:keys [stage transfer block-id firmware-length data data-length]
         :as   pkt}
        packet
        stage (or stage :unknown)
        key (peer-key sender)
        existing (get sessions key)
        now (now-ns)
        block-bytes (long (or data-length 0))
        first? (= stage :first)
        last? (= stage :last)
        data-view (duplicate-data data)
        integrity-view (duplicate-data data)
        expired? (and existing (session-expired? existing now))
        state0 (if (and expired? first?)
                 (update state :sessions #(dissoc % key))
                 state)
        existing0 (if (and expired? first?) nil existing)]
    (cond
      (= stage :unknown)
      (failure state0 key sender stage :unsupported-block {:status :error})
      (and expired? (not first?))
      (failure state0 key sender stage :timeout {:status :error, :timeout true})
      (and (not first?) (nil? existing0))
      (failure state0 key sender stage :missing-session {:status :error})
      (and existing0 (not= transfer (:transfer existing0)))
      (failure state0 key sender stage :transfer-mismatch {:status :error})
      (and existing0
           (not first?)
           (not= (int (or block-id -1)) (int (:expected-block-id existing0))))
      (failure state0
               key
               sender
               stage
               :unexpected-block
               {:status   :error
                :expected (:expected-block-id existing0)
                :received block-id})
      (and existing0
           (not= (long firmware-length) (long (:firmware-length existing0))))
      (failure state0 key sender stage :length-mismatch {:status :error})
      :else
      (let [session-base
            (if first? (start-session transfer firmware-length now) existing0)
            session-updated (-> session-base
                                (assoc :transfer transfer
                                       :firmware-length firmware-length)
                                (update-session block-id block-bytes now))
            session-tracked (if (and integrity-view (pos? block-bytes))
                              (update-integrity-tracking session-updated
                                                         integrity-view
                                                         block-bytes)
                              session-updated)
            firmware-bytes (expected-total-bytes session-tracked)]
        (if-let [validation (block-validation-error session-tracked
                                                    block-bytes
                                                    firmware-bytes)]
          (failure state0 key sender stage (:reason validation) validation)
          (let [chunk-event {:node            node
                             :sender          sender
                             :transfer        transfer
                             :stage           stage
                             :block-id        block-id
                             :start?          first?
                             :final?          last?
                             :block-bytes     block-bytes
                             :block-words     (long (quot block-bytes 2))
                             :firmware-length firmware-length
                             :firmware-bytes  firmware-bytes
                             :received-bytes  (:received-bytes session-tracked)
                             :received-blocks (:received-blocks
                                                session-tracked)
                             :session         session-tracked
                             :data            data-view
                             :packet          pkt}
                chunk-result (invoke-handler on-chunk chunk-event ::on-chunk)]
            (if (handler-error? chunk-result)
              (failure state0 key sender stage :chunk-handler chunk-result)
              (if last?
                (complete-last-block state0
                                     key
                                     session-tracked
                                     sender
                                     stage
                                     transfer
                                     firmware-length
                                     firmware-bytes
                                     pkt
                                     node
                                     now
                                     on-complete
                                     chunk-result)
                (finalize-success state0
                                  key
                                  session-tracked
                                  stage
                                  sender
                                  chunk-result
                                  {:status :ok})))))))))

(comment
  (require '[clj-artnet.impl.protocol.firmware :as fw] :reload)
  ;; initial state
  (fw/initial-state {:on-chunk prn})
  ;; handle block (stub)
  (fw/handle-block (fw/initial-state {}) {} {:host "1.2.3.4"} {})
  :rcf)
