;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.shell.bind-resolution-test
  "Tests for bind configuration resolution logic."
  (:require
    [clj-artnet.impl.shell.lifecycle :as lifecycle]
    [clj-artnet.impl.shell.net :as net]
    [clj-artnet.support.helpers :refer [thrown-with-msg?]]
    [clojure.test :refer [deftest is testing]])
  (:import (clojure.lang ExceptionInfo)))

(deftest resolve-bind-ip-precedence-test
  (testing "Explicit :node :ip takes precedence over :bind :host"
    (let [config {:node {:ip [10 0 0 99]}, :bind {:host "192.168.1.50"}}
          result (lifecycle/resolve-bind config)]
      (is (= [10 0 0 99] (:ip result)))
      (is (= :explicit-node (:ip-source result)))))
  (testing "Non-wildcard :bind :host propagates when :node :ip not set"
    (let [result (lifecycle/resolve-bind {:bind {:host "192.168.1.50"}})]
      (is (= [192 168 1 50] (:ip result)))
      (is (= :explicit-bind (:ip-source result)))))
  (testing "Vector :bind :host also propagates"
    (let [result (lifecycle/resolve-bind {:bind {:host [10 0 0 1]}})]
      (is (= [10 0 0 1] (:ip result)))
      (is (= :explicit-bind (:ip-source result)))))
  (testing "Wildcard 0.0.0.0 triggers auto-detection or fallback"
    (let [result (lifecycle/resolve-bind {:bind {:host "0.0.0.0"}})]
      (is (some? (:ip result)))
      (is (not= [0 0 0 0] (:ip result)))
      (is (contains? #{:auto-detected :fallback} (:ip-source result)))))
  (testing "Nil bind defaults to wildcard behavior"
    (let [result (lifecycle/resolve-bind {})]
      (is (some? (:ip result)))
      (is (not= [0 0 0 0] (:ip result))))))

(deftest resolve-bind-port-precedence-test
  (testing "Explicit :node :port takes precedence over :bind :port"
    (let [result (lifecycle/resolve-bind {:node {:port 6455}
                                          :bind {:port 6456}})]
      (is (= 6455 (:port result)))
      (is (= :explicit-node (:port-source result)))))
  (testing ":bind :port propagates when :node :port not set"
    (let [result (lifecycle/resolve-bind {:bind {:port 6455}})]
      (is (= 6455 (:port result)))
      (is (= :explicit-bind (:port-source result)))
      (is (true? (:non-standard-port? result)))))
  (testing "Default port is 0x1936 (6454)"
    (let [result (lifecycle/resolve-bind {})]
      (is (= 0x1936 (:port result)))
      (is (= :default (:port-source result)))
      (is (false? (:non-standard-port? result)))))
  (testing "Standard port 6454 is not non-standard"
    (let [result (lifecycle/resolve-bind {:bind {:port 6454}})]
      (is (= 6454 (:port result)))
      (is (false? (:non-standard-port? result))))))

(deftest parse-host-test
  (testing "String IP parsing"
    (is (= [192 168 1 50] (net/parse-host "192.168.1.50")))
    (is (= [0 0 0 0] (net/parse-host "0.0.0.0")))
    (is (= [127 0 0 1] (net/parse-host "127.0.0.1")))
    (is (= [255 255 255 255] (net/parse-host "255.255.255.255"))))
  (testing "Vector passthrough"
    (is (= [10 0 0 1] (net/parse-host [10 0 0 1])))
    (is (= [192 168 1 1] (net/parse-host [192 168 1 1]))))
  (testing "nil passthrough" (is (nil? (net/parse-host nil))))
  (testing "Invalid format throws ex-info"
    (is (thrown-with-msg? ExceptionInfo #"Invalid host format"
                          (net/parse-host :invalid)))))

(deftest wildcard?-test
  (testing "nil is wildcard" (is (true? (net/wildcard? nil))))
  (testing "0.0.0.0 string is wildcard" (is (true? (net/wildcard? "0.0.0.0"))))
  (testing "[0 0 0 0] vector is wildcard"
    (is (true? (net/wildcard? [0 0 0 0]))))
  (testing "Non-wildcard addresses"
    (is (false? (net/wildcard? "192.168.1.1")))
    (is (false? (net/wildcard? [10 0 0 1])))
    (is (false? (net/wildcard? "127.0.0.1")))))

(deftest detect-local-ip-test
  (testing "Auto-detection returns valid IPv4 or nil"
    (let [result (net/detect-local-ip)]
      (when result
        (is (vector? result))
        (is (= 4 (count result)))
        (is (every? #(and (>= % 0) (<= % 255)) result))
        ;; Should not be wildcard or loopback
        (is (not= [0 0 0 0] result))
        (is (not= [127 0 0 1] result))))))

(comment
  (require '[clj-artnet.impl.shell.bind-resolution-test] :reload)
  (clojure.test/run-tests 'clj-artnet.impl.shell.bind-resolution-test)
  :rcf)
