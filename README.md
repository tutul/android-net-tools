# NetTools

**English** | [繁體中文](README.zh-TW.md)

A small, self-built Android network toolbox: IP info, ping, traceroute,
iperf3 and a simple SSH command runner. It exists because free network-tool
apps are hard to trust; everything here is either written in this repo or
built from official upstream source, and nothing phones home.

Requires Android 10 (API 29) or newer. Developed and tested on Android 15.

## Features

| Tab | What it does |
|---|---|
| **IP** | Local addresses, gateways and DNS for every network (Wi-Fi, cellular, VPN), plus your public IPv4/IPv6 as seen by two independent echo services (Cloudflare and ipify), flagged if they disagree. |
| **Ping** | Runs the system's `/system/bin/ping` with live output and min/avg/max stats. Count `0` pings until stopped. IPv4 or IPv6. |
| **Traceroute** | Native (C/JNI) traceroute over an unprivileged ICMP socket: 3 probes per hop with real RTTs, IPv4 and IPv6, optional reverse DNS. No root needed. |
| **iperf3** | The official iperf 3.22 client. Upload or download, TCP or UDP, parallel streams, a duration and a data cap (whichever comes first), live per-second throughput. Includes a list of public iperf3 servers and rotates through their ports when one is busy. |
| **SSH** | Runs one command at a time on your servers and shows stdout, stderr (red) and the exit status. Key or password login. Not a terminal: no `vim`/`top`, and `cd` doesn't carry over between commands (use `cd dir && cmd`). |

## Security and privacy

- **No analytics, no ads, no third-party services** beyond what a feature
  needs: the IP tab asks Cloudflare and ipify for your public address;
  everything else only talks to the hosts you enter.
- **SSH keys**: the app generates an ed25519 key on the phone. The private key
  is encrypted with an AES-GCM key held in the Android Keystore, which never
  leaves the device.
- **SSH passwords are never stored**; you type them on each connection.
- **SSH host keys** are trusted on first use after showing the SHA256
  fingerprint, and verified during key exchange, before any password or key
  signature is sent. A changed host key blocks the connection; the only way
  past it is to explicitly forget the old key.
- **No backups**: Android backup and device-to-device transfer are disabled.
- **iperf3** is compiled from the official ESnet release tarball after
  checking its SHA-256 (see [Rebuilding iperf3](#rebuilding-iperf3)); no
  prebuilt third-party binaries.
- Release builds are not debuggable, so app data can't be read over USB.

## Building

### Requirements

- Android Studio 2026.2 or newer. Its bundled JDK (JBR) is used for builds.
- Android SDK Platform 37
- NDK `30.0.16248370` and CMake `4.1.2` (Android Studio → Settings → Android
  SDK → SDK Tools)

The command-line examples below use Android Studio's JDK:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

### Debug build

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Debug builds use the app
ID `today.tutul.nettools.debug` and show as "NetTools Debug", so they install
alongside the release app without touching its data.

### Release build

Release builds are signed with your own key, read from a `keystore.properties`
file that is never committed. See [RELEASE.md](RELEASE.md) (in Traditional
Chinese) for creating the key and installing. Without that file the release
APK is built unsigned.

```bash
./gradlew assembleRelease
```

### Rebuilding iperf3

The iperf3 binaries in `app/src/main/jniLibs/*/libiperf3.so` are committed so
the app builds without extra steps. To rebuild them (for example after
bumping the version in the script):

```bash
scripts/build-iperf3.sh
```

The script downloads the release tarball from GitHub, verifies its SHA-256,
and cross-compiles it with the NDK for `arm64-v8a` (phones) and `x86_64`
(emulators). The binary ships as a "native library" because Android only
lets apps execute files installed that way.

## Testing an iperf3 server

Run a server on a computer on the same network:

```bash
iperf3 -s
```

Then enter that computer's LAN IP in the iperf3 tab. A TCP test sends as fast
as the network allows, so on mobile data keep **Max MB** set to avoid using a
lot of data.

## Project layout

```
app/src/main/
├── cpp/traceroute.c          ICMP probe for traceroute (JNI)
├── java/today/tutul/nettools/
│   ├── MainActivity.kt       Tabs
│   ├── net/                  Network logic, no UI
│   │   ├── LocalNetwork.kt   Local addresses from ConnectivityManager
│   │   ├── PublicIp.kt       Public IP lookups
│   │   ├── Ping.kt           Wrapper around /system/bin/ping
│   │   ├── Traceroute.kt     Traceroute loop over the native probe
│   │   ├── Iperf.kt          Runs iperf3, parses --json-stream
│   │   ├── Exec.kt           Runs a process and streams its output
│   │   └── ssh/              SSH client, key storage, saved servers
│   └── ui/                   One Compose screen + ViewModel per tab
├── jniLibs/                  libiperf3.so (built by scripts/build-iperf3.sh)
└── keepRules/rules.keep      R8 rules for release builds
scripts/build-iperf3.sh       Cross-compiles official iperf3 with the NDK
RELEASE.md                    Signing and installing a release build
```

## Third-party components

| Component | Use | License |
|---|---|---|
| [iperf3](https://github.com/esnet/iperf) 3.22 (ESnet), including cJSON | iperf3 tab; binaries committed in `jniLibs` | BSD 3-Clause (cJSON: MIT), see [third_party/iperf3/LICENSE](third_party/iperf3/LICENSE) |
| [JSch](https://github.com/mwiede/jsch) 2.28.8 (mwiede fork) | SSH | BSD-style |
| [Bouncy Castle](https://www.bouncycastle.org/) 1.86 | ed25519 / curve25519 for JSch on Android | MIT-style |
| AndroidX / Jetpack Compose | UI | Apache 2.0 |

Public IP lookups use [Cloudflare's trace page](https://1.1.1.1/cdn-cgi/trace)
and [ipify](https://www.ipify.org/). The public iperf3 server list comes
from [iperf.fr](https://iperf.fr/iperf-servers.php); these are shared servers
run by others and are often busy.

## License

NetTools is released under the [MIT License](LICENSE). Third-party
components keep their own licenses, listed above.
