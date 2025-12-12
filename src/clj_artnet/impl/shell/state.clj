(ns clj-artnet.impl.shell.state
  "State snapshot/application utilities for running Art-Net nodes.

   Provides blocking access to remote node state via flow injection and
   promise synchronization, and non-blocking state updates."
  (:require [clj-artnet.impl.shell.commands :as commands]
            [clojure.core.async.flow :as flow]))

(set! *warn-on-reflection* true)

(defn request-snapshot
  "Requests a node state snapshot (blocking).

   Injects a snapshot request into the flow and waits for the result
   via a promise delivery."
  [node {:keys [keys timeout-ms], :as _opts} default-keys]
  (let [the-flow (:flow node)]
    (when-not the-flow
      (throw (ex-info "Node missing flow context" {:node node})))
    (let [result (promise)
          msg {:type  :snapshot,
               :keys  (or (seq keys) default-keys),
               :reply (fn [snapshot] (deliver result snapshot))}
          timeout (long (or timeout-ms 1000))]
      (flow/inject the-flow [:logic :commands] [msg])
      (let [value (deref result timeout ::snapshot-timeout)]
        (when (= ::snapshot-timeout value)
          (throw (ex-info "Snapshot request timed out" {:timeout-ms timeout})))
        value))))

(defn diagnostics
  "Fetched diagnostics from the running node (blocking).
   Default timeout: 1000ms."
  ([node] (diagnostics node {}))
  ([node opts] (request-snapshot node opts [:diagnostics])))

(defn state
  "Fetched runtime state [:node :network] from the node (blocking).
   Default timeout: 1000ms."
  ([node] (state node {}))
  ([node opts] (request-snapshot node opts [:node :network])))

(defn apply-state!
  "Applies a partial state updates to a running node (async).

   Accepted state keys: :node, :network, :callbacks, :capabilities.
   Enqueues the update command and returns immediately."
  [node state]
  (let [command (commands/apply-state-command state)]
    (commands/enqueue-command! node command)))

(comment
  (require '[clj-artnet.impl.shell.state :as state] :reload)
  ;; Mock node (incomplete, would fail flow injection in real usage without
  ;; real flow)
  (def node
    {:flow (reify
             Object
             (toString [_] "Flow"))})
  ;; Request state (would timeout without running logic loop)
  (future (state/state node {:timeout-ms 100}))
  ;; Apply state
  (state/apply-state! node {:node {:short-name "NewName"}})
  :rcf)
