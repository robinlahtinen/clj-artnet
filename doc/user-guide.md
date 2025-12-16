# User guide

This guide introduces clj-artnet, a pure-Clojure Art-Net 4 implementation. We'll progress from installation through
basic usage to advanced patterns.

## Installation

### deps.edn (recommended)

```clojure
{:deps {com.github.robinlahtinen/clj-artnet {:mvn/version "0.1.0"}}}
```

### Leiningen

```clojure
[com.github.robinlahtinen/clj-artnet "0.1.0"]
```

### Git dependency

```clojure
{:deps {com.github.robinlahtinen/clj-artnet
        {:git/url "https://github.com/robinlahtinen/clj-artnet.git"
         :git/sha "COMMIT_SHA"}}}
```

### Requirements

| Dependency | Version | Purpose                          |
|------------|---------|----------------------------------|
| Clojure    | 1.12.4+ | Core language                    |
| Java       | 21+     | Virtual Threads, DatagramChannel |

### Key features

**Protocol support**: ArtDmx, ArtSync, ArtPoll/ArtPollReply, ArtRdm, ArtRdmSub, ArtTimeCode, ArtTrigger, ArtCommand,
ArtAddress, ArtIpProg, ArtDiagData, ArtDataRequest/Reply, and ArtFirmwareMaster.

**Implementation highlights**:

- Zero-copy packet handling via direct ByteBuffer operations.
- Functional core and imperative Shell architecture.
- `core.async.flow` graph for backpressure-aware streaming.
- 15-bit Port-Address support (32,768 universes).
- BindIndex pagination for multi-port gateways.
- Failsafe playback with configurable modes.
- HTP/LTP merge from multiple controllers.
- Reply-on-change subscriber management.

## Quick start

### Minimal controller

Start a node, send DMX, stop:

```clojure
(require '[clj-artnet :as artnet])

;; Start a node
(def node (artnet/start-node!))

;; Send DMX to universe 1
(artnet/send-dmx! node
                  {:data         [255 128 64]
                   :port-address 1
                   :target       {:host "192.168.1.100" :port 6454}})

;; Stop the node
(artnet/stop-node! node)
```

### Minimal receiver

Start a node that receives DMX:

```clojure
(def node
    (artnet/start-node!
        {:callbacks {:dmx (fn [{:keys [packet source]}]
                              (println "DMX from" source
                                       "Port-Address:" (:port-address packet)
                                       "channels:" (:length packet)))}}))
```

### Rainbow effect

This example creates a smooth, continuous rainbow on an RGB fixture. It demonstrates how little code is needed to build
something visually stunning:

```clojure
(require '[clj-artnet :as artnet])

;; Convert a hue value (0.0–1.0) to RGB bytes [red green blue].
;; This creates the rainbow: red → yellow → green → cyan → blue → magenta → red.
(defn hue->rgb [hue]
    (let [sector (int (* hue 6))              ; Which color sector (0–5)?
          fraction (- (* hue 6) sector)         ; Position within the sector
          rising (int (* 255 fraction))       ; Fades up from 0 to 255
          falling (int (* 255 (- 1 fraction)))] ; Fades down from 255 to 0
        (case (mod sector 6)
            0 [255 rising 0]      ; Red → Yellow
            1 [falling 255 0]     ; Yellow → Green
            2 [0 255 rising]      ; Green → Cyan
            3 [0 falling 255]     ; Cyan → Blue
            4 [rising 0 255]      ; Blue → Magenta
            5 [255 0 falling])))  ; Magenta → Red

;; Start a clj-artnet node
(def node (artnet/start-node!))

;; Define where to send the DMX data
(def target {:host "192.168.1.100" :port 6454})

;; Control flag — set to false to stop the animation
(def running (atom true))

;; Run the rainbow animation in a background thread
(future
    (loop [hue 0.0]
        (when @running
            ;; Send RGB values to DMX channels 1, 2, 3 on universe 1
            (artnet/send-dmx! node {:data         (hue->rgb hue)
                                    :port-address 1
                                    :target       target})
            ;; Wait 25 milliseconds (~40 frames per second)
            (Thread/sleep 25)
            ;; Continue with the next hue (wraps around at 1.0)
            (recur (mod (+ hue 0.01) 1.0)))))

;; To stop: (reset! running false)
;; To clean up: (artnet/stop-node! node)
```

The animation runs in the background, leaving your REPL free. Stop it anytime with `(reset! running false)`.

## Core concepts

### What is Art-Net?

Art-Net transports DMX512 lighting control data over Ethernet. It operates on UDP port 6454 and supports:

- **32,768 universes** of DMX data (512 channels each).
- **Node discovery** via polling.
- **RDM** for bidirectional device communication.
- **Synchronization** for tear-free output.
- **Timecode** distribution (SMPTE/EBU).

### Port-Address

A **Port-Address** is a 15-bit identifier (0–32,767) that uniquely addresses a DMX universe on the network:

```
Port-Address = (Net × 256) + (Sub-Net × 16) + Universe
```

| Component | Range | Bits               |
|-----------|-------|--------------------|
| Net       | 0–127 | 7 bits (bits 14–8) |
| Sub-Net   | 0–15  | 4 bits (bits 7–4)  |
| Universe  | 0–15  | 4 bits (bits 3–0)  |

Use the utility functions to convert:

```clojure
(artnet/compose-port-address 1 2 3)
;; => 291

(artnet/split-port-address 291)
;; => {:net 1, :sub-net 2, :universe 3}
```

### Node types

| Style            | Description                |
|------------------|----------------------------|
| `:st-node`       | DMX to/from Art-Net device |
| `:st-controller` | Lighting console           |
| `:st-media`      | Media server               |
| `:st-route`      | Network routing device     |
| `:st-visual`     | Visualizer                 |

## Sending DMX

### Basic send

The `send-dmx!` function unicasts an ArtDmx packet:

```clojure
(artnet/send-dmx! node
                  {:data         (byte-array 512)       ; DMX channel data
                   :port-address 1                       ; Target universe
                   :target       {:host "192.168.1.100"  ; Destination IP
                                  :port 6454}})          ; Art-Net port
```

### Data formats

The `:data` parameter accepts multiple formats:

```clojure
;; Byte array (most efficient)
{:data (byte-array [255 128 64 32])}

;; Clojure sequence (auto-converted)
{:data [255 128 64 32]}

;; ByteBuffer (zero-copy)
{:data some-byte-buffer}
```

### Addressing options

Either use `:port-address` or the individual components:

```clojure
;; Using port-address
{:port-address 291}

;; Using components (equivalent to port-address 291)
{:net 1 :sub-net 2 :universe 3}
```

### Sequence numbers

Art-Net uses sequence numbers (0–255) to detect out-of-order packets:

```clojure
;; Auto-managed (recommended)
(artnet/send-dmx! node {:data data :port-address 1 :target target})

;; Manual sequence
(artnet/send-dmx! node {:data data :port-address 1 :target target :sequence 42})

;; Disable sequence checking (sequence = 0)
(artnet/send-dmx! node {:data data :port-address 1 :target target :sequence 0})
```

> **Note**: Art-Net 4 requires ArtDmx to be unicast. Broadcast is not permitted.

## Receiving DMX

### The DMX callback

Register a callback when starting the node:

```clojure
(defn dmx-handler [{:keys [packet source node]}]
    (let [{:keys [data length port-address sequence]} packet]
        ;; data is a read-only ByteBuffer
        (process-dmx port-address data length)))

(def node
    (artnet/start-node!
        {:callbacks {:dmx dmx-handler}}))
```

### Callback payload

| Key       | Type | Description                    |
|-----------|------|--------------------------------|
| `:packet` | map  | Decoded packet data            |
| `:source` | map  | Sender address `{:host :port}` |
| `:node`   | map  | Current node configuration     |

### Packet keys

| Key             | Type       | Description                |
|-----------------|------------|----------------------------|
| `:data`         | ByteBuffer | Channel data (read-only)   |
| `:length`       | int        | Number of channels (1–512) |
| `:port-address` | int        | 15-bit universe address    |
| `:sequence`     | int        | Sequence number (0–255)    |
| `:failsafe?`    | boolean    | True if failsafe playback  |

### Extracting bytes

The `:data` ByteBuffer is read-only. To extract bytes:

```clojure
(defn extract-bytes [{:keys [packet]}]
    (let [buf (:data packet)
          len (:length packet)
          bytes (byte-array len)]
        (.get (.duplicate buf) bytes)  ; Use duplicate to preserve position
        bytes))
```

## Synchronization

### The problem

When updating large LED arrays across multiple universes, unsynchronized output causes visual tearing—some universes
update before others.

### The solution: ArtSync

1. Send DMX to all universes
2. Send ArtSync to trigger simultaneous output

```clojure
;; Send DMX to 4 universes
(doseq [u (range 4)]
    (artnet/send-dmx! node
                      {:data     (get-universe-data u)
                       :universe u
                       :target   target}))

;; Trigger synchronized output
(artnet/send-sync! node)
```

### Sync mode configuration

Configure how receivers handle sync:

```clojure
(artnet/start-node!
    {:sync {:mode          :art-sync    ; Wait for ArtSync before output
            :buffer-ttl-ms 200}})      ; Timeout if no sync arrives

;; Modes:
;; :immediate - Output DMX as it arrives (default)
;; :art-sync  - Buffer until ArtSync
```

### The sync callback

The `:sync` callback fires when ArtSync is received:

```clojure
{:callbacks {:sync (fn [{:keys [sender timestamp]}]
                       (println "Sync from" sender "at" timestamp))}}
```

## Failsafe playback

### What is failsafe?

If DMX data stops arriving (controller crash, network failure), fixtures should respond safely. Art-Net nodes implement
**failsafe playback**:

| Mode     | Behavior                              |
|----------|---------------------------------------|
| `:hold`  | Output last received values (default) |
| `:zero`  | Output all zeros (blackout)           |
| `:full`  | Output all 255 (full intensity)       |
| `:scene` | Output a pre-recorded scene           |

### Configuration

```clojure
(artnet/start-node!
    {:failsafe {:enabled?         true
                :idle-timeout-ms  1000   ; Trigger after 1 second of no data
                :tick-interval-ms 100}}) ; Check every 100ms
```

### Failsafe in Callbacks

When failsafe engages, the DMX callback is invoked with `:failsafe? true`:

```clojure
(defn dmx-handler [{:keys [packet]}]
    (if (:failsafe? packet)
        (println "FAILSAFE:" (:failsafe-mode packet))
        (process-dmx packet)))
```

## Discovery

### How discovery works

1. Controllers broadcast **ArtPoll** to discover nodes
2. Nodes respond with **ArtPollReply** describing their capabilities
3. Controllers maintain a list of discovered nodes

clj-artnet handles this automatically. Your node will respond to polls and announce itself.

### Node configuration

Configure how your node identifies itself:

```clojure
(artnet/start-node!
    {:node {:short-name "LED Panel"                  ; 17 chars max
            :long-name  "4-Universe LED Matrix Panel" ; 63 chars max
            :style      :st-node
            :ports      [{:direction :output :universe 1}
                         {:direction :output :universe 2}
                         {:direction :output :universe 3}
                         {:direction :output :universe 4}]}})
```

### Reply-on-change

Controllers can subscribe to state changes. When your node's configuration changes, it automatically notifies
subscribers:

```clojure
;; Update node name at runtime
(artnet/apply-state! node
                     {:node {:short-name "New Name"}})
;; Subscribers are automatically notified
```

## RDM over Art-Net

### What is RDM?

RDM (Remote Device Management) enables bidirectional communication with compatible fixtures. You can query device
information, set addresses, and configure parameters.

### Sending RDM

```clojure
(artnet/send-rdm! node
                  {:rdm-packet   (build-rdm-request)  ; Your RDM PDU
                   :port-address 1
                   :target       {:host "192.168.1.100" :port 6454}})
```

### RDM callback

```clojure
{:callbacks {:rdm (fn [{:keys [packet source]}]
                      (let [{:keys [rdm-packet port-address]} packet]
                          (process-rdm-response rdm-packet)))}}
```

> **Note**: Art-Net 4 requires RDM packets to be unicast.

## Diagnostics

### Sending diagnostics

Nodes can send text diagnostic messages to subscribed controllers:

```clojure
(artnet/send-diagnostic! node
                         {:text     "DMX output short detected on port 1"
                          :priority :dp-high})
```

### Priority levels

| Keyword        | Description       |
|----------------|-------------------|
| `:dp-low`      | Low priority      |
| `:dp-med`      | Medium priority   |
| `:dp-high`     | High priority     |
| `:dp-critical` | Critical priority |
| `:dp-volatile` | Temporary message |

## Triggers and timecode

### ArtTrigger

Remote trigger macros for show control:

```clojure
{:callbacks {:trigger (fn [{:keys [packet]}]
                          (let [{:keys [key sub-key]} packet]
                              (case key
                                  0 (handle-ascii-trigger sub-key)
                                  1 (handle-macro-trigger sub-key)
                                  2 (handle-soft-trigger sub-key)
                                  3 (handle-show-trigger sub-key))))}}
```

### ArtTimeCode

SMPTE (Society of Motion Picture and Television Engineers) and EBU (European Broadcasting Union) timecode for
synchronization:

```clojure
{:callbacks {:timecode (fn [{:keys [packet]}]
                           (let [{:keys [hours minutes seconds frames type]} packet]
                               (update-timecode! hours minutes seconds frames)))}}
```

| Type     | Standard             |
|----------|----------------------|
| `:film`  | 24 fps               |
| `:ebu`   | 25 fps               |
| `:df`    | 29.97 fps drop-frame |
| `:smpte` | 30 fps               |

## State management

### Reading state

Get a snapshot of the node's current state:

```clojure
(artnet/state node)
;; => {:node {...} :network {...}}

;; Request specific keys
(artnet/state node {:keys [:node :peers :stats]})
```

### Reading diagnostics

```clojure
(artnet/diagnostics node)
;; => {:diagnostics {:subscribers [...] :broadcast? false}}
```

### Updating state

Apply runtime configuration changes:

```clojure
(artnet/apply-state! node
                     {:node      {:short-name "Updated Name"}
                      :callbacks {:dmx new-dmx-handler}})
```

## Lifecycle management

### Starting

```clojure
(def node (artnet/start-node! config))
```

The returned control map contains:

| Key            | Type    | Description           |
|----------------|---------|-----------------------|
| `:stop!`       | fn      | Stop the node         |
| `:pause!`      | fn      | Pause processing      |
| `:resume!`     | fn      | Resume processing     |
| `:flow`        | Flow    | core.async.flow graph |
| `:report-chan` | channel | Flow reports          |
| `:error-chan`  | channel | Flow errors           |

### Stopping

```clojure
(artnet/stop-node! node)
;; Or use the control map directly:
((:stop! node))
```

### Pause/Resume

Useful for debugging or maintenance:

```clojure
((:pause! node))   ; Pause all processing
;; ... debug ...
((:resume! node))  ; Resume
```

## Common patterns

### Controller pattern

A lighting console that sends DMX:

```clojure
(def controller
    (artnet/start-node!
        {:node      {:short-name "Console"
                     :style      :st-controller}
         :callbacks {:poll-reply (fn [{:keys [packet]}]
                                     (record-discovered-node! packet))}}))

;; Send to fixtures
(defn update-fixtures! [universe-data]
    (doseq [[universe data] universe-data]
        (artnet/send-dmx! controller
                          {:data     data
                           :universe universe
                           :target   (get-target universe)})))
```

### Fixture pattern

A DMX receiver that outputs to hardware:

```clojure
(def fixture
    (artnet/start-node!
        {:node      {:short-name "LED Wash"
                     :long-name  "12-Channel LED Wash Light"
                     :style      :st-node
                     :ports      [{:direction :input :universe 5}]}
         :callbacks {:dmx (fn [{:keys [packet]}]
                              (when (= 5 (:port-address packet))
                                  (output-to-hardware! (:data packet))))}}))
```

### Gateway pattern

A device that converts Art-Net to physical DMX:

```clojure
(def gateway
    (artnet/start-node!
        {:node      {:short-name "4-Port Gateway"
                     :ports      [{:direction :output :universe 1}
                                  {:direction :output :universe 2}
                                  {:direction :output :universe 3}
                                  {:direction :output :universe 4}]}
         :sync      {:mode :art-sync :buffer-ttl-ms 200}
         :callbacks {:dmx  (fn [{:keys [packet]}]
                               (route-to-dmx-output! packet))
                     :sync (fn [_]
                               (trigger-dmx-outputs!))}}))
```

## Gotchas and common pitfalls

### Forgetting the target

Art-Net 4 requires ArtDmx packets to be unicast. Always specify `:target`:

```clojure
;; WRONG: Missing :target
(send-dmx! node {:data (byte-array 512) :universe 1})

;; CORRECT: Always specify :target
(send-dmx! node {:data     (byte-array 512)
                 :universe 1
                 :target   {:host "192.168.1.100" :port 6454}})
```

### Blocking on state in callback

The node state passed to callbacks is a snapshot—don't call `state` from inside a callback:

```clojure
;; WRONG: Blocking call inside callback
(defn bad-dmx-handler [{:keys [node]}]
    (let [current-state (state node)]  ;; This may deadlock!
        ...))

;; CORRECT: Use the node state passed in the callback payload
(defn good-dmx-handler [{:keys [node packet]}]
    (let [short-name (:short-name node)]
        ...))
```

### ByteBuffer position

DMX `:data` arrives as a read-only ByteBuffer. Use `.duplicate` to preserve position when extracting bytes:

```clojure
(defn extract-bytes [{:keys [packet]}]
    (let [buf (:data packet)
          len (:length packet)
          bytes (byte-array len)]
        (.get (.duplicate buf) bytes)
        bytes))
```

### Sync mode timing

When using `:art-sync` mode, ensure ArtSync arrives within `buffer-ttl-ms` of ArtDmx packets:

```clojure
;; Send DMX to all universes, then sync
(doseq [u (range 4)]
    (send-dmx! node {:data     (get-universe-data u)
                     :universe u
                     :target   target}))
(send-sync! node)  ; Must arrive within 200ms of DMX
```

### Failsafe scene mode

Failsafe scene mode uses the last successfully received DMX data. Modes are:

| Mode     | Behavior                              |
|----------|---------------------------------------|
| `:hold`  | Output last received values (default) |
| `:zero`  | Output all zeros                      |
| `:full`  | Output all 255                        |
| `:scene` | Output recorded scene                 |

Scene recording is triggered via ArtAddress commands from controllers.

## Next steps

- **Reference**: Detailed data shape specifications.
- **Design**: Internal architecture and extension points.
- **API documentation**: Full function signatures and docstrings.
