(ns clj-artnet.impl.shell.commands
  "Constructs and injects commands (DMX, RDM, State) into the node's flow loop.

   Provides a functional API for queuing side effects and protocol actions
   to be processed by the shell/logic graph."
  (:require [clj-artnet.impl.protocol.diagnostics :as diagnostics]
            [clojure.core.async.flow :as flow]))

(set! *warn-on-reflection* true)

(defn enqueue-command!
  "Injects a command map into the logic layer commands channel.
   The node must be a map containing a :flow key with a running graph."
  [node command]
  (flow/inject (:flow node) [:logic :commands] [command]))

(defn send-dmx!
  "Enqueues an ArtDmx frame for transmission.

   Options:
   - :data      -> Channel bytes (byte array/ByteBuffer)
   - :net       -> Net switch (0-127)
   - :sub-net   -> Sub-Net switch (0-15)
   - :universe  -> Universe (0-15)
   - :target    -> Unicast target {:host ... :port ...} (Required)
   - :sequence  -> Sequence number override (optional)

   Art-Net 4 spec: ArtDmx must strictly be unicast."
  [node opts]
  (let [command (-> opts
                    (assoc :type :command :command :send-dmx))]
    (enqueue-command! node command)))

(defn send-rdm!
  "Enqueues an ArtRdm frame for transmission.

   Options:
   - :rdm-packet -> RDM PDU bytes (without start code)
   - :address    -> Port address / Net / Sub-Net / Universe
   - :target     -> Unicast target {:host ... :port ...} (Required)

   Art-Net 4 spec: ArtRdm must strictly be unicast."
  [node opts]
  (let [command (-> opts
                    (assoc :type :command :command :send-rdm))]
    (enqueue-command! node command)))

(defn send-diagnostic!
  "Enqueues an ArtDiagData message for transmission.

   Options:
   - :text         -> ASCII text (max 512 chars)
   - :priority     -> Priority keyword/byte
   - :logical-port -> Logical port ID

   Diagnositics are sent to controllers subscribed via ArtPoll."
  [node opts]
  (let [priority-code (diagnostics/priority-code (:priority opts))
        command (-> opts
                    (assoc :type :command
                           :command :diagnostic
                           :priority priority-code))]
    (enqueue-command! node command)))

(defn apply-state-command
  "Builds an apply-state command map.

   State map can contain :node, :network, :callbacks, :capabilities keys.
   Logic layer merges these updates into the live configuration."
  [state]
  (when (and state (not (map? state)))
    (throw (ex-info "apply-state expects a map" {:provided-type (type state)})))
  {:type :command, :command :apply-state, :state (or state {})})

(defn send-sync!
  "Broadcasts an ArtSync frame to synchronize output buffers.

   Options:
   - :target -> Broadcast target (optional, defaults to broadcast)

   Used to prevent visual tearing on large arrays."
  ([node] (send-sync! node {}))
  ([node opts]
   (let [command (assoc opts :type :command :command :send-sync)]
     (enqueue-command! node command))))

(comment
  (require '[clj-artnet.impl.shell.commands :as commands] :reload)
  ;; Define mock node flow
  (def node
    {:flow (reify
             Object
             (toString [_] "FlowGraph"))})
  ;; Enqueue various commands
  (commands/send-dmx! node
                      {:target   {:host "1.2.3.4", :port 6454},
                       :data     (byte-array 512),
                       :net      0,
                       :sub-net  0,
                       :universe 1})
  (commands/send-sync! node)
  ;; Build state command
  (commands/apply-state-command {:node {:short-name "NewName"}})
  ;; => {:type :command, :command :apply-state, :state {:node ...}}
  :rcf)
