# 构建说明

## 目录

- `android/`：安卓源码
- `windows/`：Windows 源码
- `release/`：打包后的 APK、Windows 压缩包、发布 zip
- `assets/`：Windows 打包图标
- `scripts/`：防火墙辅助脚本
- `tools/`：WebSocket 协议测试

## 安卓

要求：

- JDK 17+
- Android SDK（compileSdk 36）
- Gradle 9.6.1 或兼容版本

```powershell
$env:ANDROID_HOME = "C:\Users\<你>\AppData\Local\Android\sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
gradle -p android assembleDebug
```

输出：

```text
android\app\build\outputs\apk\debug\app-debug.apk
```

## Windows

要求：

- JDK 25（或当前 `build.ps1` 支持的 JDK）
- PowerShell

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File windows\build.ps1 -Package
```

输出：

```text
dist\SmsBridge.jar
dist\desktop\SmsBridge\SmsBridge.exe
```

只编译不打包：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File windows\build.ps1
```

## 测试

```powershell
java -cp "windows\build\classes;windows\test" com.smsbridge.pc.test.TestHarness
```

## 发布 zip

`release/SmsBridge-release-1.3.2.zip` 包含：

```text
SmsBridge-release-1.3.2/
├─ SmsBridge.apk
├─ SmsBridge-win-x64.zip
├─ README.md
├─ LICENSE
├─ NOTICE.md
├─ BUGS.md
└─ PRINCIPLES.md
```

## 注意

- `local.properties`、`build/`、`.gradle/`、`dist/` 不在源码目录中，构建时生成；
- Windows 包自带 Java 运行时，约 48 MB；
- 安卓和 Windows 都无第三方依赖。
