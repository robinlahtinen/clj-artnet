;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.codec.spec
  "Declarative packet specifications for Art-Net 4.

   Each packet type is defined as a vector of field descriptors that mirror
   the Art-Net 4 protocol specification exactly. The compiler (impl.codec.compiler)
   consumes these specs to generate high-performance ByteBuffer operations.

   Field descriptor keys:
   - :name      -> keyword identifying the field
   - :type      -> one of :u8, :u16le, :u16be, :fixed-string, :ipv4, :bytes, :uid
   - :length    -> for :fixed-string and :bytes, the byte length
   - :value     -> constant value (for headers/magic bytes)
   - :default   -> default value when encoding if not provided
   - :doc       -> optional documentation"
  {:skip-wiki true})

(set! *warn-on-reflection* true)

(def ^:const artnet-id "Art-Net\u0000")
(def ^:const protocol-version 14)
(def ^:const default-port 0x1936)

;; Opcodes (little-endian wire format)
(def ^:const op-poll 0x2000)
(def ^:const op-poll-reply 0x2100)
(def ^:const op-diag-data 0x2300)
(def ^:const op-command 0x2400)
(def ^:const op-data-request 0x2700)
(def ^:const op-data-reply 0x2800)
(def ^:const op-dmx 0x5000)
(def ^:const op-nzs 0x5100)
(def ^:const op-sync 0x5200)
(def ^:const op-address 0x6000)
(def ^:const op-input 0x7000)
(def ^:const op-tod-request 0x8000)
(def ^:const op-tod-data 0x8100)
(def ^:const op-tod-control 0x8200)
(def ^:const op-rdm 0x8300)
(def ^:const op-rdm-sub 0x8400)
(def ^:const op-timecode 0x9700)
(def ^:const op-trigger 0x9900)
(def ^:const op-ip-prog 0xF800)
(def ^:const op-ip-prog-reply 0xF900)
(def ^:const op-firmware-master 0xF200)
(def ^:const op-firmware-reply 0xF300)

;; Video opcodes (extended video features)
(def ^:const op-video-setup 0xA010)
(def ^:const op-video-palette 0xA020)
(def ^:const op-video-data 0xA040)

;; MAC opcodes (deprecated per Art-Net 4)
(def ^:const op-mac-master 0xF000)
(def ^:const op-mac-slave 0xF100)

;; File transfer opcodes
(def ^:const op-file-tn-master 0xF400)
(def ^:const op-file-fn-master 0xF500)
(def ^:const op-file-fn-reply 0xF600)

;; Media opcodes (Media Server features)
(def ^:const op-media 0x9000)
(def ^:const op-media-patch 0x9100)
(def ^:const op-media-control 0x9200)
(def ^:const op-media-control-reply 0x9300)

;; Time synchronization opcode
(def ^:const op-time-sync 0x9800)

;; Directory opcodes (file listing)
(def ^:const op-directory 0x9A00)
(def ^:const op-directory-reply 0x9B00)

(def header-fields
  "Common 8-byte Art-Net header present in all packets"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}])

(def art-poll-spec
  "ArtPoll packet specification - 22 bytes minimum"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-poll}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :flags
    :type :u8
    :doc
    "TalkToMe field: bit 0=suppress-delay, 1=reply-on-change, 2=diag, 3=unicast, 4=VLC, 5=targeted"}
   {:name :diag-priority, :type :u8}
   {:name :target-port-address-top, :type :u16be}
   {:name :target-port-address-bottom, :type :u16be}
   {:name :esta-man, :type :u16be} {:name :oem, :type :u16be}])

(def art-poll-reply-spec
  "ArtPollReply packet specification - 239 bytes"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-poll-reply} {:name :ip, :type :ipv4}
   {:name :port, :type :u16le, :default default-port}
   {:name :vers-hi, :type :u8} {:name :vers-lo, :type :u8}
   {:name :net-switch, :type :u8} {:name :sub-switch, :type :u8}
   {:name :oem, :type :u16be} {:name :ubea-version, :type :u8}
   {:name :status1, :type :u8} {:name :esta-man, :type :u16be}
   {:name :short-name, :type :fixed-string, :length 18}
   {:name :long-name, :type :fixed-string, :length 64}
   {:name :node-report, :type :fixed-string, :length 64}
   {:name :num-ports, :type :u16be} {:name :port-types, :type :bytes, :length 4}
   {:name :good-input, :type :bytes, :length 4}
   {:name :good-output-a, :type :bytes, :length 4}
   {:name :sw-in, :type :bytes, :length 4}
   {:name :sw-out, :type :bytes, :length 4}
   {:name :acn-priority, :type :u8, :default 100} {:name :sw-macro, :type :u8}
   {:name :sw-remote, :type :u8} {:name :spare1, :type :u8, :value 0}
   {:name :spare2, :type :u8, :value 0} {:name :spare3, :type :u8, :value 0}
   {:name :style, :type :u8} {:name :mac, :type :bytes, :length 6}
   {:name :bind-ip, :type :ipv4} {:name :bind-index, :type :u8, :default 1}
   {:name :status2, :type :u8} {:name :good-output-b, :type :bytes, :length 4}
   {:name :status3, :type :u8}
   {:name :default-responder, :type :bytes, :length 6}
   {:name :user-hi, :type :u8} {:name :user-lo, :type :u8}
   {:name :refresh-rate, :type :u16be}
   {:name :background-queue-policy, :type :u8}
   {:name :filler, :type :bytes, :length 10, :value (vec (repeat 10 0))}])

(def art-dmx-spec
  "ArtDmx packet specification - 18 byte header and up to 512 bytes payload"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-dmx}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :sequence
    :type :u8
    :doc  "Sequence number 1-255, 0 disables reordering"}
   {:name :physical, :type :u8, :doc "Physical port 0-3"}
   {:name :sub-uni, :type :u8, :doc "Low nibble=universe, high nibble=sub-net"}
   {:name :net, :type :u8, :doc "Net 0-127"}
   {:name :length, :type :u16be, :doc "DMX payload length (2-512, even)"}])

(def art-nzs-spec
  "ArtNzs packet specification - 18 byte header and up to 512 bytes payload"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-nzs}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :sequence, :type :u8}
   {:name :start-code, :type :u8, :doc "Non-zero start code (0x91 for VLC)"}
   {:name :sub-uni, :type :u8} {:name :net, :type :u8}
   {:name :length, :type :u16be}])

(def art-sync-spec
  "ArtSync packet specification - 14 bytes"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-sync}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :aux1, :type :u8, :value 0} {:name :aux2, :type :u8, :value 0}])

(def art-address-spec
  "ArtAddress packet specification - 107 bytes"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-address}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :net-switch, :type :u8, :doc "Bits 6:0=Net, bit 7=write enable"}
   {:name :bind-index, :type :u8}
   {:name :short-name, :type :fixed-string, :length 18}
   {:name :long-name, :type :fixed-string, :length 64}
   {:name :sw-in, :type :bytes, :length 4}
   {:name :sw-out, :type :bytes, :length 4} {:name :sub-switch, :type :u8}
   {:name :acn-priority, :type :u8} {:name :command, :type :u8}])

(def art-input-spec
  "ArtInput packet specification - 20 bytes"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-input}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :filler1, :type :u8, :value 0} {:name :bind-index, :type :u8}
   {:name :num-ports-hi, :type :u8} {:name :num-ports-lo, :type :u8}
   {:name   :input
    :type   :bytes
    :length 4
    :doc    "Bit 0 per port: 1=disable input"}])

(def art-diag-data-spec
  "ArtDiagData packet specification - 18 byte header and up to 512 bytes text"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-diag-data}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :filler1, :type :u8, :value 0}
   {:name :priority
    :type :u8
    :doc
    "DpLow=0x10, DpMed=0x40, DpHigh=0x80, DpCritical=0xE0, DpVolatile=0xF0"}
   {:name :logical-port, :type :u8} {:name :filler3, :type :u8, :value 0}
   {:name :length
    :type :u16be
    :doc  "Length of Data[] including null terminator"}])

(def art-timecode-spec
  "ArtTimeCode packet specification - 19 bytes"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-timecode}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :filler1, :type :u8, :value 0} {:name :stream-id, :type :u8}
   {:name :frames, :type :u8} {:name :seconds, :type :u8}
   {:name :minutes, :type :u8} {:name :hours, :type :u8}
   {:name :type, :type :u8, :doc "0=Film, 1=EBU, 2=DF, 3=SMPTE"}])

(def art-trigger-spec
  "ArtTrigger packet specification - 18 byte header and up to 512 bytes payload"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-trigger}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :filler1, :type :u8, :value 0} {:name :filler2, :type :u8, :value 0}
   {:name :oem, :type :u16be}
   {:name :key, :type :u8, :doc "0=Ascii, 1=Macro, 2=Soft, 3=Show"}
   {:name :sub-key, :type :u8}])

(def art-command-spec
  "ArtCommand packet specification - 16 byte header and up to 512 bytes text"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-command}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :esta-man, :type :u16be} {:name :length, :type :u16be}])

(def art-ip-prog-spec
  "ArtIpProg packet specification - 30 bytes minimum"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-ip-prog}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :filler1, :type :u8, :value 0} {:name :filler2, :type :u8, :value 0}
   {:name :command, :type :u8} {:name :filler4, :type :u8, :value 0}
   {:name :prog-ip, :type :ipv4} {:name :prog-sm, :type :ipv4}
   {:name :prog-port-hi, :type :u8} {:name :prog-port-lo, :type :u8}
   {:name :prog-gateway, :type :ipv4}])

(def art-ip-prog-reply-spec
  "ArtIpProgReply packet specification - 34 bytes"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-ip-prog-reply}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :filler1, :type :u8, :value 0} {:name :filler2, :type :u8, :value 0}
   {:name :filler3, :type :u8, :value 0} {:name :filler4, :type :u8, :value 0}
   {:name :prog-ip, :type :ipv4} {:name :prog-sm, :type :ipv4}
   {:name :prog-port-hi, :type :u8} {:name :prog-port-lo, :type :u8}
   {:name :status, :type :u8, :doc "Bit 6: DHCP enabled"}
   {:name :filler8, :type :u8, :value 0} {:name :prog-gateway, :type :ipv4}
   {:name :filler9, :type :u8, :value 0}
   {:name :filler10, :type :u8, :value 0}])

(def art-tod-request-spec
  "ArtTodRequest packet specification - 24 byte header and up to 32 addresses"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-tod-request}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :filler1, :type :u8, :value 0} {:name :filler2, :type :u8, :value 0}
   {:name :spare1, :type :u8, :value 0} {:name :spare2, :type :u8, :value 0}
   {:name :spare3, :type :u8, :value 0} {:name :spare4, :type :u8, :value 0}
   {:name :spare5, :type :u8, :value 0} {:name :spare6, :type :u8, :value 0}
   {:name :spare7, :type :u8, :value 0} {:name :net, :type :u8}
   {:name :command, :type :u8, :doc "0=TodFull, 1=TodNak"}
   {:name :add-count, :type :u8, :doc "Number of address entries (0-32)"}])

(def art-tod-data-spec
  "ArtTodData packet specification - 28 byte header and UIDs"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-tod-data}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :rdm-version, :type :u8, :default 1} {:name :port, :type :u8}
   {:name :spare1, :type :u8, :value 0} {:name :spare2, :type :u8, :value 0}
   {:name :spare3, :type :u8, :value 0} {:name :spare4, :type :u8, :value 0}
   {:name :spare5, :type :u8, :value 0} {:name :spare6, :type :u8, :value 0}
   {:name :bind-index, :type :u8, :default 1} {:name :net, :type :u8}
   {:name :command-response, :type :u8, :doc "0=TodFull, 0xFF=TodNak"}
   {:name :address, :type :u8} {:name :uid-total-hi, :type :u8}
   {:name :uid-total-lo, :type :u8} {:name :block-count, :type :u8}
   {:name :uid-count, :type :u8}])

(def art-tod-control-spec
  "ArtTodControl packet specification - 24 bytes"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-tod-control}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :filler1, :type :u8, :value 0} {:name :filler2, :type :u8, :value 0}
   {:name :spare1, :type :u8, :value 0} {:name :spare2, :type :u8, :value 0}
   {:name :spare3, :type :u8, :value 0} {:name :spare4, :type :u8, :value 0}
   {:name :spare5, :type :u8, :value 0} {:name :spare6, :type :u8, :value 0}
   {:name :spare7, :type :u8, :value 0} {:name :net, :type :u8}
   {:name :command, :type :u8, :doc "0=AtcNone, 1=AtcFlush"}
   {:name :address, :type :u8}])

(def art-rdm-spec
  "ArtRdm packet specification - 24 byte header and RDM payload"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-rdm}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :rdm-version, :type :u8, :default 1}
   {:name :spare1, :type :u8, :value 0} {:name :spare2, :type :u8, :value 0}
   {:name :spare3, :type :u8, :value 0} {:name :spare4, :type :u8, :value 0}
   {:name :spare5, :type :u8, :value 0} {:name :spare6, :type :u8, :value 0}
   {:name :fifo-available, :type :u8} {:name :fifo-max, :type :u8}
   {:name :net, :type :u8} {:name :command, :type :u8, :doc "0=ArProcess"}
   {:name :address, :type :u8}])

(def art-rdm-sub-spec
  "ArtRdmSub packet specification - 32 byte header + payload"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-rdm-sub}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :rdm-version, :type :u8, :default 1}
   {:name :filler2, :type :u8, :value 0}
   {:name :uid, :type :uid, :doc "6-byte RDM UID"}
   {:name :spare1, :type :u8, :value 0}
   {:name :command-class
    :type :u8
    :doc  "0x20=Get, 0x21=GetResp, 0x30=Set, 0x31=SetResp"}
   {:name :parameter-id, :type :u16be} {:name :sub-device, :type :u16be}
   {:name :sub-count, :type :u16be} {:name :spare2, :type :u8, :value 0}
   {:name :spare3, :type :u8, :value 0} {:name :spare4, :type :u8, :value 0}
   {:name :spare5, :type :u8, :value 0}])

(def art-firmware-master-spec
  "ArtFirmwareMaster packet specification - 40 byte header and up to 1024 bytes"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-firmware-master}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :filler1, :type :u8, :value 0} {:name :filler2, :type :u8, :value 0}
   {:name :type, :type :u8, :doc "Block type: 0-2=firmware, 3-5=UBEA"}
   {:name :block-id, :type :u8}
   {:name :firmware-length-3, :type :u8, :doc "MSB of 32-bit length"}
   {:name :firmware-length-2, :type :u8} {:name :firmware-length-1, :type :u8}
   {:name :firmware-length-0, :type :u8, :doc "LSB of 32-bit length"}
   {:name :spare1, :type :bytes, :length 20, :value (vec (repeat 20 0))}])

(def art-firmware-reply-spec
  "ArtFirmwareReply packet specification - 36 bytes"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-firmware-reply}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :filler1, :type :u8, :value 0} {:name :filler2, :type :u8, :value 0}
   {:name :type, :type :u8, :doc "0=BlockGood, 1=AllGood, 0xFF=Fail"}
   {:name :spare, :type :bytes, :length 21, :value (vec (repeat 21 0))}])

(def art-data-request-spec
  "ArtDataRequest packet specification - 40 bytes"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-data-request}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :esta-man, :type :u16be} {:name :oem, :type :u16be}
   {:name :request, :type :u16be}
   {:name :spare, :type :bytes, :length 22, :value (vec (repeat 22 0))}])

(def art-data-reply-spec
  "ArtDataReply packet specification - 20 byte header and up to 512 bytes"
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-data-reply}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :esta-man, :type :u16be} {:name :oem, :type :u16be}
   {:name :request, :type :u16be} {:name :length, :type :u16be}])

(def art-video-setup-spec
  "ArtVideoSetup packet specification - video screen setup.
   Note: Art-Net 4 spec provides opcode but no detailed field layout."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-video-setup}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}])

(def art-video-palette-spec
  "ArtVideoPalette packet specification - colour palette setup.
   Note: Art-Net 4 spec provides opcode but no detailed field layout."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-video-palette}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}])

(def art-video-data-spec
  "ArtVideoData packet specification - display data.
   Note: Art-Net 4 spec provides opcode but no detailed field layout."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-video-data}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}])

(def art-mac-master-spec
  "ArtMacMaster packet specification - DEPRECATED per Art-Net 4.
   Retained for backward compatibility with legacy devices."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-mac-master}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}])

(def art-mac-slave-spec
  "ArtMacSlave packet specification - DEPRECATED per Art-Net 4.
   Retained for backward compatibility with legacy devices."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-mac-slave}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}])

(def art-file-tn-master-spec
  "ArtFileTnMaster packet specification - uploads a user file to a node.
   Note: Uses the same reply mechanism as ArtFirmwareReply."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-file-tn-master}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :filler1, :type :u8, :value 0} {:name :filler2, :type :u8, :value 0}
   {:name :type, :type :u8, :doc "Transfer type"}
   {:name :block-id, :type :u8, :doc "Block sequence number"}
   {:name :length-3, :type :u8, :doc "MSB of 32-bit file length"}
   {:name :length-2, :type :u8} {:name :length-1, :type :u8}
   {:name :length-0, :type :u8, :doc "LSB of 32-bit file length"}
   {:name :name, :type :fixed-string, :length 14, :doc "Filename"}
   {:name :checksum-hi, :type :u8} {:name :checksum-lo, :type :u8}])

(def art-file-fn-master-spec
  "ArtFileFnMaster packet specification - downloads a user file from the node."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-file-fn-master}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :filler1, :type :u8, :value 0} {:name :filler2, :type :u8, :value 0}
   {:name :type, :type :u8, :doc "Transfer type"}
   {:name :filler3, :type :u8, :value 0} {:name :filler4, :type :u8, :value 0}
   {:name :filler5, :type :u8, :value 0} {:name :filler6, :type :u8, :value 0}
   {:name :filler7, :type :u8, :value 0}
   {:name :name, :type :fixed-string, :length 14, :doc "Filename to download"}])

(def art-file-fn-reply-spec
  "ArtFileFnReply packet specification - server-to-node acknowledge."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-file-fn-reply}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :type, :type :u8, :doc "Transfer status"}
   {:name :spare, :type :bytes, :length 21, :value (vec (repeat 21 0))}])

(def art-media-spec
  "ArtMedia packet specification - unicast by Media Server to Controller.
   Note: Art-Net 4 spec provides opcode but no detailed field layout."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-media}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}])

(def art-media-patch-spec
  "ArtMediaPatch packet specification - unicast by Controller to Media Server.
   Note: Art-Net 4 spec provides opcode but no detailed field layout."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-media-patch}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}])

(def art-media-control-spec
  "ArtMediaControl packet specification - unicast by Controller to Media Server.
   Note: Art-Net 4 spec provides opcode but no detailed field layout."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-media-control}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}])

(def art-media-control-reply-spec
  "ArtMediaControlReply packet specification - unicast by Media Server to Controller.
   Note: Art-Net 4 spec provides opcode but no detailed field layout."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-media-control-reply}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}])

(def art-time-sync-spec
  "ArtTimeSync packet specification - used to synchronize real time date and clock.
   Note: Art-Net 4 spec provides opcode but no detailed field layout."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-time-sync}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}])

(def art-directory-spec
  "ArtDirectory packet specification - requests a node's file list."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-directory}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :filler1, :type :u8, :value 0} {:name :filler2, :type :u8, :value 0}
   {:name :command, :type :u8, :doc "0=GetFirst, 1=GetNext"}
   {:name :file-type, :type :u8, :doc "Type of files to list"}])

(def art-directory-reply-spec
  "ArtDirectoryReply packet specification - replies to OpDirectory with a file list."
  [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
   {:name :op-code, :type :u16le, :value op-directory-reply}
   {:name :prot-ver-hi, :type :u8, :value 0}
   {:name :prot-ver-lo, :type :u8, :value protocol-version}
   {:name :filler1, :type :u8, :value 0} {:name :filler2, :type :u8, :value 0}
   {:name :flags, :type :u8, :doc "Bit 0: Last entry"}
   {:name :file-type, :type :u8}
   {:name :name, :type :fixed-string, :length 16, :doc "Filename"}
   {:name   :description
    :type   :fixed-string
    :length 64
    :doc    "File description"}
   {:name :length-3, :type :u8, :doc "MSB of 32-bit file size"}
   {:name :length-2, :type :u8} {:name :length-1, :type :u8}
   {:name :length-0, :type :u8, :doc "LSB of 32-bit file size"}])

(def packet-specs
  "Registry of all packet specifications keyed by opcode keyword"
  {:artpoll              art-poll-spec
   :artpollreply         art-poll-reply-spec
   :artdmx               art-dmx-spec
   :artnzs               art-nzs-spec
   :artsync              art-sync-spec
   :artaddress           art-address-spec
   :artinput             art-input-spec
   :artdiagdata          art-diag-data-spec
   :arttimecode          art-timecode-spec
   :arttrigger           art-trigger-spec
   :artcommand           art-command-spec
   :artipprog            art-ip-prog-spec
   :artipprogreply       art-ip-prog-reply-spec
   :arttodrequest        art-tod-request-spec
   :arttoddata           art-tod-data-spec
   :arttodcontrol        art-tod-control-spec
   :artrdm               art-rdm-spec
   :artrdmsub            art-rdm-sub-spec
   :artfirmwaremaster    art-firmware-master-spec
   :artfirmwarereply     art-firmware-reply-spec
   :artdatarequest       art-data-request-spec
   :artdatareply         art-data-reply-spec
   :artvideosetup        art-video-setup-spec
   :artvideopalette      art-video-palette-spec
   :artvideodata         art-video-data-spec
   :artmacmaster         art-mac-master-spec
   :artmacslave          art-mac-slave-spec
   :artfiletnmaster      art-file-tn-master-spec
   :artfilefnmaster      art-file-fn-master-spec
   :artfilefnreply       art-file-fn-reply-spec
   :artmedia             art-media-spec
   :artmediapatch        art-media-patch-spec
   :artmediacontrol      art-media-control-spec
   :artmediacontrolreply art-media-control-reply-spec
   :arttimesync          art-time-sync-spec
   :artdirectory         art-directory-spec
   :artdirectoryreply    art-directory-reply-spec})

(defn field-size
  "Calculate the byte size of a single field"
  ^long [{:keys [type length]}]
  (case type
    :fixed-string (long length)
    :ipv4 4
    :uid 6
    :u8 1
    :u16le 2
    :u16be 2
    :bytes (long length)
    0))

(defn spec-header-size
  "Calculate the total byte size of a packet spec (header only, excludes variable payload)"
  [spec]
  (reduce (fn [acc field] (+ acc (field-size field))) 0 spec))

(defn field-offset
  "Calculate the byte offset of a named field within a spec.
   Returns the offset as a long, or -1 if the field is not found."
  ^long [spec field-name]
  (loop [offset (long 0)
         fields spec]
    (if-let [[field & remaining] (seq fields)]
      (if (= (:name field) field-name)
        offset
        (recur (unchecked-add offset (field-size field)) remaining))
      -1)))

(defn field-by-name
  "Look up a field descriptor by name"
  [spec field-name]
  (some #(when (= (:name %) field-name) %) spec))

(comment
  ;; Verify spec sizes match Art-Net 4 specification
  (spec-header-size art-poll-spec)                          ;; => 22
  (spec-header-size art-poll-reply-spec)                    ;; => 239
  (spec-header-size art-dmx-spec)                           ;; => 18
  (spec-header-size art-sync-spec)                          ;; => 14
  (spec-header-size art-address-spec)                       ;; => 107
  (spec-header-size art-input-spec)                         ;; => 20
  (spec-header-size art-timecode-spec)                      ;; => 19
  (spec-header-size art-trigger-spec)                       ;; => 18
  (spec-header-size art-tod-data-spec)                      ;; => 28
  (spec-header-size art-rdm-spec)                           ;; => 24
  (spec-header-size art-rdm-sub-spec)                       ;; => 32
  (spec-header-size art-firmware-reply-spec)                ;; => 36
  (spec-header-size art-ip-prog-reply-spec)                 ;; => 34
  ;; Field offset lookups
  (field-offset art-poll-reply-spec :short-name)            ;; => 26
  (field-offset art-poll-reply-spec :long-name)             ;; => 44
  (field-offset art-dmx-spec :length)                       ;; => 16
  :rcf)
