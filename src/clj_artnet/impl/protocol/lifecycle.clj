;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.lifecycle
  "State lifecycle management: initialization and cap sync."
  (:require
    [clj-artnet.impl.protocol.data :as data]
    [clj-artnet.impl.protocol.diagnostics :as diagnostics]
    [clj-artnet.impl.protocol.dmx-helpers :as dmx]
    [clj-artnet.impl.protocol.firmware :as firmware]
    [clj-artnet.impl.protocol.node-state :as state]
    [clj-artnet.impl.protocol.poll :as poll]
    [clj-artnet.impl.protocol.rdm.discovery :as rdm.discovery]
    [clj-artnet.impl.protocol.triggers :as triggers]))

(set! *warn-on-reflection* true)

(defn- nanos->millis [^long nanos] (long (Math/round (double (/ nanos 1e6)))))

(defn- capability-auto?
  [tracker key]
  (not (true? (get-in tracker [key :manual?]))))

(defn initial-capability-tracker
  [user-node]
  {:status2 {:manual? (contains? user-node :status2)}
   :status3 {:manual? (contains? user-node :status3)}})

(defn mark-capability-manual
  [tracker node-updates]
  (let [updates (or node-updates {})]
    (cond-> tracker
            (contains? updates :status2) (assoc-in [:status2 :manual?] true)
            (contains? updates :status3) (assoc-in [:status3 :manual?] true))))

(defn- apply-derived-status
  [current derived mask]
  (let [current (bit-and (int (or current 0)) 0xFF)
        derived (bit-and (int (or derived 0)) 0xFF)
        preserved (bit-and current (bit-and 0xFF (bit-not mask)))
        managed (bit-and derived mask)]
    (bit-and 0xFF (bit-or preserved managed))))

(defn derive-status-flags
  [auto-flags _callbacks network capabilities rdm-state]
  (let [status2 (when (:status2 auto-flags)
                  (let [caps (:status2 capabilities)]
                    (if-let [override (:override caps)]
                      (bit-and (int override) 0xFF)
                      (let [dhcp-active? (true? (:dhcp? network))
                            set-mask (int (or (:set caps) 0))
                            clear-mask (int (or (:clear caps) 0))
                            base (-> 0
                                     (bit-or state/status2-extended-port-bit)
                                     (bit-or state/status2-output-style-bit)
                                     (bit-or state/status2-dhcp-capable-bit)
                                     (cond-> dhcp-active?
                                             (bit-or
                                               state/status2-dhcp-active-bit))
                                     (bit-or set-mask)
                                     (bit-and (bit-not clear-mask))
                                     (bit-and 0xFF))]
                        base))))
        status3
        (when (:status3 auto-flags)
          (let [caps (:status3 capabilities)]
            (if-let [override (:override caps)]
              (bit-and (int override) 0xFF)
              (let [queue-supported? (rdm.discovery/background-queue-supported?
                                       rdm-state)
                    set-mask (int (or (:set caps) 0))
                    clear-mask (int (or (:clear caps) 0))
                    base (-> state/status3-port-direction-bit
                             (cond-> queue-supported?
                                     (bit-or
                                       state/status3-background-queue-bit))
                             (bit-or set-mask)
                             (bit-and (bit-not clear-mask))
                             (bit-and 0xFF))]
                base))))]
    (cond-> {}
            status2 (assoc :status2 status2)
            status3 (assoc :status3 status3))))

(defn refresh-capability-status
  [state]
  (let [tracker (:capability-tracker state)
        auto-flags {:status2 (capability-auto? tracker :status2)
                    :status3 (capability-auto? tracker :status3)}
        capabilities (:capabilities state)
        status2-mask (if (get-in capabilities [:status2 :override])
                       0xFF
                       state/status2-derived-mask)
        status3-mask (if (get-in capabilities [:status3 :override])
                       0xFF
                       state/status3-derived-mask)
        derived (derive-status-flags auto-flags
                                     (:callbacks state)
                                     (:network state)
                                     capabilities
                                     (:rdm state))
        node (:node state)
        status2-value (when (contains? derived :status2)
                        (apply-derived-status (:status2 node)
                                              (:status2 derived)
                                              status2-mask))
        status3-value (when (contains? derived :status3)
                        (apply-derived-status (:status3 node)
                                              (:status3 derived)
                                              status3-mask))
        next-node (-> node
                      (cond-> status2-value (assoc :status2 status2-value))
                      (cond-> status3-value (assoc :status3 status3-value)))]
    (if (identical? node next-node) state (assoc state :node next-node))))

(defn- sync-background-queue-policy
  [state]
  (let [queue (get-in state [:rdm :background-queue])
        current (bit-and
                  (int (or (get-in state [:node :background-queue-policy]) 0))
                  0xFF)]
    (cond (true? (:supported? queue))
          (let [desired (bit-and (int (or (:policy queue) 0)) 0xFF)]
            (if (= desired current)
              state
              (assoc-in state [:node :background-queue-policy] desired)))
          (some? queue) (if (zero? current)
                          state
                          (assoc-in state [:node :background-queue-policy] 0))
          :else state)))

(defn- sync-discovery-good-output-b
  [state]
  (if-let [node (:node state)]
    (let [rdm-state (:rdm state)
          idle? (not (rdm.discovery/discovery-running? rdm-state))
          background-disabled? (rdm.discovery/background-disabled? rdm-state)
          bytes (vec (or (:good-output-b node)
                         (repeat 4 state/default-good-output-b-byte)))
          clear-mask (bit-and 0xFF
                              (bit-not state/good-outputb-discovery-clear-mask))
          updated
          (mapv (fn [byte]
                  (let [base (bit-and
                               (int (or byte state/default-good-output-b-byte))
                               0xFF)
                        cleared (bit-and base clear-mask)
                        with-idle
                        (if idle?
                          (bit-or cleared state/good-outputb-discovery-idle-bit)
                          cleared)]
                    (if background-disabled?
                      (bit-or with-idle
                              state/good-outputb-background-disabled-bit)
                      with-idle)))
                bytes)]
      (if (= bytes updated)
        state
        (assoc-in state [:node :good-output-b] updated)))
    state))

(defn refresh-node-state
  [state]
  (-> state
      refresh-capability-status
      sync-background-queue-policy
      sync-discovery-good-output-b
      dmx/apply-node-merge-modes))

(defn initial-state
  "Creates initial state map from *config*.

  Returns pure immutable state map."
  [{:keys [node callbacks diagnostics programming rdm firmware sync data
           discovery capabilities failsafe triggers command-labels
           random-delay-fn]}]
  (let [diagnostics-config (diagnostics/normalize-config diagnostics)
        programming-config (or programming {})
        user-node (or node {})
        node-defaults (state/normalize-node user-node)
        network-overrides (:network programming-config)
        network-defaults (state/default-network-state node-defaults
                                                      network-overrides)
        data-config (data/normalize-config data)
        capabilities-config (state/normalize-capabilities-config capabilities)
        runtime-node node-defaults
        port-addresses (state/node-port-addresses runtime-node)
        rdm-config (assoc (or rdm {}) :port-addresses port-addresses)
        rdm-state (rdm.discovery/initial-state runtime-node rdm-config)
        firmware-config (or firmware {})
        firmware-state (firmware/initial-state firmware-config)
        sync-config (dmx/normalize-sync-config sync)
        discovery-config (poll/normalize-config discovery)
        capability-tracker (initial-capability-tracker user-node)
        failsafe-config (dmx/normalize-failsafe-config failsafe)
        trigger-config (triggers/normalize-config triggers)
        command-label-state (state/ensure-command-labels command-labels)
        dmx-state (dmx/initial-state {:sync-config     sync-config
                                      :failsafe-config failsafe-config})
        base-state {:node               runtime-node
                    :node-defaults      node-defaults
                    :network            network-defaults
                    :network-defaults   network-defaults
                    :programming        {:on-change (:on-change programming-config)}
                    :command-labels     command-label-state
                    :inputs             {:disabled        [false false false false]
                                         :per-page        {}
                                         :last-bind-index 1}
                    :rdm                rdm-state
                    :callbacks          callbacks
                    :firmware           firmware-state
                    :data               data-config
                    :capabilities       capabilities-config
                    :dmx                dmx-state
                    :discovery          discovery-config
                    :random-delay-fn    random-delay-fn
                    :peers              {}
                    :capability-tracker capability-tracker
                    :diagnostics
                    {:subscribers                {}
                     :broadcast-target           (:broadcast-target diagnostics-config)
                     :subscriber-ttl-ns          (:subscriber-ttl-ns diagnostics-config)
                     :subscriber-warning-threshold
                     (:subscriber-warning-threshold diagnostics-config)
                     :rate-limit-hz              (:rate-limit-hz diagnostics-config)
                     :min-interval-ns            (:min-interval-ns diagnostics-config)
                     :subscriber-warning-raised? false
                     :rate-warning-raised?       false
                     :last-emitted-ns            nil
                     :last-dropped-ns            nil}
                    :triggers           (assoc trigger-config :history {})
                    :stats              {:rx-artdmx                 0
                                         :rx-artnzs                 0
                                         :rx-artvlc                 0
                                         :rx-artinput               0
                                         :tx-artdmx                 0
                                         :tx-artrdm                 0
                                         :rx-artsync                0
                                         :rx-artnzs-throttled       0
                                         :poll-requests             0
                                         :diagnostics-sent          0
                                         :diagnostics-throttled     0
                                         :address-requests          0
                                         :ip-program-requests       0
                                         :tod-requests              0
                                         :tod-controls              0
                                         :rdm-commands              0
                                         :rdm-invalid-command-class 0
                                         :rdm-sub-commands          0
                                         :rdm-sub-invalid           0
                                         :rdm-sub-proxied           0
                                         :firmware-requests         0
                                         :trigger-requests          0
                                         :trigger-throttled         0
                                         :trigger-replies           0
                                         :command-requests          0
                                         :data-requests             0}}]
    (-> base-state
        refresh-node-state
        (assoc :node-defaults (:node base-state)))))

(defn ensure-state [state config] (or state (initial-state config)))

(defn snapshot
  "Extracts state subset for diagnostics."
  [state keys]
  (let [diagnostics? (some #{:diagnostics} keys)
        dmx? (some #{:dmx} keys)
        [state' refreshed-subscribers]
        (if diagnostics? (diagnostics/refresh-state state) [state nil])
        selected (select-keys state' keys)
        diagnostics (:diagnostics selected)
        subscribers (or refreshed-subscribers
                        (when diagnostics (vals (:subscribers diagnostics))))
        last-updated (when (seq subscribers)
                       (apply max 0 (keep :updated-at subscribers)))
        summary
        (when diagnostics
          (let [ttl-ns (long (or (:subscriber-ttl-ns diagnostics) 0))]
            {:subscriber-count  (count (or subscribers []))
             :subscriber-ttl-ms (nanos->millis ttl-ns)
             :warning-threshold (:subscriber-warning-threshold diagnostics)
             :warning?          (true? (:subscriber-warning-raised? diagnostics))
             :last-updated-ns   (or last-updated 0)}))
        dmx-snapshot (when dmx? (dmx/snapshot state'))]
    (cond-> selected
            diagnostics (assoc-in [:diagnostics :summary] summary)
            dmx? (assoc :dmx dmx-snapshot))))

(comment
  (require '[clj-artnet.impl.protocol.lifecycle :as life] :reload)
  ;; init state
  (life/initial-state {:node {:short-name "Foo"}})
  :rcf)
