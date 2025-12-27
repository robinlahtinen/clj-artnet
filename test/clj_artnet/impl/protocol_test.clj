;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol-test
  "Tests for the new protocol layer modules.

   These tests verify the pure protocol logic extracted from step.clj."
  (:require
    [clj-artnet.impl.protocol.addressing :as addressing]
    [clj-artnet.impl.protocol.discovery :as discovery]
    [clj-artnet.impl.protocol.dmx :as dmx]
    [clj-artnet.impl.protocol.effects :as effects]
    [clj-artnet.impl.protocol.state :as state]
    [clj-artnet.impl.protocol.sync :as sync]
    [clj-artnet.support.helpers :refer [thrown-with-msg?]]
    [clojure.test :refer [deftest is testing]])
  (:import
    (clojure.lang ExceptionInfo)))

(deftest port-address-composition
  (testing "compose-port-address creates 15-bit address"
    (is (= 0 (addressing/compose-port-address 0 0 0)))
    (is (= 1 (addressing/compose-port-address 0 0 1)))
    (is (= 16 (addressing/compose-port-address 0 1 0)))
    (is (= 256 (addressing/compose-port-address 1 0 0)))
    (is (= 291 (addressing/compose-port-address 1 2 3)))
    (is (= 32767 (addressing/compose-port-address 127 15 15)))))

(deftest port-address-splitting
  (testing "split-port-address extracts components"
    (is (= {:net 0, :sub-net 0, :universe 0, :port-address 0}
           (addressing/split-port-address 0)))
    (is (= {:net 1, :sub-net 2, :universe 3, :port-address 291}
           (addressing/split-port-address 291)))
    (is (= {:net 127, :sub-net 15, :universe 15, :port-address 32767}
           (addressing/split-port-address 32767)))))

(deftest port-address-validation
  (testing "valid-port-address? checks range"
    (is (false? (addressing/valid-port-address? 0))
        "Port-Address 0 is deprecated")
    (is (true? (addressing/valid-port-address? 1)))
    (is (true? (addressing/valid-port-address? 32767)))
    (is (false? (addressing/valid-port-address? 32768))
        "Port-Address exceeds 15-bit max"))
  (testing "validate-port-address! handles invalid and deprecated"
    (is (= 0 (addressing/validate-port-address! 0))
        "Port-Address 0 is allowed with warning (deprecated, not prohibited)")
    (is (thrown-with-msg? ExceptionInfo
                          #"exceeds maximum"
                          (addressing/validate-port-address! 32768)))
    (is (= 100 (addressing/validate-port-address! 100)))))

(deftest port-address-resolution
  (testing "resolve-port-address handles both forms"
    (is (= 100 (addressing/resolve-port-address {:port-address 100})))
    (is (= 291
           (addressing/resolve-port-address {:net 1, :sub-net 2, :universe 3})))
    (is (= 1 (addressing/resolve-port-address {:universe 1}))))
  (testing "normalize-address-opts provides both forms"
    (let [result (addressing/normalize-address-opts
                   {:net 1, :sub-net 2, :universe 3})]
      (is (= 291 (:port-address result)))
      (is (= 1 (:net result)))
      (is (= 2 (:sub-net result)))
      (is (= 3 (:universe result))))))

(deftest effects-result-creation
  (testing "result creates step result"
    (is (= {:state {:foo :bar}, :effects []} (effects/result {:foo :bar})))
    (is (= {:state {:x 1}, :effects [{:effect :test}]}
           (effects/result {:x 1} [{:effect :test}])))))

(deftest effects-add-effect
  (testing "add-effect appends to effects"
    (let [r (effects/result {:x 1})]
      (is (= {:state {:x 1}, :effects [{:effect :callback, :key :dmx}]}
             (effects/add-effect r {:effect :callback, :key :dmx}))))))

(deftest effects-add-effects
  (testing "add-effects appends multiple"
    (let [r (effects/result {:x 1} [{:effect :a}])]
      (is (= {:state {:x 1}, :effects [{:effect :a} {:effect :b} {:effect :c}]}
             (effects/add-effects r [{:effect :b} {:effect :c}]))))))

(deftest effects-tx-constructors
  (testing "tx-packet creates transmission effect"
    (is (= {:effect :tx-packet, :op :artdmx, :data {:foo :bar}}
           (effects/tx-packet :artdmx {:foo :bar})))
    (is (= {:effect :tx-packet
            :op     :artdmx
            :data   {:foo :bar}
            :target {:host "1.2.3.4"}}
           (effects/tx-packet :artdmx {:foo :bar} {:host "1.2.3.4"}))))
  (testing "tx-reply creates reply effect"
    (is (= {:effect :tx-packet
            :op     :artpollreply
            :data   {}
            :target {:host "1.2.3.4"}
            :reply? true}
           (effects/tx-reply :artpollreply {} {:host "1.2.3.4"}))))
  (testing "tx-broadcast creates broadcast effect"
    (is (= {:effect :tx-packet, :op :artpoll, :data {}, :broadcast? true}
           (effects/tx-broadcast :artpoll {})))))

(deftest effects-callback-and-log
  (testing "callback creates callback effect"
    (is (= {:effect :callback, :key :dmx, :payload {:data "test"}}
           (effects/callback :dmx {:data "test"}))))
  (testing "log-msg creates log effect"
    (is (= {:effect :log, :level :info, :message "test"}
           (effects/log-msg :info "test")))
    (is (= {:effect :log, :level :warn, :message "test", :data {:x 1}}
           (effects/log-msg :warn "test" {:x 1})))))

(deftest dmx-htp-merge
  (testing "merge-htp takes highest value per channel"
    (let [a (byte-array [100 50 0])
          b (byte-array [50 100 200])
          result (dmx/merge-htp a b)]
      (is (= 100 (bit-and (aget result 0) 0xFF)))
      (is (= 100 (bit-and (aget result 1) 0xFF)))
      (is (= 200 (bit-and (aget result 2) 0xFF))))))

(deftest dmx-ltp-merge
  (testing "merge-ltp returns clone of new"
    (let [a (byte-array [100 50 0])
          b (byte-array [10 20 30])
          result (dmx/merge-ltp a b)]
      (is (= 10 (bit-and (aget result 0) 0xFF)))
      (is (= 20 (bit-and (aget result 1) 0xFF)))
      (is (= 30 (bit-and (aget result 2) 0xFF))))))

(deftest dmx-source-key-generation
  (testing "dmx-source-key creates unique key"
    (is (= ["192.168.1.1" 0]
           (dmx/dmx-source-key {:host "192.168.1.1"} {:physical 0})))
    (is (= ["10.0.0.1" 2]
           (dmx/dmx-source-key {:host "10.0.0.1"} {:physical 2})))))

(deftest dmx-source-limit
  (testing "at-source-limit? enforces 2 source limit"
    (let [sources {"a" {:data (byte-array 3), :length 3, :last-updated 1}
                   "b" {:data (byte-array 3), :length 3, :last-updated 2}}]
      (is (true? (dmx/at-source-limit? sources "c")))
      (is (false? (dmx/at-source-limit? sources "a"))))))

(deftest state-initialization
  (testing "initial-state creates valid state"
    (let [s (state/initial-state)]
      (is (map? (:node s)))
      (is (map? (:network s)))
      (is (map? (:callbacks s)))
      (is (map? (:peers s)))
      (is (map? (:stats s)))
      (is (map? (:dmx s)))
      (is (map? (:rdm s)))
      (is (map? (:diagnostics s)))))
  (testing "initial-state accepts config"
    (let [s (state/initial-state {:node {:short-name "Test"}})]
      (is (= "Test" (get-in s [:node :short-name]))))))

(deftest state-peer-tracking
  (testing "remember-peer adds peer to state"
    (let [s (state/initial-state)
          sender {:host "192.168.1.100", :port 6454}
          s' (state/remember-peer s sender 1000000)]
      (is (= {:last-seen 1000000, :host "192.168.1.100", :port 6454}
             (state/get-peer s' (state/peer-key sender)))))))

(deftest state-stats-increment
  (testing "inc-stat increments counter"
    (let [s (state/initial-state)
          s' (-> s
                 (state/inc-stat :rx-packets)
                 (state/inc-stat :rx-packets))]
      (is (= 2 (get-in s' [:stats :rx-packets]))))))

(deftest discovery-flags-parsing
  (testing "parse-artpoll-flags extracts all flags"
    (let [flags (discovery/parse-artpoll-flags 2r00100110)]
      (is (true? (:target-enabled? flags)))
      (is (false? (:vlc-disable? flags)))
      (is (false? (:diag-unicast? flags)))
      (is (true? (:diag-request? flags)))
      (is (true? (:reply-on-change? flags)))
      (is (false? (:suppress-delay? flags))))))

(deftest discovery-page-port-addresses
  (testing "page-port-addresses computes addresses"
    (let [addresses (discovery/page-port-addresses
                      {:net-switch 1, :sub-switch 2, :sw-out [3 4]})]
      (is (= [291 292] (vec addresses))))))

(deftest discovery-target-range-check
  (testing "page-in-target-range? filters by range"
    (is (true? (discovery/page-in-target-range?
                 {:net-switch 1, :sub-switch 2, :sw-out [3]}
                 true
                 290
                 295)))
    (is (false? (discovery/page-in-target-range?
                  {:net-switch 1, :sub-switch 2, :sw-out [3]}
                  true
                  0
                  100)))
    (is (true? (discovery/page-in-target-range?
                 {:net-switch 1, :sub-switch 2, :sw-out [3]}
                 false
                 0
                 100))
        "Non-targeted mode always matches")))

(deftest sync-mode-queries
  (testing "configured-art-sync? checks mode"
    (is (true? (sync/configured-art-sync? {:dmx {:sync {:mode :art-sync}}})))
    (is (false? (sync/configured-art-sync? {:dmx {:sync {:mode :immediate}}}))))
  (testing "current-sync-mode returns effective mode"
    (is (= :art-sync
           (sync/current-sync-mode {:dmx {:sync {:mode        :art-sync
                                                 :active-mode nil}}})))
    (is (= :immediate
           (sync/current-sync-mode {:dmx {:sync {:mode        :art-sync
                                                 :active-mode :immediate}}}))
        "Active mode overrides configured mode")))
