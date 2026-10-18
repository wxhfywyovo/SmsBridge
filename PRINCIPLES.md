# 原理与架构

## 1. 总体目标

SmsBridge 解决一个很具体的问题：

> 手机收到短信/验证码，人在电脑前，不想拿手机，希望验证码自动出现在电脑剪贴板里。

所有通信都在局域网内完成，不依赖任何云端服务。

```text
┌──────────────┐        UDP 47824 广播发现        ┌──────────────┐
│   Android    │ ───────────────────────────────> │   Windows    │
│  SmsBridge   │                                  │  SmsBridge   │
│              │ <─────────────────────────────── │              │
│              │        TCP 47823 WebSocket       │              │
│              │ <──────────────────────────────> │              │
└──────┬───────┘                                  └──────┬───────┘
       │                                                 │
  短信/通知                                         剪贴板/历史
       │                                                 │
┌──────▼───────┐                                  ┌──────▼───────┐
│ 短信 App /   │                                  │ Windows 剪贴板│
│ 通知栏       │                                  │ history.jsonl│
└──────────────┘                                  └──────────────┘
```

## 2. 手机端（Android）

主要类在 `android/app/src/main/java/com/smsbridge/mobile/`：

| 类 | 作用 |
|---|---|
| `MainActivity` | 界面、权限、配对、诊断、日志 |
| `SyncService` | 前台服务：WebSocket 连接、队列、重连、保活 |
| `SmsNotificationListener` | 读取系统短信 App 的通知 |
| `SmsCode` | 本地提取 4-8 位验证码 |
| `Prefs` | 配对信息、设备 ID、队列、最近记录、事件 |
| `Discovery` | UDP 发现电脑 |
| `PairingInfo` | 解析剪贴板里的配对信息 |
| `BootReceiver` | 开机自启 |

### 2.1 短信来源

按优先级：

1. **通知读取**：系统收到短信时，系统短信 App 会在通知栏发出通知，
   `SmsNotificationListener` 拦截并解析正文（支持 MessagingStyle、正文塞标题等格式）；
   只读取系统短信 App 的通知，其他 App 一律不同步；
2. **手动诊断**：诊断信息 / 测试同步。

### 2.2 去重

- 通知读取可能收到同一条消息（多条渠道 / 系统重发）；
- 手机端用 `Prefs.hasRecentContent` / `hasQueuedContent` 按“发件人 + 正文”在短时间窗内去重；
- 电脑端用消息 `id` 去重，`history.jsonl` 里已存在的 id 不会重复写。

### 2.3 验证码提取

`SmsCode` 的规则：

- 关键词：`验证码`、`校验码`、`动态码`、`确认码`、`激活码`、`安全码`、`提取码`、`口令`、`code`、`verification code`、`passcode`、`otp`、日文 `認証番号/確認コード/認証コード`；
- 关键词后 0-24 个非数字字符内，取 4-8 位数字；
- 没有关键词时，只在很短的通知里兜底；
- WhatsApp 的 `123-456` 会先去掉分隔符；
- 电脑端会再次提取，最终以电脑端结果为准。

### 2.4 队列与重连

- 收到消息先写入 `Prefs` 队列，再尝试连接电脑；
- 电脑离线时队列保留，重新连上后补发；
- 电脑返回 `sms_ack` 后才从队列移除；
- 服务使用 `START_STICKY`、前台服务、CPU/Wi-Fi 保活锁、开机自启。

### 2.5 锁屏保活

- 前台服务类型 `connectedDevice`；
- `PARTIAL_WAKE_LOCK`；
- `WifiManager.WIFI_MODE_FULL_HIGH_PERF`；
- 请求忽略电池优化；
- vivo/小米/OPPO/华为等还需要系统自启动、后台运行白名单。

### 2.6 隐藏后台

- 开关开启后，离开 App 时调用 `finishAndRemoveTask()`，从最近任务移除；
- 服务继续前台运行；
- 打开系统权限页时会临时抑制自动隐藏，避免设置到一半 App 消失；
- Android 要求前台服务保留通知，所以通知栏仍可能有静默小图标。

## 3. 电脑端（Windows）

主要类在 `windows/src/com/smsbridge/pc/`：

| 类 | 作用 |
|---|---|
| `Main` | 入口、参数、单实例、启动 UI/服务 |
| `SingleInstance` | 本机回环端口单实例锁 |
| `BridgeService` | 配对、鉴权、收短信、写剪贴板、发现 |
| `WsServer` | 手写 RFC6455 WebSocket 服务端 |
| `Discovery` | UDP 广播电脑信息 |
| `Config` | PIN、端口、设备令牌、复制模式 |
| `ClipboardService` | AWT 剪贴板 + PowerShell 兜底 |
| `HistoryStore` | 最近 300 条历史，落盘 `history.jsonl` |
| `AutoStart` | HKCU Run 开机自启 |
| `Ui` | Swing 主界面、托盘、单条复制 |

### 3.1 单实例

- 第一个实例绑定 `127.0.0.1:47825-47839` 中的一个端口；
- 再次启动时，新进程向这些端口握手，发送 `SHOW` 或 `PING`；
- 已有实例收到 `SHOW` 后恢复窗口并置顶；新进程立即退出；
- `--tray` 的重复启动只发 `PING`，不弹窗；
- 端口都被其他程序占用时退回普通启动。

## 4. 配对

### 4.1 配对信息

电脑生成：

```text
SMSBRIDGE-PAIR|v=1|host=<电脑IP>|port=47823|pin=<6位PIN>|name=<电脑名>
```

手机可以从剪贴板读取，也可以手动输入 IP + PIN。

### 4.2 配对流程

```text
手机                          电脑
  │  WebSocket 连接 47823       │
  │ ─────────────────────────> │
  │  {"type":"pair",           │
  │   "deviceId":"...",        │
  │   "deviceName":"vivo...",  │
  │   "pin":"123456"}          │
  │ ─────────────────────────> │
  │                            │ 校验 PIN
  │  {"type":"pair_ok",        │
  │   "token":"<32字节随机>"}   │
  │ <───────────────────────── │
  │  保存 token                │
```

- PIN 有效期 10 分钟；
- 配对失败有尝试次数限制；
- 配对成功后，电脑保存 `deviceId -> token`，手机保存 token；
- 之后重连只发 `auth`，不再需要 PIN。

### 4.3 鉴权流程

```text
{"type":"auth","deviceId":"...","token":"..."}
→ {"type":"auth_ok","deviceName":"..."}
```

令牌错误会返回 `auth_err` 并断开。

## 5. 同步协议

WebSocket 文本帧，JSON：

### 5.1 短信

```json
{
  "type": "sms",
  "id": "消息唯一ID",
  "deviceId": "设备ID",
  "from": "发件人",
  "body": "短信完整正文",
  "ts": 1234567890123
}
```

电脑返回：

```json
{
  "type": "sms_ack",
  "id": "消息唯一ID",
  "code": "123456",
  "copied": true,
  "mode": "CODE"
}
```

- `code` 可能为空；
- `copied` 表示剪贴板是否写入成功；
- `mode` 是 `CODE`（只复制验证码）或 `MESSAGE`（复制整条）；
- 电脑写历史、写剪贴板后才回 `sms_ack`。

### 5.2 心跳

```json
{"type":"hb","ts":...}
→ {"type":"hb_ack","ts":...}
```

电脑每 15 秒 ping，45 秒无响应会清理连接。

### 5.3 解除配对

```json
{"type":"unpair","deviceId":"..."}
```

## 6. 自动发现

电脑每 2 秒广播：

```text
SMSBRIDGE1 {"app":"smsbridge","name":"WIN-XXX","port":47823,"ip":"10.168.0.4"}
```

- UDP 端口 47824；
- 同时发 `255.255.255.255` 和各网卡广播地址；
- 手机监听 47824，解析后显示可用电脑；
- 路由器 AP 隔离/防火墙可能拦截广播，此时手动填 IP。

## 7. 剪贴板

电脑端优先使用 AWT `Toolkit.getDefaultToolkit().getSystemClipboard()`；  
失败时退回 PowerShell：

```powershell
Set-Clipboard -Value ...
```

复制模式：

- `CODE`：只复制验证码；识别不到时复制整条正文；
- `MESSAGE`：始终复制整条正文。

最新卡片额外提供「复制完整短信」；历史记录点单条复制完整正文，点验证码标签只复制验证码。

## 8. 安全模型

- 只监听局域网，不连接外网；
- 配对：6 位 PIN + 32 字节随机令牌；
- 令牌比较使用常量时间比较；
- 电脑端只接受已配对设备发来的 `sms`；
- 手机端只把短信发给自己配对成功的电脑；
- 没有端到端加密：局域网抓包可能看到明文短信，敏感环境请勿使用；
- 配置、令牌、历史、日志都以明文保存在本机。

## 9. 为什么需要这些权限

| 权限 | 原因 |
|---|---|
| `POST_NOTIFICATIONS` | 前台服务通知 + Android 13 运行时 |
| 通知使用权 | 读取短信 App 的通知 |
| 电池优化白名单 | 锁屏后保持连接 |
| 开机自启 | 重启后自动恢复 |

## 10. 限制

这是一个个人项目，不是商业级产品：

- 没有云中转，跨网络不能用；
- 没有端到端加密；
- 没有大量机型适配；
- 没有自动化真机测试；
- 详细已知问题见 [BUGS.md](BUGS.md)。
