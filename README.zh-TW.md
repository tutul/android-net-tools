# NetTools

[English](README.md) | **繁體中文**

一個自己做的 Android 網路工具箱：IP 資訊、Ping、Traceroute、iperf3，以及簡易的 SSH 指令執行器。

會做這個 app，是因為免費的網路工具 app 很難讓人放心。這裡的每一樣東西，不是在這個 repo 裡自己寫的，就是從官方原始碼自己編譯的，而且不會偷偷把資料傳回任何地方。

需要 Android 10（API 29）以上，開發和測試都在 Android 15 上進行。

## 功能

| 分頁 | 功能 |
|---|---|
| **IP** | 每個網路（Wi-Fi、行動網路、VPN）的本機位址、閘道和 DNS。另外會向兩個獨立的服務（Cloudflare 和 ipify）查詢你的公網 IPv4/IPv6，兩邊結果不一致時會特別標示。 |
| **Ping** | 執行系統內建的 `/system/bin/ping`，即時顯示輸出和最小/平均/最大延遲。次數填 `0` 代表持續 ping 到按停止為止。支援 IPv4 和 IPv6。 |
| **Traceroute** | 用 C（JNI）寫的原生 traceroute，透過不需要權限的 ICMP socket 實作。每跳送 3 個探測封包、顯示真實 RTT，支援 IPv4、IPv6，可選擇是否反查主機名稱。不需要 root。 |
| **iperf3** | 官方 iperf 3.22 客戶端。支援上傳/下載、TCP/UDP、平行連線數，可設定執行時間和資料量上限（哪個先到就先停），即時顯示每秒速度。內建公開 iperf3 伺服器清單，伺服器忙線時會自動換下一個埠。 |
| **SSH** | 在你的伺服器上一次執行一個指令，顯示標準輸出、錯誤輸出（紅色）和結束碼。支援金鑰和密碼登入。這不是完整的終端機：`vim`、`top` 這類全螢幕程式不能用，`cd` 也不會延續到下一個指令（請寫成 `cd 目錄 && 指令`）。 |

## 安全與隱私

- **沒有分析追蹤、沒有廣告，不會連到功能需要以外的第三方服務**：只有 IP 分頁會向 Cloudflare 和 ipify 查詢公網位址，其他功能都只連線到你自己輸入的主機。
- **SSH 金鑰**：在手機上產生 ed25519 金鑰。私鑰用 AES-GCM 加密，加密金鑰存放在 Android Keystore 裡，永遠不會離開這支手機。
- **SSH 密碼不會儲存**，每次連線時重新輸入。
- **SSH 主機金鑰**：第一次連線時顯示 SHA256 指紋讓你確認後才信任。之後每次連線，都會在送出任何密碼或金鑰簽章**之前**驗證。主機金鑰改變時會直接擋下連線，唯一的解法是你明確地「忘記」舊的主機金鑰。
- **不備份**：已關閉 Android 備份，以及換機時的資料傳輸。
- **iperf3** 是從 ESnet 官方 release 壓縮檔編譯的，編譯前會先比對 SHA-256（參見[重新編譯 iperf3](#重新編譯-iperf3)），沒有使用任何第三方預先編譯好的執行檔。
- release 版不可除錯，所以沒辦法透過 USB 讀取 app 的資料。

## 建置

### 需要的環境

- Android Studio 2026.2 以上，建置時使用它內建的 JDK（JBR）
- Android SDK Platform 37
- NDK `30.0.16248370` 和 CMake `4.1.2`（Android Studio → Settings → Android SDK → SDK Tools）

下面的命令列範例都使用 Android Studio 內建的 JDK：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

### debug 版

```bash
./gradlew assembleDebug
```

產出檔案：`app/build/outputs/apk/debug/app-debug.apk`。debug 版的 app ID 是 `today.tutul.nettools.debug`，名稱顯示為「NetTools Debug」，可以和 release 版同時安裝，不會影響 release 版的資料。

### release 版

release 版用你自己的金鑰簽章，金鑰設定放在 `keystore.properties`，這個檔案不會被 commit。建立金鑰和安裝的步驟請看 [RELEASE.md](RELEASE.md)。如果沒有這個檔案，建置出來的 release APK 會是未簽章的。

```bash
./gradlew assembleRelease
```

### 重新編譯 iperf3

`app/src/main/jniLibs/*/libiperf3.so` 這兩個 iperf3 執行檔已經 commit 進 repo，所以建置 app 不需要額外步驟。如果要重新編譯（例如修改了腳本裡的版本號）：

```bash
scripts/build-iperf3.sh
```

這個腳本會從 GitHub 下載 release 壓縮檔、比對 SHA-256，再用 NDK 交叉編譯出 `arm64-v8a`（手機用）和 `x86_64`（模擬器用）兩個版本。執行檔會偽裝成「原生函式庫」打包進 app，因為 Android 只允許 app 執行以這種方式安裝的檔案。

## 架設 iperf3 伺服器來測試

在同一個網路裡的電腦上執行：

```bash
iperf3 -s
```

然後在 iperf3 分頁填入那台電腦的區網 IP。TCP 測試會盡量把頻寬塞滿，所以用行動網路測試時，記得設定 **Max MB**，避免用掉太多流量。

## 專案結構

```
app/src/main/
├── cpp/traceroute.c          traceroute 用的 ICMP 探測（JNI）
├── java/today/tutul/nettools/
│   ├── MainActivity.kt       分頁
│   ├── net/                  網路邏輯，不含畫面
│   │   ├── LocalNetwork.kt   從 ConnectivityManager 取得本機位址
│   │   ├── PublicIp.kt       查詢公網 IP
│   │   ├── Ping.kt           包裝 /system/bin/ping
│   │   ├── Traceroute.kt     呼叫原生探測的 traceroute 迴圈
│   │   ├── Iperf.kt          執行 iperf3、解析 --json-stream
│   │   ├── Exec.kt           執行外部程式並逐行讀取輸出
│   │   └── ssh/              SSH 客戶端、金鑰儲存、伺服器清單
│   └── ui/                   每個分頁各一個 Compose 畫面 + ViewModel
├── jniLibs/                  libiperf3.so（由 scripts/build-iperf3.sh 產生）
└── keepRules/rules.keep      release 版的 R8 保留規則
scripts/build-iperf3.sh       用 NDK 交叉編譯官方 iperf3
RELEASE.md                    release 版的簽章與安裝步驟
```

## 第三方元件

| 元件 | 用途 | 授權 |
|---|---|---|
| [iperf3](https://github.com/esnet/iperf) 3.22（ESnet） | iperf3 分頁 | BSD 3-Clause |
| [JSch](https://github.com/mwiede/jsch) 2.28.8（mwiede 維護版） | SSH | BSD 類 |
| [Bouncy Castle](https://www.bouncycastle.org/) 1.86 | 讓 JSch 在 Android 上支援 ed25519 / curve25519 | MIT 類 |
| AndroidX / Jetpack Compose | 介面 | Apache 2.0 |

公網 IP 查詢使用 [Cloudflare 的 trace 頁面](https://1.1.1.1/cdn-cgi/trace)和 [ipify](https://www.ipify.org/)。公開 iperf3 伺服器清單來自 [iperf.fr](https://iperf.fr/iperf-servers.php)，這些都是別人架設、大家共用的伺服器，經常忙線。
