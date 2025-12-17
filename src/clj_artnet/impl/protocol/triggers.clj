;;  Copyright (c) Robin Lahtinen and contributors. All rights reserved.
;;  Licensed under the MIT License. See LICENSE in the project root for license information.

(ns clj-artnet.impl.protocol.triggers
  "ArtTrigger and ArtCommand processing per Art-Net 4 specification.

   Art-Net 4 §ArtTrigger mandates:
   - OEM filtering: Accept trigger if OEM = 0xFFFF or matches node's OEM
   - Key types 0-3: KeyAscii, KeyMacro, KeySoft, KeyShow
   - Key types 4-255: Undefined (pass to vendor callback if OEM != 0xFFFF)
   - Payload (512 bytes): Interpretation depends on OEM/Key

   This module provides pure functions for:
   - Trigger debouncing with configurable rate limiting
   - OEM-based target filtering
   - Key/SubKey interpretation and acknowledgement generation
   - Helper dispatch for registered trigger handlers"
  (:require
    [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def ^:const default-min-interval-ns (long (* 50 1000000)))
(def ^:const history-max-age-multiplier 16)
(def ^:const nanos-per-second 1000000000)

(def ^:private trigger-helper-kinds
  #{:key-macro :key-soft :key-show :key-ascii})

(def ^:private trigger-kind-aliases
  {:macro     :key-macro
   :key-macro :key-macro
   :soft      :key-soft
   :key-soft  :key-soft
   :show      :key-show
   :key-show  :key-show
   :ascii     :key-ascii
   :key-ascii :key-ascii})

(defn canonical-trigger-kind
  [value]
  (let [kw (keyword value)
        normalized (get trigger-kind-aliases kw kw)]
    (when (trigger-helper-kinds normalized) normalized)))

(defn history-key
  "Generate a unique key for tracking trigger history.
   Returns nil if info is nil.
   For vendor triggers: [:vendor oem key sub-key]
   For standard triggers: [kind sub-key]"
  [info]
  (when info
    (if (= :vendor (:kind info))
      [:vendor (bit-and (int (or (:oem info) 0xFFFF)) 0xFFFF)
       (bit-and (int (or (:key info) 0)) 0xFF)
       (bit-and (int (or (:sub-key info) 0)) 0xFF)]
      [(:kind info) (bit-and (int (or (:sub-key info) 0)) 0xFF)])))

(defn allow?
  "Check if a trigger should be allowed based on a debounced interval.
   Returns [next-state allowed?] where:
   - next-state has updated history if allowed
   - allowed? is true if trigger passed rate limiting

   The history is pruned to remove entries older than (interval * max-age-multiplier)."
  [state info now]
  (if (nil? info)
    [state true]
    (let [h-key (history-key info)
          interval (long (max 0
                              (or (get-in state [:triggers :min-interval-ns])
                                  default-min-interval-ns)))
          history (get-in state [:triggers :history] {})
          last-fired (get history h-key)]
      (if (or (zero? interval)
              (nil? last-fired)
              (>= (- now (long last-fired)) interval))
        (let [cutoff (- now
                        (* history-max-age-multiplier
                           (max interval default-min-interval-ns)))
              trimmed
              (into {} (filter (fn [[_ ts]] (>= (long ts) cutoff)) history))
              updated (assoc trimmed h-key now)]
          [(assoc-in state [:triggers :history] updated) true])
        [state false]))))

(defn target?
  "Check if node should accept trigger based on OEM code.
   Per §ArtTrigger: Accept if target OEM = 0xFFFF or matches node's OEM."
  [state packet]
  (let [node-oem (bit-and (int (get-in state [:node :oem] 0xFFFF)) 0xFFFF)
        target-oem (bit-and (int (or (:oem packet) 0xFFFF)) 0xFFFF)]
    (or (= target-oem 0xFFFF) (= target-oem node-oem))))

(defn command-target?
  "Check if the node should accept ArtCommand based on ESTA code.
   Per §ArtCommand: Accept if ESTA = 0xFFFF or matches node's ESTA."
  [state packet]
  (let [node-esta (bit-and (int (get-in state [:node :esta-man] 0)) 0xFFFF)
        target-esta (bit-and (int (or (:esta-man packet) 0xFFFF)) 0xFFFF)]
    (or (= target-esta 0xFFFF)
        (and (pos? node-esta) (= target-esta node-esta)))))

(defn- char-label
  "Format a character code for display.
   Returns the character if printable, or hex format otherwise."
  [code]
  (let [c (char (bit-and (int code) 0xFF))]
    (if (Character/isISOControl c)
      (format "0x%02X" (bit-and (int code) 0xFF))
      (str c))))

(defn interpret-info
  "Interpret ArtTrigger packet into structured trigger info.
   Per Table 7:
   - Key 0: KeyAscii - SubKey is an ASCII character
   - Key 1: KeyMacro - SubKey is a macro number
   - Key 2: KeySoft - SubKey is a soft-key number
   - Key 3: KeyShow - SubKey is a show number
   - Key 4-255: Undefined (treated as vendor if OEM != 0xFFFF)

   Returns a map with :kind, :key, :sub-key, and :ack for diagnostics."
  [packet]
  (let [oem (bit-and (int (or (:oem packet) 0xFFFF)) 0xFFFF)
        key (bit-and (int (or (:key packet) 0)) 0xFF)
        sub-key (bit-and (int (or (:sub-key packet) 0)) 0xFF)
        key-type (:key-type packet)
        general? (= oem 0xFFFF)]
    (if general?
      (case key-type
        :key-ascii {:kind      :ascii
                    :key       key
                    :sub-key   sub-key
                    :character (char-label sub-key)
                    :ack       {:priority 0x10
                                :text     (format "Trigger KeyAscii (%s) processed"
                                                  (char-label sub-key))}}
        :key-macro {:kind    :macro
                    :key     key
                    :sub-key sub-key
                    :ack     {:priority 0x10
                              :text     (format "Trigger KeyMacro %d executed"
                                                sub-key)}}
        :key-soft {:kind    :soft
                   :key     key
                   :sub-key sub-key
                   :ack     {:priority 0x10
                             :text     (format "Trigger KeySoft %d pressed" sub-key)}}
        :key-show {:kind    :show
                   :key     key
                   :sub-key sub-key
                   :ack     {:priority 0x10
                             :text     (format "Trigger KeyShow %d started" sub-key)}}
        {:kind    :unknown
         :key     key
         :sub-key sub-key
         :ack     {:priority 0x80
                   :text     (format "Unsupported ArtTrigger key 0x%02X" key)}})
      ;; Vendor-specific when OEM != 0xFFFF
      {:kind    :vendor
       :key     key
       :sub-key sub-key
       :oem     oem
       :ack     {:priority 0x40
                 :text     (format "Vendor trigger key 0x%02X sub-key 0x%02X forwarded"
                                   key
                                   sub-key)}})))

(defn- trigger-label
  "Format trigger info for diagnostic messages."
  [info]
  (case (:kind info)
    :ascii (format "Trigger KeyAscii (%s)" (char-label (:sub-key info)))
    :macro (format "Trigger KeyMacro %d" (:sub-key info))
    :soft (format "Trigger KeySoft %d" (:sub-key info))
    :show (format "Trigger KeyShow %d" (:sub-key info))
    :vendor (format "Vendor trigger 0x%02X/0x%02X"
                    (or (:key info) 0)
                    (or (:sub-key info) 0))
    "Trigger"))

(defn- nanos->millis [^long nanos] (long (Math/round (double (/ nanos 1e6)))))

(defn rate-limit-ack
  "Generate an acknowledgement for a rate-limited trigger."
  [info interval-ns]
  (let [interval (max 0 (long interval-ns))
        millis (max 1
                    (nanos->millis
                      (if (pos? interval) interval default-min-interval-ns)))]
    {:priority 0x40
     :text     (format "%s ignored (debounced %dms)" (trigger-label info) millis)}))

(defn helper-action
  "Find and invoke a registered helper function for the trigger.
   Returns callback effect map or nil if no helper is registered.
   The returned effect has :helper? true to mark it as a helper callback
   that should be returned in an actions list for the caller to invoke."
  [state info packet sender]
  (let [helpers (get-in state [:triggers :helpers])]
    (when helpers
      (let [kind (:kind info)
            sub (bit-and (int (or (:sub-key info) 0)) 0xFF)
            helper (if (= kind :vendor)
                     (get-in helpers
                             [:vendor (bit-and (int (or (:key info) 0)) 0xFF)
                              sub])
                     (when-let [lookup-kind (canonical-trigger-kind kind)]
                       (get-in helpers [lookup-kind sub])))]
        (when helper
          {:effect  :callback
           :fn      helper
           :helper? true                                    ;; Mark as helper callback to be returned in
           ;; actions
           :payload {:packet packet, :sender sender, :trigger info}})))))

(defn reply-action
  "Generate an ArtTrigger reply if configured.
   Returns [next-state action] where action may be nil."
  [state packet sender]
  (let [{:keys [reply]} (:triggers state)
        {:keys [enabled? target oem-source fixed-oem]} reply]
    (if (and enabled? sender)
      (let [target-map (if (= target :sender)
                         (when (:host sender)
                           {:host (:host sender)
                            :port (int (or (:port sender) 0x1936))})
                         target)]
        (if target-map
          (let [reply-oem
                (case oem-source
                  :request (bit-and (int (or (:oem packet) 0xFFFF)) 0xFFFF)
                  :fixed fixed-oem
                  :node (bit-and (int (or (get-in state [:node :oem]) 0xFFFF))
                                 0xFFFF))
                reply-packet {:op       :arttrigger
                              :oem      reply-oem
                              :key      (:key packet)
                              :key-type (:key-type packet)
                              :sub-key  (:sub-key packet)}]
            [(update-in state [:stats :trigger-replies] (fnil inc 0))
             {:type :send, :target target-map, :packet reply-packet}])
          [state nil]))
      [state nil])))

(def ^:private max-command-value-length
  "Maximum length for ArtCommand label values."
  512)

(defn sanitize-command-value
  "Sanitize an ArtCommand label value by removing null bytes and trimming.
   Returns nil if the result is empty, otherwise a string <= 512 chars."
  [value]
  (let [trimmed (-> (or value "")
                    (str/replace #"\u0000+$" "")
                    str/trim)]
    (when (seq trimmed)
      (let [limit (min (count trimmed) max-command-value-length)]
        (subs trimmed 0 limit)))))

(defn parse-artcommand-text
  "Parse ArtCommand text into a sequence of directive maps.
   ArtCommand text format: 'key=value&key2=value2'

   Recognized commands:
   - SwoutText: Set the SwOut (playback) label
   - SwinText: Set the SwIn (record) label

   Returns vector of maps with:
   - :raw - Original segment text
   - :key - Parsed key name
   - :command - Keyword (:swout-text, :swin-text) or nil if unknown
   - :value - Parsed value string"
  [text]
  (->> (str/split (or text "") #"&")
       (map str/trim)
       (remove empty?)
       (map (fn [segment]
              (let [[raw-key raw-value] (str/split segment #"=" 2)
                    key (some-> raw-key
                                str/trim)
                    value (some-> raw-value
                                  str/trim)
                    lowered (some-> key
                                    str/lower-case)
                    command (case lowered
                              "swouttext" :swout-text
                              "swintext" :swin-text
                              nil)]
                {:raw segment, :key key, :command command, :value value})))
       vec))

(defn apply-artcommand-directives
  "Apply ArtCommand directives to update command labels in the state.

   Takes state and packet, parses directives from packet :text, and applies
   SwoutText/SwinText changes to :command-labels in state.

   Returns map with:
   - :state - Updated state with new :command-labels if changed
   - :directives - Parsed directive vector
   - :acks - Acknowledgement messages for diagnostics
   - :changes - Map of changed labels (nil if none)"
  [state packet]
  (let [directives (parse-artcommand-text (:text packet))
        labels (get state :command-labels)
        label-names {:swout "SwoutText", :swin "SwinText"}
        result
        (reduce
          (fn [{:keys [labels], :as acc} {:keys [command value raw]}]
            (case command
              :swout-text
              (let [label (:swout label-names)
                    sanitized (sanitize-command-value value)
                    current (:swout labels)]
                (cond (nil? sanitized) (update acc
                                               :acks
                                               conj
                                               {:priority 0x80
                                                :text     (str label
                                                               " missing value")})
                      (= current sanitized)
                      (update acc
                              :acks
                              conj
                              {:priority 0x10
                               :text
                               (str label " already set to '" sanitized "'")})
                      :else (-> acc
                                (assoc :labels (assoc labels :swout sanitized))
                                (update :changed assoc :swout sanitized)
                                (update :acks
                                        conj
                                        {:priority 0x10
                                         :text
                                         (str label " applied: " sanitized)}))))
              :swin-text
              (let [label (:swin label-names)
                    sanitized (sanitize-command-value value)
                    current (:swin labels)]
                (cond (nil? sanitized) (update acc
                                               :acks
                                               conj
                                               {:priority 0x80
                                                :text     (str label
                                                               " missing value")})
                      (= current sanitized)
                      (update acc
                              :acks
                              conj
                              {:priority 0x10
                               :text
                               (str label " already set to '" sanitized "'")})
                      :else (-> acc
                                (assoc :labels (assoc labels :swin sanitized))
                                (update :changed assoc :swin sanitized)
                                (update :acks
                                        conj
                                        {:priority 0x10
                                         :text
                                         (str label " applied: " sanitized)}))))
              (update acc
                      :acks
                      conj
                      {:priority 0x80
                       :text     (str "Unsupported ArtCommand: " raw)})))
          {:labels labels, :changed {}, :acks []}
          directives)
        changed (:changed result)
        acks (if (seq directives)
               (:acks result)
               [{:priority 0x40, :text "ArtCommand contained no directives"}])
        state'
        (if (seq changed) (assoc state :command-labels (:labels result)) state)
        changes (when (seq changed) {:command-labels changed})]
    {:state state', :directives directives, :acks acks, :changes changes}))

(defn normalize-config
  [triggers]
  (let [config (or triggers {})
        min-interval (cond (contains? config :min-interval-ns)
                           (long (max 0 (:min-interval-ns config)))
                           (contains? config :min-interval-ms)
                           (long (max 0 (* 1000000 (:min-interval-ms config))))
                           (contains? config :rate-limit-hz)
                           (let [hz (double (or (:rate-limit-hz config) 0.0))]
                             (when (pos? hz) (long (/ nanos-per-second hz))))
                           :else default-min-interval-ns)
        helper-source (or (:macros config) (:helpers config) {})
        normalize-kind canonical-trigger-kind
        normalize-sub-key
        (fn [kind value]
          (let [byte (cond (number? value) value
                           (char? value) (int value)
                           (and (string? value) (seq value)) (int (first value))
                           :else (throw (ex-info "Unsupported trigger sub-key"
                                                 {:kind    kind
                                                  :sub-key value})))]
            (bit-and (int byte) 0xFF)))
        normalize-handler
        (fn [entry ctx]
          (cond (fn? entry) entry
                (and (map? entry) (fn? (:fn entry))) (:fn entry)
                (and (map? entry) (fn? (:callback entry))) (:callback entry)
                :else (throw (ex-info "Trigger macro handler must be a function"
                                      (assoc ctx :handler entry)))))
        helpers
        (if (map? helper-source)
          (reduce-kv
            (fn [acc raw-kind entries]
              (if (= raw-kind :vendor)
                acc
                (let [kind (normalize-kind raw-kind)]
                  (if (and kind (map? entries))
                    (let [normalized
                          (reduce-kv (fn [m raw-sub handler]
                                       (let [sub (normalize-sub-key kind raw-sub)
                                             f (normalize-handler
                                                 handler
                                                 {:kind kind, :sub-key raw-sub})]
                                         (assoc m sub f)))
                                     {}
                                     entries)]
                      (if (seq normalized) (assoc acc kind normalized) acc))
                    acc))))
            {}
            helper-source)
          {})
        vendor-helpers
        (when (map? helper-source)
          (let [entries (:vendor helper-source)]
            (when (map? entries)
              (reduce-kv
                (fn [acc raw-key sub-map]
                  (if (map? sub-map)
                    (let [key-byte (bit-and (int raw-key) 0xFF)
                          normalized
                          (reduce-kv
                            (fn [m raw-sub handler]
                              (let [sub (normalize-sub-key :vendor raw-sub)
                                    f (normalize-handler handler
                                                         {:kind    :vendor
                                                          :key     key-byte
                                                          :sub-key raw-sub})]
                                (assoc m sub f)))
                            {}
                            sub-map)]
                      (if (seq normalized) (assoc acc key-byte normalized) acc))
                    acc))
                {}
                entries))))
        helper-map (cond-> helpers
                           (seq vendor-helpers) (assoc :vendor vendor-helpers))
        reply (or (:reply config) {})
        target-config (:target reply)
        target (cond (and (map? target-config) (:host target-config))
                     {:host (:host target-config)
                      :port (int (or (:port target-config) 0x1936))}
                     (map? target-config) nil
                     (= (keyword target-config) :sender) :sender
                     (nil? target-config) :sender
                     :else :sender)
        oem (:oem reply)
        oem-source (cond (= oem :request) :request
                         (integer? oem) :fixed
                         :else :node)
        fixed-oem (when (integer? oem) (bit-and (int oem) 0xFFFF))
        reply-config {:enabled?   (true? (:enabled? reply))
                      :target     target
                      :oem-source oem-source
                      :fixed-oem  fixed-oem}
        sanitized-interval (long
                             (max 0 (or min-interval default-min-interval-ns)))]
    {:min-interval-ns sanitized-interval
     :helpers         helper-map
     :reply           reply-config}))
