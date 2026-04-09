# qiaohu

骁龙 8 Gen2 本地多模态语音 AI 助手，完全在设备端运行，无需服务器。

- **LLM**：LiteRT-LM（Gemma 4）
- **TTS**：sherpa-onnx + matcha-icefall-zh-baker（中文 22050 Hz）
- **VAD**：Silero VAD（ONNX Runtime）
- **ASR**：Whisper（设备端）
- **视觉**：CameraX

---

## 手动部署文件（已被 .gitignore 排除）

以下文件体积较大，不进入 git，需要手动放置。

### 1. sherpa-onnx .so 库

从 [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) 下载对应版本（**v1.12.36**）的 Android arm64-v8a 压缩包，解压后将以下四个文件放入 `app/src/main/jniLibs/arm64-v8a/`：

```
libsherpa-onnx-jni.so
libsherpa-onnx-c-api.so
libsherpa-onnx-cxx-api.so
libonnxruntime.so          # 必须是 v1.23.2（sherpa-onnx v1.12.36 内置版本）
```

> **注意**：`libonnxruntime.so` 版本必须与 sherpa-onnx 编译时使用的版本一致（1.23.2），否则会出现 `OrtGetApiBase` 符号找不到的链接错误。`app/build.gradle.kts` 中同样引用了 `onnxruntime-android:1.23.2`，两者需保持一致。

### 2. TTS 模型

```bash
# 下载 matcha-icefall-zh-baker 声学模型目录（约 88 MB）
# 放置路径：app/src/main/assets/matcha-icefall-zh-baker/
# 包含：model-steps-3.onnx、lexicon.txt、phone.fst、date.fst、number.fst、tokens.txt 等

# 下载 vocoder（约 51 MB）
# 放置路径：app/src/main/assets/vocos-22khz-univ.onnx
```

模型来源：https://github.com/k2-fsa/sherpa-onnx/releases（搜索 matcha-icefall-zh-baker）

### 3. VAD 模型

```bash
wget https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx \
     -O app/src/main/assets/silero_vad.onnx
```

### 4. LLM 模型（设备端）

首次运行时 App 会自动下载 LLM 模型（~2.6 GB），需要 WiFi 网络。模型存储在：

```
/sdcard/Android/data/com.donge.qiaohu/files/models/
```

---

## 构建与安装

### 环境要求

- Android Studio Meerkat (2024.3.1+)
- NDK 27
- API 34 SDK
- JDK 17（`JAVA_HOME=/opt/homebrew/opt/openjdk@17`）

### 编译

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

### 安装到真机（WiFi ADB）

```bash
# 连接设备（替换为实际 IP:port）
adb connect 192.168.5.9:41583

# 推送并安装（自动点击安装确认弹窗）
adb -s 192.168.5.9:41583 push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/app-debug.apk
(adb -s 192.168.5.9:41583 shell pm install -r /data/local/tmp/app-debug.apk) &
INSTALL_PID=$!
for i in $(seq 1 10); do sleep 0.3; adb -s 192.168.5.9:41583 shell input tap 945 2400 2>/dev/null & done
wait $INSTALL_PID
```

---

## 项目结构

```
app/src/main/
├── java/com/donge/qiaohu/
│   ├── llm/            LiteRT-LM Engine + 工具定义 + 模型下载
│   ├── tts/            SherpaOnnxTts（matcha-icefall-zh-baker）+ 句子分割
│   ├── vad/            Silero VAD（ONNX Runtime）
│   ├── audio/          MicRecorder（AudioRecord）+ AudioPlayer（AudioTrack，22050 Hz）
│   ├── camera/         CameraCapture（CameraX）
│   ├── ui/             Compose UI + Theme.Qiaohu
│   ├── MainActivity.kt
│   ├── QiaohuViewModel.kt
│   └── QiaohuApplication.kt
├── java/com/k2fsa/sherpa/onnx/
│   └── Tts.kt          # sherpa-onnx Kotlin JNI API（直接复制进项目）
├── jniLibs/arm64-v8a/  # [gitignore] sherpa-onnx .so 文件
├── assets/
│   ├── silero_vad.onnx               # [gitignore] VAD 模型
│   ├── matcha-icefall-zh-baker/      # [gitignore] 声学模型目录
│   └── vocos-22khz-univ.onnx         # [gitignore] vocoder
└── AndroidManifest.xml
```

---

## TTS 配置参考

`SherpaOnnxTts.kt` 中使用的 `getOfflineTtsConfig` 参数：

```kotlin
getOfflineTtsConfig(
    modelDir          = "matcha-icefall-zh-baker",
    acousticModelName = "model-steps-3.onnx",
    vocoder           = "vocos-22khz-univ.onnx",
    lexicon           = "lexicon.txt",
    dataDir           = "",
    dictDir           = "",
    ruleFsts          = "matcha-icefall-zh-baker/phone.fst,matcha-icefall-zh-baker/date.fst,matcha-icefall-zh-baker/number.fst",
    numThreads        = 4,
)
```

---

## 关键依赖版本

| 依赖 | 版本 | 说明 |
|------|------|------|
| sherpa-onnx | 1.12.36 | .so 文件版本 |
| onnxruntime-android | 1.23.2 | 必须与 sherpa-onnx 内置版本一致 |
| matcha-icefall-zh-baker | — | 中文 TTS 声学模型 |
| vocos-22khz-univ | — | vocoder，22050 Hz 输出 |
