(ns clj-artnet.impl.shell.sender
  "Process handling UDP transmission and action dispatch.

   Implements a core.async flow loop that:
   - Encodes and sends Art-Net packets (enforcing broadcast policy)
   - Executes callback actions on virtual threads
   - Manages delayed actions (e.g. reply dispatch)"
  (:require [clj-artnet.impl.protocol.codec.dispatch :as dispatch]
            [clj-artnet.impl.shell.buffers :as buffers]
            [clj-artnet.impl.shell.net :as net]
            [clj-artnet.impl.shell.policy :as policy]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [taoensso.trove :as trove])
  (:import (clojure.lang ExceptionInfo)
           (java.nio.channels DatagramChannel)
           (java.util.concurrent.atomic AtomicBoolean)))

(set! *warn-on-reflection* true)

(defn- resolve-target
  "Resolve a target map into an InetSocketAddress while enforcing the
  limited-broadcast policy. Throws when :host is missing or when the
  target is 255.255.255.255 and limited broadcast is disabled."
  [{:keys [host port], :as target} allow-limited?]
  (when-not target
    (throw (ex-info "Missing target for UDP send" {:reason :missing-target})))
  (let [host-value
        (or host (throw (ex-info "Target host required" {:target target})))
        addr (net/as-inet-address host-value)]
    (when (and (not allow-limited?) (net/limited-broadcast-address? addr))
      (throw (ex-info "Limited broadcast target is disabled" {:target target})))
    (net/->socket-address {:host addr, :port port})))

(defn- dispatch-callback!
  "Execute a callback action on a virtual thread."
  [action]
  (when-let [f (:fn action)]
    (async/io-thread (try (f (:payload action))
                          (catch Throwable t
                            (trove/log! {:level :error,
                                         :id    ::callback-failed,
                                         :msg   "Art-Net callback failed",
                                         :error t}))))))

(defn- send-packet!
  "Encode and send a packet, enforcing broadcast policy."
  [{:keys [^DatagramChannel channel ^buffers/BufferPool pool default-target
           allow-limited-broadcast?]} action]
  (let [target (or (:target action) default-target)
        packet (:packet action)
        op (:op packet)
        broadcast? (or (:broadcast? action) (policy/broadcast-target? target))]
    ;; Enforce Art-Net 4 broadcast policy
    (policy/check-broadcast-policy op broadcast?)
    (when-not target
      (throw (ex-info "Missing target for UDP send" {:action action})))
    (let [buf (buffers/borrow! pool)]
      (try (let [encoded (dispatch/encode packet buf)
                 socket (resolve-target target allow-limited-broadcast?)]
             (.send channel encoded socket))
           (catch Throwable t
             (trove/log! {:level :error,
                          :id    ::packet-send-failed,
                          :msg   "Failed to emit Art-Net packet",
                          :error t,
                          :data  {:action action}}))
           (finally (buffers/release! pool buf))))))

(defn- perform-action!
  "Perform an action immediately."
  [state action]
  (case (:type action)
    :send (send-packet! state action)
    :callback (dispatch-callback! action)
    :release (when-let [release (:release action)]
               (try (release)
                    (catch Throwable t
                      (trove/log! {:level :warn,
                                   :id    ::buffer-release-failed,
                                   :msg   "Failed to release buffer",
                                   :error t}))))
    (trove/log! {:level :warn,
                 :id    ::unknown-action,
                 :msg   "Unknown action",
                 :data  {:action action}}))
  state)

(defn- execute-action!
  "Execute an action, possibly with delay."
  [state action]
  (if-let [delay (:delay-ms action)]
    (async/io-thread
      (try (Thread/sleep (long (max 0 delay)))
           (if (.get ^AtomicBoolean (:running? state))
             (perform-action! state (dissoc action :delay-ms))
             (trove/log! {:level :debug,
                          :id    ::delayed-action-skipped,
                          :msg   "Skipping delayed action; sender stopped",
                          :data  {:action (:type action)}}))
           (catch InterruptedException _ (Thread/interrupted))
           (catch Throwable t
             (let [msg (ex-message t)]
               (if (and (instance? ExceptionInfo t) (= "Buffer pool closed" msg))
                 (trove/log! {:level :debug,
                              :id    ::delayed-action-cancelled,
                              :msg   "Delayed action cancelled; pool closed",
                              :data  {:action (:type action)}})
                 (trove/log! {:level :error,
                              :id    ::delayed-action-failed,
                              :msg   "Delayed action execution failed",
                              :error t,
                              :data  {:action (:type action)}}))))))
    (perform-action! state action))
  state)

(defn- sender-loop
  "Internal loop that reads actions and dispatches them."
  [{:keys [action-chan running? state]}]
  (try (loop []
         (when (.get ^AtomicBoolean running?)
           (if-let [msg (async/<!! action-chan)]
             (do (try (execute-action! state msg)
                      (catch Throwable t
                        (trove/log! {:level :error,
                                     :id    ::action-execution-failed,
                                     :msg   "Failed to execute action",
                                     :error t,
                                     :data  {:action msg}})))
                 (recur))
             (.set ^AtomicBoolean running? false))))
       (catch Throwable t
         (trove/log! {:level :error,
                      :id    ::sender-dispatcher-failed,
                      :msg   "UDP sender dispatcher failed",
                      :error t}))
       (finally (.set ^AtomicBoolean running? false))))

(defn sender-proc
  "Proc launcher for the udp-sender/action-dispatcher stage. Args:
   * `:channel`                  -> DatagramChannel for outbound packets
   * `:pool`                     -> BufferPool used for encoding
   * `:default-target`           -> fallback {:host .. :port ..}
   * `:allow-limited-broadcast?` -> permit 255.255.255.255 targets?"
  []
  (flow/process
    (fn
      ([]
       {:ins      {:actions "Protocol output actions"},
        :params   {:channel                  "DatagramChannel",
                   :pool                     "Buffer pool",
                   :default-target           "Fallback send target",
                   :allow-limited-broadcast? "Permit 255.255.255.255 targets?"},
        :workload :io})
      ([{:keys [channel pool default-target allow-limited-broadcast?],
         :as   _args}]
       (let [running? (AtomicBoolean. true)
             action-chan (async/chan (async/sliding-buffer 128))
             state {:channel                  channel,
                    :pool                     pool,
                    :default-target           default-target,
                    :allow-limited-broadcast? (true? allow-limited-broadcast?),
                    :action-chan              action-chan,
                    :running?                 running?}
             dispatcher (async/io-thread (sender-loop {:action-chan action-chan,
                                                       :running?    running?,
                                                       :state       state}))]
         (assoc state :dispatcher dispatcher)))
      ([state transition]
       (when (= transition ::flow/stop)
         (let [^AtomicBoolean running? (:running? state)
               action-chan (:action-chan state)
               dispatcher-ch (:dispatcher state)
               ^DatagramChannel ch (:channel state)]
           (when running? (.set running? false))
           (when action-chan (async/close! action-chan))
           (when dispatcher-ch (async/<!! dispatcher-ch))
           (when (and ch (.isOpen ch))
             (try (.close ch)
                  (catch Exception e
                    (trove/log! {:level :warn,
                                 :id    ::sender-channel-close-error,
                                 :msg   "Error closing sender channel",
                                 :error e}))))))
       state)
      ([state _ msg]
       (when msg
         (let [accepted? (async/offer! (:action-chan state) msg)]
           (when-not accepted?
             (trove/log! {:level :warn,
                          :id    ::action-dropped,
                          :msg   "Sender action channel closed; dropping action",
                          :data  {:action (:type msg)}})
             (when (= :release (:type msg))
               (when-let [release (:release msg)]
                 (try (release)
                      (catch Throwable t
                        (trove/log!
                          {:level :warn,
                           :id    ::dropped-action-release-failed,
                           :msg   "Failed to release buffer for dropped action",
                           :error t}))))))))
       [state {}]))))

(comment
  (require '[clj-artnet.impl.shell.sender :as sender] :reload)
  ;; Interactive development requires real channels/pools
  :rcf)
