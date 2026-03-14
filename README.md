# Russian-Chinese Real-time Simultaneous Interpreter

Fully offline Android app for real-time Russian to Chinese simultaneous interpretation.

**Stack:** Vosk ASR + vosk-recasepunc (ONNX) + NLLB CTranslate2 — all on-device, no cloud.

## Architecture

```
Microphone → Vosk ASR (Russian STT)
           → vosk-recasepunc (punctuation/capitalization restoration)
           → SentenceSegmenter (split into translatable segments)
           → NLLB CTranslate2 (Russian → Chinese translation)
           → UI display
```

## UI

- Status bar (top): current app state
- Start/Stop button
- Russian live buffer (scrollable): real-time ASR output
- Chinese translation history (scrollable): translated segments

## Requirements

- Android phone with arm64-v8a (virtually all modern Android phones)
- Android 8.0+ (API 26)
- 8GB+ RAM recommended (16GB ideal)
- ~3GB free storage for models
- Android Studio with NDK support for building

## Models (NOT included in repo — too large)

Three models must be pushed to the phone manually:

| Model | Size | Download |
|-------|------|----------|
| vosk-model-ru-0.42 | ~1.8GB | https://alphacephei.com/vosk/models → Russian → `vosk-model-ru-0.42` |
| vosk-recasepunc-ru-0.22 | ~680MB | https://alphacephei.com/vosk/models → Russian → `vosk-recasepunc-ru-0.22` |
| nllb-200-distilled-1.3B-ct2-int8 | ~1.3GB | See below |

### NLLB Translation Model

Download the CTranslate2-converted NLLB model:

```bash
# Option 1: Using huggingface-cli
pip install huggingface_hub
huggingface-cli download JustFrederik/nllb-200-distilled-1.3B-ct2-int8 --local-dir nllb-200-distilled-1.3B-ct2-int8

# Option 2: Manual download from https://huggingface.co/JustFrederik/nllb-200-distilled-1.3B-ct2-int8
# Download all files into a folder named nllb-200-distilled-1.3B-ct2-int8/
```

Then download the SentencePiece model (required, NOT included in the CT2 conversion):

```bash
wget -O nllb-200-distilled-1.3B-ct2-int8/sentencepiece.bpe.model \
  "https://huggingface.co/facebook/nllb-200-distilled-1.3B/resolve/main/sentencepiece.bpe.model"
```

### Push Models to Phone

Connect your phone via USB, then in PowerShell (adjust adb path as needed):

```powershell
$adb = "C:\Users\YOUR_USERNAME\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dest = "/sdcard/Android/data/com.bohanli.ruzhtranslator/files/models"

& $adb push "D:\path\to\vosk-model-ru-0.42" "$dest/vosk-model-ru-0.42/"
& $adb push "D:\path\to\vosk-recasepunc-ru-0.22" "$dest/vosk-recasepunc-ru-0.22/"
& $adb push "D:\path\to\nllb-200-distilled-1.3B-ct2-int8" "$dest/nllb-200-distilled-1.3B-ct2-int8/"
```

## Building the Native Libraries

The app uses CTranslate2 and SentencePiece compiled for Android arm64-v8a.
Prebuilt `.so` files are included in `app/src/main/jniLibs/arm64-v8a/`.

If you need to rebuild them (e.g., for a different ABI or to update versions):

### Prerequisites

- WSL2 with Ubuntu (on Windows) or native Linux
- Android NDK r26d

### Build Steps

```bash
# Run the build script from WSL2:
bash tools/build_ct2_android.sh
```

This script will:
1. Install build dependencies (cmake, ninja, etc.)
2. Download Android NDK r26d (if not present)
3. Build SentencePiece for arm64-v8a
4. Build CTranslate2 with RUY backend (INT8 support) for arm64-v8a
5. Copy `.so` files and headers to the project

### Key Build Flags

- `WITH_RUY=ON` — enables ARM NEON int8 acceleration (critical for performance)
- `OPENMP_RUNTIME=NONE` — Android doesn't ship Intel OpenMP
- CTranslate2 source requires patching `thread_pool.cc` to stub out `pthread_setaffinity_np` (not available in Android Bionic libc) — the build script handles this automatically

## Build & Deploy

1. Open the project in Android Studio
2. Ensure NDK is installed (SDK Manager → SDK Tools → NDK)
3. Connect your phone via USB with USB debugging enabled
4. Click the green Run button (or Shift+F10)
5. The first build compiles the JNI bridge — subsequent builds are faster

## Project Structure

```
app/src/main/
  cpp/
    ctranslate2_jni.cpp         # JNI bridge to CTranslate2 + SentencePiece
    CMakeLists.txt              # CMake build config (auto-detects prebuilt .so)
    ctranslate2_include/        # CTranslate2 C++ headers
    sentencepiece_include/      # SentencePiece C++ header
  java/.../
    MainActivity.kt             # Main UI + pipeline orchestration
    asr/
      VoskAsrManager.kt         # Vosk speech recognition wrapper
      RecasepuncProcessor.kt    # ONNX punctuation/case restoration
    core/
      ModelManager.kt           # Model path resolution (internal/external/assets)
      AppState.kt               # UI state definitions
    segmentation/
      SentenceSegmenter.kt      # Split ASR output into translatable segments
    translation/
      NllbTranslator.kt         # Kotlin wrapper for CTranslate2 JNI
      TranslationQueue.kt       # Background translation queue
  jniLibs/arm64-v8a/
    libctranslate2.so           # Prebuilt CTranslate2 (with RUY)
    libsentencepiece.so         # Prebuilt SentencePiece
  res/layout/
    activity_main.xml           # UI layout
tools/
  build_ct2_android.sh          # Cross-compilation script for WSL2/Linux
```

## Technical Notes

- JNI name mangling: `ruzhtranslator` → `ruzhtranslator` (underscores in package name)
- CTranslate2 runs with `ComputeType::INT8` via RUY backend for ARM NEON acceleration
- NLLB language tokens: `rus_Cyrl` (source), `zho_Hans` (target) — no `__` wrapper
- SentencePiece tokenizer output gets `rus_Cyrl` prepended and `</s>` appended before translation
- Target language token `zho_Hans` is passed as forced BOS (decoder prefix)
- The CMake build auto-switches between full mode (`CTRANSLATE2_AVAILABLE=1`) and stub mode (`=0`) based on whether prebuilt `.so` files exist

## Performance Notes

On Snapdragon 8 Elite (16GB RAM):
- ASR latency: ~1-2 seconds (very fast)
- Translation latency: ~3-8 seconds per segment with 1.3B model
- Consider NLLB-200-distilled-600M for faster translation (~2x speedup)

## License

This project uses the following open-source components:
- [Vosk](https://alphacephei.com/vosk/) — Apache 2.0
- [CTranslate2](https://github.com/OpenNMT/CTranslate2) — MIT
- [SentencePiece](https://github.com/google/sentencepiece) — Apache 2.0
- [NLLB-200](https://github.com/facebookresearch/fairseq/tree/nllb) — CC-BY-NC 4.0
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) — MIT
