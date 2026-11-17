# Changelog

This file records notable user-visible, build, and release changes across `TermVR` versions.

---

## [Unreleased]

This section tracks changes staged on `main` after `0.17.10002026110900`.

---

## [0.17.10002026110900]

This release (`versionCode` `301700000`) upgrades the bundled `gvx` runtime to `v0.17.10002026110900` (`e410633f24cf`). It adds online `ext4lite` space reclamation, offline compaction, copy-on-write (`CoW`) volume branches, and shared Git worktree projects.

### Storage compaction and CoW volume branches

`ext4lite` volumes now reclaim deleted blocks automatically while containers run, compact offline on demand, and support concurrent `CoW` overlays.

- Enabled online `ext4lite` auto-shrink in `gvx` `v0.17.10002026110900` and surfaced `AllocatedBytes` vs `SizeBytes` plus `Compact` actions in `Files > Volumes`.
- Added `OVERLAY_EXT4LITE` (`overlay_ext4lite`) `CoW` volume branching in `GvxCommandBuilder`, `SandboxSupervisor`, `Home`, and `Files` with `Shared (RO)` base locks.

### Multi-container Git worktrees and SAF bridge

Concurrent containers now share host-backed bare Git repositories across isolated worktrees with external Android `SAF` editor support.

- Added `GitWorktreeManager` (`core:data`) and `Git Projects` UI in `Files` and `Home` for concurrent `/git/<project>.git` and `/workspace` worktree branches.
- Added the `TermVR Git Worktrees` root in `TermVrDocumentsProvider` with atomic `.termvr.tmp` writes, `0755` preservation, and frozen-container write guards.

---

## [0.16.10022026110900]

This release (`versionCode` `301600020`) upgrades the bundled `gvx` runtime to `v0.16.10022026110900` (`4f7bbd5ff889`). It improves CJK input, touch selection, SAF workspace saves, port checks, DNS roaming, and 16 KB `arm64` JIT support.

### CJK input, touch selection, and SAF workspace storage

Soft keyboards, TUI mouse views, external SAF editors, and volume browsers now preserve character composition, POSIX permissions, and single-writer volume safety.

- Enabled Hangul and CJK soft-keyboard modes by dropping `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` and adding `IME_FLAG_NO_PERSONALIZED_LEARNING` on `TerminalInputConnection`.
- Hardened 2-beolsik `CTRL`/`ALT` jamo chords, coalesced `SIGWINCH` resize bursts (`48 ms` debounce), added TUI long-press selection, and sanitized bracketed paste bytes.
- Preserved POSIX executable modes and `"wa"` / `"rw"` contents in `TermVrDocumentsProvider`, and streamed `64 KiB` SAF imports within `255`-byte filename bounds.
- Paused live volume helpers to protect single-writer `ext4lite` mounts and added `Open Workspace`, `Stop Container`, and `Discard Checkpoint` actions in `Files`.

### Network resilience, process readiness, and 16 KB gvx runtime

Live port edits, DNS queries, and 16 KB `arm64` runtimes now handle port collisions, truncated UDP replies, V8 JIT caches, and PTY hangups cleanly.

- Added self, cross-profile, and host socket port conflict checks (`PortConflictDetector`), capped SAF WireGuard imports at `64 KiB`, and rolled back failed port applies.
- Added RFC 1035 and EDNS0 (`TC=1`) TCP DNS fallback in `LoopbackDnsProxy`, preserved Wi-Fi LAN reconfirmation state, and surfaced `X/32 procs` and `SIGKILL` diagnostics.
- Upgraded `gvx` (`gvx#203`, `gvx#204`, and `gvx#205`) to untag `TBI0` faults, decode `STLUR`, clean split JIT caches, and route `devpts` hangup and job-control ioctls.

---

## [0.16.10012026110900]

This release (`versionCode` `301600010`) upgrades the bundled `gvx` runtime to `v0.16.10012026110900` (`fd2571c34f87`). It improves terminal key chords, guest shell defaults, compact tab controls, checkpoint lifecycles, and nested containers on 16 KB `arm64` hosts.

### Terminal input, shells, and compact tab controls

Soft-keyboard chords, guest shell defaults, and narrow-screen tab bars now behave consistently across distributions and terminal apps. Exited tabs still close in one tap while live tabs guard against accidental taps.

- Fixed `AuxiliaryKeyBar` one-shot modifier highlight updates, IME composition `CTRL`/`ALT` chords, C0 punctuation encoding, and FIFO PTY write ordering.
- Set `LANG=C.UTF-8`, preferred `/bin/bash` over `/bin/sh` when installed, bypassed guest-local subnets in `termvr-agent` proxy settings, and renamed defaults to `Debian` and `Shell 1`.
- Fixed `btop` `'q'` exit hangs via `gvx` `devpts` non-canonical `VMIN` and `VTIME` replica reads (`gvx#198`).
- Hid inline close icons on unselected compact tabs and added a `Close tab?` confirmation modal for live tabs and split panes.

### Lifecycle hardening and 16 KB nested containers

Surface recreation, checkpoint consumption, and 16 KB `arm64` memory management now handle repeated pause, resume, stop, and nested container workloads cleanly. `scripts/sync-gvx-version.sh` also keeps `CheckpointController.DEFAULT_GVX_VERSION` and bundled license notices synchronized.

- Hardened OpenGL ES 3.0 thread teardown, `NativeVtSession` locking, per-tab surface controllers, and foreground service resume handling.
- Marked consumed `active-slot.json` checkpoints superseded on restore and container stop so later cold starts do not reuse stale snapshots.
- Upgraded `gvx` (`gvx#201` and `gvx#202`) to fix 16 KB `arm64` split `.relr.dyn` sub-block unmapping for `Docker (DinD)` and `k3s` `nginx:alpine` workloads and retain guest memory through teardown.

---

## [0.16.10002026110900]

This release (`versionCode` `301600000`) upgrades the bundled `gvx` runtime to `v0.16.10002026110900` (`a9bfd06c2c07`) and switches persistent rootfs containers to native `gvx` `-rootfs-volume` overlays.

### Native persistent rootfs and version parity

`TermVR` now delegates root filesystem persistence directly to `gvx` instead of copying files inside the guest at boot.

- Switched `GvxCommandBuilder` (`buildRunArgs` and `buildRestoreArgs`) to pass `-rootfs-volume rootfs-<id>` when `persistentRootfs` is enabled, removing `/.termvr-rootfs` bind mounts and the `RAW_PTY` `termvr-agent --exec` wrapper.
- Removed `pivot_root` staging, `--persist-root-dir`, and `--exec` code from `guest/termvr-agent` while keeping `/` confinement in `VOLUME_FS_OP` for live and stopped rootfs browsing.
- Updated `Files > Volumes` stopped-container inspection to mount persistent `rootfs-<id>` volumes with `-rootfs-volume` so users browse the merged `/` tree rather than raw `upper` and `work` directories.
- Updated `scripts/sync-gvx-version.sh` to compute monotonic 9-digit `versionCode` values (`301600000` for `v0.16.10002026110900`) above legacy `261109001`.

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
