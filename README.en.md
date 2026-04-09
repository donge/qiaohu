# qiaohu

A fully offline multimodal voice assistant for Snapdragon 8 Gen 2 Android devices. No server, no cloud — everything runs on-device.

**Pipeline:**
```
Mic → VAD (Silero, 16 kHz) → LLM (Gemma 4 2B, LiteRT-LM, GPU) → TTS (sherpa-onnx, 22050 Hz) → Speaker
```

- **LLM**: Google LiteRT-LM running Gemma 4 2B on Snapdragon GPU
- **TTS**: sherpa-onnx + matcha-icefall-zh-baker (Chinese, 22050 Hz)
- **VAD**: Silero VAD via ONNX Runtime
- **Vision**: CameraX — camera frames sent to LLM as multimodal input
- **Barge-in**: new speech immediately cancels ongoing TTS playback

> **"qiaohu" (巧虎)** is a beloved Chinese children's character known for being helpful and friendly.

---

## Demo

https://github.com/user-attachments/assets/e0bbd935-21c5-46fb-ad0e-f8d9c4aa8b10

*Tested on: Vivo V2338A, Snapdragon 8 Gen 2, Android 16*

---

## How it works

The entire pipeline runs in a single `ViewModel` with no network I/O after the first-run model download:

1. `SileroVad` continuously processes 16 kHz mic audio in chunks
2. On speech detection, a WAV buffer is passed to `LlmEngine`
3. `LlmEngine` wraps LiteRT-LM's `Engine` + `Conversation`; the model always responds via a `respond_to_user` tool call (structured output)
4. The response text is split into sentences and fed to `SherpaOnnxTts` one at a time
5. `AudioPlayer` plays each sentence via `AudioTrack` as soon as PCM is ready — producer/consumer channel hides inter-sentence gaps

---

## Large files (not in git)

These must be placed manually. The app will crash at startup without them.

### 1. sherpa-onnx native libraries

Download the **v1.12.36** Android arm64-v8a archive from [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) and place these four files in `app/src/main/jniLibs/arm64-v8a/`:

```
libsherpa-onnx-jni.so
libsherpa-onnx-c-api.so
libsherpa-onnx-cxx-api.so
libonnxruntime.so        # must be v1.23.2 — the version sherpa-onnx v1.12.36 was compiled against
```

> Mixing `libonnxruntime.so` versions causes an `OrtGetApiBase` symbol-not-found crash at runtime.
> `gradle/libs.versions.toml` pins `onnxruntime-android = "1.23.2"` to match.

### 2. TTS models

From [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) (search "matcha-icefall-zh-baker"):

| Destination | Contents | Size |
|---|---|---|
| `app/src/main/assets/matcha-icefall-zh-baker/` | `model-steps-3.onnx`, `lexicon.txt`, `tokens.txt`, `phone.fst`, `date.fst`, `number.fst`, `dict/` | ~88 MB |
| `app/src/main/assets/vocos-22khz-univ.onnx` | vocoder | ~51 MB |

### 3. VAD model

```bash
wget https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx \
     -O app/src/main/assets/silero_vad.onnx
```

### 4. LLM model (on-device)

Downloaded automatically on first launch (~2.6 GB, requires Wi-Fi). Stored at:

```
/sdcard/Android/data/com.donge.qiaohu/files/models/gemma-4-E2B-it.litertlm
```

The file survives app reinstalls. If the package name changes, the path changes too.

---

## Build & install

**Requirements:** JDK 17, Android Studio Meerkat (2024.3.1+), NDK 27, API 35 SDK

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

**Install via Wi-Fi ADB** (the tap loop auto-dismisses the "unknown sources" dialog):

```bash
adb connect <device-ip>:<port>
adb -s <device> push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/app-debug.apk
(adb -s <device> shell pm install -r /data/local/tmp/app-debug.apk) &
INSTALL_PID=$!
for i in $(seq 1 10); do sleep 0.3; adb -s <device> shell input tap 945 2400 2>/dev/null & done
wait $INSTALL_PID
```

Or grab the [latest release APK](https://github.com/donge/qiaohu/releases/latest).

---

## Project structure

```
app/src/main/
├── java/com/donge/qiaohu/
│   ├── QiaohuViewModel.kt      central coordinator — owns all model lifecycles
│   ├── llm/                    LiteRT-LM engine, tool definitions, model downloader
│   ├── tts/                    SherpaOnnxTts wrapper, sentence splitter
│   ├── vad/                    Silero VAD
│   ├── audio/                  MicRecorder (AudioRecord) + AudioPlayer (AudioTrack, 22050 Hz)
│   ├── camera/                 CameraX frame capture
│   └── ui/                     Jetpack Compose UI
├── java/com/k2fsa/sherpa/onnx/
│   └── Tts.kt                  sherpa-onnx Kotlin JNI API (copied directly — no AAR)
└── jniLibs/arm64-v8a/          [gitignored] sherpa-onnx .so files
```

---

## Key version constraints

| Dependency | Version | Why it matters |
|---|---|---|
| sherpa-onnx `.so` | **1.12.36** | must match onnxruntime below |
| onnxruntime-android | **1.23.2** | must match what sherpa-onnx was compiled against |
| LiteRT-LM | 0.10.0 | requires real Snapdragon hardware (SIGILL on emulator) |
| AGP | 8.7.3 | |
| Kotlin | 2.1.0 | |
| minSdk / targetSdk | 29 / 35 | |

### Non-obvious Gradle flags required for LiteRT-LM

```kotlin
// litertlm-android ships with newer Kotlin metadata — without this the build fails
kotlinOptions {
    freeCompilerArgs += "-Xskip-metadata-version-check"
}

// LiteRT-LM native libs require legacy JNI packaging
packaging {
    jniLibs { useLegacyPackaging = true }
}
```

---

## Emulator / stub mode

LiteRT-LM uses CPU instructions (SVE/dotprod) unsupported by the Android emulator hypervisor — it crashes with a native SIGILL before any Java exception can be caught. `LlmEngine` detects `isEmulator` and falls back to a stub that returns canned responses, so the full VAD → TTS pipeline can still be exercised without real hardware.

---

## License

MIT
