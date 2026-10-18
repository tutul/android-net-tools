# 打包 release 版並安裝到手機

release 版和 debug 版的差別：檔案比較小（約 8 MB，debug 版約 37 MB）、執行比較快、**無法被除錯讀取資料**，而且是用你自己的金鑰簽章。

> debug 版的 app ID 是 `today.tutul.nettools.debug`，名稱顯示為「NetTools Debug」；release 版是 `today.tutul.nettools`，名稱是「NetTools」。兩個版本可以同時裝在手機上，開發時裝 debug 版不會影響你日常用的 release 版。

## 1. 建立簽章金鑰（只需要做一次）

金鑰請放在**專案資料夾以外**的地方，例如 `~/keys/`：

```bash
mkdir -p ~/keys && chmod 700 ~/keys
```

```bash
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -genkeypair -v -keystore ~/keys/nettools-release.jks -alias nettools -keyalg RSA -keysize 4096 -validity 36500
```

- 指令會要你設定密碼，接著問姓名、組織等資料。這些資料只會寫進憑證裡，隨便填或直接按 Enter 都可以。
- 最後確認時輸入 `y`。

⚠️ **請立刻備份兩樣東西**，例如存進 1Password：
1. 金鑰檔 `~/keys/nettools-release.jks`
2. 剛才設定的密碼

**只要其中一樣不見了，就再也無法更新手機上的 app**，只能整個移除重裝，而 app 裡的資料（SSH 金鑰、伺服器清單）也會一起消失。

## 2. 告訴 Gradle 金鑰在哪裡

在專案根目錄（就是 `RELEASE.md` 所在的資料夾）建立 `keystore.properties`，內容如下，密碼換成你自己的：

```properties
storeFile=/Users/tutu/keys/nettools-release.jks
storePassword=你的密碼
keyAlias=nettools
keyPassword=你的密碼
```

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
1. 到 SSH 分頁按「Generate key」產生新金鑰，再按「Copy public key」。
2. 到每台伺服器的 `~/.ssh/authorized_keys`：
   - **刪掉舊的那行**（結尾是 `nettools@XQ-DQ72` 的那一行），因為舊私鑰已經不在了，留著沒有用
   - 加入新複製的那行
3. 重新新增伺服器。第一次連線時，一樣要確認主機指紋。

## 之後要更新 app

用同一把金鑰重新執行第 3 步，接著直接覆蓋安裝，資料會保留：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```
