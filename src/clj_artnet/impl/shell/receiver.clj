(ns clj-artnet.impl.shell.receiver
  "Process handling UDP reception and packet decoding.

   Implements a core.async flow loop that:
   - Reads datagrams from the channel into pooled buffers
   - Decodes Art-Net packets (discarding malformed ones)
   - Emits valid packets to the flow graph"
  (:require [clj-artnet.impl.protocol.codec.dispatch :as dispatch]
            [clj-artnet.impl.shell.buffers :as buffers]
            [clj-artnet.impl.shell.net :as net]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [taoensso.trove :as trove])
  (:import (java.nio ByteBuffer)
           (java.nio.channels ClosedChannelException DatagramChannel)
           (java.util.concurrent.atomic AtomicBoolean)))

(set! *warn-on-reflection* true)

(defn- receiver-loop
  "Internal loop that reads datagrams, decodes them, and puts to out-chan.
   Runs until running? is false or channel closes."
  [{:keys [^DatagramChannel channel ^buffers/BufferPool pool
           ^AtomicBoolean running? out-chan max-packet-bytes]}]
  (try (while (.get running?)
         (let [^ByteBuffer buf (buffers/borrow! pool)]
           (.clear buf)
           (when (> max-packet-bytes (.capacity buf))
             (throw (ex-info "Buffer too small for configured packet size"
                             {:capacity (.capacity buf),
                              :required max-packet-bytes})))
           (let [remote (.receive channel buf)]
             (if (nil? remote)
               (buffers/release! pool buf)
               (do (.flip buf)
                   (let [view (.duplicate buf)]
                     (try (let [packet (dispatch/decode view)
                                sender (net/sender-from-socket remote)
                                message {:type    :rx,
                                         :packet  packet,
                                         :sender  sender,
                                         :release #(buffers/release! pool buf)}]
                            (if (async/>!! out-chan message)
                              nil
                              (do (buffers/release! pool buf)
                                  (.set running? false))))
                          (catch Throwable t
                            (trove/log! {:level :warn,
                                         :id    ::malformed-udp-payload,
                                         :msg
                                         "Discarding malformed UDP payload",
                                         :error t})
                            (buffers/release! pool buf)))))))))
       (catch ClosedChannelException _)
       (catch Throwable t
         (trove/log! {:level :error,
                      :id    ::udp-receiver-failed,
                      :msg   "UDP receiver failed",
                      :error t}))
       (finally (async/close! out-chan) (.set running? false))))

(defn- stop-receiver!
  "Stop the receiver, closing the channel."
  [{:keys [^AtomicBoolean running? ^DatagramChannel channel]}]
  (when (.compareAndSet running? true false)
    (try (.close channel)
         (catch Exception e
           (trove/log! {:level :warn,
                        :id    ::receiver-channel-close-error,
                        :msg   "Error closing receiver channel",
                        :error e})))))

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
       {:outs     {:rx "Decoded Art-Net frames"},
        :params   {:channel    "DatagramChannel",
                   :pool       "Buffer pool",
                   :out-buffer "Output channel buffer size",
                   :max-packet "Maximum bytes per datagram"},
        :workload :io})
      ([{:keys [channel pool out-buffer max-packet],
         :or   {out-buffer 32, max-packet 2048}}]
       (let [running? (AtomicBoolean. true)
             out (async/chan out-buffer)
             thread (async/io-thread (receiver-loop {:channel  channel,
                                                     :pool     pool,
                                                     :running? running?,
                                                     :out-chan out,
                                                     :max-packet-bytes
                                                     max-packet}))]
         {:channel         channel,
          :pool            pool,
          :running?        running?,
          :out             out,
          :thread          thread,
          ::flow/out-ports {:rx out}}))
      ([state transition]
       (when (= transition ::flow/stop)
         (stop-receiver! state)
         (async/close! (:out state)))
       state)
      ([state _ _] [state {}]))))

(comment
  (require '[clj-artnet.impl.shell.receiver :as receiver] :reload)
  ;; Requires real channels/buffer pool to run
  :rcf)
