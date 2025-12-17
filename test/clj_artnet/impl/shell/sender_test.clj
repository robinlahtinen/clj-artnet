(ns clj-artnet.impl.shell.sender-test
  "Unit tests for I/O layer and sender loop logic.

   Tests packet sending, target resolution, and delay behavior."
  (:require
    [clj-artnet.fixtures.builders :as builders]
    [clj-artnet.impl.shell.buffers :as buffers]
    [clj-artnet.impl.shell.sender :as sender]
    [clojure.core.async :as async]
    [clojure.test :refer [deftest is]])
  (:import
    (clojure.lang ExceptionInfo)
    (java.net InetSocketAddress)
    (java.nio.channels DatagramChannel)
    (java.util.concurrent.atomic AtomicBoolean)))

(deftest sender-loop-stops-when-channel-closes
  (let [running? (AtomicBoolean. true)
        action-chan (async/chan)
        loop-fn @#'sender/sender-loop
        loop-fut (future (loop-fn {:action-chan action-chan
                                   :running?    running?
                                   :state       {}})
                         :stopped)]
    (async/close! action-chan)
    (is (= :stopped (deref loop-fut 1000 :timeout))
        "Loop should exit when channel closes")
    (is (false? (.get running?)))))

(deftest execute-action-applies-delay-before-send
  (let [state {:running? (AtomicBoolean. true)}
        action {:type :send, :delay-ms 30}
        performed (promise)
        start (System/nanoTime)]
    (with-redefs [sender/perform-action!
                  (fn [_ action']
                    (deliver performed
                             {:action  action'
                              :elapsed (/ (- (System/nanoTime) start) 1e6)}))]
      ;; io-thread is a macro, so we can't mock it. We just let it run on a
      ;; virtual thread and verify the delay behavior through the side
      ;; effect.
      (#'sender/execute-action! state action)
      (let [{:keys [action elapsed]} (deref performed 1000 {:timeout true})]
        (is (= :send (:type action)) "Action should retain original type")
        (is (nil? (:delay-ms action))
            "Delay metadata removed before perform-action!")
        (is (>= elapsed 20.0)
            (str "Expected at least 20ms delay, saw " elapsed "ms"))))))

(defn- send-action
  [packet & [target]]
  {:type :send, :packet packet, :target target})

(deftest send-packet-prefers-explicit-target
  (let [pool (buffers/create-pool {:count 1, :size 128, :direct? false})
        channel (DatagramChannel/open)
        default-target {:host "10.0.0.1", :port 6454}
        explicit-target {:host "127.0.0.1", :port 9999}
        state {:channel                  channel
               :pool                     pool
               :default-target           default-target
               :allow-limited-broadcast? false}
        observed (promise)]
    (try (with-redefs [sender/resolve-target
                       (fn [target allow?]
                         (deliver observed {:target target, :allow? allow?})
                         (InetSocketAddress. "127.0.0.1" 15000))]
           (#'sender/send-packet!
             state
             (send-action (builders/artdmx-packet (byte-array 0))
                          explicit-target))
           (is (= {:target explicit-target, :allow? false}
                  (deref observed 500 :timeout))
               "Should use explicit action target"))
         (finally (.close channel) (.close pool)))))

(deftest send-packet-falls-back-to-default-target
  (let [pool (buffers/create-pool {:count 1, :size 128, :direct? false})
        channel (DatagramChannel/open)
        default-target {:host "10.0.0.5", :port 7000}
        state {:channel                  channel
               :pool                     pool
               :default-target           default-target
               :allow-limited-broadcast? false}
        observed (promise)]
    (try (with-redefs [sender/resolve-target
                       (fn [target allow?]
                         (deliver observed {:target target, :allow? allow?})
                         (InetSocketAddress. "127.0.0.1" 15001))]
           (#'sender/send-packet!
             state
             (send-action (builders/artdmx-packet (byte-array 0))))
           (is (= {:target default-target, :allow? false}
                  (deref observed 500 :timeout))
               "Should fall back to default target"))
         (finally (.close channel) (.close pool)))))

(deftest send-packet-errors-when-no-target-available
  (let [pool (buffers/create-pool {:count 1, :size 128, :direct? false})
        channel (DatagramChannel/open)
        state {:channel                  channel
               :pool                     pool
               :default-target           nil
               :allow-limited-broadcast? false}]
    (try (let [thrown (try (#'sender/send-packet!
                             state
                             (send-action (builders/artdmx-packet (byte-array
                                                                    0))))
                           nil
                           (catch ExceptionInfo e e))
               message (when thrown (.getMessage ^Exception thrown))]
           (is (instance? ExceptionInfo thrown)
               "Expected Missing target exception")
           (is (some? (and message (re-find #"Missing target" message)))))
         (finally (.close channel) (.close pool)))))

(deftest resolve-target-blocks-limited-broadcast-when-disabled
  (let [thrown (try (#'sender/resolve-target
                      {:host "255.255.255.255", :port 6454}
                      false)
                    nil
                    (catch ExceptionInfo e e))
        message (when thrown (.getMessage ^Exception thrown))]
    (is (instance? ExceptionInfo thrown)
        "Limited broadcast should raise when disabled")
    (is (some? (and message (re-find #"Limited broadcast" message))))))

(deftest resolve-target-allows-limited-broadcast-when-enabled
  (let [addr
        (#'sender/resolve-target {:host "255.255.255.255", :port 6454} true)]
    (is (instance? InetSocketAddress addr))
    (is (= 6454 (.getPort ^InetSocketAddress addr)))))
