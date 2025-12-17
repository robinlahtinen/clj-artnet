(ns clj-artnet.support.helpers
  "Test support utilities for clj-artnet.

   Provides testing macros, helper functions for state machine testing,
   and async coordination utilities.

   Migrated from: test_support.clj, fixtures.clj (wait-for)"
  (:import
    (java.time Duration)))

(defmacro thrown-with-msg?
  "Evaluates `body` and returns true when it throws `exception-class` with a
   message matching `pattern`. Returns false when no exception is thrown. Any
   other exception is rethrown."
  [exception-class pattern & body]
  `(try ~@body
        false
        (catch ~exception-class e#
          (boolean (re-find ~pattern (or (.getMessage e#) ""))))
        (catch Throwable t# (throw t#))))

(defn wait-for
  "Wait for predicate to become true, polling at intervals.

   Returns true if predicate returns truthy within timeout-ms,
   false if timeout is reached without success.

   Options:
     :poll-interval-ms - Time between checks (default: 10)"
  [predicate timeout-ms & {:keys [poll-interval-ms], :or {poll-interval-ms 10}}]
  (let [start (System/currentTimeMillis)]
    (loop []
      (let [elapsed (- (System/currentTimeMillis) start)]
        (cond (predicate) true
              (>= elapsed timeout-ms) false
              :else (do (^[Duration] Thread/sleep poll-interval-ms)
                        (recur)))))))

(comment
  ;; Example: Wait for a condition
  (wait-for #(> (rand) 0.9) 1000)
  ;; Example: Exception matching
  (thrown-with-msg? Exception #"test" (throw (Exception. "test error")))
  ;; => true
  :rcf)
