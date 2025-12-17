# Reference

This document specifies all data shapes used by clj-artnet. It is intended as a precise technical reference, not a
tutorial.

## Configuration

clj-artnet uses two related but distinct address configurations:

| Configuration | Purpose                             | Example                                |
|---------------|-------------------------------------|----------------------------------------|
| `:node :ip`   | Identity advertised in ArtPollReply | `{:node {:ip [192 168 1 50]}}`         |
| `:bind :host` | Local socket binding address        | `{:bind {:host "0.0.0.0" :port 6454}}` |

Most users only need `:bind` configuration. The `:node :ip` is auto-detected and only needs explicit
configuration when:

- You have multiple network interfaces, and auto-detection selects the wrong one.
- You want to advertise a specific IP address for NAT or proxy scenarios.

### Node configuration

The `:node` key in the configuration map corresponds to ArtPollReply fields:

| Key           | Type                        | Default                       | Description                 |
|---------------|-----------------------------|-------------------------------|-----------------------------|
| `:short-name` | string                      | `"clj-artnet"`                | 17-character node name      |
| `:long-name`  | string                      | `"clj-artnet Art-Net 4 Node"` | 63-character description    |
| `:ip`         | `[int int int int]`         | Auto-detected                 | IPv4 address (ArtPollReply) |
| `:mac`        | `[int int int int int int]` | `[0 0 0 0 0 0]`               | MAC address                 |
| `:ports`      | vector                      | `[]`                          | Port definitions            |
| `:style`      | keyword                     | `:st-node`                    | Node type                   |
| `:oem`        | int                         | `0xFFFF`                      | OEM code                    |
| `:esta-man`   | int                         | `0x0000`                      | ESTA manufacturer code      |
| `:version-hi` | int                         | `0`                           | Firmware version high byte  |
| `:version-lo` | int                         | `1`                           | Firmware version low byte   |
| `:status1`    | int                         | Auto                          | Status register 1           |
| `:status2`    | int                         | Auto                          | Status register 2           |
| `:status3`    | int                         | Auto                          | Status register 3           |

### Port configuration

Each entry in `:ports`:

| Key          | Type    | Default   | Range               | Description      |
|--------------|---------|-----------|---------------------|------------------|
| `:direction` | keyword | `:output` | `:input`, `:output` | Port direction   |
| `:universe`  | int     | Index     | 0–15                | Universe address |
| `:type`      | keyword | `:dmx512` | —                   | Protocol type    |

### Sync configuration

The `:sync` key:

| Key              | Type    | Default      | Description                   |
|------------------|---------|--------------|-------------------------------|
| `:mode`          | keyword | `:immediate` | `:immediate` or `:art-sync`   |
| `:buffer-ttl-ms` | int     | `200`        | Buffer expiry in milliseconds |
| `:buffer-ttl-ns` | long    | `200000000`  | Buffer expiry in nanoseconds  |

### Failsafe configuration

The `:failsafe` key:

| Key                 | Type    | Default      | Description                     |
|---------------------|---------|--------------|---------------------------------|
| `:enabled?`         | boolean | `true`       | Enable failsafe detection       |
| `:idle-timeout-ms`  | int     | `1000`       | Timeout before failsafe engages |
| `:idle-timeout-ns`  | long    | `1000000000` | Timeout in nanoseconds          |
| `:tick-interval-ms` | int     | `100`        | Failsafe check interval         |

### Network configuration

The `:bind` key controls the local socket address:

| Key     | Type   | Default     | Description                 |
|---------|--------|-------------|-----------------------------|
| `:host` | string | `"0.0.0.0"` | Local IP address to bind to |
| `:port` | int    | `6454`      | Local UDP port to bind      |

> **Note**: The `:bind` key controls where the node listens for packets. The library automatically resolves the
> advertised identity from bind configuration unless explicitly overridden.

#### IP address resolution

The node's advertised IP address (in ArtPollReply) is resolved with this precedence:

| Priority | Source        | Example                          | Use Case                   |
|----------|---------------|----------------------------------|----------------------------|
| 1        | `:node :ip`   | `{:node {:ip [10 0 0 99]}}`      | Explicit identity override |
| 2        | `:bind :host` | `{:bind {:host "192.168.1.50"}}` | Non-wildcard binding       |
| 3        | Auto-detected | NetworkInterface enumeration     | Wildcard bind (`0.0.0.0`)  |
| 4        | Fallback      | `[2 0 0 1]`                      | Detection failed (WARNING) |

Auto-detection prefers Art-Net standard IP ranges (`2.x.x.x`, `10.x.x.x`) per the specification.

#### UDP port resolution

The node's advertised UDP port is resolved with this precedence:

| Priority | Source        | Example                | Use Case          |
|----------|---------------|------------------------|-------------------|
| 1        | `:node :port` | `{:node {:port 6455}}` | Explicit override |
| 2        | `:bind :port` | `{:bind {:port 6455}}` | Non-standard port |
| 3        | Default       | `6454` (0x1936)        | Standard Art-Net  |

> **Warning**: Non-standard UDP ports trigger a WARN log. Standard Art-Net UDP port is 6454 (0x1936).
> Custom ports enable local testing with multiple node instances on the same host.

### Buffer pool configuration

| Key           | Type | Default                   | Description          |
|---------------|------|---------------------------|----------------------|
| `:rx-buffer`  | map  | `{:count 256 :size 2048}` | Receive buffer pool  |
| `:tx-buffer`  | map  | `{:count 128 :size 2048}` | Transmit buffer pool |
| `:max-packet` | int  | `2048`                    | Maximum packet size  |

Buffer pool map:

| Key      | Type | Description                  |
|----------|------|------------------------------|
| `:count` | int  | Number of buffers in pool    |
| `:size`  | int  | Size of each buffer in bytes |

---

## Callback payloads

### DMX callback

```clojure
{:packet {:data          ByteBuffer  ; Read-only channel data
          :length        int         ; Number of channels (1–512)
          :port-address  int         ; 15-bit Port-Address
          :sequence      int         ; Sequence number (0–255)
          :physical      int         ; Physical port number
          :failsafe?     boolean     ; True if failsafe playback
          :failsafe-mode keyword}   ; :zero, :full, :scene, :hold
 :source {:host string              ; Sender IP address
          :port int}                ; Sender port
 :node   {...}}                     ; Current node configuration
```

### Sync callback

```clojure
{:sender    {:host string :port int}   ; Sender address
 :timestamp long                       ; System nanosecond timestamp
 :node      {...}}                     ; Current node configuration
```

### RDM callback

```clojure
{:packet {:rdm-packet   ByteBuffer   ; RDM PDU data
          :port-address int          ; Port-Address
          :net          int          ; Network (0–127)
          :command      keyword}     ; RDM command class
 :source {:host string :port int}
 :node   {...}}
```

### Trigger callback

```clojure
{:packet {:key     int          ; Trigger key (0=ASCII, 1=Macro, 2=Soft, 3=Show)
          :sub-key int          ; Trigger sub-key
          :payload ByteBuffer   ; Trigger payload data
          :oem     int}         ; OEM code
 :source {:host string :port int}
 :node   {...}}
```

### Command callback

```clojure
{:packet {:text     string   ; Command text (ASCII)
          :esta-man int      ; ESTA manufacturer code
          :length   int}     ; Command length
 :source {:host string :port int}
 :node   {...}}
```

### Timecode callback

```clojure
{:packet {:hours   int      ; Hours (0–23)
          :minutes int      ; Minutes (0–59)
          :seconds int      ; Seconds (0–59)
          :frames  int      ; Frames
          :type    keyword} ; :film, :ebu, :df, :smpte
 :source {:host string :port int}
 :node   {...}}
```

---

## Runtime state

### State snapshot keys

The `state` function accepts `:keys` to select which state sections to return:

| Key         | Description                              |
|-------------|------------------------------------------|
| `:node`     | Node configuration (ArtPollReply fields) |
| `:network`  | Network binding information              |
| `:peers`    | Discovered nodes                         |
| `:stats`    | Statistics (packets sent/received)       |
| `:dmx`      | DMX state per port-address               |
| `:sync`     | Sync buffer state                        |
| `:failsafe` | Failsafe timing state                    |

### Diagnostics snapshot

```clojure
{:diagnostics {:subscribers  [{:host string :priority keyword}]
               :broadcast?   boolean
               :last-sent-at long}}
```

---

## Protocol constants

### OpCodes

| OpCode   | Name             | Direction |
|----------|------------------|-----------|
| `0x2000` | OpPoll           | RX/TX     |
| `0x2100` | OpPollReply      | RX/TX     |
| `0x2300` | OpDiagData       | RX/TX     |
| `0x2400` | OpCommand        | RX/TX     |
| `0x2700` | OpDataRequest    | RX        |
| `0x2800` | OpDataReply      | TX        |
| `0x5000` | OpDmx            | RX/TX     |
| `0x5100` | OpNzs            | RX        |
| `0x5200` | OpSync           | RX/TX     |
| `0x6000` | OpAddress        | RX        |
| `0x7000` | OpInput          | RX        |
| `0x8000` | OpTodRequest     | RX        |
| `0x8100` | OpTodData        | TX        |
| `0x8200` | OpTodControl     | RX        |
| `0x8300` | OpRdm            | RX/TX     |
| `0x8400` | OpRdmSub         | RX/TX     |
| `0x9700` | OpTimeCode       | RX        |
| `0x9900` | OpTrigger        | RX        |
| `0xF200` | OpFirmwareMaster | RX        |
| `0xF300` | OpFirmwareReply  | TX        |
| `0xF800` | OpIpProg         | RX        |
| `0xF900` | OpIpProgReply    | TX        |

### Style codes

| Keyword          | Value  | Description                |
|------------------|--------|----------------------------|
| `:st-node`       | `0x00` | DMX to/from Art-Net device |
| `:st-controller` | `0x01` | Lighting console           |
| `:st-media`      | `0x02` | Media server               |
| `:st-route`      | `0x03` | Network routing device     |
| `:st-backup`     | `0x04` | Backup device              |
| `:st-config`     | `0x05` | Configuration tool         |
| `:st-visual`     | `0x06` | Visualizer                 |

### Priority levels

| Keyword        | Value  | Description          |
|----------------|--------|----------------------|
| `:dp-low`      | `0x10` | Low priority         |
| `:dp-med`      | `0x40` | Medium priority      |
| `:dp-high`     | `0x80` | High priority        |
| `:dp-critical` | `0xE0` | Critical priority    |
| `:dp-volatile` | `0xF0` | Volatile (temporary) |

### Timecode types

| Keyword  | Description          |
|----------|----------------------|
| `:film`  | 24 fps               |
| `:ebu`   | 25 fps               |
| `:df`    | 29.97 fps drop-frame |
| `:smpte` | 30 fps               |

---

## Protocol support matrix

### Implemented OpCodes

| OpCode   | Name             | Direction | Status       |
|----------|------------------|-----------|--------------|
| `0x2000` | OpPoll           | RX/TX     | ✅ Full       |
| `0x2100` | OpPollReply      | RX/TX     | ✅ Full       |
| `0x2300` | OpDiagData       | RX/TX     | ✅ Full       |
| `0x2400` | OpCommand        | RX/TX     | ✅ Full       |
| `0x2700` | OpDataRequest    | RX        | ✅ Full       |
| `0x2800` | OpDataReply      | TX        | ✅ Full       |
| `0x5000` | OpDmx            | RX/TX     | ✅ Full       |
| `0x5100` | OpNzs            | RX        | ✅ Full       |
| `0x5200` | OpSync           | RX/TX     | ✅ Full       |
| `0x6000` | OpAddress        | RX        | ✅ Full       |
| `0x7000` | OpInput          | RX        | ✅ Full       |
| `0x8000` | OpTodRequest     | RX        | ✅ Full       |
| `0x8100` | OpTodData        | TX        | ✅ Full       |
| `0x8200` | OpTodControl     | RX        | ✅ Full       |
| `0x8300` | OpRdm            | RX/TX     | ✅ Full       |
| `0x8400` | OpRdmSub         | RX/TX     | ✅ Full       |
| `0x9700` | OpTimeCode       | RX        | ✅ Full       |
| `0x9900` | OpTrigger        | RX        | ✅ Full       |
| `0xF200` | OpFirmwareMaster | RX        | ✅ Codec only |
| `0xF300` | OpFirmwareReply  | TX        | ✅ Codec only |
| `0xF800` | OpIpProg         | RX        | ✅ Full       |
| `0xF900` | OpIpProgReply    | TX        | ✅ Full       |

### Not implemented (deprecated)

| OpCode   | Name        | Reason                  |
|----------|-------------|-------------------------|
| `0xF000` | OpMacMaster | Deprecated in Art-Net 4 |
| `0xF100` | OpMacSlave  | Deprecated in Art-Net 4 |

---

## Edge cases and limits

### Packet size limits

| Packet       | Minimum           | Maximum                  |
|--------------|-------------------|--------------------------|
| ArtPoll      | 14 bytes          | 14 bytes                 |
| ArtPollReply | 207 bytes         | 239 bytes                |
| ArtDmx       | 18 bytes (header) | 530 bytes (header + 512) |
| ArtRdm       | 24 bytes (header) | varies                   |

### Port-address range

- **Minimum**: 0 (deprecated in Art-Net 4)
- **Maximum**: 32,767
- **Recommended start**: 1 (for sACN compatibility)

### Sequence number handling

| Value   | Meaning                    |
|---------|----------------------------|
| `0`     | Sequence checking disabled |
| `1–255` | Active sequence number     |

Receivers discard packets with sequence numbers lower than the last received (accounting for wraparound from 255 to 1).

### Reply-on-change limits

```clojure
{:discovery {:reply-on-change-limit  10
             :reply-on-change-policy :prefer-existing}}
```

| Policy             | Behavior                                     |
|--------------------|----------------------------------------------|
| `:prefer-existing` | Evict newest subscribers when limit exceeded |
| `:prefer-latest`   | Evict oldest subscribers when limit exceeded |

### BindIndex pagination

For gateways with more than four ports, responses are paginated using BindIndex (0–255). Each ArtPollReply contains up
to four ports.

### Data length constraints

| Constraint  | Value       | Reason                    |
|-------------|-------------|---------------------------|
| Minimum DMX | 2 bytes     | Art-Net specification     |
| Maximum DMX | 512 bytes   | DMX512 universe size      |
| DMX Padding | Even length | Per Art-Net specification |

---

## Error states

### Timeout exceptions

```clojure
;; ExceptionInfo with :type :timeout
{:type       :timeout
 :timeout-ms 1000
 :operation  :state-snapshot}
```

### Network binding failures

```clojure
;; ExceptionInfo with :type :bind-failed
{:type  :bind-failed
 :host  "0.0.0.0"
 :port  6454
 :cause IOException}
```

### Invalid configuration

```clojure
;; ExceptionInfo with :type :invalid-config
{:type    :invalid-config
 :key     :ports
 :value   "invalid"
 :message "Ports must be a vector"}
```

---

## Internal data shapes

### Effect types

Effects are data structures returned by the state machine:

```clojure
;; Transmit packet
{:effect :tx-packet
 :packet ByteBuffer
 :target {:host string :port int}}

;; Invoke callback
{:effect   :callback
 :callback keyword      ; :dmx, :sync, :rdm, etc.
 :payload  map}

;; Schedule delayed action
{:effect   :schedule
 :action   keyword
 :delay-ms int
 :target   {:host string :port int}}

;; Log event
{:effect  :log
 :level   keyword        ; :info, :warn, :error
 :message string}
```

### Event types

Events are inputs to the state machine:

```clojure
;; Received packet
{:type   :rx-packet
 :packet map           ; Decoded packet
 :source {:host string :port int}
 :buffer ByteBuffer}

;; Timer tick
{:type      :tick
 :timestamp long}      ; System.nanoTime

;; User command
{:type    :command
 :command keyword      ; :send-dmx, :send-rdm, :send-sync, etc.
 :data    map}         ; Command-specific data

;; State snapshot request
{:type :snapshot
 :keys vector}         ; Keys to include
```
