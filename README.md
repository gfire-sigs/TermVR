<p align="center">
  <img src="docs/assets/banner.svg" alt="TermVR banner showing Debian 13 container terminal and 120 Hz OpenGL ES 3.0 preview" width="100%">
</p>

<p align="center">
  <a href="https://github.com/gfire-sigs/TermVR/releases/latest">
    <img src="https://img.shields.io/github/v/release/gfire-sigs/TermVR?style=flat-square&color=F06E1E" alt="Latest Release">
  </a>
  <a href="https://github.com/gfire-sigs/TermVR/actions/workflows/ci.yml">
    <img src="https://img.shields.io/github/actions/workflow/status/gfire-sigs/TermVR/ci.yml?branch=main&style=flat-square&label=CI&color=1DC99A" alt="CI Status">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-Apache%202.0-1DC99A?style=flat-square" alt="Apache-2.0 License">
  </a>
  <img src="https://img.shields.io/badge/Android-12%2B%20(arm64%20%7C%20x86__64)-F06E1E?style=flat-square" alt="Android 12+ arm64 and x86_64">
</p>

<p align="center">
  <strong><a href="https://github.com/gfire-sigs/TermVR/releases/latest">Download Latest APK</a></strong>
  &nbsp;&middot;&nbsp;
  <strong><a href="docs/releases/v0.15.10002026110900.md">Release Notes</a></strong>
  &nbsp;&middot;&nbsp;
  <strong><a href="https://github.com/gfire-sigs/TermVR-Fonts">TermVR Mono NF</a></strong>
</p>

`TermVR` runs real Linux containers and development tools on unrooted Android phones, foldables, and tablets. It pairs a user-space Linux runtime with a 120 Hz GPU terminal and touch controls.

---

## Gallery

Each screen keeps container status, files, and network rules visible without nested menus. Tap any card below to inspect the interface layouts across phones and tablets.

<table>
  <tr>
    <td width="50%" align="center">
      <img src="docs/assets/gallery-terminal.svg" alt="GPU Terminal split panes with TermVR Mono NF Hangul, CJK, ligatures, and touch key bar" width="100%">
      <br>
      <strong>120 Hz GPU Terminal and TermVR Mono NF</strong><br>
      <sub>Split panes, Hangul and CJK glyphs, ligatures, box-drawing tables, and a 2-row key bar.</sub>
    </td>
    <td width="50%" align="center">
      <img src="docs/assets/gallery-containers.svg" alt="Containers dashboard running Debian 13 and alpine-svc concurrently with Unlimited defaults" width="100%">
      <br>
      <strong>Concurrent Multi-Container Control</strong><br>
      <sub>Run Debian 13 and Alpine side by side with unlimited defaults and one-tap Freeze.</sub>
    </td>
  </tr>
  <tr>
    <td width="50%" align="center">
      <img src="docs/assets/gallery-volumes-catalog.svg" alt="Files Volumes browser mounted at Debian /data and Catalog with Debian 13 first" width="100%">
      <br>
      <strong>Named Volumes and Distribution Catalog</strong><br>
      <sub>Browse ext4 volumes, edit workspace files in Android apps, and launch Debian, Ubuntu, Fedora, or Alpine.</sub>
    </td>
    <td width="50%" align="center">
      <img src="docs/assets/gallery-network-settings.svg" alt="Network port forwarding with LAN QR code and Settings Licenses viewer" width="100%">
      <br>
      <strong>Explicit Network Rules and Licenses</strong><br>
      <sub>Forward loopback or LAN ports with instant QR links, configure proxies, and inspect offline licenses.</sub>
    </td>
  </tr>
</table>

---

## Highlights

`TermVR` launches unmodified OCI container images matching your device architecture without root access. Default profiles allocate uncapped host CPU and memory while keeping background containers controllable.

- **Debian 13 Default with Unlimited Resources**: Launch `debian:trixie-slim` with uncapped CPU and memory by default. Set custom limits per container when needed.
- **Concurrent Multi-Container Execution**: Run multiple Linux environments together with multi-tab shells. Freeze or stop idle workloads in one tap.
- **Persistent Volumes and Android File Access**: Mount named ext4 volumes at custom paths, transfer archives, and edit workspace files in external Android apps.
- **120 Hz GPU Terminal (`TermVR Mono NF`)**: Render English, Hangul, CJK, ligatures, and Nerd Font icons via OpenGL ES 3.0. Use the touch key bar for chords.
- **Explicit Network, Proxy, and WireGuard Controls**: Forward `127.0.0.1` or `0.0.0.0` ports with QR links. Route traffic via `HTTP`, `HTTPS`, `SOCKS5`, `SOCKS4`, or WireGuard.
- **Prebuilt Docker and K3s Recipes**: Start an unprivileged Docker Engine (`docker:dind`) or Kubernetes cluster (`k3s`) on persistent volumes. Inspect licenses offline in `Settings > Licenses`.

---

## Install and Quick Start

Download the APK matching your device architecture from [GitHub Releases](https://github.com/gfire-sigs/TermVR/releases/latest) and verify its checksum. Each `arm64-v8a` APK supports both 4 KB and 16 KB page-size Android kernels on Android 12 and newer.

| Release File | Target Devices | Notes |
| :--- | :--- | :--- |
| `TermVR-<tag>-arm64-v8a.apk` | 64-bit ARM phones, foldables, and tablets | Recommended for Android 12+ devices on 4 KB or 16 KB kernels. |
| `TermVR-<tag>-x86_64.apk` | Android emulators and x86_64 hardware | Targets 64-bit x86 tablets, Chromebooks, and CI emulators. |
| `SHA256SUMS` | All downloads | Contains SHA-256 digests for verifying release APK integrity. |

1. Install the APK for your device architecture.
2. Open `TermVR` and tap `Launch` on the default `Debian` card.
3. Wait for the first image pull to finish.
4. Run `apt update` or switch to `Catalog` to launch `Ubuntu`, `Fedora`, `Alpine`, `Docker`, or `K3s`.
5. Tap `Freeze` on idle containers to save battery while keeping open shells intact.

> [!NOTE]
> Android limits background child processes across apps by default. Enable `Disable child process restrictions` in Android Developer Options when running multiple heavy containers at once.

---

## Build from Source

`TermVR` compiles from pinned open-source commits in [`scripts/source-lock.json`](scripts/source-lock.json) with zero proprietary SDKs or tracking libraries. Run these three commands on Linux with JDK 17 and Android NDK `28.0.13004108` installed to build a release APK.

```bash
./scripts/provision-toolchains-and-vendor.sh
./scripts/build-native-deps.sh --abi arm64-v8a,x86_64
./gradlew :app:assembleRelease -PtermvrAbis=arm64-v8a,x86_64
```

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) for offline F-Droid build rules and [`docs/designs/SITEMAP.md`](docs/designs/SITEMAP.md) for system architecture specifications.

---

## License

`TermVR` is licensed under the [Apache License, Version 2.0](LICENSE). The bundled `TermVR Mono NF` typeface uses the SIL Open Font License 1.1 (`OFL-1.1`), and you can inspect every dependency license inside `Settings > Licenses`.
