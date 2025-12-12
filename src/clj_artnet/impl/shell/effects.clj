(ns clj-artnet.impl.shell.effects
  "Translates pure Art-Net protocol effects into actionable IO descriptors.

   Decouples the logic-layer protocol machine from the shell-layer IO system
   via open multimethod dispatch."
  (:import (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(defmulti translate-effect
          "Translates a single protocol effect map to an IO-layer action map.

                   Dispatch key: (:effect effect)
                   Default: returns nil (no action)"
          (fn [_context effect] (:effect effect)))

;; Default: unknown effects return nil
(defmethod translate-effect :default [_ _] nil)

(defn- normalize-packet-payload
  "Wraps byte-array payloads in a read-only ByteBuffer (required by IO layer)."
  [data]
  (if-let [p (:payload data)]
    (cond (bytes? p)
          (assoc data :payload (.asReadOnlyBuffer (ByteBuffer/wrap ^bytes p)))
          (instance? ByteBuffer p)
          (assoc data :payload (.asReadOnlyBuffer ^ByteBuffer p))
          :else data)
    data))

(defmethod translate-effect :tx-packet
  [_context effect]
  (let [{:keys [op data target broadcast? reply?]} effect
        packet (-> (normalize-packet-payload data)
                   (assoc :op op))]
    (cond-> {:type :send, :packet packet, :target target}
            broadcast? (assoc :broadcast? true)
            reply? (assoc :delay-ms 0))))

(defmethod translate-effect :callback
  [{:keys [callbacks node]} effect]
  (let [cb-key (:key effect)
        f (or (:fn effect) (get callbacks cb-key) (get callbacks :default))
        helper? (:helper? effect)]
    (when f
      (cond-> {:type    :callback,
               :fn      f,
               :payload (cond-> (assoc (:payload effect) :node node)
                                cb-key (assoc :callback-key cb-key))}
              helper? (assoc :helper? true)))))

(defmethod translate-effect :schedule
  [_context effect]
  (let [{:keys [delay-ms event]} effect
        {:keys [cmd target data]} event]
    (when (= :send-poll-reply cmd)
      {:type     :send,
       :delay-ms delay-ms,
       :packet   (assoc data :op :artpollreply),
       :target   target})))

(defmethod translate-effect :log [_ _] nil)

(defmethod translate-effect :dmx-frame
  [{:keys [callbacks]} effect]
  (let [f (get callbacks :dmx-frame)]
    (when f
      {:type    :callback,
       :fn      f,
       :payload (select-keys effect [:port-address :sequence :data :length])})))

(def ^:private inline-callback-keys
  "Callback keys that should be invoked inline rather than returned."
  #{:programming :ipprog :address})

(defn translate-effects
  "Converts a sequence of effects to IO-layer actions.

   - Invokes :programming, :ipprog, :address callbacks inline
   - Returns other callbacks and IO actions as a vector"
  [callbacks node effects]
  (let [context {:callbacks callbacks, :node node}]
    (->> effects
         (map (partial translate-effect context))
         (remove nil?)
         (reduce (fn [acc action]
                   (if (= :callback (:type action))
                     (let [key (get-in action [:payload :callback-key])
                           is-inline? (contains? inline-callback-keys key)]
                       (if is-inline?
                         (do (when (fn? (:fn action))
                               (try ((:fn action) (:payload action))
                                    (catch Exception _)))
                             acc)
                         (conj acc action)))
                     (conj acc action)))
                 []))))

(comment
  (require '[clj-artnet.impl.shell.effects :as effects] :reload)
  ;; Translate transmit packet effect
  (effects/translate-effect nil
                            {:effect :tx-packet,
                             :op     :artdmx,
                             :data   {:data (byte-array 512)},
                             :target {:host "10.0.0.1", :port 6454}})
  ;; => {:type :send, :packet {...}, :target {...}}
  ;; Translate callback effect
  (effects/translate-effect
    {:callbacks {:my-event (fn [_])}}
    {:effect :callback, :key :my-event, :payload {:foo :bar}})
  ;; => {:type :callback, :fn ..., :payload {...}}
  :rcf)
