# 打包 release 版並安裝到手機

release 版和 debug 版的差別：檔案比較小（約 8 MB，debug 版約 37 MB）、執行比較快、**無法被除錯讀取資料**，而且是用你自己的金鑰簽章。

> debug 版的 app ID 是 `today.tutul.nettools.debug`，名稱顯示為「NetTools Debug」；release 版是 `today.tutul.nettools`，名稱是「NetTools」。兩個版本可以同時裝在手機上，開發時裝 debug 版不會影響你日常用的 release 版。

## 1. 建立簽章金鑰（只需要做一次）

金鑰請放在**專案資料夾以外**的地方，例如 `~/app_keys/`：

```bash
mkdir -p ~/app_keys && chmod 700 ~/app_keys
```

```bash
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -genkeypair -v -keystore ~/app_keys/nettools-release.jks -alias nettools -keyalg RSA -keysize 4096 -validity 36500
```

- 指令會要你設定密碼，接著問姓名、組織等資料。這些資料只會寫進憑證裡，隨便填或直接按 Enter 都可以。
- 最後確認時輸入 `y`。

⚠️ **請立刻備份兩樣東西**，例如存進 1Password：

1. 金鑰檔 `~/app_keys/nettools-release.jks`
2. 剛才設定的密碼

**只要其中一樣不見了，就再也無法更新手機上的 app**，只能整個移除重裝，而 app 裡的資料（SSH 金鑰、伺服器清單）也會一起消失。

## 2. 告訴 Gradle 金鑰在哪裡

在專案根目錄（就是 `RELEASE.md` 所在的資料夾）建立 `keystore.properties`，內容如下，密碼換成你自己的：

```properties
storeFile=/Users/你的使用者名稱/app_keys/nettools-release.jks
storePassword=你的密碼
keyAlias=nettools
keyPassword=你的密碼
```

`storeFile` 必須是**完整路徑**，不能寫 `~/app_keys/...`，因為 Gradle 不會把 `~` 展開成你的家目錄。可以用 `echo ~/app_keys/nettools-release.jks` 查出完整路徑。

（用 keytool 預設的 PKCS12 格式時，`storePassword` 和 `keyPassword` 是同一個密碼。）

把這個檔案設成只有你自己能讀：

```bash
chmod 600 keystore.properties
```

這個檔案已經寫在 `.gitignore` 裡，**不會被 commit 進 git**。

## 3. 建置 release 版

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

產出的檔案在 `app/build/outputs/apk/release/app-release.apk`。如果檔名是 `app-release-unsigned.apk`，代表沒有讀到 `keystore.properties`，請回頭檢查第 2 步。

## 4. 安裝到手機（第一次）

手機目前裝的舊 debug 版，app ID 和 release 版相同，但簽章不同，所以**必須先移除**：

```bash
adb uninstall today.tutul.nettools
```

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

## 5. 安裝後要重新設定 SSH

舊版的資料已經隨著移除一起清掉了，所以：

1. 到 SSH 分頁按「Generate key」產生新金鑰。
2. 把公鑰傳到電腦上。公鑰是**公開資訊**，用什麼管道傳都可以，例如按「Copy public key」後用 LINE 傳給自己或寄 email。手機接著 USB 時，也可以直接從手機畫面讀出來：

   ```bash
   adb shell uiautomator dump /sdcard/ui.xml >/dev/null && adb shell cat /sdcard/ui.xml | grep -oE 'ssh-ed25519 [A-Za-z0-9+/=]+ [^"]+'
   ```

3. 從電腦把新公鑰加到每台伺服器（`user@server` 換成你自己的）：

   ```bash
   echo '這裡貼上新公鑰那一整行' | ssh user@server 'cat >> ~/.ssh/authorized_keys'
   ```

4. 刪掉舊公鑰（如果之前有加過）。⚠️ 新舊金鑰的註解**都是** `nettools@手機型號`，所以要用金鑰內容來分辨，不能看註解。把下面的 `舊金鑰中間的一段` 換成舊公鑰 `AAAA…` 裡獨一無二的一段，指令會順便留一份備份 `authorized_keys.bak`：

   ```bash
   ssh user@server "sed -i.bak '\#舊金鑰中間的一段#d' ~/.ssh/authorized_keys"
   ```

5. 確認伺服器上的金鑰。列出的指紋要和你用 `ssh-keygen -lf` 對新公鑰算出來的一致：

   ```bash
   ssh user@server 'ssh-keygen -lf ~/.ssh/authorized_keys'
   ```

6. 回到手機重新新增伺服器。第一次連線時，一樣要確認主機指紋。

## 之後要更新 app

用同一把金鑰重新執行第 3 步，接著直接覆蓋安裝，資料會保留：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```
