# OfflineRealtimeTranslator

A fully offline Android application for real-time Russian-to-Chinese simultaneous interpretation. All inference runs entirely on-device with no network connection required.

## Technology Stack

```
Microphone → Vosk ASR (Russian speech recognition)
           → vosk-recasepunc (punctuation & capitalization restoration)
           → Gemma 3 4B-IT (llama.cpp, OpenCL GPU acceleration, RU→ZH translation)
           → Streaming color-coded bilingual UI (16-color rainbow gradient)
           → Room database (translation history + favorites management)
```

## User Interface

### Main Screen
- **Status bar** (top): Displays the current application state (loading / listening / translating)
- **Russian recognition area** (upper half, scrollable): Real-time ASR output with finalized text shown in color and buffered text in gray
- **Chinese translation area** (lower half, scrollable): Streaming typewriter-effect display of translation results, color-matched with corresponding Russian segments
- **Control bar** (center): Favorite button, History button, Settings button (popup menu), Start/Stop button, Pause/Resume button
- **Pause functionality**: When paused, ASR and the translation queue are suspended without terminating the current session; seamless resumption upon continue
- **Settings menu**: Quick switching between large and small ASR models; access to the settings page
- **Smart auto-scroll**: Auto-scrolling pauses when the user manually scrolls up, displaying a "Scroll to bottom" button

**Color-coded alignment**: Each Russian segment and its corresponding Chinese translation share the same color from a 16-color rainbow gradient cycle (excluding white), providing an immediate visual mapping between source and target text.

### History & Favorites Screen
- Bottom dual-tab navigation (Favorites | History) with entry counts displayed for each
- Top filter bar: time range, sorting, cleanup, and multi-select
- Entry cards: star icon on the left (one-tap favorite/unfavorite), title (timestamp + Chinese preview), Russian and Chinese text preview
- Multi-select mode: batch delete, export, copy, and favorite operations with select-all support
- Single tap to view details (full text with copy/export buttons); long press to rename
- Independent management: history and favorites are managed separately; the same entry can exist in both lists simultaneously
- Export as UTF-8 text files to the system Downloads folder

## Hardware Requirements

- Android phone with arm64-v8a architecture (virtually all modern Android devices)
- Android 10+ (API 29)
- **8 GB+ RAM** (16 GB recommended)
- Approximately 5 GB of storage for model files
- Snapdragon 8 series or equivalent SoC recommended (for OpenCL GPU acceleration)

## Model Files (Not Included in Repository)

Three models must be manually pushed to the device:

| Model | Size | Purpose | Download |
|-------|------|---------|----------|
| vosk-model-small-ru-0.22 | ~50 MB | Russian speech recognition (default, lightweight) | [Vosk Models](https://alphacephei.com/vosk/models) |
| vosk-model-ru-0.42 | ~1.8 GB | Russian speech recognition (optional, large model) | [Vosk Models](https://alphacephei.com/vosk/models) |
| vosk-recasepunc-ru-0.22 | ~680 MB | Punctuation & capitalization restoration | [Vosk Models](https://alphacephei.com/vosk/models) |
| gemma-3-4b-it-Q4_K_M | ~2.5 GB | RU→ZH translation engine | See below |

> **ASR model comparison**: Empirical testing shows that the small model (50 MB) and the large model (1.8 GB) achieve nearly identical recognition accuracy for clear speech (news broadcasts, meetings, lectures), while the small model loads orders of magnitude faster. The large model's advantage lies in noisy environments, dialectal speech, and unclear articulation. The small model is used by default.

### Downloading the Gemma Translation Model

```bash
pip install huggingface_hub
huggingface-cli download unsloth/gemma-3-4b-it-GGUF gemma-3-4b-it-Q4_K_M.gguf --local-dir gemma-3-4b-it-Q4_K_M
```

### Pushing Models to the Device

PowerShell (Windows):

```powershell
$adb = "C:\Users\YourUsername\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dest = "/sdcard/Android/data/com.bohanli.ruzhtranslator/files/models"

& $adb push "D:\YourPath\vosk-model-ru-0.42" "$dest/vosk-model-ru-0.42/"
& $adb push "D:\YourPath\vosk-recasepunc-ru-0.22" "$dest/vosk-recasepunc-ru-0.22/"
& $adb push "D:\YourPath\gemma-3-4b-it-Q4_K_M" "$dest/gemma-3-4b-it-Q4_K_M/"
```

## Building and Deployment

1. Open the project in Android Studio
2. Ensure the NDK is installed (SDK Manager → SDK Tools → NDK)
3. **Set Build Variant to `release`** (release-mode compiler optimizations are critical for llama.cpp; debug builds are approximately 25–30x slower)
4. Connect the phone via USB with USB debugging enabled
5. Click the Run button
6. The first build compiles llama.cpp (~5–10 minutes); subsequent incremental builds are fast

> **Note**: AGP 9.1.0 bundles Kotlin, so no separate kotlin-android plugin is required. `gradle.properties` must include `android.disallowKotlinSourceSets=false` to support KSP.

## Project Structure

```
app/src/main/
  cpp/
    llama_jni.cpp               # JNI bridge (llama.cpp ↔ Kotlin)
    CMakeLists.txt              # CMake build configuration (OpenCL ON)
    llama.cpp/                  # llama.cpp submodule (git submodule)
    cmake/                      # Custom FindOpenCL / FindPython3
    OpenCL-Headers/             # KhronosGroup OpenCL headers
  java/.../
    MainActivity.kt             # Main UI + pipeline orchestration + pause/resume + settings popup + ASR switching
    asr/
      VoskAsrManager.kt         # Vosk speech recognition wrapper
      RecasepuncProcessor.kt    # ONNX punctuation & capitalization restoration
    core/
      ModelManager.kt           # Model path management
      AppStatus.kt              # UI state definitions
    segmentation/
      SentenceSegmenter.kt      # 5-rule sentence segmenter (commented out, retained for reference)
    translation/
      GemmaTranslator.kt        # Gemma translator Kotlin wrapper
      TranslationQueue.kt       # Background translation queue (ordered + generation counter)
    history/
      TranslationRecord.kt      # Room entity (dual flags: isHistory + isFavorite)
      TranslationDao.kt         # Room DAO (history/favorites separate queries + soft delete)
      AppDatabase.kt            # Room database singleton
      HistoryAdapter.kt         # RecyclerView adapter (star toggle + multi-select)
      HistoryActivity.kt        # History & favorites management screen
    settings/
      SettingsActivity.kt       # Settings page scaffold (future model management entry point)
  res/layout/
    activity_main.xml           # Main screen layout (dark theme)
    activity_history.xml        # History & favorites screen layout
    activity_settings.xml       # Settings screen layout
    item_history.xml            # History entry card layout
    spinner_item.xml            # Dark-theme Spinner collapsed style
    spinner_dropdown_item.xml   # Dark-theme Spinner dropdown style
  res/drawable/
    bg_circle_button*.xml       # Circular button backgrounds (start/stop states)
    ic_*.xml                    # Icons: play, stop, arrow, history, star, export, copy, delete, multi-select
```

## Technical Details

- **Translation engine**: llama.cpp statically linked, integrated via git submodule, compiled through CMake `add_subdirectory`
- **Quantization format**: Gemma 3 4B-IT Q4_K_M (~2.5 GB, 4-bit quantization)
- **OpenCL GPU acceleration** (v2.6+): llama.cpp `GGML_OPENCL` backend with Adreno 830 optimized kernels; +40% generation speed with substantially reduced thermal output
- **KV cache prefix reuse**: The fixed prompt prefix (translation instructions) is pre-decoded at model load time and the KV cache state is saved as a snapshot; each translation call restores the snapshot instead of re-decoding, saving approximately 300–500 ms per call
- **Streaming output**: During translation generation, every 2 tokens are pushed to the UI in real time via JNI callback, producing a typewriter effect
- **Segmentation strategy**: Vosk native VAD segmentation with `min-utterance-length=2.5` parameter in model.conf
- **Translation history** (v3.0): Room database with dual-flag design (`isHistory`/`isFavorite`) supporting independent management, filtering, sorting, batch operations, and export
- **Pause/resume** (v3.1): When paused, ASR stops recording and the translation queue suspends after completing the current item (`Channel<Unit>` semaphore); the session is preserved and resumes seamlessly
- **Runtime ASR model switching** (v3.1): Switch between the large model (1.8 GB) and small model (50 MB) via the settings popup; selection is persisted to SharedPreferences
- **Parallel model loading** (v3.1): Vosk, Recasepunc, and Gemma are initialized concurrently using `async(Dispatchers.IO)`
- **Translation queue generation counter**: The generation counter increments on stop/restart, discarding stale translation results from the previous queue to ensure correct color-segment alignment
- **Thread configuration**: 6 threads + n_batch=512 (prompt batch processing acceleration)
- **JNI naming**: Package `com.bohanli.ruzhtranslator` maps to `com_bohanli_ruzhtranslator` in JNI function signatures
- **Prompt template**: `<start_of_turn>user\nTranslate...<end_of_turn>\n<start_of_turn>model\n`

## Performance Benchmarks

Measured on Snapdragon 8 Elite (16 GB RAM):

| Metric | Value |
|--------|-------|
| Vosk model loading (small) | < 1 s |
| Gemma model loading | ~1–2 s |
| KV cache prefix pre-computation | ~5 s (one-time at load) |
| Prompt processing (with prefix reuse) | Suffix decoding only, ~550 ms–1.7 s |
| Translation generation (OpenCL GPU) | ~11–14 tok/s |
| Translation generation (CPU only, reference) | ~8–10 tok/s |
| Per-segment translation latency | 1–3 s (depending on input length) |
| Speech recognition latency | ~1 s |

> **Important**: Release builds are required. Debug builds disable compiler optimizations for llama.cpp, resulting in approximately 1/30th the speed of release builds.

## Known Limitations

- **Thermal throttling under prolonged use**: OpenCL GPU acceleration (v2.6) substantially reduces heat generation, but extended continuous operation may still trigger frequency scaling
- **Long Vosk native segments**: Segmentation relies on Vosk VAD, and individual segments can be lengthy (60–90 tokens), increasing per-segment translation time
- Gemma 4B occasionally produces output in other languages (English/Japanese, roughly once every 10–15 segments) within Chinese translations
- The small Vosk model occasionally merges two short words into one when the speaker stutters
- The punctuation restoration model has limited effectiveness on speech fragments (capitalization restoration works correctly; punctuation prediction is weaker)
- Vulkan GPU backend is incompatible with Adreno (ErrorDeviceLost); OpenCL is used instead

## Licenses

This project uses the following open-source components:
- [Vosk](https://alphacephei.com/vosk/) — Apache 2.0
- [llama.cpp](https://github.com/ggml-org/llama.cpp) — MIT
- [Gemma](https://ai.google.dev/gemma) — Gemma Terms of Use
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) — MIT
- [Room](https://developer.android.com/jetpack/androidx/releases/room) — Apache 2.0

## Version History

- **v3.1 — Pause/Resume + Settings Entry + ASR Model Switching + Parallel Loading**:
  - Added pause/resume button; when paused, ASR and translation queue suspend without terminating the session, with seamless resumption
  - Added settings gear button + PopupMenu (settings page entry + ASR model size switching)
  - ASR model selection persisted to SharedPreferences, automatically loaded on restart
  - Added SettingsActivity scaffold (model management, default model selection, about) as groundwork for future LLMhub-style extensions
  - Three-model parallel loading (async + Dispatchers.IO) with real-time loading progress in the status bar
- **v3.0 — Translation History + Favorites + Major UI Overhaul**:
  - Added Room database translation history system with dual-flag design (`isHistory`/`isFavorite`) for independent history and favorites management
  - Added HistoryActivity: bottom dual-tab (Favorites/History), time filtering, sorting, batch cleanup, multi-select operations (delete/export/copy/favorite), detail dialog, entry renaming
  - Automatic saving of previous translation records when starting a new session; export as UTF-8 text files to the Downloads folder
  - Batch delete and cleanup operations require confirmation to prevent accidental data loss; action buttons show prompts when no entries are selected
  - Streamlined main screen: removed dividers and text labels; enlarged favorite/history buttons to 40 dp, aligned with the start button
  - Color-coded segments expanded from 10 to 16 colors in a rainbow gradient (excluding white)
  - Smart auto-scroll: auto-scrolling pauses on manual upward scroll, displaying a floating "Scroll to bottom" button
  - Translation queue generation counter resolves color-segment mismatch after stop/restart
  - Dark-theme custom Spinner styles (light text + dark dropdown background)
  - Russian input area placeholder changed to Russian: "Ожидание голосового ввода..."
  - Build compatibility: AGP 9.1.0 bundled Kotlin + KSP 2.1.0 + Room 2.7.1
- **v2.6 — OpenCL GPU Acceleration**: Enabled llama.cpp `GGML_OPENCL` backend with Adreno 830 optimized kernels. Generation speed increased from 8–10 tok/s to 11–14 tok/s (+40%), prompt processing reduced from 700 ms–3 s to 550 ms–1.7 s, and thermal performance improved for sustained operation. Build requires KhronosGroup OpenCL Headers + device libOpenCL.so stub
- **v2.5 — Vosk Segmentation Tuning + Color Optimization**: Optimized segmentation via Kaldi endpointer parameters in model.conf (`min-utterance-length=2.5`); short utterances are less prone to premature splits while long utterances segment naturally. Segment colors changed to a rainbow gradient sequence for better visual distinction between adjacent segments
- **v2.4 — Streaming Output**: Every 2 tokens are pushed to the UI in real time during translation generation (JNI callback), providing a typewriter effect with substantially improved perceived responsiveness
- **v2.3 — Vosk Native Segmentation + Small Model Default**: Removed custom sentence segmentation rules and partial-stability confirmation in favor of Vosk native VAD segmentation, yielding better semantic coherence per segment. ASR default switched to vosk-model-small-ru-0.22 (50 MB) with significantly faster loading; recognition accuracy on clear speech is nearly identical to the large model (1.8 GB)
- **v2.2 — KV Cache Prefix Reuse + Partial Stability Confirmation**: Pre-decoded the fixed prompt prefix and cached the KV state; each translation restores the snapshot instead of re-decoding (~300–500 ms savings per call). Tracked Vosk partial result stability to confirm prefix words that remain unchanged across consecutive frames, feeding them into the translation pipeline early to reduce overall latency
- **v2.1 — Gemma 4B Upgrade + Conjunction Refinement**: Upgraded translation model from Gemma 3 1B to 4B (Q4_K_M) with significantly improved translation quality. Reduced conjunction-based segmentation list from 33 to 6 strong clause-boundary conjunctions (но/однако/поэтому/хотя/зато/потому) to minimize fragmented segmentation
- **v2.0 — Gemma Translation Engine + Color-Coded Alignment**: Migrated to llama.cpp + Gemma 3 1B; added Russian conjunction-based segmentation, color-coded parallel segments, and retention of unfinalized text on stop
- **v1.0 — Initial Release**: Vosk + NLLB CTranslate2 architecture
