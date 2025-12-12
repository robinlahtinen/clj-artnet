(ns clj-artnet.clj-artnet-test
  (:require [clj-artnet :as core]
            [clojure.test :refer [deftest is]])
  (:import (java.util.concurrent Future)))

(def ^:private default-node-config
  {:bind {:host "127.0.0.1", :port 0}, :random-delay-fn (constantly 0)})

(defn- start-test-node
  [overrides]
  (core/start-node! (merge default-node-config overrides)))

(defn- wait-for-state
  "Poll state until predicate returns truthy or ::timeout."
  ([node predicate] (wait-for-state node predicate 1000))
  ([node predicate timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [snapshot (core/state node)]
         (if (predicate snapshot)
           snapshot
           (if (< (System/currentTimeMillis) deadline)
             (do (Thread/sleep 25) (recur))
             ::timeout)))))))

(deftest api-entrypoint-available
  (is (fn? core/start-node!) "Expected public start-node! to exist"))

(deftest diagnostics-exposes-summary
  (let [node (core/start-node! {:bind        {:host "127.0.0.1", :port 0},
                                :diagnostics {:subscriber-warning-threshold
                                              1}})]
    (try (Thread/sleep 200)
         (let [snapshot (core/diagnostics node)
               summary (get-in snapshot [:diagnostics :summary])]
           (is (= 0 (:subscriber-count summary)))
           (is (false? (:warning? summary))))
         (finally ((:stop! node))))))

(deftest state-snapshot-reflects-config
  (let [node (start-test-node {:node        {:short-name "Stateless Node",
                                             :long-name  "Stateless integration test",
                                             :net-switch 5,
                                             :sub-switch 2},
                               :programming {:network {:ip          [10 42 0 7],
                                                       :subnet-mask [255 255 0
                                                                     0],
                                                       :gateway     [10 42 0 1]}}})]
    (try (let [snapshot (core/state node {:keys [:node :network]})
               node-only (core/state node {:keys [:node]})]
           (is (= #{:node :network} (set (keys snapshot)))
               "state should honor requested keys")
           (is (= "Stateless Node" (get-in snapshot [:node :short-name])))
           (is (= [10 42 0 7] (get-in snapshot [:network :ip])))
           (is (= #{:node} (set (keys node-only)))
               "Filtering to :node should omit other sections"))
         (finally ((:stop! node))))))

(deftest apply-state-updates-node-state
  (let [node (start-test-node {:node {:short-name "Baseline"}})]
    (try (let [snapshot (core/state node)]
           (core/apply-state! node
                              {:node    {:short-name "Restored", :style 1},
                               :network {:dhcp? true}})
           (let [updated (wait-for-state node
                                         #(= "Restored"
                                             (get-in % [:node :short-name]))
                                         2000)]
             (is (not= ::timeout updated)
                 "apply-state! should eventually update runtime state")
             (is (= "Baseline" (get-in snapshot [:node :short-name]))
                 "Snapshots should remain immutable after apply-state!")
             (is (= "Restored" (get-in updated [:node :short-name])))
             (is (= 1 (get-in updated [:node :style])))
             (is (true? (get-in updated [:network :dhcp?])))))
         (finally ((:stop! node))))))

(deftest enqueue-command-processes-apply-state-command
  (let [node (start-test-node {:node {:short-name "Queue Baseline"}})]
    (try (let [command {:type    :command,
                        :command :apply-state,
                        :state   {:node {:short-name              "Queued Name",
                                         :background-queue-policy 0xAA}}}
               future (core/enqueue-command! node command)
               updated (wait-for-state node
                                       #(= "Queued Name"
                                           (get-in % [:node :short-name]))
                                       2000)]
           (is (instance? Future future)
               "enqueue-command! should return a Future handle")
           (is (not= ::timeout updated)
               "Queued command should propagate to the logic process")
           (is (= "Queued Name" (get-in updated [:node :short-name])))
           (is (= 0xAA (get-in updated [:node :background-queue-policy]))))
         (finally ((:stop! node))))))
