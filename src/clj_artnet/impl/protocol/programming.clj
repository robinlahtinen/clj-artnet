;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.programming
  "Implements Art-Net remote programming semantics for ArtAddress, ArtInput, and ArtIpProg.

   Provides pure functions to transform node state based on remote programming commands,
   including diagnostic acknowledgement generation."
  (:require
    [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def ^:private indicator->bits {:normal 0x03, :mute 0x02, :locate 0x01})

(def ^:private failsafe->bits {:hold 0x00, :zero 0x01, :full 0x02, :scene 0x03})
(def ^:const artinput-disable-bit 0x01)
(def ^:const good-input-disabled-bit 0x10)
(def ^:const good-output-sacn-bit 0x01)
(def ^:const good-outputb-rdm-disabled-bit 0x80)
(def ^:const good-outputb-continuous-bit 0x40)

(def ^:const good-output-ltp-bit 0x02)

(defn- valid-port-index? [idx] (and (int? idx) (<= 0 idx) (< idx 4)))

(defn- set-port-field-bit
  [node field port bit set?]
  (if-not (valid-port-index? port)
    [node false nil]
    (let [ports (vec (or (get node field) (repeat 4 0)))
          current (nth ports port 0)
          next (bit-and (int (if set?
                               (bit-or current bit)
                               (bit-and current (bit-not bit))))
                        0xFF)
          updated (assoc ports port next)
          changed? (not= ports updated)
          node' (if changed? (assoc node field updated) node)]
      [node' changed? updated])))

(defn- set-good-output-bit
  [node port bit set?]
  (set-port-field-bit node :good-output-a port bit set?))

(defn- set-good-output-b-bit
  [node port bit set?]
  (set-port-field-bit node :good-output-b port bit set?))

(defn- trim-ascii
  [s]
  (let [value (-> (or s "")
                  (str/replace #"\u0000+$" "")
                  str/trim)]
    (when (seq value) value)))

(defn- flagged-update
  "Interprets ArtAddress update semantics.
   Returns {:type :set/:reset :value v} or nil to ignore."
  [value mask]
  (let [b (bit-and (int (or value 0)) 0xFF)]
    (cond (zero? b) {:type :reset}
          (pos? (bit-and b 0x80)) {:type :set, :value (bit-and b mask)}
          :else nil)))

(defn- apply-switch-array
  [current defaults updates]
  (if-not (seq updates)
    [current false]
    (let [limit (min (count current) (count updates))]
      (loop [idx 0
             acc current
             changed? false]
        (if (>= idx limit)
          [acc changed?]
          (let [directive (flagged-update (nth updates idx) 0x0F)]
            (case (:type directive)
              :set (recur (inc idx) (assoc acc idx (:value directive)) true)
              :reset (recur (inc idx) (assoc acc idx (nth defaults idx 0)) true)
              (recur (inc idx) acc changed?))))))))

(defn- apply-netswitch
  [current default raw mask]
  (if-let [directive (flagged-update raw mask)]
    (case (:type directive)
      :set (:value directive)
      :reset default)
    current))

(defn- set-indicator-state
  [status mode]
  (let [bits (get indicator->bits mode)
        base (bit-and (int (or status 0)) 0x3F)]
    (if bits (bit-or base (bit-and (bit-shift-left bits 6) 0xC0)) base)))

(defn- set-failsafe-state
  [status3 mode]
  (let [bits (get failsafe->bits mode)
        base (bit-and (int (or status3 0)) 0x3F)]
    (if bits
      (-> base
          (bit-or (bit-shift-left bits 6))
          (bit-or 0x20))
      (int (or status3 0)))))

(defn- set-port-direction
  [port-types idx dir]
  (let [ports (vec (or port-types [0 0 0 0]))
        ptype (nth ports idx 0)
        cleared (bit-and ptype 0x3F)
        next (case dir
               :tx (bit-or cleared 0x80)
               :rx (bit-or cleared 0x40)
               ptype)]
    (assoc ports idx next)))

(defn- direction-command
  [command]
  (cond (<= 0x20 command 0x23) {:direction :tx, :port (- command 0x20)}
        (<= 0x30 command 0x33) {:direction :rx, :port (- command 0x30)}
        :else nil))

(defn- merge-mode-command
  [command]
  (cond (<= 0x10 command 0x13) {:mode :ltp, :port (- command 0x10)}
        (<= 0x50 command 0x53) {:mode :htp, :port (- command 0x50)}
        :else nil))

(defn- protocol-select-command
  [command]
  (cond (<= 0x60 command 0x63) {:protocol :artnet, :port (- command 0x60)}
        (<= 0x70 command 0x73) {:protocol :sacn, :port (- command 0x70)}
        :else nil))

(defn- clear-output-command
  [command]
  (when (<= 0x90 (int command) 0x93) {:port (- (int command) 0x90)}))

(defn- output-style-command
  [command]
  (cond (<= 0xA0 (int command) 0xA3) {:style :delta
                                      :port  (- (int command) 0xA0)}
        (<= 0xB0 (int command) 0xB3) {:style :constant
                                      :port  (- (int command) 0xB0)}
        :else nil))

(defn- rdm-state-command
  [command]
  (cond (<= 0xC0 (int command) 0xC3) {:state :enable
                                      :port  (- (int command) 0xC0)}
        (<= 0xD0 (int command) 0xD3) {:state :disable
                                      :port  (- (int command) 0xD0)}
        :else nil))

(defn- apply-command
  [node command]
  (let [cmd (some-> command
                    int
                    (bit-and 0xFF))
        direction-cmd (when cmd (direction-command cmd))
        merge-cmd (when cmd (merge-mode-command cmd))
        protocol (when cmd (protocol-select-command cmd))
        clear-cmd (when cmd (clear-output-command cmd))
        style-cmd (when cmd (output-style-command cmd))
        rdm-cmd (when cmd (rdm-state-command cmd))]
    (cond
      (= cmd 0x01) [node nil
                    {:description     :cancel-merge
                     :applied?        true
                     :merge-directive {:type :cancel}}]
      (= cmd 0x02) (let [status (set-indicator-state (:status1 node) :normal)]
                     [(assoc node :status1 status) {:status1 status}
                      {:description :led-normal, :applied? true}])
      (= cmd 0x03) (let [status (set-indicator-state (:status1 node) :mute)]
                     [(assoc node :status1 status) {:status1 status}
                      {:description :led-mute, :applied? true}])
      (= cmd 0x04) (let [status (set-indicator-state (:status1 node) :locate)]
                     [(assoc node :status1 status) {:status1 status}
                      {:description :led-locate, :applied? true}])
      (= cmd 0x08) (let [status (set-failsafe-state (:status3 node) :hold)]
                     [(assoc node :status3 status) {:status3 status}
                      {:description :failsafe-hold, :applied? true}])
      (= cmd 0x09) (let [status (set-failsafe-state (:status3 node) :zero)]
                     [(assoc node :status3 status) {:status3 status}
                      {:description :failsafe-zero, :applied? true}])
      (= cmd 0x0A) (let [status (set-failsafe-state (:status3 node) :full)]
                     [(assoc node :status3 status) {:status3 status}
                      {:description :failsafe-full, :applied? true}])
      (= cmd 0x0B) (let [status (set-failsafe-state (:status3 node) :scene)]
                     [(assoc node :status3 status) {:status3 status}
                      {:description :failsafe-scene, :applied? true}])
      (= cmd 0x0C) [node nil
                    {:description        :failsafe-record
                     :applied?           true
                     :failsafe-directive :record}]
      clear-cmd (let [{:keys [port]} clear-cmd
                      valid-port? (valid-port-index? port)
                      info {:description      :output-clear
                            :applied?         valid-port?
                            :port-index       port
                            :output-directive (when valid-port?
                                                {:type :clear-buffer
                                                 :port port})}]
                  [node nil info])
      style-cmd
      (let [{:keys [style port]} style-cmd
            valid-port? (valid-port-index? port)
            desc
            (if (= style :constant) :output-style-constant :output-style-delta)]
        (if-not valid-port?
          [node nil
           {:description desc, :applied? false, :port-index port, :style style}]
          (let [[node' changed? ports] (set-good-output-b-bit
                                         node
                                         port
                                         good-outputb-continuous-bit
                                         (= style :constant))
                info {:description desc
                      :applied?    (boolean changed?)
                      :port-index  port
                      :style       style}
                changes (when changed? {:good-output-b ports})]
            [node' changes info])))
      rdm-cmd
      (let [{:keys [state port]} rdm-cmd
            valid-port? (valid-port-index? port)
            disable? (= state :disable)
            desc (if disable? :rdm-disable :rdm-enable)]
        (if-not valid-port?
          [node nil
           {:description desc, :applied? false, :port-index port, :state state}]
          (let [[node' changed? ports] (set-good-output-b-bit
                                         node
                                         port
                                         good-outputb-rdm-disabled-bit
                                         disable?)
                info {:description desc
                      :applied?    (boolean changed?)
                      :port-index  port
                      :state       state}
                changes (when changed? {:good-output-b ports})]
            [node' changes info])))
      direction-cmd
      (let [{:keys [direction port]} direction-cmd
            valid-port? (and (number? port) (<= 0 port) (< port 4))]
        (if-not valid-port?
          [node nil
           {:description :port-direction, :applied? false, :port-index port}]
          (let [ports (vec (or (:port-types node) [0 0 0 0]))
                next (set-port-direction ports port direction)
                before (nth ports port 0)
                after (nth next port 0)
                changed? (not= before after)
                info {:description
                      (if (= direction :tx) :port-output :port-input)
                      :applied?           changed?
                      :port-index         port
                      :direction          direction
                      :flush-subscribers? (= direction :rx)}]
            (if changed?
              [(assoc node :port-types next) {:port-types next} info]
              [node nil info]))))
      merge-cmd (let [{:keys [mode port]} merge-cmd
                      valid-port? (valid-port-index? port)]
                  (if-not valid-port?
                    [node nil
                     {:description :merge-mode
                      :applied?    false
                      :port-index  port
                      :mode        mode}]
                    (let [[node' changed? ports] (set-good-output-bit
                                                   node
                                                   port
                                                   good-output-ltp-bit
                                                   (= mode :ltp))
                          info {:description :merge-mode
                                :applied?    (boolean changed?)
                                :port-index  port
                                :mode        mode
                                :merge-directive
                                {:type :set-mode, :port port, :mode mode}}
                          changes (when changed? {:good-output-a ports})]
                      [node' changes info])))
      protocol
      (let [{:keys [protocol port]} protocol
            valid-port? (valid-port-index? port)
            sacn? (= protocol :sacn)]
        (if-not valid-port?
          [node nil
           {:description :output-protocol
            :applied?    false
            :port-index  port
            :protocol    protocol}]
          (let [[node' changed? ports]
                (set-good-output-bit node port good-output-sacn-bit sacn?)
                info {:description :output-protocol
                      :applied?    (boolean changed?)
                      :port-index  port
                      :protocol    protocol
                      :output-directive
                      {:type :set-protocol, :port port, :protocol protocol}}
                changes (when changed? {:good-output-a ports})]
            [node' changes info])))
      (and cmd (<= 0xE0 cmd 0xEF))
      (let [policy (- cmd 0xE0)
            clamped (bit-and policy 0x0F)
            current (int (or (:background-queue-policy node) 0))
            changed? (not= current clamped)
            node' (cond-> node
                          changed? (assoc :background-queue-policy clamped))
            changes (when changed? {:background-queue-policy clamped})
            info {:description   :background-queue-policy
                  :applied?      changed?
                  :value         clamped
                  :rdm-directive {:type   :set-background-queue-policy
                                  :policy clamped}}]
        [node' changes info])
      :else [node nil
             (when command {:description :unsupported, :applied? false})])))

(defn apply-artaddress
  "Applies ArtAddress packet fields to the node state.

   Returns {:node node' :changes {...} :command-info {...}}."
  [node defaults packet]
  (let [{:keys [short-name long-name net-switch sub-switch sw-in sw-out
                acn-priority command]}
        packet
        short (trim-ascii short-name)
        long (trim-ascii long-name)
        current-short (:short-name node)
        current-long (:long-name node)
        [sw-in' sw-in-changed?]
        (apply-switch-array (:sw-in node) (:sw-in defaults) sw-in)
        [sw-out' sw-out-changed?]
        (apply-switch-array (:sw-out node) (:sw-out defaults) sw-out)
        net' (apply-netswitch (:net-switch node)
                              (:net-switch defaults)
                              net-switch
                              0x7F)
        sub' (apply-netswitch (:sub-switch node)
                              (:sub-switch defaults)
                              sub-switch
                              0x0F)
        priority (int (or acn-priority 0xFF))
        priority' (cond (<= 0 priority 200) priority
                        (= priority 0xFF) nil
                        :else 200)
        node' (cond-> node
                      (and short (not= short current-short)) (assoc :short-name short)
                      (and long (not= long current-long)) (assoc :long-name long)
                      (not= net' (:net-switch node)) (assoc :net-switch net')
                      (not= sub' (:sub-switch node)) (assoc :sub-switch sub')
                      sw-in-changed? (assoc :sw-in sw-in')
                      sw-out-changed? (assoc :sw-out sw-out')
                      (and priority' (not= priority' (:acn-priority node)))
                      (assoc :acn-priority priority'))
        base-changes
        (cond-> {}
                (and short (not= short current-short)) (assoc :short-name short)
                (and long (not= long current-long)) (assoc :long-name long)
                (not= net' (:net-switch node)) (assoc :net-switch net')
                (not= sub' (:sub-switch node)) (assoc :sub-switch sub')
                sw-in-changed? (assoc :sw-in sw-in')
                sw-out-changed? (assoc :sw-out sw-out')
                (and priority' (not= priority' (:acn-priority node)))
                (assoc :acn-priority priority'))
        [node-final command-changes command-info] (apply-command node' command)
        merged (merge base-changes (or command-changes {}))]
    {:node         node-final
     :changes      (not-empty merged)
     :command-info (when command command-info)}))

(def ^:private artipprog-defaults
  {:ip          [0 0 0 0]
   :subnet-mask [255 0 0 0]
   :gateway     [0 0 0 0]
   :port        0x1936
   :dhcp?       false})

(defn apply-artipprog
  "Applies ArtIpProg semantics, producing state changes and reply map."
  [{:keys [node network network-defaults packet]}]
  (let [{:keys [command prog-ip prog-sm prog-gateway prog-port]} packet
        node (or node {})
        network (or network {})
        default-overrides (select-keys (or network-defaults {})
                                       [:ip :subnet-mask :gateway :port :dhcp?])
        defaults (merge artipprog-defaults default-overrides)
        cmd (bit-and (int (or command 0)) 0xFF)
        enable? (pos? (bit-and cmd 0x80))
        dhcp-command? (pos? (bit-and cmd 0x40))
        reset? (and enable? (pos? (bit-and cmd 0x08)))
        program-ip? (and enable? (pos? (bit-and cmd 0x04)))
        program-mask? (and enable? (pos? (bit-and cmd 0x02)))
        program-port? (and enable? (pos? (bit-and cmd 0x01)))
        program-gateway? (and enable? (pos? (bit-and cmd 0x10)))
        ip-bytes (vec (or prog-ip (:ip network) (:ip defaults)))
        mask-bytes (vec
                     (or prog-sm (:subnet-mask network) (:subnet-mask defaults)))
        gateway-bytes (vec
                        (or prog-gateway (:gateway network) (:gateway defaults)))
        port-value
        (bit-and (int (or prog-port (:port network) (:port defaults))) 0xFFFF)
        network' (cond
                   dhcp-command? (assoc network :dhcp? true)
                   reset? (merge network defaults)
                   (or program-ip? program-mask? program-port? program-gateway?)
                   (-> network
                       (cond-> program-ip? (assoc :ip ip-bytes))
                       (cond-> program-mask? (assoc :subnet-mask mask-bytes))
                       (cond-> program-gateway? (assoc :gateway gateway-bytes))
                       (cond-> program-port? (assoc :port port-value))
                       (cond-> (or program-ip? program-mask? program-gateway?)
                               (assoc :dhcp? false)))
                   :else network)
        ip-reply (or (:ip network') (:ip defaults))
        mask-reply (or (:subnet-mask network') (:subnet-mask defaults))
        gateway-reply (or (:gateway network') (:gateway defaults))
        port-reply (:port network' (:port defaults))
        node' (-> node
                  (assoc :ip ip-reply)
                  (assoc :port port-reply))
        changes (cond-> {}
                        (not= (:ip network) (:ip network')) (assoc :ip ip-reply)
                        (not= (:subnet-mask network) (:subnet-mask network'))
                        (assoc :subnet-mask (:subnet-mask network'))
                        (not= (:gateway network) (:gateway network'))
                        (assoc :gateway (:gateway network'))
                        (not= (:port network) (:port network'))
                        (assoc :port (:port network'))
                        (not= (:dhcp? network) (:dhcp? network'))
                        (assoc :dhcp? (:dhcp? network')))
        status-old (int (or (:status2 node) 0))
        status2 (if (:dhcp? network')
                  (bit-or status-old 0x02)
                  (bit-and status-old 0xFD))
        node-final (assoc node' :status2 status2)
        reply {:op          :artipprogreply
               :ip          ip-reply
               :subnet-mask mask-reply
               :gateway     gateway-reply
               :port        port-reply
               :dhcp?       (:dhcp? network')}]
    {:node    node-final
     :network network'
     :changes (not-empty changes)
     :dhcp?   (:dhcp? network')
     :reply   reply}))

(defn- normalize-disable-flags
  [inputs disabled]
  (let [source (cond (seq disabled) disabled
                     (seq inputs) (map #(pos? (bit-and (int (or % 0))
                                                       artinput-disable-bit))
                                       inputs)
                     :else (repeat false))]
    (->> (concat source (repeat false))
         (take 4)
         (mapv boolean))))

(defn- apply-disable-flags
  [good-input disable-flags]
  (vec (map (fn [byte disable?]
              (let [base (bit-and (int (or byte 0)) 0xFF)
                    cleared (bit-and base (bit-not good-input-disabled-bit))
                    cleared (bit-and cleared 0xFF)]
                (if disable?
                  (bit-and (bit-or cleared good-input-disabled-bit) 0xFF)
                  cleared)))
            (->> (concat good-input (repeat 0))
                 (take 4))
            disable-flags)))

(defn apply-artinput
  "Applies ArtInput disable directives to the node's GoodInput field.

  Returns {:node node' :disabled [...] :changes {...} :applied-bind-index n
           :applied-to-base? boolean}."
  [{:keys [node packet target-bind-index]}]
  (let [{:keys [inputs disabled bind-index]} packet
        disable-flags (normalize-disable-flags inputs disabled)
        good-input (vec (:good-input node [0 0 0 0]))
        next-good (apply-disable-flags good-input disable-flags)
        requested-bind (max 1 (int (or target-bind-index bind-index 1)))
        base-bind (max 1 (int (:bind-index node 1)))
        apply-base? (= requested-bind base-bind)
        node-final (if apply-base? (assoc node :good-input next-good) node)
        changes (when (and apply-base? (not= good-input next-good))
                  {:good-input next-good})]
    {:node               node-final
     :disabled           disable-flags
     :changes            changes
     :applied-bind-index requested-bind
     :applied-to-base?   apply-base?}))

(defn describe-artaddress-command
  "Converts ArtAddress command-info to a human-readable diagnostic string.

  Returns nil if no description is applicable."
  [info packet]
  (when info
    (let [{:keys [description applied? port-index mode protocol value style]}
          info
          command (bit-and (int (or (:command packet) 0)) 0xFF)
          indicator-text (fn [label]
                           (if applied?
                             (format "LED indicators set to %s" label)
                             (format "LED indicators already %s" label)))
          failsafe-text (fn [label]
                          (let [word (str/capitalize label)]
                            (if applied?
                              (format "Failsafe mode set to %s" word)
                              (format "Failsafe mode already %s" word))))
          merge-label (some-> mode
                              name
                              str/upper-case)
          protocol-label (fn [proto]
                           (case proto
                             :sacn "sACN"
                             :artnet "Art-Net"
                             (some-> proto
                                     name
                                     str/capitalize)))
          style-label (fn [m]
                        (case m
                          :constant "continuous"
                          :delta "delta"
                          nil))
          port-valid? (valid-port-index? port-index)
          invalid-port (format "Invalid port index %s"
                               (if (some? port-index) port-index "unknown"))
          text
          (case description
            :cancel-merge "Merge cancel armed"
            :led-normal (indicator-text "normal")
            :led-mute (indicator-text "mute")
            :led-locate (indicator-text "locate")
            :failsafe-hold (failsafe-text "hold")
            :failsafe-zero (failsafe-text "zero")
            :failsafe-full (failsafe-text "full")
            :failsafe-scene (failsafe-text "scene")
            :failsafe-record "Failsafe scene capture requested"
            :output-clear (if port-valid?
                            (if applied?
                              (format "Port %d DMX buffer cleared" port-index)
                              (format "Port %d DMX buffer already clear"
                                      port-index))
                            (str invalid-port " for clear command"))
            :output-style-constant
            (if port-valid?
              (let [label (or (style-label style) "continuous")]
                (if applied?
                  (format "Port %d output style set to %s" port-index label)
                  (format "Port %d output style already %s" port-index label)))
              (str invalid-port " for output style command"))
            :output-style-delta
            (if port-valid?
              (let [label (or (style-label style) "delta")]
                (if applied?
                  (format "Port %d output style set to %s" port-index label)
                  (format "Port %d output style already %s" port-index label)))
              (str invalid-port " for output style command"))
            :rdm-enable (if port-valid?
                          (if applied?
                            (format "Port %d RDM enabled" port-index)
                            (format "Port %d RDM already enabled" port-index))
                          (str invalid-port " for RDM command"))
            :rdm-disable (if port-valid?
                           (if applied?
                             (format "Port %d RDM disabled" port-index)
                             (format "Port %d RDM already disabled" port-index))
                           (str invalid-port " for RDM command"))
            :port-output (when (some? port-index)
                           (if applied?
                             (format "Port %d set to output" port-index)
                             (format "Port %d already set to output"
                                     port-index)))
            :port-input (when (some? port-index)
                          (if applied?
                            (format "Port %d set to input" port-index)
                            (format "Port %d already set to input" port-index)))
            :port-direction (format
                              "Invalid port index %s for direction command"
                              (if (some? port-index) port-index "unknown"))
            :merge-mode
            (when (some? port-index)
              (if merge-label
                (if applied?
                  (format "Port %d merge mode set to %s" port-index merge-label)
                  (format "Port %d merge mode already %s"
                          port-index
                          merge-label))
                (format "Port %d merge mode command ignored" port-index)))
            :output-protocol
            (when (some? port-index)
              (let [label (or (protocol-label protocol) "requested protocol")]
                (if applied?
                  (format "Port %d protocol set to %s" port-index label)
                  (format "Port %d protocol already %s" port-index label))))
            :background-queue-policy
            (let [policy (bit-and (int (or value 0)) 0x0F)]
              (if applied?
                (format "BackgroundQueuePolicy set to %d" policy)
                (format "BackgroundQueuePolicy already %d" policy)))
            :unsupported (format "Unsupported ArtAddress command 0x%02X"
                                 command)
            nil)]
      text)))

(defn artaddress-acknowledgements
  "Generates diagnostic acknowledgement entries for ArtAddress command-info.

  Returns a vector of {:text ... :priority ...} maps."
  [info packet]
  (if-let [text (describe-artaddress-command info packet)]
    [{:text text, :priority (if (true? (:applied? info)) 0x10 0x80)}]
    []))

(comment
  (require '[clj-artnet.impl.protocol.programming :as programming] :reload)
  ;; Apply ArtAddress changes (no command)
  (programming/apply-artaddress {:short-name "Old"} {} {:short-name "New"})
  ;; => {:node {:short-name "New"} ...}
  ;; Generate acknowledgement
  (programming/artaddress-acknowledgements {:applied?    true
                                            :description :led-normal}
                                           {})
  ;; => [{:text "LED indicators set to normal", :priority 0x10}]
  :rcf)
