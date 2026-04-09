# AGENTS.md — qiaohu

## Build

```bash
# Requires JDK 17 — must be set explicitly, system default may differ
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

No unit tests exist. Build success is the only automated verification step.

## Install to device (WiFi ADB)

Device: V2338A, arm64-v8a, Android 16, Snapdragon 8 Gen 2. IP may change.

```bash
adb connect 192.168.5.9:41583

adb -s 192.168.5.9:41583 push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/app-debug.apk
(adb -s 192.168.5.9:41583 shell pm install -r /data/local/tmp/app-debug.apk) &
INSTALL_PID=$!
for i in $(seq 1 10); do sleep 0.3; adb -s 192.168.5.9:41583 shell input tap 945 2400 2>/dev/null & done
wait $INSTALL_PID
```

The tap loop is required to auto-dismiss the "Allow unknown sources" dialog.

## Files excluded from git (must be placed manually)

All large binaries are gitignored. The app will crash or hang at startup without them.

| Path | Source | Size |
|------|--------|------|
| `app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so` | sherpa-onnx v1.12.36 release | — |
| `app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-c-api.so` | same | — |
| `app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-cxx-api.so` | same | — |
| `app/src/main/jniLibs/arm64-v8a/libonnxruntime.so` | same (must be v1.23.2) | — |
| `app/src/main/assets/matcha-icefall-zh-baker/` | sherpa-onnx releases | ~88 MB |
| `app/src/main/assets/vocos-22khz-univ.onnx` | sherpa-onnx releases | ~51 MB |
| `app/src/main/assets/silero_vad.onnx` | snakers4/silero-vad repo | ~1.8 MB |

**Critical version constraint**: `libonnxruntime.so` inside the sherpa-onnx `.so` bundle must be v1.23.2. Using v1.20.0 causes `OrtGetApiBase` symbol-not-found at runtime. `gradle/libs.versions.toml` pins `onnxruntime = "1.23.2"` to match.

LLM model on device (survives reinstall):
```
/sdcard/Android/data/com.donge.qiaohu/files/models/gemma-4-E2B-it.litertlm
```
First run auto-downloads it (~2.6 GB) from HuggingFace if absent. If the package name changes, this path changes too.

## Architecture

Single-activity app. One `ViewModel` owns everything.

```
VAD (Silero, 16 kHz) → speech detected
  → LLM (LiteRT-LM Gemma 4, GPU, multimodal)
    → TTS (sherpa-onnx matcha-icefall-zh-baker, 22050 Hz)
      → AudioPlayer (AudioTrack PCM_16BIT)
```

- `QiaohuViewModel.kt` — central coordinator; owns all model lifecycles
- `tts/SherpaOnnxTts.kt` — wraps `OfflineTts`; `SAMPLE_RATE = 22_050`
- `audio/AudioPlayer.kt` — hardcoded `SAMPLE_RATE = 22_050`; if you change TTS sample rate, update both
- `java/com/k2fsa/sherpa/onnx/Tts.kt` — sherpa-onnx Kotlin JNI API copied directly into the project (no AAR/Maven); do not move or rename this package
- TTS streams sentence-by-sentence via a `Channel<FloatArray>` producer/consumer to hide inter-sentence gaps
- Barge-in: new VAD speech cancels the running `ttsJob` and calls `audioPlayer.stop()`

## LiteRT-LM quirks

- Requires real Snapdragon hardware. On emulators it causes a native SIGILL before any Java exception — `LlmEngine` detects `isEmulator` and falls back to stub mode automatically.
- `kotlinOptions { freeCompilerArgs += "-Xskip-metadata-version-check" }` is required because litertlm-android is compiled with a newer Kotlin metadata version.
- `packaging { jniLibs { useLegacyPackaging = true } }` is required for LiteRT-LM native libs.
- Engine caches compiled artefacts to `context.cacheDir` to speed up subsequent loads.

## Identifiers

| Thing | Value |
|-------|-------|
| applicationId / namespace | `com.donge.qiaohu` |
| Root project name (settings.gradle.kts) | `parlor-android` (stale — harmless) |
| ViewModel | `QiaohuViewModel` |
| Application class | `QiaohuApplication` |
| Theme | `Theme.Qiaohu` |

`settings.gradle.kts` still says `rootProject.name = "parlor-android"` — this is cosmetic only and does not affect the build or package name.

## Key versions

| Dependency | Version | Why it matters |
|-----------|---------|----------------|
| sherpa-onnx .so | 1.12.36 | must match onnxruntime version below |
| onnxruntime-android | 1.23.2 | must match what sherpa-onnx was compiled against |
| AGP | 8.7.3 | |
| Kotlin | 2.1.0 | |
| compileSdk / targetSdk | 35 | |
| minSdk | 29 | |
| JVM target | 17 | JAVA_HOME must point to JDK 17 |
