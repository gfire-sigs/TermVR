# Agent Operating Manual (`AGENTS.md`)

`TermVR` (`Terminal Virtual Runtime`) is an Android terminal and Linux container sandbox app built with Kotlin, Jetpack Compose, JNI, `gvx`, and `libghostty-vt`. This manual defines the startup protocol, module boundaries, design workflow, and **ASD-STE100** writing gates for every change in this repository.

> [!IMPORTANT]
> Read [`docs/designs/SITEMAP.md`](docs/designs/SITEMAP.md) and [`docs/designs/0001-termvr-system-design.md`](docs/designs/0001-termvr-system-design.md) at the start of every session before modifying any file. Verify the owning Gradle or Go module boundary before writing code.

---

## 1. Mandatory Startup Protocol (Procedural Module)

Execute these numbered steps before editing repository files.

1. Read [`docs/designs/0001-termvr-system-design.md`](docs/designs/0001-termvr-system-design.md) to verify the target milestone (`M1` through `M5`) and module contract.
2. Inspect [`scripts/source-lock.json`](scripts/source-lock.json) to confirm pinned commit SHAs for `gvx`, `ghostty`, `freetype`, `harfbuzz`, and bundled fonts.
3. Activate the matching skills under `.agents/skills/` from the Section 1.1 routing table.
4. Verify that planned changes introduce zero proprietary dependencies, tracking SDKs, or unpinned prebuilt binaries.
5. Update the matching design document in `docs/designs/` before changing cross-module interfaces, wire frames, or manifest permissions.
6. Run the mechanical text and JSON verification checks in Section 5 before committing.

### 1.1 Engineering Skill Routing Table (Reference Module)

The repository vendors 41 engineering skills under `.agents/skills/` from `gosuda/dstack-skills` at commit `5272cb20529ac477a519144b554174d22227d95d` (`Apache-2.0`). Select the skills matching the active module and task category before planning or editing files.

| Task Category | Vendored Skills (`.agents/skills/`) | Scope in `TermVR` |
| :--- | :--- | :--- |
| **Zig & Native Cross-Build** | `zig-cross`, `elf-inspection`, `dynamic-linking`, `abi-and-calling-conventions` | `libghostty-vt` Zig Android targets, 16 KB `PT_LOAD` alignment, Bionic linking, and JNI C ABI. |
| **C/C++ Hardening & Threads** | `c-hardening-baseline`, `sanitizers`, `concurrency-debugging`, `memory-model`, `libfuzzer` | `libtermvr_vt.so` compiler hardening, ASan/UBSan/TSan, VT/EGL atomic handoffs, and parser fuzzing. |
| **Go Guest Agent** | `production-go` | `guest/termvr-agent` PID 1 supervisor, `/dev/pts` mux, local proxy listener, and `SO_ORIGINAL_DST`. |
| **Specs, Types & Lifecycles** | `spec-driven`, `implement-spec`, `contract-driven`, `type-driven`, `validation-first-driven` | `M1` to `M5` implementation waves, Kotlin domain schemas, and IME/key-bar/service state machines. |
| **Security & Supply Chain** | `security-review`, `c-security-review`, `supply-chain-risk-auditor`, `zeroize-audit` | `0.0.0.0` LAN gates, `SAF` paths, JNI bounds, F-Droid source lock audits, and secret zeroization. |
| **Testing & Performance** | `tests-practices`, `tests-adversarial`, `verification-before-completion`, `control-cli`, `run-smoke-tests`, `optimize` | Red-to-green TDD, adversarial wire tests, PTY/TUI transcripts, and 120Hz OpenGL ES 3.0 profiling. |
| **Compose UI & Typography** | `design`, `frontend-ui-engineering`, `ui-animation` | Adaptive screens, CJK/Hangul visual density, palettes, and frame-budgeted spring animations. |
| **ASD-STE100 & Docs** | `humanizer-simple-english-asd-ste100`, `docs-writing`, `docs-update`, `unslop` | ASD-STE100 controlled English, S1000D/Diataxis module structure, and spec synchronization. |
| **Review, CI & Git** | `review`, `fix`, `ci-fix`, `code-simplification`, `ci-cd`, `commit`, `commit-push-pr`, `commit-push-current`, `commit-push-main` | Multi-pass code review, reproducible APK CI gates, atomic commits, and branch publishing. |

---

## 2. Layered Module Map and Import Rules (Descriptive Module)

`TermVR` isolates UI composition, terminal emulation, and Linux sandbox supervision across six Gradle modules and one static Go guest binary. Lower layers never import higher layers.

| Module | Namespace / Package | Single Responsibility | Permitted Internal Imports |
| :--- | :--- | :--- | :--- |
| `:core:model` | `dev.gfire.termvr.core.model` | Pure Kotlin value types for profiles, port and proxy rules, recipes, fonts, key bars, and telemetry. | None |
| `:core:data` | `dev.gfire.termvr.core.data` | DataStore persistence, Keystore proxy credentials, `SAF` grants, font registry, and recipe catalog loading. | `:core:model` |
| `:core:terminal-vt` | `dev.gfire.termvr.core.vt` | JNI library (`libtermvr_vt.so`) owning `libghostty-vt`, FreeType, HarfBuzz, and OpenGL ES 3.0 atlas shaders. | `:core:model` |
| `:core:terminal-ui` | `dev.gfire.termvr.core.ui` | Single-surface `SurfaceView` host, `Choreographer` frame loop, `InputConnection` IME bridge, and auxiliary key bar. | `:core:model`, `:core:terminal-vt` |
| `:core:runtime-gvx` | `dev.gfire.termvr.core.runtime` | `librungv.so` supervisor, foreground service, wake lock, loopback DNS and proxy bridges, and mux client. | `:core:model`, `:core:data` |
| `:app` | `dev.gfire.termvr` | Adaptive navigation shell, feature screens, onboarding diagnostics, and `DocumentsProvider`. | All `:core:*` modules |
| `guest/termvr-agent` | `dev.gfire.termvr/guest/termvr-agent` | Static pure-Go Linux PID 1 multiplexer and local proxy listener over stdio pipes. | Go standard library only |

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
