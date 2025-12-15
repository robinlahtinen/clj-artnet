# Design

This document describes the internal architecture of clj-artnet for contributors and users who want to understand how
the library works.

## Architectural overview

clj-artnet implements the **functional core and imperative shell** pattern:

```
┌───────────────────────────────────────────────────────────────────────────┐
│                          PUBLIC API (clj-artnet)                          │
│   start-node! | stop-node! | send-dmx! | send-rdm! | send-sync! | state   │
└─────────────────────────┬─────────────────────────────────────────────────┘
                          │
          ┌───────────────┴───────────────┐
          │                               │
          ▼                               ▼
┌─────────────────────────┐   ┌─────────────────────────────────────────────┐
│   IMPERATIVE SHELL      │   │           FUNCTIONAL CORE                   │
│   (impl/shell/*)        │   │           (impl/protocol/*)                 │
├─────────────────────────┤   ├─────────────────────────────────────────────┤
│ • UDP send/receive      │   │ • Pure state machine (machine.clj)          │
│ • Buffer pools          │   │ • Codec encoder/decoder (codec/*)           │
│ • DatagramChannel       │   │ • Packet specifications (codec/spec.clj)    │
│ • Flow graph wiring     │   │ • Discovery logic (discovery.clj)           │
│ • Callback dispatch     │   │ • DMX sync/merge (dmx_helpers.clj)          │
│ • Lifecycle management  │   │ • Failsafe playback (dmx_helpers.clj)       │
│ • Effect translation    │   │ • Diagnostics (diagnostics.clj)             │
└─────────────────────────┘   │ • Node state (node_state.clj)               │
                              │ • Addressing (addressing.clj)               │
                              │ • Timing (timing.clj)                       │
                              └─────────────────────────────────────────────┘
```

### Why this separation?

**Testability**. The core state machine can be tested without network I/O. Given a state and an event, verify the
resulting state and effects.

**Predictability**. Pure functions always produce the same outputs for the same inputs. No hidden state, no race
conditions in the core logic.

**Debugging**. Effects are explicit data structures. You can log, inspect, and mock every side effect.

**Extensibility**. New effect types can be added without modifying the core. The shell handles effect dispatch.

---

## The state machine

The heart of clj-artnet is a pure state machine in `machine.clj`:

```clojure
(defn step
    "Transitions state based on event.
     Returns {:state new-state :effects [effect-1 effect-2 ...]}"
    [state event]
    (case (:type event)
        :rx-packet (handle-packet state event)
        :tick (handle-tick state event)
        :command (handle-command state event)
        :snapshot (handle-command state (assoc event :command :snapshot))
        (result state)))
```

### Event types

| Type         | Source         | Description             |
|--------------|----------------|-------------------------|
| `:rx-packet` | UDP Receiver   | Incoming Art-Net packet |
| `:tick`      | Failsafe Timer | Periodic heartbeat      |
| `:command`   | User API       | DMX/RDM/Sync commands   |
| `:snapshot`  | State API      | Request state snapshot  |

### Effect types

| Effect                 | Description             |
|------------------------|-------------------------|
| `{:effect :tx-packet}` | Transmit Art-Net packet |
| `{:effect :callback}`  | Invoke user callback    |
| `{:effect :schedule}`  | Schedule delayed action |
| `{:effect :log}`       | Log event               |

### State shape

The state is a map containing:

```clojure
{:node        {...}   ; ArtPollReply configuration
 :network     {...}   ; Bind address, default target
 :callbacks   {...}   ; User callback functions
 :dmx         {...}   ; Per-port-address DMX state
 :sync        {...}   ; Sync buffer manager
 :failsafe    {...}   ; Failsafe timing
 :discovery   {...}   ; Peer tracking, subscribers
 :diagnostics {...}   ; Diagnostic subscribers
 :rdm         {...}   ; RDM state
 :timing      {...}}  ; Timestamps
```

---

## The codec system

### Declarative specifications

Packet formats are defined as data in `codec/spec.clj`:

```clojure
(def art-dmx-spec
    "ArtDmx packet specification - 18-byte header + up to 512-byte payload"
    [{:name :id, :type :fixed-string, :length 8, :value artnet-id}
     {:name :op-code, :type :u16le, :value op-dmx}
     {:name :prot-ver-hi, :type :u8, :value 0}
     {:name :prot-ver-lo, :type :u8, :value 14}
     {:name :sequence, :type :u8}
     {:name :physical, :type :u8}
     {:name :sub-uni, :type :u8}
     {:name :net, :type :u8}
     {:name :length, :type :u16be}])
```

### Compiler

The compiler in `codec/compiler.clj` transforms specifications into optimized functions:

```clojure
(def encode-art-dmx (compile-encoder art-dmx-spec))
(def decode-art-dmx (compile-decoder art-dmx-spec))
```

Generated encoders:

- Validate field values.
- Write bytes in correct order.
- Handle byte order (little-endian for OpCode, big-endian for lengths).

Generated decoders:

- Read bytes from ByteBuffer.
- Return Clojure maps.
- Handle truncated packets gracefully.

### Field types

| Type            | Size | Description                   |
|-----------------|------|-------------------------------|
| `:u8`           | 1    | Unsigned 8-bit integer        |
| `:u16le`        | 2    | Unsigned 16-bit little-endian |
| `:u16be`        | 2    | Unsigned 16-bit big-endian    |
| `:u32le`        | 4    | Unsigned 32-bit little-endian |
| `:fixed-string` | N    | Fixed-length ASCII string     |
| `:bytes`        | N    | Raw byte array                |
| `:ip4`          | 4    | IPv4 address as 4 bytes       |
| `:mac`          | 6    | MAC address as 6 bytes        |

### Zero-copy design

Decoders don't copy payload data. They slice the ByteBuffer:

```clojure
;; Decoder returns a view into the original buffer
{:data (buffer-slice buf offset length)}
```

This avoids allocation for large DMX payloads.

---

## The flow graph

The shell uses `core.async.flow` to wire processes:

```
                                    ┌──────────────────┐
                                    │  UDP Receiver    │
                                    │  (receiver.clj)  │
                                    └────────┬─────────┘
                                             │ {:type :rx :packet {...}}
                                             ▼
┌────────────────┐    {:type :command}   ┌────────────────────────────────┐
│ User Commands  │ ─────────────────────▶│         Logic Process          │
│ (commands.clj) │                       │         (graph.clj)            │
└────────────────┘                       │                                │
                                         │  ┌──────────────────────────┐  │
┌────────────────┐    {:type :tick}      │  │   Protocol State Machine │  │
│ Failsafe Timer │ ─────────────────────▶│  │   (machine.clj)          │  │
└────────────────┘                       │  │                          │  │
                                         │  │   [state, event]         │  │
                                         │  │       ↓                  │  │
                                         │  │   {:state s' :effects e} │  │
                                         │  └──────────────────────────┘  │
                                         └────────────────┬───────────────┘
                                                          │ actions
                                                          ▼
                              ┌───────────────────────────────────────────┐
                              │              Action Router                │
                              └───┬───────────────┬───────────────┬───────┘
                                  │               │               │
                          {:type :send}   {:type :callback} {:type :release}
                                  ▼               ▼               ▼
                           ┌──────────┐   ┌───────────┐   ┌────────────┐
                           │  Sender  │   │ Callbacks │   │ Buffer     │
                           │ (sender) │   │  (user)   │   │ Release    │
                           └──────────┘   └───────────┘   └────────────┘
```

### Process definitions

Each process is a step function with four arities:

```clojure
(defn logic-step
    ([] {:params {} :ins {:recv :cmds :ticks} :outs {:actions}})    ; describe
    ([args] (init-state args))                                      ; init
    ([state transition] (handle-transition state transition))       ; transition
    ([state input msg] (handle-message state input msg)))           ; transform
```

### Workload types

| Workload   | Thread Type     | Use Case              |
|------------|-----------------|-----------------------|
| `:io`      | Virtual Thread  | Network I/O, blocking |
| `:compute` | Fork/Join Pool  | CPU-intensive work    |
| `:mixed`   | Platform Thread | General purpose       |

The receiver and sender use `:io` workload for Virtual Thread execution.

### Backpressure

Channels have bounded buffers. When a channel is full:

- `put!` blocks (in `go` blocks, parks).
- The flow graph propagates backpressure upstream.
- Prevents memory exhaustion under load.

---

## Buffer pool design

### Pre-allocation

At startup, we allocate pools of `ByteBuffer` objects:

```clojure
{:rx-buffer {:count 256 :size 2048}
 :tx-buffer {:count 128 :size 2048}}
```

### Acquire/release cycle

```
┌─────────────┐     acquire      ┌─────────────┐
│ Buffer Pool │ ───────────────▶ │   Buffer    │
│             │ ◀─────────────── │  (in use)   │
└─────────────┘     release      └─────────────┘
```

1. Receiver acquires buffer from pool
2. Buffer passed through flow graph
3. After processing, buffer released back to pool

### Ownership semantics

- Only one owner at a time.
- Receiver owns until it passes to logic.
- Logic owns during processing.
- Sender owns during transmission.
- Release returns ownership to pool.

---

## Discovery subsystem

### ArtPoll handling

When an ArtPoll arrives:

1. Decode the packet.
2. Check if sender wants reply-on-change subscription.
3. Add subscriber if requested.
4. Generate ArtPollReply effect(s).
5. For more than four ports, paginate with BindIndex.

### Subscriber management

```clojure
{:discovery
 {:reply-on-change-subscribers
  #{{:host "192.168.1.100" :port 6454 :added-at 123456789}}
  :reply-on-change-limit  10
  :reply-on-change-policy :prefer-existing}}
```

When limit is reached:

- `:prefer-existing` — reject new subscriber
- `:prefer-latest` — evict oldest subscriber

### BindIndex pagination

For gateways with many ports:

```
Port 0-3:  BindIndex 0
Port 4-7:  BindIndex 1
Port 8-11: BindIndex 2
...
```

Each ArtPollReply contains up to four ports. Multiple replies sent for large gateways.

---

## DMX subsystem

### Sync buffer manager

When sync mode is `:art-sync`:

```clojure
{:sync
 {:mode          :art-sync
  :buffer-ttl-ns 200000000
  :buffers       {port-address {:data        ByteBuffer
                                :received-at timestamp}}}}
```

1. ArtDmx arrives → buffer data, don't output.
2. ArtSync arrives → output all buffered data.
3. Buffer TTL expires → output stale data anyway.

### Failsafe state machine

```
                 ┌─────────────┐
                 │   Active    │
                 │ (receiving) │
                 └──────┬──────┘
                        │ no DMX for idle-timeout
                        ▼
                 ┌─────────────┐
                 │  Failsafe   │
                 │ (outputting)│
                 └──────┬──────┘
                        │ DMX received
                        ▼
                 ┌─────────────┐
                 │   Active    │
                 └─────────────┘
```

Failsafe modes:

- `:hold` — last known values
- `:zero` — all zeros
- `:full` — all 255
- `:scene` — pre-recorded scene

### HTP/LTP merge (design notes)

Art-Net supports merging from multiple controllers:

- **HTP** (Highest Takes Precedence): Per channel, use max value
- **LTP** (Latest Takes Precedence): Per channel, use latest value

Currently tracked per port-address with source IP/timestamp.

---

## RDM subsystem

### Table of devices (ToD)

```clojure
{:rdm
 {:tod          {port-address #{uid1 uid2 uid3}}
  :tod-requests {}}}
```

### ArtTodRequest/ArtTodData flow

1. Controller sends ArtTodRequest.
2. Node responds with ArtTodData listing known UIDs.
3. Controller can then send ArtRdm to specific UIDs.

### RDM command routing

ArtRdm packets contain:

- Port-Address (which output)
- RDM PDU (the actual RDM packet)

The shell extracts and routes to hardware.

---

## Testing strategy

### Unit tests (pure core)

Test state transitions in isolation:

```clojure
(deftest handle-art-poll-test
         (let [state (init-state config)
               event {:type :rx-packet :packet art-poll-packet}
               result (step state event)]
             (is (= 1 (count (:effects result))))
             (is (= :tx-packet (:effect (first (:effects result)))))))
```

### Property-based tests

Fuzz testing for codecs:

```clojure
(defspec art-dmx-round-trip 100
         (prop/for-all [packet (gen-art-dmx-packet)]
                       (= packet (decode-art-dmx (encode-art-dmx packet)))))
```

### Integration tests

Full node lifecycle:

```clojure
(deftest node-lifecycle-test
         (let [node (start-node! config)]
             (try
                 (is (some? (state node)))
                 (finally
                     (stop-node! node)))))
```

---

## Design decisions and trade-offs

This section addresses common questions about why clj-artnet is built the way it is.

### Why not Component or Integrant?

Component and Integrant are excellent libraries for applications with complex dependency graphs. clj-artnet's lifecycle
needs are simpler:

- **One DatagramChannel**
- **Two buffer pools** (receive and transmit)
- **One flow graph**

A stop function with `compare-and-set!` and cleanup closures suffices. Adding Component would introduce:

- Additional dependency.
- Protocol boilerplate for simple resources.
- Complexity disproportionate to the lifecycle needs.

### Why not clojure.spec for validation?

clj-artnet's `codec/spec.clj` contains **data specifications** (vectors of field descriptors), not `clojure.spec`
schemas. We don't use spec for packet validation because:

1. **Hot path performance**: Incoming packets at 44+ Hz per universe cannot afford instrumentation overhead.
2. **Binary protocols are structural**: Packet validity is determined by byte layout, not runtime predicates.
3. **Compile-time validation**: Encoders validate fields during closure generation.

The "spec" in `spec.clj` refers to "specification" in the sense of "packet format specification," not the `clojure.spec`
library.

### Why not macros for codecs?

The codec compiler uses **higher-order functions**, not macros:

```clojure
;; At load time, not macro expansion time
(def decode-artdmx (compile-decoder art-dmx-spec :artdmx))
```

Benefits:

- Debuggable: you can inspect `art-dmx-spec` at the REPL.
- No hidden code generation.
- Composable: specs are just data.

### Why mutable byte arrays internally?

For performance in the hot path:

| Operation      | Approach              | Reason                                      |
|----------------|-----------------------|---------------------------------------------|
| DMX merge      | `aset-byte`, `aclone` | Avoiding per-channel allocation             |
| Buffer pools   | `LinkedBlockingQueue` | Thread-safe, zero-allocation borrow/release |
| Payload access | ByteBuffer `.slice`   | Zero-copy view                              |

The **public interface is immutable**: users receive read-only ByteBuffer views, and the state machine returns effect
data structures. Mutation is confined to the shell layer.

### Why not a simple blocking UDP loop?

A single-threaded blocking loop:

```java
while(running){
    channel.

receive(buffer);

process(buffer);
}
```

This works for simple use cases but lacks:

| Need              | Why core.async.flow is better                            |
|-------------------|----------------------------------------------------------|
| **Backpressure**  | Blocking loops either block indefinitely or drop packets |
| **Timers**        | Failsafe requires concurrent timer ticks                 |
| **User commands** | send-dmx! must not block on packet receive               |
| **Lifecycle**     | pause/resume for REPL development                        |

The flow graph adds ~50 lines of wiring code in `graph.clj` for these capabilities.

### Why direct ByteBuffer operations?

We use Java NIO's `ByteBuffer` directly with type hints:

```clojure
(defn put-u16-le! [^ByteBuffer buf ^long v]
    (.put buf (unchecked-byte (bit-and v 0xFF)))
    (.put buf (unchecked-byte (bit-and (unsigned-bit-shift-right v 8) 0xFF))))
```

Alternatives like `gloss` or `octet` are excellent libraries, but:

- We need precise control over the direct buffer lifecycle.
- Type hints remove reflection.
- Zero dependencies added.

---

## Extension points

### Adding new OpCodes

1. Add specification to `codec/spec.clj`.
2. Add decoder dispatch in `codec/dispatch.clj`.
3. Add handler in `machine.clj`.
4. Add effect processing in `shell/effects.clj`.

### Custom effect handlers

Effects are routed by the `:effect` key. Add new handlers in the shell:

```clojure
(defmethod handle-effect :my-custom-effect
    [effect context]
    (process-my-effect effect))
```

### Alternative transports

The shell abstracts transport. Replace `DatagramChannel` with:

- TCP transport for Art-Net 4 subscriptions.
- WebSocket for browser-based tools.
- Mock transport for testing.

---

## File structure

```
src/clj_artnet/
├── impl/
│   ├── protocol/                 # FUNCTIONAL CORE
│   │   ├── machine.clj           # State machine (903 lines)
│   │   ├── codec/                # Packet codec
│   │   │   ├── compiler.clj      # Spec → functions
│   │   │   ├── spec.clj          # Packet specs
│   │   │   ├── dispatch.clj      # OpCode routing
│   │   │   └── domain/           # Per-packet logic
│   │   ├── addressing.clj        # Port-Address math
│   │   ├── discovery.clj         # Poll/Reply logic
│   │   ├── dmx.clj               # DMX state
│   │   ├── dmx_helpers.clj       # Sync/failsafe
│   │   ├── diagnostics.clj       # DiagData
│   │   ├── effects.clj           # Effect constructors
│   │   └── ...
│   └── shell/                    # IMPERATIVE SHELL
│       ├── graph.clj             # Flow graph
│       ├── receiver.clj          # UDP receive
│       ├── sender.clj            # UDP send
│       ├── buffers.clj           # Buffer pools
│       ├── commands.clj          # Command builders
│       ├── effects.clj           # Effect handlers
│       └── ...
└── clj_artnet.clj                # Public API
```
