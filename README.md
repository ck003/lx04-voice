# LX04 Voice

为 **小米小爱触屏音箱 LX04**（MT8167A 四核 A35 / 1GB RAM / Android 8.1）做的轻量语音触发 App。

## 为什么不是唤醒词识别 App

这台机器跑不动设备端唤醒词：

| 引擎 | 实测 |
|---|---|
| openWakeWord | CPU 满载 |
| microWakeWord | CPU **192%**（四核共 400%），唤醒无响应 |

MT8167A 是 2017 年的低端四核 A35，跑 TFLite 音频推理的实时性不够。
HA 官方文档也指出 microWakeWord 是为 ESP32-S3 这类带推理加速的芯片设计的。

## 这个 App 做什么

**只做 VAD（音量检测），不做任何 ML 推理。**

```
麦克风 RMS 检测（CPU 占用接近 0）
    ↓ 检测到有人说话
调用 HA Assist 服务
    ↓
唤醒词校验 + ASR + LLM + TTS 全部在 HA 服务端
```

唤醒词交给 HA 服务端的 openWakeWord（NAS 上有算力），
本机只回答一个问题：「现在有没有人在说话」。

## 构建

GitHub Actions 自动构建，无需本地环境：

- 推送 tag 触发 Release：`git tag v1.0 && git push --tags`
- 或手动触发 workflow，从 Artifacts 下载 APK

APK 位置：`app/build/outputs/apk/debug/app-debug.apk`

## 安装与配置

```bash
adb install -r app-debug.apk
adb shell pm grant io.ck.lx04voice android.permission.RECORD_AUDIO
```

打开 App，填：

- **HA 地址**：`http://192.168.1.191:8123`
- **长期访问令牌**：HA 个人资料 → 安全 → 长期访问令牌
- **灵敏度阈值**：默认 1200，说话没反应就调小，误触发就调大

点「启动监听」。开机自启由 `BootReceiver` 处理。

## 已知限制

- 这是**声音触发**不是**唤醒词**：大声说话会触发。灵敏度需按房间调。
- 触发后由 HA 服务端判断是不是唤醒词；不是则忽略。
- 屏幕显示部分依赖系统 WebView（本机为 61，跑不了 HA 2026 前端），
  所以本 App 只做语音，不做面板。

## 目录

```
app/src/main/java/io/ck/lx04voice/
├── Config.kt          配置存储
├── ListenerService.kt 前台服务 + RMS 检测 + HA 调用
├── SetupActivity.kt   配置界面
└── BootReceiver.kt    开机自启
```
