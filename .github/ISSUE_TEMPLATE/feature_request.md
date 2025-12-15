---
name: Feature request
about: Suggest an idea for clj-artnet
title: "[FEATURE] "
labels: enhancement
assignees: ""
---

## Is your feature request related to a problem?

A clear and concise description of what the problem is. For example, "I'm always frustrated when ..."

## Describe the solution you'd like

A clear and concise description of what you want to happen.

**Proposed API (if applicable):**

```clojure
;; Proposed function signature or usage example
(artnet/your-proposed-function {:option "value"})
```

## Art-Net protocol reference

If this feature relates to a specific part of the Art-Net 4 specification:

| Detail            | Value                           |
|-------------------|---------------------------------|
| **Op-Code**       | such as OpPoll, OpDmx, OpRdm    |
| **Packet type**   | such as ArtPoll, ArtDmx, ArtRdm |
| **Specification** | such as Section 8.3, Table 4    |

## Use case

Describe a concrete scenario where this feature would be useful.

- **Who:** such as lighting designer, fixture developer, show controller
- **What:** What they want to accomplish
- **Why:** Why the current functionality doesn't meet this need

## Alternatives considered

A clear and concise description of any alternative solutions or workarounds you've considered.

<details>
<summary>Alternative approaches (if applicable)</summary>

```clojure
;; Example of the current workaround, if any
```

</details>

## Compatibility considerations

- [ ] This feature would be backwards compatible.
- [ ] This feature may require breaking changes.
- [ ] This feature aligns with the Art-Net 4 protocol specification.

## Related issues

- [ ] I have searched for existing issues, and this is not a duplicate.

## Additional context

Add any other context, diagrams, or references about the feature request here.
