(ns clj-artnet.impl.shell.core-test
  "Unit tests for impl.shell.* modules: lifecycle, commands, state, graph.

   These tests focus on the modular components while the main clj-artnet-test
   covers integration-level verification of the public API."
  (:require [clj-artnet.impl.shell.buffers :as buffers]
            [clj-artnet.impl.shell.commands :as commands]
            [clj-artnet.impl.shell.graph :as graph]
            [clj-artnet.impl.shell.lifecycle :as lifecycle]
            [clj-artnet.impl.shell.net :as net]
            [clj-artnet.impl.shell.state :as state]
            [clj-artnet.support.helpers :refer [thrown-with-msg?]]
            [clojure.test :refer [deftest is testing]])
  (:import (clojure.lang ExceptionInfo)
           (java.nio.channels DatagramChannel)))

(deftest build-logic-config-test
  (testing "extracts relevant keys from config"
    (let [config {:node            {:short-name "Test"},
                  :callbacks       {:dmx identity},
                  :diagnostics     {:threshold 10},
                  :network         {:ip [192 168 1 1]},
                  :programming     {:on-change identity},
                  :rdm             {:enabled true},
                  :sync            {:timeout-ns 1000},
                  :data            {:custom "value"},
                  :capabilities    {:status2 {:set 0x04}},
                  :failsafe        {:mode :hold},
                  :random-delay-fn (fn [] 100),
                  :extra-ignored   :ignored}
          result (lifecycle/build-logic-config config)]
      (is (= {:short-name "Test"} (:node result)))
      (is (fn? (:dmx (:callbacks result))))
      (is (= {:threshold 10} (:diagnostics result)))
      (is (nil? (:extra-ignored result)) "Should not include extra keys"))))

(deftest create-resource-pools-test
  (testing "creates rx and tx pools with defaults"
    (let [{:keys [rx-pool tx-pool]} (lifecycle/create-resource-pools {})]
      (try (is (buffers/buffer-pool? rx-pool))
           (is (buffers/buffer-pool? tx-pool))
           (finally (lifecycle/close-quietly rx-pool)
                    (lifecycle/close-quietly tx-pool)))))
  (testing "creates pools with custom sizes"
    (let [{:keys [rx-pool tx-pool]} (lifecycle/create-resource-pools
                                      {:rx-buffer {:count 64, :size 1024},
                                       :tx-buffer {:count 32, :size 1024}})]
      (try (is (buffers/buffer-pool? rx-pool))
           (is (buffers/buffer-pool? tx-pool))
           (finally (lifecycle/close-quietly rx-pool)
                    (lifecycle/close-quietly tx-pool))))))

(deftest open-network-channel-test
  (testing "opens a channel with default bind"
    (let [channel (lifecycle/open-network-channel {})]
      (try (is (instance? DatagramChannel channel))
           (is (.isOpen channel))
           (finally (lifecycle/ensure-chan-open channel)))))
  (testing "channel is closed after ensure-chan-open"
    (let [channel (lifecycle/open-network-channel {})]
      (is (.isOpen channel))
      (lifecycle/ensure-chan-open channel)
      (is (not (.isOpen channel))))))

(deftest close-quietly-test
  (testing "closes a resource without throwing"
    (let [pool (buffers/create-pool {:count 8, :size 512})]
      (is (nil? (lifecycle/close-quietly pool)))
      ;; Calling again should also not throw
      (is (nil? (lifecycle/close-quietly pool)))))
  (testing "handles nil safely" (is (nil? (lifecycle/close-quietly nil)))))

(deftest apply-state-command-test
  (testing "builds correct command map"
    (let [result (commands/apply-state-command {:node {:short-name "Updated"}})]
      (is (= :command (:type result)))
      (is (= :apply-state (:command result)))
      (is (= {:node {:short-name "Updated"}} (:state result)))))
  (testing "handles nil state"
    (let [result (commands/apply-state-command nil)]
      (is (= {} (:state result)))))
  (testing "throws on non-map state"
    (is (thrown-with-msg? ExceptionInfo
                          #"apply-state expects a map"
                          (commands/apply-state-command "invalid")))))

(deftest create-graph-structure-test
  (testing "creates flow with expected processes"
    (let [channel (net/open-channel {:bind           {:host "0.0.0.0", :port 0},
                                     :broadcast?     true,
                                     :reuse-address? true})
          rx-pool (buffers/create-pool {:count 8, :size 512})
          tx-pool (buffers/create-pool {:count 4, :size 512})]
      (try (let [flow-def (graph/create-graph
                            {:channel                  channel,
                             :rx-pool                  rx-pool,
                             :tx-pool                  tx-pool,
                             :logic-config             {:node {:short-name "Test"}},
                             :max-packet               2048,
                             :recv-buffer              32,
                             :command-buffer           16,
                             :actions-buffer           16,
                             :default-target           nil,
                             :allow-limited-broadcast? false})]
             ;; Verify the flow is created (it's an opaque object)
             (is (some? flow-def)))
           (finally (lifecycle/close-quietly rx-pool)
                    (lifecycle/close-quietly tx-pool)
                    (lifecycle/ensure-chan-open channel))))))

(deftest request-snapshot-throws-without-flow-test
  (testing "throws when node has no flow"
    (is (thrown-with-msg? ExceptionInfo
                          #"Node missing flow context"
                          (state/request-snapshot {} {} [:node])))))

(deftest io-step-dmx-frame-callback-test
  (testing "io-step translates :dmx-frame effect to callback action"
    (let [io-step #'graph/io-step
          dmx-called (atom nil)
          callbacks {:dmx-frame (fn [p] (reset! dmx-called p))}
          config {:callbacks callbacks, :node {:short-name "Test"}}
          event {:type      :rx-packet,
                 :packet    {:op           :artdmx,
                             :port-address 0,
                             :data         (byte-array 512),
                             :length       512,
                             :sequence     1,
                             :physical     0,
                             :net          0,
                             :sub-uni      0},
                 :sender    {:host "1.2.3.4", :port 6454},
                 :timestamp 0}
          ;; Run io-step. Pass nil state so it initializes.
          [_ actions] (io-step nil config event)
          callback-action (first (filter #(= :callback (:type %)) actions))]
      (is (some? callback-action) "Should produce a callback action")
      (when callback-action
        ;; Execute the callback to verify it's the right one
        ((:fn callback-action) (:payload callback-action))
        (is (= 0 (:port-address @dmx-called)))
        (is (= 512 (:length @dmx-called)))))))
