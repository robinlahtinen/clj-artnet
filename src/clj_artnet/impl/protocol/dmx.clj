;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.dmx
  "Pure DMX merge and processing logic (Art-Net 4)."
  (:import
    (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def ^:const merge-source-timeout-ns
  "Timeout for merge sources (10s)."
  (long (* 10 1000000000)))

(def ^:const artsync-timeout-ns "ArtSync timeout (4s)." 4000000000)

(def ^:const default-sync-buffer-ttl-ns
  "Default sync buffer TTL (1s)."
  1000000000)

(def ^:const max-merge-sources "Max merge sources per port (2)." 2)

(defn merge-htp
  "Returns new byte array with max value at each position (HTP)."
  ^bytes [^bytes old ^bytes new]
  (let [len (max (alength old) (alength new))
        result (byte-array len)]
    (dotimes [i len]
      (let [o (if (< i (alength old)) (bit-and (long (aget old i)) 0xFF) 0)
            n (if (< i (alength new)) (bit-and (long (aget new i)) 0xFF) 0)]
        (aset-byte result i (unchecked-byte (max o n)))))
    result))

(defn merge-ltp
  "Returns clone of new data (LTP)."
  ^bytes [^bytes _old ^bytes new]
  (aclone new))

(defn merge-sources-htp
  "Returns {:data bytes :length N} merged via HTP."
  [sources]
  (let [entries (vals sources)]
    (if (empty? entries)
      {:data (byte-array 0), :length 0}
      (let [length (long (apply max (map :length entries)))
            result (byte-array length)]
        (doseq [{:keys [^bytes data]} entries]
          (dotimes [i length]
            (let [value (bit-and (long (aget data i)) 0xFF)
                  current (bit-and (long (aget result i)) 0xFF)]
              (when (> value current)
                (aset-byte result i (unchecked-byte value))))))
        {:data result, :length length}))))

(defn merge-sources-ltp
  "Returns {:data bytes :length N} from most recent source (LTP)."
  [sources]
  (let [latest (->> sources
                    vals
                    (apply max-key :last-updated))]
    {:data (aclone ^bytes (:data latest)), :length (:length latest)}))

(defn merge-sources
  "Returns merged source data using specified mode (:htp/:ltp)."
  [mode sources]
  (case mode
    :ltp (merge-sources-ltp sources)
    (merge-sources-htp sources)))

(defn extract-dmx-bytes
  "Extracts DMX data as byte array from packet."
  ^bytes [packet]
  (let [data (:data packet)
        length (long (or (:length packet) 512))]
    (cond (bytes? data)
          (let [^bytes source data
                result (byte-array length)]
            (System/arraycopy source 0 result 0 (min (alength source) length))
            result)
          (instance? ByteBuffer data)
          (let [^ByteBuffer buf (.duplicate ^ByteBuffer data)
                result (byte-array length)]
            (.get buf result 0 (min (.remaining buf) (int length)))
            result)
          (sequential? data)
          (byte-array (take length (map #(unchecked-byte (int %)) data)))
          :else (byte-array length))))

(defn dmx-source-key
  "Returns unique key [host physical] for DMX source."
  [sender packet]
  (let [host-str (cond (string? (:host sender)) (:host sender)
                       (some? (:host sender)) (str (:host sender))
                       :else "unknown")]
    [host-str (or (:physical packet) 0)]))

(defn prune-by-age
  "Returns map with entries older than cutoff-ns removed."
  ([m now cutoff-ns] (prune-by-age m now cutoff-ns :last-updated))
  ([m now cutoff-ns timestamp-key]
   (into {}
         (filter (fn [[_ v]]
                   (let [ts (get v timestamp-key)]
                     (and ts (< (- ^long now ^long ts) ^long cutoff-ns)))))
         (or m {}))))

(defn prune-merge-sources
  "Returns sources map with stale entries removed."
  [sources timestamp]
  (prune-by-age sources timestamp merge-source-timeout-ns :last-updated))

(defn port-merge-mode
  "Returns merge mode for port index. Default :htp."
  [state port-index]
  (or (get-in state [:dmx :merge :per-port port-index :mode]) :htp))

(defn at-source-limit?
  "Returns true if adding new source exceeds limit (2)."
  [sources source-key]
  (and (not (contains? sources source-key))
       (>= (count sources) max-merge-sources)))

(defn process-artdmx-merge
  "Processes ArtDmx packet with multi-source merge tracking.

  Returns map:
    :state           - updated state
    :output-data     - bytes
    :output-length   - int
    :emit?           - boolean
    :merging?        - boolean
    :source-rejected? - boolean"
  [state packet sender timestamp]
  (let [port-address (:port-address packet)
        source-key (dmx-source-key sender packet)
        cancel-armed? (get-in state [:dmx :merge :cancel-armed?])
        merge-state (get-in state [:dmx :merge] {})
        port-entry (get-in merge-state [:ports port-address] {})
        base-sources (if cancel-armed? {} (:sources port-entry {}))
        sources (prune-merge-sources base-sources timestamp)]
    (if (at-source-limit? sources source-key)
      (let [last-output (:last-output port-entry)
            dmx-bytes (extract-dmx-bytes packet)
            length (or (:length packet) (alength dmx-bytes))
            prev-data (or (:data last-output) dmx-bytes)]
        {:state            state
         :output-data      prev-data
         :output-length    (or (:length last-output) length)
         :emit?            false
         :merging?         true
         :source-rejected? true})
      (let [dmx-bytes (extract-dmx-bytes packet)
            length (long (or (:length packet) (alength dmx-bytes)))
            source-entry
            {:data dmx-bytes, :length length, :last-updated timestamp}
            sources' (assoc sources source-key source-entry)
            merging? (> (count sources') 1)
            mode (port-merge-mode state 0)
            {:keys [data length]} (if merging?
                                    (merge-sources mode sources')
                                    {:data dmx-bytes, :length length})
            last-output {:data data, :length length, :updated-at timestamp}
            port-entry' {:sources sources', :last-output last-output}
            state' (-> state
                       (assoc-in [:dmx :merge :ports port-address] port-entry')
                       (assoc-in [:dmx :merge :cancel-armed?] false))]
        {:state         state'
         :output-data   data
         :output-length length
         :emit?         true
         :merging?      merging?}))))

(defn any-port-merging?
  "Returns true if any port is merging multiple sources."
  [state]
  (boolean (some (fn [[_ {:keys [sources]}]] (> (count sources) 1))
                 (get-in state [:dmx :merge :ports]))))

(defn sync-sender-matches?
  "Returns true if ArtSync sender matches recent ArtDmx sender."
  [state sync-sender]
  (let [sync-host (some-> sync-sender
                          :host
                          str)
        ports (get-in state [:dmx :merge :ports] {})]
    (or (empty? ports)
        (every? (fn [[_ {:keys [sources]}]]
                  (or (empty? sources)
                      (> (count sources) 1)
                      (let [[source-host] (first (keys sources))]
                        (= source-host sync-host))))
                ports))))

(comment
  (require '[clj-artnet.impl.protocol.dmx :as dmx] :reload)
  ;; HTP merge
  (vec (dmx/merge-htp (byte-array [100 0]) (byte-array [0 100])))
  ;; => [100 100]
  ;; Source key
  (dmx/dmx-source-key {:host "1.2.3.4"} {:physical 1})
  ;; => ["1.2.3.4" 1]
  ;; Extract bytes
  (def packet {:data [255 0 0], :length 3})
  (vec (dmx/extract-dmx-bytes packet))
  ;; => [255 0 0]
  :rcf)
