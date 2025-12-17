;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.logic-test
  (:require
    [clj-artnet.fixtures.builders :as builders]
    [clj-artnet.fixtures.data :as fixtures]
    [clj-artnet.impl.protocol.codec.constants :as const]
    [clj-artnet.impl.protocol.codec.dispatch :as dispatch]
    [clj-artnet.impl.protocol.codec.domain.common :as common]
    [clj-artnet.impl.protocol.codec.types :as types]
    [clj-artnet.impl.protocol.diagnostics :as diagnostics]
    [clj-artnet.impl.protocol.dmx-helpers :as dmx]
    [clj-artnet.impl.protocol.firmware :as firmware]
    [clj-artnet.impl.protocol.lifecycle :as lifecycle]
    [clj-artnet.impl.protocol.machine :as machine]
    [clj-artnet.impl.protocol.node-state :as state]
    [clj-artnet.impl.protocol.rdm.discovery :as rdm.discovery]
    [clj-artnet.support.helpers :refer [thrown-with-msg?]]
    [clojure.test :refer [deftest is testing]])
  (:import
    (clojure.lang ExceptionInfo)
    (java.net InetAddress)
    (java.nio ByteBuffer)))

(defn- normalize-payload
  "Ensures payload data is exposed as a ReadOnly ByteBuffer for test assertions."
  [data]
  (if-let [p (:payload data)]
    (cond (bytes? p)
          (assoc data :payload (.asReadOnlyBuffer (ByteBuffer/wrap ^bytes p)))
          (instance? ByteBuffer p)
          (assoc data :payload (.asReadOnlyBuffer ^ByteBuffer p))
          :else data)
    data))

(defn- resolve-callbacks
  "Reconstructs the callback resolution logic used by tests.
   1. Merges state and config callbacks.
   2. Flattens :packets sub-map into root (for generic ops).
   3. Maps programming/on-change to :programming, :ipprog, :address."
  [state config]
  (let [base (or (:callbacks state) (:callbacks config) {})
        packet-cbs (:packets base)
        ;; Flatten :packets into the root
        flat (if packet-cbs (merge (dissoc base :packets) packet-cbs) base)
        ;; Programming handlers (often in config root, not :callbacks)
        prog-cb (or (get-in state [:programming :on-change])
                    (get-in config [:programming :on-change]))]
    (cond-> flat
            prog-cb (assoc :programming prog-cb :ipprog prog-cb :address prog-cb))))

(defn- transform-effects
  "Transforms internal machine effects into test actions."
  [state config effects]
  (let [callbacks (resolve-callbacks state config)
        ;; These callback keys trigger inline execution in tests (legacy
        ;; behavior)
        inline-keys #{:programming :ipprog :address}]
    (reduce
      (fn [acc eff]
        (case (:effect eff)
          :tx-packet (let [{:keys [op data target broadcast? reply?]} eff
                           packet (-> (normalize-payload data)
                                      (assoc :op op))]
                       (conj
                         acc
                         (cond-> {:type :send, :packet packet, :target target}
                                 broadcast? (assoc :broadcast? true)
                                 reply? (assoc :delay-ms 0))))
          :callback
          (let [k (:key eff)
                f (or (:fn eff) (get callbacks k) (get callbacks :default))
                payload (cond-> (assoc (:payload eff) :node (:node state))
                                k (assoc :callback-key k))]
            (if (and (contains? inline-keys k) f)
              ;; Execute inline if it's a synchronous logic callback
              (do (try (f payload) (catch Exception _ nil)) acc)
              ;; Otherwise return as action for test assertion
              (cond-> acc
                      f (conj (cond-> {:type :callback, :fn f, :payload payload}
                                      (:helper? eff) (assoc :helper? true))))))
          :schedule
          (let [{:keys [delay-ms event]} eff
                {:keys [cmd target data]} event]
            (if (= :send-poll-reply cmd)
              (conj acc
                    {:type     :send
                     :delay-ms delay-ms
                     :packet   (assoc data :op :artpollreply)
                     :target   target})
              (conj acc {:type :schedule, :delay-ms delay-ms, :event event})))
          ;; Default: ignore unknown effects
          acc))
      []
      effects)))

(defn logic-step
  "Executes the protocol machine step and transforms effects to test actions.
   Signature: (state config msg) -> [state' actions]"
  [state config msg]
  (let [state (lifecycle/ensure-state state config)
        ;; Ensure callbacks are present in state for machine logic if
        ;; needed
        state (if (and (:callbacks config) (not (:callbacks state)))
                (assoc state :callbacks (:callbacks config))
                state)]
    (if (= :snapshot (:type msg))
      ;; Handle test-specific snapshot request
      (let [{:keys [keys reply]} msg
            payload (lifecycle/snapshot state (or (seq keys) [:diagnostics]))]
        (when (fn? reply) (reply payload))
        [state []])
      ;; Standard Protocol Machine Step
      (let [evt (case (:type msg)
                  :rx (assoc msg :type :rx-packet)
                  :tick (assoc msg :timestamp (:now msg))
                  msg)
            result (machine/step state evt)
            new-state (:state result)
            actions (transform-effects new-state config (:effects result))
            ;; Append release action if present in test message
            actions (if (:release msg)
                      (conj actions {:type :release, :release (:release msg)})
                      actions)]
        [new-state actions]))))

(def base-config
  {:node
   {:net-switch 0, :sub-switch 0, :port-types [0xC0 0 0 0], :sw-out [1 0 0 0]}
   :diagnostics     {:broadcast-target {:host "10.0.0.255", :port 6454}}
   :random-delay-fn (constantly 0)})

(def artpollreply-config
  (-> base-config
      (assoc :node fixtures/artpollreply-node-config)
      (assoc :discovery {:reply-on-change-limit 10})))

(defn- artdmx-packet
  [bytes]
  (let [payload (byte-array bytes)
        buf (dispatch/encode {:op       :artdmx
                              :sequence 1
                              :physical 0
                              :net      0
                              :sub-net  0
                              :universe 0
                              :data     payload}
                             (ByteBuffer/allocate 1024))]
    (dispatch/decode buf)))

(defn- artnzs-packet
  [start-code bytes]
  (let [payload (byte-array bytes)
        buf (dispatch/encode {:op         :artnzs
                              :sequence   7
                              :start-code start-code
                              :net        0
                              :sub-net    0
                              :universe   0
                              :data       payload}
                             (ByteBuffer/allocate 1024))]
    (dispatch/decode buf)))

(defn- artinput-packet
  [disabled-or-opts]
  (let [{:keys [disabled bind-index num-ports inputs]}
        (if (map? disabled-or-opts)
          disabled-or-opts
          {:disabled disabled-or-opts})
        packet (cond-> {:op         :artinput
                        :bind-index (or bind-index 1)
                        :num-ports  (or num-ports 4)}
                       (some? disabled) (assoc :disabled disabled)
                       (some? inputs) (assoc :inputs inputs))
        buf (dispatch/encode packet
                             (ByteBuffer/allocate const/artinput-length))]
    (dispatch/decode buf)))

(defn- artvlc-packet
  [bytes]
  (let [payload (byte-array bytes)
        buf (dispatch/encode {:op       :artvlc
                              :sequence 9
                              :net      0
                              :sub-net  0
                              :universe 0
                              :vlc      {:payload          payload
                                         :transaction      0x1201
                                         :slot-address     0
                                         :depth            42
                                         :frequency        1000
                                         :modulation       3
                                         :payload-language 0x0001
                                         :beacon-repeat    0
                                         :ieee?            true}}
                             (ByteBuffer/allocate 1024))]
    (dispatch/decode buf)))

(defn- arttrigger-packet
  [{:keys [oem key sub-key data key-type]
    :or   {oem 0xFFFF, key-type :key-macro, sub-key 0, data (byte-array 0)}}]
  (let [packet {:op       :arttrigger
                :oem      oem
                :key      key
                :key-type key-type
                :sub-key  sub-key
                :data     data}
        buf (dispatch/encode packet
                             (ByteBuffer/allocate const/arttrigger-length))]
    (dispatch/decode buf)))

(defn- artcommand-packet
  [{:keys [esta text data], :or {esta 0xFFFF}}]
  (let [packet (cond-> {:op :artcommand, :esta-man esta}
                       text (assoc :text text)
                       data (assoc :data data))
        buf (dispatch/encode packet
                             (ByteBuffer/allocate const/artcommand-max-length))]
    (dispatch/decode buf)))

(defn- artrdmsub-packet
  [{:keys [command values sub-count uid parameter-id sub-device]
    :or   {command :get, uid [0 1 2 3 4 5], parameter-id 0x1234, sub-device 0}}]
  (let [resolved-count (or sub-count (if (seq values) (count values) 1))
        packet (cond-> {:op           :artrdmsub
                        :rdm-version  1
                        :uid          uid
                        :command      command
                        :parameter-id parameter-id
                        :sub-device   sub-device
                        :sub-count    resolved-count}
                       (seq values) (assoc :values values))
        buf (dispatch/encode packet)]
    (dispatch/decode buf)))

(defn- rdm-payload-buffer
  ([command-class] (rdm-payload-buffer command-class 32))
  ([command-class length]
   (let [size (max length 0)
         bytes (byte-array size)]
     (dotimes [idx size] (aset bytes idx (unchecked-byte (mod (+ idx 1) 256))))
     (when (>= size 21) (aset bytes 20 (unchecked-byte command-class)))
     (ByteBuffer/wrap bytes))))

(defn- artdatarequest-packet
  [{:keys [request request-type esta oem]
    :or   {request-type :dr-poll, esta 0x7FF0, oem 0xFFFF}}]
  (let [packet (cond-> {:op :artdatarequest, :esta-man esta, :oem oem}
                       request (assoc :request request)
                       request-type (assoc :request-type request-type))
        buf (dispatch/encode packet
                             (ByteBuffer/allocate const/artdatarequest-length))]
    (dispatch/decode buf)))

(defn- generic-op-packet
  ([op] (generic-op-packet op [0]))
  ([op bytes]
   (let [payload (byte-array (map unchecked-byte bytes))
         buf (dispatch/encode {:op op, :data payload})]
     (dispatch/decode buf))))

(def multi-bind-config
  (assoc base-config
    :node
    {:net-switch 0
     :sub-switch 0
     :port-pages [{:bind-index    1
                   :port-types    [0xC0 0 0 0]
                   :sw-out        [0x01 0 0 0]
                   :good-output-a [0x80 0 0 0]}
                  {:bind-index    2
                   :port-types    [0xC0 0 0 0]
                   :sw-out        [0x02 0 0 0]
                   :good-output-a [0x40 0 0 0]}]}))

(def auto-ports-config
  (assoc base-config
    :node
    {:ports [{:port-address  (common/compose-port-address 0 0 1)
              :port-type     0xC0
              :good-output-a 0x80}
             {:port-address  (common/compose-port-address 0 0 2)
              :port-type     0xC0
              :good-output-a 0x40}
             {:port-address  (common/compose-port-address 0 1 1)
              :port-type     0xC0
              :good-output-a 0x20}
             {:port-address  (common/compose-port-address 0 1 2)
              :port-type     0xC0
              :good-output-a 0x10}
             {:port-address  (common/compose-port-address 1 0 1)
              :port-type     0xC0
              :good-output-a 0x08}]}))

(def multi-bind-good-input-config
  (update multi-bind-config
          :node
          (fn [node]
            (update node
                    :port-pages
                    (fn [pages]
                      (vec (map-indexed
                             (fn [idx page]
                               (case idx
                                 0 (assoc page :good-input [0x80 0x00 0x00 0x00])
                                 1 (assoc page :good-input [0x40 0x01 0x02 0x03])
                                 page))
                             pages)))))))

(def merge-port-address (common/compose-port-address 0 0 0))

(def merge-test-config
  (assoc base-config
    :node
    (-> (:node base-config)
        (assoc :sw-out [0 0 0 0])
        (assoc :port-types [0xC0 0 0 0]))))

(defn- failsafe-status3
  [mode]
  (let [mode->bits {:hold 0, :zero 1, :full 2, :scene 3}
        bits (bit-and (or (mode->bits mode) 0) 0x03)]
    (-> state/status3-port-direction-bit
        (bit-or state/status3-programmable-failsafe-bit)
        (bit-or (bit-shift-left bits 6)))))

(defn- targeted-artdmx [bytes] (artdmx-packet bytes))

(defn- merge-rx
  [sender bytes]
  {:type :rx, :sender sender, :packet (targeted-artdmx bytes)})

(defn- merge-last-output
  [state]
  (when-let [data (get-in state
                          [:dmx :merge :ports merge-port-address :last-output
                           :data])]
    (mapv #(bit-and 0xFF %) data)))

(defn- merge-source-count
  [state]
  (count (get-in state [:dmx :merge :ports merge-port-address :sources])))

(defn step* [state msg] (logic-step state base-config msg))

(defn- host-string
  [host]
  (cond (instance? InetAddress host) (.getHostAddress ^InetAddress host)
        (string? host) host
        (nil? host) nil
        :else (str host)))

(defn- target-id [{:keys [host port]}] [(host-string host) (int (or port 0))])

(defn- artpoll-reply-targets
  [actions]
  (->> actions
       (filter #(= :send (:type %)))
       (filter #(= :artpollreply (get-in % [:packet :op])))
       (map (comp target-id :target))
       set))

(defn- artpoll-message
  [sender packet-overrides]
  {:type   :rx
   :sender sender
   :packet (merge {:op               :artpoll
                   :flags            0
                   :talk-to-me       0
                   :reply-on-change? false
                   :diag-priority    (diagnostics/priority-code :dp-low)
                   :diag-request?    false
                   :diag-unicast?    false}
                  packet-overrides)})

(def default-good-output-b
  (-> 0
      (bit-or state/good-outputb-rdm-disabled-bit)
      (bit-or state/good-outputb-continuous-bit)
      (bit-or state/good-outputb-discovery-idle-bit)
      (bit-or state/good-outputb-background-disabled-bit)))

(defn- bytes->octets [^bytes arr] (mapv #(bit-and 0xFF %) arr))

(defn- encode-artpollreply-octets
  [packet]
  (let [buf (dispatch/encode packet
                             (ByteBuffer/allocate const/artpollreply-length))
        arr (byte-array (.remaining buf))]
    (.get buf arr)
    (bytes->octets arr)))

(deftest status-bit-automation-derives-callback-capabilities
  (let [state (lifecycle/initial-state {:callbacks {:rdm (constantly nil)}})
        node (:node state)
        expected-status2 (-> 0
                             (bit-or state/status2-extended-port-bit)
                             (bit-or state/status2-output-style-bit)
                             (bit-or state/status2-dhcp-capable-bit))
        expected-status3 state/status3-port-direction-bit]
    (is (= expected-status2 (:status2 node)))
    (is (= expected-status3 (:status3 node)))
    (is (zero? (bit-and (:status2 node) state/status2-rdm-artaddress-bit)))
    (is (pos? (bit-and (:status2 node) state/status2-dhcp-capable-bit)))
    (is (zero? (bit-and (:status3 node)
                        (bit-or state/status3-programmable-failsafe-bit
                                state/status3-llrp-bit
                                state/status3-background-queue-bit))))))

(deftest status-bit-automation-preserves-user-node-values
  (let [state (lifecycle/initial-state {:node      {:status2 0xAA, :status3 0x55}
                                        :callbacks {:rdm (constantly nil)}})
        node (:node state)]
    (is (= 0xAA (:status2 node)))
    (is (= 0x55 (:status3 node)))))

(deftest status-bit-capability-overrides-adjust-derived-values
  (let [state (lifecycle/initial-state {:callbacks {:dmx (constantly nil)}
                                        :capabilities
                                        {:status2 {:set   [:dhcp-active]
                                                   :clear [:extended-port]}
                                         :status3 {:override 0xA5}}})
        node (:node state)
        status2 (:status2 node)
        status3 (:status3 node)
        caps (:capabilities state)]
    (is (= (bit-or state/status2-output-style-bit
                   state/status2-dhcp-capable-bit
                   state/status2-dhcp-active-bit)
           status2))
    (is (= 0xA5 status3))
    (is (= {:status2 {:set   state/status2-dhcp-active-bit
                      :clear state/status2-extended-port-bit}
            :status3 {:override 0xA5}}
           caps))))

(deftest background-queue-support-advertises-status-bit
  (let [config (assoc base-config :rdm {:background {:supported? true}})
        state (lifecycle/initial-state config)
        status3 (get-in state [:node :status3])
        queue (get-in state [:rdm :background-queue])]
    (is (pos? (bit-and status3 state/status3-background-queue-bit)))
    (is (= 0 (get-in state [:node :background-queue-policy])))
    (is (= 0 (:policy queue)))
    (is (= :none (:severity queue)))))

(deftest background-queue-tick-dispatches-callback
  (let [config (assoc base-config
                 :rdm {:background {:supported? true, :poll-interval-ms 1}}
                 :callbacks {:rdm (fn [_payload])})
        state (lifecycle/initial-state config)
        [next actions] (logic-step state config {:type :tick, :now 0})
        callback (first (filter #(= :callback (:type %)) actions))
        [_ later-actions] (logic-step next config {:type :tick, :now 1})
        later-callback (some #(when (= :callback (:type %)) %) later-actions)]
    (is callback)
    (is (= :background-queue (get-in callback [:payload :event])))
    (is (= [:status-none]
           (get-in callback [:payload :background-queue :requested-pids])))
    (is (nil? later-callback)
        "No callback once queue no longer requests work")))

(deftest tod-control-flush-triggers-discovery-events
  (let [sender {:host (InetAddress/getByName "10.0.0.10"), :port 6454}
        config (assoc base-config
                 :callbacks {:rdm (fn [_])}
                 :rdm {:ports     {0x0001 {:uids []}}
                       :discovery {:batch-size       1
                                   :step-delay-ms    1
                                   :initial-delay-ms 0}})
        packet {:op :arttodcontrol, :net 0, :command 0x01, :address 0x01}]
    (with-redefs [rdm.discovery/now-ns (constantly 0)]
      (let [[state actions]
            (logic-step nil config {:type :rx, :sender sender, :packet packet})]
        (is (= 1 (count actions)))
        (let [[next actions']
              (logic-step state config {:type :tick, :now 2000000})
              callback (some #(when (= :callback (:type %)) %) actions')]
          (is next)
          (is callback)
          (is (= :discovery (get-in callback [:payload :event])))
          (is (= :full (get-in callback [:payload :discovery :mode])))
          (is (= [0x0001] (get-in callback [:payload :discovery :ports])))
          (is (seq (get-in callback [:payload :discovery :targets]))))))))

(deftest initial-state-syncs-background-queue-policy
  (let [config
        (assoc base-config :rdm {:background {:supported? true, :policy 3}})
        state (lifecycle/initial-state config)]
    (is (= 3 (get-in state [:node :background-queue-policy])))
    (is (= 3 (get-in state [:rdm :background-queue :policy])))))

(deftest apply-state-updates-sync-config
  (testing "top-level sync overrides"
    (let [state (lifecycle/initial-state base-config)
          ttl-ms 300
          msg {:type    :command
               :command :apply-state
               :state   {:sync {:mode :art-sync, :buffer-ttl-ms ttl-ms}}}
          [next _] (logic-step state base-config msg)
          expected-ttl (long (* ttl-ms 1000000))]
      (is (= :art-sync (get-in next [:dmx :sync :mode])))
      (is (= expected-ttl (get-in next [:dmx :sync :buffer-ttl-ns])))))
  (testing "nested dmx sync overrides"
    (let [state (lifecycle/initial-state base-config)
          msg {:type    :command
               :command :apply-state
               :state   {:dmx {:sync {:mode :art-sync}}}}
          [next _] (logic-step state base-config msg)]
      (is (= :art-sync (get-in next [:dmx :sync :mode]))))))

(deftest apply-state-disables-art-sync-clears-runtime
  (let [config (assoc base-config :sync {:mode :art-sync})
        sender {:host (InetAddress/getByName "192.168.70.10"), :port 6454}
        packet (artdmx-packet [1 2 3])
        release! (fn [actions]
                   (doseq [rel (keep #(when (= :release (:type %)) (:release %))
                                     actions)]
                     (when rel (rel))))
        [state actions]
        (logic-step nil config {:type :rx, :packet packet, :sender sender})]
    (release! actions)
    (is (seq (get-in state [:dmx :sync-buffer]))
        "Expect staged frames when ArtSync active")
    (let [msg {:type    :command
               :command :apply-state
               :state   {:sync {:mode :immediate}}}
          [next _] (logic-step state config msg)]
      (is (= :immediate (get-in next [:dmx :sync :mode])))
      (is (nil? (get-in next [:dmx :sync :active-mode])))
      (is (nil? (get-in next [:dmx :sync :last-sync-at])))
      (is (nil? (get-in next [:dmx :sync :waiting-since])))
      (is (empty? (get-in next [:dmx :sync-buffer]))))))

(deftest apply-state-updates-background-queue-policy
  (let [config (assoc base-config :rdm {:background {:supported? true}})
        state (lifecycle/initial-state config)
        msg {:type    :command
             :command :apply-state
             :state   {:node {:background-queue-policy 2}}}
        [next _] (logic-step state config msg)]
    (is (= 2 (get-in next [:node :background-queue-policy])))
    (is (= 2 (get-in next [:rdm :background-queue :policy])))))

(deftest apply-state-background-queue-policy-persists-when-unsupported
  (let [state (lifecycle/initial-state base-config)
        msg {:type    :command
             :command :apply-state
             :state   {:node {:background-queue-policy 5}}}
        [next _] (logic-step state base-config msg)]
    (is (= 5 (get-in next [:node :background-queue-policy])))
    (is (nil? (get-in next [:rdm :background-queue :policy])))))

(deftest apply-state-updates-failsafe-config
  (testing "top-level failsafe overrides"
    (let [state (lifecycle/initial-state base-config)
          timeout-ms 250
          msg {:type    :command
               :command :apply-state
               :state   {:failsafe {:enabled?        false
                                    :idle-timeout-ms timeout-ms}}}
          [next _] (logic-step state base-config msg)
          expected-timeout (long (* timeout-ms 1000000))]
      (is (false? (get-in next [:dmx :failsafe :config :enabled?])))
      (is (= expected-timeout
             (get-in next [:dmx :failsafe :config :idle-timeout-ns])))))
  (testing "nested dmx failsafe overrides"
    (let [state (lifecycle/initial-state base-config)
          msg {:type    :command
               :command :apply-state
               :state   {:dmx {:failsafe {:enabled? true, :tick-interval-ms 50}}}}
          [next _] (logic-step state base-config msg)]
      (is (true? (get-in next [:dmx :failsafe :config :enabled?])))
      (is (= (long (* 50 1000000))
             (get-in next [:dmx :failsafe :config :tick-interval-ns]))))))

(deftest default-good-output-b-marks-capabilities
  (let [node (:node (lifecycle/initial-state base-config))
        values (:good-output-b node)
        expected default-good-output-b]
    (is (= 4 (count values)))
    (is (every? #(= expected %) values))
    (is (every? #(pos? (bit-and % state/good-outputb-rdm-disabled-bit)) values))
    (is (every? #(pos? (bit-and % state/good-outputb-continuous-bit)) values))
    (is (every? #(pos? (bit-and % state/good-outputb-discovery-idle-bit))
                values))
    (is (every? #(pos? (bit-and % state/good-outputb-background-disabled-bit))
                values))))

(deftest good-output-b-discovery-bit-follows-queue
  (let [state (lifecycle/initial-state base-config)
        idle-values (get-in state [:node :good-output-b])
        running (assoc-in state
                          [:rdm :discovery :queue]
                          [{:mode :full, :ports [(common/compose-port-address 0 0 0)]}])
        synced (#'lifecycle/sync-discovery-good-output-b running)
        active-values (get-in synced [:node :good-output-b])]
    (is (every? #(pos? (bit-and % state/good-outputb-discovery-idle-bit))
                idle-values))
    (is (every? #(zero? (bit-and % state/good-outputb-discovery-idle-bit))
                active-values))))

(deftest good-output-b-background-bit-follows-support
  (let [supported-config
        (assoc base-config :rdm {:background {:supported? true}})
        supported-state (lifecycle/initial-state supported-config)
        supported-values (get-in supported-state [:node :good-output-b])
        disabled-config
        (assoc base-config :rdm {:background {:supported? true, :policy 4}})
        disabled-state (lifecycle/initial-state disabled-config)
        disabled-values (get-in disabled-state [:node :good-output-b])]
    (is (every? #(zero? (bit-and % state/good-outputb-background-disabled-bit))
                supported-values))
    (is (every? #(pos? (bit-and % state/good-outputb-background-disabled-bit))
                disabled-values))))

(deftest status-bit-refreshes-when-callbacks-change
  (let [config (assoc base-config :callbacks {:rdm (constantly nil)})
        state (lifecycle/initial-state config)
        initial-status2 (get-in state [:node :status2])
        initial-status3 (get-in state [:node :status3])
        msg {:type :command, :command :apply-state, :state {:callbacks {}}}
        [next _] (logic-step state config msg)
        status2 (get-in next [:node :status2])
        status3 (get-in next [:node :status3])]
    (is (= initial-status2 status2))
    (is (= initial-status3 status3))
    (is (zero? (bit-and status2 state/status2-rdm-artaddress-bit)))
    (is (zero? (bit-and status3 state/status3-llrp-bit)))
    (is (zero? (bit-and status3 state/status3-background-queue-bit)))))

(deftest status-bit-refreshes-when-network-dhcp-changes
  (let [state (lifecycle/initial-state base-config)
        enable
        {:type :command, :command :apply-state, :state {:network {:dhcp? true}}}
        [dhcp-state _] (logic-step state base-config enable)
        status2 (get-in dhcp-state [:node :status2])]
    (is (pos? (bit-and status2 state/status2-dhcp-active-bit)))
    (let [disable {:type    :command
                   :command :apply-state
                   :state   {:network {:dhcp? false}}}
          [final _] (logic-step dhcp-state base-config disable)
          status2' (get-in final [:node :status2])]
      (is (zero? (bit-and status2' state/status2-dhcp-active-bit))))))

(deftest artpollreply-ports-config-builds-fixture-pages
  (is (= fixtures/artpollreply-pages
         (state/node-port-pages fixtures/artpollreply-node-config))))
(deftest artpollreply-logic-produces-fixture-packets
  (let [sender {:host (InetAddress/getByName "192.168.0.99"), :port 6454}
        poll
        {:type   :rx
         :sender sender
         :packet
         {:op :artpoll, :flags 0, :diag-request? false, :diag-unicast? false}}
        [_ actions] (logic-step nil artpollreply-config poll)
        packets (map :packet actions)]
    (is (= (count fixtures/artpollreply-pages) (count actions)))
    (is (every? #(= :send (:type %)) actions))
    (is (= (map #(assoc % :op :artpollreply) fixtures/artpollreply-pages)
           packets))))

(deftest artpollreply-logic-encodes-fixture-bytes
  (let [sender {:host (InetAddress/getByName "192.168.0.99"), :port 6454}
        poll
        {:type   :rx
         :sender sender
         :packet
         {:op :artpoll, :flags 0, :diag-request? false, :diag-unicast? false}}
        [_ actions] (logic-step nil artpollreply-config poll)
        encoded (mapv #(encode-artpollreply-octets (:packet %)) actions)
        expected (mapv #(bytes->octets (builders/build-artpollreply-bytes %))
                       fixtures/artpollreply-pages)]
    (is (= expected encoded))))

(deftest artpollreply-runtime-ports-shrink-clears-fields
  (let [initial (lifecycle/initial-state artpollreply-config)
        shrink
        {:type :command, :command :apply-state, :state {:node {:ports []}}}
        [shrunk _] (logic-step initial artpollreply-config shrink)
        sender {:host (InetAddress/getByName "192.168.0.100"), :port 6454}
        poll (artpoll-message sender {})
        [_ actions] (logic-step shrunk artpollreply-config poll)
        reply (get-in actions [0 :packet])]
    (is (= 1 (count actions)))
    (is (= :send (:type (first actions))))
    (is (= 3 (:bind-index reply)))
    (is (= 0 (:num-ports reply)))
    (is (= [0 0 0 0] (:port-types reply)))
    (is (= [0 0 0 0] (:good-input reply)))
    (is (= (vec (repeat 4 default-good-output-b)) (:good-output-b reply)))
    (is (= [0 0 0 0] (:sw-in reply)))
    (is (= [0 0 0 0] (:sw-out reply)))))

(deftest artpollreply-runtime-ports-expand-restores-pagination
  (let [initial (lifecycle/initial-state artpollreply-config)
        shrink
        {:type :command, :command :apply-state, :state {:node {:ports []}}}
        [shrunk _] (logic-step initial artpollreply-config shrink)
        subset (vec (take 3 fixtures/artpollreply-port-descriptors))
        grow
        {:type :command, :command :apply-state, :state {:node {:ports subset}}}
        [subset-ready _] (logic-step shrunk artpollreply-config grow)
        sender {:host (InetAddress/getByName "192.168.0.101"), :port 6454}
        poll (artpoll-message sender {})
        [subset-state subset-actions]
        (logic-step subset-ready artpollreply-config poll)
        subset-reply (get-in subset-actions [0 :packet])]
    (is (= 1 (count subset-actions)))
    (is (= 3 (:bind-index subset-reply)))
    (is (= 3 (:num-ports subset-reply)))
    (is (= [0xC0 0x80 0x90 0x00] (:port-types subset-reply)))
    (is (= [0xBA 0xBB 0xFC default-good-output-b]
           (:good-output-b subset-reply)))
    (let [restore {:type    :command
                   :command :apply-state
                   :state   {:node {:ports
                                    fixtures/artpollreply-port-descriptors}}}
          [restored _] (logic-step subset-state artpollreply-config restore)
          [_ restored-actions] (logic-step restored artpollreply-config poll)
          packets (map :packet restored-actions)]
      (is (= (map #(assoc % :op :artpollreply) fixtures/artpollreply-pages)
             packets)))))

(deftest artpoll-targeted-mode-controls-replies
  (let [sender {:host (InetAddress/getByName "192.168.0.10"), :port 6454}
        miss {:type   :rx
              :sender sender
              :packet {:op              :artpoll
                       :flags           0x20
                       :target-enabled? true
                       :target-top      0x0004
                       :target-bottom   0x0004
                       :diag-priority   0x10
                       :diag-request?   false
                       :diag-unicast?   false}}
        [state-no-reply actions] (step* nil miss)]
    (is (empty? actions))
    (let [hit {:type   :rx
               :sender sender
               :packet {:op              :artpoll
                        :flags           0x20
                        :target-enabled? true
                        :target-top      0x0001
                        :target-bottom   0x0001
                        :diag-priority   0x10
                        :diag-request?   false
                        :diag-unicast?   false}}
          [_ actions2] (step* state-no-reply hit)
          reply (first actions2)]
      (is (= 1 (count actions2)))
      (is (= :send (:type reply)))
      (is (= sender (:target reply)))
      (is (= 0 (:delay-ms reply))))))

(deftest artpoll-multi-bind-sends-each-page
  (let [sender {:host (InetAddress/getByName "192.168.0.50"), :port 6454}
        poll
        {:type   :rx
         :sender sender
         :packet
         {:op :artpoll, :flags 0, :diag-request? false, :diag-unicast? false}}
        [_ actions] (logic-step nil multi-bind-config poll)
        bind-indices (map #(get-in % [:packet :bind-index]) actions)]
    (is (= 2 (count actions)))
    (is (= #{1 2} (set bind-indices)))))

(deftest artpoll-targeted-mode-filters-pages
  (let [sender {:host (InetAddress/getByName "192.168.0.51"), :port 6454}
        poll {:type   :rx
              :sender sender
              :packet {:op              :artpoll
                       :flags           0x20
                       :target-enabled? true
                       :target-top      0x0002
                       :target-bottom   0x0002
                       :diag-request?   false
                       :diag-unicast?   false}}
        [_ actions] (logic-step nil multi-bind-config poll)
        reply (first actions)]
    (is (= 1 (count actions)))
    (is (= 2 (get-in reply [:packet :bind-index])))))

(deftest artpoll-ports-config-builds-pages-automatically
  (let [sender {:host (InetAddress/getByName "192.168.0.52"), :port 6454}
        poll
        {:type   :rx
         :sender sender
         :packet
         {:op :artpoll, :flags 0, :diag-request? false, :diag-unicast? false}}
        [_ actions] (logic-step nil auto-ports-config poll)
        bind-indices (map #(get-in % [:packet :bind-index]) actions)
        address-groups (map #(get-in % [:packet :port-addresses]) actions)]
    (is (= 3 (count actions)))
    (is (= [1 2 3] bind-indices))
    (is (= [[(common/compose-port-address 0 0 1)
             (common/compose-port-address 0 0 2)]
            [(common/compose-port-address 0 1 1)
             (common/compose-port-address 0 1 2)]
            [(common/compose-port-address 1 0 1)]]
           address-groups))))

(deftest targeted-mode-works-with-ports-config
  (let [sender {:host (InetAddress/getByName "192.168.0.53"), :port 6454}
        target (common/compose-port-address 1 0 1)
        poll {:type   :rx
              :sender sender
              :packet {:op              :artpoll
                       :flags           0x20
                       :target-enabled? true
                       :target-top      target
                       :target-bottom   target
                       :diag-request?   false
                       :diag-unicast?   false}}
        [_ actions] (logic-step nil auto-ports-config poll)
        reply (first actions)]
    (is (= 1 (count actions)))
    (is (= 3 (get-in reply [:packet :bind-index])))
    (is (= [target] (get-in reply [:packet :port-addresses])))))

(deftest talk-to-me-reply-on-change-triggers-updates
  (let [sender {:host (InetAddress/getByName "192.168.0.20"), :port 6454}
        poll {:type   :rx
              :sender sender
              :packet {:op               :artpoll
                       :flags            0x02
                       :talk-to-me       0x02
                       :reply-on-change? true
                       :diag-priority    0x10
                       :diag-request?    false
                       :diag-unicast?    false}}
        [state _] (step* nil poll)
        change {:type    :command
                :command :apply-state
                :state   {:node {:short-name "auto"}}}
        [_ actions] (step* state change)
        reply (first actions)]
    (is (= 1 (count actions)))
    (is (= :artpollreply (get-in reply [:packet :op])))
    (is (= sender (:target reply)))))

(deftest talk-to-me-only-notifies-subscribers
  (let [c1 {:host (InetAddress/getByName "192.168.0.30"), :port 6454}
        c2 {:host (InetAddress/getByName "192.168.0.31"), :port 6454}
        poll-rxc {:type   :rx
                  :packet {:op               :artpoll
                           :flags            0x02
                           :talk-to-me       0x02
                           :reply-on-change? true
                           :diag-priority    0x10
                           :diag-request?    false
                           :diag-unicast?    false}}
        poll-passive {:type   :rx
                      :packet {:op               :artpoll
                               :flags            0x00
                               :talk-to-me       0x00
                               :reply-on-change? false
                               :diag-priority    0x10
                               :diag-request?    false
                               :diag-unicast?    false}}
        [state _] (step* nil (assoc poll-rxc :sender c1))
        [state _] (step* state (assoc poll-passive :sender c2))
        change {:type    :command
                :command :apply-state
                :state   {:node {:long-name "change"}}}
        [_ actions] (step* state change)
        reply (first actions)]
    (is (= 1 (count actions)))
    (is (= c1 (:target reply)))))

(deftest talk-to-me-reply-on-change-sends-all-pages
  (let [sender {:host (InetAddress/getByName "192.168.0.98"), :port 6454}
        poll {:type   :rx
              :sender sender
              :packet {:op               :artpoll
                       :flags            0x02
                       :talk-to-me       0x02
                       :reply-on-change? true
                       :diag-priority    0x10
                       :diag-request?    false
                       :diag-unicast?    false}}
        [state _] (logic-step nil artpollreply-config poll)
        change {:type    :command
                :command :apply-state
                :state   {:node {:long-name "updated"}}}
        [_ actions] (logic-step state artpollreply-config change)
        packets (map :packet actions)
        expected (map #(-> %
                           (assoc :op :artpollreply)
                           (assoc :long-name "updated"))
                      fixtures/artpollreply-pages)]
    (is (= (count fixtures/artpollreply-pages) (count actions)))
    (is (every? #(= :send (:type %)) actions))
    (is (every? #(= sender (:target %)) actions))
    (is (= expected packets))))

(deftest talk-to-me-bit-controls-random-delay
  (let [sender {:host (InetAddress/getByName "192.168.0.40"), :port 6454}
        config (assoc base-config :random-delay-fn (constantly 1500))
        poll {:type   :rx
              :sender sender
              :packet {:op            :artpoll
                       :flags         0x00
                       :talk-to-me    0x00
                       :diag-request? false
                       :diag-unicast? false}}
        [state actions] (logic-step nil config poll)
        delayed (first actions)
        fast {:type   :rx
              :sender sender
              :packet {:op            :artpoll
                       :flags         0x01
                       :talk-to-me    0x01
                       :diag-request? false
                       :diag-unicast? false}}
        [_ fast-actions] (logic-step state config fast)
        immediate (first fast-actions)]
    (is (= 1000 (:delay-ms delayed)))
    (is (= 0 (:delay-ms immediate)))))

(deftest targeted-talk-to-me-only-subscribes-when-address-matches
  (let [config artpollreply-config
        addresses (->> fixtures/artpollreply-pages
                       (mapcat :port-addresses)
                       (remove nil?)
                       set)
        valid-target (or (first addresses) (common/compose-port-address 0 0 1))
        invalid-target (loop [candidate (inc valid-target)]
                         (if (contains? addresses candidate)
                           (recur (inc candidate))
                           candidate))
        miss-sender {:host (InetAddress/getByName "192.168.0.77"), :port 6454}
        hit-sender {:host (InetAddress/getByName "192.168.0.78"), :port 6454}
        targeted-poll (fn [sender target]
                        (artpoll-message sender
                                         {:flags            0x22
                                          :talk-to-me       0x02
                                          :reply-on-change? true
                                          :target-enabled?  true
                                          :target-top       target
                                          :target-bottom    target
                                          :diag-priority    0x10
                                          :diag-request?    false
                                          :diag-unicast?    false}))
        change (fn [state label]
                 (logic-step state
                             config
                             {:type    :command
                              :command :apply-state
                              :state   {:node {:long-name label}}}))
        [state _]
        (logic-step nil config (targeted-poll miss-sender invalid-target))
        [state miss-actions] (change state "target-miss")
        [state _]
        (logic-step state config (targeted-poll hit-sender valid-target))
        [_ hit-actions] (change state "target-hit")]
    (is (empty? (artpoll-reply-targets miss-actions)))
    (is (= #{(target-id hit-sender)} (artpoll-reply-targets hit-actions)))))

(deftest controller-default-poll-clears-reply-on-change
  (let [sender {:host (InetAddress/getByName "192.168.0.79"), :port 6454}
        subscribe (artpoll-message sender
                                   {:flags            0x02
                                    :talk-to-me       0x02
                                    :reply-on-change? true
                                    :diag-priority    0x10
                                    :diag-request?    false
                                    :diag-unicast?    false})
        default-poll (artpoll-message sender
                                      {:flags            0x00
                                       :talk-to-me       0x00
                                       :reply-on-change? false
                                       :diag-priority    0x10
                                       :diag-request?    false
                                       :diag-unicast?    false})
        change (fn [state label]
                 (logic-step state
                             base-config
                             {:type    :command
                              :command :apply-state
                              :state   {:node {:long-name label}}}))
        [state _] (logic-step nil base-config subscribe)
        [state subscribed-actions] (change state "default-a")
        [state _] (logic-step state base-config default-poll)
        [_ after-actions] (change state "default-b")]
    (is (= #{(target-id sender)} (artpoll-reply-targets subscribed-actions)))
    (is (empty? (artpoll-reply-targets after-actions)))))

(deftest reply-on-change-churn-respects-limit-during-default-polls
  (let [config (assoc base-config
                 :discovery
                 {:reply-on-change-limit  2
                  :reply-on-change-policy :prefer-latest})
        c1 {:host (InetAddress/getByName "192.168.0.80"), :port 6454}
        c2 {:host (InetAddress/getByName "192.168.0.81"), :port 6454}
        c3 {:host (InetAddress/getByName "192.168.0.82"), :port 6454}
        c4 {:host (InetAddress/getByName "192.168.0.83"), :port 6454}
        subscribe (fn [state sender]
                    (logic-step state
                                config
                                (artpoll-message sender
                                                 {:flags            0x02
                                                  :talk-to-me       0x02
                                                  :reply-on-change? true
                                                  :diag-priority    0x10
                                                  :diag-request?    false
                                                  :diag-unicast?    false})))
        default-poll (fn [state sender]
                       (logic-step state
                                   config
                                   (artpoll-message sender
                                                    {:flags            0x00
                                                     :talk-to-me       0x00
                                                     :reply-on-change? false
                                                     :diag-priority    0x10
                                                     :diag-request?    false
                                                     :diag-unicast?    false})))
        announce (fn [state label]
                   (logic-step state
                               config
                               {:type    :command
                                :command :apply-state
                                :state   {:node {:long-name label}}}))
        [state _] (subscribe nil c1)
        [state _] (subscribe state c2)
        [state _] (subscribe state c3)
        [state first-actions] (announce state "churn-a")
        [state _] (default-poll state c2)
        [state _] (subscribe state c4)
        [_ second-actions] (announce state "churn-b")
        first-targets (artpoll-reply-targets first-actions)
        second-targets (artpoll-reply-targets second-actions)]
    (is (= #{(target-id c2) (target-id c3)} first-targets))
    (is (= #{(target-id c3) (target-id c4)} second-targets))))

(deftest reply-on-change-limit-prefers-existing-when-configured
  (let [config (assoc base-config
                 :discovery
                 {:reply-on-change-limit  1
                  :reply-on-change-policy :prefer-existing})
        c1 {:host (InetAddress/getByName "192.168.0.70"), :port 6454}
        c2 {:host (InetAddress/getByName "192.168.0.71"), :port 6454}
        poll (fn [sender]
               {:type   :rx
                :sender sender
                :packet {:op               :artpoll
                         :flags            0x02
                         :talk-to-me       0x02
                         :reply-on-change? true
                         :diag-priority    0x10
                         :diag-request?    false
                         :diag-unicast?    false}})
        [state _] (logic-step nil config (poll c1))
        [state _] (logic-step state config (poll c2))
        change {:type    :command
                :command :apply-state
                :state   {:node {:short-name "prefers-existing"}}}
        [_ actions] (logic-step state config change)
        replies (filter #(and (= :send (:type %))
                              (= :artpollreply (get-in % [:packet :op])))
                        actions)]
    (is (= 1 (count replies)))
    (is (= c1 (:target (first replies))))))

(deftest reply-on-change-limit-can-prefer-latest
  (let [config (assoc base-config
                 :discovery
                 {:reply-on-change-limit  1
                  :reply-on-change-policy :prefer-latest})
        c1 {:host (InetAddress/getByName "192.168.0.72"), :port 6454}
        c2 {:host (InetAddress/getByName "192.168.0.73"), :port 6454}
        poll (fn [sender]
               {:type   :rx
                :sender sender
                :packet {:op               :artpoll
                         :flags            0x02
                         :talk-to-me       0x02
                         :reply-on-change? true
                         :diag-priority    0x10
                         :diag-request?    false
                         :diag-unicast?    false}})
        [state _] (logic-step nil config (poll c1))
        [state _] (logic-step state config (poll c2))
        change {:type    :command
                :command :apply-state
                :state   {:node {:long-name "prefers-latest"}}}
        [_ actions] (logic-step state config change)
        replies (filter #(and (= :send (:type %))
                              (= :artpollreply (get-in % [:packet :op])))
                        actions)]
    (is (= 1 (count replies)))
    (is (= c2 (:target (first replies))))))

(deftest artdmx-immediate-mode-returns-safe-payloads
  (let [packet (artdmx-packet [1 2 3 4])
        sender {:host (InetAddress/getByName "192.168.50.10"), :port 6454}
        config (assoc base-config :callbacks {:dmx (fn [_])})
        released (promise)
        [_ actions] (logic-step nil
                                config
                                {:type    :rx
                                 :packet  packet
                                 :sender  sender
                                 :release #(deliver released true)})
        callback (some #(when (= :callback (:type %)) %) actions)
        release-action (some #(when (= :release (:type %)) %) actions)
        safe (:packet (:payload callback))
        view (types/payload-buffer safe)
        bytes (byte-array (.remaining view))]
    (.get view bytes)
    (when-let [f (:release release-action)] (f))
    (let [view2 (types/payload-buffer safe)
          bytes2 (byte-array (.remaining view2))]
      (.get view2 bytes2)
      (is (= (seq bytes) (seq bytes2))))
    (is (true? (deref released 1000 false)))))

(deftest artdmx-art-sync-mode-flushes-on-artsync
  (let [packet (artdmx-packet [5 6 7])
        sender {:host (InetAddress/getByName "192.168.60.10"), :port 6454}
        config (assoc base-config
                 :callbacks {:dmx (fn [_]), :sync (fn [_])}
                 :sync {:mode :art-sync})
        [state actions]
        (logic-step
          nil
          config
          {:type :rx, :packet packet, :sender sender, :release (fn [] nil)})]
    (is (= 1 (count (get-in state [:dmx :sync-buffer]))))
    (let [release-action (some #(when (= :release (:type %)) %) actions)]
      (when-let [f (:release release-action)] (f)))
    (is (nil? (some #(= :callback (:type %)) actions)))
    (let [[_ actions2] (logic-step
                         state
                         config
                         {:type :rx, :packet {:op :artsync}, :sender sender})
          callbacks (filter #(= :callback (:type %)) actions2)
          dmx-action (some #(when (= :artdmx (:op (:packet (:payload %)))) %)
                           callbacks)
          sync-action (some #(when (= :artsync (:op (:packet (:payload %)))) %)
                            callbacks)]
      (is (= :artdmx (:op (:packet (:payload dmx-action)))))
      (is (true? (get-in dmx-action [:payload :synced?])))
      (is sync-action))))

(deftest artdmx-art-sync-timeout-falls-back-to-immediate
  (let [packet (artdmx-packet [9 8 7])
        sender {:host (InetAddress/getByName "192.168.60.12"), :port 6454}
        config
        (assoc base-config :callbacks {:dmx (fn [_])} :sync {:mode :art-sync})
        release! (fn [actions]
                   (when-let [rel (some #(when (= :release (:type %)) %)
                                        actions)]
                     (when-let [f (:release rel)] (f))))
        clock (atom 0)
        advance! (fn [delta] (swap! clock + delta))
        msg (fn []
              {:type :rx, :packet packet, :sender sender, :timestamp @clock})
        [state actions] (logic-step nil config (msg))]
    (release! actions)
    (is (= 1 (count (get-in state [:dmx :sync-buffer]))))
    (advance! (inc dmx/artsync-timeout-ns))
    (let [[state' actions'] (logic-step state config (msg))
          callback (some #(when (= :callback (:type %)) %) actions')]
      (release! actions')
      (is callback)
      (is (nil? (get-in callback [:payload :synced?])))
      (is (= :immediate (get-in state' [:dmx :sync :active-mode])))
      (is (nil? (get-in state' [:dmx :sync :last-sync-at])))
      (is (empty? (get-in state' [:dmx :sync-buffer]))))))

(deftest artsync-activation-records-last-sync-time
  (let [config (assoc base-config :sync {:mode :art-sync})
        sender {:host (InetAddress/getByName "192.168.60.15"), :port 6454}
        clock (atom 123456789)
        msg
        {:type :rx, :packet {:op :artsync}, :sender sender, :timestamp @clock}
        [state actions] (logic-step nil config msg)]
    (is (empty? actions))
    (is (= :art-sync (get-in state [:dmx :sync :active-mode])))
    (is (= @clock (get-in state [:dmx :sync :last-sync-at])))
    (is (empty? (get-in state [:dmx :sync-buffer])))))

(deftest artsync-drops-stale-staged-frames
  (let [sender {:host (InetAddress/getByName "192.168.60.16"), :port 6454}
        ttl (long (* 50 1000000))
        config (assoc base-config
                 :callbacks {:dmx (fn [_])}
                 :sync {:mode :art-sync, :buffer-ttl-ns ttl})
        clock (atom 0)
        advance! (fn [delta] (swap! clock + delta))
        dmx-msg (fn []
                  {:type      :rx
                   :sender    sender
                   :packet    (artdmx-packet [1 2 3])
                   :timestamp @clock})
        [state _] (logic-step nil config (dmx-msg))]
    (is (= 1 (count (get-in state [:dmx :sync-buffer]))))
    (advance! (+ ttl 1000))
    (let [[state' actions] (logic-step state
                                       config
                                       {:type      :rx
                                        :sender    sender
                                        :packet    {:op :artsync}
                                        :timestamp @clock})
          callbacks (filter #(= :callback (:type %)) actions)]
      (is (empty? callbacks))
      (is (empty? (get-in state' [:dmx :sync-buffer])))
      (is (nil? (get-in state' [:dmx :sync :waiting-since])))
      (is (= :art-sync (get-in state' [:dmx :sync :active-mode])))
      (is (= 1 (get-in state' [:stats :rx-artsync]))))))

(deftest artnzs-trigger-dmx-callback
  (let [packet (artnzs-packet 0x17 [9 8 7])
        sender {:host (InetAddress/getByName "192.168.60.20"), :port 6454}
        config (assoc base-config :callbacks {:dmx (fn [_])})
        [state actions]
        (logic-step nil config {:type :rx, :packet packet, :sender sender})
        callback (some #(when (= :callback (:type %)) %) actions)
        safe (:packet (:payload callback))
        view (types/payload-buffer safe)
        bytes (byte-array (.remaining view))]
    (.get view bytes)
    (is (some? callback))
    (is (= :artnzs (:op safe)))
    (is (= 0x17 (:start-code safe)))
    (is (= [9 8 7] (map #(bit-and 0xFF %) bytes)))
    (is (= 1 (get-in state [:stats :rx-artnzs])))))

(deftest artvlc-triggers-callback-and-stats
  (let [packet (artvlc-packet [1 2 3 4])
        sender {:host (InetAddress/getByName "192.168.60.30"), :port 6454}
        config (assoc base-config :callbacks {:dmx (fn [_])})
        [state actions]
        (logic-step nil config {:type :rx, :packet packet, :sender sender})
        callback (some #(when (= :callback (:type %)) %) actions)
        safe (:packet (:payload callback))
        vlc (:vlc safe)
        view (:payload vlc)
        bytes (byte-array (.remaining view))]
    (.get view bytes)
    (is (some? callback))
    (is (= :artvlc (:op safe)))
    (is (= const/artvlc-start-code (:start-code safe)))
    (is (= true (:ieee? vlc)))
    (is (= [1 2 3 4] (map #(bit-and 0xFF %) bytes)))
    (is (= 1 (get-in state [:stats :rx-artvlc])))))

(deftest artnzs-throughput-throttles-fast-frames
  (let [packet (artnzs-packet 0x17 [1 2 3])
        sender {:host (InetAddress/getByName "192.168.60.25"), :port 6454}
        refresh-rate 50
        interval (long (/ (double dmx/nanos-per-second) refresh-rate))
        config (-> base-config
                   (assoc :callbacks {:dmx (fn [_])})
                   (assoc-in [:node :refresh-rate] refresh-rate))
        clock (atom 0)
        step-packet
        (fn [state]
          (logic-step
            state
            config
            {:type :rx, :packet packet, :sender sender, :timestamp @clock}))
        [state1 actions1] (step-packet nil)]
    (is (some #(= :callback (:type %)) actions1))
    (let [[state2 actions2] (step-packet state1)]
      (is (empty? (filter #(= :callback (:type %)) actions2)))
      (is (= 1 (get-in state2 [:stats :rx-artnzs-throttled])))
      (is (= 2 (get-in state2 [:stats :rx-artnzs])))
      (swap! clock + interval)
      (let [[_ actions3] (step-packet state2)]
        (is (some #(= :callback (:type %)) actions3))))))

(deftest artinput-updates-good-input-and-stats
  (let [packet (artinput-packet [true false true false])
        sender {:host (InetAddress/getByName "192.168.60.40"), :port 6454}
        [state actions]
        (logic-step nil base-config {:type :rx, :packet packet, :sender sender})
        send-action (some #(when (= :send (:type %)) %) actions)]
    (is (= [true false true false] (:disabled packet)))
    (is (= [true false true false] (get-in state [:inputs :disabled])))
    (is (= {:disabled   [true false true false]
            :good-input [0x10 0x00 0x10 0x00]}
           (get-in state [:inputs :per-page 1])))
    (is (= 1 (get-in state [:inputs :last-bind-index])))
    (is (= 0x10 (get-in state [:node :good-input 0])))
    (is (= 0x00 (get-in state [:node :good-input 1])))
    (is (= 0x10 (get-in state [:node :good-input 2])))
    (is (= 0x00 (get-in state [:node :good-input 3])))
    (is (= 1 (get-in state [:stats :rx-artinput])))
    (is (= :artpollreply (get-in send-action [:packet :op])))))

(deftest artinput-targets-specific-bind-index
  (let [packet (artinput-packet {:disabled   [false true false true]
                                 :bind-index 2})
        sender {:host (InetAddress/getByName "192.168.60.41"), :port 6454}
        [state actions] (logic-step nil
                                    multi-bind-config
                                    {:type :rx, :packet packet, :sender sender})
        send-action (some #(when (= :send (:type %)) %) actions)
        page-entry (get-in state [:inputs :per-page 2])]
    (is (nil? (get-in state [:inputs :per-page 1])))
    (is (= [0 0 0 0] (get-in state [:node :good-input])))
    (is (= {:disabled   [false true false true]
            :good-input [0x00 0x10 0x00 0x10]}
           page-entry))
    (is (= 2 (get-in state [:inputs :last-bind-index])))
    (is (= 2 (get-in send-action [:packet :bind-index])))
    (is (= [0x00 0x10 0x00 0x10] (get-in send-action [:packet :good-input])))))

(deftest artinput-auto-ports-bind-index-selection
  (let [packet (artinput-packet {:disabled   [true true false false]
                                 :bind-index 3})
        sender {:host (InetAddress/getByName "192.168.60.42"), :port 6454}
        [state actions] (logic-step nil
                                    auto-ports-config
                                    {:type :rx, :packet packet, :sender sender})
        send-action (some #(when (= :send (:type %)) %) actions)]
    (is (seq (state/node-port-pages (:node state))))
    (is (= 3 (get-in state [:inputs :last-bind-index])))
    (is (= {:disabled   [true true false false]
            :good-input [0x10 0x10 0x00 0x00]}
           (get-in state [:inputs :per-page 3])))
    (is (= 3 (get-in send-action [:packet :bind-index])))
    (is (= [0x10 0x10 0x00 0x00] (get-in send-action [:packet :good-input])))))

(deftest artinput-num-ports-selects-matching-page
  (let [packet (artinput-packet {:disabled   [true false false false]
                                 :bind-index 0
                                 :num-ports  1})
        sender {:host (InetAddress/getByName "192.168.60.43"), :port 6454}
        [state actions] (logic-step nil
                                    auto-ports-config
                                    {:type :rx, :packet packet, :sender sender})
        send-action (some #(when (= :send (:type %)) %) actions)]
    (is (= 3 (get-in state [:inputs :last-bind-index])))
    (is (= {:disabled   [true false false false]
            :good-input [0x10 0x00 0x00 0x00]}
           (get-in state [:inputs :per-page 3])))
    (is (= 3 (get-in send-action [:packet :bind-index])))
    (is (= [0x10 0x00 0x00 0x00] (get-in send-action [:packet :good-input])))))

(deftest artinput-preserves-existing-good-input-flags
  (let [packet (artinput-packet {:disabled   [false true false false]
                                 :bind-index 2
                                 :num-ports  1})
        sender {:host (InetAddress/getByName "192.168.60.44"), :port 6454}
        [state actions] (logic-step nil
                                    multi-bind-good-input-config
                                    {:type :rx, :packet packet, :sender sender})
        send-action (some #(when (= :send (:type %)) %) actions)
        expected [0x40 0x11 0x02 0x03]]
    (is (= expected (get-in state [:inputs :per-page 2 :good-input])))
    (is (= expected (get-in send-action [:packet :good-input])))))

(deftest arttrigger-matching-oem-dispatches-callback
  (let [cb (promise)
        config (-> base-config
                   (assoc-in [:node :oem] 0x2222)
                   (assoc :callbacks {:trigger (fn [ctx] (deliver cb ctx))}))
        packet (arttrigger-packet {:oem      0x2222
                                   :key-type :key-show
                                   :sub-key  0x05
                                   :data     (byte-array [0x42])})
        sender {:host (InetAddress/getByName "192.168.60.41"), :port 6454}
        [state actions]
        (logic-step nil config {:type :rx, :packet packet, :sender sender})
        diag (some #(when (= :artdiagdata (get-in % [:packet :op])) %) actions)
        callback (some #(when (= :callback (:type %)) %) actions)]
    (is (= 2 (count actions)))
    (is (= "Vendor trigger key 0x03 sub-key 0x05 forwarded"
           (get-in diag [:packet :text])))
    (is (= :callback (:type callback)))
    ((:fn callback) (:payload callback))
    (let [ctx (deref cb 100 nil)
          ^ByteBuffer payload (get-in ctx [:packet :payload])
          bytes (byte-array (.remaining payload))]
      (.get payload bytes)
      (is (= sender (:sender ctx)))
      (is (= :arttrigger (get-in ctx [:packet :op])))
      (is (= 0x42 (first (map #(bit-and 0xFF %) bytes)))))
    (is (= 1 (get-in state [:stats :trigger-requests])))))

(deftest arttrigger-macro-helper-dispatches
  (let [helper-called (promise)
        helper (fn [ctx] (deliver helper-called ctx))
        config (assoc base-config :triggers {:macros {:key-macro {5 helper}}})
        packet (arttrigger-packet
                 {:oem 0xFFFF, :key-type :key-macro, :sub-key 0x05})
        sender {:host (InetAddress/getByName "192.168.60.45"), :port 6454}
        msg {:type :rx, :packet packet, :sender sender}
        [state actions] (logic-step nil config msg)
        helper-action (some #(when (= :callback (:type %)) %) actions)
        diag (some #(when (= :artdiagdata (get-in % [:packet :op])) %) actions)]
    (is (= "Trigger KeyMacro 5 executed" (get-in diag [:packet :text])))
    (is (= :callback (:type helper-action)))
    ((:fn helper-action) (:payload helper-action))
    (let [ctx (deref helper-called 100 nil)]
      (is (= sender (:sender ctx)))
      (is (= :arttrigger (get-in ctx [:packet :op])))
      (is (= 0x05 (get-in ctx [:trigger :sub-key]))))
    (is (= 1 (get-in state [:stats :trigger-requests])))))

(deftest arttrigger-ignored-when-oem-mismatch
  (let [config (assoc-in base-config [:node :oem] 0x7777)
        packet (arttrigger-packet {:oem 0x1234})
        sender {:host (InetAddress/getByName "192.168.60.42"), :port 6454}
        [state actions]
        (logic-step nil config {:type :rx, :packet packet, :sender sender})]
    (is (empty? actions))
    (is (= 0 (get-in state [:stats :trigger-requests])))))

(deftest arttrigger-rate-limit-debounces-rapid-events
  (let [trigger-called (promise)
        config (-> base-config
                   (assoc :callbacks
                          {:trigger (fn [ctx] (deliver trigger-called ctx))})
                   (assoc :triggers {:min-interval-ms 100}))
        packet (arttrigger-packet
                 {:oem 0xFFFF, :key-type :key-macro, :sub-key 0x07})
        sender {:host (InetAddress/getByName "192.168.60.46"), :port 6454}
        clock (atom 0)
        msg (fn []
              {:type :rx, :packet packet, :sender sender, :timestamp @clock})
        [state actions] (logic-step nil config (msg))
        callback (some #(when (= :callback (:type %)) %) actions)]
    (is (= :callback (:type callback)))
    ((:fn callback) (:payload callback))
    (is (= sender (:sender (deref trigger-called 100 nil))))
    (let [[state2 actions2] (logic-step state config (msg))
          diag (some #(when (= :artdiagdata (get-in % [:packet :op])) %)
                     actions2)]
      (is (= 1 (get-in state2 [:stats :trigger-throttled])))
      (is (= "Trigger KeyMacro 7 ignored (debounced 100ms)"
             (get-in diag [:packet :text])))
      (is (nil? (some #(when (= :callback (:type %)) %) actions2))))))

(deftest arttrigger-optional-reply-packet
  (let [config (assoc base-config :triggers {:reply {:enabled? true}})
        packet (arttrigger-packet
                 {:oem 0xFFFF, :key-type :key-show, :sub-key 0x02})
        sender {:host (InetAddress/getByName "192.168.60.47"), :port 6454}
        msg {:type :rx, :packet packet, :sender sender}
        [state actions] (logic-step nil config msg)
        reply (some #(when (= :arttrigger (get-in % [:packet :op])) %) actions)]
    (is reply)
    (is (= (:host sender) (get-in reply [:target :host])))
    (is (= :arttrigger (get-in reply [:packet :op])))
    (is (= (get-in state [:node :oem]) (get-in reply [:packet :oem])))
    (is (= (:sub-key packet) (get-in reply [:packet :sub-key])))
    (is (= 1 (get-in state [:stats :trigger-replies])))))

(deftest artcommand-matching-esta-dispatches-callback
  (let [cb (promise)
        config (-> base-config
                   (assoc-in [:node :esta-man] 0x4567)
                   (assoc :callbacks {:command (fn [ctx] (deliver cb ctx))}))
        packet (artcommand-packet {:esta 0x4567, :text "SwoutText=Playback&"})
        sender {:host (InetAddress/getByName "192.168.60.43"), :port 6454}
        [state actions]
        (logic-step nil config {:type :rx, :packet packet, :sender sender})
        diag (some #(when (= :artdiagdata (get-in % [:packet :op])) %) actions)
        callback (some #(when (= :callback (:type %)) %) actions)]
    (is (= 2 (count actions)))
    (is (= "SwoutText applied: Playback" (get-in diag [:packet :text])))
    (is (= "Playback" (get-in state [:command-labels :swout])))
    (is (= :callback (:type callback)))
    ((:fn callback) (:payload callback))
    (let [ctx (deref cb 100 nil)]
      (is (= sender (:sender ctx)))
      (is (= :artcommand (get-in ctx [:packet :op])))
      (is (= "SwoutText=Playback&" (get-in ctx [:packet :text])))
      (is (= "Playback" (get-in ctx [:command-labels :swout]))))
    (is (= 1 (get-in state [:stats :command-requests])))))

(deftest artdatarequest-produces-configured-reply
  (let [config (assoc base-config
                 :data
                 {:responses {:dr-url-product
                              "https://example.invalid/product"}})
        sender {:host (InetAddress/getByName "192.168.0.60"), :port 6454}
        packet (artdatarequest-packet {:request-type :dr-url-product})
        msg {:type :rx, :sender sender, :packet packet}
        [state actions] (logic-step nil config msg)
        reply (first actions)]
    (is (= 1 (count actions)))
    (is (= :send (:type reply)))
    (is (= :artdatareply (get-in reply [:packet :op])))
    (is (= "https://example.invalid/product" (get-in reply [:packet :text])))
    (is (= sender (:target reply)))
    (is (= 1 (get-in state [:stats :data-requests])))))

(deftest artdatarequest-reply-carries-node-identifiers
  (let [config (-> base-config
                   (assoc-in [:node :esta-man] 0x2222)
                   (assoc-in [:node :oem] 0x3333)
                   (assoc :data
                          {:responses {:dr-url-product
                                       "https://example.invalid/product"}}))
        sender {:host (InetAddress/getByName "192.168.0.63"), :port 6454}
        packet (artdatarequest-packet
                 {:request-type :dr-url-product, :esta 0x2222, :oem 0x3333})
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})
        reply (first actions)]
    (is (= 1 (count actions)))
    (is (= 0x2222 (get-in reply [:packet :esta-man])))
    (is (= 0x3333 (get-in reply [:packet :oem])))
    (is (= 1 (get-in state [:stats :data-requests])))))

(deftest artdatarequest-ignored-when-identifiers-mismatch
  (let [config (-> base-config
                   (assoc-in [:node :esta-man] 0x4444)
                   (assoc-in [:node :oem] 0x5555)
                   (assoc :data
                          {:responses {:dr-url-product
                                       "https://example.invalid/product"}}))
        sender {:host (InetAddress/getByName "192.168.0.66"), :port 6454}
        packet (artdatarequest-packet
                 {:request-type :dr-url-product, :esta 0x4444, :oem 0x9999})
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})]
    (is (empty? actions))
    (is (= 0 (get-in state [:stats :data-requests])))))

(deftest artdatarequest-accepts-drip-requests
  (let [config (-> base-config
                   (assoc-in [:node :esta-man] 0x1001)
                   (assoc-in [:node :oem] 0x2002)
                   (assoc :data
                          {:responses {:dr-ip-support (byte-array [1 2 3])}}))
        sender {:host (InetAddress/getByName "192.168.0.70"), :port 6454}
        packet (artdatarequest-packet
                 {:request-type :dr-ip-support, :esta 0x1001, :oem 0x2002})
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})
        reply (first actions)
        data (-> reply
                 :packet
                 :data)]
    (is (= 1 (count actions)))
    (is (= :artdatareply (get-in reply [:packet :op])))
    (is (= [1 2 3] (vec data)))
    (is (= 1 (get-in state [:stats :data-requests])))))

(deftest artdatarequest-rejects-drip-without-identifier-match
  (let [config (-> base-config
                   (assoc-in [:node :esta-man] 0x1001)
                   (assoc-in [:node :oem] 0x2002)
                   (assoc :data {:responses {:dr-ip-support (byte-array [0])}}))
        sender {:host (InetAddress/getByName "192.168.0.71"), :port 6454}
        packet (artdatarequest-packet
                 {:request-type :dr-ip-support, :esta 0x1001, :oem 0x2003})
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})]
    (is (empty? actions))
    (is (= 0 (get-in state [:stats :data-requests])))))

(deftest artdatarequest-variant-overrides-code
  (let [config (assoc base-config
                 :data
                 {:responses {:dr-url-support
                              "https://example.invalid/support"
                              0x0003 "http://ignored.invalid"}})
        sender {:host (InetAddress/getByName "192.168.0.65"), :port 6454}
        packet (artdatarequest-packet {:request-type :dr-url-support
                                       :request      0x0003})
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})
        reply (first actions)]
    (is (= 1 (count actions)))
    (is (= "https://example.invalid/support" (get-in reply [:packet :text])))
    (is (= 1 (get-in state [:stats :data-requests])))))

(deftest artdatarequest-dr-poll-responds-when-supported
  (let [config (assoc base-config
                 :data
                 {:responses {:dr-url-support
                              "https://example.invalid/support"}})
        sender {:host (InetAddress/getByName "192.168.0.61"), :port 6454}
        packet (artdatarequest-packet {:request-type :dr-poll})
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})
        reply (first actions)]
    (is (= 1 (count actions)))
    (is (= :artdatareply (get-in reply [:packet :op])))
    (is (= 0 (get-in reply [:packet :request])))
    (is (= "" (get-in reply [:packet :text])))
    (is (= 1 (get-in state [:stats :data-requests])))))

(deftest artdatarequest-ignored-when-no-config
  (let [sender {:host (InetAddress/getByName "192.168.0.62"), :port 6454}
        packet (artdatarequest-packet {:request-type :dr-url-product})
        [state actions] (logic-step
                          nil
                          base-config
                          {:type :rx, :sender sender, :packet packet})]
    (is (empty? actions))
    (is (= 0 (get-in state [:stats :data-requests])))))

(deftest artdatarequest-falls-back-to-code-when-type-missing
  (let [config (assoc base-config
                 :data
                 {:responses {0x0002 "https://example.invalid/guide"}})
        sender {:host (InetAddress/getByName "192.168.0.64"), :port 6454}
        packet (-> (artdatarequest-packet {:request 0x0002})
                   (assoc :request-type nil))
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})
        reply (first actions)]
    (is (= 1 (count actions)))
    (is (= "https://example.invalid/guide" (get-in reply [:packet :text])))
    (is (= 1 (get-in state [:stats :data-requests])))))

(deftest artdatarequest-response-length-validated
  (is (thrown-with-msg? ExceptionInfo
                        #"ArtDataReply payload exceeds maximum length"
                        (lifecycle/initial-state
                          {:data {:responses {:dr-url-product (byte-array
                                                                600)}}}))))

(deftest artcommand-broadcast-dispatches
  (let [cb (promise)
        config (-> base-config
                   (assoc-in [:node :esta-man] 0x2222)
                   (assoc :callbacks {:command (fn [ctx] (deliver cb ctx))}))
        packet (artcommand-packet {:esta 0xFFFF, :text "SwinText=Record&"})
        sender {:host (InetAddress/getByName "192.168.60.44"), :port 6454}
        [state actions]
        (logic-step nil config {:type :rx, :packet packet, :sender sender})
        diag (some #(when (= :artdiagdata (get-in % [:packet :op])) %) actions)
        callback (some #(when (= :callback (:type %)) %) actions)]
    (is (= 2 (count actions)))
    (is (= "SwinText applied: Record" (get-in diag [:packet :text])))
    (is (= "Record" (get-in state [:command-labels :swin])))
    (is (= :callback (:type callback)))
    ((:fn callback) (:payload callback))
    (let [ctx (deref cb 100 nil)]
      (is (= "SwinText=Record&" (get-in ctx [:packet :text])))
      (is (= "Record" (get-in ctx [:command-labels :swin]))))))

(deftest artcommand-unsupported-command-produces-warning
  (let [config (assoc-in base-config [:node :esta-man] 0x9999)
        packet (artcommand-packet {:esta 0x9999, :text "Foo=Bar&"})
        sender {:host (InetAddress/getByName "192.168.60.47"), :port 6454}
        [state actions]
        (logic-step nil config {:type :rx, :packet packet, :sender sender})
        diag (first actions)]
    (is (= 1 (count actions)))
    (is (= :artdiagdata (get-in diag [:packet :op])))
    (is (= "Unsupported ArtCommand: Foo=Bar" (get-in diag [:packet :text])))
    (is (= 1 (get-in state [:stats :command-requests])))
    (is (nil? (get-in state [:command-labels :swout])))))

(deftest artcommand-programming-change-notifies
  (let [event (promise)
        config (-> base-config
                   (assoc-in [:node :esta-man] 0x1357)
                   (assoc :programming
                          {:on-change (fn [evt] (deliver event evt))}))
        packet (artcommand-packet {:esta 0x1357
                                   :text "SwoutText=SceneA&SwinText=SceneB&"})
        sender {:host (InetAddress/getByName "192.168.60.46"), :port 6454}
        [state actions]
        (logic-step nil config {:type :rx, :packet packet, :sender sender})
        diag-texts (map #(get-in % [:packet :text]) actions)
        evt (deref event 100 nil)]
    (is (= 2 (count actions)))
    (is (= #{"SwoutText applied: SceneA" "SwinText applied: SceneB"}
           (set diag-texts)))
    (is (= "SceneA" (get-in state [:command-labels :swout])))
    (is (= "SceneB" (get-in state [:command-labels :swin])))
    (is (= :artcommand (:event evt)))
    (is (= "SceneA" (get-in evt [:changes :command-labels :swout])))
    (is (= 2 (count (:directives evt))))))

(deftest initial-state-seeds-command-labels
  (let [config
        (assoc base-config :command-labels {:swout "Playback", :swin "Record"})
        state (lifecycle/initial-state config)]
    (is (= "Playback" (get-in state [:command-labels :swout])))
    (is (= "Record" (get-in state [:command-labels :swin])))))

(deftest apply-state-updates-command-labels
  (let [state (lifecycle/initial-state base-config)
        msg {:type    :command
             :command :apply-state
             :state   {:command-labels {:swout "Playback"}}}
        [next-state _] (logic-step state base-config msg)]
    (is (= "Playback" (get-in next-state [:command-labels :swout])))
    (is (nil? (get-in next-state [:command-labels :swin])))))

(deftest artcommand-acknowledges-preconfigured-labels
  (let [config (assoc base-config :command-labels {:swout "Playback"})
        packet (artcommand-packet {:esta 0xFFFF, :text "SwoutText=Playback&"})
        sender {:host (InetAddress/getByName "192.168.60.48"), :port 6454}
        [state actions]
        (logic-step nil config {:type :rx, :packet packet, :sender sender})
        diag (first actions)]
    (is (= 1 (count actions)))
    (is (= :artdiagdata (get-in diag [:packet :op])))
    (is (= "SwoutText already set to 'Playback'" (get-in diag [:packet :text])))
    (is (= "Playback" (get-in state [:command-labels :swout])))))

(deftest artcommand-ignored-when-esta-mismatch
  (let [config (assoc-in base-config [:node :esta-man] 0x0102)
        packet (artcommand-packet {:esta 0x0A0B})
        sender {:host (InetAddress/getByName "192.168.60.45"), :port 6454}
        [state actions]
        (logic-step nil config {:type :rx, :packet packet, :sender sender})]
    (is (empty? actions))
    (is (= 0 (get-in state [:stats :command-requests])))))

(deftest generic-op-dispatches-through-handler-map
  (let [cb (promise)
        config (assoc base-config
                 :callbacks
                 {:packets {:arttimesync (fn [ctx] (deliver cb ctx))}})
        packet (generic-op-packet :arttimesync [0x11 0x22 0x33])
        sender {:host (InetAddress/getByName "192.168.60.146"), :port 6454}
        [state actions]
        (logic-step nil config {:type :rx, :packet packet, :sender sender})
        action (first actions)]
    (is (= 1 (count actions)))
    (is (= :callback (:type action)))
    ((:fn action) (:payload action))
    (let [ctx (deref cb 100 nil)
          ^ByteBuffer data (get-in ctx [:packet :data])
          bytes (byte-array (.remaining data))]
      (.get data bytes)
      (is (= sender (:sender ctx)))
      (is (= :arttimesync (get-in ctx [:packet :op])))
      (is (= [0x11 0x22 0x33] (map #(bit-and 0xFF %) bytes))))
    (is (= 1 (get-in state [:stats :rx-arttimesync])))))

(deftest generic-op-falls-back-to-default-callback
  (let [cb (promise)
        config
        (assoc base-config :callbacks {:default (fn [ctx] (deliver cb ctx))})
        packet (generic-op-packet :artvideosetup [0xCC 0xDD])
        sender {:host (InetAddress/getByName "192.168.60.147"), :port 6454}
        [state actions]
        (logic-step nil config {:type :rx, :packet packet, :sender sender})
        action (first actions)]
    (is (= 1 (count actions)))
    (is (= :callback (:type action)))
    ((:fn action) (:payload action))
    (let [ctx (deref cb 100 nil)
          ^ByteBuffer data (get-in ctx [:packet :data])
          bytes (byte-array (.remaining data))]
      (.get data bytes)
      (is (= sender (:sender ctx)))
      (is (= :artvideosetup (get-in ctx [:packet :op])))
      (is (= [0xCC 0xDD] (map #(bit-and 0xFF %) bytes))))
    (is (= 1 (get-in state [:stats :rx-artvideosetup])))))

(deftest send-dmx-validates-payload-length
  (let [msg {:type     :command
             :command  :send-dmx
             :data     (byte-array (repeat 513 0))
             :net      0
             :sub-net  0
             :universe 0}]
    (is (thrown-with-msg? ExceptionInfo
                          #"DMX payload exceeds"
                          (logic-step nil base-config msg)))))

(deftest reply-on-change-multi-bind-emits-all-pages
  (let [sender {:host (InetAddress/getByName "192.168.0.41"), :port 6454}
        poll {:type   :rx
              :sender sender
              :packet {:op               :artpoll
                       :flags            0x03
                       :talk-to-me       0x03
                       :reply-on-change? true
                       :diag-request?    false
                       :diag-unicast?    false}}
        [state _] (logic-step nil multi-bind-config poll)
        change {:type    :command
                :command :apply-state
                :state   {:node {:long-name "multi"}}}
        [_ actions] (logic-step state multi-bind-config change)]
    (is (= 2 (count actions)))
    (is (= #{1 2} (set (map #(get-in % [:packet :bind-index]) actions))))))

(deftest diagnostic-priority-code-normalizes-inputs
  (is (= 0x80 (diagnostics/priority-code :dp-high)))
  (is (= 0x40 (diagnostics/priority-code "dp-med")))
  (is (= 0x10 (diagnostics/priority-code nil)))
  (is (= 0xE0 (diagnostics/priority-code nil :dp-critical)))
  (is (thrown-with-msg? ExceptionInfo
                        #"Unknown diagnostics priority keyword"
                        (diagnostics/priority-code :dp-unknown)))
  (is (thrown-with-msg? ExceptionInfo
                        #"Diagnostics priority must use"
                        (diagnostics/priority-code 0x22))))

(deftest diagnostics-subscriptions-govern-ArtDiagData
  (let [c1 {:host (InetAddress/getByName "192.168.1.50"), :port 6454}
        c2 {:host (InetAddress/getByName "192.168.1.60"), :port 6454}
        poll {:type   :rx
              :sender c1
              :packet {:op            :artpoll
                       :flags         0x0C                  ; diag request + unicast
                       :diag-priority 0x40
                       :diag-request? true
                       :diag-unicast? true}}
        [state _] (step* nil poll)
        diag-command {:type     :command
                      :command  :diagnostic
                      :priority :dp-high
                      :text     "Subsystem ready"}
        [state' actions] (step* state diag-command)
        diag-action (first actions)]
    (is (= 1 (count actions)))
    (is (= :artdiagdata (get-in diag-action [:packet :op])))
    (is (= c1 (:target diag-action)))
    (let [poll2 {:type   :rx
                 :sender c2
                 :packet {:op            :artpoll
                          :flags         0x04               ; diag request broadcast
                          :diag-priority 0x10
                          :diag-request? true
                          :diag-unicast? false}}
          [state'' _] (step* state' poll2)
          [_ actions2] (step* state'' diag-command)
          targets (set (map :target actions2))]
      (is (= 2 (count actions2)))
      (is (contains? targets c1))
      (is (contains? targets {:host "10.0.0.255", :port 6454})))))

(deftest multiple-subscribers-force-broadcast-target
  (let [c1 {:host (InetAddress/getByName "192.168.2.10"), :port 6454}
        c2 {:host (InetAddress/getByName "192.168.2.11"), :port 6454}
        poll-common
        {:type   :rx
         :packet
         {:op :artpoll, :flags 0x0C, :diag-request? true, :diag-unicast? false}
         :sender c1}
        poll-second (assoc poll-common :sender c2)
        [state _] (step* nil poll-common)
        [state _] (step* state poll-second)
        cmd {:type     :command
             :command  :diagnostic
             :priority :dp-high
             :text     "Broadcast diag"}
        [_ actions] (step* state cmd)
        diag-action (first actions)]
    (is (= 1 (count actions)))
    (is (= {:host "10.0.0.255", :port 6454} (:target diag-action)))))

(deftest diagnostic-command-updates-stats
  (let [sender {:host (InetAddress/getByName "192.168.3.10"), :port 6454}
        poll {:type   :rx
              :sender sender
              :packet {:op            :artpoll
                       :flags         0x0C
                       :diag-request? true
                       :diag-unicast? true
                       :diag-priority (diagnostics/priority-code :dp-med)}}
        [state _] (step* nil poll)
        cmd {:type     :command
             :command  :diagnostic
             :priority :dp-med
             :text     "Stat bump"}
        [state' actions] (step* state cmd)
        stats (lifecycle/snapshot state' [:stats])]
    (is (= 1 (count actions)))
    (is (= 1 (get-in stats [:stats :diagnostics-sent])))))

(deftest diagnostic-snapshot-includes-summary
  (let [state (lifecycle/initial-state base-config)
        snap (lifecycle/snapshot state [:diagnostics])
        summary (get-in snap [:diagnostics :summary])]
    (is (= 0 (:subscriber-count summary)))
    (is (= 30000 (:subscriber-ttl-ms summary)))
    (is (= 32 (:warning-threshold summary)))
    (is (false? (:warning? summary)))))

(deftest diagnostic-snapshot-reflects-subscriber-count
  (let [sender {:host (InetAddress/getByName "192.168.7.10"), :port 6454}
        poll {:type   :rx
              :sender sender
              :packet {:op            :artpoll
                       :flags         0x0C
                       :diag-request? true
                       :diag-unicast? true
                       :diag-priority (diagnostics/priority-code :dp-high)}}
        [state _] (step* nil poll)
        snap (lifecycle/snapshot state [:diagnostics])
        summary (get-in snap [:diagnostics :summary])]
    (is (= 1 (:subscriber-count summary)))
    (is (pos? (:last-updated-ns summary)))
    (is (false? (:warning? summary)))))

(deftest dmx-snapshot-exposes-telemetry
  (let [port (common/compose-port-address 0 0 1)
        state
        (-> (lifecycle/initial-state base-config)
            (assoc-in
              [:dmx :merge :ports port]
              {:sources              {["10.0.0.1" 0] {:length 12, :last-updated 111}}
               :exclusive-owner      ["10.0.0.1" 0]
               :exclusive-updated-at 222
               :last-output          {:length 12, :updated-at 333, :data (byte-array 1)}})
            (assoc-in [:dmx :merge :per-port 0] {:mode :htp, :protocol :artnet})
            (assoc-in [:dmx :failsafe :playback port]
                      {:mode :scene, :engaged-at 444, :length 12})
            (assoc-in [:dmx :failsafe :scene port]
                      {:data (byte-array 2), :length 12})
            (assoc-in [:dmx :failsafe :missing-scene port] 555)
            (assoc-in [:dmx :failsafe :recorded-at] 666)
            (assoc-in [:dmx :throughput :artnzs port] 777))
        snapshot (lifecycle/snapshot state [:dmx])
        dmx (:dmx snapshot)]
    (is (= :htp (get-in dmx [:merge :per-port 0 :mode])))
    (is (= :artnet (get-in dmx [:merge :per-port 0 :protocol])))
    (is (= 1 (get-in dmx [:merge :ports port :source-count])))
    (is (= "10.0.0.1" (get-in dmx [:merge :ports port :exclusive-owner :host])))
    (is (= :scene (get-in dmx [:failsafe :playback port :mode])))
    (is (= [port] (get-in dmx [:failsafe :scene-ports])))
    (is (= 777 (get-in dmx [:throughput :artnzs port])))
    (is (nil? (get-in dmx [:merge :ports port :last-output :data])))))

(deftest diagnostic-warning-threshold-logs-and-flags
  (let [sender {:host (InetAddress/getByName "192.168.8.10"), :port 6454}
        poll {:type   :rx
              :sender sender
              :packet {:op            :artpoll
                       :flags         0x0C                  ; diag request + unicast
                       :diag-request? true
                       :diag-unicast? true
                       :diag-priority (diagnostics/priority-code :dp-med)}}
        config (assoc base-config
                 :diagnostics
                 {:broadcast-target             {:host "10.0.0.255", :port 6454}
                  :subscriber-warning-threshold 0})
        [state _] (logic-step nil config poll)
        summary (get-in (lifecycle/snapshot state [:diagnostics])
                        [:diagnostics :summary])]
    (is (= 1 (get-in summary [:subscriber-count])))
    (is (:warning? summary))))

(deftest diagnostic-subscriber-expires-without-refresh
  (let [sender {:host (InetAddress/getByName "192.168.4.10"), :port 6454}
        poll {:type   :rx
              :sender sender
              :packet {:op            :artpoll
                       :flags         0x0C
                       :diag-request? true
                       :diag-unicast? true
                       :diag-priority 0x40}}
        [state _] (step* nil poll)
        ttl (get-in state [:diagnostics :subscriber-ttl-ns])
        stale-time (- (System/nanoTime) (+ ttl 1000000))
        expired-state (update-in
                        state
                        [:diagnostics :subscribers]
                        (fn [subs]
                          (into {}
                                (map (fn [[k v]]
                                       [k (assoc v :updated-at stale-time)]))
                                subs)))
        cmd {:type     :command
             :command  :diagnostic
             :priority :dp-high
             :text     "no receivers"}
        [_ actions] (step* expired-state cmd)]
    (is (empty? actions))))

(deftest diagnostic-rate-limit-throttles-rapid-messages
  (let [config (assoc base-config
                 :diagnostics
                 {:broadcast-target {:host "10.0.0.255", :port 6454}
                  :rate-limit-hz    10.0})
        sender {:host (InetAddress/getByName "192.168.5.50"), :port 6454}
        poll {:type   :rx
              :sender sender
              :packet {:op            :artpoll
                       :flags         0x0C
                       :diag-request? true
                       :diag-unicast? true
                       :diag-priority (diagnostics/priority-code :dp-low)}}
        [state _] (logic-step nil config poll)
        interval (long (/ dmx/nanos-per-second 10))
        cmd {:type     :command
             :command  :diagnostic
             :priority :dp-high
             :text     "first"
             :now      0}
        [state' actions] (logic-step state config cmd)]
    (is (= 1 (count actions)))
    (let [rapid (assoc cmd :text "second" :now (quot interval 2))
          [throttled actions2] (logic-step state' config rapid)]
      (is (empty? actions2))
      (is (= 1 (get-in throttled [:stats :diagnostics-throttled])))
      (let [recover (assoc cmd :text "third" :now interval)
            [released actions3] (logic-step throttled config recover)]
        (is (= 1 (count actions3)))
        (is (= 1 (get-in released [:stats :diagnostics-throttled])))))))

(deftest diagnostic-priority-filtering-respects-thresholds
  (let [sender {:host (InetAddress/getByName "192.168.5.10"), :port 6454}
        poll {:type   :rx
              :sender sender
              :packet {:op            :artpoll
                       :flags         0x0C
                       :diag-request? true
                       :diag-unicast? true
                       :diag-priority 0x40}}
        [state _] (step* nil poll)
        high {:type     :command
              :command  :diagnostic
              :priority :dp-high
              :text     "deliver"}
        low
        {:type :command, :command :diagnostic, :priority :dp-low, :text "drop"}
        [_ actions-high] (step* state high)
        [_ actions-low] (step* state low)]
    (is (= 1 (count actions-high)))
    (is (empty? actions-low))))

(deftest apply-state-command-updates-node-and-network
  (let [state-command {:type    :command
                       :command :apply-state
                       :state
                       {:node    {:short-name "restored", :sw-out [5 0 0 0]}
                        :network {:ip [10 0 0 99], :subnet-mask [255 0 0 0]}}}
        [state _] (logic-step nil base-config state-command)
        snap (lifecycle/snapshot state [:node :network])]
    (is (= "restored" (get-in snap [:node :short-name])))
    (is (= [5 0 0 0] (get-in snap [:node :sw-out])))
    (is (= [10 0 0 99] (get-in snap [:network :ip])))))

(deftest apply-state-supports-partial-updates
  (let [initial {:type    :command
                 :command :apply-state
                 :state   {:node {:short-name "one"}}}
        [state _] (logic-step nil base-config initial)
        partial {:type    :command
                 :command :apply-state
                 :state   {:network {:ip [1 2 3 4]}}}
        [state' _] (logic-step state base-config partial)]
    (is (= "one" (get-in state' [:node :short-name]))
        "Node fields persist when only network changes")
    (is (= [1 2 3 4] (get-in state' [:network :ip])))))

(deftest diagnostic-fanout-reverts-to-unicast-after-expiry
  (let [c1 {:host (InetAddress/getByName "192.168.6.10"), :port 6454}
        c2 {:host (InetAddress/getByName "192.168.6.11"), :port 6454}
        poll-template {:type   :rx
                       :packet {:op            :artpoll
                                :flags         0x0C
                                :diag-request? true
                                :diag-unicast? true
                                :diag-priority (diagnostics/priority-code
                                                 :dp-med)}}
        [state _] (step* nil (assoc poll-template :sender c1))
        [state _] (step* state (assoc poll-template :sender c2))
        cmd {:type     :command
             :command  :diagnostic
             :priority :dp-med
             :text     "fanout"}
        [state actions] (step* state cmd)]
    (is (= 2 (count actions)))
    (is (= #{c1 c2} (set (map :target actions)))
        "Multiple unicast subscribers should emit directly")
    (let [ttl (get-in state [:diagnostics :subscriber-ttl-ns])
          stale (- (System/nanoTime) (+ ttl 1000000))
          key2 [(str (:host c2)) (:port c2)]
          state'
          (assoc-in state [:diagnostics :subscribers key2 :updated-at] stale)
          [_ actions2] (step* state' cmd)
          target (:target (first actions2))]
      (is (= 1 (count actions2)))
      (is (= c1 target)
          "With one subscriber remaining, diagnostics revert to unicast"))))

(deftest diagnostic-priority-honors-per-subscriber-thresholds
  (let [broadcast-subscriber {:host (InetAddress/getByName "192.168.30.10")
                              :port 6454}
        unicast-subscriber {:host (InetAddress/getByName "192.168.30.11")
                            :port 6454}
        broadcast-request {:type   :rx
                           :sender broadcast-subscriber
                           :packet {:op            :artpoll
                                    :flags         0x0C
                                    :diag-request? true
                                    :diag-unicast? false
                                    :diag-priority (diagnostics/priority-code
                                                     :dp-low)}}
        [state _] (logic-step nil base-config broadcast-request)
        unicast-request {:type   :rx
                         :sender unicast-subscriber
                         :packet {:op            :artpoll
                                  :flags         0x0C
                                  :diag-request? true
                                  :diag-unicast? true
                                  :diag-priority (diagnostics/priority-code
                                                   :dp-critical)}}
        [state _] (logic-step state base-config unicast-request)
        low
        {:type :command, :command :diagnostic, :priority :dp-med, :text "low"}
        [state low-actions] (logic-step state base-config low)
        high {:type     :command
              :command  :diagnostic
              :priority :dp-volatile
              :text     "high"}
        [_ high-actions] (logic-step state base-config high)
        broadcast-target (get-in base-config [:diagnostics :broadcast-target])]
    (is (= 1 (count low-actions)))
    (is (= [broadcast-target] (map :target low-actions))
        "Only broadcast subscriber should receive low priority diagnostics")
    (is (= 2 (count high-actions)))
    (let [targets (set (map :target high-actions))]
      (is (contains? targets broadcast-target))
      (is (contains? targets unicast-subscriber)))))

(deftest artaddress-programming-updates-node
  (let [persist (promise)
        config (assoc base-config
                 :programming
                 {:on-change (fn [event] (deliver persist event))})
        sender {:host (InetAddress/getByName "10.0.0.5"), :port 6454}
        packet {:op           :artaddress
                :short-name   "Desk"
                :long-name    "Front Of House"
                :net-switch   0x81
                :sub-switch   0x82
                :sw-out       [0x81 0 0 0]
                :acn-priority 120
                :command      0x02}
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})]
    (is (= "Desk" (get-in state [:node :short-name])))
    (is (= 1 (get-in state [:stats :address-requests])))
    (let [diag (first actions)
          reply (second actions)]
      (is (= :artdiagdata (get-in diag [:packet :op])))
      (is (= "LED indicators set to normal" (get-in diag [:packet :text])))
      (is (= sender (:target diag)))
      (is (= :artpollreply (get-in reply [:packet :op])))
      (is (= sender (:target reply))))
    (let [event (deref persist 100 nil)]
      (is event)
      (is (= :artaddress (:event event)))
      (is (= "Desk" (get-in event [:changes :short-name]))))))

(deftest artaddress-reply-on-change-updates-multi-page-node
  (let [subscriber {:host (InetAddress/getByName "10.0.0.21"), :port 6454}
        controller {:host (InetAddress/getByName "10.0.0.22"), :port 6454}
        poll {:type   :rx
              :sender subscriber
              :packet {:op               :artpoll
                       :flags            0x02
                       :talk-to-me       0x02
                       :reply-on-change? true
                       :diag-priority    0x10
                       :diag-request?    false
                       :diag-unicast?    false}}
        [state _] (logic-step nil artpollreply-config poll)
        artaddress {:type   :rx
                    :sender controller
                    :packet {:op         :artaddress
                             :short-name "PGM"
                             :long-name  "Programmed Node"
                             :command    0x02}}
        [state' actions] (logic-step state artpollreply-config artaddress)
        diag (some #(when (and (= controller (:target %))
                               (= :artdiagdata (get-in % [:packet :op])))
                      %)
                   actions)
        subscriber-updates (filter #(and (= subscriber (:target %))
                                         (= :artpollreply
                                            (get-in % [:packet :op])))
                                   actions)
        controller-reply (some #(when (and (= controller (:target %))
                                           (= :artpollreply
                                              (get-in % [:packet :op])))
                                  %)
                               actions)
        expected (map #(-> %
                           (assoc :op :artpollreply)
                           (assoc :short-name "PGM")
                           (assoc :long-name "Programmed Node"))
                      fixtures/artpollreply-pages)]
    (is (= "Programmed Node" (get-in state' [:node :long-name])))
    (is diag)
    (is (= "LED indicators set to normal" (get-in diag [:packet :text])))
    (is controller-reply)
    (is (= :artpollreply (get-in controller-reply [:packet :op])))
    (is (= (count fixtures/artpollreply-pages) (count subscriber-updates)))
    (is (every? #(= subscriber (:target %)) subscriber-updates))
    (is (= expected (map :packet subscriber-updates)))))

(deftest artaddress-direction-commands-update-port-types
  (let [sender {:host (InetAddress/getByName "10.0.0.6"), :port 6454}
        packet {:op :artaddress, :command 0x21}
        [state _] (logic-step nil
                              base-config
                              {:type :rx, :sender sender, :packet packet})]
    (is (= 0x80 (get-in state [:node :port-types 1])))))

(deftest artaddress-input-command-flushes-sync-buffer
  (let [config (assoc base-config :sync {:mode :art-sync})
        sender {:host (InetAddress/getByName "10.0.0.7"), :port 6454}
        dmxtx {:type    :rx
               :sender  sender
               :packet  (artdmx-packet [9 9 9])
               :release (fn [] nil)}
        [state-after-dmx actions] (logic-step nil config dmxtx)
        _ (doseq [action actions
                  :when (= :release (:type action))]
            (when-let [f (:release action)] (f)))
        _ (is (seq (get-in state-after-dmx [:dmx :sync-buffer])))
        cmd
        {:type :rx, :sender sender, :packet {:op :artaddress, :command 0x30}}
        [state-final _] (logic-step state-after-dmx config cmd)]
    (is (empty? (get-in state-final [:dmx :sync-buffer])))
    (is (= 0x40 (get-in state-final [:node :port-types 0])))))

(deftest artaddress-merge-mode-updates-state
  (let [sender {:host (InetAddress/getByName "10.0.0.70"), :port 6454}
        ltp
        {:type :rx, :sender sender, :packet {:op :artaddress, :command 0x10}}
        [state _] (logic-step nil merge-test-config ltp)
        good-output (get-in state [:node :good-output-a 0])]
    (is (= :ltp (get-in state [:dmx :merge :per-port 0 :mode])))
    (is (pos? (bit-and good-output dmx/good-output-ltp-bit)))
    (let [htp
          {:type :rx, :sender sender, :packet {:op :artaddress, :command 0x50}}
          [state' _] (logic-step state merge-test-config htp)
          good-output' (get-in state' [:node :good-output-a 0])]
      (is (= :htp (get-in state' [:dmx :merge :per-port 0 :mode])))
      (is (zero? (bit-and good-output' dmx/good-output-ltp-bit))))))

(deftest artaddress-failsafe-record-captures-scene
  (let [sender {:host (InetAddress/getByName "10.0.0.90"), :port 6454}
        initial (lifecycle/initial-state base-config)
        snapshot (byte-array [1 2 3 4])
        state (assoc-in initial
                        [:dmx :merge]
                        {:ports {0x0001 {:last-output
                                         {:data snapshot, :length 4, :updated-at 100}}}})
        packet {:op :artaddress, :command 0x0C}
        [next actions] (logic-step state
                                   base-config
                                   {:type :rx, :sender sender, :packet packet})
        diag (first actions)
        scene (get-in next [:dmx :failsafe :scene 0x0001])]
    (is (= :artdiagdata (get-in diag [:packet :op])))
    (is (= "Failsafe scene capture requested" (get-in diag [:packet :text])))
    (is scene)
    (is (= [1 2 3 4] (map #(bit-and 0xFF %) (:data scene))))
    (is (= 4 (:length scene)))))

(deftest failsafe-zero-mode-emits-dark-frame
  (let [port (common/compose-port-address 0 0 1)
        config (-> base-config
                   (assoc :failsafe {:idle-timeout-ms 1})
                   (assoc :callbacks {:dmx (fn [_])})
                   (assoc-in [:node :status3] (failsafe-status3 :zero)))
        base-state (lifecycle/initial-state config)
        state (assoc-in base-state
                        [:dmx :merge]
                        {:ports {port {:last-output {:data       (byte-array [5 4 3])
                                                     :length     3
                                                     :updated-at 0}}}})
        [next actions] (logic-step state config {:type :tick, :now 2000000})
        callback (some #(when (= :callback (:type %)) %) actions)
        payload (:payload callback)
        packet (:packet payload)
        view (types/payload-buffer packet)
        arr (byte-array (.remaining view))]
    (.get view arr)
    (is callback)
    (is (= true (:failsafe? payload)))
    (is (= :zero (:failsafe-mode payload)))
    (is (= port (:port-address payload)))
    (is (= [0 0 0] (map #(bit-and 0xFF %) arr)))
    (is (contains? (get-in next [:dmx :failsafe :playback]) port))))

(deftest failsafe-scene-mode-plays-recorded-bytes
  (let [port (common/compose-port-address 0 0 2)
        config (-> base-config
                   (assoc :failsafe {:idle-timeout-ms 1})
                   (assoc :callbacks {:dmx (fn [_])})
                   (assoc-in [:node :status3] (failsafe-status3 :scene)))
        base-state (lifecycle/initial-state config)
        scene-bytes (byte-array [9 8 7 6])
        state (-> base-state
                  (assoc-in [:dmx :merge]
                            {:ports {port {:last-output {:data       (byte-array [1 2
                                                                                  3])
                                                         :length     4
                                                         :updated-at 0}}}})
                  (assoc-in [:dmx :failsafe :scene port]
                            {:data scene-bytes, :length 4, :updated-at 0}))
        [next actions] (logic-step state config {:type :tick, :now 3000000})
        callback (some #(when (= :callback (:type %)) %) actions)
        payload (:payload callback)
        packet (:packet payload)
        view (types/payload-buffer packet)
        arr (byte-array (.remaining view))]
    (.get view arr)
    (is (= :scene (:failsafe-mode payload)))
    (is (= [9 8 7 6] (map #(bit-and 0xFF %) arr)))
    (is (contains? (get-in next [:dmx :failsafe :playback]) port))))

(deftest failsafe-full-mode-emits-all-on-frame
  (let [port (common/compose-port-address 0 0 1)
        config (-> base-config
                   (assoc :failsafe {:idle-timeout-ms 1})
                   (assoc :callbacks {:dmx (fn [_])})
                   (assoc-in [:node :status3] (failsafe-status3 :full)))
        base-state (lifecycle/initial-state config)
        state (assoc-in base-state
                        [:dmx :merge]
                        {:ports {port {:last-output {:data       (byte-array [1 2 3])
                                                     :length     3
                                                     :updated-at 0}}}})
        [next actions] (logic-step state config {:type :tick, :now 4000000})
        callback (some #(when (= :callback (:type %)) %) actions)
        payload (:payload callback)
        packet (:packet payload)
        view (types/payload-buffer packet)
        arr (byte-array (.remaining view))]
    (.get view arr)
    (is (= :full (:failsafe-mode payload)))
    (is (= [0xFF 0xFF 0xFF] (map #(bit-and 0xFF %) arr)))
    (is (contains? (get-in next [:dmx :failsafe :playback]) port))))

(deftest failsafe-clears-when-dmx-returns
  (let [sender {:host (InetAddress/getByName "10.0.0.120"), :port 6454}
        config (-> merge-test-config
                   (assoc :failsafe {:idle-timeout-ms 1})
                   (assoc-in [:node :status3] (failsafe-status3 :zero)))
        base-state (lifecycle/initial-state config)
        port merge-port-address
        state (-> base-state
                  (assoc-in [:dmx :failsafe :playback port]
                            {:mode :zero, :engaged-at 0, :length 3})
                  (assoc-in [:dmx :merge]
                            {:ports {port {:last-output {:data       (byte-array [1 1
                                                                                  1])
                                                         :length     3
                                                         :updated-at 0}}}}))
        packet (merge-rx sender [1 2 3])
        [next actions] (logic-step state config packet)]
    (doseq [action actions
            :when (= :release (:type action))]
      (when-let [f (:release action)] (f)))
    (is (nil? (get-in next [:dmx :failsafe :playback port])))))

(deftest failsafe-disabled-clears-active-playback
  (let [port (common/compose-port-address 0 0 3)
        config (-> base-config
                   (assoc :failsafe {:enabled? false, :idle-timeout-ms 1})
                   (assoc :callbacks {:dmx (fn [_])})
                   (assoc-in [:node :status3] (failsafe-status3 :zero)))
        base-state (lifecycle/initial-state config)
        playback-entry {:mode :zero, :engaged-at 0, :length 3}
        state (-> base-state
                  (assoc-in [:dmx :merge]
                            {:ports {port {:last-output {:data       (byte-array [1 1
                                                                                  1])
                                                         :length     3
                                                         :updated-at 0}}}})
                  (assoc-in [:dmx :failsafe :playback port] playback-entry))
        [next actions] (logic-step state config {:type :tick, :now 5000000})]
    (is (nil? (some #(when (= :callback (:type %)) %) actions)))
    (is (nil? (get-in next [:dmx :failsafe :playback port])))))

(deftest artaddress-background-queue-command-updates-policy
  (let [sender {:host (InetAddress/getByName "10.0.0.91"), :port 6454}
        packet {:op :artaddress, :command 0xE2}
        config (assoc base-config :rdm {:background {:supported? true}})
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})
        diag (first actions)
        reply (second actions)
        queue (get-in state [:rdm :background-queue])]
    (is (= 2 (get-in state [:node :background-queue-policy])))
    (is (= 2 (:policy queue)))
    (is (= :warning (:severity queue)))
    (is (= :artdiagdata (get-in diag [:packet :op])))
    (is (= "BackgroundQueuePolicy set to 2" (get-in diag [:packet :text])))
    (is (= :artpollreply (get-in reply [:packet :op])))))

(deftest artdmx-htp-merges-two-sources
  (let [sender-a {:host (InetAddress/getByName "10.0.0.71"), :port 6454}
        sender-b {:host (InetAddress/getByName "10.0.0.72"), :port 6454}
        sender-c {:host (InetAddress/getByName "10.0.0.73"), :port 6454}
        [state-a _]
        (logic-step nil merge-test-config (merge-rx sender-a [10 20 30]))
        [state-b _]
        (logic-step state-a merge-test-config (merge-rx sender-b [5 25 15]))
        [state-c _] (logic-step state-b
                                merge-test-config
                                (merge-rx sender-c [100 100 100]))]
    (is (= 2 (merge-source-count state-b)))
    (is (= [10 25 30] (merge-last-output state-b)))
    (is (pos? (bit-and (get-in state-b [:node :good-output-a 0])
                       dmx/good-output-merge-bit)))
    (is (= 2 (merge-source-count state-c)))
    (is (= [10 25 30] (merge-last-output state-c)))))

(deftest artdmx-ltp-chooses-latest-source
  (let [sender {:host (InetAddress/getByName "10.0.0.74"), :port 6454}
        cmd
        {:type :rx, :sender sender, :packet {:op :artaddress, :command 0x10}}
        [state _] (logic-step nil merge-test-config cmd)
        sender-a {:host (InetAddress/getByName "10.0.0.75"), :port 6454}
        sender-b {:host (InetAddress/getByName "10.0.0.76"), :port 6454}
        [state-b _]
        (logic-step state merge-test-config (merge-rx sender-a [1 2 3]))
        [state-c _]
        (logic-step state-b merge-test-config (merge-rx sender-b [9 9 9]))]
    (is (= :ltp (get-in state-c [:dmx :merge :per-port 0 :mode])))
    (is (= [9 9 9] (merge-last-output state-c)))
    (is (pos? (bit-and (get-in state-c [:node :good-output-a 0])
                       dmx/good-output-merge-bit)))
    (is (pos? (bit-and (get-in state-c [:node :good-output-a 0])
                       dmx/good-output-ltp-bit)))))

(deftest artdmx-merge-timeout-clears-stale-source
  (let [sender-a {:host (InetAddress/getByName "10.0.0.171"), :port 6454}
        sender-b {:host (InetAddress/getByName "10.0.0.172"), :port 6454}
        clock (atom 0)
        advance! (fn [delta] (swap! clock + delta))
        timeout dmx/merge-source-timeout-ns
        timed-rx (fn [sender bytes]
                   (assoc (merge-rx sender bytes) :timestamp @clock))
        state-a (first (logic-step nil
                                   merge-test-config
                                   (timed-rx sender-a [10 20 30])))
        _ (advance! 1000)
        state-b (first (logic-step state-a
                                   merge-test-config
                                   (timed-rx sender-b [5 25 15])))
        _ (advance! (- timeout 2000))
        state-c (first (logic-step state-b
                                   merge-test-config
                                   (timed-rx sender-a [11 21 31])))
        _ (advance! 5000)
        state-final (first (logic-step state-c
                                       merge-test-config
                                       (timed-rx sender-a [12 22 32])))]
    (is (= 2 (merge-source-count state-b)))
    (is (= 2 (merge-source-count state-c)))
    (is (= 1 (merge-source-count state-final)))
    (is (= [12 22 32] (merge-last-output state-final)))
    (is (zero? (bit-and (get-in state-final [:node :good-output-a 0])
                        dmx/good-output-merge-bit)))))

(deftest artdmx-cancel-merge-resets-sources
  (let [sender-a {:host (InetAddress/getByName "10.0.0.77"), :port 6454}
        sender-b {:host (InetAddress/getByName "10.0.0.78"), :port 6454}
        sender-c {:host (InetAddress/getByName "10.0.0.79"), :port 6454}
        [state-a _]
        (logic-step nil merge-test-config (merge-rx sender-a [1 1 1]))
        [state-b _]
        (logic-step state-a merge-test-config (merge-rx sender-b [2 2 2]))
        cancel
        {:type :rx, :sender sender-a, :packet {:op :artaddress, :command 0x01}}
        [state-c _] (logic-step state-b merge-test-config cancel)
        [state-final _]
        (logic-step state-c merge-test-config (merge-rx sender-c [7 7 7]))]
    (is (true? (get-in state-c [:dmx :merge :cancel-armed?])))
    (is (= 1 (merge-source-count state-final)))
    (is (= [7 7 7] (merge-last-output state-final)))
    (is (zero? (bit-and (get-in state-final [:node :good-output-a 0])
                        dmx/good-output-merge-bit)))
    (is (false? (get-in state-final [:dmx :merge :cancel-armed?])))))

(deftest dmx-keepalive-helpers-identify-stale-ports
  (testing "ports-needing-keepalive detects idle ports"
    (let [sender {:host (InetAddress/getByName "10.0.0.90"), :port 6454}
          [state _]
          (logic-step nil merge-test-config (merge-rx sender [100 100 100]))
          ;; Capture now AFTER step so updated-at is in the past
          now (+ (System/nanoTime) 1000000)                 ;; Add 1ms buffer for safety
          fresh-interval dmx/keepalive-default-interval-ns
          stale-interval 0]
      ;; With 0 interval, everything is stale (now > updated-at by at least
      ;; 1ms)
      (is (= 1 (count (dmx/ports-needing-keepalive state now stale-interval))))
      ;; With default interval (900ms), just-received data shouldn't be
      ;; stale
      (is (= 0
             (count (dmx/ports-needing-keepalive state now fresh-interval))))))
  (testing "keepalive-packets builds ArtDmx packets from stale entries"
    (let [sender {:host (InetAddress/getByName "10.0.0.91"), :port 6454}
          [state _]
          (logic-step nil merge-test-config (merge-rx sender [50 60 70]))
          ;; Capture now AFTER step with buffer
          now (+ (System/nanoTime) 1000000)
          stale (dmx/ports-needing-keepalive state now 0)
          packets (dmx/keepalive-packets stale)]
      (is (= 1 (count packets)))
      (let [{:keys [port-address packet]} (first packets)]
        (is (= 0 port-address))
        (is (= :artdmx (:op packet)))
        (is (= 3 (:length packet))))))
  (testing "keepalive constants are within spec range"
    (is (= 800000000 dmx/keepalive-min-interval-ns) "Minimum is 800ms")
    (is (= 1000000000 dmx/keepalive-max-interval-ns) "Maximum is 1000ms")
    (is (<= dmx/keepalive-min-interval-ns
            dmx/keepalive-default-interval-ns
            dmx/keepalive-max-interval-ns)
        "Default is between min and max")))

(deftest artipprog-programming-updates-network
  (let [persist (promise)
        config (assoc base-config
                 :programming
                 {:network   {:subnet-mask [255 0 0 0]}
                  :on-change (fn [event] (deliver persist event))})
        sender {:host (InetAddress/getByName "10.0.0.5"), :port 6454}
        packet {:op           :artipprog
                :command      0x97                          ; enable + program IP/mask/gateway/port
                :prog-ip      [10 0 0 50]
                :prog-sm      [255 255 0 0]
                :prog-port    0x2468
                :prog-gateway [10 0 0 1]}
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})]
    (is (= [10 0 0 50] (get-in state [:node :ip])))
    (is (= [10 0 0 50] (get-in state [:network :ip])))
    (is (= [255 255 0 0] (get-in state [:network :subnet-mask])))
    (is (= [10 0 0 1] (get-in state [:network :gateway])))
    (is (= 0x2468 (get-in state [:network :port])))
    (is (= 0x2468 (get-in state [:node :port])))
    (is (false? (get-in state [:network :dhcp?])))
    (is (= 1 (get-in state [:stats :ip-program-requests])))
    (let [reply (first actions)]
      (is (= :artipprogreply (get-in reply [:packet :op])))
      (is (= sender (:target reply)))
      (is (= [10 0 0 50] (get-in reply [:packet :ip])))
      (is (= [255 255 0 0] (get-in reply [:packet :subnet-mask])))
      (is (= [10 0 0 1] (get-in reply [:packet :gateway])))
      (is (= 0x2468 (get-in reply [:packet :port])))
      (is (false? (get-in reply [:packet :dhcp?]))))
    (let [event (deref persist 100 nil)]
      (is event)
      (is (= :artipprog (:event event)))
      (is (= {:ip          [10 0 0 50]
              :subnet-mask [255 255 0 0]
              :gateway     [10 0 0 1]
              :port        0x2468}
             (:changes event))))))

(deftest artipprog-reset-restores-network-defaults
  (let [events (atom [])
        defaults {:ip          [2 2 2 2]
                  :subnet-mask [255 0 0 0]
                  :gateway     [2 2 2 1]
                  :port        0x2222}
        config (assoc base-config
                 :programming
                 {:network defaults, :on-change #(swap! events conj %)})
        sender {:host (InetAddress/getByName "10.0.0.6"), :port 6454}
        program {:op           :artipprog
                 :command      0x97
                 :prog-ip      [3 3 3 3]
                 :prog-sm      [255 255 0 0]
                 :prog-port    0x3333
                 :prog-gateway [3 3 3 1]}
        reset {:op :artipprog, :command 0x88}
        [state-after-program _]
        (logic-step nil config {:type :rx, :sender sender, :packet program})
        [state-after-reset actions] (logic-step
                                      state-after-program
                                      config
                                      {:type :rx, :sender sender, :packet reset})
        reply (first actions)]
    (is (= [2 2 2 2] (get-in state-after-reset [:network :ip])))
    (is (= [255 0 0 0] (get-in state-after-reset [:network :subnet-mask])))
    (is (= [2 2 2 1] (get-in state-after-reset [:network :gateway])))
    (is (= 0x2222 (get-in state-after-reset [:network :port])))
    (is (false? (get-in state-after-reset [:network :dhcp?])))
    (is (= [2 2 2 2] (get-in reply [:packet :ip])))
    (is (= [255 0 0 0] (get-in reply [:packet :subnet-mask])))
    (is (= [2 2 2 1] (get-in reply [:packet :gateway])))
    (is (= 0x2222 (get-in reply [:packet :port])))
    (let [event (last @events)]
      (is (= :artipprog (:event event)))
      (is (= {:ip          [2 2 2 2]
              :subnet-mask [255 0 0 0]
              :gateway     [2 2 2 1]
              :port        0x2222}
             (:changes event))))))

(deftest artipprog-dhcp-command-only-toggles-dhcp
  (let [events (atom [])
        config (assoc base-config
                 :programming
                 {:network   {:ip          [5 5 5 5]
                              :subnet-mask [255 0 0 0]
                              :gateway     [5 5 5 1]
                              :port        0x5555}
                  :on-change #(swap! events conj %)})
        sender {:host (InetAddress/getByName "10.0.0.7"), :port 6454}
        packet {:op           :artipprog
                :command      0xC4
                :prog-ip      [1 1 1 1]
                :prog-sm      [255 255 0 0]
                :prog-port    0x1111
                :prog-gateway [1 1 1 1]}
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})
        reply (first actions)
        event (first @events)]
    (is (true? (get-in state [:network :dhcp?])))
    (is (= [5 5 5 5] (get-in state [:network :ip])))
    (is (= [255 0 0 0] (get-in state [:network :subnet-mask])))
    (is (= [5 5 5 1] (get-in state [:network :gateway])))
    (is (= 0x5555 (get-in state [:network :port])))
    (is (true? (get-in reply [:packet :dhcp?])))
    (is (= [5 5 5 5] (get-in reply [:packet :ip])))
    (is (= 0x5555 (get-in reply [:packet :port])))
    (is (= sender (:target reply)))
    (is event)
    (is (= :artipprog (:event event)))
    (is (= {:dhcp? true} (:changes event)))))

(deftest artipprog-dhcp-command-works-without-enable-bit
  (let [events (atom [])
        config (assoc base-config
                 :programming
                 {:network   {:ip          [6 6 6 6]
                              :subnet-mask [255 0 0 0]
                              :gateway     [6 6 6 1]
                              :port        0x6666}
                  :on-change #(swap! events conj %)})
        sender {:host (InetAddress/getByName "10.0.0.9"), :port 6454}
        packet {:op           :artipprog
                :command      0x40
                :prog-ip      [1 1 1 1]
                :prog-sm      [255 255 0 0]
                :prog-port    0x1111
                :prog-gateway [1 1 1 1]}
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})
        reply (first actions)
        event (first @events)]
    (is (true? (get-in state [:network :dhcp?])))
    (is (= [6 6 6 6] (get-in state [:network :ip])))
    (is (= [6 6 6 1] (get-in state [:network :gateway])))
    (is (= 0x6666 (get-in state [:network :port])))
    (is (true? (get-in reply [:packet :dhcp?])))
    (is (= sender (:target reply)))
    (is event)
    (is (= :artipprog (:event event)))
    (is (= {:dhcp? true} (:changes event)))))

(deftest artipprog-ignores-programming-without-enable-bit
  (let [events (atom [])
        config (assoc base-config
                 :programming
                 {:network   {:ip          [9 9 9 9]
                              :subnet-mask [255 0 0 0]
                              :gateway     [9 9 9 1]
                              :port        0x9999}
                  :on-change #(swap! events conj %)})
        sender {:host (InetAddress/getByName "10.0.0.8"), :port 6454}
        packet {:op           :artipprog
                :command      0x04
                :prog-ip      [1 2 3 4]
                :prog-sm      [255 255 0 0]
                :prog-port    0x1234
                :prog-gateway [1 1 1 1]}
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})
        reply (first actions)]
    (is (= [9 9 9 9] (get-in state [:network :ip])))
    (is (= [255 0 0 0] (get-in state [:network :subnet-mask])))
    (is (= [9 9 9 1] (get-in state [:network :gateway])))
    (is (= 0x9999 (get-in state [:network :port])))
    (is (false? (get-in state [:network :dhcp?])))
    (is (empty? @events))
    (is (= :artipprogreply (get-in reply [:packet :op])))
    (is (= [9 9 9 9] (get-in reply [:packet :ip])))
    (is (= [255 0 0 0] (get-in reply [:packet :subnet-mask])))
    (is (= [9 9 9 1] (get-in reply [:packet :gateway])))
    (is (= 0x9999 (get-in reply [:packet :port])))
    (is (false? (get-in reply [:packet :dhcp?])))))

(def rdm-empty-config
  (assoc base-config :rdm {:ports {0x0001 {:uids [], :port 1, :bind-index 1}}}))

(def rdm-test-config
  (assoc base-config
    :rdm
    {:ports {0x0001 {:uids [[0 1 2 3 4 5]], :port 1, :bind-index 1}}}))

(defn- tod-request
  [sender addresses]
  {:type   :rx
   :sender sender
   :packet {:op        :arttodrequest
            :net       0
            :add-count (count addresses)
            :addresses addresses}})

(defn- tod-control
  [sender address]
  {:type   :rx
   :sender sender
   :packet {:op :arttodcontrol, :net 0, :command 0x01, :address address}})

(defn- firmware-packet
  [{:keys [stage block-id bytes buffer firmware-length transfer]
    :or   {stage :first, block-id 0, transfer :firmware}}]
  (let [payload (cond buffer (let [dup (.duplicate ^ByteBuffer buffer)]
                               (doto (.slice dup) (.clear)))
                      bytes (ByteBuffer/wrap (byte-array bytes))
                      :else (ByteBuffer/allocate 0))
        view (.asReadOnlyBuffer payload)
        block-type (case stage
                     :first :firmware-first
                     :last :firmware-last
                     :firmware-continue)]
    {:op              :artfirmwaremaster
     :stage           stage
     :transfer        transfer
     :block-type      block-type
     :block-id        block-id
     :firmware-length (or firmware-length 0x0010)
     :data-length     (.remaining view)
     :data            view}))

(def ^:const firmware-header-bytes 1060)

(defn- wrap16-test
  [^long value]
  (loop [sum value]
    (let [carry (unsigned-bit-shift-right sum 16)
          truncated (bit-and sum 0xFFFF)]
      (if (zero? carry) truncated (recur (+ truncated carry))))))

(defn- ones-complement-data
  [bytes]
  (->> (partition-all 2 (map #(bit-and % 0xFF) bytes))
       (reduce (fn [acc [hi lo]]
                 (let [hi-byte (bit-shift-left (or hi 0) 8)
                       lo-byte (bit-and (or lo 0) 0xFF)
                       word (bit-or hi-byte lo-byte)]
                   (wrap16-test (+ acc word))))
               0)
       bit-not
       (bit-and 0xFFFF)))

(defn- chunk-byte-array
  [^bytes source chunk-sizes]
  (let [total (alength source)
        requested (if (seq chunk-sizes) chunk-sizes [total])]
    (loop [offset 0
           sizes requested
           acc []]
      (if (>= offset total)
        acc
        (let [remaining (- total offset)
              next-size (long (or (first sizes) remaining))
              chunk (int (max 0 (min next-size remaining)))
              sizes' (if (seq sizes) (next sizes) sizes)]
          (if (zero? chunk)
            (recur offset sizes' acc)
            (let [buf (.asReadOnlyBuffer (ByteBuffer/wrap source offset chunk))]
              (recur (+ offset chunk) sizes' (conj acc buf)))))))))

(defn- firmware-image
  [{:keys [data chunk-sizes]}]
  (let [payload-source (byte-array (map unchecked-byte (or data [0])))
        payload (if (odd? (alength payload-source))
                  (let [padded (byte-array (inc (alength payload-source)))]
                    (System/arraycopy payload-source
                                      0 padded
                                      0 (alength payload-source))
                    padded)
                  payload-source)
        payload-length (alength payload)
        payload-words (long (quot payload-length 2))
        total (+ firmware-header-bytes payload-length)
        words (long (quot total 2))
        checksum (ones-complement-data payload)
        header (byte-array firmware-header-bytes)]
    (aset header 0 (unchecked-byte (unsigned-bit-shift-right checksum 8)))
    (aset header 1 (unchecked-byte (bit-and checksum 0xFF)))
    (let [length-offset (- firmware-header-bytes 4)]
      (dotimes [idx 4]
        (let [shift (* 8 (- 3 idx))
              byte (bit-and (unsigned-bit-shift-right payload-words shift)
                            0xFF)]
          (aset header (+ length-offset idx) (unchecked-byte byte)))))
    (let [file-bytes (byte-array total)]
      (System/arraycopy header 0 file-bytes 0 firmware-header-bytes)
      (System/arraycopy payload
                        0
                        file-bytes
                        firmware-header-bytes
                        payload-length)
      {:firmware-length words
       :total-bytes     total
       :bytes           file-bytes
       :buffers         (chunk-byte-array file-bytes chunk-sizes)})))

(deftest arttodrequest-returns-todnak-when-empty
  (let [sender {:host (InetAddress/getByName "192.168.10.7"), :port 6454}
        [state actions]
        (logic-step nil rdm-empty-config (tod-request sender [0x01]))
        reply (first actions)]
    (is (= 1 (count actions)))
    (is (= :arttoddata (get-in reply [:packet :op])))
    (is (= 0xFF (get-in reply [:packet :command-response])))
    (is (empty? (get-in reply [:packet :tod])))
    (is (= 1 (get-in state [:stats :tod-requests])))))

(deftest arttodrequest-produces-arttoddata
  (let [sender {:host (InetAddress/getByName "192.168.10.5"), :port 6454}
        [state actions]
        (logic-step nil rdm-test-config (tod-request sender [0x01]))
        reply (first actions)]
    (is (= 1 (count actions)))
    (is (= sender (:target reply)))
    (is (= :arttoddata (get-in reply [:packet :op])))
    (is (= [[0 1 2 3 4 5]] (get-in reply [:packet :tod])))
    (is (= 1 (get-in state [:stats :tod-requests])))))

(deftest arttodcontrol-flush-returns-todnak
  (let [sender {:host (InetAddress/getByName "192.168.10.6"), :port 6454}
        [state actions]
        (logic-step nil rdm-test-config (tod-control sender 0x01))
        reply (first actions)]
    (is (= 1 (count actions)))
    (is (= :arttoddata (get-in reply [:packet :op])))
    (is (= sender (:target reply)))
    (is (= 0xFF (get-in reply [:packet :command-response])))
    (is (empty? (get-in reply [:packet :tod])))
    (is (= 1 (get-in state [:stats :tod-controls])))))

(deftest artrdmsub-dispatches-rdm-sub-callback
  (let [cb (promise)
        config
        (assoc base-config :callbacks {:rdm-sub (fn [ctx] (deliver cb ctx))})
        sender {:host (InetAddress/getByName "192.168.20.11"), :port 6454}
        packet (artrdmsub-packet {:command :get, :sub-count 2})
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})
        action (first actions)]
    (is (= :callback (:type action)))
    ((:fn action) (:payload action))
    (let [ctx (deref cb 100 nil)]
      (is (= sender (:sender ctx)))
      (is (= :artrdmsub (get-in ctx [:packet :op])))
      (is (= :get (get-in ctx [:packet :command])))
      (is (= {:first 0, :count 2, :last 1} (:sub-device-range ctx)))
      (is (= [{:index 0, :sub-device 0, :value nil}
              {:index 1, :sub-device 1, :value nil}]
             (:entries ctx)))
      (is (= {:type :rdm-sub, :phase :request} (:proxy ctx))))
    (is (= 1 (get-in state [:stats :rdm-sub-commands])))))

(deftest artrdmsub-request-emits-proxy-rdm-callback
  (let [rdm-cb (promise)
        sub-cb (promise)
        config (assoc base-config
                 :callbacks
                 {:rdm     (fn [ctx] (deliver rdm-cb ctx))
                  :rdm-sub (fn [ctx] (deliver sub-cb ctx))})
        sender {:host (InetAddress/getByName "192.168.20.15"), :port 6454}
        packet
        (artrdmsub-packet
          {:command :set, :sub-device 5, :values [0x1111 0x2222], :sub-count 2})
        [_ actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})]
    (is (= 2 (count actions)))
    (doseq [action actions] ((:fn action) (:payload action)))
    (doseq [p [rdm-cb sub-cb]]
      (is (some? (deref p 100 nil)) "callback not invoked"))
    (let [proxy (deref rdm-cb 100 nil)]
      (is (= {:type :rdm-sub, :phase :request} (:proxy proxy)))
      (is (= {:first 5, :count 2, :last 6} (:sub-device-range proxy)))
      (is (= [{:index 0, :sub-device 5, :value 0x1111}
              {:index 1, :sub-device 6, :value 0x2222}]
             (:entries proxy))))))

(deftest artrdm-packets-dispatch-to-callback
  (let [cb (promise)
        payload (let [buf (ByteBuffer/allocate 32)]
                  (dotimes [_ 27] (.put buf (byte 0)))
                  (.put buf 20 (byte 0x30))
                  (.put buf 24 (byte 1))
                  (.put buf 25 (byte 2))
                  (.put buf 26 (byte 3))
                  (.position buf 27)
                  (.flip buf)
                  buf)
        config (-> rdm-test-config
                   (assoc :callbacks {:rdm (fn [ctx] (deliver cb ctx))}))
        sender {:host (InetAddress/getByName "192.168.20.10"), :port 6454}
        packet {:op             :artrdm
                :rdm-version    1
                :fifo-available 8
                :fifo-max       16
                :net            0
                :command        0
                :address        0x01
                :payload        payload}
        [_ actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})
        action (first actions)]
    (is (= :callback (:type action)))
    (is (= sender (:sender (:payload action))))
    ((:fn action) (:payload action))
    (let [ctx (deref cb 100 nil)
          view (:payload (:packet ctx))
          arr (byte-array (.remaining view))]
      (.get view arr)
      (is (= sender (:sender ctx)))
      (is (= 0x30 (bit-and 0xFF (aget arr 20))))
      (is (= [1 2 3] (take-last 3 (vec arr)))))))

(deftest artrdm-invalid-command-class-rejected
  (let [payload (let [buf (ByteBuffer/allocate 32)]
                  (dotimes [_ 24] (.put buf (byte 0)))
                  (.put buf 20 (byte 0x10))
                  (.position buf 24)
                  (.flip buf)
                  buf)
        sender {:host (InetAddress/getByName "192.168.20.13"), :port 6454}
        packet {:op             :artrdm
                :rdm-version    1
                :fifo-available 4
                :fifo-max       16
                :net            0
                :command        0
                :address        0x01
                :payload        payload}
        [state actions] (logic-step
                          nil
                          rdm-test-config
                          {:type :rx, :sender sender, :packet packet})]
    (is (empty? actions))
    (is (= 1 (get-in state [:stats :rdm-invalid-command-class])))))

(deftest artrdm-response-command-classes-dispatch
  (doseq [command-class [0x21 0x31]]
    (let [cb (promise)
          config
          (assoc base-config :callbacks {:rdm (fn [ctx] (deliver cb ctx))})
          sender {:host (InetAddress/getByName "192.168.20.33"), :port 6454}
          payload (rdm-payload-buffer command-class)
          packet {:op             :artrdm
                  :rdm-version    1
                  :fifo-available 2
                  :fifo-max       4
                  :net            0
                  :command        0
                  :address        0x01
                  :payload        payload}
          [_ actions]
          (logic-step nil config {:type :rx, :sender sender, :packet packet})
          action (first actions)]
      (is (= :callback (:type action)))
      ((:fn action) (:payload action))
      (let [ctx (deref cb 100 nil)
            ^ByteBuffer view (:payload (:packet ctx))
            bytes (byte-array (.remaining view))]
        (.get view bytes)
        (is (= sender (:sender ctx)))
        (is (= command-class (bit-and 0xFF (aget bytes 20))))))))

(deftest send-rdm-produces-artnet-action
  (let [target {:host "127.0.0.1", :port 6454}
        command {:type         :command
                 :command      :send-rdm
                 :target       target
                 :port-address (common/compose-port-address 0 0 2)
                 :rdm-packet   (rdm-payload-buffer 0x30)}
        [state actions] (logic-step nil base-config command)
        action (first actions)
        packet (:packet action)
        ^ByteBuffer view (:rdm-packet packet)
        bytes (byte-array (.remaining view))]
    (.get view bytes)
    (is (= 1 (count actions)))
    (is (= :send (:type action)))
    (is (= target (:target action)))
    (is (= :artrdm (:op packet)))
    (is (.isReadOnly ^ByteBuffer view))
    (is (= 0x30 (bit-and 0xFF (aget bytes 20))))
    (is (= 1 (get-in state [:stats :tx-artrdm])))))

(deftest send-rdm-requires-target
  (let [command {:type    :command
                 :command :send-rdm
                 :payload (rdm-payload-buffer 0x20)}]
    (is (thrown-with-msg? ExceptionInfo
                          #"requires :target"
                          (logic-step nil base-config command)))))

(deftest send-rdm-validates-length
  (let [command {:type       :command
                 :command    :send-rdm
                 :target     {:host "127.0.0.1", :port 6454}
                 :rdm-packet (rdm-payload-buffer 0x20 22)}]
    (is (thrown-with-msg? ExceptionInfo
                          #"shorter"
                          (logic-step nil base-config command)))))

(deftest send-rdm-validates-command-class
  (let [command {:type       :command
                 :command    :send-rdm
                 :target     {:host "127.0.0.1", :port 6454}
                 :rdm-packet (rdm-payload-buffer 0x10)}]
    (is (thrown-with-msg? ExceptionInfo
                          #"Unsupported RDM command class"
                          (logic-step nil base-config command)))))

(deftest artrdmsub-falls-back-to-rdm-callback
  (let [cb (promise)
        config (assoc base-config :callbacks {:rdm (fn [ctx] (deliver cb ctx))})
        sender {:host (InetAddress/getByName "192.168.20.12"), :port 6454}
        packet (artrdmsub-packet
                 {:command :get-response, :values [0x1111 0x2222], :sub-count 2})
        [state actions]
        (logic-step nil config {:type :rx, :sender sender, :packet packet})
        action (first actions)]
    (is (= :callback (:type action)))
    ((:fn action) (:payload action))
    (let [ctx (deref cb 100 nil)]
      (is (= sender (:sender ctx)))
      (is (= :artrdmsub (get-in ctx [:packet :op])))
      (is (= :get-response (get-in ctx [:packet :command])))
      (is (= {:type :rdm-sub, :phase :response} (:proxy ctx)))
      (is (= [{:index 0, :sub-device 0, :value 0x1111}
              {:index 1, :sub-device 1, :value 0x2222}]
             (:entries ctx))))
    (is (= 1 (get-in state [:stats :rdm-sub-commands])))))

(deftest artrdmsub-invalid-command-class-dropped
  (let [sender {:host (InetAddress/getByName "192.168.20.16"), :port 6454}
        packet (-> (artrdmsub-packet {:command :get})
                   (assoc :command-class 0x10 :command nil))
        [state actions] (logic-step
                          nil
                          base-config
                          {:type :rx, :sender sender, :packet packet})]
    (is (empty? actions))
    (is (= 1 (get-in state [:stats :rdm-sub-invalid])))))

(deftest artrdmsub-invalid-length-dropped
  (let [sender {:host (InetAddress/getByName "192.168.20.17"), :port 6454}
        packet (-> (artrdmsub-packet {:command :set, :values [0x1111]})
                   (assoc :payload-length 0))
        [state actions] (logic-step
                          nil
                          base-config
                          {:type :rx, :sender sender, :packet packet})]
    (is (empty? actions))
    (is (= 1 (get-in state [:stats :rdm-sub-invalid])))))

(deftest artfirmwaremaster-acknowledges-blocks
  (let [chunk-stages (atom [])
        complete-calls (atom [])
        {:keys [bytes firmware-length total-bytes]} (firmware-image
                                                      {:data (repeat 256 0x5A)})
        first-size firmware/firmware-header-length
        last-size (- total-bytes first-size)
        first-buffer (.asReadOnlyBuffer
                       (ByteBuffer/wrap ^bytes bytes 0 first-size))
        last-buffer (.asReadOnlyBuffer
                      (ByteBuffer/wrap ^bytes bytes first-size last-size))
        config (assoc base-config
                 :firmware
                 {:on-chunk (fn [evt]
                              (swap! chunk-stages conj (:stage evt)))
                  :on-complete
                  (fn [evt] (swap! complete-calls conj (:stage evt)))})
        sender {:host (InetAddress/getByName "192.168.30.5"), :port 6454}
        first-packet (firmware-packet {:stage           :first
                                       :block-id        0
                                       :buffer          first-buffer
                                       :firmware-length firmware-length})
        [state actions] (logic-step
                          nil
                          config
                          {:type :rx, :sender sender, :packet first-packet})
        reply (first actions)]
    (is (= :artfirmwarereply (get-in reply [:packet :op])))
    (is (= :block-good (get-in reply [:packet :status])))
    (is (= 1 (get-in state [:stats :firmware-requests])))
    (is (= [:first] @chunk-stages))
    (is (empty? @complete-calls))
    (let [last-packet (firmware-packet {:stage           :last
                                        :block-id        1
                                        :buffer          last-buffer
                                        :firmware-length firmware-length})
          [state' actions2] (logic-step
                              state
                              config
                              {:type :rx, :sender sender, :packet last-packet})
          reply2 (first actions2)]
      (is (= :all-good (get-in reply2 [:packet :status])))
      (is (= 2 (get-in state' [:stats :firmware-requests])))
      (is (= [:first :last] @chunk-stages))
      (is (= [:complete] @complete-calls)))))

(deftest artfirmwaremaster-detects-length-mismatch
  (let [{:keys [buffers firmware-length]}
        (firmware-image {:data (repeat 1600 0x2A), :chunk-sizes [800 800 800]})
        [first-block second-block & _] buffers
        sender {:host (InetAddress/getByName "192.168.30.6"), :port 6454}
        config base-config
        first-packet (firmware-packet {:stage           :first
                                       :block-id        0
                                       :buffer          first-block
                                       :firmware-length firmware-length})
        [state _] (logic-step nil
                              config
                              {:type :rx, :sender sender, :packet first-packet})
        premature-last (firmware-packet {:stage           :last
                                         :block-id        1
                                         :buffer          second-block
                                         :firmware-length firmware-length})
        [failed-state actions]
        (logic-step state
                    config
                    {:type :rx, :sender sender, :packet premature-last})
        reply (first actions)]
    (is (= :artfirmwarereply (get-in reply [:packet :op])))
    (is (= :fail (get-in reply [:packet :status])))
    (is (= 2 (get-in failed-state [:stats :firmware-requests])))
    (is (empty? (get-in failed-state [:firmware :sessions])))))

(deftest artfirmwaremaster-detects-checksum-mismatch
  (let [{:keys [buffers firmware-length bytes]}
        (firmware-image {:data (range 256), :chunk-sizes [700 700 700]})
        _ (let [original (bit-and (aget ^bytes bytes 0) 0xFF)
                corrupted (bit-and (bit-xor original 0xFF) 0xFF)]
            (aset bytes 0 (unchecked-byte corrupted)))
        [first-block second-block third-block] buffers
        sender {:host (InetAddress/getByName "192.168.30.7"), :port 6454}
        config base-config
        first-packet (firmware-packet {:stage           :first
                                       :block-id        0
                                       :buffer          first-block
                                       :firmware-length firmware-length})
        [state _] (logic-step nil
                              config
                              {:type :rx, :sender sender, :packet first-packet})
        mid-packet (firmware-packet {:stage           :firmware-continue
                                     :block-id        1
                                     :buffer          second-block
                                     :firmware-length firmware-length})
        [state' _] (logic-step state
                               config
                               {:type :rx, :sender sender, :packet mid-packet})
        final-packet (firmware-packet {:stage           :last
                                       :block-id        2
                                       :buffer          third-block
                                       :firmware-length firmware-length})
        [final-state actions]
        (logic-step state'
                    config
                    {:type :rx, :sender sender, :packet final-packet})
        reply (first actions)]
    (is (= :artfirmwarereply (get-in reply [:packet :op])))
    (is (= :fail (get-in reply [:packet :status])))
    (is (= 3 (get-in final-state [:stats :firmware-requests])))
    (is (empty? (get-in final-state [:firmware :sessions])))))
