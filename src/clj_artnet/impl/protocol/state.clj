;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.state
  "State schema and initialization (Art-Net 4).")

(set! *warn-on-reflection* true)

(def ^:const default-sync-buffer-ttl-ns
  "Default sync buffer TTL (1s)."
  1000000000)

(def ^:const default-failsafe-timeout-ns
  "Default failsafe timeout (2.5s)."
  2500000000)

(def ^:const default-merge-source-timeout-ns
  "Default merge source timeout (2.5s)."
  2500000000)

(defn initial-dmx-state
  "Creates initial DMX state.

  Options:
    :sync     - map, {:mode :immediate|:art-sync, :buffer-ttl-ns long}
    :failsafe - map, {:enabled? bool, :idle-timeout-ns long}"
  ([] (initial-dmx-state {}))
  ([config]
   (let [sync-config (:sync config)
         failsafe-config (:failsafe config)
         sync-mode (or (:mode sync-config) (:sync-mode config) :immediate)
         buffer-ttl-ms (:buffer-ttl-ms sync-config)
         buffer-ttl-ns (or (:buffer-ttl-ns sync-config)
                           (:buffer-ttl-ns config)
                           (when buffer-ttl-ms (* buffer-ttl-ms 1000000))
                           default-sync-buffer-ttl-ns)
         fs-enabled (if (some? (:enabled? failsafe-config))
                      (:enabled? failsafe-config)
                      (if (some? (:failsafe-enabled? config))
                        (:failsafe-enabled? config)
                        true))
         fs-timeout (or (:idle-timeout-ns failsafe-config)
                        (:idle-timeout-ns config)
                        default-failsafe-timeout-ns)]
     {:sync        {:mode          sync-mode
                    :buffer-ttl-ns (long buffer-ttl-ns)
                    :active-mode   nil
                    :waiting-since nil
                    :last-sync-at  nil}
      :sync-buffer {}
      :merge       {:per-port {}, :ports {}, :cancel-armed? false}
      :failsafe    {:config        {:enabled?        fs-enabled
                                    :idle-timeout-ns (long fs-timeout)}
                    :scene         {}
                    :recorded-at   nil
                    :playback      {}
                    :missing-scene {}}
      :throughput  {:artnzs {}}
      :sequence    0})))

(defn initial-state
  "Creates initial protocol state.

  Options:
    :node      - map, ArtPollReply fields
    :network   - map, Network configuration
    :callbacks - map, {:dmx fn :sync fn :rdm fn}
    :dmx       - map, DMX config
    :rdm       - map, RDM config"
  ([] (initial-state {}))
  ([config]
   {:node        (or (:node config) {})
    :network     (or (:network config) {})
    :callbacks   (or (:callbacks config) {})
    :peers       {}
    :stats       {:rx-packets 0
                  :tx-packets 0
                  :rx-artpoll 0
                  :rx-artdmx  0
                  :rx-artsync 0
                  :rx-artrdm  0}
    :dmx         (initial-dmx-state (:dmx config))
    :rdm         {:tod {}, :discovery {:state :idle, :pending-requests []}}
    :diagnostics {:subscribers {}}}))

(defn node-config
  "Returns node configuration from state."
  [state]
  (or (:node state) {}))

(defn network-config
  "Returns network configuration from state."
  [state]
  (or (:network state) {}))

(defn callbacks
  "Returns callbacks from state."
  [state]
  (or (:callbacks state) {}))

(defn get-peer
  "Returns peer entry by key."
  [state peer-key]
  (get-in state [:peers peer-key]))

(defn peer-key
  "Returns peer key [host-str port] from sender."
  [sender]
  (let [host (cond (string? (:host sender)) (:host sender)
                   (some? (:host sender)) (str (:host sender))
                   :else "unknown")]
    [(some-> host
             str) (int (or (:port sender) 6454))]))

(defn remember-peer
  "Updates peer entry in state."
  [state sender timestamp]
  (let [k (peer-key sender)
        existing (get-in state [:peers k])
        entry (-> (or existing {})
                  (assoc :last-seen timestamp)
                  (merge (select-keys sender [:host :port])))]
    (assoc-in state [:peers k] entry)))

(defn inc-stat
  "Increments statistics counter."
  [state stat-key]
  (update-in state [:stats stat-key] (fnil inc 0)))

(defn update-sequence
  "Increments DMX sequence number (wraps at 255).
   Returns [state next-seq]."
  [state]
  (let [seq-num (get-in state [:dmx :sequence] 0)
        next-seq (if (>= seq-num 255) 1 (inc seq-num))]
    [(assoc-in state [:dmx :sequence] next-seq) next-seq]))

(comment
  (require '[clj-artnet.impl.protocol.state :as state] :reload)
  ;; defaults
  (state/initial-state)
  ;; configured
  (state/initial-state {:node {:short-name "node-1"}})
  ;; peer tracking
  (-> (state/initial-state)
      (state/remember-peer {:host "1.2.3.4"} 100)
      (state/get-peer ["1.2.3.4" 6454]))
  ;; dmx sequencing
  (state/update-sequence (state/initial-state))
  :rcf)
