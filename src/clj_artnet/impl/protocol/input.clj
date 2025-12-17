;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.input
  "ArtInput paging and selection logic."
  (:require
    [clj-artnet.impl.protocol.node-state :as state]
    [clj-artnet.impl.protocol.programming :as programming]))

(set! *warn-on-reflection* true)

(defn- ensure-width
  [coll width fill]
  (->> (concat (or coll []) (repeat fill))
       (take width)
       (mapv #(bit-and (int %) 0xFF))))

(defn apply-good-input-disables
  [current disable-flags]
  (let [flags (->> (concat disable-flags (repeat false))
                   (take 4))
        bytes (ensure-width current 4 0)]
    (vec (map (fn [byte disable?]
                (let [cleared (bit-and byte
                                       (bit-not
                                         programming/good-input-disabled-bit))]
                  (if disable?
                    (bit-or cleared programming/good-input-disabled-bit)
                    cleared)))
              bytes
              flags))))

(defn- clamp-num-ports
  [value]
  (-> (int (or value 0))
      (max 0)
      (min 4)))

(defn artinput-page-entries
  [state]
  (let [node (:node state)
        base (state/node-bind-index node)]
    (vec (map-indexed (fn [idx page]
                        {:idx       idx
                         :bind      (state/page-bind-index page base idx)
                         :num-ports (clamp-num-ports (:num-ports page))
                         :page      page})
                      (state/node-port-pages node)))))

(defn artinput-select-page
  "Selects page matching packet bind/ports."
  [state packet]
  (let [entries (artinput-page-entries state)
        base (state/state-bind-index state)
        requested-bind (int (or (:bind-index packet) 0))
        requested-ports (clamp-num-ports (:num-ports packet))
        last-bind (int (or (get-in state [:inputs :last-bind-index]) base))
        match (fn [pred] (some (fn [entry] (when (pred entry) entry)) entries))]
    (or (when (pos? requested-bind)
          (or (when (pos? requested-ports)
                (match #(and (= (:bind %) requested-bind)
                             (= (:num-ports %) requested-ports))))
              (match #(= (:bind %) requested-bind))))
        (when (pos? requested-ports)
          (match #(= (:num-ports %) requested-ports)))
        (match #(= (:bind %) last-bind))
        (first entries))))

(defn page-good-input
  "Returns good-input vector for bind-index."
  ([state bind-index] (page-good-input state bind-index nil))
  ([state bind-index page]
   (let [base (state/state-bind-index state)
         selected (max 1 (int (or bind-index base)))
         override (get-in state [:inputs :per-page selected :good-input])]
     (or override
         (when (= selected base) (vec (:good-input (:node state) [0 0 0 0])))
         (when page (vec (:good-input page [0 0 0 0])))
         (let [pages (state/node-port-pages (:node state))]
           (if-let [[_ matched _]
                    (state/find-page-by-bind-index pages selected base)]
             (vec (:good-input matched [0 0 0 0]))
             (vec (repeat 4 0))))))))

(defn record-page-input
  "Records input state for page."
  [state bind-index disabled good-input]
  (assoc-in state
            [:inputs :per-page bind-index]
            {:disabled disabled, :good-input good-input}))

(defn pages-with-input-overrides
  "Applies input overrides to pages."
  [state]
  (let [node (:node state)
        base (state/node-bind-index node)
        overrides (get-in state [:inputs :per-page])
        pages (state/node-port-pages node)]
    (vec (map-indexed (fn [idx page]
                        (let [bind (state/page-bind-index page base idx)
                              override (get overrides bind)]
                          (cond-> page
                                  override (assoc :good-input
                                                  (:good-input override)))))
                      pages))))

(comment
  (require '[clj-artnet.impl.protocol.input :as input] :reload)
  ;; select page
  (input/artinput-select-page {:node {:bind-index 1, :num-ports 4}}
                              {:bind-index 1, :num-ports 4})
  ;; apply disables
  (input/apply-good-input-disables [0x80 0x80 0 0] [true false true false])
  :rcf)
