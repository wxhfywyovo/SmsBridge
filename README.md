# SmsBridge 短信小桥

手机收到短信/验证码后，通过局域网自动同步到 Windows 电脑并写入剪贴板。  
不经过外网、不需要云服务器，配对后自动重连。

> **重要声明**
>
> - **禁止商业用途**：本项目仅允许个人、学习、研究等非商业用途，详见 [LICENSE](LICENSE)。
> - **禁止植入后门**：禁止在本项目或衍生作品中加入后门、木马、远程控制、静默数据上传等恶意代码。
> - **本软件不含后门**：无遥测、无云端、无远程控制；代码全部开源可审计，详见 [NOTICE.md](NOTICE.md)。
> - **AI 生成说明**：本软件由 AI 生成，可能存在尚未发现的 Bug，请见谅；重要场景请先自行测试。
> - **作者是个懒人**：如果有 Bug，请自行修复或提交 PR，作者不保证及时修复。
> - **可能有 Bug**：已知问题和限制见 [BUGS.md](BUGS.md)，使用前请先阅读。

---

## 无后门声明

**本软件不含后门。**

- 不连接作者控制的服务器；
- 不上传短信、通讯录、位置、剪贴板到外网；
- 不包含远程控制、静默执行、权限提升、隐藏恶意行为；
- 全部代码开源，可自行审计；
- 只通过局域网与用户自己配对的电脑通信。

详细说明见 [NOTICE.md](NOTICE.md)。禁止任何人在衍生作品中植入后门。

---

## 功能

- 手机短信自动同步到电脑剪贴板
- 自动提取 4-8 位验证码；也可复制整条短信
- 6 位配对码 + 32 字节持久令牌，配对一次后自动重连
- 局域网 UDP 自动发现电脑，也可手动填 IP
- 电脑离线时手机先入队，连上后补发
- 支持短信广播 + 收件箱兜底 + 通知读取（RCS/应用号/WhatsApp/LINE/Telegram）
- QQ/微信通知黑名单：按包名 + 标题拦截，不同步
- Windows 单实例：再次打开只唤起已有窗口
- 托盘菜单、开机自启、锁屏保活、隐藏后台开关

## 仓库结构

```text
android/      安卓源码（Java，无第三方依赖）
windows/      Windows 源码（Java Swing，无第三方依赖）
assets/       Windows 打包图标
scripts/      防火墙辅助脚本
tools/        WebSocket 协议测试
release/      打包后的 APK + Windows 压缩包 + 发布 zip
```

## 直接使用

### 安卓

安装 `release/SmsBridge.apk`（Android 8.0+，当前 1.3.2 / versionCode 19）。

首次需要授予：

- 短信、读取短信、通知权限
- 通知使用权（读取 RCS/应用号/WhatsApp/LINE/Telegram 通知时需要）
- 忽略电池优化（锁屏保活）

### Windows

解压 `release/SmsBridge-release-1.3.2.zip`，里面包含：

```text
SmsBridge.apk
SmsBridge-win-x64.zip
README.md / LICENSE / NOTICE.md / BUGS.md / PRINCIPLES.md
```

再把 `SmsBridge-win-x64.zip` 解压，双击 `SmsBridge.exe`。自带 Java 运行时，不需要另装 Java。

第一次启动如弹出防火墙提示，请允许“专用网络”。程序未签名，SmartScreen 可能提示“未知发布者”。

### 配对

1. 手机和电脑连同一个 Wi-Fi；
2. 电脑点「复制配对信息」；
3. 手机 App 在前台会自动读取剪贴板，或手动输入电脑 IP + 6 位配对码；
4. 配对成功后打开「后台常驻同步」。

## 构建

### 安卓

```powershell
$env:ANDROID_HOME = "C:\Users\<你>\AppData\Local\Android\sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
gradle -p android assembleDebug
```

输出：`android/app/build/outputs/apk/debug/app-debug.apk`

### Windows

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File windows\build.ps1 -Package
```

输出：

- `dist/SmsBridge.jar`
- `dist/desktop/SmsBridge/SmsBridge.exe`

详细说明见 [BUILDING.md](BUILDING.md)。

## 原理

架构、协议、配对与同步流程见 [PRINCIPLES.md](PRINCIPLES.md)。

## 安全与隐私

- 只在局域网通信：UDP 47824 发现，TCP 47823 WebSocket 同步；
- 配对使用 PIN + 持久令牌；
- 无外网服务器、无遥测；
- 手机端调试日志会保存短信完整正文，排查后请清空；
- 电脑端配置和令牌以明文保存在本机 `%APPDATA%\SmsBridge`，请保护电脑账户。

## 许可

非商业使用许可，禁止商业用途，禁止植入后门。详见 [LICENSE](LICENSE) 和 [NOTICE.md](NOTICE.md)。
