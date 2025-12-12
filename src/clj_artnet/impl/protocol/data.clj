;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.data
  "Helpers for ArtDataRequest/Reply configuration.

   Provides pure functions for normalizing data configuration, utilized by
   `impl.protocol.lifecycle`. Extracted to resolve dependency cycles."
  (:require [clj-artnet.impl.protocol.codec.constants :as const])
  (:import (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(defn- byte-array-from-seq
  [coll]
  (byte-array (map #(unchecked-byte (int %)) coll)))

(defn- ascii-text? [^String s] (every? #(<= 0 (int %) 0x7F) s))

(defn- enforce-text-length
  [^String text context]
  (when-not (ascii-text? text)
    (throw (ex-info "ArtDataReply text must be ASCII"
                    {:context context, :text text})))
  (let [^bytes bytes (.getBytes text StandardCharsets/US_ASCII)
        total-length (inc (alength bytes))]
    (when (> total-length const/artdatareply-max-bytes)
      (throw (ex-info "ArtDataReply text exceeds maximum length"
                      {:context context,
                       :length  total-length,
                       :max     const/artdatareply-max-bytes}))))
  text)

(defn- payload-byte-length
  [payload]
  (cond (bytes? payload) (alength ^bytes payload)
        (instance? ByteBuffer payload) (.remaining (.duplicate ^ByteBuffer
                                                               payload))
        :else (throw (ex-info "Unsupported ArtDataReply payload container"
                              {:type (type payload)}))))

(defn- enforce-payload-length
  [payload context]
  (let [length (payload-byte-length payload)]
    (when (> length const/artdatareply-max-bytes)
      (throw (ex-info "ArtDataReply payload exceeds maximum length"
                      {:context context,
                       :length  length,
                       :max     const/artdatareply-max-bytes})))
    payload))

(defn- normalize-data-response-value
  [key value]
  (let [context {:key key}]
    (cond (nil? value) {:text ""}
          (string? value) {:text (enforce-text-length value context)}
          (bytes? value) {:data (enforce-payload-length value context)}
          (instance? ByteBuffer value) {:data (enforce-payload-length value
                                                                      context)}
          (sequential? value) (let [data (byte-array-from-seq value)]
                                {:data (enforce-payload-length data context)})
          (map? value)
          (let [allowed (select-keys value [:text :data])
                sanitized (if (seq allowed) allowed {:text ""})
                text (:text sanitized)
                raw-data (:data sanitized)
                normalized-data (cond (sequential? raw-data)
                                      (byte-array-from-seq raw-data)
                                      :else raw-data)
                sanitized-text (when text (enforce-text-length text context))
                sanitized-data (when normalized-data
                                 (enforce-payload-length normalized-data
                                                         context))]
            (-> sanitized
                (cond-> sanitized-text (assoc :text sanitized-text))
                (cond-> sanitized-data (assoc :data sanitized-data))))
          :else (throw (ex-info "Unsupported ArtDataReply response value"
                                {:value value, :context context})))))

(defn- resolve-dataresponse-code
  [key]
  (cond (keyword? key) (let [code (get const/datarequest-keyword->code key)]
                         (if (some? code)
                           code
                           (throw (ex-info "Unknown ArtDataRequest keyword"
                                           {:key key}))))
        (integer? key) (bit-and (int key) 0xFFFF)
        :else (throw (ex-info "Unsupported ArtDataRequest response key"
                              {:key key}))))

(defn normalize-config
  "Normalizes data configuration for ArtDataRequest responses.

   Accepts:
   - Map with :responses containing response definitions
   - Map of response definitions directly

   Response values:
   - String -> :text
   - byte array/ByteBuffer -> :data
   - Sequential -> byte array :data
   - Map -> {:text ... :data ...}

   Returns {:responses {:by-type {...} :by-code {...}}}."
  [data]
  (let [responses-source (cond (and (map? data) (map? (:responses data)))
                               (:responses data)
                               (map? data) data
                               :else {})
        normalized (reduce-kv (fn [acc k v]
                                (let [code (resolve-dataresponse-code k)
                                      type (when (keyword? k) k)
                                      value (normalize-data-response-value k v)]
                                  (cond-> acc
                                          type (assoc-in [:by-type type] value)
                                          (nil? type) (assoc-in [:by-code code]
                                                                value))))
                              {:by-type {}, :by-code {}}
                              responses-source)
        has-responses (or (seq (:by-type normalized))
                          (seq (:by-code normalized)))
        normalized' (cond-> normalized
                            (and has-responses
                                 (not (contains? (:by-code normalized) 0x0000)))
                            (update :by-code #(assoc % 0 {:text ""})))
        final-responses
        (if has-responses normalized' {:by-type {}, :by-code {}})]
    {:responses final-responses}))

(comment
  (require '[clj-artnet.impl.protocol.data :as data] :reload)
  ;; Normalize empty data config
  (data/normalize-config {})
  ;; => {:responses {:by-type {}, :by-code {}}}
  ;; Normalize config with specific handlers
  (data/normalize-config {:responses {:by-type {:artpollreply "Poll Reply"}}})
  ;; => {:responses {:by-type {:artpollreply {:text "Poll Reply"}},
  ;; :by-code {0 {:text ""}}}}
  :rcf)
