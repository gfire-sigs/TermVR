# Changelog

This file records notable user-visible, build, and specification changes across `TermVR` releases.

---

## [Unreleased]

This section tracks changes staged on `main` after `0.15.10002026110900`.

---

## [0.15.10002026110900]

This release delivers Milestones `M1` through `M5` and aligns `TermVR` release versions (`versionCode` `261109000`) with upstream `gvx` `v0.15.10002026110900`. Every native, guest, runtime, UI, and reproducible build module matches the master specification.

### Features

- Implemented `:core:model` domain types and `:core:data` DataStore, Keystore credential storage, and recipe catalogs (`M1`, `M3`).
- Implemented `:core:terminal-vt` JNI bridge (`libtermvr_vt.so`), FreeType/HarfBuzz font shaping, shelf glyph atlases, and OpenGL ES 3.0 rendering (`M1`).
- Implemented `:core:terminal-ui` `SurfaceView` host, `Choreographer` frame loop, Hangul/CJK `TerminalInputConnection`, and two-row `AuxiliaryKeyBar` (`M1`).
- Implemented `:core:runtime-gvx` foreground supervisor, `GvxCommandBuilder`, `LoopbackDnsProxy`, `LoopbackProxyBridge`, `MuxSessionClient`, and `CheckpointController` (`M2`, `M3`).
- Implemented `guest/termvr-agent` static Go PID 1 multiplexer, PTY allocator, gated host RPCs, and `SO_ORIGINAL_DST` local proxy (`M2`, `M3`).
- Implemented `:app` adaptive Compose navigation shell, `Home`, `Terminal`, `Catalog`, `Network`, `Files`, and `TermVrDocumentsProvider` (`M4`).

### Build and Release

- Aligned `versionName` and 32-bit `YYMMDDGGR` `versionCode` (`261109000`) across `scripts/source-lock.json`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, and `fdroid/dev.gfire.termvr.yml` (`M5`).
- Exposed `BuildConfig.GVX_VERSION` and `BuildConfig.GVX_COMMIT` from the Gradle version catalog (`M5`).
- Added `scripts/sync-gvx-version.sh`, `.github/workflows/sync-gvx.yml`, and Fastlane changelog `261109000.txt` (`M5`).
- Enforced version parity checks in `.github/workflows/ci.yml` and `.github/workflows/release-slsa.yml`, plus `:core:model:test` in CI (`M5`).

### Documentation

- Initialized the Milestone 0 (`M0`) project skeleton, pinned source lockfile, and `EGM` master specification (`docs/designs/0001-termvr-system-design.md`).
- Updated `README.md`, `CHANGELOG.md`, and `docs/designs/0001-termvr-system-design.md` with the `gvx`-aligned versioning contract.
- Vendored 41 `dstack-skills` engineering skills under `.agents/skills/` for JNI, Zig, Go, Compose UI, security, and ASD-STE100 workflows.
