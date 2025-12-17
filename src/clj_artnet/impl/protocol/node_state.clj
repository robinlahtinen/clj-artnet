;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.node-state
  "Manages Art-Net node state, including normalization, paging, and status bits.

   Provides pure functions for configuring and transforming node state,
   ensuring valid bitmasks and default values."
  (:require
    [clj-artnet.impl.protocol.codec.constants :as const]
    [clj-artnet.impl.protocol.codec.domain.common :as common]
    [clojure.string :as str]))

(def ^:const status2-rdm-artaddress-bit 0x80)
(def ^:const status2-output-style-bit 0x40)
(def ^:const status2-extended-port-bit 0x08)
(def ^:const status2-dhcp-capable-bit 0x04)
(def ^:const status2-dhcp-active-bit 0x02)
(def ^:const status3-programmable-failsafe-bit 0x20)
(def ^:const status3-llrp-bit 0x10)
(def ^:const status3-port-direction-bit 0x08)
(def ^:const status3-rdmnet-bit 0x04)
(def ^:const status3-background-queue-bit 0x02)

(def ^:const good-outputb-rdm-disabled-bit 0x80)
(def ^:const good-outputb-continuous-bit 0x40)

(def ^:const good-outputb-discovery-idle-bit 0x20)
(def ^:const good-outputb-background-disabled-bit 0x10)
(def ^:const good-outputb-discovery-clear-mask
  (bit-or good-outputb-discovery-idle-bit good-outputb-background-disabled-bit))
(def ^:const default-good-output-b-byte
  (-> 0
      (bit-or good-outputb-rdm-disabled-bit)
      (bit-or good-outputb-continuous-bit)
      (bit-or good-outputb-discovery-idle-bit)
      (bit-or good-outputb-background-disabled-bit)))

(def ^:const status2-derived-mask
  (bit-or status2-rdm-artaddress-bit
          status2-output-style-bit
          status2-extended-port-bit
          status2-dhcp-capable-bit
          status2-dhcp-active-bit))

(def ^:const status3-derived-mask
  (bit-or status3-programmable-failsafe-bit
          status3-llrp-bit
          status3-port-direction-bit
          status3-rdmnet-bit
          status3-background-queue-bit))

(defn ^:dynamic *system-nano-time*
  "Gets current system time in nanoseconds.
   Override for deterministic testing."
  []
  (System/nanoTime))

(defn nano-time
  "Gets current nano time from event or system default."
  [event]
  (or (:timestamp event) (*system-nano-time*)))

(def ^:private status2-bit-aliases
  {:rdm-artaddress status2-rdm-artaddress-bit
   :output-style   status2-output-style-bit
   :extended-port  status2-extended-port-bit
   :dhcp-capable   status2-dhcp-capable-bit
   :dhcp-active    status2-dhcp-active-bit})

(def ^:private status3-bit-aliases
  {:programmable-failsafe status3-programmable-failsafe-bit
   :llrp                  status3-llrp-bit
   :port-direction        status3-port-direction-bit
   :rdmnet                status3-rdmnet-bit
   :background-queue      status3-background-queue-bit})

(defn- status-mask-value
  [aliases context field value]
  (cond (nil? value) nil
        (number? value) (bit-and (int value) 0xFF)
        (keyword? value)
        (if-let [bit (get aliases value)]
          bit
          (throw (ex-info "Unknown status bit keyword"
                          {:context context, :field field, :value value})))
        (set? value) (status-mask-value aliases context field (seq value))
        (sequential? value)
        (let [masks (keep #(status-mask-value aliases context field %) value)]
          (when (seq masks) (reduce bit-or 0 masks)))
        :else (throw (ex-info "Unsupported status override value"
                              {:context context, :field field, :value value}))))

(defn- normalize-status-override
  [aliases overrides context]
  (when (some? overrides)
    (when-not (map? overrides)
      (throw (ex-info "Status override must be a map"
                      {:context context, :value overrides})))
    (let [override
          (status-mask-value aliases context :override (:override overrides))
          set-mask (status-mask-value aliases context :set (:set overrides))
          clear-mask
          (status-mask-value aliases context :clear (:clear overrides))
          normalized (cond-> {}
                             (some? override) (assoc :override override)
                             (some? set-mask) (assoc :set set-mask)
                             (some? clear-mask) (assoc :clear clear-mask))]
      (not-empty normalized))))

(defn normalize-capabilities-config
  [capabilities]
  (let [config (or capabilities {})
        status2 (normalize-status-override status2-bit-aliases
                                           (:status2 config)
                                           :status2)
        status3 (normalize-status-override status3-bit-aliases
                                           (:status3 config)
                                           :status3)
        normalized (cond-> {}
                           status2 (assoc :status2 status2)
                           status3 (assoc :status3 status3))]
    normalized))

(defn- ensure-width
  [coll width fill]
  (->> (concat (or coll []) (repeat fill))
       (take width)
       (mapv #(bit-and (int %) 0xFF))))

(defn normalize-node
  "Normalizes node configuration map to ensure all required fields function correctly.

   Populates defaults for required Art-Net node properties like IP, port,
   status bits, and port definitions."
  [node]
  (-> {:ip                      [0 0 0 0]
       :bind-ip                 nil
       :port                    0x1936
       :version-hi              0
       :version-lo              0
       :net-switch              0
       :sub-switch              0
       :oem                     0xFFFF
       :ubea-version            0
       :status1                 0
       :esta-man                const/esta-man-prototype-id
       :short-name              "clj-artnet"
       :long-name               "clj-artnet node"
       :node-report             "#0001 [0001] Startup"
       :num-ports               1
       :port-types              [0xC0 0 0 0]
       :good-input              [0 0 0 0]
       :good-output-a           [0 0 0 0]
       :good-output-b           (vec (repeat 4 default-good-output-b-byte))
       :sw-in                   [0 0 0 0]
       :sw-out                  [0 0 0 0]
       :acn-priority            100
       :sw-macro                0
       :sw-remote               0
       :style                   0
       :mac                     [0 0 0 0 0 0]
       :bind-index              1
       :status2                 (bit-or status2-extended-port-bit status2-output-style-bit)
       :status3                 status3-port-direction-bit
       :default-responder       [0 0 0 0 0 0]
       :user-hi                 0
       :user-lo                 0
       :refresh-rate            0
       :background-queue-policy 0}
      (merge node)
      (update :ip ensure-width 4 0)
      (update :bind-ip #(when % (ensure-width % 4 0)))
      (update :mac ensure-width 6 0)
      (update :port-types ensure-width 4 0)
      (update :good-input ensure-width 4 0)
      (update :good-output-a ensure-width 4 0)
      (update :good-output-b ensure-width 4 default-good-output-b-byte)
      (update :sw-in ensure-width 4 0)
      (update :sw-out ensure-width 4 0)
      (update :default-responder ensure-width 6 0)
      (update :bind-index #(max 1 (int %)))))

(defn default-network-state
  "Constructs default network state from node config and overrides."
  [node overrides]
  (merge {:ip          (:ip node)
          :port        (:port node 0x1936)
          :subnet-mask [255 0 0 0]
          :gateway     [0 0 0 0]
          :dhcp?       false}
         overrides))

(defn- blank-port-fields
  [node]
  (reduce (fn [acc k]
            (let [fill (if (= k :good-output-b) default-good-output-b-byte 0)]
              (assoc acc k (vec (repeat 4 fill)))))
          node
          [:port-types :good-input :good-output-a :good-output-b :sw-in
           :sw-out]))

(defn- page-active-port-count
  [port-types]
  (let [types (or port-types [0 0 0 0])
        outputs (count (filter #(pos? (bit-and % 0x80)) types))
        inputs (count (filter #(pos? (bit-and % 0x40)) types))]
    (max outputs inputs)))

(defn- normalize-port-descriptor
  [descriptor order]
  (let [base (when-some [addr (:port-address descriptor)]
               (common/split-port-address (bit-and (int addr) 0x7FFF)))
        net (bit-and (int (or (:net descriptor) (:net base) 0)) 0x7F)
        sub-net (bit-and (int (or (:sub-net descriptor) (:sub-net base) 0))
                         0x0F)
        universe (bit-and (int (or (:universe descriptor) (:universe base) 0))
                          0x0F)
        port-address (common/compose-port-address net sub-net universe)
        port-type (bit-and (int (or (:port-type descriptor) 0xC0)) 0xFF)
        good-input (bit-and (int (or (:good-input descriptor) 0)) 0xFF)
        good-output-a (bit-and (int (or (:good-output-a descriptor) 0)) 0xFF)
        good-output-b (bit-and (int (or (:good-output-b descriptor)
                                        default-good-output-b-byte))
                               0xFF)
        sw-in (bit-and (int (or (:sw-in descriptor) universe)) 0xFF)
        sw-out (bit-and (int (or (:sw-out descriptor) universe)) 0xFF)]
    {:order         order
     :net           net
     :sub-net       sub-net
     :universe      universe
     :port-address  port-address
     :port-type     port-type
     :good-input    good-input
     :good-output-a good-output-a
     :good-output-b good-output-b
     :sw-in         sw-in
     :sw-out        sw-out}))

(defn ports->pages
  "Converts a flat list of port descriptors into a vector of Art-Net node pages."
  [node ports]
  (let [template (-> node
                     (dissoc :port-pages :ports)
                     (assoc :port-addresses nil)
                     (assoc :num-ports 0)
                     blank-port-fields)
        normalized (->> ports
                        (map-indexed (fn [idx descriptor]
                                       (when descriptor
                                         (normalize-port-descriptor descriptor
                                                                    idx))))
                        (remove nil?))]
    (if (empty? normalized)
      [(assoc (normalize-node template) :num-ports 0)]
      (let [grouped (->> normalized
                         (group-by (juxt :net :sub-net))
                         (map (fn [[[net sub-net] entries]]
                                {:net     net
                                 :sub-net sub-net
                                 :order   (apply min (map :order entries))
                                 :entries (sort-by :order entries)}))
                         (sort-by :order)
                         (mapcat
                           (fn [{:keys [net sub-net entries]}]
                             (map (fn [chunk]
                                    {:net net, :sub-net sub-net, :ports chunk})
                                  (partition-all 4 entries)))))
            base-index (max 1 (int (:bind-index node 1)))]
        (vec
          (map-indexed
            (fn [idx {:keys [net sub-net ports]}]
              (let [override (-> template
                                 (assoc :bind-index (+ base-index idx))
                                 (assoc :net-switch net)
                                 (assoc :sub-switch sub-net)
                                 (assoc :port-addresses
                                        (mapv :port-address ports)))
                    filled (reduce
                             (fn [page
                                  [slot
                                   {:keys [port-type good-input good-output-a
                                           good-output-b sw-in sw-out]}]]
                               (-> page
                                   (assoc-in [:port-types slot] port-type)
                                   (assoc-in [:good-input slot] good-input)
                                   (assoc-in [:good-output-a slot] good-output-a)
                                   (assoc-in [:good-output-b slot] good-output-b)
                                   (assoc-in [:sw-in slot] sw-in)
                                   (assoc-in [:sw-out slot] sw-out)))
                             override
                             (map-indexed vector ports))
                    normalized-page (normalize-node filled)
                    port-count (page-active-port-count (:port-types
                                                         normalized-page))]
                (assoc normalized-page :num-ports port-count)))
            grouped))))))

(defn node-port-pages
  "Calculates effective node pages based on configuration strategy (:port-pages, :ports, or logical default)."
  [node]
  (cond (contains? node :port-pages)
        (let [pages (:port-pages node)
              base-index (max 1 (int (:bind-index node 1)))
              template (-> node
                           (dissoc :port-pages :ports)
                           (assoc :port-addresses nil)
                           (assoc :num-ports 0)
                           blank-port-fields)
              blank-page (assoc (normalize-node template) :num-ports 0)]
          (if (seq pages)
            (vec (map-indexed (fn [idx overrides]
                                (let [default-index (+ base-index idx)
                                      bind-index (max 1
                                                      (int (or (:bind-index
                                                                 overrides)
                                                               default-index)))
                                      merged (-> template
                                                 (merge overrides)
                                                 (assoc :bind-index bind-index))
                                      normalized (normalize-node merged)
                                      port-count (page-active-port-count
                                                   (:port-types normalized))]
                                  (if (pos? port-count)
                                    (assoc normalized :num-ports port-count)
                                    normalized)))
                              pages))
            [blank-page]))
        (contains? node :ports) (ports->pages node (:ports node))
        :else [node]))

(defn node-bind-index [node] (max 1 (int (:bind-index node 1))))

(defn state-bind-index [state] (node-bind-index (:node state)))

(defn page-bind-index
  [page base idx]
  (max 1 (int (or (:bind-index page) (+ base idx)))))

(defn find-page-by-bind-index
  [pages bind-index base]
  (some (fn [[idx page]]
          (let [page-bind (page-bind-index page base idx)]
            (when (= page-bind bind-index) [idx page page-bind])))
        (map-indexed vector pages)))

(defn page-port-addresses
  "Calculates list of active port addresses for a page."
  [page]
  (let [explicit (:port-addresses page)]
    (if (seq explicit)
      (->> explicit
           (remove #(or (nil? %) (zero? %)))
           (map #(bit-and (int %) 0x7FFF)))
      (let [net (bit-and (:net-switch page 0) 0x7F)
            sub-net (bit-and (:sub-switch page 0) 0x0F)
            port-types (vec (:port-types page))
            sw-in (vec (:sw-in page))
            sw-out (vec (:sw-out page))
            port-count (count port-types)]
        (loop [idx 0
               acc []]
          (if (>= idx port-count)
            acc
            (let [ptype (nth port-types idx 0)
                  out? (pos? (bit-and ptype 0x80))
                  in? (pos? (bit-and ptype 0x40))
                  out-universe (bit-and (nth sw-out idx 0) 0x0F)
                  in-universe (bit-and (nth sw-in idx 0) 0x0F)
                  acc' (cond-> acc
                               out? (conj (common/compose-port-address net
                                                                       sub-net
                                                                       out-universe))
                               in? (conj (common/compose-port-address net
                                                                      sub-net
                                                                      in-universe)))]
              (recur (inc idx) acc'))))))))

(defn node-port-addresses
  "Calculates all unique active port addreses across all pages of a node."
  [node]
  (->> (node-port-pages node)
       (mapcat page-port-addresses)
       (remove #(or (nil? %) (zero? %)))
       (map #(bit-and (int %) 0x7FFF))
       distinct))

(defn ensure-command-labels
  "Normalizes command label strings (swin/swout), removing null termination and whitespace."
  [labels]
  (let [defaults {:swin nil, :swout nil}
        normalize (fn [value]
                    (when value
                      (-> value
                          str
                          (str/replace #"\u0000+$" "")
                          str/trim
                          not-empty)))]
    (reduce-kv (fn [acc k v]
                 (case k
                   :swin (assoc acc :swin (normalize v))
                   :swout (assoc acc :swout (normalize v))
                   acc))
               defaults
               (or labels {}))))

(comment
  (require '[clj-artnet.impl.protocol.node-state :as node-state] :reload)
  ;; Normalize basic node config
  (node-state/normalize-node {:short-name "Test", :ip [192 168 1 50]})
  ;; Returns map with full default fields populated
  ;; Calculate port pages for a logical node
  (def node (node-state/normalize-node {:ports [{:universe 0} {:universe 1}]}))
  (node-state/node-port-pages node)
  ;; Returns vector of page maps
  ;; Calculate all port addresses
  (node-state/node-port-addresses node)
  ;; => (0 1)
  :rcf)
