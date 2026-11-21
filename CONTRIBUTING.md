# Contributing to TermVR

`TermVR` (`Terminal Virtual Runtime`) follows a specification-first workflow and targets reproducible source builds for F-Droid and GitHub Releases. Every contributor and automated agent obeys the architectural boundaries in [`AGENTS.md`](AGENTS.md) and [`docs/designs/0001-termvr-system-design.md`](docs/designs/0001-termvr-system-design.md).

---

## 1. Design-First Workflow (Procedural Module)

Follow the Elephant-Goldfish Model (`EGM`) before implementing cross-module features, native JNI interfaces, or wire protocol changes.

1. Draft or update the design specification under `docs/designs/` with separate `FeatureRange` and `DetailedRange` sections.
2. Keep high-altitude architecture free of code-level schemas, and specify exact types, wire layouts, and file paths in `DetailedRange`.
3. Review the draft from a fresh context against comprehension, adversarial failure modes, altitude separation, and upstream `gvx` / `libghostty-vt` contracts.
4. Implement one milestone slice per pull request after resolving all design blockers.

---

## 2. Trusted Build and F-Droid Rules (Descriptive Module)

`TermVR` ships zero proprietary SDKs, telemetry trackers, or unverified binary blobs. Every native artifact compiles from a commit SHA pinned in [`scripts/source-lock.json`](scripts/source-lock.json).

| Artifact | Pinned Source Repository | Toolchain | F-Droid Compliance Rule |
| :--- | :--- | :--- | :--- |
| `librungv.so` | `gfire-sigs/gvx` | Go `1.27.4` (+ NDK on `x86_64`) | Build from source with `-trimpath`, `-buildid=`, and `PT_LOAD` alignment `>= 16 KB`. |
| `libghostty-vt.a` | `ghostty-org/ghostty` | Zig `0.17.0` | Build offline via `zig build -Demit-lib-vt --system` from the pinned `source-lock.json` checkout. |
| `libtermvr_vt.so` | `TermVR` + `freetype` + `harfbuzz` | Android NDK `r28`, CMake `3.22.1` | Link with `-Wl,-z,max-page-size=16384` and `-Wl,--build-id=none`. |
| `termvr-agent` | `TermVR` (`guest/termvr-agent`) | Go `1.27.4` (`CGO_ENABLED=0`) | Build static Linux ELF64 (`arm64` and `amd64`) with zero external Go modules. |
| `TermVR.apk` | `TermVR` (`:app`) | Android Gradle Plugin (`AGP`) `8.9.1`, JDK `17` | Set `dependenciesInfo.includeInApk = false` and enable deterministic R8 shrinking. |

---

## 3. Commit and Documentation Standards (Procedural Module)

Write all code comments, documentation, and commit messages in English following [`AGENTS.md`](AGENTS.md) Section 4.

1. Format commit subjects with Conventional Commits (`feat:`, `fix:`, `docs:`, `build:`, `refactor:`, `test:`) under 72 characters.
2. State technical causes and invariants in commit bodies without conversational filler or file-by-file restatements.
3. Run the verification commands in [`AGENTS.md`](AGENTS.md) Section 5 before pushing a branch.
