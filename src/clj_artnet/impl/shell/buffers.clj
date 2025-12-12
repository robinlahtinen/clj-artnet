(ns clj-artnet.impl.shell.buffers
  "Pools direct ByteBuffers for zero-allocation UDP I/O.

   Provides a thread-safe, hot-path optimized regular buffer pool to minimize
   garbage collection pressure during high-throughput packet processing."
  (:require [taoensso.trove :as trove])
  (:import (java.io Closeable)
           (java.nio ByteBuffer)
           (java.util.concurrent LinkedBlockingQueue)
           (java.util.concurrent.atomic AtomicBoolean)))

(set! *warn-on-reflection* true)

(defrecord BufferPool [^LinkedBlockingQueue queue ^long buffer-size
                       ^AtomicBoolean closed?]
  Closeable
  (close [_] (.set closed? true) (while (.poll queue) nil)))

(defn buffer-pool?
  "Returns true if x is a BufferPool instance."
  [x]
  (instance? BufferPool x))

(defn create-pool
  "Creates ByteBuffer pool options.

   Options:
   - :count   -> Number of buffers to preallocate (default 128)
   - :size    -> Buffer capacity in bytes (default 2048)
   - :direct? -> Use direct buffers (default true)

   Returns a BufferPool which implements java.io.Closeable."
  [{:keys [count size direct?], :or {count 128, size 2048, direct? true}}]
  (let [queue (LinkedBlockingQueue. (int count))
        closed? (AtomicBoolean. false)
        factory (if direct?
                  #(ByteBuffer/allocateDirect size)
                  #(ByteBuffer/allocate size))]
    (dotimes [_ count] (.offer queue (doto ^ByteBuffer (factory) (.clear))))
    (->BufferPool queue size closed?)))

(defn borrow!
  "Borrows a buffer from the pool (blocking).
   Resets position/limit before returning. Throws ex-info if the pool is closed."
  [^BufferPool pool]
  (let [queue (:queue pool)
        closed? (:closed? pool)]
    (loop []
      (when (.get ^AtomicBoolean closed?)
        (throw (ex-info "Buffer pool closed" {})))
      (let [result
            (try (.take ^LinkedBlockingQueue queue)
                 (catch InterruptedException _ (Thread/interrupted) ::retry))]
        (if (identical? result ::retry)
          (recur)
          (let [^ByteBuffer buf result]
            (.clear buf)
            buf))))))

(defn release!
  "Returns a buffer to the pool.
   Safe to call multiple times or with nil. Clears buffer before return."
  [^BufferPool pool ^ByteBuffer buf]
  (when (and pool buf)
    (try (.clear buf)
         (when-not (.offer ^LinkedBlockingQueue (:queue pool) buf)
           (trove/log! {:level :warn,
                        :id    ::buffer-pool-full,
                        :msg   "Dropping ByteBuffer; pool full"}))
         (catch Exception e
           (trove/log! {:level :warn,
                        :id    ::buffer-return-failed,
                        :msg   "Failed to return ByteBuffer to pool",
                        :error e})))))

(comment
  (require '[clj-artnet.impl.shell.buffers :as buffers] :reload)
  ;; Create a small pool
  (def pool (buffers/create-pool {:count 2, :size 1024}))
  ;; Borrow a buffer
  (def buf (buffers/borrow! pool))
  (.capacity buf)                                           ;; => 1024
  ;; Return it
  (buffers/release! pool buf)
  ;; Close pool
  (.close pool)
  :rcf)
