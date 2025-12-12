(ns clj-artnet.impl.shell.lifecycle
  "Node lifecycle management (Shell Layer).

   Manages initialization, resource acquisition (channels, buffers),
   and clean shutdown of the imperative shell components.
   Delegates core domain logic to pure functions in the protocol layer."
  (:require [clj-artnet.impl.shell.buffers :as buffers]
            [clj-artnet.impl.shell.net :as net]
            [taoensso.trove :as trove])
  (:import (java.io Closeable)
           (java.nio.channels DatagramChannel)))

(set! *warn-on-reflection* true)

(def ^:private default-bind
  "Default Art-Net bind address (0.0.0.0:6454)."
  {:host "0.0.0.0", :port 0x1936})

(defn close-quietly
  "Closes a resource (Closeable), logging any errors instead of throwing.
   Safe to call with nil."
  [^Closeable c]
  (when c
    (try (.close c)
         (catch Throwable t
           (trove/log! {:level :warn,
                        :id    ::close-error,
                        :msg   "Error closing resource",
                        :error t,
                        :data  {:resource (class c)}})))))

(defn ensure-chan-open
  "Closes a DatagramChannel if it is still open.
   Logs errors instead of throwing."
  [^DatagramChannel ch]
  (when (and ch (.isOpen ch))
    (try (.close ch)
         (catch Throwable t
           (trove/log! {:level :warn,
                        :id    ::channel-close-error,
                        :msg   "Error closing DatagramChannel",
                        :error t})))))

(defn build-logic-config
  "Extracts and normalizes the logic-layer configuration map from
   the user-provided system configuration."
  [{:keys [node network callbacks diagnostics random-delay-fn programming rdm
           sync data capabilities failsafe]}]
  {:node            node,
   :network         network,
   :callbacks       callbacks,
   :diagnostics     diagnostics,
   :random-delay-fn random-delay-fn,
   :programming     programming,
   :rdm             rdm,
   :sync            sync,
   :data            data,
   :capabilities    capabilities,
   :failsafe        failsafe})

(defn create-resource-pools
  "Creates RX and TX buffer pools.

   Returns {:rx-pool <BufferPool> :tx-pool <BufferPool>}."
  [{:keys [rx-buffer tx-buffer]}]
  (let [rx-pool (buffers/create-pool (merge {:count 256, :size 2048}
                                            (or rx-buffer {})))
        tx-pool (buffers/create-pool (merge {:count 128, :size 2048}
                                            (or tx-buffer {})))]
    {:rx-pool rx-pool, :tx-pool tx-pool}))

(defn open-network-channel
  "Opens and configures the DatagramChannel for Art-Net I/O.
   Binds to configured address or default (0.0.0.0:6454)."
  [{:keys [bind]}]
  (net/open-channel
    {:bind (or bind default-bind), :broadcast? true, :reuse-address? true}))

(defn make-stop-fn
  "Creates an idempotent stop function for the node system.

   Returns a 0-arity function that:
   - Stops the async flow
   - Closes buffer pools
   - Closes network channel"
  [{:keys [flow rx-pool tx-pool channel]}]
  (let [closed? (atom false)]
    (fn stop! []
      (when (compare-and-set! closed? false true)
        (try (require '[clojure.core.async.flow :as flow])
             ((resolve 'clojure.core.async.flow/stop) flow)
             (catch Throwable t
               (trove/log! {:level :warn,
                            :id    ::flow-stop-failed,
                            :msg   "Flow stop failed",
                            :error t})))
        (close-quietly rx-pool)
        (close-quietly tx-pool)
        (ensure-chan-open channel)))))

(defn pause-flow!
  "Pauses a running flow (dynamically resolved to avoid circular deps)."
  [flow]
  (require '[clojure.core.async.flow :as flow-ns])
  ((resolve 'clojure.core.async.flow/pause) flow))

(defn resume-flow!
  "Resumes a paused flow (dynamically resolved to avoid circular deps)."
  [flow]
  (require '[clojure.core.async.flow :as flow-ns])
  ((resolve 'clojure.core.async.flow/resume) flow))

(comment
  (require '[clj-artnet.impl.shell.lifecycle :as lifecycle] :reload)
  ;; Create pools
  (def pools (lifecycle/create-resource-pools {:rx-buffer {:count 128}}))
  (:rx-pool pools)
  ;; Cleanup
  (lifecycle/close-quietly (:rx-pool pools))
  (lifecycle/close-quietly (:tx-pool pools))
  :rcf)
