;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.shell.system
  "System lifecycle and runtime management/integration.

   Integrates shell components and provides higher-level system operations
   (lifecycle, effect execution). Delegates core I/O to shell modules."
  (:require
    [clj-artnet.impl.protocol.effects :as effects]
    [clj-artnet.impl.protocol.state :as proto.state]
    [clj-artnet.impl.shell.lifecycle :as lifecycle]))

(set! *warn-on-reflection* true)

(def close-quietly lifecycle/close-quietly)
(def ensure-chan-open lifecycle/ensure-chan-open)
(def build-logic-config lifecycle/build-logic-config)
(def create-resource-pools lifecycle/create-resource-pools)
(def open-network-channel lifecycle/open-network-channel)

(defn execute-effect!
  "Performs the side effect described by a protocol effect map.
   (Legacy/Direct execution pattern).

   Supported effects: :tx-packet, :callback, :log, :schedule, :dmx-frame."
  [context effect]
  (case (:effect effect)
    :tx-packet (when-let [send-fn (:send-fn context)] (send-fn effect))
    :callback (when-let [callbacks (:callbacks context)]
                (when-let [cb-fn (get callbacks (:key effect))]
                  (cb-fn (:payload effect))))
    :log (println (:level effect) (:message effect) (:data effect))
    :schedule (when-let [scheduler (:scheduler context)]
                (scheduler (:delay-ms effect) (:event effect)))
    :dmx-frame (when-let [dmx-out-fn (:dmx-out-fn context)] (dmx-out-fn effect))
    (println :warn "Unknown effect type:" (:effect effect))))

(defn execute-effects!
  "Executes a sequence of protocol effects.
   Returns the count of executed effects."
  [context effects]
  (reduce (fn [n effect] (execute-effect! context effect) (inc n)) 0 effects))

(defn create-initial-state
  "Creates the initial protocol state for a new node.
   Delegates to clj-artnet.impl.protocol.state/initial-state."
  [config]
  (proto.state/initial-state config))

(comment
  (require '[clj-artnet.impl.shell.system :as system] :reload)
  ;; Initialize state
  (system/create-initial-state {:node {:short-name "TestNode"}})
  ;; Execute effects
  (let [ctx {:callbacks {:dmx (fn [e] (println "DMX:" e))}}]
    (system/execute-effects! ctx
                             [(effects/callback :dmx {:data "test"})
                              (effects/log-msg :info "Hello")]))
  ;; Output: DMX: {:data "test"} \n :info Hello nil
  :rcf)
