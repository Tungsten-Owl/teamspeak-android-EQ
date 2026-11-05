# teamspeak-android-EQ

**TeamSpeak 3 原生 Android 客户端 · 语音 EQ 与 AI 降噪**

<p align="center">  
  <a href="./LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0--only-0f766e?style=flat-square" alt="AGPL-3.0-only" /></a>  
  <img src="https://img.shields.io/badge/platform-Android%208.0%2B-3ddc84?style=flat-square" alt="Android 8.0+" />  
  <img src="https://img.shields.io/badge/language-Java%20%2F%20C%20%2F%20Rust-orange?style=flat-square" alt="Java / C / Rust" />  
</p>

本仓库收录 TeamSpeak 3 的**原生 Android 客户端**及其语音处理（三段语音 EQ、多种 AI 降噪）相关实现。主力客户端 `android-ts3/` 通过客户端语音协议（UDP 9987）**直连 TeamSpeak 3 服务器**，完成 `Init1 → clientinitiv → initivexpand2 → clientek → clientinit` 握手，不经过 WebSpeak 网关、WebSocket 或 HTTP 层。

## 仓库结构

| 目录                                              | 定位           | 说明                                                                                                                     |
| ----------------------------------------------- | ------------ | ---------------------------------------------------------------------------------------------------------------------- |
| [`android-ts3/`](./android-ts3/README.md)       | **主项目 · 活跃** | 直连 TS3 的 Java 客户端；内置 vendored `ts3j` 协议栈、Android-free `core` 会话/语音引擎、RNNoise 与 DeepFilterNet3 的 NDK 源码、ONNX 降噪链路与语音 EQ |
| [`android-native/`](./android-native/README.md) | 早期原型 · 归档    | 连接 WebSpeak 网关（`/api/join-ticket` + `/ws/voice`）的原生 Java 客户端；**已不作为交付目标**，保留作协议参考                                      |

## 语音处理特性

- **语音 EQ**：200 Hz 低架、1 kHz 峰值、4 kHz 高架，各 ±12 dB 的温和语音塑形；麦克风侧使用固定增益，不引入 AGC 或限幅器，避免破坏齿音。
- **AI 降噪（默认关闭，单一主开关）**：RNNoise 0.2（BSD-3-Clause，C/JNI）、DeepFilterNet3（`deep-filter-rt`，Rust）、`a2heng/lightweight-denoise-48k`（MIT，ONNX Runtime）、Alibaba DFSMN `speech_dfsmn_ans_psm_48k_causal`（`DakeQQ/Audio-Denoiser-ONNX` 导出）。
- **语音链路**：`AudioRecord` 48 kHz 单声道 → 预处理/降噪/EQ → Concentus Opus 编码 → `ts3j` 麦克风队列；下行经自适应抖动缓冲 → Opus 解码 → 混音播放，含 in-band FEC 与丢包隐藏。
- 中英文界面切换、断线重连、可选 UDP-over-TCP relay、频道树/成员/聊天/事件日志等完整客户端功能。

完整的实现说明、构建步骤\`、自检流程与已知限制见 [`android-ts3/README.md`](./android-ts3/README.md)。

## 快速开始

已验证的构建环境：JDK 21、Android SDK platform 37 / build-tools 37.0.0 / NDK 30.0.16248370、CMake 3.22.1（随 SDK 提供）。`minSdk 26`、`targetSdk 35`，无 AndroidX 依赖。

```powershell
cd android-ts3
$env:JAVA_HOME   = 'C:\Tools\jdk-21'
$env:ANDROID_HOME = 'C:\Android\sdk'
.\gradlew.bat :app:assembleDebug
```

Windows 下也可直接运行 `.\package-apk.ps1`（或双击 `package-apk.bat`）自动探测 JDK/SDK、清理旧包并把 APK 复制到 `dist/`。

> **注意**：本仓库**不包含**构建产物与预编译动态库（Gradle `build/`、Rust `target/`、`*.apk`、`jniLibs/*.so` 等均由 [`.gitignore`](./.gitignore) 排除）。首次构建需由本地 NDK 编译 RNNoise 与 DeepFilterNet3 的 native 库。ONNX 权重 `lightweight-denoise-48k.onnx`（MIT）已随仓库提供；`DFSMN.onnx` 等其他权重请按 [模型说明](./android-ts3/app/src/main/assets/models/README.md) 自行放置。缺少模型时对应降噪项显示「模型未安装」，引擎自动回退到 DeepFilterNet3 或 RNNoise。

---

## 鸣谢 · Acknowledgements

以下名单**完整沿用 `android-ts3` 客户端「关于」页面**（`app/src/main/res/values/strings.xml` 与 `values-en/strings.xml` 中的 `about_body`），分组、姓名与链接均保持原样。

### 感谢协助人员 · Thanks to

- [github.com/lishang999](https://github.com/lishang999)
- [github.com/akatsuki-misaki](https://github.com/akatsuki-misaki)
- [github.com/Tungsten-Owl](https://github.com/Tungsten-Owl)
- [github.com/hyb-oyqq](https://github.com/hyb-oyqq)

### 特别鸣谢 · Special thanks

- [space.bilibili.com/38556474](https://space.bilibili.com/38556474)
- [space.bilibili.com/38958171](https://space.bilibili.com/38958171)

### 项目鸣谢 · Projects

- [HoneyBBQ/teamspeak-js](https://github.com/HoneyBBQ/teamspeak-js)
- [EchoSixHIYA/WebSpeak-client-for-TeamSpeak](https://github.com/EchoSixHIYA/WebSpeak-client-for-TeamSpeak)

> 内置 AI 降噪模型来自各自的开源项目，授权随模型文件，详见项目内的[模型说明](./android-ts3/app/src/main/assets/models/README.md)。

### 上游开源组件（补充，非原始鸣谢名单）

[ts3j](./android-ts3/ts3j/UPSTREAM-README.md) 是 [Splamy/TS3AudioBot](https://github.com/Splamy/TS3AudioBot/) 中 C# TS3Client 的 Java 适配；音频链路另使用了 [xiph/rnnoise](https://github.com/xiph/rnnoise)、Concentus Opus、[DeepFilterNet](https://github.com/Rikorose/DeepFilterNet)、[a2heng/lightweight-denoise-48k](https://github.com/a2heng/lightweight-denoise-48k)、[DakeQQ/Audio-Denoiser-ONNX](https://github.com/DakeQQ/Audio-Denoiser-ONNX) 与 ONNX Runtime。各组件遵循其原始许可证。

---

## 声明 · Disclaimer

本软件基于 [WebSpeak](https://github.com/EchoSixHIYA/WebSpeak-client-for-TeamSpeak) 项目的代码修改而来，仓库与部分设计同源；本客户端是原生 Android 实现，直连 TeamSpeak 服务器。

**本软件未获得 TeamSpeak 官方授权，亦未获 WebSpeak 授权**；与原项目及其作者没有隶属或代理关系，也不代表原项目的立场。需要官方支持时，请使用原项目发布的版本。

## 许可证 · License

本项目沿用 [AGPL-3.0-only](./LICENSE)。第三方协议栈、组件与内置模型各自遵循其原始许可证；再分发构建包前请核对相应模型许可。
