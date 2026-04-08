# parlor-android

Parlor Android — 骁龙 8 Gen2 本地多模态语音 AI 助手。

与 macOS 版 (`../src/`) 功能对等，完全本地运行，无需服务器。

## 快速开始

### 1. 准备模型文件

**VAD 模型（~1.8 MB）**
```bash
wget https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx \
     -O app/src/main/assets/silero_vad.onnx
```

**TTS 模型（~330 MB + ~500 MB）**
```bash
# 需要 huggingface_hub
pip install huggingface_hub
python - <<'EOF'
from huggingface_hub import hf_hub_download
import shutil
for f in ["kokoro-v1.0.onnx", "voices-v1.0.bin"]:
    src = hf_hub_download("fastrtc/kokoro-onnx", f)
    shutil.copy(src, f"app/src/main/assets/{f}")
EOF
```

**提取 zf_xiaobei 音色向量**
```bash
python scripts/extract_voice.py --voice zf_xiaobei --out app/src/main/assets/
```

### 2. 在 Android Studio 中打开

```
File → Open → 选择 parlor-android/ 目录
```

要求：Android Studio Meerkat (2024.3.1+), NDK 27, API 34 SDK。

### 3. 编译并安装

首次运行时 App 会自动下载 LLM 模型（2.58 GB），需要 WiFi 网络。

## 项目结构

```
app/src/main/
├── java/com/example/parlor/
│   ├── llm/           LiteRT-LM Engine + 工具定义 + 模型下载
│   ├── tts/           Kokoro ONNX TTS + 中文音素化 + 句子分割
│   ├── vad/           Silero VAD (ONNX Runtime)
│   ├── audio/         MicRecorder (AudioRecord) + AudioPlayer (AudioTrack)
│   ├── camera/        CameraCapture (CameraX)
│   ├── ui/            Compose UI 屏幕 + 主题
│   ├── MainActivity.kt
│   ├── ParlourViewModel.kt
│   └── ParlourApplication.kt
├── assets/
│   ├── silero_vad.onnx       # VAD 模型 (随 APK 打包)
│   ├── kokoro-v1.0.onnx      # TTS 模型 (随 APK 打包，或首次下载)
│   ├── voices-v1.0.bin       # TTS 音色库
│   └── zf_xiaobei.bin        # 提取的音色向量 (by scripts/extract_voice.py)
└── AndroidManifest.xml
```

## 参考文档

完整移植说明见 `../docs/android-migration.md`。
