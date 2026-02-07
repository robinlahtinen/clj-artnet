# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-02-07

### Added

- **Art-Net conformance test suite.** New `conformance_test.clj` with wire-level protocol verification tests for Art-Net
  Conformance Tester (ACT) compliance. Tests verify OpCode encoding (little-endian), protocol version byte order
  (big-endian), packet lengths, and Status2 capability flags.
- **Improved ArtPoll coverage.** Achieved 100% field and flag coverage for `ArtPoll` packets. Added property-based
  roundtrip tests and specialized flag confirmation tests.
- **MAC address auto-detection.** The node now automatically detects its MAC address from the bound network interface
  when `:node :mac` is not explicitly configured. This eliminates the `00:00:00:00:00:00` placeholder that triggered
  ACT advisories.

### Changed

- **ArtPoll information model.** Refactored `decode-artpoll` to strictly map the specification information model,
  including support for VLC transmission (Bit 4) and suppress-delay (Bit 0). Flag keys now use predicates (e.g.,
  `:target-enabled?`).
- **ESTA manufacturer ID default.** Changed default `:esta-man` from `0x0000` to `0x7FF0` (ESTA prototype ID) to comply
  with ANSI E1.20 Section 5.1. A WARN-level log is emitted on startup if `:esta-man` is not explicitly configured.
- **Non-standard UDP port warning.** Non-standard Art-Net UDP ports now trigger a WARN log to alert users while still
  allowing custom ports for local testing.
- **Sequential RDM data support.** `send-rdm!` `:rdm-packet` parameter now accepts Clojure vectors and sequences in
  addition to `byte-array` and `ByteBuffer`.
- **Concurrency primitives refactor.** Replaced `Thread/sleep` usage in shell processes with `ReentrantLock` +
  `Condition.await` for interruptible, condition-based waiting. This improves interrupt responsiveness and eliminates
  CPU spinning during timed waits.
- **Port-Address 0 validation relaxed.** Port-Address 0 is now allowed with a warning instead of throwing an exception.
  Art-Net 4 deprecates Port-Address 0 for sACN compatibility but does not prohibit it.

### Fixed

- **Bind configuration propagation.** `:bind :host` now correctly propagates to node identity (ArtPollReply IP address).
  Previously, binding to a specific host would still advertise `0.0.0.0`.
- **Bind port propagation.** `:bind :port` now correctly propagates to node identity when `:node :port` is not
  explicitly set.
- **IP address auto-detection.** When binding to `0.0.0.0`, the node now advertises its primary network interface IP
  address instead of the invalid `0.0.0.0` address. Auto-detection prefers Art-Net standard ranges (`2.x.x.x`,
  `10.x.x.x`) per the specification.
- **Sequential DMX data coercion.** `send-dmx!` now correctly accepts Clojure vectors and sequences for the `:data`
  parameter, as documented. Previously, only `byte-array` and `ByteBuffer` were accepted.
- **UDP packet reception reliability.** Fixed a wiring issue in the `core.async.flow` graph where source processes
  (`receiver-proc` and `failsafe-timer-proc`) were using external ports for internal communication. These processes now
  use the idiomatic in-port/transform pattern to ensure reliable message delivery to the logic process.
- **ArtPollReply Status2 RDM ArtAddress bit.** Status2 bit 7 now correctly indicates support for RDM parameter
  configuration via ArtAddress.
- **ArtPollReply Status3 failsafe bit.** Status3 bit 5 now correctly indicates support for programmable failsafe. Per
  Art-Net 4, this bit must be set when the node supports hold/zero/full/scene failsafe modes via ArtAddress commands.
- **Default Port-Address changed to 1.** Encoder defaults now use Port-Address 1 (universe 1) instead of deprecated
  Port-Address 0, per Art-Net 4 Rev DP specification.
- **Documentation accuracy.** Corrected default values for `:idle-timeout-ms` (`6000`) and `:idle-timeout-ns` (
  `6000000000`). Added missing `:stream-id` field to timecode callback. Fixed a callback payload key from `:source` to
  `:sender` across reference.md and user-guide.md.

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

[Unreleased]: https://github.com/robinlahtinen/clj-artnet/compare/v0.2.0...HEAD

[0.2.0]: https://github.com/robinlahtinen/clj-artnet/releases/tag/v0.2.0

[0.1.0]: https://github.com/robinlahtinen/clj-artnet/releases/tag/v0.1.0
