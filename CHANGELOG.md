# Changelog

This file records notable user-visible, build, and release changes across `TermVR` versions.

---

## [Unreleased]

This section tracks changes staged on `main` after `0.24.10002026112300`.

---

## [0.24.10002026112300]

This release (`versionCode` `302400000`) upgrades `gvx` to `v0.24.10002026112300` (`e4e6b7b8b695`) and aligns `TermVR` host environment storage with `gvx` user data directory resolution.

### Runtime upgrade to `gvx` `v0.24.10002026112300`

The embedded `librungvx.so` runtime advances from `gvx` `v0.23.10022026112300` to `v0.24.10002026112300` (`e4e6b7b8b695`), keeping `gVisor` `release-20261123.0` (`83df8eae069e`).

- Bundled `gvx` `v0.24.10002026112300` (`e4e6b7b8b695`), which moves default named volume storage out of cache directories (`gvx#261`) and adds `-volume-dir` and `GVX_VOLUME_DIR` controls across the CLI and Go SDK.
- Included VFS-backed working-directory resolution for relative guest executables and relative or empty `PATH` entries (`gvx#262`).
- Updated `CheckpointController.DEFAULT_GVX_VERSION`, `scripts/source-lock.json`, `gradle/libs.versions.toml`, `fdroid/dev.gfire.termvr.yml`, and bundled license notices to `v0.24.10002026112300`.

### Persistent `ext4lite` volume directory alignment

Upstream `gvx` `v0.24` resolves default named volumes on Android from `$XDG_DATA_HOME/gvx/volumes` instead of `$XDG_CACHE_HOME/gvx/volumes`. `TermVR` sets `XDG_DATA_HOME` to the same persistent app storage directory so fresh and upgraded installs share one volume path.

- Exported `XDG_DATA_HOME = <filesDir>/gvx-store` alongside `XDG_CACHE_HOME` in `GvxCommandBuilder.buildHostEnvironment`.
- Updated `GvxCommandBuilderTest` and `docs/designs/0001-termvr-system-design.md` Section 15.1 to cover the `XDG_DATA_HOME` environment contract.

---

## [0.23.10022026112300]

This release (`versionCode` `302300020`) upgrades `gvx` to `v0.23.10022026112300` (`52e94052d139`) and migrates the packaged runtime binary from `librungv.so` to `librungvx.so`. It bundles the upstream `gVisor` `release-20261123.0` sync, `xattr` capability security fixes, and updated `rungvx` stderr port parsing.

### Runtime upgrade to `gvx` `v0.23.10022026112300`

The embedded sandbox kernel advances from `gvx` `v0.22.10002026111600` to `v0.23.10022026112300` (`52e94052d139`), tracking `gVisor` `release-20261123.0` (`83df8eae069e`).

- Bundled `gvx` `v0.23.10022026112300` (`52e94052d139`), which syncs `gVisor` to `release-20261123.0` with EROFS tail-packed inline data, TCP Delayed ACKs, `seccomp` filter cache reuse, overlay depth limits, and `epoll_ctl` `EPOLLEXCLUSIVE` validation.
- Included raw v3 file capability `xattr` fixes across `hostfs`, `ext4lite`, and `erofs`, plus per-stack netstack isolation improvements in `gvx`.
- Updated `CheckpointController.DEFAULT_GVX_VERSION`, `scripts/source-lock.json`, `gradle/libs.versions.toml`, `fdroid/dev.gfire.termvr.yml`, and bundled license notices to `v0.23.10022026112300`.

### Migration from `librungv.so` to `librungvx.so`

Upstream `gvx` `v0.23` renamed `./cmd/rungv` and its Android binary to `./cmd/rungvx` and `librungvx.so` (`gvx#250`). `TermVR` updates its build script, native binary lookup, and stderr parser while keeping existing profiles and volumes compatible.

- Updated `scripts/build-native-deps.sh` and `scripts/source-lock.json` to build `./cmd/rungvx` into `jniLibs/<abi>/librungvx.so` and remove any stale `librungv.so` output.
- Updated `SandboxForegroundService`, `SandboxRuntimeGateway`, and `SandboxSupervisor` to launch `librungvx.so` from `applicationInfo.nativeLibraryDir`.
- Updated `GvxCommandBuilder.parsePublishingLine` to parse `rungvx: publishing` stderr lines alongside legacy `rungv: publishing` lines.

---

## [0.22.10002026111600]

This release (`versionCode` `302200000`) upgrades `gvx` to `v0.22.10002026111600` (`a90476e02630`) and hardens automated upstream release tracking. It bundles `ext4lite` per-mount size limits, ephemeral scratch volumes, and improved `sync-gvx` workflow automation.

### Runtime upgrade to `gvx` `v0.22.10002026111600`

The bundled `librungv.so` binary advances to upstream `gvx` `v0.22.10002026111600` (`a90476e02630`) with backward-compatible storage and checkpoint updates.

- Bundled `gvx` `v0.22.10002026111600` (`a90476e02630`), which adds `size=SIZE` caps and bare `ephemeral` scratch mounts on `--mount type=volume` for `ext4lite` volumes (`gvx#247`, `gvx#256`).
- Included checkpoint manifest version `3` support for capped persistent volumes, explicit `UNSUPPORTED` rejection for ephemeral volume checkpoints, and the `systrap` `forkStub` unmap check cleanup.
- Updated `CheckpointController.DEFAULT_GVX_VERSION`, `scripts/source-lock.json`, `gradle/libs.versions.toml`, `fdroid/dev.gfire.termvr.yml`, and bundled license notices to `v0.22.10002026111600`.

### Automated `gvx` release tracking workflow

`scripts/sync-gvx-version.sh` and `.github/workflows/sync-gvx.yml` now track published upstream GitHub Releases and manage sync branches cleanly.

- Updated `scripts/sync-gvx-version.sh --fetch-latest` to query the latest published non-draft, non-prerelease GitHub Release and peel its commit via `git ls-remote`. Added a `versionCode` downgrade guard and `--from-tags` fallback.
- Updated `.github/workflows/sync-gvx.yml` to pass `SYNC_GH_PAT || GITHUB_TOKEN` to `actions/checkout` and the fetch step. The job skips redundant pushes when `chore/sync-gvx` matches and cleans stale branches when `main` is current.
- Added upstream release and diff links to sync PR descriptions and structured status summaries to `$GITHUB_STEP_SUMMARY`.

---

## [0.21.10012026111600]

This release (`versionCode` `302100010`) upgrades `gvx` to `v0.21.10012026111600` (`3178313725b5`). It fixes a startup panic on Android devices with 16 KB pages and sealed kernel mappings, and preserves full panic headers in startup errors.

### Sealed-page systrap fix and startup diagnostics

`TermVR` now boots `gvx` containers on kernels that seal `[vdso]` or `[vvar]` pages on 16 KB Android devices, and preserves both ends of early Go panic logs.

- Bundled `gvx` `v0.21.10012026111600` (`3178313725b5`), which aligns recursive `munmap` splits in `systrap` `unmapSegment` to `hostPageSize`, ignores `EPERM` during the early `forkStub` unmap sweep and optional `mseal`, guards VA gap bounds in `stubInit`, and tolerates restricted `seccomp` notification probes.
- Updated `SandboxSupervisor` (`StderrCaptureBuffer`) to retain the first 12 head lines and last 12 tail lines of `librungv.so` stderr, preventing truncation of the `panic:` message.
- Unwrapped `ExecutionException` causes in `SandboxSupervisor.startOrResumeSandbox` so container failure cards display the underlying cause directly.

---

## [0.21.10002026111600]

This release (`versionCode` `302100000`) upgrades `gvx` to `v0.21.10002026111600` (`b14cfd642f46`). It adds live NAT bandwidth shaping, Container Inspector block I/O telemetry, OCI image push, opt-in `-keep-blobs` retention for offline archive export, and fixes `image load -i`.

### Live NAT bandwidth limits and block I/O telemetry

`TermVR` now sets NAT transmit and receive rate limits at launch, across restore, and live over `gvx-ctrl/2`, and reports block I/O counters in the Container Inspector.

- Added `netTxBps`, `netRxBps`, and `autoPublishAddr` to `NetworkModeConfig`, emitting `--net-tx-bps`, `--net-rx-bps`, and `--auto-publish-addr` in `GvxCommandBuilder` for both `run` and `restore`.
- Extended `GvxControlClient` and `SandboxSupervisor.updateResourcesLive` to send and decode `net_tx_bps`, `net_rx_bps`, and `block_io` samples (`LiveBlockIoSample`).
- Added TX and RX `Mbps` limit inputs and live `Apply Bandwidth` controls in `NetworkScreen` (`Backend & WireGuard` tab).
- Added NAT cap and block I/O read and write totals to the Container Inspector `Stats` tab on `HomeScreen`.

### OCI image push, offline archive export, and gvx v0.21.10002026111600

Local OCI image workflows now support pushing images to registries, retaining original layer blobs for offline `.tar` exports, and loading `.tar` archives with the required `-i` flag.

- Added `CatalogRepository.pushImage` and `Push` dialog controls in `CatalogScreen` (`Local Images` card) running `rungv image push --host-dns 127.0.0.1:<port>`.
- Added opt-in `Keep original blobs` (`-keep-blobs`) in `CatalogScreen` for `pull` and `image load`, enabling offline `image save` without a registry connection.
- Fixed `CatalogRepository.buildImageLoadArgs` to pass `-i <tarPath>` to `rungv image load`.
- Bundled `gvx` `v0.21.10002026111600` (`b14cfd642f46`), including live NAT bandwidth shaping, block I/O stats, `-keep-blobs`, `image push`, and the `gvx-oci/v1.0` OCI registry `User-Agent`.

---

## [0.20.10012026111600]

This release (`versionCode` `302000010`) upgrades `gvx` to `v0.20.10012026111600` (`cc04f5ba8790`) and integrates the `gvx-ctrl/2` control surface. It adds live pause and resume, live resource and LAN-isolation updates, live loopback ports, live `ext4lite` mounts, and a Container Inspector.

### Live container control over `gvx-ctrl/2`

`TermVR` now communicates with running containers over the framed `gvx-ctrl/2` Unix socket protocol and passes `SCM_RIGHTS` descriptors under host lockdown.

- Added `GvxControlClient` in `:core:runtime-gvx` for binary-framed `GVX2` calls over owner-only Unix domain sockets. It calls lifecycle, resource, port, mount, process, and kernel-log RPCs.
- Added instant in-memory `Pause` and `Resume` (`SandboxRunState.PAUSED`) alongside checkpoint `Freeze`. Stopping a container sends graceful `SIGTERM`, and `RAW_PTY` containers read `sandbox.stats`.
- Added live RAM, CPU, and `PIDs` limit updates in `Edit Container`. Added live toggling of LAN egress isolation in `Network` without restarting containers.
- Enabled `--dynamic-ports`, opt-in `--auto-publish`, and `--allow-volume-attach` in `GvxCommandBuilder`. Loopback TCP/UDP rules and named `ext4lite` volumes apply live, while LAN binds require confirmation and staged restarts.
- Added the tabbed **Container Inspector** dialog in `HomeScreen` with `Stats`, `Processes` (`TERM`/`KILL`), `Mounts` (`RO`/`RW` remount and unmount), and `dmesg` (`Clear`).

### Release and automation workflow fixes

Release and daily sync workflows now index Android 16 APKs on F-Droid cleanly and tolerate restricted PR permissions in GitHub Actions.

- Included the PyPI `fdroidserver` fix on this release tag so `publish-fdroid-repo` indexes Android 16 (API 36) APKs on `gh-pages`.
- Updated `.github/workflows/sync-gvx.yml` to prefer `SYNC_GH_PAT` when configured. The workflow logs a warning with a compare URL when repository policy blocks PR creation.
- Added `scripts/publish-fdroid-repo.sh` to the shell syntax and mode audit in `.github/workflows/ci.yml`.

---

## [0.18.10022026111600.1]

This release (`versionCode` `301800021`) ships `TermVR` with the permanent release signing key (`SHA-256: 23FAEDB7...A5AC257`), adds signed Accrescent `.apks` split archives, and publishes the self-hosted F-Droid repository on GitHub Pages.

### Distribution channels and provenance

`TermVR` now distributes signed binaries across GitHub Releases, Obtainium, the self-hosted F-Droid repository, and Accrescent split APK sets with linked SLSA provenance.

- Added pinned `bundletool` `1.18.3` to `scripts/source-lock.json` and generated signed, 16 KB verified `TermVR-<tag>.apks` split APK sets alongside APKs and AABs.
- Extended `scripts/verify-16kb-alignment.sh` to inspect every nested split APK inside `.apks` archives.
- Added `scripts/publish-fdroid-repo.sh` and the `publish-fdroid-repo` release job to publish signed `arm64-v8a` builds to `https://termvr.gfire.dev/fdroid/repo`.
- Appended direct SLSA v1.0 attestation URLs and `gh attestation verify` commands to published GitHub Release notes, and added Obtainium and F-Droid links to `README.md`.
- Rotated to the permanent backed-up release signing certificate (`SHA-256: 23FAEDB73343D4AE06DD849FCF803B39DA420A11B32AF8EF4EA1F63E1A5AC257`); users upgrading from earlier pre-store APKs must uninstall and reinstall once.

---

## [0.18.10022026111600]

This release (`versionCode` `301800020`) upgrades the bundled `gvx` runtime to `v0.18.10022026111600` (`14ac125f1871`). It applies the `GHSA-424c-p5c6-jhhm` security fixes and `arm64`/`amd64` runtime hardening. It also defaults wake lock to indefinite until stopped and adds universal AAB packaging.

### Security hardening and gvx v0.18.10022026111600

The bundled `librungv.so` runtime applies 14 security fixes from `GHSA-424c-p5c6-jhhm` alongside `arm64` and `amd64` instruction decoding hardening from `gvx` `v0.18.10012026111600`.

- Disabled guest-initiated `hostfs` and `erofs` `mount(2)` calls, blocked write opens on read-only `hostfs` mounts after guest remount, and rejected `.gvx_meta` traversal paths (`GHSA-424c-p5c6-jhhm`).
- Hardened checkpoint bundle opens through `os.Root` regular-file checks, bounded OCI tar path length and sparse hole accounting, and capped VXLAN transmit recursion (`GHSA-424c-p5c6-jhhm`).
- Rejected aliased extent, journal, and xattr blocks in `ext4lite` and updated `klauspost/compress` to `v1.20.5` to fix `GO-2026-6865` (`GHSA-424c-p5c6-jhhm`).
- Hardened `arm64` 16 KB store emulation (`DC ZVA`, `CASP`, `STTR`) and `amd64` decoding and patching in `gvx` (`gvx#222`, `gvx#224`).

### Wake lock, F-Droid recipe, and AAB packaging

Container wake locks now stay active until all containers stop, and release pipelines ship a signed universal Android App Bundle alongside APKs.

- Set default wake lock duration to indefinite (`0` minutes) when `POST_NOTIFICATIONS` is granted, falling back to 30 minutes when revoked.
- Added an `Indefinite` quick action in `TerminalSessionDrawer` and updated wake lock unit tests.
- Fixed `fdroid/dev.gfire.termvr.yml` to remove unregistered `srclibs` and stage toolchains outside the source tree in `/tmp/termvr-toolchains`.
- Added `PRIVACY.md`, extended `verify-16kb-alignment.sh` to inspect `.aab` bundles, and packaged signed, SLSA-attested universal `TermVR-v0.18.10022026111600.aab` releases.

---

## [0.18.10002026111600]

This release (`versionCode` `301800000`) upgrades the bundled `gvx` runtime to `v0.18.10002026111600` (`a55326e735cb`) and syncs upstream gVisor to `release-20261116.0` (`83282de1f21f`). It adds guest loopback fallback for published ports, fixes an `ext4lite`/`hostfs` rename deadlock, and improves DNS relay and NAT TCP behavior.

### Runtime networking and gVisor release-20261116.0

Published container ports, guest DNS queries, and outbound NAT TCP connections now match Docker Engine behavior more closely inside `gvx`.

- Added guest loopback fallback (`127.0.0.1`) for inbound `-p` port forwarders (`gvx#219`) so host clients reach guest services bound only to `localhost`.
- Bundled opt-in `--auto-publish` dynamic loopback port publishing in `gvx` `v0.18.10002026111600` (`gvx#218`, `gvx#220`) under host lockdown.
- Forwarded guest TCP DNS queries directly over upstream TCP, inherited sanitized search domains, answered internal names authoritatively, and returned empty `NOERROR` for `AAAA` without IPv6 (`gvx#208`, `gvx#215`).
- Classified failed outbound NAT TCP host dials with `ECONNREFUSED`, `ETIMEDOUT`, `EHOSTUNREACH`, and `ENETUNREACH` ICMPv4 replies (`gvx#206`).

### Storage stability and store metadata

`ext4lite` and `hostfs` mounts now prevent mount-propagation deadlocks and avoid unnecessary volume growth during mapped write-backs.

- Fixed a lock-order deadlock between mount propagation and renames on `ext4lite` and `hostfs` using a leaf `ancestryMu` lock (`gvx#209`).
- Fixed `ext4lite` held-space allocation so mapped write-backs flush and reuse held blocks cleanly without extra sequential volume growth (`gvx#214`, `gvx#216`).
- Refreshed gallery screenshots and added Fastlane F-Droid phone screenshot metadata.

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
