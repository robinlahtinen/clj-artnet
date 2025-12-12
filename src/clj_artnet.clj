;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet
  "Facilities for Art-Net 4 protocol communication.

  Lifecycle:
    start-node! - starts a local art-net node
    stop-node!  - stops the art-net node

  Commands:
    send-dmx!        - unicasts artdmx packet
    send-rdm!        - unicasts artrdm packet
    send-diagnostic! - unicasts artdiagdata packet
    send-sync!       - broadcasts artsync packet

  State:
    state             - snapshot of node state
    diagnostics       - snapshot of diagnostics
    apply-state!      - updates node configuration"
  (:require [clj-artnet.impl.protocol.codec.domain.common :as common]
            [clj-artnet.impl.shell.commands :as commands]
            [clj-artnet.impl.shell.graph :as graph]
            [clj-artnet.impl.shell.lifecycle :as lifecycle]
            [clj-artnet.impl.shell.state :as state]
            [clojure.core.async.flow :as flow]))

(set! *warn-on-reflection* true)

(defn start-node!
  "Starts a local Art-Net Node. Returns control map.

  Options:
    :node                     - map, ArtPollReply fields
    :callbacks                - map, event handlers {:dmx fn, :sync fn}
    :bind                     - map, {:host str, :port int}
    :diagnostics              - map, {:broadcast-target {:host str, :port int}}
    :default-target           - map, fallback send target (e.g. Output Gateway)
    :allow-limited-broadcast? - boolean, permit 255.255.255.255 (default false)
    :rx-buffer                - map, {:count int, :size int}
    :tx-buffer                - map, {:count int, :size int}

  Returns map:
    :flow        - flow instance
    :stop!       - idempotent stop function
    :config      - resolved configuration
    :report-chan - flow report channel"
  ([] (start-node! {}))
  ([{:keys [default-target max-packet recv-buffer command-buffer actions-buffer
            allow-limited-broadcast?],
     :as   config,
     :or
     {max-packet 2048, recv-buffer 64, command-buffer 32, actions-buffer 32}}]
   (let [logic-config (lifecycle/build-logic-config config)
         {:keys [rx-pool tx-pool]} (lifecycle/create-resource-pools config)
         channel (lifecycle/open-network-channel config)
         the-flow (graph/create-graph {:channel        channel,
                                       :rx-pool        rx-pool,
                                       :tx-pool        tx-pool,
                                       :logic-config   logic-config,
                                       :max-packet     max-packet,
                                       :recv-buffer    recv-buffer,
                                       :command-buffer command-buffer,
                                       :actions-buffer actions-buffer,
                                       :default-target default-target,
                                       :allow-limited-broadcast?
                                       allow-limited-broadcast?})]
     (try (let [start-result (flow/start the-flow)]
            (flow/resume the-flow)
            (let [stop-fn (lifecycle/make-stop-fn {:flow    the-flow,
                                                   :rx-pool rx-pool,
                                                   :tx-pool tx-pool,
                                                   :channel channel})]
              {:flow           the-flow,
               :report-chan    (:report-chan start-result),
               :error-chan     (:error-chan start-result),
               :stop!          stop-fn,
               :pause!         #(flow/pause the-flow),
               :resume!        #(flow/resume the-flow),
               :config         logic-config,
               :default-target default-target}))
          (catch Throwable t
            (lifecycle/close-quietly rx-pool)
            (lifecycle/close-quietly tx-pool)
            (lifecycle/ensure-chan-open channel)
            (throw t))))))

(defn stop-node!
  "Stops the Art-Net Node and releases resources. Returns result of flow stop."
  [node]
  ((:stop! node)))

(defn enqueue-command!
  "Enqueues a command into the logic process. Returns nil."
  [node command]
  (commands/enqueue-command! node command))

(defn send-dmx!
  "Unicasts an ArtDmx packet to a specific Port-Address. Returns nil.

  Options:
    :data         - byte-array/ByteBuffer/seq, DMX512 data (up to 512 bytes)
    :net          - int, Network address (0-127)
    :sub-net      - int, Sub-Net address (0-15)
    :universe     - int, Universe address (0-15)
    :port-address - int, 15-bit Port-Address (alternative to net/sub-net/universe)
    :target       - map, {:host str, :port int} (target Node)"
  [node opts]
  (commands/send-dmx! node opts))

(defn send-rdm!
  "Unicasts an ArtRdm packet to a specific Node. Returns nil.

  Options:
    :rdm-packet   - byte-array/ByteBuffer, RDM PDU
    :net          - int, Network address (0-127)
    :sub-net      - int, Sub-Net address (0-15)
    :universe     - int, Universe address (0-15)
    :port-address - int, 15-bit Port-Address (alternative to net/sub-net/universe)
    :target       - map, {:host str, :port int} (target Node)"
  [node opts]
  (commands/send-rdm! node opts))

(defn send-diagnostic!
  "Unicasts an ArtDiagData packet. Returns nil.

  Options:
    :text         - string, diagnostic message (ASCII)
    :priority     - keyword/int, e.g. :dp-low
    :logical-port - int, port index"
  [node opts]
  (commands/send-diagnostic! node opts))

(defn send-sync!
  "Broadcasts an ArtSync packet to force synchronous output. Returns nil.

  Options:
    :target - map, optional override {:host str, :port int}"
  ([node] (commands/send-sync! node))
  ([node opts] (commands/send-sync! node opts)))

(defn diagnostics
  "Returns a snapshot of the diagnostics state.

  Options:
    :keys       - vector, keys to include (default [:diagnostics])
    :timeout-ms - int, wait duration"
  ([node] (state/diagnostics node))
  ([node opts] (state/diagnostics node opts)))

(defn state
  "Returns a snapshot of the runtime state.

  Options:
    :keys       - vector, keys to include (default [:node :network])
    :timeout-ms - int, wait duration"
  ([node] (state/state node))
  ([node opts] (state/state node opts)))

(defn apply-state!
  "Applies a partial state update to the Node. Returns nil.

  State map:
    :node         - ArtPollReply fields
    :network      - network config
    :callbacks    - handlers
    :capabilities - status overrides"
  [node state-map]
  (state/apply-state! node state-map))

(defn compose-port-address
  "Composes a 15-bit Port-Address from its components.

  Returns int (15-bit Port-Address)."
  [net sub-net universe]
  (common/compose-port-address net sub-net universe))

(defn split-port-address
  "Splits a 15-bit Port-Address into its components.

  Returns map {:net :sub-net :universe}."
  [port-address]
  (common/split-port-address port-address))

(comment
  (require '[clj-artnet :as artnet])
  ;; start controller node
  (def node
    (artnet/start-node! {:callbacks {:dmx (fn [{:keys [packet source]}]
                                            (println "rx dmx" (:length packet)
                                                     "bytes from" source))}}))
  ;; unicast dmx (net 0, subnet 0, universe 0)
  (artnet/send-dmx! node {:data (byte-array 512), :universe 0})
  ;; unicast dmx (composed port-address)
  (artnet/send-dmx! node {:port-address 0, :data [255 0 0]})
  ;; broadcast sync
  (artnet/send-sync! node)
  ;; update config
  (artnet/apply-state! node {:node {:short-name "clojure controller"}})
  ;; inspect state
  (artnet/state node)
  ;; stop node
  (artnet/stop-node! node)
  :rcf)
