# TermVR

`TermVR` (`Terminal Virtual Runtime`, mobile terminal app) is an open-source Linux container terminal for Android 12 and newer (`arm64-v8a` and `x86_64`). It runs unmodified OCI images without root access using the `gvx` user-space kernel, `libghostty-vt`, and an OpenGL ES 3.0 glyph renderer.

> [!NOTE]
> This repository currently holds the **Milestone 0 (`M0`)** multi-module skeleton, source lockfile, F-Droid build recipe, and `EGM` specification. Application and native runtime implementation begins in `Milestone 1`.

---

## 1. Architecture and Capability Overview (Descriptive Module)

`TermVR` separates Android UI composition, native terminal emulation, and Linux sandbox execution into isolated layers. Multiple interactive shells share one sandbox process so child process counts remain inside Android's background limits.

| Subsystem | Technology | Capability Summary |
| :--- | :--- | :--- |
| **Linux Sandbox Runtime** | `gvx` (`librungv.so`, `systrap` engine) | Runs host-ISA OCI images (`linux/arm64` on `arm64-v8a`, `linux/amd64` on `x86_64`) compiled into Enhanced Read-Only File System (`EROFS`) layers with `ext4lite` volumes. |
| **Session Multiplexer** | `termvr-agent` (static pure-Go PID 1) | Multiplexes multi-tab `/dev/pts` shells and gated host helper calls (`URL`, clipboard, share, volume I/O) over `stdin`/`stdout` pipes (`<= 4` host processes per sandbox). |
| **VT Engine & GPU Shaders** | `libghostty-vt` (`vt-1.0.1`) + OpenGL ES 3.0 | Shapes HarfBuzz ligatures, Nerd Font icons, CJK fallback fonts, and Kitty inline images inside `libtermvr_vt.so` at up to `120 Hz`. |
| **Mobile Input Pipeline** | Jetpack Compose + custom `InputConnection` | Pairs a single-surface `SurfaceView` and 2-row auxiliary key bar (`ESC`, `CTRL`, `ALT`, arrows, scrub) with Hangul/CJK-safe IME composition. |
| **Prebuilt Recipes** | `docker:dind` & `rancher/k3s` | Boots an unprivileged Docker Engine or single-node Kubernetes cluster backed by persistent `ext4lite` storage. |
| **Trusted Supply Chain** | Go `1.27.4`, Zig `0.17.0`, NDK `r28`, AGP `8.9.1` | Compiles deterministically from pinned commits (`scripts/source-lock.json`) for F-Droid `ReproducibleBuilds` and Supply-chain Levels for Software Artifacts (`SLSA`) v1.0 Build Level 2 provenance. |

---

## 2. Screen and Workspace Layout (Descriptive Module)

The adaptive navigation shell switches between a bottom bar on phones and a side rail on foldables, tablets, and desktop-mode displays. Every screen exposes explicit controls for sandbox state, network exposure, and persistent files.

| Screen | Core Controls |
| :--- | :--- |
| **`Home`** | One-tap profile launch, live sandbox cards, Android 14+ child-process restriction diagnostics, and 16 KB page-size status. |
| **`Terminal`** | Multi-tab strip, single-surface split-pane viewport, `GhosttySearch` bar, and dockable 2-row auxiliary key bar. |
| **`Catalog`** | Host-ISA distributions (`Alpine`, `Debian`, `Ubuntu`, `Fedora`), `Docker` and `Kubernetes` recipes, and `.tar` archive import/export. |
| **`Network`** | Loopback (`127.0.0.1`), security-gated LAN (`0.0.0.0` with Wi-Fi IP and QR code), Unix sockets, LAN egress isolation, and WireGuard profiles. |
| **`Files`** | Dual-root browser for `/workspace` (`hostfs`) and `ext4lite` volumes, Storage Access Framework (`SAF`) transfers, and `DocumentsProvider` editor access. |
| **Notification Bar** | Live CPU and Proportional Set Size (`PSS`) memory telemetry, wake-lock timer toggle, one-tap `Freeze` / `Resume`, and `Stop All`. |

---

## 3. Repository Layout (Reference Module)

Every directory maps to a single architectural boundary defined in [`AGENTS.md`](AGENTS.md). Lower-level core modules never import `:app`.

| Path | Description |
| :--- | :--- |
| [`app/`](app/) | Android application shell, adaptive navigation host, feature screens, R8 rules, and `DocumentsProvider` manifest. |
| [`core/model/`](core/model/) | Pure Kotlin serializable domain types for profiles, port forwards, recipes, fonts, palettes, and key bars. |
| [`core/data/`](core/data/) | AndroidX DataStore persistence, `SAF` URI grants, and bundled recipe catalog assets. |
| [`core/terminal-vt/`](core/terminal-vt/) | JNI module (`libtermvr_vt.so`, 16 KB aligned) owning `libghostty-vt`, FreeType, HarfBuzz, and OpenGL ES 3.0 atlas rendering. |
| [`core/terminal-ui/`](core/terminal-ui/) | Single-surface `SurfaceView` host, `Choreographer` VSYNC loop, Hangul/CJK `InputConnection`, and auxiliary key bar. |
| [`core/runtime-gvx/`](core/runtime-gvx/) | `SandboxForegroundService`, `librungv.so` command builder, loopback DNS proxy, wake lock controller, and mux client. |
| [`guest/termvr-agent/`](guest/termvr-agent/) | Pure-Go static Linux multiplexer (`CGO_ENABLED=0`) for multi-tab PTY streams and gated host helper RPCs. |
| [`scripts/source-lock.json`](scripts/source-lock.json) | Pinned Git commit SHAs and SPDX licenses for `gvx`, `ghostty`, `freetype`, `harfbuzz`, and bundled fonts. |
| [`fdroid/`](fdroid/) & [`fastlane/`](fastlane/) | F-Droid reproducible build recipe (`dev.gfire.termvr.yml`) and store metadata. |
| [`docs/designs/`](docs/designs/) | Elephant-Goldfish Model (`EGM`) system architecture and zero-decision implementation specifications. |

---

## 4. Documentation Index (Reference Module)

Read these documents in order for subsystem specifications, operational rules, and contribution procedures. Each document separates high-altitude product intent from low-level implementation contracts.

| Document | Scope |
| :--- | :--- |
| [`docs/designs/SITEMAP.md`](docs/designs/SITEMAP.md) | Index of `EGM` design documents and `FeatureRange` / `DetailedRange` review boundaries. |
| [`docs/designs/0001-termvr-system-design.md`](docs/designs/0001-termvr-system-design.md) | Master product and engineering specification covering UI, GPU renderer, IME, recipes, and F-Droid builds. |
| [`AGENTS.md`](AGENTS.md) | Module import rules, runtime ownership invariants, and ASD-STE100 documentation gates. |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Specification-first workflow, reproducible native build rules, and commit standards. |
| [`CHANGELOG.md`](CHANGELOG.md) | Categorized release history and milestone baseline records. |

---

## 5. License (Reference Module)

`TermVR` is licensed under the [Apache License, Version 2.0](LICENSE). All pinned upstream dependencies (`gvx`, `libghostty-vt`, `FreeType`, `HarfBuzz`, `JetBrains Mono`, and `Nerd Fonts`) use F-Droid-compatible open-source licenses recorded in [`scripts/source-lock.json`](scripts/source-lock.json).
