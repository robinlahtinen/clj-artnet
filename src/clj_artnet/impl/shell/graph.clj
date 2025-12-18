;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.shell.graph
  "Flow graph construction for the Art-Net node runtime.

   Assembles the core.async.flow graph connecting:
   - udp-recv: Receives UDP packets
   - failsafe: Timer ticks for failsafe DMX output
   - logic:    Pure protocol state machine
   - udp-send: Sends UDP packets

   Part of the Imperative Shell: Orchestrates the reactive pipeline."
  (:require
    [clj-artnet.impl.protocol.diagnostics :as diagnostics]
    [clj-artnet.impl.protocol.dmx-helpers :as dmx]
    [clj-artnet.impl.protocol.lifecycle :as lifecycle]
    [clj-artnet.impl.protocol.machine :as machine]
    [clj-artnet.impl.protocol.node-state :as logic-state]
    [clj-artnet.impl.shell.buffers :as buffers]
    [clj-artnet.impl.shell.effects :as effects]
    [clj-artnet.impl.shell.net :as net]
    [clj-artnet.impl.shell.receiver :as receiver]
    [clj-artnet.impl.shell.sender :as sender]
    [clojure.core.async :as async]
    [clojure.core.async.flow :as flow]
    [taoensso.trove :as trove])
  (:import
    (java.util.concurrent Semaphore)
    (java.util.concurrent.atomic AtomicBoolean)))

(set! *warn-on-reflection* true)

(defn- translate-event
  "Convert IO-layer event format to protocol/machine event format.

   IO formats:
   - {:type :rx :packet {...} :sender {...}}
   - {:type :tick :now <nanos>}

   Protocol formats:
   - {:type :rx-packet :packet {...} :sender {...}}
   - {:type :tick :timestamp <nanos>}"
  [msg]
  (case (:type msg)
    :rx (assoc msg :type :rx-packet)
    :tick (-> msg
              (assoc :timestamp (:now msg)))
    msg))

(defn- build-callbacks-map
  "Build a complete callbacks map from config and state.

   The callbacks can be registered in several ways:
   1. Direct: {:callbacks {:dmx fn :sync fn :rdm fn :trigger fn :command fn}}
   2. Programming: {:programming {:on-change fn}} -> maps to :ipprog, :address, and :programming
   3. Packets: {:callbacks {:packets {:arttimesync fn}}} -> for generic handlers (keyed by opcode)
   4. Default: {:callbacks {:default fn}} -> fallback for unknown ops

   Returns a merged map of callback-key -> fn."
  [config state]
  (let [base-callbacks (or (:callbacks state) (:callbacks config) {})
        programming-cb (or (get-in state [:programming :on-change])
                           (get-in config [:programming :on-change]))
        packets-cbs (get base-callbacks :packets {})
        merged-callbacks (-> (dissoc base-callbacks :packets)
                             (merge packets-cbs))]
    (cond-> merged-callbacks
            programming-cb (-> (assoc :ipprog programming-cb)
                               (assoc :address programming-cb)
                               (assoc :programming programming-cb)))))

(defn- finalize-actions
  "Append a :release action if the message has a release function.
   This handles buffer lifecycle management."
  [actions release]
  (if release (conj (vec actions) {:type :release, :release release}) actions))

(defn- io-step
  "Step function wrapping protocol/machine for IO layer use.

   Arguments:
   - state  : Current logic state (maybe nil initially)
   - config : Configuration map with :node, :callbacks, etc.
   - msg    : Event message in IO format {:type :rx/:tick ...}

   Returns: [state' actions] where actions are IO-layer actions

   This function:
   1. Initializes state if nil via lifecycle/ensure-state
   2. Handles :snapshot messages directly
   3. Translates IO events to protocol events
   4. Calls protocol/machine/step
   5. Translates effects back to IO actions"
  [state config msg]
  ;; Initialize state if nil
  (let [state (lifecycle/ensure-state state config)]
    ;; Handle snapshot requests directly (not passed to machine/step)
    (case (:type msg)
      :snapshot (let [{:keys [keys reply]} msg
                      requested-keys (or (seq keys) [:diagnostics])
                      payload (lifecycle/snapshot state requested-keys)]
                  (when (fn? reply) (reply payload))
                  [state []])
      ;; All other messages go through machine/step
      (let [state-with-callbacks (if (and (not (:callbacks state))
                                          (:callbacks config))
                                   (assoc state :callbacks (:callbacks config))
                                   state)
            ;; Translate event format
            event (translate-event msg)
            ;; Call protocol/machine/step
            result (machine/step state-with-callbacks event)
            ;; Extract state and effects
            new-state (:state result)
            effects (:effects result)
            ;; Build complete callbacks map from config and state
            callbacks (build-callbacks-map config new-state)
            node (or (:node new-state) (:node state))
            actions (effects/translate-effects callbacks node effects)
            final-actions (finalize-actions actions (:release msg))]
        [new-state final-actions]))))

(defn- logic-proc
  "Create the logic process for the flow graph.

   Handles:
   - Configuration normalization on init
   - State management
   - Packet processing via machine/step"
  []
  (flow/process
    (fn
      ([]
       {:ins
        {:rx "UDP frames", :commands "User commands", :ticks "Failsafe ticks"}
        :outs     {:actions "Actions bound for IO"}
        :params   {:config "Logic configuration"}
        :workload :mixed})
      ([{:keys [config]}]
       (let [node (or (logic-state/normalize-node (:node config))
                      {:short-name "FORCED"})
             programming-network (get-in config [:programming :network])
             network-overrides (merge programming-network (:network config))
             network (logic-state/default-network-state node network-overrides)
             capabilities (logic-state/normalize-capabilities-config
                            (:capabilities config))
             normalized-config (assoc config
                                 :node node
                                 :network network
                                 :capabilities capabilities)
             base-state {:node         node
                         :network      network
                         :diagnostics  (:diagnostics config)
                         :programming  (:programming config)
                         :capabilities capabilities
                         :peers        {}
                         :stats        {}
                         :callbacks    (:callbacks config)
                         :dmx          (dmx/initial-state {:sync-config     (:sync config)
                                                           :failsafe-config (:failsafe
                                                                              config)})
                         :rdm          {:discovery {}, :transport {}}}
             [state _] (diagnostics/refresh-state base-state (System/nanoTime))
             _ (when-not (contains? (:node config) :esta-man)
                 (trove/log!
                   {:level :warn
                    :id    ::esta-prototype-id
                    :msg   (str "Using default ESTA prototype manufacturer ID (0x7FF0). "
                                "Reserved for testing only. "
                                "Production use requires a registered ID.")}))]
         {:logic-state state, :config normalized-config, :step-fn io-step}))
      ([state _transition] state)
      ([{:keys [logic-state config step-fn], :as proc-state} _ msg]
       (if (nil? msg)
         [proc-state {}]
         (let [[next actions] (step-fn logic-state config msg)
               outputs (when (seq actions) {:actions actions})]
           [(assoc proc-state :logic-state next) outputs]))))))

(defn- failsafe-timer-loop
  "Background loop for failsafe timer ticks.
   Uses the provided Semaphore gate for instant resume and zero-CPU idle."
  [{:keys [^AtomicBoolean running? ^AtomicBoolean paused? ^Semaphore gate out
           interval-ms]}]
  (try (while (.get running?)
         (Thread/sleep (long (max 1 interval-ms)))
         (when (.get running?)
           (if (.get paused?)
             (.acquire gate)
             (when-not (async/>!! out {:type :tick, :now (System/nanoTime)})
               (.set running? false)))))
       (catch InterruptedException _ (Thread/interrupted))
       (catch Throwable t (throw t))
       (finally (async/close! out))))

(defn- failsafe-timer-proc
  "Create the failsafe timer process for the flow graph. Args:
   * `:interval-ms` -> Tick interval in milliseconds (default 100)

   Emits periodic tick events for failsafe DMX handling."
  []
  (flow/process
    (fn
      ([]
       {:ins      {:pulse "Internal pulse channel"}
        :outs     {:ticks "Failsafe tick events"}
        :params   {:interval-ms "Tick interval in milliseconds"}
        :workload :cpu})
      ([{:keys [interval-ms], :or {interval-ms 100}}]
       (let [interval (long (max 1 interval-ms))
             running? (AtomicBoolean. true)
             paused? (AtomicBoolean. false)
             gate (Semaphore. 0)
             out (async/chan (async/sliding-buffer 1))
             thread (async/io-thread (failsafe-timer-loop {:running?    running?
                                                           :paused?     paused?
                                                           :gate        gate
                                                           :out         out
                                                           :interval-ms interval}))]
         {:running?       running?
          :paused?        paused?
          :gate           gate
          :out            out
          :interval-ms    interval
          :thread         thread
          ::flow/in-ports {:pulse     out
                           :lifecycle thread}}))
      ([state transition]
       (case transition
         ::flow/stop (let [^AtomicBoolean running? (:running? state)]
                       (when running? (.set running? false))
                       (.release ^Semaphore (:gate state)))
         ::flow/pause (when-let [^AtomicBoolean p (:paused? state)]
                        (.set p true))
         ::flow/resume (do (when-let [^AtomicBoolean p (:paused? state)]
                             (.set p false))
                           (.release ^Semaphore (:gate state)))
         nil)
       state)
      ([state in msg]
       (cond (= in :pulse) [state {:ticks [msg]}]
             (and (= in :lifecycle) (instance? Throwable msg)) (throw msg)
             :else [state {}])))))

(defn create-graph
  "Create the flow graph for Art-Net node processing.

   Arguments:
   - channel: DatagramChannel for network IO
   - rx-pool: Buffer pool for receiving
   - tx-pool: Buffer pool for sending
   - logic-config: Logic layer configuration
   - max-packet: Maximum inbound packet size
   - recv-buffer: Size of receiver→logic channel
   - command-buffer: Size of a command injection channel
   - actions-buffer: Size of logic→sender channel
   - default-target: Default send target
   - allow-limited-broadcast?: Permit 255.255.255.255?"
  [{:keys [channel rx-pool tx-pool logic-config max-packet recv-buffer
           command-buffer actions-buffer default-target
           allow-limited-broadcast?]}]
  (let [failsafe-conf (dmx/normalize-failsafe-config (:failsafe logic-config))
        tick-ms (long (max 1
                           (^[double] Math/round
                             (/ (:tick-interval-ns failsafe-conf) 1000000.0))))]
    (flow/create-flow
      {:procs
       {:udp-recv {:proc (receiver/receiver-proc)
                   :args {:channel    channel
                          :pool       rx-pool
                          :out-buffer recv-buffer
                          :max-packet max-packet}}
        :failsafe {:proc (failsafe-timer-proc), :args {:interval-ms tick-ms}}
        :logic    {:proc (logic-proc), :args {:config logic-config}}
        :udp-send {:proc (sender/sender-proc)
                   :args {:channel                  channel
                          :pool                     tx-pool
                          :default-target           default-target
                          :allow-limited-broadcast? allow-limited-broadcast?}}}
       :chan-opts {[:logic :rx]       {:buf-or-n recv-buffer}
                   [:logic :commands] {:buf-or-n command-buffer}
                   [:logic :actions]  {:buf-or-n actions-buffer}
                   [:logic :ticks]    {:buf-or-n 16}}
       :conns     [[[:udp-recv :rx] [:logic :rx]]
                   [[:failsafe :ticks] [:logic :ticks]]
                   [[:logic :actions] [:udp-send :actions]]]})))

(comment
  (require '[clj-artnet.impl.shell.graph :as graph] :reload)
  ;; Setup dependencies
  (def ch (net/open-channel {:bind {:host "0.0.0.0", :port 6454}}))
  (def rx (buffers/create-pool {:count 8}))
  (def tx (buffers/create-pool {:count 8}))
  ;; Create graph
  (def g
    (graph/create-graph {:channel                  ch
                         :rx-pool                  rx
                         :tx-pool                  tx
                         :logic-config             {:node {:short-name "MyNode"}}
                         :max-packet               2048
                         :recv-buffer              16
                         :command-buffer           16
                         :actions-buffer           16
                         :default-target           nil
                         :allow-limited-broadcast? true}))
  ;; Start flow (requires core.async.flow/start)
  ;; (flow/start g)
  :rcf)
