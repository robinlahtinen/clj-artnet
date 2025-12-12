(ns clj-artnet.impl.shell.net
  "Utilities for Art-Net network I/O, including address coercion and channel configuration.

   Provides helpers for java.net.InetAddress and java.nio.channels.DatagramChannel
   setup, isolated from protocol logic."
  (:import
    (java.net InetAddress InetSocketAddress SocketAddress StandardSocketOptions)
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
   Returns nil if address is not an InetSocketAddress."
  [^SocketAddress addr]
  (when (instance? InetSocketAddress addr)
    {:host (.getAddress ^InetSocketAddress addr),
     :port (.getPort ^InetSocketAddress addr)}))

(defn open-channel
  "Opens and configures a DatagramChannel for Art-Net I/O.

   Options:
   - :bind           -> Bind address map {:host ... :port ...}
   - :broadcast?     -> Enable SO_BROADCAST (default true)
   - :reuse-address? -> Enable SO_REUSEADDR (default true)"
  ^DatagramChannel
  [{:keys [bind broadcast? reuse-address?],
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

(comment
  (require '[clj-artnet.impl.shell.net :as net] :reload)
  ;; Coerce specific address
  (net/as-inet-address "192.168.1.50")
  ;; Coerce socket address
  (net/->socket-address {:host "127.0.0.1", :port 6454})
  ;; => #object[java.net.InetSocketAddress ...]
  :rcf)
