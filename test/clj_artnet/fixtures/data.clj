;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.fixtures.data
  "Packet fixture data for Art-Net protocol testing.

   Provides reference data for protocol-compliant packets as pure Clojure maps.
   These fixtures serve as the canonical test data for roundtrip and validation tests.

   Design Philosophy:
   - Fixtures are declarative data maps, not code that constructs data
   - All fixtures are Art-Net 4 protocol compliant
   - Port addresses are computed using the standard formula

   Migrated from: artpollreply_fixtures.clj, arttimecode_fixtures.clj"
  (:require
    [clj-artnet.impl.protocol.codec.domain.common :as common]))

(def artpollreply-page
  "Reference ArtPollReply page with typical node configuration."
  {:ip                      [172 16 1 50]
   :bind-ip                 [10 0 1 200]
   :port                    0x1936
   :version-hi              1
   :version-lo              99
   :net-switch              0x12
   :sub-switch              0x03
   :oem                     0x4AF1
   :ubea-version            2
   :status1                 0xC5
   :esta-man                0x1234
   :short-name              "Fixture Node"
   :long-name               "clj-artnet fixture node"
   :node-report             "#0001 [0001] Fixture Ready"
   :num-ports               3
   :port-types              [0xC0 0x80 0x90 0x00]
   :good-input              [0x11 0x22 0x33 0x00]
   :good-output-a           [0x55 0x66 0x77 0x00]
   :good-output-b           [0xBA 0xBB 0xFC 0xF0]
   :sw-in                   [0x00 0x01 0x02 0x00]
   :sw-out                  [0x10 0x11 0x12 0x00]
   :acn-priority            0xF0
   :sw-macro                0x0A
   :sw-remote               0x0B
   :style                   0xD0
   :mac                     [0x00 0x0D 0xB8 0x01 0x02 0x03]
   :bind-index              3
   :status2                 0x5A
   :status3                 0x0F
   :default-responder       [0x01 0x23 0x45 0x67 0x89 0xAB]
   :user-hi                 0x9A
   :user-lo                 0xBC
   :refresh-rate            0x0456
   :background-queue-policy 0x07
   :port-addresses          [(common/compose-port-address 0x12 0x03 0)
                             (common/compose-port-address 0x12 0x03 1)
                             (common/compose-port-address 0x12 0x03 2)]})

(def artpollreply-port-descriptors
  "Port descriptor array for multi-port node configuration."
  [{:port-address  (common/compose-port-address 0x12 0x03 0)
    :port-type     0xC0
    :good-input    0x11
    :good-output-a 0x55
    :good-output-b 0xBA
    :sw-in         0x00
    :sw-out        0x10}
   {:port-address  (common/compose-port-address 0x12 0x03 1)
    :port-type     0x80
    :good-input    0x22
    :good-output-a 0x66
    :good-output-b 0xBB
    :sw-in         0x01
    :sw-out        0x11}
   {:port-address  (common/compose-port-address 0x12 0x03 2)
    :port-type     0x90
    :good-input    0x33
    :good-output-a 0x77
    :good-output-b 0xFC
    :sw-in         0x02
    :sw-out        0x12}
   {:port-address  (common/compose-port-address 0x13 0x05 0)
    :port-type     0xC0
    :good-input    0x44
    :good-output-a 0x88
    :good-output-b 0x31
    :sw-in         0x03
    :sw-out        0x20}
   {:port-address  (common/compose-port-address 0x13 0x05 1)
    :port-type     0x40
    :good-input    0x55
    :good-output-a 0x99
    :good-output-b 0x32
    :sw-in         0x04
    :sw-out        0x21}])

(def artpollreply-second-page
  "Second ArtPollReply page for multi-bind-index testing."
  (-> artpollreply-page
      (assoc :bind-index 4
             :net-switch 0x13
             :sub-switch 0x05
             :num-ports 2
             :port-addresses [(common/compose-port-address 0x13 0x05 0)
                              (common/compose-port-address 0x13 0x05 1)]
             :port-types [0xC0 0x40 0x00 0x00]
             :good-input [0x44 0x55 0x00 0x00]
             :good-output-a [0x88 0x99 0x00 0x00]
             :good-output-b [0x31 0x32 0xF0 0xF0]
             :sw-in [0x03 0x04 0x00 0x00]
             :sw-out [0x20 0x21 0x00 0x00])))

(def artpollreply-pages
  "Collection of ArtPollReply pages for multi-page testing."
  [artpollreply-page artpollreply-second-page])

(def artpollreply-node-config
  "Node configuration derived from ArtPollReply fixtures."
  (-> artpollreply-page
      (assoc :ports artpollreply-port-descriptors)
      (assoc :port-addresses nil)
      (assoc :num-ports 0)))

(def arttimecode-default-frame
  "Default ArtTimeCode frame with typical timecode values."
  {:stream-id 0x42, :frames 24, :seconds 50, :minutes 15, :hours 11, :type 3})

(def arttimecode-nonzero-unused-frame
  "ArtTimeCode frame with non-zero unused/proto fields for edge case testing."
  (assoc arttimecode-default-frame :unused 0x55 :proto 0x1201))

(def artpoll-default-packet
  "Default ArtPoll packet with typical configuration."
  {:protocol-version 14
   :suppress-delay?  false
   :reply-on-change? true
   :diag-request?    false
   :diag-unicast?    false
   :target-enabled?  false
   :diag-priority    0
   :target-top       0x7FFF
   :target-bottom    0
   :esta-man         0
   :oem              0})

(def artpoll-targeted-packet
  "ArtPoll packet with targeted mode enabled for a specific universe range."
  (assoc artpoll-default-packet
    :target-enabled? true
    :target-bottom 0x1000
    :target-top 0x1FFF))

(comment
  ;; Example: Access fixture data
  (:short-name artpollreply-page)
  ;; => "Fixture Node". Example: Port addresses
  (:port-addresses artpollreply-page)
  ;; => [4656 4657 4658]
  :rcf)
