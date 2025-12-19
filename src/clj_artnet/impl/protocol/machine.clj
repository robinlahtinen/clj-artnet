;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.machine
  "Pure Art-Net protocol state machine."
  (:require
    [clj-artnet.impl.protocol.addressing :as proto.addressing]
    [clj-artnet.impl.protocol.diagnostics :as diagnostics]
    [clj-artnet.impl.protocol.discovery :as proto.discovery]
    [clj-artnet.impl.protocol.dmx :as proto.dmx]
    [clj-artnet.impl.protocol.dmx-helpers :as dmx-helpers]
    [clj-artnet.impl.protocol.firmware :as firmware]
    [clj-artnet.impl.protocol.input :as input-helpers]
    [clj-artnet.impl.protocol.lifecycle :as lifecycle]
    [clj-artnet.impl.protocol.node-state :as node-state]
    [clj-artnet.impl.protocol.poll :as poll-helpers]
    [clj-artnet.impl.protocol.programming :as programming]
    [clj-artnet.impl.protocol.rdm.discovery :as rdm.discovery]
    [clj-artnet.impl.protocol.rdm.transport :as rdm.transport]
    [clj-artnet.impl.protocol.state :as proto.state]
    [clj-artnet.impl.protocol.sync :as proto.sync]
    [clj-artnet.impl.protocol.timing :as timing]
    [clj-artnet.impl.protocol.triggers :as triggers])
  (:import
    (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(declare nano-time)

(defn result
  "Creates step result."
  ([state] {:state state, :effects []})
  ([state effects] {:state state, :effects (vec effects)}))

(defn add-effect [result effect] (update result :effects conj effect))

(defn tx-packet
  "Effect: send packet."
  ([op-kw data] {:effect :tx-packet, :op op-kw, :data data})
  ([op-kw data target]
   {:effect :tx-packet, :op op-kw, :data data, :target target}))

(defn tx-reply
  "Effect: send reply."
  [op-kw data sender]
  {:effect :tx-packet, :op op-kw, :data data, :target sender, :reply? true})

(defn callback
  "Effect: invoke callback."
  [key payload]
  {:effect :callback, :key key, :payload payload})

(defn log-msg
  "Effect: log message."
  [level message & {:as extra}]
  {:effect :log, :level level, :message message, :data extra})

(defn schedule
  "Effect: schedule event."
  [delay-ms event]
  {:effect :schedule, :delay-ms delay-ms, :event event})

(defn dmx-frame
  "Effect: output DMX."
  [port-address sequence data length]
  {:effect       :dmx-frame
   :port-address port-address
   :sequence     sequence
   :data         data
   :length       length})

(defn initial-state
  "Creates initial state."
  ([] (proto.state/initial-state))
  ([config] (proto.state/initial-state config)))

(defn initial-dmx-state
  ([] (proto.state/initial-dmx-state))
  ([config] (proto.state/initial-dmx-state config)))

(defmulti handle-packet
          "Handles packet by opcode."
          (fn [_state event] (get-in event [:packet :op])))

(defmethod handle-packet :default
  [state {:keys [packet sender], :as event}]
  (let [op (:op packet)
        timestamp (nano-time event)
        stat-key (when (keyword? op) (keyword (str "rx-" (name op))))
        state' (-> state
                   (proto.state/remember-peer sender timestamp)
                   (cond-> stat-key (proto.state/inc-stat stat-key)))]
    (result state' [(callback op {:packet packet, :sender sender})])))

(defn- keepalive-effects
  [state timestamp]
  (let [stale-ports (dmx-helpers/ports-needing-keepalive
                      state
                      timestamp
                      dmx-helpers/keepalive-default-interval-ns)]
    (mapv (fn [{:keys [port-address last-output]}]
            (let [{:keys [data length]} last-output
                  {:keys [net sub-net universe]}
                  (proto.addressing/split-port-address port-address)]
              {:effect     :tx-packet
               :op         :artdmx
               :data       {:net          net
                            :sub-uni      (bit-or (bit-shift-left sub-net 4) universe)
                            :port-address port-address
                            :length       length
                            :data         data
                            :sequence     0}
               :broadcast? true
               :keepalive? true}))
          stale-ports)))

(defn handle-tick
  "Handles timer tick."
  [state event]
  (let [timestamp (nano-time event)
        state1 (proto.sync/maybe-expire-art-sync state timestamp)
        {:keys [state effects]} (dmx-helpers/run-failsafe state1 timestamp)
        state2 state
        fs-effects effects
        ka-effects (keepalive-effects state2 timestamp)
        state3 (reduce (fn [s {:keys [data]}]
                         (assoc-in s
                                   [:dmx :merge :ports (:port-address data) :last-output
                                    :updated-at]
                                   timestamp))
                       state2
                       ka-effects)
        [state4 _] (diagnostics/refresh-state state3 timestamp)
        [rdm-state1 queue-evt]
        (if (:rdm state4)
          (rdm.discovery/run-background-queue (:rdm state4) timestamp)
          [(:rdm state4) nil])
        state5 (assoc state4 :rdm rdm-state1)
        queue-effect (when queue-evt
                       {:effect  :callback
                        :key     :rdm
                        :payload {:event            :background-queue
                                  :background-queue queue-evt}})
        [rdm-state2 disc-evt] (if rdm-state1
                                (rdm.discovery/run-discovery rdm-state1
                                                             timestamp)
                                [rdm-state1 nil])
        state6 (assoc state5 :rdm rdm-state2)
        disc-effect (when disc-evt
                      {:effect  :callback
                       :key     :rdm
                       :payload {:event :discovery, :discovery disc-evt}})
        all-effects (-> fs-effects
                        (into ka-effects)
                        (cond-> queue-effect (conj queue-effect))
                        (cond-> disc-effect (conj disc-effect)))]
    (result state6 all-effects)))

(defn handle-config
  [state event]
  (result (merge state (select-keys event [:node :network :callbacks]))))

(defmulti handle-command
          "Handles external command."
          (fn [_state event] (or (:command event) (:cmd event))))

(defmethod handle-command :send-poll-reply
  [state {:keys [target data]}]
  (result state
          [{:effect :tx-packet
            :op     :artpollreply
            :data   data
            :target target
            :reply? true}]))

(defmethod handle-command :snapshot
  [state {:keys [keys reply]}]
  (let [snapshot (if (seq keys) (select-keys state keys) state)]
    (result state [{:effect :callback, :fn reply, :payload snapshot}])))

(defmethod handle-command :send-dmx
  [state {:keys [target port-address data], :as command}]
  (when data
    (let [len (cond (bytes? data) (alength ^bytes data)
                    (instance? ByteBuffer data) (.remaining ^ByteBuffer data)
                    :else 0)]
      (when (> len 512)
        (throw (ex-info "DMX payload exceeds 512 bytes"
                        {:length len, :maximum 512})))))
  (let [address-parts (when port-address
                        (proto.addressing/split-port-address port-address))
        base-packet (select-keys command
                                 [:sequence :physical :net :sub-net :universe])
        packet (-> (merge base-packet address-parts)
                   (assoc :data data))]
    (result state
            [{:effect :tx-packet, :op :artdmx, :data packet, :target target}])))

(defmethod handle-command :send-rdm
  [state {:keys [target rdm-packet command-code], :as command}]
  (when-not target (throw (ex-info "send-rdm requires :target" {})))
  (when rdm-packet
    (let [command-class (rdm.transport/payload-command-class rdm-packet)]
      (when (and (some? command-class)
                 (not (rdm.transport/valid-command-class? command-class)))
        (throw (ex-info "Unsupported RDM command class"
                        {:command-class command-class
                         :valid-classes rdm.transport/valid-command-classes}))))
    (let [len (cond (instance? ByteBuffer rdm-packet) (.limit ^ByteBuffer
                                                              rdm-packet)
                    (bytes? rdm-packet) (alength ^bytes rdm-packet)
                    :else 0)]
      (when (< len 24)
        (throw (ex-info "RDM packet shorter than minimum"
                        {:length len, :minimum 24})))))
  (let [base-packet (select-keys command
                                 [:rdm-version :fifo-available :fifo-max :net
                                  :address])
        normalized-packet (when rdm-packet
                            (rdm.transport/normalize-payload-buffer rdm-packet))
        packet (cond-> (assoc base-packet :command (or command-code 0))
                       normalized-packet (assoc :rdm-packet normalized-packet))
        state' (update-in state [:stats :tx-artrdm] (fnil inc 0))]
    (result state'
            [{:effect :tx-packet, :op :artrdm, :data packet, :target target}])))

(defmethod handle-command :diagnostic
  [state {:keys [target priority now], :as command}]
  (let [priority-code (diagnostics/priority-code priority)
        [state-pruned {:keys [targets effective-priority]}]
        (diagnostics/resolve-diagnostic-targets state priority-code)
        now-ns (or now (System/nanoTime))
        min-interval-ns (get-in state-pruned [:diagnostics :min-interval-ns])
        last-sent-at (get-in state-pruned [:diagnostics :last-sent-at])
        rate-limited? (and min-interval-ns
                           (pos? min-interval-ns)
                           last-sent-at
                           (< (- now-ns last-sent-at) min-interval-ns))]
    (if rate-limited?
      (result
        (update-in state-pruned [:stats :diagnostics-throttled] (fnil + 0) 1))
      (let [final-priority (or priority effective-priority)
            packet (-> (select-keys command [:logical-port :text])
                       (assoc :priority final-priority))
            final-targets (if target [target] targets)]
        (if (seq final-targets)
          (let [effects (mapv #(tx-packet :artdiagdata packet %) final-targets)
                state' (-> state-pruned
                           (assoc-in [:diagnostics :last-sent-at] now-ns)
                           (update-in [:stats :diagnostics-sent]
                                      (fnil + 0)
                                      (count final-targets)))]
            (result state' effects))
          (result state-pruned))))))

(defmethod handle-command :apply-state
  [current-state {:keys [state]}]
  (let [node-before (:node current-state)
        dmx-applied (dmx-helpers/apply-runtime-config current-state
                                                      (or state {}))
        updates-sans-dmx-config (dissoc (or state {}) :sync :failsafe)
        state'
        (reduce
          (fn [acc [k v]]
            (cond
              (and (= k :dmx) (or (contains? v :sync) (contains? v :failsafe)))
              (let [v-sans-config (dissoc v :sync :failsafe)]
                (if (seq v-sans-config) (update acc k merge v-sans-config) acc))
              (and (map? (get acc k)) (map? v)) (update acc k merge v)
              :else (assoc acc k v)))
          dmx-applied
          updates-sans-dmx-config)
        node-after (:node state')
        node-updates (get state :node)
        background-policy-update?
        (and node-updates (contains? node-updates :background-queue-policy))
        desired-policy
        (when background-policy-update?
          (bit-and (int (or (:background-queue-policy node-after) 0)) 0xFF))
        state''
        (if background-policy-update?
          (update state'
                  :rdm
                  #(rdm.discovery/set-background-queue-policy % desired-policy))
          state')
        network-changed? (contains? state :network)
        state'''
        (if network-changed? (lifecycle/refresh-node-state state'') state'')
        effects (if (not= node-before node-after)
                  (let [pages (node-state/node-port-pages node-after)]
                    (vec (for [page pages
                               target
                               (poll-helpers/reply-on-change-peers-for-page
                                 state'''
                                 page
                                 nil
                                 node-state/page-port-addresses)]
                           {:effect :tx-packet
                            :op     :artpollreply
                            :data   page
                            :target target})))
                  [])]
    (result state''' effects)))

(defmethod handle-command :default
  [state event]
  (result state
          [(log-msg :warn
                    (str "Unknown command: " (or (:cmd event) (:command event)))
                    {:event event})]))

(defn step
  "Transitions state based on event."
  [state event]
  (case (:type event)
    :rx-packet (handle-packet state event)
    :tick (handle-tick state event)
    :config (handle-config state event)
    :command (handle-command state event)
    :snapshot (handle-command state (assoc event :command :snapshot))
    (result state)))

(def ^:private ^:const good-output-merge-bit 0x08)

(defn- nano-time [event] (or (:timestamp event) (timing/*system-nano-time*)))

(defmethod handle-packet :artdmx
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        {:keys [sub-uni net]} packet
        port-address (or (:port-address packet)
                         (proto.addressing/compose-port-address
                           (bit-and (or net 0) 0x7F)
                           (bit-and (bit-shift-right (or sub-uni 0) 4) 0x0F)
                           (bit-and (or sub-uni 0) 0x0F)))
        port-index (bit-and port-address 0x0F)
        state' (-> state
                   (proto.state/inc-stat :rx-artdmx)
                   (proto.state/remember-peer sender timestamp))
        state'' (proto.sync/maybe-expire-art-sync state' timestamp)
        mode (proto.sync/current-sync-mode state'')]
    (if (= mode :art-sync)
      (let [state'''
            (proto.sync/stage-sync-frame state'' packet sender timestamp)]
        (result state''' []))
      (let [{merge-state   :state
             output-data   :output-data
             output-length :output-length
             merging?      :merging?}
            (proto.dmx/process-artdmx-merge state'' packet sender timestamp)
            good-output-a (vec (or (get-in merge-state [:node :good-output-a])
                                   [0 0 0 0]))
            current-byte (int (get good-output-a port-index 0))
            updated-byte (if merging?
                           (bit-or current-byte good-output-merge-bit)
                           (bit-and current-byte
                                    (bit-not good-output-merge-bit)))
            state-with-merge (if (not= current-byte updated-byte)
                               (assoc-in merge-state
                                         [:node :good-output-a port-index]
                                         updated-byte)
                               merge-state)
            was-in-failsafe? (get-in state-with-merge
                                     [:dmx :failsafe :playback port-address])
            state-cleared (if was-in-failsafe?
                            (update-in state-with-merge
                                       [:dmx :failsafe :playback]
                                       dissoc
                                       port-address)
                            state-with-merge)
            effects (cond-> [(callback :dmx
                                       {:packet       packet
                                        :sender       sender
                                        :port-address port-address
                                        :data         output-data
                                        :length       output-length})
                             (dmx-frame port-address
                                        (or (:sequence packet) 0)
                                        output-data
                                        output-length)]
                            was-in-failsafe? (conj (log-msg :info
                                                            "Failsafe cleared"
                                                            {:port-address
                                                             port-address
                                                             :reason :dmx-resumed})))]
        (result state-cleared effects)))))

(defmethod handle-packet :artsync
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-artsync)
                   (proto.state/remember-peer sender timestamp))]
    (cond
      (not (proto.sync/configured-art-sync? state'))
      (result state' [(callback :sync {:packet packet, :sender sender})])
      (proto.dmx/any-port-merging? state')
      (result
        state'
        [(callback
           :sync
           {:packet packet, :sender sender, :ignored? true, :reason :merging})
         (log-msg :debug "ArtSync ignored - merging active" {:sender sender})])
      (not (proto.dmx/sync-sender-matches? state' sender))
      (result
        state'
        [(callback :sync
                   {:packet   packet
                    :sender   sender
                    :ignored? true
                    :reason   :sender-mismatch})
         (log-msg :debug "ArtSync ignored - sender mismatch" {:sender sender})])
      :else (let [{:keys [state frames-data]}
                  (proto.sync/release-sync-frames-impl state' timestamp)
                  sync-effects (mapcat (fn [{:keys [packet sender port-address
                                                    output-data output-length]}]
                                         [(callback :dmx
                                                    {:packet       packet
                                                     :sender       sender
                                                     :port-address port-address
                                                     :data         output-data
                                                     :length       output-length
                                                     :synced?      true})
                                          (dmx-frame port-address
                                                     (or (:sequence packet) 0)
                                                     output-data
                                                     output-length)])
                                       frames-data)]
              {:state   state
               :effects (into [(callback :sync
                                         {:packet packet, :sender sender})]
                              sync-effects)}))))

(def ^:private ^:const nanos-per-second 1000000000)

(defn- artnzs-interval-ns
  ^Long [state]
  (let [refresh-rate (get-in state [:node :refresh-rate] 0)]
    (when (pos? refresh-rate)
      (long (/ (double nanos-per-second) (double refresh-rate))))))

(defn- apply-artnzs-throughput
  [state port-address now]
  (if-let [interval (artnzs-interval-ns state)]
    (let [last-time (get-in state [:dmx :throughput :artnzs port-address])]
      (if (and last-time (< (- now last-time) interval))
        [(update-in state [:stats :rx-artnzs-throttled] (fnil inc 0)) false]
        [(assoc-in state [:dmx :throughput :artnzs port-address] now) true]))
    [state true]))

(defmethod handle-packet :artnzs
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        {:keys [start-code sub-uni net length data]} packet
        port-address (bit-or (bit-shift-left (bit-and (or net 0) 0x7F) 8)
                             (bit-and (or sub-uni 0) 0xFF))
        state' (-> state
                   (proto.state/inc-stat :rx-artnzs)
                   (proto.state/remember-peer sender timestamp)
                   (proto.state/remember-peer sender timestamp))
        [state'' allowed?]
        (apply-artnzs-throughput state' port-address timestamp)]
    (if allowed?
      (result state''
              [(callback :dmx
                         {:packet       packet
                          :sender       sender
                          :port-address port-address
                          :start-code   start-code
                          :data         data
                          :length       length})])
      (result state''))))

(defmethod handle-packet :artvlc
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        {:keys [start-code sub-uni net length data]} packet
        port-address (bit-or (bit-shift-left (bit-and (or net 0) 0x7F) 8)
                             (bit-and (or sub-uni 0) 0xFF))
        state' (-> state
                   (proto.state/inc-stat :rx-artvlc)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :dmx
                       {:packet       packet
                        :sender       sender
                        :port-address port-address
                        :start-code   start-code
                        :data         data
                        :length       length})])))

(defmethod handle-packet :arttimecode
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        {:keys [frames seconds minutes hours type]} packet
        state' (-> state
                   (proto.state/inc-stat :rx-arttimecode)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :arttimecode
                       {:packet packet
                        :sender sender
                        :time   {:frames  frames
                                 :seconds seconds
                                 :minutes minutes
                                 :hours   hours
                                 :type    type}})])))

(defmethod handle-packet :artdiagdata
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-artdiagdata)
                   (proto.state/remember-peer sender timestamp))]
    (result state' [(callback :artdiagdata {:packet packet, :sender sender})])))

(defmethod handle-packet :artdatareply
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-artdatareply)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :artdatareply {:packet packet, :sender sender})])))

(defmethod handle-packet :artpollreply
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        {:keys [short-name long-name net-switch sub-switch oem esta-man
                bind-index sw-in sw-out port-types]}
        packet
        peer-key* (proto.state/peer-key sender)
        state' (-> state
                   (proto.state/inc-stat :rx-artpollreply)
                   (proto.state/remember-peer sender timestamp)
                   (update-in [:peers peer-key*]
                              merge
                              {:short-name short-name
                               :long-name  long-name
                               :oem        oem
                               :esta-man   esta-man
                               :bind-index (or bind-index 1)
                               :net-switch net-switch
                               :sub-switch sub-switch
                               :sw-in      sw-in
                               :sw-out     sw-out
                               :port-types port-types}))]
    (result state'
            [(callback :artpollreply {:packet packet, :sender sender})])))

(defmethod handle-packet :arttoddata
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        {:keys [net address uid-total uid-count]} packet
        port-address (bit-or (bit-shift-left (bit-and net 0x7F) 8)
                             (bit-and address 0xFF))
        state' (-> state
                   (proto.state/inc-stat :rx-arttoddata)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :arttoddata
                       {:packet       packet
                        :sender       sender
                        :port-address port-address
                        :uid-total    uid-total
                        :uid-count    uid-count})])))

(defmethod handle-packet :artipprogreply
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        {:keys [prog-ip prog-sm prog-gateway status]} packet
        state' (-> state
                   (proto.state/inc-stat :rx-artipprogreply)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :artipprogreply
                       {:packet        packet
                        :sender        sender
                        :ip            prog-ip
                        :subnet-mask   prog-sm
                        :gateway       prog-gateway
                        :dhcp-enabled? (bit-test (or status 0) 6)})])))

(defmethod handle-packet :artpoll
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        flags (or (:flags packet) (:talk-to-me packet) 0)
        targeted-mode? (if (contains? packet :target-enabled?)
                         (:target-enabled? packet)
                         (bit-test flags 5))
        unicast-diag? (if (contains? packet :diag-unicast?)
                        (:diag-unicast? packet)
                        (bit-test flags 3))
        send-diag? (if (contains? packet :diag-request?)
                     (:diag-request? packet)
                     (bit-test flags 2))
        reply-on-change? (if (contains? packet :reply-on-change?)
                           (:reply-on-change? packet)
                           (bit-test flags 1))
        suppress-delay? (if (contains? packet :suppress-delay?)
                          (:suppress-delay? packet)
                          (bit-test flags 0))
        diag-priority (or (:diag-priority packet) 0)
        target-top
        (or (:target-top packet) (:target-port-address-top packet) 0x7FFF)
        target-bottom
        (or (:target-bottom packet) (:target-port-address-bottom packet) 0)
        node (:node state)
        all-pages (node-state/node-port-pages node)
        matched-pages (proto.discovery/filter-pages-by-target all-pages
                                                              targeted-mode?
                                                              target-bottom
                                                              target-top)
        peer-key* (proto.state/peer-key sender)
        state'
        (-> state
            (proto.state/inc-stat :rx-artpoll)
            (proto.state/remember-peer sender timestamp)
            (assoc-in [:peers peer-key* :reply-on-change?] reply-on-change?)
            (cond-> reply-on-change? (assoc-in [:peers peer-key*
                                                :reply-on-change-granted-at]
                                               timestamp))
            (assoc-in [:peers peer-key* :target-enabled?] targeted-mode?)
            (assoc-in [:peers peer-key* :target-top] target-top)
            (assoc-in [:peers peer-key* :target-bottom] target-bottom)
            (assoc-in [:peers peer-key* :suppress-delay?] suppress-delay?)
            (cond-> send-diag? (assoc-in [:peers peer-key* :diag-subscriber?]
                                         true))
            (cond-> send-diag? (assoc-in [:peers peer-key* :diag-priority]
                                         diag-priority))
            (cond-> send-diag? (assoc-in [:peers peer-key* :diag-unicast?]
                                         unicast-diag?))
            (cond-> send-diag? (assoc-in [:diagnostics :subscribers peer-key*]
                                         {:host       (:host sender)
                                          :port       (or (:port sender) 0x1936)
                                          :priority   diag-priority
                                          :unicast?   unicast-diag?
                                          :updated-at timestamp})))
        state'' (poll-helpers/enforce-reply-on-change-limit state')
        random-delay-fn (:random-delay-fn state)
        delay-ms (if suppress-delay?
                   0
                   (if random-delay-fn
                     (-> (long (random-delay-fn))
                         (max 0)
                         (min 1000))
                     (rand-int 1000)))]
    (if (seq matched-pages)
      (if (zero? delay-ms)
        (result state''
                (mapv #(tx-reply :artpollreply
                                 (proto.discovery/page-reply-data %)
                                 sender)
                      matched-pages))
        (result state''
                (mapv #(schedule delay-ms
                                 {:type   :command
                                  :cmd    :send-poll-reply
                                  :target sender
                                  :data   (proto.discovery/page-reply-data %)})
                      matched-pages)))
      (result state''))))

(defn- datarequest-node-identifiers
  [state]
  {:esta (bit-and (int (get-in state [:node :esta-man] 0)) 0xFFFF)
   :oem  (bit-and (int (get-in state [:node :oem] 0xFFFF)) 0xFFFF)})

(defn- datarequest-targets-node?
  [state packet]
  (let [{:keys [esta oem]} (datarequest-node-identifiers state)
        request-esta (bit-and (int (or (:esta-man packet) 0)) 0xFFFF)
        request-oem (bit-and (int (or (:oem packet) 0)) 0xFFFF)]
    (and (= request-esta esta) (= request-oem oem))))

(defn- normalize-data-response
  [response]
  (cond (nil? response) nil
        (string? response) {:text response}
        (bytes? response) {:data response}
        (instance? ByteBuffer response) {:data response}
        (map? response) response
        :else nil))

(defn- lookup-data-response
  [state packet]
  (let [request-code (or (:request packet) 0)
        request-type (:request-type packet)
        responses (get-in state [:data :responses] {})
        normalized? (or (contains? responses :by-type)
                        (contains? responses :by-code))
        response (if normalized?
                   (or (get-in responses [:by-type request-type])
                       (get-in responses [:by-code request-code]))
                   (or (get responses request-type)
                       (get responses request-code)))]
    (when response
      {:code request-code, :response (normalize-data-response response)})))

(defmethod handle-packet :artdatarequest
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)]
    (if-not (datarequest-targets-node? state packet)
      (result state [])
      (let [state' (-> state
                       (proto.state/inc-stat :rx-artdatarequest)
                       (proto.state/remember-peer sender timestamp))]
        (if-let [{:keys [code response]} (lookup-data-response state' packet)]
          (let [{:keys [esta oem]} (datarequest-node-identifiers state')
                reply-data
                (merge
                  {:op :artdatareply, :request code, :esta-man esta, :oem oem}
                  response)]
            (result (proto.state/inc-stat state' :data-requests)
                    [(tx-reply :artdatareply reply-data sender)
                     (callback
                       :data-request
                       {:packet packet, :sender sender, :replied? true})]))
          (result state'
                  [(callback
                     :data-request
                     {:packet packet, :sender sender, :replied? false})]))))))

(defmethod handle-packet :artfirmwarereply
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-artfirmwarereply)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :artfirmwarereply {:packet packet, :sender sender})])))

(defmethod handle-packet :artrdm
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        payload (:payload packet)
        command-class (rdm.transport/payload-command-class payload)
        valid-class? (or (nil? command-class)
                         (rdm.transport/valid-command-class? command-class))]
    (if-not valid-class?
      (result (-> state
                  (proto.state/inc-stat :rdm-invalid-command-class)
                  (proto.state/remember-peer sender timestamp))
              [])
      (let [state' (-> state
                       (proto.state/inc-stat :rx-artrdm)
                       (proto.state/remember-peer sender timestamp))]
        (result state' [(callback :rdm {:packet packet, :sender sender})])))))

(defmethod handle-packet :arttodrequest
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-arttodrequest)
                   (proto.state/inc-stat :tod-requests)
                   (proto.state/remember-peer sender timestamp))
        rdm-state (:rdm state')
        [rdm-state' actions]
        (rdm.discovery/handle-tod-request rdm-state packet sender)
        state'' (assoc state' :rdm rdm-state')
        reply-effects (mapv #(tx-reply :arttoddata (:packet %) (:target %))
                            actions)
        effects (into [(callback :tod-request {:packet packet, :sender sender})]
                      reply-effects)]
    (result state'' effects)))

(defmethod handle-packet :arttodcontrol
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-arttodcontrol)
                   (proto.state/inc-stat :tod-controls)
                   (proto.state/remember-peer sender timestamp))
        rdm-state (:rdm state')
        [rdm-state' actions]
        (rdm.discovery/handle-tod-control rdm-state packet sender)
        state'' (assoc state' :rdm rdm-state')
        effects (into [(callback :tod-control {:packet packet, :sender sender})]
                      (mapv #(tx-reply :arttoddata (:packet %) (:target %))
                            actions))]
    (result state'' effects)))

(defmethod handle-packet :artrdmsub
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)]
    (if-not (rdm.transport/valid-rdmsub-packet? packet)
      (result (-> state
                  (proto.state/inc-stat :rdm-sub-invalid)
                  (proto.state/remember-peer sender timestamp))
              [])
      (let [state' (-> state
                       (proto.state/inc-stat :rdm-sub-commands)
                       (proto.state/remember-peer sender timestamp))
            sub-range (rdm.transport/sub-device-range packet)
            entries (rdm.transport/sub-device-entries packet)
            command (:command packet)
            phase (if (contains? #{:get-response :set-response} command)
                    :response
                    :request)
            payload {:packet           packet
                     :sender           sender
                     :sub-device-range sub-range
                     :entries          entries
                     :proxy            {:type :rdm-sub, :phase phase}}]
        (result state'
                [(callback :rdm-sub payload) (callback :rdm payload)])))))

(defmethod handle-packet :arttrigger
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state-with-peer (proto.state/remember-peer state sender timestamp)
        accept? (triggers/target? state-with-peer packet)
        peer-key* [(some-> (:host sender)
                           str) (int (or (:port sender) 6454))]
        diag-subscriber? (true? (get-in state-with-peer
                                        [:peers peer-key* :diag-subscriber?]))]
    (if-not accept?
      (result state-with-peer)
      (let [state' (proto.state/inc-stat state-with-peer :trigger-requests)
            info (triggers/interpret-info packet)
            [state'' allowed?] (triggers/allow? state' info timestamp)
            min-interval-ms
            (quot (or (get-in state'' [:triggers :min-interval-ns]) 100000000)
                  1000000)]
        (if-not allowed?
          (let [state-throttled (proto.state/inc-stat state''
                                                      :trigger-throttled)
                key-name (case (:kind info)
                           :ascii "KeyAscii"
                           :macro "KeyMacro"
                           :soft "KeySoft"
                           :show "KeyShow"
                           :vendor "Vendor"
                           "Unknown")
                debounce-text (format "Trigger %s %d ignored (debounced %dms)"
                                      key-name
                                      (or (:sub-key info) 0)
                                      min-interval-ms)
                debounce-diag (when diag-subscriber?
                                {:effect :tx-packet
                                 :op     :artdiagdata
                                 :data   {:text debounce-text, :priority 0x10}
                                 :target sender})]
            (result state-throttled (if debounce-diag [debounce-diag] [])))
          (let [helper-cb (triggers/helper-action state'' info packet sender)
                [state''' reply-act]
                (triggers/reply-action state'' packet sender)
                reply-effect (when reply-act
                               {:effect :tx-packet
                                :op     (get-in reply-act [:packet :op])
                                :data   (dissoc (:packet reply-act) :op)
                                :target (:target reply-act)})
                ack (:ack info)
                diag-effect (when (and ack diag-subscriber?)
                              {:effect :tx-packet
                               :op     :artdiagdata
                               :data   {:priority (:priority ack)
                                        :text     (:text ack)}
                               :target sender})
                cb-effect (callback
                            :trigger
                            {:packet packet, :sender sender, :info info})]
            (result state'''
                    (cond-> [cb-effect]
                            helper-cb (conj helper-cb)
                            reply-effect (conj reply-effect)
                            diag-effect (conj diag-effect)))))))))

(defmethod handle-packet :artcommand
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state-with-peer (proto.state/remember-peer state sender timestamp)
        accept? (triggers/command-target? state-with-peer packet)]
    (if-not accept?
      (result state-with-peer)
      (let [state' (proto.state/inc-stat state-with-peer :command-requests)
            {:keys [state directives acks changes]}
            (triggers/apply-artcommand-directives state' packet)
            peer-key* [(some-> (:host sender)
                               str) (int (or (:port sender) 6454))]
            diag-subscriber?
            (true? (get-in state [:peers peer-key* :diag-subscriber?]))
            acks-to-send (if diag-subscriber? (filter #(seq (:text %)) acks) [])
            state'' (if (seq acks-to-send)
                      (update-in state
                                 [:stats :diagnostics-sent]
                                 (fnil + 0)
                                 (count acks-to-send))
                      state)
            diag-effects (mapv #(tx-reply
                                  :artdiagdata
                                  {:op   :artdiagdata
                                   :priority
                                   (bit-and (int (or (:priority %) 0x10)) 0xFF)
                                   :logical-port
                                   (bit-and (int (or (:logical-port %) 0)) 0xFF)
                                   :text (or (:text %) "")}
                                  sender)
                               acks-to-send)
            cb-effect (callback :command
                                {:packet           packet
                                 :sender           sender
                                 :directives       directives
                                 :changes          changes
                                 :command-labels   (:command-labels state'')
                                 :acknowledgements acks})
            prog-effect (when (seq (:command-labels changes))
                          {:effect  :callback
                           :key     :programming
                           :payload {:event      :artcommand
                                     :changes    changes
                                     :directives directives}})]
        (result state''
                (cond-> (into diag-effects [cb-effect])
                        prog-effect (conj prog-effect)))))))

(defmethod handle-packet :artvideosetup
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-artvideosetup)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :artvideosetup {:packet packet, :sender sender})])))

(defmethod handle-packet :artvideopalette
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-artvideopalette)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :artvideopalette {:packet packet, :sender sender})])))

(defmethod handle-packet :artvideodata
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-artvideodata)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :artvideodata {:packet packet, :sender sender})])))

(defmethod handle-packet :artmacmaster
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-artmacmaster)
                   (proto.state/remember-peer sender timestamp))]
    (result
      state'
      [(callback :artmacmaster
                 {:packet packet, :sender sender, :deprecated? true})
       (log-msg :warn "Received deprecated ArtMacMaster" {:sender sender})])))

(defmethod handle-packet :artmacslave
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-artmacslave)
                   (proto.state/remember-peer sender timestamp))]
    (result
      state'
      [(callback :artmacslave
                 {:packet packet, :sender sender, :deprecated? true})
       (log-msg :warn "Received deprecated ArtMacSlave" {:sender sender})])))

(defmethod handle-packet :artfiletnmaster
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        {:keys [type block-id name]} packet
        state' (-> state
                   (proto.state/inc-stat :rx-artfiletnmaster)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :artfiletnmaster
                       {:packet   packet
                        :sender   sender
                        :type     type
                        :block-id block-id
                        :filename name})])))

(defmethod handle-packet :artfilefnmaster
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        {:keys [type name]} packet
        state' (-> state
                   (proto.state/inc-stat :rx-artfilefnmaster)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback
               :artfilefnmaster
               {:packet packet, :sender sender, :type type, :filename name})])))

(defmethod handle-packet :artfilefnreply
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        {:keys [type]} packet
        state' (-> state
                   (proto.state/inc-stat :rx-artfilefnreply)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :artfilefnreply
                       {:packet packet, :sender sender, :status type})])))

(defmethod handle-packet :artmedia
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-artmedia)
                   (proto.state/remember-peer sender timestamp))]
    (result state' [(callback :artmedia {:packet packet, :sender sender})])))

(defmethod handle-packet :artmediapatch
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-artmediapatch)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :artmediapatch {:packet packet, :sender sender})])))

(defmethod handle-packet :artmediacontrol
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-artmediacontrol)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :artmediacontrol {:packet packet, :sender sender})])))

(defmethod handle-packet :artmediacontrolreply
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-artmediacontrolreply)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :artmediacontrolreply
                       {:packet packet, :sender sender})])))

(defmethod handle-packet :arttimesync
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-arttimesync)
                   (proto.state/remember-peer sender timestamp))]
    (result state' [(callback :arttimesync {:packet packet, :sender sender})])))

(defmethod handle-packet :artdirectory
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        {:keys [command file-type]} packet
        state' (-> state
                   (proto.state/inc-stat :rx-artdirectory)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :artdirectory
                       {:packet    packet
                        :sender    sender
                        :command   command
                        :file-type file-type})])))

(defmethod handle-packet :artdirectoryreply
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        {:keys [flags file-type name description]} packet
        state' (-> state
                   (proto.state/inc-stat :rx-artdirectoryreply)
                   (proto.state/remember-peer sender timestamp))]
    (result state'
            [(callback :artdirectoryreply
                       {:packet      packet
                        :sender      sender
                        :last-entry? (bit-test (or flags 0) 0)
                        :file-type   file-type
                        :filename    name
                        :description description})])))

(defmethod handle-packet :artfirmwaremaster
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :firmware-requests)
                   (proto.state/remember-peer sender timestamp))
        fw-state (or (:firmware state')
                     {:sessions  {}
                      :callbacks (select-keys (:callbacks state')
                                              [:on-chunk :on-complete])})
        node (:node state')
        fw-result (firmware/handle-block fw-state packet sender node)
        next-fw-state (:state fw-result)
        actions (:actions fw-result)
        fw-effects (keep (fn [a]
                           (when (= (:type a) :send)
                             {:effect :tx-packet
                              :op     (get-in a [:packet :op])
                              :data   (dissoc (:packet a) :op)
                              :target (:target a)}))
                         actions)
        callback-effect (callback :firmware
                                  {:packet packet
                                   :sender sender
                                   :status (:status fw-result)
                                   :error  (:error fw-result)})
        all-effects (conj (vec fw-effects) callback-effect)]
    (result (assoc state' :firmware next-fw-state) all-effects)))

(defmethod handle-packet :artipprog
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :ip-program-requests)
                   (proto.state/remember-peer sender timestamp))
        current-node (:node state')
        current-network (:network state')
        network-defaults (:network-defaults state')
        {:keys [node network changes reply]}
        (programming/apply-artipprog {:node             current-node
                                      :network          current-network
                                      :network-defaults network-defaults
                                      :packet           packet})
        state'' (cond-> state'
                        (seq changes) (assoc :node node :network network))
        reply-effect (tx-reply :artipprogreply reply sender)
        callback-effect (when (seq changes)
                          (callback :ipprog
                                    {:event   :artipprog
                                     :packet  packet
                                     :sender  sender
                                     :changes changes
                                     :reply   reply}))
        effects (filterv some? [reply-effect callback-effect])]
    (result state'' effects)))

(defmethod handle-packet :artinput
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :rx-artinput)
                   (proto.state/remember-peer sender timestamp))
        node (:node state')
        our-bind (or (:bind-index node) 1)
        raw-packet-bind (:bind-index packet)
        packet-bind (or raw-packet-bind 1)
        multi-page? (or (seq (:port-pages node)) (seq (:ports node)))
        bind-matches?
        (or multi-page? (nil? raw-packet-bind) (= our-bind packet-bind))]
    (if-not bind-matches?
      (result state')
      (let [pages (node-state/node-port-pages node)
            target-bind
            (if (or (nil? raw-packet-bind) (zero? (int (or raw-packet-bind 0))))
              (let [packet-num-ports (int (or (:num-ports packet) 4))]
                (or (some (fn [page]
                            (when (= (:num-ports page) packet-num-ports)
                              (:bind-index page)))
                          pages)
                    packet-bind))
              packet-bind)
            {:keys [node changes disabled applied-bind-index applied-to-base?]}
            (programming/apply-artinput
              {:node node, :packet packet, :target-bind-index target-bind})
            applied (or applied-bind-index target-bind)
            previous-good-input
            (input-helpers/page-good-input state' applied nil)
            next-good-input (input-helpers/apply-good-input-disables
                              previous-good-input
                              disabled)
            state'' (-> state'
                        (assoc-in [:inputs :last-bind-index] applied)
                        (assoc-in [:inputs :per-page applied]
                                  {:disabled   disabled
                                   :good-input next-good-input})
                        (cond-> applied-to-base?
                                (-> (assoc :node node)
                                    (assoc-in [:inputs :disabled] disabled))))
            input-flags (or (:input packet) [])
            any-disable-requested? (some #(pos? (bit-and (int (or % 0)) 1))
                                         input-flags)
            state''' (if any-disable-requested?
                       (assoc-in state'' [:dmx :sync-buffer] {})
                       state'')
            updated-node (:node state''')
            reply-data
            (-> (select-keys updated-node
                             [:ip :port :short-name :long-name :net-switch
                              :sub-switch :esta-man :oem :status1 :status2
                              :status3 :sw-in :sw-out :port-types :good-output-a
                              :good-output-b :mac :bind-ip :acn-priority])
                (assoc :bind-index applied :good-input next-good-input))
            reply-effect (tx-reply :artpollreply reply-data sender)
            callback-effect (callback :input
                                      {:packet   packet
                                       :sender   sender
                                       :changes  changes
                                       :disabled disabled})
            roc-effects (when (and applied-to-base? (seq changes))
                          (poll-helpers/reply-on-change-effects
                            state'''
                            reply-data
                            (poll-helpers/peer-key sender)))
            all-effects (into [reply-effect callback-effect]
                              (or roc-effects []))]
        (result state''' all-effects)))))

(defmethod handle-packet :artaddress
  [state {:keys [packet sender], :as event}]
  (let [timestamp (nano-time event)
        state' (-> state
                   (proto.state/inc-stat :address-requests)
                   (proto.state/remember-peer sender timestamp))
        node (:node state')
        defaults (or (:node-defaults state') node)
        {:keys [node changes command-info]}
        (programming/apply-artaddress node defaults packet)
        our-bind (or (:bind-index node) 1)
        raw-packet-bind (:bind-index packet)
        packet-bind (or raw-packet-bind 1)
        matches-bind? (or (nil? raw-packet-bind)
                          (zero? (int (or raw-packet-bind 0)))
                          (= our-bind packet-bind))
        state''
        (if (and matches-bind? changes) (assoc state' :node node) state')
        failsafe-directive (:failsafe-directive command-info)
        merge-directive (:merge-directive command-info)
        flush-subscribers? (:flush-subscribers? command-info)
        state''' (if (and matches-bind? (= failsafe-directive :record))
                   (dmx-helpers/record-failsafe-scene state'' timestamp)
                   state'')
        state'''' (if (and matches-bind? (:mode merge-directive))
                    (assoc-in state'''
                              [:dmx :merge :per-port (or (:port merge-directive) 0)
                               :mode]
                              (:mode merge-directive))
                    state''')
        state''''' (if (and matches-bind? flush-subscribers?)
                     (assoc-in state'''' [:dmx :sync-buffer] {})
                     state'''')
        state'''''' (if (and matches-bind? (= :cancel (:type merge-directive)))
                      (assoc-in state''''' [:dmx :merge :cancel-armed?] true)
                      state''''')
        rdm-directive (:rdm-directive command-info)
        state6a (if (and matches-bind?
                         (= (:type rdm-directive) :set-background-queue-policy))
                  (update state''''''
                          :rdm
                          #(rdm.discovery/set-background-queue-policy
                             %
                             (:policy rdm-directive)))
                  state'''''')
        acknowledgements (programming/artaddress-acknowledgements command-info
                                                                  packet)
        [state''''''' diag-actions]
        (diagnostics/ack-actions state6a sender acknowledgements)
        diag-effects (mapv #(tx-reply :artdiagdata (:packet %) (:target %))
                           diag-actions)
        updated-node (:node state''''''')
        reply-data (select-keys updated-node
                                [:ip :port :short-name :long-name :net-switch
                                 :sub-switch :esta-man :oem :status1 :status2
                                 :status3 :sw-in :sw-out :port-types :good-input
                                 :good-output-a :good-output-b :mac :bind-ip
                                 :bind-index :acn-priority])
        reply-effect (tx-reply :artpollreply reply-data sender)
        callback-effect (callback :address
                                  {:event        :artaddress
                                   :packet       packet
                                   :sender       sender
                                   :changes      changes
                                   :command-info command-info})
        roc-effects (when (and matches-bind? (seq changes))
                      (let [pages (node-state/node-port-pages updated-node)
                            exclude-key (poll-helpers/peer-key sender)]
                        (vec (for [page pages
                                   target
                                   (poll-helpers/reply-on-change-peers-for-page
                                     state'''''''
                                     page
                                     exclude-key
                                     node-state/page-port-addresses)]
                               {:effect :tx-packet
                                :op     :artpollreply
                                :data   page
                                :target target}))))
        log-effects (when (and matches-bind? (= failsafe-directive :record))
                      [(log-msg :info
                                "Failsafe scene recorded"
                                {:port-count (count (get-in state'''''''
                                                            [:dmx :failsafe
                                                             :scene]))})])
        all-effects (-> diag-effects
                        (conj reply-effect)
                        (conj callback-effect)
                        (into (or roc-effects []))
                        (into (or log-effects [])))]
    (result state''''''' all-effects)))

(comment
  (require '[clj-artnet.impl.protocol.machine :as machine] :reload)
  (def test-state (machine/initial-state {:node {:short-name "Test"}}))
  (machine/step test-state {:type :tick, :timestamp (System/nanoTime)})
  (machine/step test-state
                {:type   :rx-packet
                 :packet {:op :unknown-op}
                 :sender {:host "192.168.1.100", :port 6454}})
  :rcf)
