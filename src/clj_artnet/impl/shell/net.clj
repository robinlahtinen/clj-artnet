;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.shell.net
  "Utilities for Art-Net network I/O, including address coercion and channel configuration.

   Provides helpers for java.net.InetAddress and java.nio.channels.DatagramChannel
   setup, isolated from protocol logic."
  (:import
    (java.net Inet4Address InetAddress InetSocketAddress NetworkInterface SocketAddress SocketException StandardSocketOptions)
    (java.nio.channels DatagramChannel)
    (java.nio.channels.spi SelectorProvider)))

(set! *warn-on-reflection* true)

(def ^:const default-artnet-port
  "Default Art-Net UDP port (0x1936 = 6454)."
  0x1936)

(def ^:private ^InetAddress limited-broadcast-address
  (InetAddress/getByName "255.255.255.255"))

(defn as-inet-address
  "Coerces host representation to InetAddress.

   Accepts InetAddress, String, or nil (defaults to 0.0.0.0)."
  ^InetAddress [host]
  (cond (instance? InetAddress host) host
        (string? host) (InetAddress/getByName ^String host)
        (nil? host) (InetAddress/getByName "0.0.0.0")
        :else (throw (ex-info "Unsupported host representation" {:host host}))))

(defn limited-broadcast-address?
  "Returns true if addr is the limited broadcast address 255.255.255.255."
  [^InetAddress addr]
  (.equals ^InetAddress limited-broadcast-address addr))

(defn ->socket-address
  "Coerces {:host ... :port ...} map to InetSocketAddress.
   Defaults port to 6454 (0x1936) if nil."
  ^InetSocketAddress [{:keys [host port]}]
  (let [^InetAddress addr (as-inet-address host)]
    (InetSocketAddress. addr (int (or port default-artnet-port)))))

(defn sender-from-socket
  "Extracts sender info {:host ... :port ...} from a SocketAddress.
   Returns nil if the address is not an InetSocketAddress."
  [^SocketAddress addr]
  (when (instance? InetSocketAddress addr)
    {:host (.getAddress ^InetSocketAddress addr)
     :port (.getPort ^InetSocketAddress addr)}))

(defn open-channel
  "Opens and configures a DatagramChannel for Art-Net I/O.

   Options:
   - :bind           -> Bind address map {:host ... :port ...}
   - :broadcast?     -> Enable SO_BROADCAST (default true)
   - :reuse-address? -> Enable SO_REUSEADDR (default true)"
  ^DatagramChannel
  [{:keys [bind broadcast? reuse-address?]
    :or   {broadcast? true, reuse-address? true}}]
  (let [^SelectorProvider provider (SelectorProvider/provider)
        ^DatagramChannel ch (.openDatagramChannel provider)]
    (.setOption ch
                StandardSocketOptions/SO_REUSEADDR
                (Boolean/valueOf (boolean reuse-address?)))
    (when broadcast?
      (.setOption ch StandardSocketOptions/SO_BROADCAST Boolean/TRUE))
    (.configureBlocking ch true)
    (when bind (.bind ch ^SocketAddress (->socket-address bind)))
    ch))

(defn- usable-addr?
  "Returns true if addr is suitable for ArtPollReply.
   Art-Net uses IPv4 only; filters loopback, link-local, multicast, any-local."
  [^InetAddress addr]
  (and (some? addr)
       (instance? Inet4Address addr)
       (not (.isAnyLocalAddress addr))
       (not (.isLoopbackAddress addr))
       (not (.isLinkLocalAddress addr))
       (not (.isMulticastAddress addr))))

(defn- artnet-range?
  "Returns true if addr is in Art-Net preferred ranges.
   Primary: 2.x.x.x, Secondary: 10.x.x.x per spec."
  [^InetAddress addr]
  (let [octets (.getAddress addr)
        first-octet (bit-and (aget octets 0) 0xFF)]
    (or (= first-octet 2) (= first-octet 10))))

(defn- usable-interface?
  "Returns true if the interface is usable for Art-Net."
  [^NetworkInterface ni]
  (try (and (.isUp ni) (not (.isLoopback ni)) (not (.isVirtual ni)))
       (catch SocketException _ false)))

(defn detect-local-ip
  "Detects the primary non-loopback IPv4 address.

   Returns [a b c d] vector or nil.
   Prefers Art-Net IP address ranges (2.x.x.x, 10.x.x.x) per spec."
  []
  (try (let [interfaces (->> (NetworkInterface/getNetworkInterfaces)
                             enumeration-seq
                             (filter usable-interface?))
             addrs (->> interfaces
                        (mapcat #(enumeration-seq (.getInetAddresses
                                                    ^NetworkInterface %)))
                        (filter usable-addr?))]
         (if-let [artnet-addr (first (filter artnet-range? addrs))]
           (mapv #(bit-and % 0xFF) (.getAddress ^InetAddress artnet-addr))
           (when-let [any-addr (first addrs)]
             (mapv #(bit-and % 0xFF) (.getAddress ^InetAddress any-addr)))))
       (catch SocketException _ nil)))

(defn parse-host
  "Parses host to [a b c d] vector.

   Accepts: IP address string, vector, or nil.
   Returns nil for nil input."
  [host]
  (cond (nil? host) nil
        (sequential? host) (mapv #(bit-and (int %) 0xFF) (take 4 host))
        (string? host) (mapv #(bit-and % 0xFF)
                             (.getAddress (InetAddress/getByName host)))
        :else (throw (ex-info "Invalid host format" {:host host}))))

(defn wildcard?
  "Returns true if the host represents any IP address (0.0.0.0 or nil)."
  [host]
  (or (nil? host) (= host "0.0.0.0") (= host [0 0 0 0])))

(comment
  (require '[clj-artnet.impl.shell.net :as net] :reload)
  ;; Coerce specific address
  (net/as-inet-address "192.168.1.50")
  ;; Coerce socket address
  (net/->socket-address {:host "127.0.0.1", :port 6454})
  ;; => #object[java.net.InetSocketAddress ...]
  ;; IP detection
  (net/detect-local-ip)
  ;; Parse host
  (net/parse-host "192.168.1.50")
  (net/parse-host [10 0 0 1])
  ;; Wildcard?
  (net/wildcard? "0.0.0.0")
  (net/wildcard? [0 0 0 0])
  :rcf)
