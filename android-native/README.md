# WebSpeak 原生 Android 客户端（Java）

> **已弃用为本任务的交付目标。** 本目录连接的是 WebSpeak 网关（`/api/join-ticket` + `/ws/voice`），
> **不是**直连 TeamSpeak 服务器。直连 TeamSpeak 3 的版本见 [`../android-ts3/`](../android-ts3/README.md)。

这是 WebSpeak 的**原生 Java Android 客户端**：不使用 Kotlin、WebView、Capacitor 或任何第三方运行时库，
只依赖 Android Framework。它复用网页端 `web/src/voice/connection.ts` 与 `web/src/composables/useVoiceWebSocket.ts`
所实现的同一套网关协议——先申请一次性会话票据，再通过 `/ws/voice` 建立语音连接，以 20 ms PCM 帧上传麦克风，
在本地解码并混音其他成员的 Opus 音频。

应用界面为中文，包含连接表单、频道树、成员列表、聊天、事件日志、成员音量/私聊/戳一戳/移动等操作。

---

## 1. 与网页端一致的连接流程

| 步骤 | 网页端 | 原生客户端实现 |
| --- | --- | --- |
| 1. 申请票据 | `POST /api/join-ticket`，请求体含昵称、频道、服务器密码、邀请码、可选身份 | `net/HttpCall` 发送相同的 JSON，并显式设置 `Origin`（网关要求与 Host 同源） |
| 2. 建立语音通道 | `ws(s)://网关/ws/voice?ticket=…` | `net/WebSocketClient` 实现 RFC 6455 握手、掩码、分片、ping/pong 与关闭握手 |
| 3. 控制命令 | JSON `{type,payload,requestId}` | `core/Wire` 构建并做与服务端 `parseClientCommand` 相同的参数校验 |
| 4. 上行音频 | 二进制帧 = 960 个 Int16 采样（1,920 字节，48 kHz 单声道 20 ms） | `MicCapture` 用 `AudioRecord` 采集并按帧发送 |
| 5. 下行音频 | `[codec:1][clientId:2 BE][Opus…]` | 解析出 clientId，`OpusSpeaker` 用 MediaCodec 解码，`AudioMixer` 混音 |
| 6. 心跳 | 浏览器自动回 pong | `WebSocketClient` 在读取线程立即回 pong（网关每 30 s ping，两次未回即断开） |

### 支持的服务端消息

`core/ServerMessages` 按网页端 `src/shared/server-messages.ts` 的必要字段做校验，未知类型（如屏幕共享）会被安全忽略：

- `connected`、`serverInfo`、`channelList`、`memberEnter`、`memberLeave`、`memberAvatar`
- `chatMessage`、`pokeReceived`、`serverEvent`、`channelSwitched`、`whisperTargets`
- `commandCompleted`、`error`、`disconnected`、`reconnecting`、`reconnected`
- `connectionFailed`、`reconnectFailed`、`audioError`、`webrtcError`
- `audioStats`、`latencyPong`、`voiceActivity`（后三者保留解析，当前界面不展示）

### 支持的命令

`switchChannel`、`moveClient`、`sendTextMessage`、`sendServerMessage`、`sendPrivateMessage`、`poke`、
`setAway`、`setWhisperTargets`、`setWhisperActive`、`setMicrophoneMuted`、`setMemberVolume`。
其中 `switchChannel` 由服务端 `channelSwitched` 消息确认，其余带 `requestId` 的命令由 `commandCompleted`
确认；8 秒（可配置）未确认即判定超时。

### 关闭码映射

`IDENTITY_IN_USE`、`JOIN_TICKET_REQUIRED`、`SERVER_REJECTED`、`GATEWAY_NETWORK_LOST`、
`ACCELERATION_UNAVAILABLE` 等关闭码/失败码都映射为可操作的中文提示，与网页端 `CONNECTION_FAILURE_MESSAGES`
的语义一致。

---

## 2. 构建

### Android Studio

用 Android Studio 打开 `android-native/` 目录，等待 Gradle 同步后运行 `app`。

### 命令行（已验证）

```powershell
$env:JAVA_HOME = "C:\Tools\jdk-21"
$env:ANDROID_HOME = "C:\Android\sdk"
Set-Location android-native
.\gradlew.bat assembleDebug
```

产物：`android-native/app/build/outputs/apk/debug/app-debug.apk`。
本次交付也把已构建的调试包复制到了 `android-native/dist/WebSpeakNative-1.0-debug.apk`
（`dist/` 与 Gradle 构建目录均已在 `.gitignore` 中忽略）。

构建配置：

- `compileSdk 37`、`targetSdk 35`、`minSdk 26`（Android 8.0+，`java.util.Base64`、`AudioTrack.Builder` 等均可用）
- Java 17 源码级别，但代码保持 Java 8 语法，便于低版本工具链
- 没有 `runtimeOnly`/`implementation` 依赖；`WebSocket`、HTTP、JSON 全部自带
- 本机验证工具链：JDK 21、Android SDK Platform 37 / Build-Tools 37.0.0、Gradle 9.8.0、AGP 9.4.1

### 桌面协议自检（无需 Android SDK）

```powershell
powershell -ExecutionPolicy Bypass -File android-native\tools\verify-core.ps1
```

该脚本用桌面 JDK 编译 `core`、`net`、`voice` 三个纯 Java 包，并运行 `tools/selftest/SelfTest.java`：
本地假网关通过真实 HTTP + 原始 WebSocket 服务端验证票据请求头（含 `Origin`）、握手、
PCM 上行、Opus 下行、非 Opus 帧丢弃、命令确认、超时、ping/pong 与关闭码。**共 90 项检查，全部通过。**

---

## 3. 代码结构

```
app/src/main/java/com/webspeak/nativeclient/
├── core/                 纯 Java，可在桌面 JVM 编译/测试
│   ├── Json.java         自带的 JSON 读写（不依赖 org.json）
│   ├── ServerMessages.java 服务端消息解析与校验
│   ├── Wire.java         客户端命令构建与参数校验
│   ├── Directory.java    频道树投影（缺失父级/环提升为根）、成员归属
│   ├── ErrorText.java    失败码/关闭码 → 中文提示
│   └── SessionConfig.java 网关地址规范化、票据请求体、ws URL 构建
├── net/                  纯 Java
│   ├── HttpCall.java     原始 HTTP/1.1 客户端（保证 Origin 头不被 JDK 丢弃）
│   ├── WebSocketFrames.java RFC 6455 帧编解码
│   ├── WebSocketClient.java 握手、读写线程、ping/pong、关闭
│   └── Tls.java          默认证书校验；可选自签名信任
├── voice/                纯 Java
│   ├── VoiceSession.java 票据 → 连接 → 命令/事件/音频/重连的编排
│   ├── AudioBridge.java  音频边界接口（Android 实现 / 测试假实现）
│   └── VoiceState.java   面向 UI 的不可变状态快照
└── android/              Android 专属
    ├── WebSpeakApp.java  进程级 Session 所有权与 UI 回调分发
    ├── MainActivity.java 连接表单、权限、网关信息探测
    ├── RoomActivity.java 房间界面（频道/成员/聊天/事件）
    ├── AudioEngine.java  麦克风 + 解码 + 混音的资源边界
    ├── MicCapture.java   AudioRecord 48 kHz 单声道采集
    ├── OpusSpeaker.java  每成员 MediaCodec Opus 解码线程
    └── AudioMixer.java   20 ms 混音与 AudioTrack 输出
```

设计要点：

- **进程级会话**：旋转屏幕、表单与房间之间切换都不会重建 socket。
- **回调统一回到主线程**：`Dispatcher` 由 `MainThreadDispatcher` 实现，`VoiceSession` 的公开回调都在主线程。
- **资源所有权明确**：`VoiceSession` 只通过 `AudioBridge` 启停音频；`AudioEngine` 拥有 `AudioRecord`/`MediaCodec`/`AudioTrack`，
  会话断开或失败时统一释放。
- **生命周期**：房间不可见时停止麦克风采集（`VoiceSession.setForeground(false)`），避免后台录音；
  返回键会确认断开，网关不会留下孤儿 TeamSpeak 客户端。

---

## 4. 功能与限制

已实现：

- 加入/断开、昵称、频道名、服务器密码、邀请码、TeamSpeak 地址（开放模式）
- 粘贴/复用 TeamSpeak 身份字符串（`保持身份`），网关会在 `connected.identity` 中回传更新后的身份
- 频道树、成员列表（含静音/离开/频道指挥标记、说话指示）、频道切换（支持频道密码）
- 频道/服务器/私聊文字消息，输入长度与网关校验一致（500 字符）
- 戳一戳、成员音量（0–400%，按 UID 持久化）、移动成员、成员资料
- 服务端事件与本地提示日志
- 麦克风静音、回声消除音频源（`VOICE_COMMUNICATION`）、每成员独立 Opus 解码与本地混音
- `http://` 与 `https://` 网关；自签名证书需显式勾选（默认关闭）
- 网关信息探测（`GET /api/public-config`，展示站点名、访问模式、默认服务器）

明确不支持（与本次“原生 Java”范围一致）：

- **WebRTC 音频传输**：原生 WebRTC 需要 NDK/libwebrtc；本客户端始终使用兼容的 WebSocket PCM 上行 + Opus 下行，
  网关在没有收到 `webrtcOffer` 时也会继续走该路径。
- **屏幕共享**：网关的屏幕共享依赖 WebRTC，客户端不实现。
- **头像显示**：`channelList`/`memberAvatar` 中的 base64 头像会被解析后丢弃（避免大图占用内存）。
- **私语 UI**：`whisperTargets` 状态会接收和显示人数，但界面未提供目标选择。
- **后台/锁屏通话**：没有前台服务；房间不可见时停止麦克风，进程被系统回收后会话结束。
- **多设备/真机音频验证**：见下节。

---

## 5. 验证状态（务必阅读）

| 项目 | 状态 |
| --- | --- |
| 纯 Java 协议层 | 桌面 JDK 编译通过；90 项自检全部通过（含真实 HTTP/WS 交互） |
| Android 编译/打包 | `./gradlew assembleDebug` 通过，生成可安装 debug APK |
| Android Lint | 通过（仅有 i18n/autofill/RTL 等警告，无 error） |
| 真机连接真实网关 | **未验证**：本机没有连接 Android 设备/模拟器，也没有可用的 TeamSpeak 服务器 |
| 真机麦克风/扬声器 | **未验证**：AudioRecord/AudioTrack 参数按网关契约实现，仍需在设备上听测 |
| Opus 解码 | 依赖设备 MediaCodec 的 `audio/opus` 解码器（AOSP 软件解码器通常存在）；解码失败会以提示形式降级，聊天仍可用 |

因此，本客户端应视为“协议完整、可编译、可安装”的原生实现；音频链路的真实效果必须按仓库约定在真实设备上单独验证，
不能用编译成功或 HTTP 健康检查替代音频测试。

---

## 6. 常见问题

- **`ORIGIN_REJECTED` / 请求来源不受信任**：`/api/join-ticket` 要求 `Origin` 与网关看到的 `Host` 完全一致。
  请填写用户实际访问的对外地址（含端口、协议），不要把反向代理后的内网地址填进来；网关必须部署在根路径，
  不能带 `/voice` 之类的子路径。
- **`NOT_INITIALIZED`**：网关尚未在 `/admin` 完成初始化。
- **`TLS_ERROR` / 证书校验失败**：自建网关常用自签名证书；确认地址正确后，才勾选“信任自签名证书”。
- **昵称被拒绝**：TeamSpeak 要求 3–30 个字符，客户端会提前校验；服务器仍可能因其他规则拒绝。
- **没有声音**：先确认房间状态为“已连接”，再检查系统媒体音量与设备是否支持 Opus 解码；
  解码/播放失败会在“事件”页给出提示。麦克风权限被拒绝时仍可收听和收发文字。
- **频道密码 / 服务器密码**：错误提示会说明需要密码或密码错误；修改后重新连接即可。

安全提示：

- 表单与身份字符串保存在应用私有 `SharedPreferences`（`allowBackup=false`），未额外加密。
- 为兼容自建网关允许明文 `http://`；公网部署建议使用 HTTPS。
- “信任自签名证书”会关闭证书与主机名校验，仅建议在受控网络中临时使用。
- 屏幕共享/WebRTC 未实现，因此不存在摄像头/屏幕采集权限需求。
