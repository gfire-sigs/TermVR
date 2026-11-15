# Agent Operating Manual (`AGENTS.md`)

`TermVR` (`Terminal Virtual Runtime`) is an Android terminal and Linux container sandbox app built with Kotlin, Jetpack Compose, JNI, `gvx`, and `libghostty-vt`. This manual defines the startup protocol, module boundaries, design workflow, and **ASD-STE100** writing gates for every change in this repository.

> [!IMPORTANT]
> Read [`docs/designs/SITEMAP.md`](docs/designs/SITEMAP.md) and [`docs/designs/0001-termvr-system-design.md`](docs/designs/0001-termvr-system-design.md) at the start of every session before modifying any file. Verify the owning Gradle or Go module boundary before writing code.

---

## 1. Mandatory Startup Protocol (Procedural Module)

Execute these numbered steps before editing repository files.

1. Read [`docs/designs/0001-termvr-system-design.md`](docs/designs/0001-termvr-system-design.md) to verify the target milestone (`M1` through `M5`) and module contract.
2. Inspect [`scripts/source-lock.json`](scripts/source-lock.json) to confirm pinned commit SHAs for `gvx`, `ghostty`, `freetype`, `harfbuzz`, and bundled fonts.
3. Verify that planned changes introduce zero proprietary dependencies, tracking SDKs, or unpinned prebuilt binaries.
4. Update the matching design document in `docs/designs/` before changing cross-module interfaces, wire frames, or manifest permissions.
5. Run the mechanical text and JSON verification checks in Section 5 before committing.

---

## 2. Layered Module Map and Import Rules (Descriptive Module)

`TermVR` isolates UI composition, terminal emulation, and Linux sandbox supervision across six Gradle modules and one static Go guest binary. Lower layers never import higher layers.

| Module | Namespace / Package | Single Responsibility | Permitted Internal Imports |
| :--- | :--- | :--- | :--- |
| `:core:model` | `dev.gfire.termvr.core.model` | Pure Kotlin value types for profiles, port rules, recipes, fonts, key bars, and telemetry. | None |
| `:core:data` | `dev.gfire.termvr.core.data` | DataStore persistence, Storage Access Framework (`SAF`) grants, font registry, and recipe catalog loading. | `:core:model` |
| `:core:terminal-vt` | `dev.gfire.termvr.core.vt` | JNI library (`libtermvr_vt.so`) owning `libghostty-vt`, FreeType, HarfBuzz, and OpenGL ES 3.0 atlas shaders. | `:core:model` |
| `:core:terminal-ui` | `dev.gfire.termvr.core.ui` | Single-surface `SurfaceView` host, `Choreographer` frame loop, `InputConnection` IME bridge, and auxiliary key bar. | `:core:model`, `:core:terminal-vt` |
| `:core:runtime-gvx` | `dev.gfire.termvr.core.runtime` | `librungv.so` process supervisor, foreground service, wake lock, loopback DNS proxy, and mux client. | `:core:model`, `:core:data` |
| `:app` | `dev.gfire.termvr` | Adaptive navigation shell, feature screens, onboarding diagnostics, and `DocumentsProvider`. | All `:core:*` modules |
| `guest/termvr-agent` | `dev.gfire.termvr/guest/termvr-agent` | Static pure-Go Linux PID 1 multiplexer allocating guest `/dev/pts` sessions over stdio pipes. | Go standard library only |

---

## 3. Runtime Ownership and Android Security Invariants (Descriptive Module)

Every component that allocates native memory, OpenGL textures, file descriptors, or child OS processes obeys strict single-owner lifetimes.

### 3.1 Native Terminal Memory and Render Thread Isolation

`:core:terminal-vt` allocates `GhosttyTerminal` and `GhosttyRenderState` instances on the native heap behind a per-session mutex. The I/O reader thread feeds VT byte sequences into `ghostty_terminal_vt_write` and sets an atomic dirty flag. The OpenGL ES 3.0 thread wakes on `Choreographer` callbacks only when dirty, updates dirty row instances, and calls `ghostty_render_state_clean`. Calling `Close()` stops the reader thread, releases GL resources on the EGL thread, and calls `ghostty_terminal_free`.

### 3.2 Host Lockdown, Phantom-Process Budget, and 16 KB Pages

`librungv.so` applies `hostlockdown` before starting guest code. The sandbox cannot bind new host ports, open new host directories, or create checkpoint files after guest boot. `:core:runtime-gvx` passes all `-p` port rules, `-v` volume mounts, `--host-dns` proxy endpoints, `-checkpoint-dir`, and `-control-socket` flags at startup.

Android limits total app child processes to 32 across the OS by default on Android 12 and newer. Each `gvx` guest address space consumes one Sentry process, one `systrap` stub process, and up to two `sysmsg` processes. `TermVR` multiplexes all tabs and split panes of a sandbox through `termvr-agent` over `stdin` and `stdout` pipes. Every native ELF binary (`librungv.so` and `libtermvr_vt.so`) aligns `PT_LOAD` segments to at least 16 KB (`-Wl,-z,max-page-size=16384`) for Android 15+ devices.

---

## 4. Elephant-Goldfish Design Model and ASD-STE100 Standard (Reference Module)

Architectural proposals obey the Elephant-Goldfish Model (`EGM`). Every design document separates high-altitude product architecture (`FeatureRange`) from implementation-ready contracts (`DetailedRange`) and undergoes stateless Goldfish reviews.

All repository prose (`README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, `docs/**/*.md`, `fastlane/**/*.txt`), comments, and commits obey **ASD-STE100** in English.

| Rule | Mandatory Constraint |
| :--- | :--- |
| **Procedural Sentences** | Maximum **20 words**; maximum **1 instruction**; start with an imperative verb (`Run`, `Build`, `Set`, `Verify`). |
| **Descriptive Sentences** | Maximum **25 words**; maximum **6 sentences** per paragraph; write in active voice only. |
| **Noun Clusters** | Maximum **3 consecutive nouns**; unpack longer chains with prepositions (`for`, `in`, `of`, `on`). |
| **Controlled Verbs** | Use exact verbs (`parse`, `validate`, `dispatch`, `allocate`, `supervise`, `verify`, `obey`). |
| **Zero Em-Dashes & Arrows** | Never output `U+2014`, spaced `U+2013`, double hyphens as punctuation, ASCII arrows, or `U+2192` in prose. |
| **Zero AI-Slop Lexicon** | Never output marketing buzzwords, throat-clearing prefaces, or theatrical contrast formulas. |
| **Heading Separation** | Never stack two headings without a 2-to-4 sentence scope lead or summary table beneath each heading. |
| **Safety Alert Placement** | Place `> [!CAUTION]`, `> [!WARNING]`, and `> [!IMPORTANT]` immediately before the steps or code they govern. |

---

## 5. Verification Procedure (Procedural Module)

Run these deterministic checks from the repository root before creating a commit or pull request.

> [!IMPORTANT]
> Run all text audits after the final edit to any Markdown, Fastlane, or Gradle file. A check executed before the last edit does not count as verification evidence.

1. Verify zero em-dashes, spaced en-dashes, or prose arrows across documentation and metadata files.
2. Verify zero banned AI-slop words or rhetorical filler across the repository.
3. Verify that `scripts/source-lock.json` parses cleanly as valid JSON and matches `fdroid/dev.gfire.termvr.yml` commit SHAs.

```bash
rg -n "\x{2014}| \x{2013} |\x{2192}" README.md AGENTS.md CONTRIBUTING.md CHANGELOG.md docs/ fastlane/ fdroid/
rg -ni "\b(seamless|seamlessly|robust|battle-tested|cutting-edge|next-generation|state-of-the-art|powerhouse|unleash|empower|supercharge|elevate|delve|tapestry|testament|holistic|synergy|paradigm|under the hood|heavy lifting|effortlessly|meticulously|crucial|pivotal|paramount|game-changer|utilize|utilizes|utilizing|leverage|leverages|leveraging)\b" README.md AGENTS.md CONTRIBUTING.md CHANGELOG.md docs/ fastlane/ fdroid/
python3 -m json.tool scripts/source-lock.json >/dev/null
```
