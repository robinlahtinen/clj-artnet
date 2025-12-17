;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.discovery
  "ArtPoll/ArtPollReply handling (Art-Net 4)."
  (:require
    [clj-artnet.impl.protocol.addressing :as addressing]))

(set! *warn-on-reflection* true)

(def ^:const artpoll-reply-delay-max-ms
  "Max random ArtPollReply delay (1000ms)."
  1000)

(def ^:const artpoll-broadcast-timeout-ms
  "ArtPoll response timeout (3000ms)."
  3000)

(def ^:const reply-on-change-max-peers "Max reply-on-change peers (10)." 10)

(defn parse-artpoll-flags
  "Parses TalkToMe byte into map."
  [flags]
  {:target-enabled?  (bit-test flags 5)
   :vlc-disable?     (bit-test flags 4)
   :diag-unicast?    (bit-test flags 3)
   :diag-request?    (bit-test flags 2)
   :reply-on-change? (bit-test flags 1)
   :suppress-delay?  (bit-test flags 0)})

(defn parse-artpoll-packet
  "Parses ArtPoll packet fields.

  Returns map:
    :target-enabled?
    :diag-unicast?
    :diag-priority
    :target-top
    :target-bottom
    ..."
  [packet]
  (let [flags (or (:flags packet) (:talk-to-me packet) 0)
        parsed-flags (parse-artpoll-flags flags)]
    (merge parsed-flags
           (select-keys packet
                        [:target-enabled? :vlc-disable? :diag-unicast?
                         :diag-request? :reply-on-change? :suppress-delay?])
           {:diag-priority (or (:diag-priority packet) 0)
            :target-top    (or (:target-top packet)
                               (:target-port-address-top packet)
                               addressing/max-port-address)
            :target-bottom (or (:target-bottom packet)
                               (:target-port-address-bottom packet)
                               0)})))

(defn page-port-addresses
  "Extracts Port-Addresses from page config.
   Returns distinct sequence of addresses."
  [page]
  (let [explicit (seq (:port-addresses page))
        subscribed (seq (:subscribed-ports page))
        net (bit-and (or (:net-switch page) 0) 0x7F)
        sub-net (bit-and (or (:sub-switch page) 0) 0x0F)
        port-types (seq (:port-types page))
        sw-in (seq (:sw-in page))
        sw-out (seq (:sw-out page))
        computed (when port-types
                   (for [[i pt] (map-indexed vector port-types)
                         :let [universe (cond (and sw-out (< i (count sw-out)))
                                              (bit-and (nth sw-out i) 0x0F)
                                              (and sw-in (< i (count sw-in)))
                                              (bit-and (nth sw-in i) 0x0F)
                                              :else i)]
                         :when (pos? (bit-and pt 0xC0))]
                     (addressing/compose-port-address net sub-net universe)))
        fallback
        (when (and (empty? computed) (not port-types))
          (concat
            (when sw-out
              (map
                #(addressing/compose-port-address net sub-net (bit-and % 0x0F))
                sw-out))
            (when sw-in
              (map
                #(addressing/compose-port-address net sub-net (bit-and % 0x0F))
                sw-in))))]
    (distinct (concat explicit subscribed computed fallback))))

(defn page-in-target-range?
  "Returns true if page intersects target range."
  [page target-enabled? target-bottom target-top]
  (if-not target-enabled?
    true
    (let [addresses (page-port-addresses page)
          low (min target-bottom target-top)
          high (max target-bottom target-top)]
      (if (seq addresses) (boolean (some #(<= low % high) addresses)) false))))

(defn filter-pages-by-target
  "Returns vector of pages in targeted mode range."
  [pages target-enabled? target-bottom target-top]
  (if target-enabled?
    (filterv #(page-in-target-range? % true target-bottom target-top) pages)
    pages))

(def ^:private artpollreply-keys
  [:ip :port :short-name :long-name :net-switch :sub-switch :esta-man :oem
   :status1 :status2 :status3 :sw-in :sw-out :port-types :good-input
   :good-output-a :good-output-b :mac :bind-ip :bind-index :acn-priority :style
   :default-responder :background-queue-policy :num-ports :version-hi
   :version-lo :ubea-version :node-report :sw-macro :sw-remote :user-hi :user-lo
   :refresh-rate :port-addresses])

(defn page-reply-data
  "Extracts ArtPollReply fields from page."
  [page]
  (select-keys page artpollreply-keys))

(defn disable-reply-on-change
  "Disables reply-on-change for peer."
  [entry]
  (-> entry
      (assoc :reply-on-change? false)
      (dissoc :reply-on-change-granted-at)))

(defn reply-on-change-peer-count
  "Returns count of reply-on-change peers."
  [peers]
  (count (filter (fn [[_ entry]] (:reply-on-change? entry)) peers)))

(defn oldest-reply-on-change-peer
  "Returns oldest reply-on-change peer entry."
  [peers]
  (when-let [roc-peers (seq (filter (fn [[_ entry]] (:reply-on-change? entry))
                                    peers))]
    (->> roc-peers
         (sort-by (fn [[_ entry]] (:reply-on-change-granted-at entry 0)))
         first
         first)))

(defn enforce-reply-on-change-limit
  "Enforces limit on reply-on-change peers. Returns updated state."
  [state]
  (let [peers (:peers state)
        configured-limit (get-in state [:discovery :reply-on-change-limit])
        policy
        (get-in state [:discovery :reply-on-change-policy] :prefer-existing)
        roc-peers (filter (fn [[_ entry]] (:reply-on-change? entry)) peers)
        current-roc-count (count roc-peers)]
    (if (and configured-limit (> current-roc-count configured-limit))
      (let [sorted-peers (sort-by (fn [[_ entry]]
                                    (:reply-on-change-granted-at entry 0))
                                  roc-peers)
            peers-to-disable
            (case policy
              :prefer-existing (drop configured-limit sorted-peers)
              :prefer-latest (take (- current-roc-count configured-limit)
                                   sorted-peers)
              (drop configured-limit sorted-peers))]
        (reduce (fn [acc [key _]]
                  (update-in acc [:peers key] disable-reply-on-change))
                state
                peers-to-disable))
      state)))

(comment
  (require '[clj-artnet.impl.protocol.discovery :as discovery] :reload)
  ;; flags parse
  (discovery/parse-artpoll-flags 2r00100110)
  ;; page addresses
  (discovery/page-port-addresses
    {:net-switch 0, :sub-switch 0, :sw-out [0 1 2 3], :port-types [0xC0 0xC0]})
  ;; => (0 1)
  :rcf)
