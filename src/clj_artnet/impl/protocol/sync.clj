;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.sync
  "ArtSync handling for Art-Net 4 protocol.

   Per Art-Net 4 spec:
   - ArtSync is used to synchronize DMX output across multiple universes
   - ArtSync timeout is 4 seconds (nodes revert to immediate mode)
   - ArtSync shall be ignored when merging multiple streams
   - ArtSync shall be ignored if the sender doesn't match the most recent ArtDmx sender

   Modes:
   - :immediate - Output DMX frames as they arrive (default)
   - :art-sync  - Buffer DMX frames until ArtSync is received")

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(require '[clj-artnet.impl.protocol.dmx :as dmx])

(def ^:const artsync-timeout-ns
  "ArtSync timeout per spec (4 seconds in nanoseconds)."
  4000000000)

(def ^:const default-sync-buffer-ttl-ns
  "Default TTL for sync-buffered frames (1 second in nanoseconds)."
  1000000000)

(defn configured-art-sync?
  "Check if ArtSync mode is configured."
  [state]
  (= :art-sync (get-in state [:dmx :sync :mode])))

(defn current-sync-mode
  "Get the current effective sync mode (may differ from configured if expired)."
  [state]
  (or (get-in state [:dmx :sync :active-mode])
      (get-in state [:dmx :sync :mode])
      :immediate))

(defn sync-buffer-ttl-ns
  "Get sync buffer TTL from state."
  ^long [state]
  (long (or (get-in state [:dmx :sync :buffer-ttl-ns])
            default-sync-buffer-ttl-ns)))

(defn maybe-expire-art-sync
  "Check if ArtSync mode should expire due to timeout (4s per spec).
   Returns updated state with mode reset to :immediate if expired."
  [state timestamp]
  (if (and (configured-art-sync? state) (= :art-sync (current-sync-mode state)))
    (let [waiting-since (get-in state [:dmx :sync :waiting-since])
          last-sync (get-in state [:dmx :sync :last-sync-at])
          reference (or waiting-since last-sync)]
      (if (and reference
               (>= (- ^long timestamp ^long reference) artsync-timeout-ns))
        (-> state
            (assoc-in [:dmx :sync :active-mode] :immediate)
            (assoc-in [:dmx :sync :last-sync-at] nil)
            (assoc-in [:dmx :sync :waiting-since] nil)
            (assoc-in [:dmx :sync-buffer] {}))
        state))
    state))

(defn activate-art-sync
  "Activate ArtSync mode (if configured). Returns updated state."
  [state timestamp]
  (if (configured-art-sync? state)
    (-> state
        (assoc-in [:dmx :sync :active-mode] :art-sync)
        (assoc-in [:dmx :sync :last-sync-at] timestamp))
    state))

(defn stage-sync-frame
  "Buffer a DMX frame for a later release when ArtSync arrives.
   Prunes expired entries and tracks when waiting started."
  [state packet sender timestamp]
  (let [ttl (sync-buffer-ttl-ns state)
        buffer (dmx/prune-by-age (get-in state [:dmx :sync-buffer])
                                 timestamp
                                 ttl
                                 :received-at)
        entry {:packet packet, :sender sender, :received-at timestamp}
        waiting? (get-in state [:dmx :sync :waiting-since])]
    (-> state
        (assoc-in [:dmx :sync-buffer]
                  (assoc buffer (:port-address packet) entry))
        (cond-> (not waiting?) (assoc-in [:dmx :sync :waiting-since]
                                         timestamp)))))

(defn drain-sync-frames
  "Remove all buffered frames from the sync buffer.
   Returns [state' frames] where frames are sorted by received-at."
  [state timestamp]
  (let [ttl (sync-buffer-ttl-ns state)
        buffer (dmx/prune-by-age (get-in state [:dmx :sync-buffer])
                                 timestamp
                                 ttl
                                 :received-at)
        frames (->> buffer
                    vals
                    (sort-by :received-at)
                    vec)]
    [(-> state
         (assoc-in [:dmx :sync-buffer] {})
         (assoc-in [:dmx :sync :waiting-since] nil)) frames]))

(defn should-ignore-sync?
  "Check if ArtSync should be ignored per Art-Net 4 spec.

   ArtSync is ignored when:
   - Merging multiple streams (any port has >1 source)
   - Sender doesn't match the most recent ArtDmx sender"
  [state sync-sender]
  (or (dmx/any-port-merging? state)
      (not (dmx/sync-sender-matches? state sync-sender))))

(defn release-sync-frames-impl
  "Process buffered frames after ArtSync trigger.

   Returns {:state state' :frames-processed N :frames-data [...]}
   where frames-data contains {:packet :sender :output-data :output-length}
   for each frame that should be emitted."
  [state timestamp]
  (let [state' (maybe-expire-art-sync state timestamp)
        state'' (if (configured-art-sync? state')
                  (activate-art-sync state' timestamp)
                  state')
        mode (current-sync-mode state'')]
    (if (not= mode :art-sync)
      {:state state'', :frames-processed 0, :frames-data []}
      (let [[state''' frames] (drain-sync-frames state'' timestamp)]
        (reduce
          (fn [acc {:keys [packet sender received-at]}]
            (let [state-in (:state acc)
                  {:keys [state output-data output-length emit?]}
                  (dmx/process-artdmx-merge state-in packet sender received-at)
                  port-addr (:port-address packet)
                  processed (long (:frames-processed acc))]
              (if emit?
                {:state            state
                 :frames-processed (unchecked-inc processed)
                 :frames-data      (conj (:frames-data acc)
                                         {:packet        packet
                                          :sender        sender
                                          :port-address  port-addr
                                          :output-data   output-data
                                          :output-length output-length})}
                (assoc acc :state state))))
          {:state state''', :frames-processed 0, :frames-data []}
          frames)))))

(comment
  ;; Check if sync is configured
  (configured-art-sync? {:dmx {:sync {:mode :art-sync}}})
  ;; => true
  (configured-art-sync? {:dmx {:sync {:mode :immediate}}})
  ;; => false. Get current mode
  (current-sync-mode {:dmx {:sync {:mode :art-sync, :active-mode nil}}})
  ;; => :art-sync
  (current-sync-mode {:dmx {:sync {:mode :art-sync, :active-mode :immediate}}})
  ;; => :immediate (expired)
  :rcf)
