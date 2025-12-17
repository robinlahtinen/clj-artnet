;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.diagnostics
  "Diagnostics logic (Art-Net 4)."
  (:require
    [clojure.string :as str]
    [taoensso.trove :as trove]))

(set! *warn-on-reflection* true)

(def ^:const nanos-per-second 1000000000)

(def ^:private default-subscriber-ttl-ns (long (* 30 nanos-per-second)))

(def ^:private default-warning-threshold 32)

(def ^:const default-rate-limit-hz 0.0)

(def ^:const default-priority-code 0x10)

(def ^:private priority-table
  {:dp-low      0x10
   :dp-med      0x40
   :dp-high     0x80
   :dp-critical 0xE0
   :dp-volatile 0xF0})

(def ^:private priority-codes (set (vals priority-table)))

(defn- normalize-priority-code
  [value fallback]
  (cond (nil? value) fallback
        (keyword? value) (if-let [code (get priority-table value)]
                           code
                           (throw (ex-info
                                    "Unknown diagnostics priority keyword"
                                    {:value   value
                                     :allowed (sort (keys priority-table))})))
        (string? value) (normalize-priority-code (some-> value
                                                         str/lower-case
                                                         keyword)
                                                 fallback)
        (integer? value)
        (let [code (bit-and (int value) 0xFF)]
          (if (priority-codes code)
            code
            (throw (ex-info "Diagnostics priority must use Table 5 codes"
                            {:value   value
                             :allowed (sort (vals priority-table))}))))
        :else (throw (ex-info "Unsupported diagnostics priority value"
                              {:value   value
                               :allowed (sort (keys priority-table))}))))

(defn priority-code
  "Normalizes priority to Table 5 code. Defaults to :dp-low."
  ([value] (priority-code value nil))
  ([value default-value]
   (let [fallback (if (some? default-value)
                    (normalize-priority-code default-value
                                             default-priority-code)
                    default-priority-code)]
     (normalize-priority-code value fallback))))

(defn normalize-config
  "Normalizes diagnostics configuration options."
  [diagnostics]
  (let [config (or diagnostics {})
        broadcast-target (or (:broadcast-target config)
                             {:host "2.255.255.255", :port 0x1936})
        ttl (cond (:subscriber-ttl-ns config) (long (:subscriber-ttl-ns config))
                  (:subscriber-ttl-ms config)
                  (long (* 1000000 (:subscriber-ttl-ms config)))
                  :else default-subscriber-ttl-ns)
        threshold (long (max 0
                             (or (:subscriber-warning-threshold config)
                                 default-warning-threshold)))
        requested-hz (cond (contains? config :rate-limit-hz) (:rate-limit-hz
                                                               config)
                           (contains? config :min-interval-ms)
                           (let [ms (double (or (:min-interval-ms config) 0))]
                             (when (pos? ms) (/ 1000.0 ms)))
                           :else default-rate-limit-hz)
        rate-limit-hz (double (or requested-hz 0.0))
        positive-hz (when (pos? rate-limit-hz) rate-limit-hz)
        min-interval-ns (when positive-hz
                          (long (/ nanos-per-second positive-hz)))]
    {:broadcast-target             broadcast-target
     :subscriber-ttl-ns            ttl
     :subscriber-warning-threshold threshold
     :rate-limit-hz                (or positive-hz 0.0)
     :min-interval-ns              min-interval-ns}))

(defn- nano-time [] (System/nanoTime))

(defn- prune-subscribers
  [diagnostics now]
  (let [ttl (long (or (:subscriber-ttl-ns diagnostics)
                      default-subscriber-ttl-ns))
        subscribers (or (:subscribers diagnostics) {})
        pruned (into {}
                     (filter (fn [[_ {:keys [updated-at]}]]
                               (and updated-at (< (- now updated-at) ttl))))
                     subscribers)]
    (assoc diagnostics :subscribers pruned)))

(defn- apply-warning
  [diagnostics subscriber-count]
  (let [threshold (long (max 0
                             (or (:subscriber-warning-threshold diagnostics)
                                 default-warning-threshold)))
        raised? (true? (:subscriber-warning-raised? diagnostics))
        over? (> subscriber-count threshold)
        diagnostics'
        (cond over? (assoc diagnostics :subscriber-warning-raised? true)
              raised? (assoc diagnostics :subscriber-warning-raised? false)
              :else diagnostics)]
    (when (and over? (not raised?))
      (trove/log! {:level :warn
                   :id    ::diagnostic-subscriber-threshold
                   :msg   "Diagnostics subscriber count exceeded threshold"
                   :data  {:count subscriber-count, :threshold threshold}}))
    diagnostics'))

(defn refresh-state
  "Prunes subscribers and updates warnings. Returns [state subscribers]."
  ([state] (refresh-state state (nano-time)))
  ([state now]
   (if-let [diagnostics (:diagnostics state)]
     (let [pruned (prune-subscribers diagnostics now)
           subscribers (vals (:subscribers pruned))
           diagnostics' (apply-warning pruned (count subscribers))]
       [(assoc state :diagnostics diagnostics') subscribers])
     [state []])))

(defn ack-actions
  "Generates ArtDiagData actions for acknowledgements."
  [state sender acknowledgements]
  (let [valid (->> acknowledgements
                   (keep (fn [{:keys [text priority logical-port]}]
                           (let [trimmed (some-> text
                                                 str/trim)]
                             (when (seq trimmed)
                               {:text         trimmed
                                :priority     (bit-and (int (or priority 0x10))
                                                       0xFF)
                                :logical-port (bit-and (int (or logical-port 0))
                                                       0xFF)})))))]
    (if (or (empty? valid) (nil? (:host sender)))
      [state []]
      (let [target {:host (:host sender), :port (:port sender 0x1936)}
            actions (mapv (fn [{:keys [text priority logical-port]}]
                            {:type   :send
                             :target target
                             :packet {:op           :artdiagdata
                                      :priority     priority
                                      :logical-port logical-port
                                      :text         text}})
                          valid)]
        [(update-in state [:stats :diagnostics-sent] (fnil + 0) (count actions))
         actions]))))

(defn resolve-diagnostic-targets
  "Resolves diagnostic message targets.

  Returns:
    [state {:targets [...] :effective-priority int :subscriber-count int}]"
  [state message-priority]
  (let [[state' active-diag-subscribers] (refresh-state state)
        broadcast-target (get-in state' [:diagnostics :broadcast-target])
        msg-prio (or message-priority 0x10)
        has-diag-subscriber-system? (contains? (:diagnostics state)
                                               :subscribers)
        diag-subscribers (mapv (fn [sub]
                                 {:host     (:host sub)
                                  :port     (or (:port sub) 0x1936)
                                  :unicast? (:unicast? sub)
                                  :priority (or (:priority sub) 0x10)})
                               active-diag-subscribers)
        peer-subscribers (if has-diag-subscriber-system?
                           []
                           (vec (keep (fn [[_ peer]]
                                        (when (:diag-subscriber? peer)
                                          {:host     (:host peer)
                                           :port     (or (:port peer) 0x1936)
                                           :unicast? (:diag-unicast? peer)
                                           :priority (or (:diag-priority peer)
                                                         0x10)}))
                                      (:peers state'))))
        all-subscribers (into diag-subscribers peer-subscribers)
        eligible (filter #(>= msg-prio (:priority %)) all-subscribers)
        subscriber-count (count eligible)
        effective-priority
        (if (seq eligible) (apply min (map :priority eligible)) msg-prio)
        {:keys [unicast broadcast]}
        (group-by (fn [sub] (if (:unicast? sub) :unicast :broadcast)) eligible)
        unicast-targets (mapv #(select-keys % [:host :port]) unicast)
        broadcast-targets (when (and (seq broadcast) broadcast-target)
                            [broadcast-target])
        targets (vec (concat (or broadcast-targets []) unicast-targets))]
    [state'
     {:targets            targets
      :effective-priority effective-priority
      :subscriber-count   subscriber-count
      :has-unicast?       (boolean (seq unicast))
      :has-broadcast?     (boolean (seq broadcast))}]))

(comment
  (require '[clj-artnet.impl.protocol.diagnostics :as diagnostics] :reload)
  (require '[clj-artnet.impl.protocol.state :as state] :reload)
  ;; normalize priority
  (diagnostics/priority-code :dp-high)                      ;; => 0x80
  ;; refresh state
  (diagnostics/refresh-state (state/initial-state))
  :rcf)
