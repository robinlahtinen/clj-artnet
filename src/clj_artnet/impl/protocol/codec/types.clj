;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.codec.types
  "Flyweight packet types for zero-allocation hot paths.
   ArtDmx and ArtNzs packets use these types to provide map-like
   access without allocating intermediate data structures."
  (:require [clj-artnet.impl.protocol.codec.constants :as const])
  (:import (clojure.lang ILookup)
           (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

;; Forward declaration for mutual references
(declare payload-buffer)

(defn- payload-view
  "Create a read-only ByteBuffer view of payload data."
  ^ByteBuffer [^ByteBuffer buf base length header-size]
  (let [^ByteBuffer dup (.duplicate buf)
        start (+ (long base) (long header-size))
        end (+ start (long length))]
    (.position dup (int start))
    (.limit dup (int end))
    (.asReadOnlyBuffer dup)))

(defn- payload-from-buffer
  "Get payload buffer from original buffer or pre-sliced payload."
  ^ByteBuffer [^ByteBuffer buf base length header-size ^ByteBuffer payload]
  (cond buf (payload-view buf base length header-size)
        payload (let [^ByteBuffer dup (.duplicate payload)]
                  (.position dup 0)
                  (.limit dup (int length))
                  (.asReadOnlyBuffer dup))
        :else (throw (ex-info "Packet payload unavailable" {:length length}))))

;; Keys `:data`/`:payload` return a zero-copy ByteBuffer view; pooled and valid
;; only during the callback—copy if you need to keep it.
(deftype ArtDmxPacket [^ByteBuffer buf ^int base ^int length ^int sequence
                       ^int physical ^int net ^int sub-net ^int universe
                       ^int port-address ^ByteBuffer payload]
  ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [this k not-found]
    (case k
      :op :artdmx
      :length length
      :sequence sequence
      :physical physical
      :net net
      :sub-net sub-net
      :universe universe
      :port-address port-address
      :payload (payload-buffer this)
      :data (payload-buffer this)
      not-found))
  Object
  (toString [_]
    (str "#ArtDmxPacket"
         {:sequence sequence, :port port-address, :length length})))

(deftype ArtNzsPacket [op ^ByteBuffer buf ^int base ^int length ^int sequence
                       ^int start-code ^int net ^int sub-net ^int universe
                       ^int port-address vlc ^ByteBuffer payload]
  ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [this k not-found]
    (case k
      :op op
      :length length
      :sequence sequence
      :start-code start-code
      :net net
      :sub-net sub-net
      :universe universe
      :port-address port-address
      :vlc vlc
      :payload (payload-buffer this)
      :data (payload-buffer this)
      not-found))
  Object
  (toString [_]
    (str "#ArtNzsPacket"
         {:sequence   sequence,
          :start-code start-code,
          :port       port-address,
          :length     length})))

(defn payload-buffer
  "Return a read-only ByteBuffer view of the DMX or ArtNzs payload."
  ^ByteBuffer [packet]
  (cond (instance? ArtDmxPacket packet) (payload-from-buffer
                                          (.buf ^ArtDmxPacket packet)
                                          (.base ^ArtDmxPacket packet)
                                          (.length ^ArtDmxPacket packet)
                                          const/artdmx-header-size
                                          (.payload ^ArtDmxPacket packet))
        (instance? ArtNzsPacket packet) (payload-from-buffer
                                          (.buf ^ArtNzsPacket packet)
                                          (.base ^ArtNzsPacket packet)
                                          (.length ^ArtNzsPacket packet)
                                          const/artnzs-header-size
                                          (.payload ^ArtNzsPacket packet))
        :else (throw (ex-info "Unsupported packet type for payload view"
                              {:packet (class packet)}))))
