# Changelog

This file records notable user-visible, build, and release changes across `TermVR` versions.

---

## [Unreleased]

This section tracks changes staged on `main` after `0.15.10002026110900`.

---

## [0.15.10002026110900]

This initial public release aligns `TermVR` (`versionCode` `261109000`) with upstream `gvx` `v0.15.10002026110900`. It delivers an unrooted Linux container runtime, an OpenGL ES 3.0 terminal, storage and network controls, and reproducible release builds.

### Features

`TermVR` ships an isolated Linux environment, GPU terminal rendering, and container management in one Android application. The items below summarize the core capabilities available in this release.

- Sets Debian 13 (`docker.io/library/debian:trixie-slim`) with uncapped host memory and CPU as the default container profile, with optional per-profile limits.
- Runs concurrent multi-container sessions supervised by a foreground Android service with wake locks, live resource telemetry, and checkpoint `Freeze` and `Resume`.
- Multiplexes tabs and split panes inside each sandbox through the static Go `termvr-agent` PID 1 supervisor over stdio pipes.
- Creates, inspects, mounts, and deletes persistent named `ext4lite` volumes alongside sandbox `/workspace` directories.
- Shares `/workspace` files with external Android editors through `TermVrDocumentsProvider` and persisted Storage Access Framework (`SAF`) grants.
- Renders terminal grids and split panes through `libghostty-vt`, static FreeType and HarfBuzz shaping, and OpenGL ES 3.0 up to `120 Hz`.
- Bundles `TermVR Mono NF` `v1.0.1` for English, Korean Hangul, CJK glyphs, ligatures, and Nerd Font icons, paired with Hangul/CJK IME composition and a two-row auxiliary key bar.
- Forwards guest ports to loopback by default, gates LAN `0.0.0.0` exposure, and displays LAN connection QR codes.
- Translates `HTTP`, `HTTPS`, `SOCKS5`, and `SOCKS4` proxies with Keystore credential storage, dynamic host DNS resolution, and imported WireGuard tunnels.
- Includes ready-to-launch recipes for rootless Docker Engine (`docker:dind`) and single-node `k3s` Kubernetes clusters.
- Provides an adaptive Jetpack Compose shell in the `TermVR Obsidian` palette with an offline Open-Source Licenses viewer under `Settings`.

### Build and Release

The build pipeline compiles Kotlin, Go, Zig, and C/C++ components offline from pinned commits. Every release artifact passes alignment, reproducibility, and version-parity gates before publication.

- Aligned `versionName` and 32-bit `YYMMDDGGR` `versionCode` (`261109000`) across `scripts/source-lock.json`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, and `fdroid/dev.gfire.termvr.yml`.
- Pinned upstream `gvx`, `ghostty`, `freetype`, `harfbuzz`, and `TermVR Mono NF` `v1.0.1` commits in `scripts/source-lock.json` and `fdroid/dev.gfire.termvr.yml`.
- Enforced `16 KB` (`16384`-byte) ELF `PT_LOAD` alignment across `arm64-v8a` and `x86_64` shared libraries with `scripts/verify-16kb-alignment.sh`.
- Configured `.github/workflows/release-slsa.yml` to verify clean-room unsigned APK reproducibility, apply Android v2/v3 signing, verify `apksigcopier` parity, publish curated release notes from `docs/releases/`, and attach `SLSA` provenance.
- Enforced version parity, unit tests, lint checks, and Android 16 (`16 KB`) emulator smoke verification in `.github/workflows/ci.yml`.
