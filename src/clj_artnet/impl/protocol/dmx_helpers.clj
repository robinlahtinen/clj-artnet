;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.dmx-helpers
  "DMX logic helpers: sync, failsafe, merge (Art-Net 4)."
  (:require [clj-artnet.impl.protocol.codec.domain.common :as common]
            [clj-artnet.impl.protocol.codec.types :as types])
  (:import (java.nio ByteBuffer)
           (java.util Arrays)))

(set! *warn-on-reflection* true)

(def ^:const nanos-per-second 1000000000)
(def ^:const artsync-timeout-ns (long (* 4 nanos-per-second)))
(def ^:const default-refresh-rate-hz 44)

(def ^:const default-failsafe-config
  {:enabled?         true,
   :idle-timeout-ns  (long (* 6 1000000000)),
   :tick-interval-ns (long (* 100 1000000))})

(def ^:const failsafe-min-tick-interval-ns (long (* 10 1000000)))
(def ^:const merge-source-timeout-ns (long (* 10 1000000000)))
(def ^:const good-output-merge-bit 0x08)
(def ^:const good-output-ltp-bit 0x02)
(def ^:const good-output-sacn-bit 0x01)

(def ^:private default-sync-config
  {:mode :immediate, :buffer-ttl-ns (long (* 200 1000000))})

(def ^:private allowed-sync-modes #{:immediate :art-sync})

(defn normalize-sync-config
  "Normalizes sync config."
  [sync]
  (let [config (or sync {})
        requested (keyword (or (:mode config) (:mode default-sync-config)))
        mode (if (allowed-sync-modes requested)
               requested
               (:mode default-sync-config))
        ttl (cond (:buffer-ttl-ns config) (long (:buffer-ttl-ns config))
                  (:buffer-ttl-ms config) (long (* 1000000
                                                   (:buffer-ttl-ms config)))
                  :else (:buffer-ttl-ns default-sync-config))]
    {:mode mode, :buffer-ttl-ns ttl}))

(defn normalize-failsafe-config
  "Normalizes failsafe config."
  [failsafe]
  (let [config (or failsafe {})
        enabled? (not (false? (:enabled? config)))
        idle-timeout (cond (:idle-timeout-ns config)
                           (long (max 0 (:idle-timeout-ns config)))
                           (:idle-timeout-ms config)
                           (long (max 0 (* 1000000 (:idle-timeout-ms config))))
                           :else (:idle-timeout-ns default-failsafe-config))
        tick-interval (cond (:tick-interval-ns config) (long (:tick-interval-ns
                                                               config))
                            (:tick-interval-ms config)
                            (long (* 1000000 (:tick-interval-ms config)))
                            :else (:tick-interval-ns default-failsafe-config))]
    {:enabled?         enabled?,
     :idle-timeout-ns  idle-timeout,
     :tick-interval-ns (max failsafe-min-tick-interval-ns tick-interval)}))

(defn ensure-merge-state
  "Ensures merge state structure."
  [merge]
  (or merge {:per-port {}, :ports {}, :cancel-armed? false}))

(defn initial-state
  "Returns default DMX state shard."
  [{:keys [sync-config failsafe-config],
    :or   {sync-config     (normalize-sync-config nil),
           failsafe-config (normalize-failsafe-config nil)}}]
  {:sync        sync-config,
   :sync-buffer {},
   :throughput  {:artnzs {}},
   :merge       (ensure-merge-state nil),
   :failsafe    {:config        failsafe-config,
                 :scene         {},
                 :recorded-at   nil,
                 :playback      {},
                 :missing-scene {}},
   :sequence    0})

(defn node-merge-modes
  "Extracts merge modes from node's good-output-a.
   Returns sequence of [port-index mode]."
  [node]
  (let [good-output (vec (:good-output-a node [0 0 0 0]))]
    (map-indexed
      (fn [idx byte]
        [idx (if (pos? (bit-and (int byte) good-output-ltp-bit)) :ltp :htp)])
      good-output)))

(defn apply-node-merge-modes
  "Syncs merge modes from node config."
  [state]
  (let [merge (ensure-merge-state (get-in state [:dmx :merge]))
        modes (node-merge-modes (:node state))
        merged (reduce (fn [acc [port mode]]
                         (assoc-in acc [:per-port port :mode] mode))
                       merge
                       modes)]
    (assoc-in state [:dmx :merge] merged)))

(defn failsafe-supported?
  "Returns true if node supports failsafe."
  [state]
  (pos? (bit-and (int (or (get-in state [:node :status3]) 0)) 0x20)))

(defn failsafe-mode
  "Returns failsafe mode: :hold, :zero, :full, or :scene."
  [state]
  (let [status (int (or (get-in state [:node :status3]) 0))
        bits (bit-and (bit-shift-right status 6) 0x03)]
    ({0 :hold, 1 :zero, 2 :full, 3 :scene} bits :hold)))

(defn get-failsafe-config
  "Returns failsafe config from state."
  [state]
  (or (get-in state [:dmx :failsafe :config])
      {:enabled?        true,
       :idle-timeout-ns (:idle-timeout-ns default-failsafe-config)}))

(defn failsafe-globally-enabled?
  "Returns true if failsafe is enabled and supported."
  [state]
  (let [config (get-failsafe-config state)
        mode (failsafe-mode state)
        supported? (failsafe-supported? state)]
    (and supported? (:enabled? config) (not= mode :hold))))

(defn build-failsafe-data
  "Builds failsafe data. Returns {:data :length} or nil."
  [mode last-output scene-entry]
  (let [length (or (:length last-output) (:length scene-entry) 512)]
    (case mode
      :zero {:data (byte-array length), :length length}
      :full (let [arr (byte-array length)]
              (Arrays/fill arr (unchecked-byte 0xFF))
              {:data arr, :length length})
      :scene (when scene-entry
               {:data   (aclone ^bytes (:data scene-entry)),
                :length (min length (alength ^bytes (:data scene-entry)))})
      nil)))

(defn check-port-failsafe
  "Checks port failsafe condition. Returns failsafe info or nil."
  [state timestamp port-address port-entry]
  (let [config (get-failsafe-config state)
        mode (failsafe-mode state)
        supported? (failsafe-supported? state)
        enabled? (and supported? (:enabled? config) (not= mode :hold))
        {:keys [last-output]} port-entry
        updated-at (:updated-at last-output)
        timeout-ns (:idle-timeout-ns config)]
    (when (and enabled? updated-at (> (- timestamp updated-at) timeout-ns))
      (let [scene-entry (get-in state [:dmx :failsafe :scene port-address])
            failsafe-data (build-failsafe-data mode last-output scene-entry)]
        (when failsafe-data
          (assoc failsafe-data :port-address port-address :mode mode))))))

(defn run-failsafe
  "Checks all ports for failsafe. Returns {:state :effects}."
  [state timestamp]
  (if-not (failsafe-globally-enabled? state)
    (let [playback-ports (keys (get-in state [:dmx :failsafe :playback]))
          next-state (if (seq playback-ports)
                       (update-in state [:dmx :failsafe] dissoc :playback)
                       state)]
      {:state next-state, :effects []})
    (let [ports (get-in state [:dmx :merge :ports] {})]
      (reduce
        (fn [acc [port-address port-entry]]
          (if-let [failsafe-info (check-port-failsafe (:state acc)
                                                      timestamp
                                                      port-address
                                                      port-entry)]
            (let [{:keys [data length mode]} failsafe-info
                  state' (assoc-in (:state acc)
                                   [:dmx :failsafe :playback port-address]
                                   {:mode mode, :engaged-at timestamp, :length length})
                  {:keys [net sub-net universe]} (common/split-port-address
                                                   port-address)
                  ^ByteBuffer payload (doto (ByteBuffer/wrap ^bytes data)
                                        (.limit (int length)))
                  packet (types/->ArtDmxPacket nil
                                               0
                                               length
                                               0
                                               0
                                               net
                                               sub-net
                                               universe
                                               port-address
                                               (.asReadOnlyBuffer payload))
                  cb-payload {:packet        packet,
                              :sender        nil,
                              :failsafe?     true,
                              :failsafe-mode mode,
                              :port-address  port-address}
                  effects [{:effect       :dmx-frame,
                            :port-address port-address,
                            :sequence     0,
                            :data         (.asReadOnlyBuffer payload),
                            :length       length}
                           {:effect :callback, :key :dmx, :payload cb-payload}
                           {:effect  :log,
                            :level   :info,
                            :message "Failsafe engaged",
                            :data    {:port-address port-address, :mode mode}}]]
              {:state state', :effects (into (:effects acc) effects)})
            acc))
        {:state state, :effects []}
        ports))))

(defn- source-key->snapshot
  [[host physical]]
  (cond-> {:host host} (some? physical) (assoc :physical physical)))

(defn- merge-snapshot
  [state]
  (let [merge-state (get-in state [:dmx :merge])
        ports (reduce-kv
                (fn [acc port-address
                     {:keys [sources last-output exclusive-owner
                             exclusive-updated-at]}]
                  (let [fmt-output (when last-output
                                     (cond-> {}
                                             (:length last-output)
                                             (assoc :length (:length last-output))
                                             (:updated-at last-output)
                                             (assoc :updated-at
                                                    (:updated-at last-output))))
                        entry (cond-> {:source-count (count (or sources {}))}
                                      exclusive-owner (assoc :exclusive-owner
                                                             (source-key->snapshot
                                                               exclusive-owner))
                                      exclusive-updated-at (assoc :exclusive-updated-at
                                                                  exclusive-updated-at)
                                      fmt-output (assoc :last-output fmt-output))]
                    (assoc acc port-address entry)))
                {}
                (or (:ports merge-state) {}))
        per-port-config (reduce-kv
                          (fn [acc idx {:keys [mode protocol]}]
                            (let [details (cond-> {}
                                                  mode (assoc :mode mode)
                                                  protocol (assoc :protocol protocol))]
                              (if (seq details) (assoc acc idx details) acc)))
                          {}
                          (:per-port merge-state))]
    (cond-> {:cancel-armed? (true? (:cancel-armed? merge-state))}
            (seq ports) (assoc :ports ports)
            (seq per-port-config) (assoc :per-port per-port-config))))

(defn- failsafe-snapshot
  [state]
  (let [failsafe (or (get-in state [:dmx :failsafe]) {})
        playback (reduce-kv (fn [acc port {:keys [mode engaged-at length]}]
                              (let [entry (cond-> {}
                                                  mode (assoc :mode mode)
                                                  engaged-at (assoc :engaged-at
                                                                    engaged-at)
                                                  length (assoc :length length))]
                                (if (seq entry) (assoc acc port entry) acc)))
                            {}
                            (or (:playback failsafe) {}))
        scene-ports (vec (sort (keys (:scene failsafe))))
        missing (vec (sort (keys (:missing-scene failsafe))))]
    (cond-> {:supported? (failsafe-supported? state),
             :mode       (failsafe-mode state),
             :enabled?   (get-in failsafe [:config :enabled?])}
            (:recorded-at failsafe) (assoc :scene-recorded-at (:recorded-at failsafe))
            (seq scene-ports) (assoc :scene-ports scene-ports)
            (seq missing) (assoc :missing-scene missing)
            (seq playback) (assoc :playback playback))))

(defn- throughput-snapshot
  [state]
  (let [artnzs (get-in state [:dmx :throughput :artnzs])]
    (cond-> {} (seq artnzs) (assoc :artnzs artnzs))))

(defn snapshot
  "Returns DMX telemetry snapshot."
  [state]
  (let [merge-info (merge-snapshot state)
        failsafe-info (failsafe-snapshot state)
        throughput-info (throughput-snapshot state)]
    (cond-> {}
            (seq merge-info) (assoc :merge merge-info)
            (seq failsafe-info) (assoc :failsafe failsafe-info)
            (seq throughput-info) (assoc :throughput throughput-info))))

(def ^:const keepalive-min-interval-ns (long (* 800 1000000)))
(def ^:const keepalive-max-interval-ns (long (* 1000 1000000)))
(def ^:const keepalive-default-interval-ns (long (* 900 1000000)))

(defn ports-needing-keepalive
  "Returns list of ports idle longer than interval-ns."
  [state now interval-ns]
  (let [ports (get-in state [:dmx :merge :ports] {})]
    (into []
          (keep (fn [[port-address {:keys [last-output]}]]
                  (when-let [updated-at (:updated-at last-output)]
                    (let [idle-time (- now updated-at)]
                      (when (>= idle-time interval-ns)
                        {:port-address port-address,
                         :last-output  last-output,
                         :idle-time-ns idle-time})))))
          ports)))

(defn keepalive-packets
  "Builds ArtDmx packets for stale ports."
  [stale-ports]
  (into []
        (keep (fn [{:keys [port-address last-output]}]
                (when-let [{:keys [data length]} last-output]
                  (when (and data (pos? length))
                    (let [{:keys [net sub-net universe]}
                          (common/split-port-address port-address)
                          packet {:op       :artdmx,
                                  :sequence 0,
                                  :physical 0,
                                  :net      net,
                                  :sub-net  sub-net,
                                  :universe universe,
                                  :data     data,
                                  :length   length}]
                      {:port-address port-address, :packet packet})))))
        stale-ports))

(defn clear-sync-buffer
  "Clears sync buffer and waiting state."
  [state]
  (-> state
      (assoc-in [:dmx :sync-buffer] {})
      (assoc-in [:dmx :sync :waiting-since] nil)))

(defn update-sync-config
  "Updates sync config and clears buffer if ArtSync disabled."
  [state config]
  (let [normalized (normalize-sync-config config)
        next-sync (merge (or (get-in state [:dmx :sync]) {}) normalized)
        state' (assoc-in state [:dmx :sync] next-sync)]
    (if (= :art-sync (:mode normalized))
      state'
      (-> state'
          (assoc-in [:dmx :sync :active-mode] nil)
          (assoc-in [:dmx :sync :last-sync-at] nil)
          clear-sync-buffer))))

(defn update-failsafe-config
  "Updates failsafe config."
  [state config]
  (assoc-in state [:dmx :failsafe :config] (normalize-failsafe-config config)))

(defn apply-runtime-config
  "Applies :sync and :failsafe updates."
  [state updates]
  (let [dmx-updates (when (map? (:dmx updates)) (:dmx updates))
        sync-config (or (:sync updates) (:sync dmx-updates))
        failsafe-config (or (:failsafe updates) (:failsafe dmx-updates))]
    (cond-> state
            sync-config (update-sync-config sync-config)
            failsafe-config (update-failsafe-config failsafe-config))))

(defn record-failsafe-scene
  "Records current output as failsafe scene."
  [state timestamp]
  (let [ports (get-in state [:dmx :merge :ports])
        scene (reduce-kv (fn [acc port {:keys [last-output]}]
                           (if-let [{:keys [data length]} last-output]
                             (assoc acc
                               port
                               {:data       (aclone ^bytes data),
                                :length     length,
                                :updated-at timestamp})
                             acc))
                         {}
                         (or ports {}))]
    (if (seq scene)
      (-> state
          (assoc-in [:dmx :failsafe :scene] scene)
          (assoc-in [:dmx :failsafe :recorded-at] timestamp))
      state)))

(defn clear-failsafe-port
  "Clears failsafe state for port."
  [state port-address]
  (if (get-in state [:dmx :failsafe :playback port-address])
    (update-in state [:dmx :failsafe :playback] dissoc port-address)
    state))

(comment
  (require '[clj-artnet.impl.protocol.dmx-helpers :as h] :reload)
  ;; sync config
  (h/normalize-sync-config {:mode :art-sync})
  ;; => {:mode :art-sync, :buffer-ttl-ns ...}
  ;; initial state
  (h/initial-state {})
  ;; failsafe checks
  (h/failsafe-mode {:node {:status3 2r10000000}})           ;; => :full
  :rcf)
