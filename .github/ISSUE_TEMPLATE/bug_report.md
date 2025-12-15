---
name: Bug report
about: Report a bug in clj-artnet
title: "[BUG] "
labels: bug
assignees: ""
---

## Describe the bug

A clear and concise description of what the bug is.

## To reproduce

**Minimal code example:**

```clojure
(require '[clj-artnet :as artnet])

;; Your code here
```

**Steps to reproduce the behavior:**

1. Start the node with `...`.
2. Send or receive `...`.
3. See error.

## Expected behavior

A clear and concise description of what you expected to happen.

## Actual behavior

What actually happened, including any error messages or stack traces.

<details>
<summary>Stack trace (if applicable)</summary>

```
Paste full stack trace here
```

</details>

## Environment

| Component            | Version                                  |
|----------------------|------------------------------------------|
| **clj-artnet**       | e.g., 1.0.0-RC1                          |
| **Clojure**          | e.g., 1.12.4                             |
| **Java**             | e.g., OpenJDK 21.0.2                     |
| **Operating system** | e.g., Ubuntu 24.04, Windows 11, macOS 14 |
| **core.async**       | e.g., 1.9.829-alpha2                     |

## Art-Net configuration

**Node configuration (if relevant):**

```clojure
{:node {:short-name "..."
        :long-name  "..."
        :ports      [...]}
 :bind {:host "..." :port 6454}
 ;; Other relevant config
 }
```

**Network setup:**

- [ ] Single node
- [ ] Multiple nodes
- [ ] Physical Art-Net hardware involved

| Detail                  | Value                       |
|-------------------------|-----------------------------|
| **Number of universes** | e.g., four                  |
| **DMX512 refresh rate** | e.g., 40 Hz                 |
| **Sync mode**           | `:immediate` or `:art-sync` |
| **Failsafe enabled**    | Yes or No                   |

## Packet details (if applicable)

If this bug involves specific packet handling, provide the following:

- **Packet type:** e.g., ArtDmx, ArtRdm, ArtPoll
- **Port-Address:** e.g., 0, 291
- **Packet capture** (if available): Attach Wireshark .pcap file or hex dump

<details>
<summary>Packet hex dump</summary>

```
41 72 74 2D 4E 65 74 00 ...
```

</details>

## Related issues

- [ ] I have searched existing issues and this is not a duplicate.

## Additional context

Add any other context about the problem here.
