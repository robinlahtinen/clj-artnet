;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.codec.dispatch
  "Central opcode dispatch for Art-Net packet encoding and decoding.
   Routes to domain-specific handlers while maintaining the original
   public API contract."
  (:require
    [clj-artnet.impl.protocol.codec.constants :as const]
    [clj-artnet.impl.protocol.codec.domain.common :as common]
    [clj-artnet.impl.protocol.codec.domain.config :as config]
    [clj-artnet.impl.protocol.codec.domain.data :as data]
    [clj-artnet.impl.protocol.codec.domain.diag :as diag]
    [clj-artnet.impl.protocol.codec.domain.dmx :as dmx]
    [clj-artnet.impl.protocol.codec.domain.firmware :as firmware]
    [clj-artnet.impl.protocol.codec.domain.poll :as poll]
    [clj-artnet.impl.protocol.codec.domain.rdm :as rdm]
    [clj-artnet.impl.protocol.codec.primitives :as prim])
  (:import
    (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(defn encode
  "Encode a packet map into a ByteBuffer ready for transmission. Supply *buf*
  to reuse an existing buffer and avoid allocations on the hot path."
  ([packet] (encode packet nil))
  ([{:keys [op data], :as packet} ^ByteBuffer buf]
   (let [result
         (case op
           :artdmx (let [payload-size (prim/payload-length data)
                         target (prim/prepare-target buf
                                                     (+ const/artdmx-header-size
                                                        payload-size))]
                     (dmx/encode-artdmx! target packet))
           :artnzs (dmx/encode-artnzs! (prim/prepare-target
                                         buf
                                         (+ const/artnzs-header-size
                                            (prim/payload-length data)))
                                       packet)
           :artvlc (dmx/encode-artvlc! buf packet)
           :artpollreply (poll/encode-artpollreply!
                           (prim/prepare-target buf const/artpollreply-length)
                           packet)
           :artinput (config/encode-artinput!
                       (prim/prepare-target buf const/artinput-length)
                       packet)
           :artipprogreply (config/encode-artipprogreply!
                             (prim/prepare-target buf
                                                  const/artipprogreply-length)
                             packet)
           :artdiagdata
           (diag/encode-artdiagdata!
             (prim/prepare-target buf (+ const/artdiagdata-header-size 512))
             packet)
           :artcommand (diag/encode-artcommand! buf packet)
           :arttrigger (diag/encode-arttrigger!
                         (prim/prepare-target buf const/arttrigger-length)
                         packet)
           :arttimecode (diag/encode-arttimecode!
                          (prim/prepare-target buf const/arttimecode-length)
                          packet)
           :artdatarequest (data/encode-artdatarequest!
                             (prim/prepare-target buf
                                                  const/artdatarequest-length)
                             packet)
           :artdatareply (data/encode-artdatareply! buf packet)
           :artrdm (rdm/encode-artrdm! buf packet)
           :artrdmsub (rdm/encode-artrdmsub! buf packet)
           :arttoddata (rdm/encode-arttoddata! buf packet)
           :artfirmwarereply
           (firmware/encode-artfirmwarereply!
             (prim/prepare-target buf const/artfirmwarereply-length)
             packet)
           ::unsupported)]
     (if (identical? ::unsupported result)
       (if (contains? const/generic-payload-ops op)
         (common/encode-generic-packet! buf op packet)
         (throw (ex-info "Unsupported opcode" {:op op})))
       result))))

(defn decode
  "Decode an Art-Net frame from *buf*. ArtDmx returns a flyweight packet and
  control packets return immutable maps."
  [^ByteBuffer buf]
  (let [view (.duplicate buf)]
    (.clear view)
    (.limit view (.limit buf))
    (prim/ensure-header! view)
    (let [opcode (prim/uint16-le view 8)
          op (const/opcode->keyword opcode)
          result (case op
                   :artdmx (dmx/decode-artdmx view)
                   :artnzs (dmx/decode-artnzs view)
                   :artsync (dmx/decode-artsync view)
                   :artpoll (poll/decode-artpoll view)
                   :artpollreply (poll/decode-artpollreply view)
                   :artinput (config/decode-artinput view)
                   :artaddress (config/decode-artaddress view)
                   :artipprog (config/decode-artipprog view)
                   :artdiagdata (diag/decode-artdiagdata view)
                   :artcommand (diag/decode-artcommand view)
                   :arttrigger (diag/decode-arttrigger view)
                   :arttimecode (diag/decode-arttimecode view)
                   :artdatarequest (data/decode-artdatarequest view)
                   :artdatareply (data/decode-artdatareply view)
                   :artrdm (rdm/decode-artrdm view)
                   :artrdmsub (rdm/decode-artrdmsub view)
                   :arttodrequest (rdm/decode-arttodrequest view)
                   :arttoddata (rdm/decode-arttoddata view)
                   :arttodcontrol (rdm/decode-arttodcontrol view)
                   :artfirmwaremaster (firmware/decode-artfirmwaremaster view)
                   :artfirmwarereply (firmware/decode-artfirmwarereply view)
                   ::unsupported)]
      (cond (not (identical? ::unsupported result)) result
            (contains? const/generic-payload-ops op)
            (common/decode-generic-payload op view)
            :else (throw (ex-info "Unsupported opcode" {:opcode opcode}))))))
