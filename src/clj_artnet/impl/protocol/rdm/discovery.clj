;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.rdm.discovery
  "Art-Net RDM/TOD helpers for the impl architecture. Maintains a
  lightweight Table-of-Devices snapshot and subscriber registry so the logic
  coordinator can focus on orchestration."
  (:require [clj-artnet.impl.protocol.codec.constants :as const]
            [clj-artnet.impl.protocol.codec.domain.common :as common])
  (:import (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(def ^:private default-subscriber-ttl-ns (long (* 300 1000000000)))
(def ^:private default-background-poll-interval-ns (long (* 500 1000000)))

(def ^:private discovery-defaults
  {:batch-size       64,
   :step-delay-ns    (long (* 50 1000000)),
   :initial-delay-ns 0,
   :max-backoff-ns   (long (* 1000 1000000))})

(defn- normalize-discovery-options
  [config]
  (let [opts (or config {})
        batch (int
                (max 1 (or (:batch-size opts) (:batch-size discovery-defaults))))
        step-ns (cond (:step-delay-ns opts) (long (max 0 (:step-delay-ns opts)))
                      (:step-delay-ms opts)
                      (long (* 1000000 (max 0 (:step-delay-ms opts))))
                      :else (:step-delay-ns discovery-defaults))
        initial (cond (:initial-delay-ns opts)
                      (long (max 0 (:initial-delay-ns opts)))
                      (:initial-delay-ms opts)
                      (long (* 1000000 (max 0 (:initial-delay-ms opts))))
                      :else (:initial-delay-ns discovery-defaults))
        max-backoff (cond (:max-backoff-ns opts)
                          (long (max 0 (:max-backoff-ns opts)))
                          (:max-backoff-ms opts)
                          (long (* 1000000 (max 0 (:max-backoff-ms opts))))
                          :else (:max-backoff-ns discovery-defaults))]
    {:batch-size       batch,
     :step-delay-ns
     (if (pos? step-ns) step-ns (:step-delay-ns discovery-defaults)),
     :initial-delay-ns initial,
     :max-backoff-ns   (max initial max-backoff)}))

(def ^:private default-background-targets-per-poll 4)

(def ^:private empty-task-queue [])

(defn- enqueue-task [queue task] (conj (or queue empty-task-queue) task))

(defn- dequeue-task
  [queue]
  (if (seq queue) [(first queue) (vec (rest queue))] [nil empty-task-queue]))

(defn- keep-tasks
  [queue pred]
  (->> (or queue empty-task-queue)
       (map pred)
       (remove nil?)
       vec))

(defn- port-info
  [state port-address]
  (when-let [port (get-in state [:ports port-address])]
    (let [{:keys [net sub-net universe]} (common/split-port-address
                                           port-address)]
      {:port-address port-address,
       :net          net,
       :sub-net      sub-net,
       :universe     universe,
       :port         (:port port 1),
       :bind-index   (:bind-index port 1),
       :rdm-version  (:rdm-version port (:version state))})))

(defn- background-targets
  [state]
  (->> (:ports state)
       (mapcat (fn [[port-address {:keys [uids]}]]
                 (let [base (port-info state port-address)
                       responders (or uids [])]
                   (when (and base (seq responders))
                     (for [uid responders] (assoc base :uid uid))))))
       (remove nil?)
       vec))

(defn- select-target-window
  [targets cursor limit]
  (let [total (count targets)]
    (if (zero? total)
      [[] 0]
      (let [limit (min limit total)
            rotated (concat (drop cursor targets) targets)
            selected (vec (take limit rotated))
            next-cursor (mod (+ cursor limit) total)]
        [selected next-cursor]))))

(defn- discovery-targets
  [state ports]
  (->> ports
       (map #(port-info state %))
       (remove nil?)
       vec))

(defn- background-policy->severity
  [policy]
  (let [value (bit-and (int (or policy 0)) 0xFF)]
    (cond (= value 0) :none
          (= value 1) :advisory
          (= value 2) :warning
          (= value 3) :error
          (= value 4) :disabled
          (< value 251) :vendor
          :else :reserved)))

(defn- severity->requested-pids
  [severity]
  (case severity
    :none [:status-none]
    :advisory [:status-message]
    :warning [:status-message :queued-message]
    :error [:status-message :queued-message]
    :vendor [:status-message :queued-message]
    []))

(defn- normalize-background-config
  [config]
  (let [supported? (true? (:supported? config))
        poll-ns (cond (:poll-interval-ns config)
                      (long (max 1 (:poll-interval-ns config)))
                      (:poll-interval-ms config)
                      (long (* 1000000 (max 1 (:poll-interval-ms config))))
                      :else default-background-poll-interval-ns)
        policy (bit-and (int (or (:policy config) 0)) 0xFF)]
    (when supported?
      {:supported?       true,
       :policy           policy,
       :severity         (background-policy->severity policy),
       :poll-interval-ns poll-ns,
       :next-poll-at     nil,
       :cursor           0})))

(defn background-queue-supported?
  "Return true when the RDM background queue workflow is enabled."
  [state]
  (true? (get-in state [:background-queue :supported?])))

(defn set-background-queue-policy
  [state policy]
  (if-not (background-queue-supported? state)
    state
    (let [value (bit-and (int (or policy 0)) 0xFF)
          severity (background-policy->severity value)
          queue (-> (:background-queue state)
                    (assoc :policy value)
                    (assoc :severity severity)
                    (cond-> (or (= severity :disabled) (= severity :reserved))
                            (assoc :next-poll-at nil)))]
      (assoc state :background-queue queue))))

(defn run-background-queue
  "Advance the background queue scheduler. Returns `[state event]` where the event is
  a map describing the requested severity/poll if a poll should be dispatched."
  [state now]
  (let [{:keys [supported? severity next-poll-at poll-interval-ns cursor],
         :as   queue}
        (:background-queue state)]
    (if (and supported?
             (not (#{:disabled :reserved} severity))
             (or (nil? next-poll-at) (<= next-poll-at now)))
      (let [interval (or poll-interval-ns default-background-poll-interval-ns)
            next (+ now interval)
            targets (background-targets state)
            cursor (long (or cursor 0))
            [selected next-cursor] (select-target-window
                                     targets
                                     cursor
                                     default-background-targets-per-poll)
            event {:policy         (:policy queue),
                   :severity       severity,
                   :requested-pids (severity->requested-pids severity),
                   :targets        selected,
                   :timestamp      now}]
        [(assoc state
           :background-queue
           (-> queue
               (assoc :next-poll-at next)
               (assoc :cursor next-cursor))) event])
      [state nil])))

(def ^:private default-rdm-version 1)

(defn- now-ns [] (System/nanoTime))

(defn- peer-key
  [{:keys [host port]}]
  [(some-> host
           str) (int (or port 0x1936))])

(defn- normalize-uid
  [uid]
  (cond (nil? uid) (vec (repeat 6 0))
        (bytes? uid) (mapv #(bit-and % 0xFF) uid)
        (instance? ByteBuffer uid)
        (let [dup (.duplicate ^ByteBuffer uid)
              length (.remaining dup)]
          (when (not= length 6)
            (throw (ex-info "ByteBuffer UID must be 6 bytes" {:length length})))
          (let [data (byte-array 6)]
            (.get dup data 0 6)
            (mapv #(bit-and % 0xFF) data)))
        (and (sequential? uid) (= 6 (count uid))) (mapv #(bit-and (int %) 0xFF)
                                                        uid)
        (integer? uid)
        (mapv (fn [shift]
                (bit-and (unsigned-bit-shift-right (long uid) shift) 0xFF))
              [40 32 24 16 8 0])
        :else (throw (ex-info "Unsupported UID representation" {:uid uid}))))

(defn- normalize-uids
  [uids]
  (->> (or uids [])
       (map normalize-uid)
       vec))

(defn- address-byte
  [port-address]
  (let [{:keys [sub-net universe]} (common/split-port-address port-address)]
    (bit-or (bit-shift-left (bit-and sub-net 0x0F) 4) (bit-and universe 0x0F))))

(defn- compose-address
  [net byte]
  (let [sub-net (bit-and (unsigned-bit-shift-right byte 4) 0x0F)
        universe (bit-and byte 0x0F)]
    (common/compose-port-address net sub-net universe)))

(defn- build-discovery-state
  [ports config]
  {:config          config,
   :queue           empty-task-queue,
   :next-run-at     nil,
   :last-run-at     nil,
   :last-request-at nil,
   :current-backoff (:initial-delay-ns config),
   :incremental
   {:next-at  nil,
    :per-port (into {} (map (fn [[addr _]] [addr {:enabled? true}]) ports))}})

(defn- next-backoff-ns
  [config current]
  (let [base (long (max (:step-delay-ns config)
                        (or current (:step-delay-ns config))))
        doubled (long (* 2 base))]
    (long (min (:max-backoff-ns config)
               (max (:step-delay-ns config) doubled)))))

(defn- reset-backoff
  [discovery]
  (let [config (:config discovery)
        initial (long (max (:initial-delay-ns config) (:step-delay-ns config)))]
    (assoc discovery :current-backoff initial)))

(defn initial-state
  "Build the initial RDM state from *node* defaults and runtime config.
  Config keys:
  * `:rdm-version`          -> advertised RDM revision (default 1)
  * `:ports`                -> {port-address {:uids [...]
                                             :port <physical>
                                             :bind-index <n>
                                             :rdm-version <n>}}
  * `:devices`              -> fallback UID vector applied when :ports entry missing
  * `:port-addresses`       -> preferred order (defaults to provided ports)
  * `:subscriber-ttl-ns/ms` -> override subscriber lifetime"
  [node
   {:keys [rdm-version ports devices port-addresses subscriber-ttl-ns
           subscriber-ttl-ms background discovery],
    :as   _config}]
  (let [version (int (or rdm-version default-rdm-version))
        ttl (long (cond subscriber-ttl-ns subscriber-ttl-ns
                        subscriber-ttl-ms (* 1000000 subscriber-ttl-ms)
                        :else default-subscriber-ttl-ns))
        address-order (->> (concat (keys (or ports {})) port-addresses)
                           (remove nil?)
                           distinct)
        fallback-uids (normalize-uids devices)
        default-bind-index (:bind-index node 1)
        normalized-ports
        (into {}
              (for [port-address address-order
                    :let [cfg (get ports port-address)
                          uids (normalize-uids (or (:uids cfg) fallback-uids))
                          physical (int (or (:port cfg) 1))
                          bind-idx (int (or (:bind-index cfg)
                                            default-bind-index))
                          rdmv (int (or (:rdm-version cfg) version))]]
                [port-address
                 {:port-address port-address,
                  :port         physical,
                  :bind-index   bind-idx,
                  :rdm-version  rdmv,
                  :uids         uids}]))
        discovery-options (merge (or (:discovery node) {}) (or discovery {}))
        discovery-config (normalize-discovery-options discovery-options)
        discovery-state (build-discovery-state normalized-ports
                                               discovery-config)
        background-config (when (map? background)
                            (let [policy (if (contains? background :policy)
                                           (:policy background)
                                           (:background-queue-policy node 0))
                                  cfg (assoc background :policy policy)]
                              (normalize-background-config cfg)))]
    (cond-> {:version           version,
             :ports             normalized-ports,
             :subscribers       {},
             :subscriber-ttl-ns ttl,
             :last-control      nil}
            discovery-state (assoc :discovery discovery-state)
            background-config (assoc :background-queue background-config))))

(defn- prune-subscribers
  [state now]
  (let [ttl (long (or (:subscriber-ttl-ns state) default-subscriber-ttl-ns))]
    (update state
            :subscribers
            (fn [subs]
              (into {}
                    (filter (fn [[_ {:keys [updated-at]}]]
                              (and updated-at (< (- now updated-at) ttl))))
                    (or subs {}))))))

(defn- remember-subscriber
  [state sender now]
  (if (and sender (:host sender))
    (assoc-in state
              [:subscribers (peer-key sender)]
              {:sender sender, :updated-at now})
    state))

(defn- available-port-addresses [state] (keys (:ports state)))

(defn- discovery-port-selection
  [state requested]
  (let [available (available-port-addresses state)
        available-set (set available)]
    (if (seq requested)
      (vec (filter available-set requested))
      (vec available))))

(defn- schedule-discovery
  [state ports mode reason now]
  (if-let [discovery (:discovery state)]
    (let [cfg (:config discovery)
          selection (discovery-port-selection state ports)
          chunk-size (:batch-size cfg)
          chunked (seq (map vec (partition-all chunk-size selection)))
          queue (:queue discovery)
          queue-empty? (empty? queue)
          queue' (if chunked
                   (reduce (fn [q chunk]
                             (enqueue-task q
                                           {:mode         mode,
                                            :ports        chunk,
                                            :reason       reason,
                                            :requested-at now}))
                           queue
                           chunked)
                   queue)
          initial-delay
          (if (= mode :full) (:initial-delay-ns cfg) (:step-delay-ns cfg))
          first-next (max initial-delay (:step-delay-ns cfg))
          next-run (if (and chunked queue-empty?)
                     (+ now first-next)
                     (:next-run-at discovery))
          base (-> discovery
                   (assoc :queue queue')
                   (assoc :next-run-at next-run))
          discovery' (cond-> base
                             (= mode :full) reset-backoff
                             (= mode :full) (assoc :last-request-at now))]
      (if chunked (assoc state :discovery discovery') state))
    state))

(defn- cancel-discovery
  [state ports]
  (if-let [discovery (:discovery state)]
    (let [selection
          (if (seq ports) (set (discovery-port-selection state ports)) nil)]
      (if selection
        (let [queue (:queue discovery)
              queue' (keep-tasks queue
                                 (fn [task]
                                   (let [remaining (vec (remove selection
                                                                (:ports task)))]
                                     (when (seq remaining)
                                       (assoc task :ports remaining)))))
              discovery' (-> discovery
                             (assoc :queue queue')
                             (cond-> (empty? queue') (assoc :next-run-at nil)))]
          (assoc state :discovery discovery'))
        (assoc state
          :discovery
          (-> discovery
              (assoc :queue empty-task-queue)
              (assoc :next-run-at nil)))))
    state))

(defn- set-incremental-state
  [state ports enabled?]
  (if-let [discovery (:discovery state)]
    (let [targets (if (seq ports)
                    (discovery-port-selection state ports)
                    (available-port-addresses state))
          discovery' (reduce (fn [acc port]
                               (assoc-in acc
                                         [:incremental :per-port port :enabled?]
                                         enabled?))
                             discovery
                             targets)]
      (assoc state :discovery discovery'))
    state))

(defn- incremental-enabled-ports
  [discovery]
  (->> (get-in discovery [:incremental :per-port])
       (keep (fn [[port {:keys [enabled?]}]]
               (when (not (false? enabled?)) port)))
       sort
       vec))

(defn- maybe-enqueue-incremental
  [state now]
  (if-let [discovery (:discovery state)]
    (let [ports (incremental-enabled-ports discovery)
          queue (:queue discovery)
          next-at (get-in discovery [:incremental :next-at])
          cfg (:config discovery)
          current-backoff (:current-backoff discovery)
          delay (or current-backoff (:initial-delay-ns cfg))]
      (cond (empty? ports) state
            (seq queue) state
            (and next-at (> next-at now)) state
            :else
            (let [state' (schedule-discovery state ports :incremental :auto now)
                  next-time (+ now (max (:step-delay-ns cfg) delay))
                  state''
                  (assoc-in state' [:discovery :incremental :next-at] next-time)
                  next-backoff (next-backoff-ns cfg delay)]
              (assoc-in state'' [:discovery :current-backoff] next-backoff))))
    state))

(defn run-discovery
  "Advance the discovery scheduler. Returns `[state event]` when work should be
  dispatched to the host controller."
  [state now]
  (if-not (:discovery state)
    [state nil]
    (let [state (maybe-enqueue-incremental state now)]
      (loop [state state]
        (let [{:keys [queue next-run-at config], :as discovery} (:discovery
                                                                  state)]
          (if (and (seq queue) (or (nil? next-run-at) (<= next-run-at now)))
            (let [[task queue'] (dequeue-task queue)
                  targets (discovery-targets state (:ports task))]
              (if (seq targets)
                (let [next-run (when (seq queue')
                                 (+ now (:step-delay-ns config)))
                      discovery' (-> discovery
                                     (assoc :queue queue')
                                     (assoc :last-run-at now)
                                     (assoc :next-run-at next-run))]
                  [(assoc state :discovery discovery')
                   {:mode         (:mode task),
                    :reason       (:reason task),
                    :ports        (:ports task),
                    :targets      targets,
                    :requested-at (:requested-at task),
                    :timestamp    now}])
                (let [discovery' (-> discovery
                                     (assoc :queue queue')
                                     (assoc :next-run-at
                                            (when (seq queue') now)))]
                  (recur (assoc state :discovery discovery')))))
            [state nil]))))))

(defn- match-port-addresses
  [state {:keys [net add-count addresses]}]
  (let [request-net (bit-and (int (or net 0)) 0x7F)
        add-count (int (or add-count 0))
        explicit? (pos? add-count)
        requested (when explicit?
                    (set (map #(compose-address request-net %)
                              (take add-count (or addresses [])))))]
    (->> (available-port-addresses state)
         (filter (fn [port-address]
                   (let [{:keys [net]} (common/split-port-address port-address)]
                     (and (= net request-net)
                          (or (nil? requested)
                              (contains? requested port-address)))))))))

(defn- discovery-running-for-port?
  [state port-address]
  (let [queue (get-in state [:discovery :queue])]
    (boolean (some (fn [task]
                     (let [ports (:ports task)]
                       (some #(= % port-address) ports)))
                   queue))))

(defn discovery-running?
  "Return true when any discovery work is queued."
  [state]
  (boolean (seq (get-in state [:discovery :queue]))))

(defn background-disabled?
  "Return true when background RDM polling is unsupported or disabled."
  [state]
  (let [{:keys [supported? severity]} (:background-queue state)]
    (if supported? (boolean (#{:disabled :reserved} severity)) true)))

(def ^:private tod-command->keyword
  {0x00 :none, 0x01 :flush, 0x02 :end, 0x03 :inc-on, 0x04 :inc-off})

(defn- tod-command-key
  [command]
  (get tod-command->keyword (bit-and (int (or command 0)) 0xFF) :unknown))

(defn- tod-control-targets
  [state packet]
  (match-port-addresses
    state
    {:net (:net packet), :add-count 1, :addresses [(:address packet)]}))

(defn- chunk-tod
  [uids]
  (let [chunks (vec (partition-all const/max-tod-uids-per-packet uids))]
    (if (seq chunks)
      (map-indexed (fn [idx chunk] {:block idx, :uids (vec chunk)}) chunks)
      [{:block 0, :uids []}])))

(defn- tod-packets-for-port
  [state port-address]
  (when-let [port (get-in state [:ports port-address])]
    (let [{:keys [net]} (common/split-port-address port-address)
          addr-byte (address-byte port-address)
          responders (vec (:uids port))
          total (count responders)
          version (:rdm-version port (:version state))
          discovery-running? (discovery-running-for-port? state port-address)]
      (if (or discovery-running? (empty? responders))
        [{:op               :arttoddata,
          :rdm-version      version,
          :port             (:port port 1),
          :bind-index       (:bind-index port 1),
          :net              net,
          :command-response 0xFF,
          :address          addr-byte,
          :uid-total        total,
          :block-count      0,
          :tod              []}]
        (for [{:keys [block uids]} (chunk-tod responders)]
          {:op               :arttoddata,
           :rdm-version      version,
           :port             (:port port 1),
           :bind-index       (:bind-index port 1),
           :net              net,
           :command-response 0,
           :address          addr-byte,
           :uid-total        total,
           :block-count      block,
           :tod              uids})))))

(defn- mk-send-action
  [sender packet]
  {:type   :send,
   :target {:host (:host sender), :port (:port sender 0x1936)},
   :packet packet})

(defn handle-tod-request
  "Return `[rdm-state actions]` for an ArtTodRequest packet."
  [state packet sender]
  (let [now (now-ns)
        state' (prune-subscribers state now)
        matches (seq (match-port-addresses state' packet))]
    (if (empty? matches)
      [state' []]
      (let [state'' (remember-subscriber state' sender now)
            packets (mapcat #(tod-packets-for-port state'' %) matches)
            actions (mapv #(mk-send-action sender %) packets)]
        [state'' actions]))))

(defn- apply-tod-command
  [state packet now]
  (let [command (tod-command-key (:command packet))
        targets (vec (tod-control-targets state packet))]
    (case command
      :flush (let [state' (reduce (fn [acc port]
                                    (assoc-in acc [:ports port :uids] []))
                                  state
                                  targets)]
               (schedule-discovery state' targets :full :tod-control now))
      :end (cancel-discovery state targets)
      :inc-on (let [state' (set-incremental-state state targets true)
                    state''
                    (if (seq targets)
                      (assoc-in state' [:discovery :incremental :next-at] now)
                      state')]
                (maybe-enqueue-incremental state'' now))
      :inc-off (-> state
                   (set-incremental-state targets false)
                   (cancel-discovery targets))
      state)))

(defn handle-tod-control
  "Handle ArtTodControl by applying discovery commands and replying with
  ArtTodData snapshots for the targeted port."
  [state packet sender]
  (let [now (now-ns)
        state' (assoc state :last-control {:packet packet, :timestamp now})
        state'' (apply-tod-command state' packet now)
        synthetic
        {:net (:net packet), :add-count 1, :addresses [(:address packet)]}]
    (handle-tod-request state'' synthetic sender)))
