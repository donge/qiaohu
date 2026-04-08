#!/usr/bin/env python3
"""
scripts/extract_voice.py
────────────────────────
Pre-process Kokoro voice embeddings for use on Android.

The kokoro-onnx Python package distributes voice embeddings packed in a
NumPy .npz archive (voices-v1.0.bin).  Android cannot parse .npz files
natively, so this script extracts individual voice vectors as flat raw
float32 binary files that KokoroTts.kt can load directly.

Usage (run on your development machine, not on the device):

    # 1. Install kokoro-onnx (macOS/Linux)
    pip install kokoro-onnx huggingface_hub

    # 2. Download the voices file (if not already cached by hf_hub)
    python extract_voice.py --all

    # 3. Or extract a specific voice
    python extract_voice.py --voice zf_xiaobei

Output files are written to the current directory as <voice_name>.bin
and should be placed in:
    parlor-android/app/src/main/assets/

Supported voices (Chinese):
    zf_xiaobei  (default — Chinese female, recommended)
    zf_xiaoni
    zm_yunxi
    zm_yunjian
"""

import argparse
import sys
from pathlib import Path

VOICES_FILENAME = "voices-v1.0.bin"
HF_REPO = "fastrtc/kokoro-onnx"

CHINESE_VOICES = [
    "zf_xiaobei",  # Chinese female — used as default in server.py / KokoroTts.kt
    "zf_xiaoni",
    "zm_yunxi",
    "zm_yunjian",
]


def download_voices() -> Path:
    """Download voices-v1.0.bin from HuggingFace if not already cached."""
    try:
        from huggingface_hub import hf_hub_download
    except ImportError:
        print("ERROR: huggingface_hub is not installed.\n  pip install huggingface_hub")
        sys.exit(1)

    path = hf_hub_download(repo_id=HF_REPO, filename=VOICES_FILENAME)
    print(f"Voices file: {path}")
    return Path(path)


def load_voices(voices_path: Path) -> dict:
    """Load the .npz voices archive."""
    try:
        import numpy as np
    except ImportError:
        print("ERROR: numpy is not installed.\n  pip install numpy")
        sys.exit(1)

    data = np.load(str(voices_path), allow_pickle=True)
    return dict(data)


def extract_voice(voices: dict, voice_name: str, out_dir: Path) -> Path:
    """Extract a single voice embedding to a .bin file."""
    import numpy as np

    if voice_name not in voices:
        available = sorted(voices.keys())
        print(f"ERROR: Voice '{voice_name}' not found.")
        print(f"Available voices: {available}")
        sys.exit(1)

    vec = voices[voice_name]
    flat = vec.astype(np.float32).flatten()

    out_path = out_dir / f"{voice_name}.bin"
    flat.tofile(str(out_path))

    print(
        f"  {voice_name}: shape={vec.shape}  dtype={vec.dtype}  "
        f"→ {out_path}  ({out_path.stat().st_size} bytes)"
    )
    return out_path


def main():
    parser = argparse.ArgumentParser(
        description="Extract Kokoro voice embeddings for Android"
    )
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--voice", help="Extract a single voice (e.g. zf_xiaobei)")
    group.add_argument(
        "--all-chinese",
        action="store_true",
        help="Extract all Chinese voices: " + ", ".join(CHINESE_VOICES),
    )
    group.add_argument(
        "--all", action="store_true", help="Extract every voice in the archive"
    )
    parser.add_argument(
        "--out", default=".", help="Output directory (default: current dir)"
    )
    parser.add_argument(
        "--voices-file",
        default=None,
        help="Path to voices-v1.0.bin (downloads from HuggingFace if omitted)",
    )
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    voices_path = Path(args.voices_file) if args.voices_file else download_voices()
    voices = load_voices(voices_path)

    print(f"\nExtracting to {out_dir.resolve()}/")

    if args.voice:
        targets = [args.voice]
    elif args.all_chinese:
        targets = CHINESE_VOICES
    else:
        targets = sorted(voices.keys())

    for name in targets:
        extract_voice(voices, name, out_dir)

    print("\nDone!  Copy the .bin files to:")
    print("  parlor-android/app/src/main/assets/")
    print("\nThe default voice used by KokoroTts.kt is 'zf_xiaobei'.")


if __name__ == "__main__":
    main()
