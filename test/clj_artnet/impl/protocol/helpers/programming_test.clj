;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.helpers.programming-test
  "Tests for Art-Net programming helpers (ArtAddress, ArtInput, ArtIpProg).

   Uses impl.helpers.programming directly since the library is unpublished
   and backward compatibility is not a concern."
  (:require
    [clj-artnet.impl.protocol.programming :as programming]
    [clojure.test :refer [deftest is]]))

(def base-node {:good-input [0 0 0 0], :bind-index 5})

(deftest apply-artinput-respects-target-bind-index
  (let [packet {:disabled [true false false false], :bind-index 9}
        {:keys [node applied-bind-index applied-to-base?]}
        (programming/apply-artinput
          {:node base-node, :packet packet, :target-bind-index 7})]
    (is (= 7 applied-bind-index))
    (is (false? applied-to-base?))
    (is (= base-node node))))

(deftest apply-artipprog-reset-falls-back-to-defaults
  (let [{:keys [network reply]} (programming/apply-artipprog
                                  {:node             {}
                                   :network          {:ip          [3 3 3 3]
                                                      :subnet-mask [255 255 0 0]
                                                      :gateway     [3 3 3 1]
                                                      :port        0x3333
                                                      :dhcp?       true}
                                   :network-defaults {:ip          [2 2 2 2]
                                                      :subnet-mask [255 0 0 0]}
                                   :packet           {:op :artipprog, :command 0x88}})]
    (is (= [2 2 2 2] (:ip network)))
    (is (= [255 0 0 0] (:subnet-mask network)))
    (is (= [0 0 0 0] (:gateway network)))
    (is (= 0x1936 (:port network)))
    (is (false? (:dhcp? network)))
    (is (= [0 0 0 0] (:gateway reply)))
    (is (= 0x1936 (:port reply)))
    (is (false? (:dhcp? reply)))))

(deftest apply-artipprog-dhcp-command-ignores-enable
  (let [{:keys [network reply]} (programming/apply-artipprog
                                  {:node    {}
                                   :network {:ip          [10 10 10 10]
                                             :subnet-mask [255 0 0 0]
                                             :gateway     [10 10 10 1]
                                             :port        0x1234
                                             :dhcp?       false}
                                   :packet  {:op :artipprog, :command 0x40}})]
    (is (true? (:dhcp? network)))
    (is (= [10 10 10 10] (:ip network)))
    (is (= [10 10 10 1] (:gateway network)))
    (is (= 0x1234 (:port network)))
    (is (true? (:dhcp? reply)))))

(deftest apply-artinput-updates-base-when-target-matches
  (let [packet {:disabled [true false false false], :bind-index 3}
        {:keys [node changes applied-bind-index applied-to-base?]}
        (programming/apply-artinput
          {:node base-node, :packet packet, :target-bind-index 5})]
    (is (= 5 applied-bind-index))
    (is applied-to-base?)
    (is (= [programming/good-input-disabled-bit 0 0 0] (:good-input node)))
    (is (= {:good-input [programming/good-input-disabled-bit 0 0 0]} changes))))
