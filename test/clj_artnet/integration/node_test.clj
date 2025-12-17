;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.integration.node-test
  "Integration tests for Art-Net node network behavior.

   Tests UDP packet emission, injection handling, and sync buffer behavior."
  (:require
    [clj-artnet :as core]
    [clj-artnet.fixtures.builders :as builders]
    [clj-artnet.impl.protocol.codec.dispatch :as dispatch]
    [clj-artnet.impl.protocol.codec.domain.common :as common]
    [clj-artnet.impl.protocol.codec.types :as types]
    [clj-artnet.impl.protocol.diagnostics :as diagnostics]
    [clj-artnet.support.helpers :as helpers]
    [clojure.core.async.flow :as flow]
    [clojure.test :refer [deftest is]])
  (:import
    (java.net DatagramPacket DatagramSocket InetAddress SocketTimeoutException)
    (java.nio ByteBuffer)
    (java.util Arrays)))

(deftest start-node-handles-rx-injection
  (let [delivered (promise)
        released (promise)
        node (core/start-node!
               {:callbacks {:dmx (fn [{:keys [packet sender node]}]
                                   (let [data-view (:data packet)
                                         data-bytes (byte-array (.remaining
                                                                  data-view))]
                                     (.get data-view data-bytes)
                                     (deliver delivered
                                              {:op         (:op packet)
                                               :length     (:length packet)
                                               :sender     sender
                                               :node       node
                                               :data-bytes data-bytes})))}})
        packet (builders/artdmx-buffer (byte-array [1 2 3 4]))]
    (try
      (flow/inject (:flow node)
                   [:logic :rx]
                   [{:type    :rx
                     :packet  packet
                     :sender  {:host (InetAddress/getByName "127.0.0.1")
                               :port 6454}
                     :release #(deliver released true)}])
      (let [result (deref delivered 1000 ::timeout)]
        (is (= :artdmx (:op result)))
        (is (= 4 (:length result)))
        (is (Arrays/equals ^bytes (:data-bytes result) (byte-array [1 2 3 4])))
        (is (= "clj-artnet" (get-in result [:node :short-name]))))
      (is (true? (deref released 1000 ::timeout)) "Release action should run")
      (finally ((:stop! node))))))

(defn- recv-datagram!
  [^DatagramSocket socket timeout-ms]
  (let [buffer (byte-array 1024)
        packet (DatagramPacket. buffer (alength buffer))]
    (.setSoTimeout socket timeout-ms)
    (.receive socket packet)
    (doto (ByteBuffer/wrap buffer) (.limit (.getLength packet)) (.rewind))))

(defn- drain-pending-packets!
  "Drain all pending packets from the socket until timeout."
  [^DatagramSocket socket]
  (loop []
    (when (try (recv-datagram! socket 100)
               true
               (catch SocketTimeoutException _ false))
      (recur))))

(defn- recv-packet-of-type!
  "Receive packets until we get one of the expected op type, or timeout."
  [^DatagramSocket socket expected-op timeout-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (let [elapsed (- (System/currentTimeMillis) start)
            remaining (- timeout-ms elapsed)]
        (when (pos? remaining)
          (let [buf (try (recv-datagram! socket (min remaining 500))
                         (catch SocketTimeoutException _ nil))]
            (if buf
              (let [packet (dispatch/decode buf)]
                (if (= expected-op (:op packet)) packet (recur)))
              (recur))))))))

(defn- subscribe-for-diagnostics!
  [node sender
   {:keys [priority unicast?], :or {priority :dp-low, unicast? false}}]
  (let [priority-code (diagnostics/priority-code priority)]
    (flow/inject (:flow node)
                 [:logic :rx]
                 [{:type   :rx
                   :sender sender
                   :packet {:op              :artpoll
                            :flags           0x20
                            :diag-priority   priority-code
                            :diag-request?   true
                            :diag-unicast?   unicast?
                            :target-enabled? false
                            :target-top      0
                            :target-bottom   0}}])
    ;; Allow the async logic process to record the subscription before we
    ;; emit diagnostics
    (Thread/sleep 100)))

(deftest send-dmx-emits-udp
  (let [listener (DatagramSocket. 0)
        port (.getLocalPort listener)
        node (core/start-node! {:default-target {:host "127.0.0.1"
                                                 :port port}})
        payload (byte-array [9 8 7 6])]
    (try
      ;; Wait for node to be ready rather than fixed sleep
      (helpers/wait-for #(some? (:flow node)) 500)
      (core/send-dmx! node
                      {:net      0
                       :sub-net  0
                       :universe 0
                       :target   {:host "127.0.0.1", :port port}
                       :data     payload})
      (let [buf (recv-datagram! listener 3000)
            packet (dispatch/decode buf)]
        (is (= :artdmx (:op packet)))
        (is (= (alength payload) (:length packet)))
        (let [view (types/payload-buffer packet)
              data-view (:data packet)
              bytes (byte-array (.remaining view))
              data-bytes (byte-array (.remaining data-view))]
          (.get view bytes)
          (.get data-view data-bytes)
          (is (Arrays/equals payload bytes))
          (is (Arrays/equals payload data-bytes))))
      (finally ((:stop! node)) (.close listener)))))

(deftest send-rdm-emits-udp
  (let [listener (DatagramSocket. 0)
        port (.getLocalPort listener)
        node (core/start-node! {:default-target {:host "127.0.0.1"
                                                 :port port}})
        payload (byte-array (map unchecked-byte (range 1 40)))]
    (aset payload 20 (byte 0x30))
    (try
      ;; Wait for node to be ready rather than fixed sleep
      (helpers/wait-for #(some? (:flow node)) 500)
      (core/send-rdm! node
                      {:target       {:host "127.0.0.1", :port port}
                       :port-address (common/compose-port-address 0 0 1)
                       :rdm-packet   payload})
      (let [buf (recv-datagram! listener 3000)
            packet (dispatch/decode buf)
            view (:rdm-packet packet)
            bytes (byte-array (.remaining view))]
        (.get ^ByteBuffer view bytes)
        (is (= :artrdm (:op packet)))
        (is (= (alength payload) (:payload-length packet)))
        (is (Arrays/equals payload bytes)))
      (finally ((:stop! node)) (.close listener)))))

(deftest send-diagnostic-broadcasts-to-configured-target
  (let [listener (DatagramSocket. 0)
        port (.getLocalPort listener)
        node (core/start-node! {:bind            {:host "127.0.0.1", :port 0}
                                :random-delay-fn (constantly 0)
                                :diagnostics     {:broadcast-target
                                                  {:host "127.0.0.1", :port port}}})
        sender {:host (InetAddress/getByName "127.0.0.1"), :port port}]
    (try (subscribe-for-diagnostics! node
                                     sender
                                     {:priority :dp-med, :unicast? false})
         (drain-pending-packets! listener)                  ; Drain any ArtPollReply packets
         (core/send-diagnostic! node
                                {:text "System nominal", :priority :dp-high})
         (let [packet (recv-packet-of-type! listener :artdiagdata 3000)]
           (is (some? packet) "Expected to receive artdiagdata packet")
           (when packet
             (is (= :artdiagdata (:op packet)))
             (is (= "System nominal" (:text packet)))
             (is (= (diagnostics/priority-code :dp-high) (:priority packet)))))
         (finally ((:stop! node)) (.close listener)))))

(deftest send-diagnostic-respects-unicast-subscriber
  (let [listener (DatagramSocket. 0)
        port (.getLocalPort listener)
        node (core/start-node! {:bind            {:host "127.0.0.1", :port 0}
                                :random-delay-fn (constantly 0)
                                :diagnostics     {:broadcast-target {:host
                                                                     "127.0.0.1"
                                                                     :port 65000}}})
        sender {:host (InetAddress/getByName "127.0.0.1"), :port port}]
    (try (subscribe-for-diagnostics! node
                                     sender
                                     {:priority :dp-high, :unicast? true})
         (drain-pending-packets! listener)                  ; Drain any ArtPollReply packets
         (core/send-diagnostic!
           node
           {:text "Unicast diag", :priority :dp-critical, :logical-port 2})
         (let [packet (recv-packet-of-type! listener :artdiagdata 3000)]
           (is (some? packet) "Expected to receive artdiagdata packet")
           (when packet
             (is (= :artdiagdata (:op packet)))
             (is (= "Unicast diag" (:text packet)))
             (is (= (diagnostics/priority-code :dp-critical)
                    (:priority packet)))
             (is (= 2 (:logical-port packet)))))
         (finally ((:stop! node)) (.close listener)))))

(deftest art-sync-node-buffers-dmx-until-sync
  (let [delivered (promise)
        released (promise)
        sender {:host (InetAddress/getByName "127.0.0.1"), :port 6454}
        node (core/start-node! {:callbacks {:dmx (fn [ctx]
                                                   (deliver delivered ctx))}
                                ;; Extend buffer TTL so the staged frame
                                ;; survives our timeout check
                                :sync      {:mode :art-sync, :buffer-ttl-ms 1000}})
        packet (builders/artdmx-buffer (byte-array [1 2 3]))]
    (try (flow/inject (:flow node)
                      [:logic :rx]
                      [{:type    :rx
                        :packet  packet
                        :sender  sender
                        :release #(deliver released true)}])
         (is (= ::timeout (deref delivered 200 ::timeout)))
         (is (true? (deref released 1000 false)))
         (flow/inject (:flow node)
                      [:logic :rx]
                      [{:type   :rx
                        :sender sender
                        :packet {:op :artsync, :aux1 0, :aux2 0}}])
         (let [result (deref delivered 1000 ::timeout)
               view (types/payload-buffer (:packet result))
               bytes (byte-array (.remaining view))]
           (.get view bytes)
           (is (= :artdmx (:op (:packet result))))
           (is (= [1 2 3] (seq bytes))))
         (finally ((:stop! node))))))
