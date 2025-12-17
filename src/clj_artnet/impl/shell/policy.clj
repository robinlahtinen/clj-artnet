;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.shell.policy
  "Art-Net 4 protocol policy enforcement.

   Provides pure functions to validate compliance, specifically regarding
   broadcast restrictions for certain opcodes."
  (:require
    [clj-artnet.impl.shell.net :as net]))

(set! *warn-on-reflection* true)

(def no-broadcast-ops
  "Set of opcodes strictly forbidden from broadcast by Art-Net 4 spec (Table 1).
   Includes: :artdmx, :artpollreply, :artrdm, :arttoddata."
  #{:artdmx :artpollreply :artrdm :arttoddata})

(defn check-broadcast-policy
  "Validates Art-Net 4 broadcast restrictions.

   Throws ex-info if a forbidden opcode is targeted for broadcast."
  [op broadcast?]
  (when (and broadcast? (contains? no-broadcast-ops op))
    (throw (ex-info (str "Art-Net 4 spec violation: "
                         (name op)
                         " must not be broadcast")
                    {:op op, :spec-reference "Art-Net 4 Table 1"}))))

(defn broadcast-target?
  "Determines if a target is a broadcast address.

   Checks both the explicit :broadcast? flag and 255.255.255.255 address."
  [{:keys [host broadcast?]}]
  (or broadcast?
      (when host (net/limited-broadcast-address? (net/as-inet-address host)))))

(comment
  (require '[clj-artnet.impl.shell.policy :as policy] :reload)
  ;; Check policy for unicast op
  (policy/check-broadcast-policy :artpoll false)            ;; => nil
  ;; Check policy for broadcast forbidden op
  (try (policy/check-broadcast-policy :artdmx true)
       (catch Exception e (ex-data e)))
  ;; => {:op :artdmx, :spec-reference ...}
  :rcf)
