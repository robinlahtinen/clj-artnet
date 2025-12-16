;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.codec.constants
  "Art-Net 4 protocol constants, opcodes, and lookup tables.
   Single source of truth for all protocol-level values derived from
   the Art-Net 4 specification."
  (:require [clj-artnet.impl.protocol.codec.spec :as spec])
  (:import (java.nio.charset StandardCharsets)))

(def ^:const protocol-version "Art-Net protocol version (14 for Art-Net 4)." 14)

(def ^"[B" artnet-id-bytes
  "Art-Net packet ID magic bytes (\"Art-Net\\0\" as a byte array)."
  (.getBytes spec/artnet-id StandardCharsets/US_ASCII))

(def ^:const artnet-id-length "Length of Art-Net ID header in bytes." 8)

(def ^:const artnet-base-header-size
  "Minimum Art-Net packet header: ID (8) + OpCode (2) + ProtVer (2)."
  12)

(def ^:const max-dmx-channels "Maximum DMX512 channels per universe." 512)

(def ^:const artdmx-header-size "ArtDmx header size before payload." 18)

(def ^:const artnzs-header-size "ArtNzs header size before payload." 18)

(def ^:const artvlc-header-size
  "ArtVlc header size (within ArtNzs payload)."
  22)

(def ^:const artvlc-start-code
  "Start code identifying VLC data in ArtNzs."
  0x91)

(def ^:const artvlc-magic
  "Magic bytes identifying VLC frame payload."
  [0x41 0x4C 0x45])

(def ^:const artvlc-flag-ieee "VLC flag: IEEE compliant data." 0x80)

(def ^:const artvlc-flag-reply "VLC flag: reply requested." 0x40)

(def ^:const artvlc-flag-beacon "VLC flag: beacon mode." 0x20)

(def ^:const artvlc-flags-mask
  "Mask for valid VLC flag bits."
  (bit-or artvlc-flag-ieee artvlc-flag-reply artvlc-flag-beacon))

(def ^:const artvlc-max-payload
  "Maximum VLC payload size."
  (- max-dmx-channels artvlc-header-size))

(def ^:const artpollreply-length "ArtPollReply fixed packet length." 239)

(def ^:const artinput-length "ArtInput fixed packet length." 20)

(def ^:const artinput-disable-bit "Bit flag to disable the input port." 0x01)

(def ^:const artaddress-length "ArtAddress fixed packet length." 107)

(def ^:const artipprog-min-length "ArtIpProg minimum packet length." 30)

(def ^:const artipprogreply-length "ArtIpProgReply fixed packet length." 34)

(def ^:const artdiagdata-header-size
  "ArtDiagData header size before payload."
  18)

(def ^:const artcommand-header-size "ArtCommand header size before payload." 16)

(def ^:const artcommand-max-bytes "Maximum ArtCommand payload bytes." 512)

(def ^:const artcommand-max-length
  "Maximum ArtCommand total packet length."
  (+ artcommand-header-size artcommand-max-bytes))

(def ^:const arttrigger-header-size "ArtTrigger header size before payload." 18)

(def ^:const arttrigger-max-data "Maximum ArtTrigger payload bytes." 512)

(def ^:const arttrigger-length
  "ArtTrigger fixed packet length."
  (+ arttrigger-header-size arttrigger-max-data))

(def ^:const arttimecode-length "ArtTimeCode fixed packet length." 19)

(def ^:const artdatarequest-length "ArtDataRequest fixed packet length." 40)

(def ^:const artdatareply-header-size
  "ArtDataReply header size before payload."
  20)

(def ^:const artdatareply-max-bytes "Maximum ArtDataReply payload bytes." 512)

(def ^:const arttodrequest-base-length "ArtTodRequest base packet length." 24)

(def ^:const arttodrequest-max-addresses
  "Maximum addresses in ArtTodRequest."
  32)

(def ^:const arttoddata-header-size
  "ArtTodData header size before the UID list."
  28)

(def ^:const arttodcontrol-length "ArtTodControl fixed packet length." 24)

(def ^:const artrdm-header-size "ArtRdm header size before RDM payload." 24)

(def ^:const artrdmsub-header-size "ArtRdmSub header size before values." 32)

(def ^:const artrdmsub-max-bytes "Maximum ArtRdmSub payload bytes." 512)

(def ^:const max-tod-uids-per-packet
  "Maximum RDM UIDs per ArtTodData packet."
  200)

(def ^:const artfirmwaremaster-header-size
  "ArtFirmwareMaster header size before data."
  40)

(def ^:const artfirmwaremaster-max-bytes
  "Maximum ArtFirmwareMaster block size."
  1024)

(def ^:const artfirmwarereply-length "ArtFirmwareReply fixed packet length." 36)

(def ^:const opcode->keyword
  "Map from numeric Art-Net opcode to keyword."
  {0x2000 :artpoll,
   0x2100 :artpollreply,
   0x2300 :artdiagdata,
   0x2400 :artcommand,
   0x2700 :artdatarequest,
   0x2800 :artdatareply,
   0x5000 :artdmx,
   0x5100 :artnzs,
   0x5200 :artsync,
   0x6000 :artaddress,
   0x7000 :artinput,
   0x8000 :arttodrequest,
   0x8100 :arttoddata,
   0x8200 :arttodcontrol,
   0x8300 :artrdm,
   0x8400 :artrdmsub,
   0x9000 :artmedia,
   0x9100 :artmediapatch,
   0x9200 :artmediacontrol,
   0x9300 :artmediacontrolreply,
   0x9700 :arttimecode,
   0x9800 :arttimesync,
   0x9900 :arttrigger,
   0x9A00 :artdirectory,
   0x9B00 :artdirectoryreply,
   0xA010 :artvideosetup,
   0xA020 :artvideopalette,
   0xA040 :artvideodata,
   0xF000 :artmacmaster,
   0xF100 :artmacslave,
   0xF200 :artfirmwaremaster,
   0xF300 :artfirmwarereply,
   0xF400 :artfiletnmaster,
   0xF500 :artfilefnmaster,
   0xF600 :artfilefnreply,
   0xF800 :artipprog,
   0xF900 :artipprogreply})

(def ^:const keyword->opcode
  "Map from keyword to numeric Art-Net opcode."
  (-> (into {} (map (fn [[opcode op]] [op opcode]) opcode->keyword))
      (assoc :artvlc 0x5100)))

(def generic-payload-ops
  "Opcodes with undocumented field layouts that pass payloads through untouched."
  #{:artmedia :artmediapatch :artmediacontrol :artmediacontrolreply :arttimesync
    :artdirectory :artdirectoryreply :artvideosetup :artvideopalette
    :artvideodata :artmacmaster :artmacslave :artfiletnmaster :artfilefnmaster
    :artfilefnreply})

(def ^:const arttrigger-key->keyword
  "Map from ArtTrigger key code to keyword."
  {0 :key-ascii, 1 :key-macro, 2 :key-soft, 3 :key-show})

(def ^:const arttrigger-keyword->key
  "Map from keyword to ArtTrigger key code."
  (into {} (map (fn [[code kw]] [kw code]) arttrigger-key->keyword)))

(def ^:const datarequest-code->keyword
  "Map from ArtDataRequest code to keyword."
  {0x0000 :dr-poll,
   0x0001 :dr-url-product,
   0x0002 :dr-url-user-guide,
   0x0003 :dr-url-support,
   0x0004 :dr-url-pers-udr,
   0x0005 :dr-url-pers-gdtf,
   0x0006 :dr-ip-product,
   0x0007 :dr-ip-user-guide,
   0x0008 :dr-ip-support,
   0x0009 :dr-ip-pers-udr,
   0x000A :dr-ip-pers-gdtf})

(def ^:const datarequest-keyword->code
  "Map from keyword to ArtDataRequest code."
  (into {} (map (fn [[code kw]] [kw code]) datarequest-code->keyword)))

(def ^:const rdm-command-class->keyword
  "Map from RDM command class code to keyword."
  {0x20 :get, 0x21 :get-response, 0x30 :set, 0x31 :set-response})

(def ^:const rdm-command-keyword->class
  "Map from keyword to RDM command class code."
  (into {} (map (fn [[code kw]] [kw code]) rdm-command-class->keyword)))

(def firmware-reply-code->status
  "Map from firmware reply code to status keyword."
  {0x00 :block-good, 0x01 :all-good, 0xFF :fail})

(def firmware-reply-status->code
  "Map from status keyword to firmware reply code."
  (into {}
        (map (fn [[code status]] [status code]) firmware-reply-code->status)))

(def firmware-master-type->info
  "Map from firmware master type code to an info map."
  {0x00 {:block-type :firmware-first, :transfer :firmware, :stage :first},
   0x01 {:block-type :firmware-continue, :transfer :firmware, :stage :continue},
   0x02 {:block-type :firmware-last, :transfer :firmware, :stage :last},
   0x03 {:block-type :ubea-first, :transfer :ubea, :stage :first},
   0x04 {:block-type :ubea-continue, :transfer :ubea, :stage :continue},
   0x05 {:block-type :ubea-last, :transfer :ubea, :stage :last}})

(def ^:const esta-man-prototype-id
  "ESTA manufacturer ID reserved for prototyping (0x7FF0–0x7FFF range).
   Per ESTA: 'reserved for prototyping and experimental use while waiting for an assigned ID.'"
  0x7FF0)
