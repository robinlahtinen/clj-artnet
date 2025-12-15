[**API**][api] | [**Docs**][docs] | Latest release: [v0.1.0][latest-release] (2025-12-15)

[![Tests][Tests SVG]][Tests URL]
[![Clojars][Clojars SVG]][Clojars URL]

# clj-artnet

### Art-Net 4 protocol implementation for Clojure

[Art-Net](https://art-net.org.uk/) is the entertainment industry's de facto standard for transporting DMX512 lighting
data over Ethernet—powering everything from concert tours to theme parks to architectural installations.

**clj-artnet** is a **pure-Clojure** implementation of Art-Net 4, providing a **high-performance**, data-driven API for
building lighting control applications on the JVM.

**Protocol target:** [Art-Net 4 protocol specification](https://art-net.org.uk/art-net-specification/) V1.4 (Rev 1.4dp,
2025-10-23).

## Why clj-artnet?

- **Pure Clojure.** No native dependencies, runs anywhere the JVM runs.
- **Zero-copy hot path.** Direct ByteBuffer operations, allocates nothing in steady state.
- **Full Art-Net 4 compliance.** All modern features including BindIndex, RDM, failsafe, and sync.
- **Functional core, imperative shell.** Pure protocol logic, testable without I/O.
- **core.async.flow graph.** Backpressure-aware streaming with Virtual Threads.
- **Data-driven.** Packets are plain Clojure maps, not opaque objects.
- **Comprehensive tests.** 320 tests and 1,308 assertions including property-based fuzzing.

## Protocol support

| OpCode        | Direction    | Description                             |
|:--------------|:-------------|:----------------------------------------|
| ArtDmx        | Send/Receive | DMX512 data (512 channels per universe) |
| ArtSync       | Send         | Synchronize outputs across nodes        |
| ArtPoll/Reply | Auto         | Discovery and status announcements      |
| ArtRdm        | Send/Receive | RDM over Art-Net (unicast)              |
| ArtTod*       | Send/Receive | Table of Devices management             |
| ArtTimeCode   | Send/Receive | SMPTE/EBU timecode distribution         |
| ArtAddress    | Send         | Remote node programming                 |
| ArtInput      | Send         | Input enable/disable                    |
| ArtTrigger    | Send/Receive | Remote trigger macros                   |
| ArtCommand    | Send/Receive | Text-based parameter commands           |

See [reference.md][reference] for the full protocol support matrix.

## Quick example

```clojure
(require '[clj-artnet :as artnet])

;; Start a node
(def node (artnet/start-node! {}))

;; Send DMX to Port-Address 1
(artnet/send-dmx! node
                  {:data         (byte-array 512)
                   :port-address 1
                   :target       {:host "192.168.1.100" :port 6454}})

;; Receive DMX via callbacks
(def receiver
    (artnet/start-node!
        {:callbacks {:dmx (fn [{:keys [packet source]}]
                              (println "DMX from" source
                                       "Port-Address:" (:port-address packet)
                                       "channels:" (:length packet)))}}))

;; Stop the node
(artnet/stop-node! node)
```

See [user-guide.md][user-guide] for more examples.

## Things to be aware of

1. **Pooled buffers are recycled.** The `(:data packet)` ByteBuffer is valid only during the callback. Copy with
   `(.get (.duplicate buf) (byte-array len))` if needed.

2. **Java 21+ is required.** clj-artnet uses Virtual Threads via `core.async/io-thread`. Older JVMs are not supported.

3. **Alpha core.async dependency.** We depend on core.async 1.9.829-alpha2 for the flow API. This API is stable in
   practice but marked alpha.

4. **Always specify `:target`.** Art-Net 4 mandates that ArtDmx packets must be unicast. Omitting `:target` will fail.

5. **No sACN bridge.** clj-artnet implements Art-Net only. Bridging to sACN (E1.31) is a separate concern.

6. **No web configuration UI.** The library provides a programmatic API. Web interfaces are application concerns.

7. **Port-Address 0 is deprecated.** Art-Net 4 deprecates Port-Address 0 to enhance sACN compatibility. New
   implementations should start at Port-Address 1.

## Documentation

- [Rationale][rationale]
- [Design][design]
- [User guide][user-guide]
- [API reference][api]

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

Copyright © 2025 Robin Lahtinen.
Distributed under the [MIT License](LICENSE).

---

*Art-Net™ Designed by and Copyright Artistic Licence*

<!-- Links -->

[api]: https://robinlahtinen.github.io/clj-artnet/clj-artnet.html

[docs]: https://robinlahtinen.github.io/clj-artnet/

[latest-release]: https://github.com/robinlahtinen/clj-artnet/releases/tag/v0.1.0

[rationale]: doc/rationale.md

[design]: doc/design.md

[user-guide]: doc/user-guide.md

[reference]: doc/reference.md

[Clojars SVG]: https://img.shields.io/clojars/v/com.github.robinlahtinen/clj-artnet.svg

[Clojars URL]: https://clojars.org/com.github.robinlahtinen/clj-artnet

[Tests SVG]: https://github.com/robinlahtinen/clj-artnet/actions/workflows/tests.yml/badge.svg

[Tests URL]: https://github.com/robinlahtinen/clj-artnet/actions/workflows/tests.yml
