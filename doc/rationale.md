# Rationale

*December 2025*

clj-artnet is a pure-Clojure implementation of the Art-Net 4 protocol for DMX512 lighting control over Ethernet.

## The problem

Art-Net is the de facto standard for transporting DMX512 data over IP networks. Professional lighting installations rely
on it for everything from concert tours to architectural lighting to theme parks. Yet existing implementations on the
JVM share common limitations:

**Imperative, stateful designs**. Traditional Art-Net libraries expose mutable state directly to user code. Starting a
node mutates global state; receiving packets mutates buffers in place; callbacks happen on unpredictable threads. This
makes testing difficult, debugging frustrating, and concurrency hazardous.

**Incomplete Art-Net 4 support**. Most libraries implement Art-Net 2 or 3 features and lack modern capabilities:

- BindIndex pagination for gateways with more than four ports
- Proper failsafe playback modes (zero/full/scene)
- RDM over Art-Net with ToD (Table of Devices) support
- Reply-on-change subscriber management
- ArtDataRequest/Reply for URLs and firmware information

**Blocking I/O**. Traditional implementations use one thread per socket, blocking on receive. This doesn't scale for
installations with many universes and conflicts with Clojure's philosophy of leveraging the host platform's concurrency
primitives.

**No pure-Clojure option**. Java's ArtNet4j, Node.js libraries, and Python implementations each have their merits, but
none provide the combination of:

- Immutable data structures for predictable state management
- First-class REPL development for live debugging
- Protocol-level data that is plain Clojure maps
- Seamless integration with core.async for backpressure

## The approach

clj-artnet addresses these problems through deliberate architectural choices.

### Functional core and imperative shell

We strictly separate **pure domain logic** from **side-effectful I/O**:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           PUBLIC API (clj-artnet)                           │
└─────────────────────────┬───────────────────────────────────────────────────┘
                          │
          ┌───────────────┴───────────────┐
          │                               │
          ▼                               ▼
┌─────────────────────────┐   ┌─────────────────────────────────────────────┐
│   IMPERATIVE SHELL      │   │           FUNCTIONAL CORE                   │
│   (impl/shell/*)        │   │           (impl/protocol/*)                 │
├─────────────────────────┤   ├─────────────────────────────────────────────┤
│ • UDP send/receive      │   │ • Pure state machine                        │
│ • Buffer pools          │   │ • Codec encoder/decoder                     │
│ • DatagramChannel       │   │ • Packet specifications                     │
│ • Flow graph wiring     │   │ • Discovery logic                           │
│ • Callback dispatch     │   │ • DMX sync/merge                            │
│ • Lifecycle management  │   │ • Failsafe playback                         │
└─────────────────────────┘   └─────────────────────────────────────────────┘
```

The core is a pure state machine: `(step state event) → {:state state' :effects [...]}`. Given the same state and event,
it always produces the same result. Effects are explicit data structures describing what the shell should do—send a
packet, invoke a callback, schedule a delayed action.

This pattern, popularized by Gary Bernhardt as "Functional Core, Imperative Shell," enables:

- **Comprehensive testing** without network I/O
- **Predictable debugging** since all state transitions are reproducible
- **Explicit side effects** that can be logged, inspected, and mocked

### Data-driven protocol definitions

Art-Net packets are defined as declarative specifications, not imperative code:

```clojure
(def art-dmx-spec
    [{:name :id, :type :fixed-string, :length 8, :value "Art-Net\0"}
     {:name :op-code, :type :u16le, :value 0x5000}
     {:name :prot-ver-hi, :type :u8, :value 0}
     {:name :prot-ver-lo, :type :u8, :value 14}
     {:name :sequence, :type :u8}
     {:name :physical, :type :u8}
     {:name :sub-uni, :type :u8}
     {:name :net, :type :u8}
     {:name :length, :type :u16be}])
```

A compiler transforms these specifications into optimized encoder and decoder functions. This approach:

- Directly mirrors the Art-Net 4 specification document
- Makes packet structure visible and auditable
- Enables property-based testing of codec round-trips
- Simplifies adding new packet types

### Zero-copy ByteBuffer operations

clj-artnet operates directly on `java.nio.ByteBuffer` without intermediate byte array copies. Incoming packets are
decoded in place; outgoing packets are encoded into pre-allocated buffer pools. This minimizes garbage collection
pressure and keeps latency predictable.

### core.async.flow for lifecycle and backpressure

The shell uses core.async.flow to wire together processes (receiver, logic, sender) with explicit backpressure
semantics. When the system can't keep up, channels provide natural throttling rather than unbounded queuing. Flow graphs
also provide:

- Unified lifecycle management (start/stop/pause/resume)
- Error handling and reporting channels
- Process monitoring and introspection

## Prior art

### ArtNet4j (Java)

The most mature JVM option. However:

- Uses blocking I/O with dedicated threads
- Mutable state throughout (nodes, universes, packets)
- Limited Art-Net 4 features (no BindIndex, partial RDM)
- API designed for imperative Java patterns

### Node.js libraries

JavaScript's event loop suits network protocols, and libraries like `artnet` exist. However:

- Single-threaded limits throughput for large installations
- No static typing leads to runtime errors with protocol data
- Not suitable for embedded or high-reliability deployments

### Python libraries

Libraries like `python-artnet` prioritize simplicity. However:

- GIL limits parallelism
- Performance insufficient for time-critical applications
- Ecosystem less suited to long-running services

### Why Clojure?

Clojure is uniquely suited for protocol implementation:

1. **Immutable data**. Protocol state is naturally a value that transitions through messages. Clojure's persistent data
   structures make this explicit without defensive copying.

2. **REPL development**. Art-Net debugging involves sending packets and observing responses. The REPL enables live
   exploration of running nodes, packet inspection, and state manipulation.

3. **Data as API**. Clojure encourages data-first design. Packets are maps, configurations are maps, events are maps.
   Everything is inspectable and transformable.

4. **Host interop**. Java's `DatagramChannel` and `ByteBuffer` are excellent primitives. Clojure lets us use them
   directly with minimal ceremony.

5. **core.async**. Channels provide the right abstraction for network I/O: asynchronous, backpressured, and composable.
   Flow graphs extend this to full process supervision.

## Trade-offs

Every design involves trade-offs. We accept these limitations:

### Java 21+ requirement

clj-artnet requires Java 21 or later for Virtual Threads via core.async's `io-thread`. This is a hard requirement. Older
JVMs are not supported.

**Why accept this?** Virtual Threads eliminate the overhead of dedicated I/O threads while maintaining blocking
semantics in code. The abstraction is worth the version requirement.

### Alpha core.async dependency

We depend on core.async 1.9.829-alpha2 for the flow API. This API is marked alpha and may change.

**Why accept this?** Flow graphs provide exactly the lifecycle and topology management we need. The alpha status
reflects API refinement, not instability. We track upstream closely.

### No built-in sACN bridge

Art-Net and sACN (E1.31) are both DMX-over-IP protocols. Some installations need bridging between them. clj-artnet does
not include an sACN implementation.

**Why accept this?** sACN is a distinct protocol deserving its own library. A bridge would be a separate concern,
composable with clj-artnet but not bundled.

### No web configuration UI

Many commercial Art-Net nodes include web interfaces for configuration. clj-artnet provides only a programmatic API.

**Why accept this?** A web UI is an application concern, not a library concern. clj-artnet provides the primitives;
users build the interfaces appropriate to their deployments.

## Common misconceptions

Based on feedback from the community, here are clarifications about common misunderstandings:

### "This uses immutable data which will thrash the GC"

**Clarification:** The hot path uses **mutable byte arrays** for performance.

| Layer                 | Data Handling                                                     |
|-----------------------|-------------------------------------------------------------------|
| **Shell (hot path)**  | `byte-array` with `aset-byte`, `aclone`, direct ByteBuffer access |
| **Core (pure logic)** | Immutable Clojure maps for state, effects as data                 |
| **User interface**    | Read-only ByteBuffer views                                        |

The functional core returns immutable effect descriptions; the shell mutates buffers. This is the functional core and
imperative shell pattern in action.

### "core.async adds unnecessary complexity"

**Clarification:** We use a **three-node flow graph**, not a complex channel topology.

The entire graph is:

```
[receiver] → [logic] → [sender]
      ↑
[failsafe timer]
```

This provides backpressure, Virtual Thread integration, and unified lifecycle, all of which are required for production
UDP handling. Without core.async.flow, you'd write equivalent complexity manually (or accept dropped packets and blocked
threads).

### "Verbose discovery/poll implementation"

**Clarification:** Art-Net 4 discovery is more complex than Art-Net 2 broadcast.

Modern ArtPoll includes:

- Targeted Port-Address ranges (5.3.1 in spec)
- Reply-on-change subscriber management
- Diagnostic priority filtering
- BindIndex pagination for more than four ports

The implementation matches the specification's complexity.

### "Using spec validation on every packet is slow"

**Clarification:** We do not use runtime schema validation in the hot path.

Packet decoding is handled by **compiled closures** generated from declarative specs at load time. As a result, there is
no per-packet validation overhead. The decoder reads bytes directly from the ByteBuffer.

### "lifecycle.clj reinvents Integrant or Component"

**Clarification:** The lifecycle is intentionally minimal.

clj-artnet manages:

- One DatagramChannel
- Two buffer pools
- One flow graph

This requires ~30 lines of lifecycle code, not a full component system. Integrant and Component are excellent for
complex applications; they would be overhead here.

---

## Central ideas

To summarize, clj-artnet is built on these principles:

- **Separation of concerns**: Pure protocol logic is isolated from I/O
- **Data over code**: Packets, events, effects, and state are all plain data
- **Explicit effects**: Side effects are returned as data structures, not performed implicitly
- **Zero-copy performance**: Direct ByteBuffer operations minimize allocation
- **Modern Clojure**: Leverages Java 21+ features and cutting-edge core.async
- **Full Art-Net 4**: Comprehensive protocol support, not a subset

## Credits

I'd like to thank the Clojure community for the inspiration and the tooling that made this library possible. Special
thanks to Artistic Licence for creating and maintaining the Art-Net protocol specification.

I hope clj-artnet helps you build simpler and more robust lighting control systems.

Robin Lahtinen

---

*Art-Net™ Designed by and Copyright Artistic Licence*
