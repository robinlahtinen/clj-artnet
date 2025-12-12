;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.timing
  "Timing utilities for deterministic testing."
  (:refer-clojure :exclude [time]))

(set! *warn-on-reflection* true)

(defn ^:dynamic *system-nano-time*
  "Dynamic function for getting system time in nanoseconds.
  Default: System/nanoTime."
  []
  (System/nanoTime))

(defn nano-time
  "Returns timestamp from event or *system-nano-time*."
  [event]
  (or (:timestamp event) (*system-nano-time*)))

(comment
  (require '[clj-artnet.impl.protocol.timing :as timing] :reload)
  ;; system time
  (timing/*system-nano-time*)
  ;; deterministic time binding
  (binding [timing/*system-nano-time* (constantly 1000)]
    (timing/*system-nano-time*))
  ;; extract from event
  (timing/nano-time {:timestamp 42})
  :rcf)
