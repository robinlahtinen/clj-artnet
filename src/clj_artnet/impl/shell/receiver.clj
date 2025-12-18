;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.shell.receiver
  "Process handling UDP reception and packet decoding.

   Implements a core.async flow loop that:
   - Reads datagrams from the channel into pooled buffers
   - Decodes Art-Net packets (discarding malformed ones)
   - Emits valid packets to the flow graph"
  (:require
    [clj-artnet.impl.protocol.codec.dispatch :as dispatch]
    [clj-artnet.impl.shell.buffers :as buffers]
    [clj-artnet.impl.shell.net :as net]
    [clojure.core.async :as async]
    [clojure.core.async.flow :as flow])
  (:import
    (java.nio ByteBuffer)
    (java.nio.channels ClosedChannelException DatagramChannel)
    (java.util.concurrent Semaphore)
    (java.util.concurrent.atomic AtomicBoolean)))

(set! *warn-on-reflection* true)

(defn- receiver-loop
  "Internal loop that reads datagrams, decodes them, and puts to out-chan.
   Uses the provided Semaphore gate to implement zero-CPU idle during pause."
  [{:keys [^DatagramChannel channel ^buffers/BufferPool pool
           ^AtomicBoolean running? ^AtomicBoolean paused? ^Semaphore gate
           out-chan max-packet]}]
  (try (while (.get running?)
         (if (.get paused?)
           (.acquire gate)
           (let [^ByteBuffer buf (buffers/borrow! pool)]
             (try (.clear buf)
                  (when (> max-packet (.capacity buf))
                    (throw (ex-info "Buffer too small for configured packet size"
                                    {:capacity (.capacity buf)
                                     :required max-packet})))
                  (let [remote (.receive channel buf)]
                    (if (nil? remote)
                      (buffers/release! pool buf)
                      (do (.flip buf)
                          (let [view (.duplicate buf)]
                            (try (let [packet (dispatch/decode view)
                                       sender (net/sender-from-socket remote)
                                       message {:type    :rx
                                                :packet  packet
                                                :sender  sender
                                                :release #(buffers/release! pool buf)}]
                                   (if (async/>!! out-chan message)
                                     true
                                     (do (buffers/release! pool buf)
                                         (.set running? false))))
                                 (catch Throwable t
                                   (buffers/release! pool buf)
                                   (throw t)))))))
                  (catch Throwable t (buffers/release! pool buf) (throw t))))))
       (catch ClosedChannelException _ nil)
       (catch Throwable t (throw t))))

(defn- stop-receiver!
  "Stop the receiver, closing the channel."
  [{:keys [^AtomicBoolean running? ^DatagramChannel channel]}]
  (when (.compareAndSet running? true false)
    (try (.close channel) (catch Exception _ nil))))

(defn receiver-proc
  "Proc launcher for the udp-receiver stage. Args:
   * `:channel`    -> DatagramChannel bound to the Art-Net port
   * `:pool`       -> BufferPool for inbound packets
   * `:out-buffer` -> core.async buffer size for a downstream channel (default 32)
   * `:max-packet` -> sanity limit (default 2048)"
  []
  (flow/process
    (fn
      ([]
       {:ins      {:internal "Internal channel for IO thread"}
        :outs     {:rx "Decoded Art-Net frames"}
        :params   {:channel    "DatagramChannel"
                   :pool       "Buffer pool"
                   :out-buffer "Output buffer size"
                   :max-packet "Maximum bytes per datagram"}
        :workload :io})
      ([{:keys [channel pool out-buffer max-packet]
         :or   {out-buffer 32, max-packet 2048}}]
       (let [running? (AtomicBoolean. true)
             paused? (AtomicBoolean. false)
             gate (Semaphore. 0)
             out (async/chan out-buffer)
             thread (async/io-thread (receiver-loop {:channel    channel
                                                     :pool       pool
                                                     :running?   running?
                                                     :paused?    paused?
                                                     :gate       gate
                                                     :out-chan   out
                                                     :max-packet max-packet}))]
         {:channel        channel
          :pool           pool
          :running?       running?
          :paused?        paused?
          :gate           gate
          :out            out
          :thread         thread
          ::flow/in-ports {:internal  out
                           :lifecycle thread}}))
      ([state transition]
       (case transition
         ::flow/stop (do (stop-receiver! state)
                         (.release ^Semaphore (:gate state))
                         (async/close! (:out state)))
         ::flow/pause (.set ^AtomicBoolean (:paused? state) true)
         ::flow/resume (do (.set ^AtomicBoolean (:paused? state) false)
                           (.release ^Semaphore (:gate state)))
         nil)
       state)
      ([state in msg]
       (cond (= in :internal) [state {:rx [msg]}]
             (and (= in :lifecycle) (instance? Throwable msg)) (throw msg)
             :else [state {}])))))

(comment
  (require '[clj-artnet.impl.shell.receiver :as receiver] :reload)
  ;; Requires real channels/buffer pool to run
  :rcf)
