# TermVR Design Specifications (`SITEMAP.md`)

This directory stores system and feature design specifications structured under the Elephant-Goldfish Model (`EGM`). Each document separates high-altitude product and system architecture (`FeatureRange`) from zero-decision engineering specifications (`DetailedRange`).

---

## 1. Document Registry (Reference Module)

Read these documents in order before modifying module build files, JNI headers, guest protocol frames, or Android manifest components. Every cross-module contract change updates its owning specification first.

| ID | Document | Status | Altitude Coverage | Owning Modules |
| :--- | :--- | :--- | :--- | :--- |
| `0001` | [`0001-termvr-system-design.md`](0001-termvr-system-design.md) | Approved Baseline (`M0`) | `FeatureRange` (`Sections 1-9`) and `DetailedRange` (`Sections 10-18`) | `:app`, `:core:*`, `guest/termvr-agent`, `fdroid/` |

---

## 2. Altitude Separation Guide (Descriptive Module)

`0001-termvr-system-design.md` divides into two explicit review boundaries so architecture reviewers and implementers inspect distinct abstraction levels. High-altitude sections omit raw C prototypes, CLI flags, octal modes, and byte offsets.

| Range Name | Sections | Purpose and Review Standard |
| :--- | :--- | :--- |
| **`FeatureRange`** | Sections `1` through `9` | Defines objectives (Heilmeier Catechism), problem boundaries, user journeys, component topology, trade-offs, security posture, and operational targets without low-level code schemas. |
| **`DetailedRange`** | Sections `10` through `18` | Defines exact milestone files (`Existing`, `Create`, `Modify`), Kotlin data models, JNI C signatures, OpenGL ES 3.0 shaders, IME and key-bar state machines, `termvr-agent` wire frames, recipe manifests, and F-Droid verification commands. |
