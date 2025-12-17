;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.helpers.triggers-test
  "Unit tests for clj-artnet.impl.protocol.helpers.triggers module.
   Tests ArtTrigger/ArtCommand processing per Art-Net 4 specification."
  (:require
    [clj-artnet.impl.protocol.triggers :as triggers]
    [clojure.test :refer [deftest is run-tests testing]]))

(deftest trigger-oem-filtering
  (testing "OEM=0xFFFF accepts any node"
    (is (true? (triggers/target? {:node {:oem 0x1234}} {:oem 0xFFFF})))
    (is (true? (triggers/target? {:node {:oem 0xFFFF}} {:oem 0xFFFF})))
    (is (true? (triggers/target? {:node {:oem 0}} {:oem 0xFFFF}))))
  (testing "OEM match accepts trigger"
    (is (true? (triggers/target? {:node {:oem 0x1234}} {:oem 0x1234}))))
  (testing "OEM mismatch rejects trigger"
    (is (false? (triggers/target? {:node {:oem 0x1234}} {:oem 0x5678})))
    (is (false? (triggers/target? {:node {:oem 0x1234}} {:oem 0})))))

(deftest command-esta-filtering
  (testing "ESTA=0xFFFF accepts any node"
    (is (true? (triggers/command-target? {:node {:esta-man 0x1234}}
                                         {:esta-man 0xFFFF})))
    (is (true? (triggers/command-target? {:node {:esta-man 0}}
                                         {:esta-man 0xFFFF}))))
  (testing "ESTA match accepts command"
    (is (true? (triggers/command-target? {:node {:esta-man 0x1234}}
                                         {:esta-man 0x1234}))))
  (testing "ESTA mismatch rejects command"
    (is (false? (triggers/command-target? {:node {:esta-man 0x1234}}
                                          {:esta-man 0x5678}))))
  (testing "ESTA=0 node does not match non-broadcast ESTA"
    (is (false? (triggers/command-target? {:node {:esta-man 0}}
                                          {:esta-man 0x1234})))))

(deftest trigger-key-interpretation
  (testing "KeyAscii interpretation"
    (let [info (triggers/interpret-info
                 {:oem 0xFFFF, :key 0, :sub-key 65, :key-type :key-ascii})]
      (is (= :ascii (:kind info)))
      (is (= 0 (:key info)))
      (is (= 65 (:sub-key info)))
      (is (= "A" (:character info)))
      (is (= 0x10 (get-in info [:ack :priority])))))
  (testing "KeyMacro interpretation"
    (let [info (triggers/interpret-info
                 {:oem 0xFFFF, :key 1, :sub-key 5, :key-type :key-macro})]
      (is (= :macro (:kind info)))
      (is (= 1 (:key info)))
      (is (= 5 (:sub-key info)))))
  (testing "KeySoft interpretation"
    (let [info (triggers/interpret-info
                 {:oem 0xFFFF, :key 2, :sub-key 10, :key-type :key-soft})]
      (is (= :soft (:kind info)))))
  (testing "KeyShow interpretation"
    (let [info (triggers/interpret-info
                 {:oem 0xFFFF, :key 3, :sub-key 1, :key-type :key-show})]
      (is (= :show (:kind info)))))
  (testing "Vendor-specific trigger"
    (let [info (triggers/interpret-info {:oem 0x1234, :key 5, :sub-key 1})]
      (is (= :vendor (:kind info)))
      (is (= 0x1234 (:oem info))))))

(deftest trigger-rate-limiting
  (testing "First trigger is allowed"
    (let [state {:triggers {:min-interval-ns 100000000}}
          info {:kind :ascii, :key 0, :sub-key 65}
          [_ allowed?] (triggers/allow? state info 0)]
      (is (true? allowed?))))
  (testing "Trigger within interval is rejected"
    (let [interval 100000000                                ; 100ms
          now 50000000                                      ; 50ms later
          state {:triggers {:min-interval-ns interval
                            :history         {[:ascii 65] 0}}}
          info {:kind :ascii, :key 0, :sub-key 65}
          [_ allowed?] (triggers/allow? state info now)]
      (is (false? allowed?))))
  (testing "Trigger after interval is allowed"
    (let [interval 100000000                                ; 100ms
          now 200000000                                     ; 200ms later
          state {:triggers {:min-interval-ns interval
                            :history         {[:ascii 65] 0}}}
          info {:kind :ascii, :key 0, :sub-key 65}
          [_ allowed?] (triggers/allow? state info now)]
      (is (true? allowed?))))
  (testing "Zero interval disables rate limiting"
    (let [state {:triggers {:min-interval-ns 0, :history {[:ascii 65] 0}}}
          info {:kind :ascii, :key 0, :sub-key 65}
          [_ allowed?] (triggers/allow? state info 1)]
      (is (true? allowed?)))))

(deftest sanitize-command-value
  (testing "Normal value is preserved"
    (is (= "Playback" (triggers/sanitize-command-value "Playback"))))
  (testing "Trailing null bytes are removed"
    (is (= "Test" (triggers/sanitize-command-value "Test\u0000\u0000"))))
  (testing "Whitespace is trimmed"
    (is (= "Value" (triggers/sanitize-command-value "  Value  "))))
  (testing "Empty values return nil"
    (is (nil? (triggers/sanitize-command-value "")))
    (is (nil? (triggers/sanitize-command-value nil)))
    (is (nil? (triggers/sanitize-command-value "   "))))
  (testing "Long values are truncated to 512 chars"
    (let [long-value (apply str (repeat 600 "x"))
          result (triggers/sanitize-command-value long-value)]
      (is (= 512 (count result))))))

(deftest parse-artcommand-text
  (testing "Empty text returns empty vector"
    (is (= [] (triggers/parse-artcommand-text "")))
    (is (= [] (triggers/parse-artcommand-text nil))))
  (testing "SwoutText directive is recognized"
    (let [[directive] (triggers/parse-artcommand-text "SwoutText=Playback")]
      (is (= :swout-text (:command directive)))
      (is (= "SwoutText" (:key directive)))
      (is (= "Playback" (:value directive)))))
  (testing "SwinText directive is recognized"
    (let [[directive] (triggers/parse-artcommand-text "SwinText=Record")]
      (is (= :swin-text (:command directive)))
      (is (= "SwinText" (:key directive)))
      (is (= "Record" (:value directive)))))
  (testing "Multiple directives are parsed"
    (let [directives (triggers/parse-artcommand-text
                       "SwoutText=Playback&SwinText=Record")]
      (is (= 2 (count directives)))
      (is (= :swout-text (:command (first directives))))
      (is (= :swin-text (:command (second directives))))))
  (testing "Unknown directives have nil command"
    (let [[directive] (triggers/parse-artcommand-text "Unknown=Value")]
      (is (nil? (:command directive)))
      (is (= "Unknown" (:key directive)))
      (is (= "Value" (:value directive)))))
  (testing "Case insensitive matching"
    (let [[d1] (triggers/parse-artcommand-text "SWOUTTEXT=Test")
          [d2] (triggers/parse-artcommand-text "swouttext=Test")]
      (is (= :swout-text (:command d1)))
      (is (= :swout-text (:command d2))))))

(deftest apply-artcommand-directives
  (testing "SwoutText updates command-labels"
    (let [state {:command-labels {:swout "Default", :swin "Default"}}
          result (triggers/apply-artcommand-directives state
                                                       {:text
                                                        "SwoutText=Playback"})]
      (is (= "Playback" (get-in result [:state :command-labels :swout])))
      (is (= {:swout "Playback"} (get-in result [:changes :command-labels])))))
  (testing "SwinText updates command-labels"
    (let [state {:command-labels {:swout "Default", :swin "Default"}}
          result (triggers/apply-artcommand-directives state
                                                       {:text
                                                        "SwinText=Record"})]
      (is (= "Record" (get-in result [:state :command-labels :swin])))
      (is (= {:swin "Record"} (get-in result [:changes :command-labels])))))
  (testing "Both directives update together"
    (let [state {:command-labels {:swout "Default", :swin "Default"}}
          result (triggers/apply-artcommand-directives
                   state
                   {:text "SwoutText=Playback&SwinText=Record"})]
      (is (= "Playback" (get-in result [:state :command-labels :swout])))
      (is (= "Record" (get-in result [:state :command-labels :swin])))))
  (testing "Already set value generates acknowledgement"
    (let [state {:command-labels {:swout "Same", :swin "Default"}}
          result
          (triggers/apply-artcommand-directives state {:text "SwoutText=Same"})]
      (is (nil? (:changes result)))
      (is (some #(re-find #"already set" (:text %)) (:acks result)))))
  (testing "Empty text generates no-directives ack"
    (let [state {:command-labels {:swout "Default", :swin "Default"}}
          result (triggers/apply-artcommand-directives state {:text ""})]
      (is (= [] (:directives result)))
      (is (some #(re-find #"no directives" (:text %)) (:acks result)))))
  (testing "Unsupported directive generates error ack"
    (let [state {:command-labels {:swout "Default", :swin "Default"}}
          result (triggers/apply-artcommand-directives state
                                                       {:text "Unknown=Test"})]
      (is (some #(= 0x80 (:priority %)) (:acks result)))))
  (testing "Missing value generates error ack"
    (let [state {:command-labels {:swout "Default", :swin "Default"}}
          result (triggers/apply-artcommand-directives state
                                                       {:text "SwoutText="})]
      (is (some #(re-find #"missing value" (:text %)) (:acks result))))))

(deftest history-key-generation
  (testing "Standard trigger uses kind and sub-key"
    (is (= [:ascii 65] (triggers/history-key {:kind :ascii, :sub-key 65})))
    (is (= [:macro 5] (triggers/history-key {:kind :macro, :sub-key 5}))))
  (testing "Vendor trigger includes OEM and key"
    (is (= [:vendor 0x1234 5 1]
           (triggers/history-key
             {:kind :vendor, :oem 0x1234, :key 5, :sub-key 1}))))
  (testing "Nil info returns nil" (is (nil? (triggers/history-key nil)))))

(comment
  (run-tests 'clj-artnet.impl.protocol.helpers.triggers-test)
  :rcf)
