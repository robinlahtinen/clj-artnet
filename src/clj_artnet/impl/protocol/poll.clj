;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.poll
  "Helpers for ArtPoll discovery configuration and reply-on-change logic.

   Provides pure functions for normalizing discovery settings and managing purely functional
   reply-on-change subscriber state.")

(set! *warn-on-reflection* true)

(def ^:const allowed-reply-on-change-policies
  #{:prefer-existing :prefer-latest})

(def ^:const default-discovery-config
  {:reply-on-change-limit 1, :reply-on-change-policy :prefer-existing})

(defn normalize-config
  "Normalizes discovery configuration to canonical form.

   Ensures :reply-on-change-limit is non-negative and :reply-on-change-policy
   is valid (defaults to :prefer-existing)."
  [discovery]
  (let [config (or discovery {})
        limit (-> (or (:reply-on-change-limit config)
                      (:reply-on-change-limit default-discovery-config))
                  int
                  (max 0))
        candidate (:reply-on-change-policy config)
        policy (if (allowed-reply-on-change-policies candidate)
                 candidate
                 (:reply-on-change-policy default-discovery-config))]
    {:reply-on-change-limit limit, :reply-on-change-policy policy}))

(defn peer-key
  "Creates consistent peer lookup key from sender info (host and port)."
  [{:keys [host port]}]
  [(some-> host
           str) (int (or port 0x1936))])

(defn disable-reply-on-change
  "Disables reply-on-change tracking for a peer entry."
  [entry]
  (-> entry
      (assoc :reply-on-change? false)
      (dissoc :reply-on-change-granted-at)))

(defn enforce-reply-on-change-limit
  "Enforces the limit on reply-on-change subscribers.

   Returns updated state with excess subscribers disabled based on policy.
   Uses :discovery {:reply-on-change-limit N :reply-on-change-policy ...}."
  [state]
  (let [{:keys [reply-on-change-limit reply-on-change-policy]
         :or   {reply-on-change-limit 1, reply-on-change-policy :prefer-existing}}
        (:discovery state)
        limit (long (max 0 (or reply-on-change-limit 0)))
        peers (:peers state)]
    (if (zero? limit)
      ;; Zero limit means disable all
      (assoc state
        :peers
        (into {}
              (map (fn [[k entry]] [k
                                    (if (:reply-on-change? entry)
                                      (disable-reply-on-change entry)
                                      entry)]))
              peers))
      ;; Enforce limit
      (let [subscribed (->> peers
                            (keep (fn [[k entry]]
                                    (when (:reply-on-change? entry)
                                      [k entry]))))]
        (if (<= (count subscribed) limit)
          state
          (let [sorted (sort-by (fn [[_ entry]]
                                  (long (or (:reply-on-change-granted-at entry)
                                            (:seen-at entry)
                                            0)))
                                subscribed)
                winners (if (= :prefer-latest reply-on-change-policy)
                          (set (map first (take limit (reverse sorted))))
                          (set (map first (take limit sorted))))]
            (assoc state
              :peers
              (into {}
                    (map (fn [[k entry]] [k
                                          (if (and
                                                (:reply-on-change? entry)
                                                (not (contains? winners k)))
                                            (disable-reply-on-change entry)
                                            entry)]))
                    peers))))))))

(defn page-in-target-range?
  "Checks if a page has any port addresses within the targeted range.

   Returns true if:
   - Targeted mode is disabled (pass-through)
   - Page has port addresses within [target-bottom, target-top]"
  [page target-enabled? target-bottom target-top page-port-addresses-fn]
  (if-not target-enabled?
    true                                                    ;; Not targeted mode, all pages match
    (let [;; Gather addresses from all possible sources
          computed (page-port-addresses-fn page)
          subscribed (seq (:subscribed-ports page))
          ;; Fallback: infer addresses from sw-in/sw-out if port-types
          ;; missing
          fallback-addresses
          (when (and (empty? computed) (not (seq (:port-types page))))
            (let [net (bit-and (or (:net-switch page) 0) 0x7F)
                  sub-net (bit-and (or (:sub-switch page) 0) 0x0F)
                  sw-in (seq (:sw-in page))
                  sw-out (seq (:sw-out page))]
              (concat (map #(bit-or (bit-shift-left net 8)
                                    (bit-or (bit-shift-left sub-net 4)
                                            (bit-and % 0x0F)))
                           sw-out)
                      (map #(bit-or (bit-shift-left net 8)
                                    (bit-or (bit-shift-left sub-net 4)
                                            (bit-and % 0x0F)))
                           sw-in))))
          addresses (concat computed subscribed fallback-addresses)
          low (min target-bottom target-top)
          high (max target-bottom target-top)]
      (if (seq addresses)
        (boolean (some #(<= low % high) addresses))
        ;; No addresses from any source - don't respond (conservative
        ;; default)
        false))))

(defn reply-on-change-peers
  "Gets a list of peers subscribed to reply-on-change, excluding a specific peer."
  [state exclude-key]
  (->> (:peers state)
       (filter (fn [[k {:keys [reply-on-change? host]}]]
                 (and reply-on-change? host (not= k exclude-key))))
       (mapv (fn [[_ {:keys [host port]}]]
               {:host host, :port (or port 0x1936)}))))

(defn reply-on-change-peers-for-page
  "Gets reply-on-change peers whose targeted mode matches the given page's addresses.

   If a peer uses targeted mode, only returns it if the page has addresses in range.
   If a peer doesn't use the targeted mode, always returns it."
  [state page exclude-key page-port-addresses-fn]
  (->> (:peers state)
       (filter (fn [[k
                     {:keys [reply-on-change? host target-enabled? target-top
                             target-bottom]}]]
                 (and reply-on-change?
                      host
                      (not= k exclude-key)
                      ;; Check targeted mode filter
                      (if target-enabled?
                        (page-in-target-range? page
                                               true
                                               target-bottom
                                               target-top
                                               page-port-addresses-fn)
                        true))))
       (mapv (fn [[_ {:keys [host port]}]]
               {:host host, :port (or port 0x1936)}))))

(defn reply-on-change-effects
  "Generates tx-packet effects for all reply-on-change peers.

   Used when node state changes (e.g., address change, name change)."
  [state reply-data exclude-key]
  (let [peers (reply-on-change-peers state exclude-key)]
    (mapv (fn [target]
            {:effect :tx-packet
             :op     :artpollreply
             :data   reply-data
             :target target})
          peers)))

(comment
  (require '[clj-artnet.impl.protocol.poll :as poll] :reload)
  ;; Normalize config
  (poll/normalize-config {:reply-on-change-limit 5})
  ;; => {:reply-on-change-limit 5, :reply-on-change-policy
  ;; :prefer-existing}
  ;; Create peer key
  (poll/peer-key {:host "1.2.3.4", :port 6454})
  ;; => ["1.2.3.4" 6454]
  :rcf)
