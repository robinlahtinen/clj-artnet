;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.effects
  "Pure data constructors for Art-Net protocol effects.")

(set! *warn-on-reflection* true)

(defn result
  "Creates step result with state and optional effects."
  ([state] {:state state, :effects []})
  ([state effects] {:state state, :effects (vec effects)}))

(defn add-effect
  "Adds effect to step result."
  [result effect]
  (update result :effects conj effect))

(defn add-effects
  "Adds multiple effects to step result."
  [result effects]
  (update result :effects into effects))

(defn merge-results
  "Merges multiple step results, combining effects."
  [& results]
  (reduce (fn [acc r]
            {:state   (:state r (:state acc))
             :effects (into (:effects acc []) (:effects r []))})
          {:state nil, :effects []}
          results))

(defn tx-packet
  "Effect: Send packet to network."
  ([op-kw data] {:effect :tx-packet, :op op-kw, :data data})
  ([op-kw data target]
   {:effect :tx-packet, :op op-kw, :data data, :target target}))

(defn tx-reply
  "Effect: Send reply packet to sender."
  [op-kw data sender]
  {:effect :tx-packet, :op op-kw, :data data, :target sender, :reply? true})

(defn tx-broadcast
  "Effect: Broadcast packet to all peers."
  [op-kw data]
  {:effect :tx-packet, :op op-kw, :data data, :broadcast? true})

(defn callback
  "Effect: Invoke user callback."
  [callback-key payload]
  {:effect :callback, :key callback-key, :payload payload})

(defn log-msg
  "Effect: Emit log message."
  ([level message] {:effect :log, :level level, :message message})
  ([level message data]
   {:effect :log, :level level, :message message, :data data}))

(defn schedule
  "Effect: Schedule future event."
  [delay-ms event]
  {:effect :schedule, :delay-ms delay-ms, :event event})

(defn diag-message
  "Effect: Send ArtDiagData diagnostic message."
  [priority message target]
  {:effect :tx-packet
   :op     :artdiagdata
   :data   {:priority priority, :data message}
   :target target})

(defn dmx-frame
  "Effect: Emit DMX frame to output."
  [port-address sequence data length]
  {:effect       :dmx-frame
   :port-address port-address
   :sequence     sequence
   :data         data
   :length       length})

(comment
  (require '[clj-artnet.impl.protocol.effects :as effects] :reload)
  ;; create result
  (effects/result {:state :foo})
  ;; => {:state {:state :foo}, :effects []}
  ;; add effect
  (-> (effects/result {})
      (effects/add-effect (effects/log-msg :info "hello")))
  ;; constructors
  (effects/tx-packet :artdmx {:data []})
  (effects/schedule 1000 {:type :tick})
  :rcf)
