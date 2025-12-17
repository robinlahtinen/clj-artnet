# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **ESTA manufacturer ID default.** Changed default `:esta-man` from `0x0000` to `0x7FF0` (ESTA prototype ID) to comply
  with ANSI E1.20 Section 5.1. A WARN-level log is emitted on startup if `:esta-man` is not explicitly configured.
- **Non-standard UDP port warning.** Non-standard Art-Net UDP ports now trigger a WARN log to alert users while still
  allowing custom ports for local testing.

### Fixed

- **Bind configuration propagation.** `:bind :host` now correctly propagates to node identity (ArtPollReply IP address).
  Previously, binding to a specific host would still advertise `0.0.0.0`.
- **Bind port propagation.** `:bind :port` now correctly propagates to node identity when `:node :port` is not
  explicitly set.
- **IP address auto-detection.** When binding to `0.0.0.0`, the node now advertises its primary network interface IP
  address instead of the invalid `0.0.0.0` address. Auto-detection prefers Art-Net standard ranges (`2.x.x.x`,
  `10.x.x.x`) per the specification.

## [0.1.0] - 2025-12-15

Initial release of clj-artnet — a pure-Clojure implementation of Art-Net 4.

### Added

#### Core protocol support

- **ArtDmx** — Send and receive DMX512 data (512 channels per universe).
- **ArtSync** — Synchronize outputs across multiple nodes for tear-free rendering.
- **ArtPoll/ArtPollReply** — Automatic node discovery and status announcements.
- **ArtRdm** — RDM (Remote Device Management) messaging over Art-Net.
- **ArtRdmSub** — Compressed RDM sub-device data.
- **ArtTimeCode** — SMPTE/EBU timecode distribution.
- **ArtTrigger** — Remote trigger macros.
- **ArtCommand** — Text-based parameter commands.
- **ArtAddress** — Remote node programming.
- **ArtIpProg** — IP address programming.
- **ArtDiagData** — Diagnostic messaging.
- **ArtDataRequest/ArtDataReply** — Data queries (URLs, firmware info).
- **ArtFirmwareMaster** — Firmware upload support.

#### Architecture

- **Functional core, imperative shell** — Pure protocol logic separated from I/O.
- **core.async.flow graph** — Backpressure-aware streaming architecture.
- **Zero-copy packet handling** — Direct ByteBuffer operations minimize allocation.
- **Declarative codec specifications** — Data-driven packet encoding/decoding.
- **Event-sourced state machine** — Predictable, testable protocol handling.

#### Public API

- `start-node!` — Start an Art-Net node with configuration options.
- `stop-node!` — Stop node and release all resources.
- `send-dmx!` — Unicast ArtDmx packets with full addressing support.
- `send-rdm!` — Unicast ArtRdm packets for RDM communication.
- `send-sync!` — Broadcast ArtSync for synchronized output.
- `send-diagnostic!` — Send ArtDiagData to diagnostic subscribers.
- `state` — Get current node runtime state snapshot.
- `apply-state!` — Update node configuration at runtime.

#### Features

- **15-bit Port-Address** — Full Art-Net 3/4 universe addressing (0–32,767).
- **BindIndex pagination** — Multi-port gateways (>4 ports) without multi-homing.
- **Failsafe playback** — Configurable idle timeout with zero/full/scene modes.
- **HTP/LTP (Highest Takes Precedence/Latest Takes Precedence) merge** — Automatic merging from multiple source
  controllers.
- **Reply-on-change** — Subscriber management with configurable eviction policies.
- **Buffer pools** — Pre-allocated RX/TX buffer pools for zero-allocation steady state.
- **Virtual Thread I/O** — Java 21+ Virtual Threads via `core.async/io-thread`.

#### Configuration

- Extensive node configuration (short name, long name, ports, style).
- Network binding options (host, port).
- Callback system for DMX, RDM, timecode, and discovery events.
- Sync mode configuration (immediate, art-sync, buffer-ttl).
- Failsafe configuration (timeout, tick interval, playback mode).
- Buffer pool sizing (RX/TX count and size).

#### Testing

- Property-based tests with `test.check` for codec verification.
- Integration tests for node lifecycle and packet handling.
- 320 tests with 1,308 assertions.

### Dependencies

- Clojure 1.12.4+
- Java 21+ (Virtual Threads, DatagramChannel)
- org.clojure/core.async 1.9.829-alpha2
- com.taoensso/trove 1.1.0

[Unreleased]: https://github.com/robinlahtinen/clj-artnet/compare/v0.1.0...HEAD

[0.1.0]: https://github.com/robinlahtinen/clj-artnet/releases/tag/v0.1.0
