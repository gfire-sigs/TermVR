# Privacy Policy

`TermVR` (`dev.gfire.termvr`) is an open-source Android terminal and Linux container runtime published by `gfire-sigs`. This policy explains what data `TermVR` accesses, stores, and transmits on your device.

## 1. Data Collection and Telemetry

`TermVR` collects no personal information, analytics, crash reports, advertising identifiers, or usage telemetry. The application contains no proprietary SDKs, Google Play Services libraries, Firebase SDKs, or third-party tracking code.

## 2. On-Device Storage

All container images, persistent `ext4lite` volumes, checkpoints, shell settings, and workspace files stay inside your device's local storage.

- Container state and cached layers stay in Android app-private storage (`filesDir` and `cacheDir`).
- Outbound proxy passwords and WireGuard keys stay encrypted in Android Keystore and never leave your device.
- Workspace and Git worktree files exposed through `TermVrDocumentsProvider` use Android's Storage Access Framework (`SAF`) only when you select them in another app.

## 3. User-Initiated Network Connections

`TermVR` sends network requests only when you explicitly pull an OCI image, run a container that connects to the network, or test an outbound proxy.

- Image pulls contact the OCI registry you specify (such as Docker Hub or a custom registry URL).
- Outbound container traffic uses your device network, a proxy you configure, or a WireGuard tunnel you import.
- Inbound port forwards bind `127.0.0.1` by default and open `0.0.0.0` LAN ports only after explicit confirmation.

## 4. Android Permissions

`TermVR` requests only the permissions needed to run local Linux containers reliably.

| Permission | Purpose |
| :--- | :--- |
| `INTERNET` and `ACCESS_NETWORK_STATE` | Pull OCI container images, relay guest network traffic, and detect Wi-Fi changes for LAN port safety. |
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` | Keep running Linux containers and active PTY sessions alive while the app is in the background. |
| `POST_NOTIFICATIONS` | Display live CPU, memory, proxy, LAN, and wake-lock controls in the ongoing status notification. |
| `WAKE_LOCK` | Keep the CPU awake during long-running container tasks when you enable a wake lock. |

## 5. Contact

Report privacy questions or security concerns through the [TermVR Issue Tracker](https://github.com/gfire-sigs/TermVR/issues).
