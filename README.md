# 🌙 Nightwatch

**Your Cybercore hosts, on watch in your pocket.**

Nightwatch is the Android companion to [`nightwatchd`](https://github.com/cybercoretech/nightwatchd).
It shows each host's systemd units, compose stacks, Cyberdeck diagnostics, Mission Control
agents and alerts over your tailnet. With an operate token it can also start, stop and restart
things, behind your fingerprint or screen lock.

`Kotlin` · `Jetpack Compose` · `OkHttp` · `WorkManager` · `UnifiedPush`

## 👁️ Screens

| | |
|---|---|
| **🛰️ Watch** | Open alerts, failed units, units active, stacks up, the latest alerts and which services answer. |
| **⚙️ Units** | DaemonHall's watch list, filtered by *needs attention*, all, system or user. Each unit shows vitals, the allowed actions, the **live journal** and its history. |
| **📦 Stacks** | Dockspace's compose stacks: status, containers, ports and logs. `up` and `restart` for stacks; start, stop and restart for containers. |
| **🚨 Alerts** | Failures, self-restarts, stacks dropping, Argus integrity changes and VortexWall bans. Acknowledge them one by one or all at once. |
| **🧠 More** | Cyberdeck reports (Markdown) and scans, Mission Control, the activity timeline, hosts, push. |

The theme is CYBERGRID neon in JetBrains Mono, matching Holodrive Photos.

## 🛡️ Safety

- **The gateway decides.** The app only offers what `nightwatchd` allows: each unit carries its own
  `nw_actions`. A read-only token shows no action buttons, and the gateway would refuse them anyway.
- **Fingerprint or screen lock** (`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`) before anything changes on
  a host. Acknowledging an alert only asks for a tap.
- **Tokens are encrypted** with a per-host AES-GCM key in the Android Keystore. The app has backups
  turned off, and the window is `FLAG_SECURE`.
- **Tailnet only:** plain HTTP is allowed only to `*.ts.net` names (`network_security_config.xml`
  and `normalizeBaseUrl`); anything else must be HTTPS.
- **A push is never shown as-is.** It only wakes the app, which then fetches the real alerts from the
  host with its token. A forged push can't put text on your screen.

## 📲 Install

- 🤖 **F-Droid (auto-updates):** add the Cybercore repository at <https://cybercoretech.github.io/fdroid/>, then install *Nightwatch*.
- 💻 **From a computer** (phone on USB, USB debugging on; it verifies the SHA-256 and the signing certificate first):
  ```sh
  curl -fsSL https://raw.githubusercontent.com/cybercoretech/nightwatch-android-app/main/install.sh | sh
  ```
- 📦 **Manual:** download `nightwatch.apk` from the [latest release](https://github.com/cybercoretech/nightwatch-android-app/releases/latest). Obtainium works with `https://github.com/cybercoretech/nightwatch-android-app`.

🪪 Release signing certificate (SHA-256): `be90ca53b53c48c52a5a75a5d60191a8dd6e08219ff6074c7ce36d6db961b700`. Releases are built and signed on the maintainer's machine with `tools/release.sh X.Y.Z "note"` (settings in `tools/release.conf`); the key is never uploaded to GitHub.

## 📦 Setup

1. On each host: install `nightwatchd` and create a token:
   `nightwatchd token add "SGS25U" --operate`
2. In Nightwatch, enter `http://<host>.<tailnet>.ts.net:8769` and paste the token. Add more
   hosts under More → Hosts, and switch between them from the title.
3. Optional, for instant alerts:
   - install the **ntfy** app and point its default server at your own ntfy;
   - add that server to the host's `[push] allowed_hosts`;
   - in Nightwatch, go to More → Push and pick ntfy.

   Without push, the app checks every 15 minutes.

## 🔨 Build

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Lint treats warnings as errors.

- **Debug builds** (`dev.cybercore.nightwatch.debug`) can also reach the development machine from the
  emulator at `http://10.0.2.2:<port>`.
- **Emulator screenshots:** build with `-PnightwatchScreenshots=true` to lift `FLAG_SECURE`.
- **Release signing:** set all four `NIGHTWATCH_RELEASE_*` variables (store file, store password,
  key alias, key password).

Toolchain: SDK and NDK in `~/android`, JDK 21. See `~/Devspace/Android/README.md`.

## 🗂️ Code

| File | What |
|---|---|
| `Api.kt` | The `/v1` client, models, URL rule, SSE log reader |
| `Hosts.kt` | Host list and Keystore-backed token vault |
| `Model.kt` | `NightwatchModel`: state, navigation, loading, confirm-then-act |
| `Alerts.kt` | Alert sync, notifications, the WorkManager jobs |
| `Push.kt` | UnifiedPush, one instance per host |
| `App.kt`, `Screens.kt`, `Settings.kt`, `Widgets.kt` | Compose UI |
| `Markdown.kt` | The small Markdown renderer for Cyberdeck reports |

MIT © Cybercore Tech
