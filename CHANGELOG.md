# Changelog

This file records notable user-visible, build, and release changes across `TermVR` versions.

---

## [Unreleased]

This section tracks changes staged on `main` after `0.15.10002026110900.1`.

---

## [0.15.10002026110900.1]

This maintenance release (`versionCode` `261109001`) tracks upstream `gvx` `v0.15.10002026110900` and adds persistent `ext4lite` root filesystems, terminal compatibility fixes, and checkpoint reliability improvements.

### Persistent storage and container lifecycle

Container creation and runtime supervision now support optional root persistence alongside reliable `Freeze` and `Resume` checkpoints.

- Added an optional persistent root checkbox during container creation and launch that mounts `rootfs-<id>.ext4` at `/` via `pivot_root`.
- Preserved installed `apt`, `apk`, and `dnf` packages, `/etc` settings, and `/root` files across full container stops and restarts.
- Exposed persistent `rootfs-<id>.ext4` volumes in `Files > Volumes` and removed them automatically when deleting a container.
- Fixed `LocalSocket` listener accept ordering, JSON control framing for `rungv`, pre-opened `-checkpoint-dir` lockdown handling, and post-restore mux tab rebinding.

### Terminal emulation and rendering

Terminal I/O and OpenGL ES 3.0 rendering now support standard terminfo profiles and bidirectional VT capability queries.

- Changed default guest `TERM` to `xterm-256color` with `COLORTERM=truecolor` and `TERM_PROGRAM=TermVR`.
- Wired `libghostty-vt` query replies (`CPR`, `DSR`, `DA1`, `DA2`, `XTWINOPS`, `DECRQM`, `XTVERSION`, `OSC 10/11`, and Kitty graphics) back to the PTY.
- Fixed OpenGL ES 3.0 viewport clearing, scrollback clearing, and cursor redraws on `clear` (`ED 2` and `ED 3`).
- Forwarded pre-launch cell and pixel dimensions on `openPty` and preserved `O_NONBLOCK` during `TIOCSWINSZ` via `SyscallConn()`.

### Recipes, fonts, and documentation

Catalog recipes now boot without artificial resource caps, and repository docs include updated emulator screenshots.

- Removed default CPU and memory caps across catalog container recipes.
- Added `arm64` `/proc/cpuinfo` synthesis and legacy `iptables` selection for `docker:dind` and single-node `k3s`, plus writable `overlayfs` at `/var/lib/rancher` for `k3s`.
- Shipped bundled `TermVR Mono NF` `v1.1.0` alongside updated GitHub Actions dependencies and replaced placeholder SVGs in `README.md` with Android 16 emulator screenshots.

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
- Bundles `TermVR Mono NF` `v1.1.0` for English, Korean Hangul, CJK glyphs, ligatures, and Nerd Font icons, paired with Hangul/CJK IME composition and a two-row auxiliary key bar.
- Forwards guest ports to loopback by default, gates LAN `0.0.0.0` exposure, and displays LAN connection QR codes.
- Translates `HTTP`, `HTTPS`, `SOCKS5`, and `SOCKS4` proxies with Keystore credential storage, dynamic host DNS resolution, and imported WireGuard tunnels.
- Includes ready-to-launch recipes for rootless Docker Engine (`docker:dind`) and single-node `k3s` Kubernetes clusters.
- Provides an adaptive Jetpack Compose shell in the `TermVR Obsidian` palette with an offline Open-Source Licenses viewer under `Settings`.

### Build and Release

The build pipeline compiles Kotlin, Go, Zig, and C/C++ components offline from pinned commits. Every release artifact passes alignment, reproducibility, and version-parity gates before publication.

- Aligned `versionName` and 32-bit `YYMMDDGGR` `versionCode` (`261109000`) across `scripts/source-lock.json`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, and `fdroid/dev.gfire.termvr.yml`.
- Pinned upstream `gvx`, `ghostty`, `freetype`, `harfbuzz`, and `TermVR Mono NF` `v1.1.0` commits in `scripts/source-lock.json` and `fdroid/dev.gfire.termvr.yml`.
- Enforced `16 KB` (`16384`-byte) ELF `PT_LOAD` alignment across `arm64-v8a` and `x86_64` shared libraries with `scripts/verify-16kb-alignment.sh`.
- Configured `.github/workflows/release-slsa.yml` to verify clean-room unsigned APK reproducibility, apply Android v2/v3 signing, verify `apksigcopier` parity, publish curated release notes from `docs/releases/`, and attach `SLSA` provenance.
- Enforced version parity, unit tests, lint checks, and Android 16 (`16 KB`) emulator smoke verification in `.github/workflows/ci.yml`.
