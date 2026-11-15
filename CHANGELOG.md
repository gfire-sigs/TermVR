# Changelog

This file records notable user-visible, build, and specification changes across `TermVR` releases.

---

## [Unreleased]

This section tracks changes staged on `main` for the upcoming `TermVR` release.

### Features

- Initialized the Milestone 0 (`M0`) multi-module Gradle and Go project skeleton (`:app`, `:core:model`, `:core:data`, `:core:terminal-vt`, `:core:terminal-ui`, `:core:runtime-gvx`, and `guest/termvr-agent`).
- Added commit-pinned upstream source lockfile (`scripts/source-lock.json`), F-Droid recipe metadata (`fdroid/dev.gfire.termvr.yml`), and Fastlane store descriptions.
- Published the Elephant-Goldfish Model (`EGM`) master architecture and zero-decision implementation specification (`docs/designs/0001-termvr-system-design.md`), including `HTTP`/`HTTPS`/`SOCKS5`/`SOCKS4` proxy bridging.
