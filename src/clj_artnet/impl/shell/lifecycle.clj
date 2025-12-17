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
  "Default Art-Net socket address for binding (0.0.0.0:6454)."
  {:host "0.0.0.0", :port 0x1936})

(def ^:private artnet-default-ip
  "Art-Net fallback IP address when auto-detection fails (primary range)."
  [2 0 0 1])

(def ^:private artnet-default-port "Standard Art-Net UDP port (6454)." 0x1936)

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

(defn resolve-bind
  "Resolves bind configuration to concrete IP address and UDP port.

   Returns map:
     :ip              - [a b c d] IP address for ArtPollReply
     :port            - int, UDP port for ArtPollReply (not Port-Address)
     :ip-source       - :explicit-node, :explicit-bind, :auto-detected, :fallback
     :port-source     - :explicit-node, :explicit-bind, :default
     :non-standard-port? - true if UDP port != 0x1936"
  [{:keys [node bind]}]
  (let [;; IP address resolution
        node-ip (:ip node)
        bind-host (get bind :host "0.0.0.0")
        bind-octets (net/parse-host bind-host)
        [ip ip-source]
        (cond (some? node-ip) [(net/parse-host node-ip) :explicit-node]
              (and (some? bind-octets) (not (net/wildcard? bind-octets)))
              [bind-octets :explicit-bind]
              :else (if-let [detected (net/detect-local-ip)]
                      [detected :auto-detected]
                      [artnet-default-ip :fallback]))
        ;; UDP port resolution
        node-port (:port node)
        bind-port (:port bind)
        [port port-source]
        (cond (some? node-port) [(int node-port) :explicit-node]
              (some? bind-port) [(int bind-port) :explicit-bind]
              :else [artnet-default-port :default])]
    {:ip                 ip
     :port               port
     :ip-source          ip-source
     :port-source        port-source
     :non-standard-port? (not= port artnet-default-port)}))

(defn build-logic-config
  "Extracts and normalizes the logic-layer configuration map from
   the user-provided system configuration.

   Resolves bind configuration to concrete IP and port values,
   merging them into node and network maps."
  [{:keys [node network callbacks diagnostics random-delay-fn programming rdm
           sync data capabilities failsafe]
    :as   config}]
  (let [{:keys [ip port ip-source _port-source non-standard-port?]}
        (resolve-bind config)
        ;; Merge resolved values into node
        node' (-> (or node {})
                  (assoc :ip ip)
                  (assoc :port port))
        ;; Merge resolved values into network (if not explicit)
        network' (-> (or network {})
                     (cond-> (not (:ip network)) (assoc :ip ip))
                     (cond-> (not (:port network)) (assoc :port port)))]
    ;; Log IP address resolution outcome
    (case ip-source
      :auto-detected (trove/log! {:level :info
                                  :id    ::node-ip-detected
                                  :msg   (str "Auto-detected node IP address: "
                                              ip)})
      :fallback
      (trove/log!
        {:level :warn
         :id    ::node-ip-fallback
         :msg   (str "Could not detect local IP address. Using Art-Net default: "
                     ip
                     ". Set :node :ip explicitly.")})
      nil)
    (when non-standard-port?
      (trove/log! {:level :warn
                   :id    ::udp-port-nonstandard
                   :msg   (str "Using non-standard Art-Net UDP port: "
                               port
                               ". Standard port is 6454 (0x1936).")}))
    {:node            node'
     :network         network'
     :callbacks       callbacks
     :diagnostics     diagnostics
     :random-delay-fn random-delay-fn
     :programming     programming
     :rdm             rdm
     :sync            sync
     :data            data
     :capabilities    capabilities
     :failsafe        failsafe}))

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
