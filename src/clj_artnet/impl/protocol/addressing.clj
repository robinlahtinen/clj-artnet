;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.addressing
  "Port-Address logic (Art-Net 4).")

(set! *warn-on-reflection* true)

(def ^:const max-port-address "Maximum valid Port-Address (15-bit)." 32767)

(def ^:const min-port-address
  "Minimum valid Port-Address (1 as 0 is deprecated)."
  1)

(def ^:const max-net "Maximum Net (7-bit)." 127)

(def ^:const max-sub-net "Maximum Sub-Net (4-bit)." 15)

(def ^:const max-universe "Maximum Universe (4-bit)." 15)

(defn compose-port-address
  "Composes 15-bit Port-Address from components.

  Args:
    net      - int (0-127)
    sub-net  - int (0-15)
    universe - int (0-15)"
  [net sub-net universe]
  (bit-or (bit-shift-left (bit-and net 0x7F) 8)
          (bit-shift-left (bit-and sub-net 0x0F) 4)
          (bit-and universe 0x0F)))

(defn split-port-address
  "Splits Port-Address into components.

  Returns map:
    :net          - int
    :sub-net      - int
    :universe     - int
    :port-address - int"
  [port-address]
  {:net          (bit-and (bit-shift-right port-address 8) 0x7F)
   :sub-net      (bit-and (bit-shift-right port-address 4) 0x0F)
   :universe     (bit-and port-address 0x0F)
   :port-address port-address})

(defn valid-port-address?
  "Returns true if Port-Address is valid (1-32767)."
  [port-address]
  (and (integer? port-address)
       (>= port-address min-port-address)
       (<= port-address max-port-address)))

(defn deprecated-port-address?
  "Returns true if Port-Address is deprecated (0)."
  [port-address]
  (= port-address 0))

(defn validate-port-address!
  "Validates Port-Address. Returns port-address or throws ex-info."
  [port-address]
  (when-not (integer? port-address)
    (throw (ex-info "Port-Address must be an integer"
                    {:type :invalid-port-address, :port-address port-address})))
  (when (< port-address 0)
    (throw (ex-info "Port-Address must be non-negative"
                    {:type :invalid-port-address, :port-address port-address})))
  (when (> port-address max-port-address)
    (throw (ex-info "Port-Address exceeds maximum value"
                    {:type         :invalid-port-address
                     :port-address port-address
                     :max          max-port-address})))
  (when (zero? port-address)
    (throw (ex-info "Port-Address 0 is deprecated"
                    {:type         :deprecated-port-address
                     :port-address port-address})))
  port-address)

(defn resolve-port-address
  "Resolves Port-Address from options. Returns validated address.

  Options:
    :port-address - int, 15-bit address
    :net          - int
    :sub-net      - int
    :universe     - int"
  [{:keys [port-address net sub-net universe], :as opts}]
  (let [addr
        (cond (some? port-address) port-address
              (or (some? net) (some? sub-net) (some? universe))
              (compose-port-address (or net 0) (or sub-net 0) (or universe 0))
              :else (throw (ex-info "No port-address specified"
                                    {:type :missing-port-address
                                     :opts opts})))]
    (validate-port-address! addr)))

(defn normalize-address-opts
  "Normalizes address options. Returns map with both forms."
  [opts]
  (let [addr (resolve-port-address opts)
        components (split-port-address addr)]
    (merge opts components)))

(comment
  (require '[clj-artnet.impl.protocol.addressing :as addressing] :reload)
  ;; compose
  (addressing/compose-port-address 0 0 1)                   ;; => 1
  (addressing/compose-port-address 1 2 3)                   ;; => 291
  ;; split
  (addressing/split-port-address 291)
  ;; => {:net 1, :sub-net 2, :universe 3, :port-address 291}
  ;; validate
  (addressing/valid-port-address? 1)                        ;; => true
  (addressing/valid-port-address? 0)                        ;; => false
  ;; resolve
  (addressing/resolve-port-address {:net 1, :sub-net 2, :universe 3})
  ;; => 291
  :rcf)
