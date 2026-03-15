# Development Notes & Upgrade Roadmap

> This document records pitfalls encountered during development, technical decisions, and future plans.
> Reading this file before contributing will help avoid repeating known issues.

---

## 1. Environment Information

| Item | Value |
|------|-------|
| Development machine | Windows + WSL (development tools running in WSL) |
| IDE | Android Studio (Windows side) |
| NDK | 28.2.13676358 |
| AGP | 9.1.0 (built-in Kotlin; do not add the `kotlin-android` plugin separately) |
| KSP | 2.1.0-1.0.29 (Room annotation processor) |
| adb path | `C:\Users\Acerola\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| Test device | K90 Pro Max, Snapdragon 8 Elite, 16 GB RAM, Android 14+ |
| Model storage path | `/sdcard/Android/data/com.bohanli.ruzhtranslator/files/models/` |
| GitHub | https://github.com/XiaoYi2018/RU-ZH-Translator.git (private) |

**Key constraints**:
- **Never run `gradlew`, PowerShell, or `apt` commands inside WSL** — builds must be performed in Android Studio on the Windows side
- System commands intended for the user (e.g., `adb push`, `git push`) should be provided in PowerShell format

---

## 2. Known Pitfalls

### 2.1 Vulkan GPU Acceleration — Not Viable

**Approaches attempted**:
1. Enabled `GGML_VULKAN=ON` to compile the llama.cpp Vulkan backend
2. NDK 28.2 only ships C Vulkan headers; `vulkan/vulkan.hpp` is missing. Manually downloaded KhronosGroup/Vulkan-Headers v1.3.275
3. `vkGetPhysicalDeviceFeatures2` link failure — requires Vulkan 1.1 (API 29+). Changed minSdk from 26 to 29
4. Compiled successfully, but at runtime: `vk::Queue::submit: ErrorDeviceLost`
   - `n_gpu_layers=99` — device overheated severely and crashed
   - `n_gpu_layers=12` — still ErrorDeviceLost
   - `n_gpu_layers=0` — **still ErrorDeviceLost**

**Root cause**: With `GGML_VULKAN=ON`, the Vulkan backend dispatches compute shaders even with 0 GPU layers. The Adreno GPU's Vulkan compute shaders are incompatible with llama.cpp.

**Final decision**: `GGML_VULKAN OFF`, pure CPU mode. minSdk kept at 29 (K90 Pro Max runs Android 14+, so no impact).

**Residual files** (safe to delete):
- `app/src/main/cpp/vulkan-headers/` — downloaded Vulkan C++ headers, no longer used
- `app/src/main/cpp/host-toolchain.cmake` — toolchain for building vulkan-shaders-gen with TDM-GCC

### 2.2 OpenCL GPU Acceleration — Succeeded in v2.6

llama.cpp includes a full OpenCL backend (12,544 lines of C++ + 98 kernels), with Adreno 830 as a first-class target.

**Build configuration** (`CMakeLists.txt`):
```cmake
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
```

**Build dependencies**:
1. OpenCL Headers — `git clone https://github.com/KhronosGroup/OpenCL-Headers.git` into `app/src/main/cpp/OpenCL-Headers/`
2. libOpenCL.so — `adb pull /system/vendor/lib64/libOpenCL.so` into `app/src/main/cpp/libOpenCL.so` (link-time stub only)
3. Python 3 — required by `embed_kernel.py` to embed .cl kernels into the binary
4. Custom `cmake/FindOpenCL.cmake` and `cmake/FindPython3.cmake` to work around `find_package` issues during Android NDK cross-compilation

**Key pitfalls**:
- `libOpenCL.so` **must not be packaged into the APK** (otherwise `dlopen` will fail because `libcutils.so` cannot be found). Exclude it in `build.gradle.kts`: `jniLibs { excludes += "**/libOpenCL.so" }`
- `AndroidManifest.xml` must declare `<uses-native-library android:name="libOpenCL.so" android:required="false" />` (inside the `<application>` tag), so the system vendor library is loaded at runtime
- **Do not attempt Vulkan again** (Adreno Vulkan compute shaders are incompatible with llama.cpp)

**Performance comparison** (Snapdragon 8 Elite / Adreno 830):

| Metric | CPU (v2.5) | OpenCL GPU (v2.6) | Improvement |
|--------|-----------|-------------------|-------------|
| Generation speed | ~8-10 tok/s | ~11-14 tok/s | +40% |
| Prompt processing | ~700 ms-3 s | ~550 ms-1.7 s | Faster |
| Thermal throttling | Severe, drops to 1.5 tok/s | Significantly reduced, sustained operation possible | Biggest benefit |

### 2.3 Parallel Model Loading — Slower in Practice

Attempted parallel loading of Vosk + Recasepunc + Gemma using `async`. Loading was actually slower because all three models contend for I/O and CPU simultaneously. **Serial loading is retained.**

### 2.4 Vosk API Limitations (v0.3.47 Android)

- `setEndpointerDelays()` and `setEndpointerMode()` **do not exist** in Vosk 0.3.47 for Android
- Available methods: `setMaxAlternatives`, `setWords`, `setPartialWords`, `setSpeakerModel`, `setGrammar`
- **However, endpointer parameters can be controlled directly via `conf/model.conf` in the model directory** (see Section 2.8)

### 2.5 Custom Segmentation vs. Vosk Native Segmentation

**v2.0-v2.2 custom segmentation approach** (SentenceSegmenter.kt):
- 5-level rules: sentence-final punctuation > commas > Russian conjunctions > pauses > forced split
- Combined with partial stability confirmation (tracking Vosk partial prefix stability; if unchanged for N consecutive frames, the prefix is confirmed early)
- **Problem**: Segments were too short and non-semantic boundaries degraded translation quality due to lack of context

**v2.3 switched to Vosk native segmentation**:
- Vosk final results are sent directly to translation without secondary splitting
- Longer segments (60-90 tokens) with better semantic completeness lead to higher translation quality
- **Trade-off**: Per-segment translation time increases, slightly reducing real-time responsiveness

**SentenceSegmenter.kt code is retained but unused**; related calls in MainActivity are commented out.

### 2.6 Vosk Large vs. Small Model Comparison

| Model | Size | Load time | Accuracy (clear speech) |
|-------|------|-----------|------------------------|
| vosk-model-ru-0.42 | ~1.8 GB | 10-15 s | High |
| vosk-model-small-ru-0.22 | ~50 MB | <1 s | Nearly identical |

Conclusion: For clear speech scenarios (news, meetings, lectures), the difference is negligible. The large model excels only in noisy, dialectal, or mumbled speech. **Small model is the default since v2.3.**

**⚠ Large model CPU contention issue (confirmed v3.1)**:
The large model's default decoding parameters (`max-active=7000`, `beam=13.0`, `lattice-beam=6.0`) are far heavier than the small model's (`3000/10.0/2.0`). Even after reducing these parameters to match the small model, the large model's acoustic network itself consumes excessive CPU per `acceptWaveForm()` call. When running concurrently with Gemma (6 threads + OpenCL GPU), the CPU is fully saturated, causing:
- Gemma translation speed drops from ~11-14 tok/s to ~1-2 tok/s
- Translation queue backs up indefinitely
- Device overheats and throttles further
- Stopping ASR immediately restores normal translation speed (confirming CPU contention)

**Conclusion**: The large Vosk model is not viable for real-time concurrent use with LLM translation on current hardware. Potential future mitigations: throttle the audio loop (`delay` between frames), reduce Gemma thread count when large model is active, or offload Vosk to a dedicated thread pool with CPU affinity.

### 2.7 Thermal Throttling

Prolonged operation causes severe CPU thermal throttling:
- Normal: ~8-10 tok/s generation speed
- Throttled: ~1.5 tok/s (5-6x slower)

No software mitigation available; this is a hardware limitation.

### 2.8 Vosk model.conf Parameter Tuning

Vosk is built on Kaldi. The `conf/model.conf` file in the model directory directly controls decoding and endpointer parameters. After editing, push the file to the device via `adb push` and restart the app — **no recompilation needed**.

#### Decoding Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| min-active | 200 | Minimum number of active candidates in the decoder |
| max-active | 3000 | Maximum number of active candidates in the decoder |
| beam | 10.0 | Search beam width; larger values improve accuracy at the cost of speed |
| lattice-beam | 2.0 | Lattice output beam; larger values produce more alternative paths |
| acoustic-scale | 1.0 | Acoustic model score weight; generally left unchanged |
| frame-subsampling-factor | 3 | Takes every 3rd frame; determined by model architecture, do not change |
| silence-phones | 1:2:3:...10 | Phone IDs treated as silence for endpoint detection |

#### Endpoint Rules

| Parameter | Default | Description |
|-----------|---------|-------------|
| rule2.min-trailing-silence | 0.5 | After speech is detected, silence duration to trigger segmentation |
| rule2.min-utterance-length | 0.0 (default) | rule2 does not trigger if utterance is shorter than this (in seconds) |
| rule3.min-trailing-silence | 1.0 | Fallback if rule2 does not trigger; silence duration to force segmentation |
| rule4.min-trailing-silence | 2.0 | Absolute fallback; forces segmentation after this silence duration |

#### Tuning Experiment Log

| Change | Result | Satisfaction |
|--------|--------|-------------|
| rule2 silence 0.5 → 1.0 only | Segments became longer | Not the desired effect |
| Added min-utterance-length=2.5 only (decoding params unchanged) | Segments fill roughly half to two-thirds of the input area; short utterances no longer produce single-word fragments | **Best result** |
| beam 10 → 13, lattice-beam 2 → 4, max-active 3000 → 5000 | Segments became excessively long paragraphs; endpoint rules appeared to stop functioning | Not satisfactory; reverted |

#### Best Configuration (v2.5)

Keep all decoding parameters at their defaults and only add `--endpoint.rule2.min-utterance-length=2.5`.

**Note**: Decoding parameters (beam/lattice-beam/max-active) and endpoint rules interact with each other. Increasing decoding beam widths alters endpoint trigger behavior. Recognition accuracy issues should not be addressed by tuning beam parameters.

### 2.9 AGP 9.1.0 + KSP + Room Compatibility Chain (v3.0 Pitfall)

Three cascading compilation issues encountered when introducing Room in v3.0:

1. **kotlin-android plugin conflict**: AGP 9.1.0 has built-in Kotlin compilation support. Manually adding the `kotlin-android` plugin causes `Cannot add extension with name 'kotlin', as there is an extension already registered`. **Fix**: Remove the `kotlin-android` plugin from `build.gradle.kts`; keep only `android.application` + `ksp`.

2. **KSP sourceSets conflict**: KSP attempts to use the `kotlin.sourceSets` DSL, which AGP 9 disallows. **Fix**: Add `android.disallowKotlinSourceSets=false` to `gradle.properties`.

3. **Room 2.6.1 + KSP 2.x incompatibility**: Room 2.6.1's annotation processor throws `unexpected jvm signature V` under KSP 2.x. **Fix**: Upgrade Room from 2.6.1 to 2.7.1.

**Correct configuration**:
- `build.gradle.kts` (root): Declare only `android.application` + `ksp` (not `kotlin-android`)
- `app/build.gradle.kts`: Apply only `android.application` + `ksp`
- `gradle.properties`: `android.disallowKotlinSourceSets=false`
- Room 2.7.1 + KSP 2.1.0-1.0.29

### 2.10 Translation Queue Color Block Mismatch (v3.0 Pitfall)

**Symptom**: After stopping and restarting listening, Chinese and Russian paragraph colors no longer correspond.

**Cause**: When `stopListening` is called, the translation queue may still have pending translation requests. After `startListening` resets the Russian paragraph counter, stale translation results still in the channel are delivered with incorrect color indices.

**Fix**:
- `TranslationQueue` gained a `clear()` method with an internal `AtomicInteger` generation counter that increments on each `clear()` call
- The channel sends `Pair<Int, String>` (generation + text); the consumer checks whether the generation matches the current value and discards mismatches
- `startListening()` calls `translationQueue.clear()` before saving the previous session and clearing the UI

---

## 3. Current Architecture (v3.1)

```
Microphone → Vosk ASR (small/large model switchable, native VAD segmentation + min-utterance-length=2.5)
           → vosk-recasepunc (ONNX, punctuation and capitalization restoration)
           → Gemma 3 4B-IT (llama.cpp, Q4_K_M, OpenCL GPU on Adreno 830, KV Cache prefix reuse)
           → Streaming token callback → Color-coded bilingual UI (rainbow gradient, 16 colors)
           → Room database → Translation history + favorites management
           ↕ Pause/Resume (without interrupting the session)
```

**KV Cache Prefix Reuse**:
- The fixed prompt prefix is pre-decoded during `nativeCreate()` and its KV state is saved (~3.5 MB)
- Each `nativeTranslate()` call restores the snapshot and only decodes the suffix (Russian text + closing tag)
- Saves approximately 300-500 ms per translation

**Translation History System** (added in v3.0):
- Room database stores translation records (`TranslationRecord` entity with dual flags: `isHistory` / `isFavorite`)
- The previous session is automatically saved each time a new listening session starts (triggered in `startListening()`)
- `HistoryActivity`: Dual-tab interface (Favorites / History) with time filtering, sorting, cleanup, and batch operations
- Deletion logic: Deleting from History only clears `isHistory`; deleting from Favorites only clears `isFavorite`; physical deletion occurs when both flags are false
- Export: Generates a BOM-prefixed UTF-8 text file in the system Download folder via the MediaStore API

**Key files**:
| File | Purpose |
|------|---------|
| `app/src/main/cpp/llama_jni.cpp` | JNI layer: KV Cache prefix reuse + streaming token callback |
| `app/src/main/cpp/CMakeLists.txt` | llama.cpp build configuration (OpenCL ON, Vulkan OFF) |
| `app/src/main/cpp/cmake/FindOpenCL.cmake` | Custom OpenCL finder for Android cross-compilation |
| `app/src/main/cpp/cmake/FindPython3.cmake` | Custom Python 3 finder for kernel embedding |
| `MainActivity.kt` | Main pipeline orchestration: parallel model loading, pause/resume, settings popup, ASR model switching |
| `SentenceSegmenter.kt` | Custom segmentation (commented out since v2.3, retained for reference) |
| `VoskAsrManager.kt` | Vosk wrapper |
| `GemmaTranslator.kt` | Gemma translator Kotlin wrapper |
| `TranslationQueue.kt` | Background translation queue (ordered + generation counter) |
| `history/TranslationRecord.kt` | Room entity (dual-flag design) |
| `history/TranslationDao.kt` | Room DAO (separate history/favorites queries + soft delete) |
| `history/AppDatabase.kt` | Room database singleton |
| `history/HistoryAdapter.kt` | RecyclerView adapter (star toggle + multi-select checkboxes) |
| `history/HistoryActivity.kt` | History and favorites management UI |
| `settings/SettingsActivity.kt` | Settings page skeleton (future model management entry point) |

---

## 4. Upgrade Roadmap

### 4.1 Streaming Output (completed in v2.4)

### 4.2 Vosk Segmentation Parameter Tuning (completed in v2.5; see Section 2.8)

### 4.3 OpenCL GPU Acceleration (completed in v2.6; see Section 2.2)

### 4.4 Translation History + Favorites (completed in v3.0; see Section 3)

### 4.5 ASR Model Switching + Pause Feature + Parallel Loading + Settings Page (completed in v3.1)

### 4.6 Further Vosk Parameter Tuning (priority: medium, pending)
Continue fine-tuning endpoint rules and decoding parameters to improve segment length consistency and word-level recognition accuracy.
Note: beam/lattice-beam/max-active and endpoint rules interact (see Section 2.8); changes must be tested individually and carefully.

### 4.9 Large Vosk Model CPU Contention Fix (priority: medium, pending)
The large ASR model causes translation stalls due to CPU contention with Gemma (see Section 2.6). Possible approaches:
- Throttle `audioLoop()`: add `delay(10-20ms)` after each `acceptWaveForm()` to yield CPU time to the translation engine
- Dynamically reduce Gemma thread count (6→3) when large model is active
- Assign Vosk and Gemma to separate thread pools with CPU core affinity
- Combination of the above

### 4.7 Translation Prompt Optimization (priority: low, pending)
Gemma occasionally outputs partial English (approximately once every 5-10 segments). A possible mitigation is to reinforce "output Chinese only" in the prompt using Chinese instructions.

### 4.8 Translation Model Exploration (priority: low)
- A larger model (Gemma 12B) may improve translation quality, but device memory and inference speed are bottlenecks
- A smaller model (Gemma 1B) offers significantly faster inference with somewhat lower quality, potentially sufficient for short utterances

---

## 5. Build Notes

- **Always use Release builds**: In Debug mode, llama.cpp lacks compiler optimizations and runs at roughly 1/30th of Release speed
- llama.cpp is integrated as a git submodule: `app/src/main/cpp/llama.cpp/`
- Initial llama.cpp compilation takes approximately 5-10 minutes; subsequent incremental builds are fast
- Gradle Sync (elephant icon) can be triggered at any time without side effects
- JNI naming convention: Package `com.bohanli.ruzhtranslator` maps to `com_bohanli_ruzhtranslator` in JNI function signatures
- **AGP 9.1.0 must not include the `kotlin-android` plugin** (it is built in); use only `android.application` + `ksp`
- **Room 2.7.1+ is required** for KSP 2.x compatibility (see Section 2.9)
- `gradle.properties` must contain `android.disallowKotlinSourceSets=false`

---

## 6. Changelog (append-only)

> Records key bugs, fix procedures, and conclusions for each iteration.

### v1.0 (Initial Release)
- Vosk ASR + NLLB CTranslate2 architecture, basic functionality

### v2.0 — Gemma Translation Engine + Color-Coded Display
- Migrated translation engine from NLLB CTranslate2 to llama.cpp + Gemma 3 1B-IT (Q4_K_M)
- Added Russian conjunction-based segmentation (SentenceSegmenter, 33 conjunctions)
- Added color-coded paragraph display (10-color cycle, Russian-Chinese color correspondence)
- Added retention of undrafted text on stop

### v2.1 — Gemma 4B Upgrade + Failed Vulkan Attempt
- **Translation model upgrade**: Gemma 1B to 4B (Q4_K_M); significant improvement in translation quality
- **Vulkan GPU acceleration attempt** (4 rounds of fixes, ultimately abandoned):
  1. `vulkan/vulkan.hpp` not found — NDK 28.2 only has C headers; manually downloaded Vulkan-Headers v1.3.275 — **fixed**
  2. `undefined symbol: vkGetPhysicalDeviceFeatures2` — NDK API 26 only has Vulkan 1.0; this symbol requires 1.1 (API 29+); changed minSdk 26 to 29 — **fixed**
  3. Runtime `vk::Queue::submit: ErrorDeviceLost` (n_gpu_layers=99) — device overheated and crashed — reduced to 12, still crashed — reduced to 0, **still crashed**
  4. Root cause: `GGML_VULKAN=ON` dispatches Vulkan compute even with 0 GPU layers; Adreno GPU is incompatible
  - **Final conclusion**: Disabled `GGML_VULKAN`, pure CPU mode. **Do not attempt Vulkan on Adreno again.**

### v2.2 — KV Cache Prefix Reuse + Segmentation Optimization
- **KV Cache prefix reuse**: The fixed prompt prefix (25 tokens) is pre-decoded at model load time; KV snapshot (~3.5 MB) is saved and restored for each translation, saving ~300-500 ms per call. Verified working correctly.
- **parse_special bug** (1-round fix):
  - Symptom: One translation output was 889 characters long, containing a Chinese translation of the full prompt template + the original Russian text + the actual translation
  - Cause: Suffix tokenization used `parse_special=false`, so `<end_of_turn>` and `<start_of_turn>` were treated as plain text
  - Fix: Changed to `parse_special=true` — **resolved**
- **Conjunction list reduction**: Reduced from 33 to 6 strong clause-boundary conjunctions (but/however/therefore/although/yet/because), reducing fragmentation
- **Partial stability confirmation**: Tracked Vosk partial prefix stability; if unchanged for 8 consecutive frames, the first 60% was confirmed and sent for translation
  - Effect: Speed improved, but segments were too short and semantic fragmentation degraded translation quality
  - Status: Removed in v2.3

### v2.3 — Vosk Native Segmentation + Small Model
- **Removed custom segmentation**: Commented out SentenceSegmenter and partial stability confirmation; switched to Vosk native VAD segmentation
  - Effect: Better semantic completeness, improved translation quality, but longer segments (60-90 tokens) increase per-segment translation time
- **ASR model switch**: Default changed to vosk-model-small-ru-0.22 (50 MB)
  - Comparison: For clear speech (news/meetings/lectures), accuracy is virtually identical to the large model (1.8 GB)
  - Load time: <1 s vs. 10-15 s
- **Parallel loading attempt** (reverted):
  - Used `async` to load Vosk + Recasepunc + Gemma in parallel
  - Result: Loading was slower (three models contending for I/O and CPU), and potentially degraded subsequent translation performance
  - **Conclusion**: Serial loading retained
- **Vosk endpointer API unavailable**:
  - Attempted `setEndpointerDelays(false, 0.5f, 0.5f, 15.0f)` — compilation failed
  - Decompiled vosk-android-0.3.47.aar and confirmed: `setEndpointerDelays` and `setEndpointerMode` methods do not exist in this version
  - **Conclusion**: Version 0.3.47 does not support endpointer control; requires a newer version or source-level investigation

### v2.4 — Streaming Output (Typewriter Effect)
- **JNI streaming callback**: During translation generation, every 2 tokens are pushed to the UI in real time via JNI callback
  - `llama_jni.cpp`: Resolves `GemmaTranslator.onStreamToken(String)` in the generation loop, accumulates token text, and flushes every 2 tokens
  - `GemmaTranslator.kt`: Added `@Keep`-annotated `onStreamToken()` callback method to prevent R8 from stripping it
  - `TranslationQueue.kt`: Added `onStreamToken` and `onStreamStart` callback parameters; callbacks are set before translation and cleared after
  - `MainActivity.kt`: Added `startChineseStream()` / `appendStreamToken()` / `finalizeChinese()` to manage streaming display state
- **Visual effect**: Translation results appear incrementally (typewriter effect); users no longer wait for an entire segment to complete before seeing output
- **Leading whitespace handling**: Skips whitespace at the beginning of generation to avoid leading spaces in streamed output
- **end_of_turn detection**: Detects `<end_of_turn>` markers during streaming and truncates; control tags are not pushed to the UI

### v2.5 — Vosk Segmentation Parameter Tuning
- **Discovered model.conf controls endpointer**: Vosk is built on Kaldi; endpoint parameters in the model directory's `conf/model.conf` directly control segmentation behavior without recompiling Vosk or calling nonexistent APIs
- **Tuning experiments**:
  - rule2 silence 0.5 → 1.0: Segments became longer but not in the desired way
  - Added min-utterance-length=2.5: **Best result** — short utterances no longer produce single-word fragments; long utterances fit within half to two-thirds of the display area
  - Increased beam/lattice-beam/max-active: Segments became excessively long paragraphs; endpoint rules stopped functioning; reverted
- **Final configuration**: All decoding parameters at defaults; only added `--endpoint.rule2.min-utterance-length=2.5`
- **Important finding**: Decoding parameters and endpoint rules interact and cannot be tuned independently. Recognition accuracy should not be addressed by adjusting beam parameters

### v2.6 — OpenCL GPU Acceleration (Adreno 830)
- **Enabled GGML_OPENCL backend**: llama.cpp's built-in OpenCL backend (12,544 lines of C++ + 98 kernels) treats Adreno 830 as a first-class target with dedicated optimized kernels (noshuffle GEMV/GEMM)
- **Build environment setup**:
  - Downloaded KhronosGroup/OpenCL-Headers to `app/src/main/cpp/OpenCL-Headers/`
  - Pulled `libOpenCL.so` from the device via `adb pull /system/vendor/lib64/libOpenCL.so` as a compile-time link stub
  - Wrote custom `cmake/FindOpenCL.cmake` and `cmake/FindPython3.cmake` to work around NDK cross-compilation limitations
- **Key pitfall — libOpenCL.so must not be packaged into the APK**:
  - Symptom: App crashes immediately; `dlopen failed: library "libcutils.so" not found: needed by libOpenCL.so`
  - Cause: The pulled libOpenCL.so was bundled into the APK; at runtime, the bundled copy is loaded instead of the system library, and it depends on `libcutils.so` (a system-internal library not visible in the app namespace)
  - Fix: `build.gradle.kts` — `jniLibs { excludes += "**/libOpenCL.so" }`
- **AndroidManifest declaration**: `<uses-native-library android:name="libOpenCL.so" android:required="false" />` (must be inside the `<application>` tag, not `<manifest>`)
- **Performance comparison** (Snapdragon 8 Elite / Adreno 830):

  | Metric | CPU (v2.5) | OpenCL GPU (v2.6) | Improvement |
  |--------|-----------|-------------------|-------------|
  | Generation speed | ~8-10 tok/s | ~11-14 tok/s | +40% |
  | Prompt processing | ~700 ms-3 s | ~550 ms-1.7 s | Faster |
  | Thermal throttling | Severe, drops to 1.5 tok/s | Significantly reduced, sustained operation possible | Biggest benefit |

- **Known issue**: Gemma 4B occasionally outputs Japanese or English (approximately once every 10-15 segments), a multilingual model hallucination to be addressed via prompt optimization

### v3.0 — Translation History + Favorites + Major UI Overhaul

**New feature: Translation history and favorites system**
- **Room database** (Room 2.7.1 + KSP 2.1.0-1.0.29): `TranslationRecord` entity with dual-flag design (`isHistory` + `isFavorite`) for independent history and favorites management
- **Automatic session saving**: Each time listening starts, the previous session's Russian and Chinese text is automatically saved to the database (triggered in `startListening()` to avoid race conditions with pending translations)
- **HistoryActivity management interface**:
  - Bottom dual-tab navigation (Favorites | History) with item counts displayed next to tab labels
  - Top filter bar: time range (All / Last week / Last month), sort order (Newest first / Oldest first), cleanup (older than 1 week / 1 month / 1 year), multi-select button
  - Multi-select action bar: delete, export, copy, favorite, select-all checkbox + selected count
  - Item cards: star toggle on the left (single tap to toggle favorite), title (`MM-dd HH:mm` + first 2 Chinese characters), Chinese/Russian preview
  - Single tap on item: Detail dialog (full Russian and Chinese text + copy/export buttons)
  - Long press on item: Rename dialog (pre-filled with current name)
- **Dual-flag deletion logic**:
  - Deleting from the History tab only clears `isHistory` (if favorited, the item remains visible in Favorites)
  - Deleting from the Favorites tab only clears `isFavorite` (the item remains visible in History)
  - Physical deletion occurs only when both flags are false
- **Cleanup feature**: Time-based batch cleanup following the same dual-flag logic; double-confirmation dialogs to prevent accidental deletion
- **Export feature**: Exports BOM-prefixed UTF-8 text files to the system Download folder via the MediaStore API
- **Multi-select safety logic**: Tapping copy/export/favorite/delete with no items selected displays a "Please select items first" prompt; batch delete and cleanup operations require secondary confirmation

**UI overhaul**
- **Main interface separator area streamlined**: Removed divider lines and "Russian Recognition" / "Chinese Translation" text labels; retained only the favorite star (40 dp), history clock (40 dp), and start/stop button (44 dp)
- **Color palette expanded**: 10 colors to 16 rainbow gradient colors (no white) for better visual distinction
- **Smart auto-scroll**: Russian and Chinese areas independently track user scroll state; when the user scrolls up manually, auto-scroll pauses and a "Scroll to bottom" button appears; tapping it resumes auto-scroll
- **Translation queue generation counter**: `TranslationQueue.clear()` increments the generation counter; stale translation results from a previous generation remaining in the channel are automatically discarded, resolving the color mismatch issue after stop/restart
- **Russian placeholder text**: Input area hint changed from Chinese to Russian: "Ожидание голосового ввода..."
- **Dark theme Spinner**: Custom `spinner_item.xml` / `spinner_dropdown_item.xml` with light text on a dark dropdown background, resolving the issue of default black text being invisible on a dark interface

**Build compatibility fixes** (see Section 2.9):
- AGP 9.1.0 has built-in Kotlin — do not add the `kotlin-android` plugin
- KSP sourceSets conflict — add `android.disallowKotlinSourceSets=false`
- Room 2.6.1 + KSP 2.x incompatible — upgrade Room to 2.7.1

### v3.1 — Pause Feature + Settings Entry + ASR Model Switching + Parallel Loading

**Pause/Resume feature**
- Added a pause button (amber circle, visible during listening) alongside the start/stop button on the main interface
- On pause: ASR recording stops, residual partial results are flushed, and the translation queue suspends after completing the current item
- No history write, no paragraph clearing, color indices preserved; tapping resume continues the same session
- TranslationQueue gained `pause()` / `resume()` methods, implemented via a `Channel<Unit>` semaphore for consumer suspension
- The stop button works even in paused state (internally resumes before stopping to avoid consumer deadlock)

**Settings button + PopupMenu**
- Added a gear icon (40 dp) to the right of the history button in the main interface separator bar
- Tapping it opens a PopupMenu with two items:
  - "Settings" — opens SettingsActivity
  - "Switch Large/Small Model" — runtime ASR model switching (stop listening, release old Vosk instance, load new model, re-enter ready state)
- ASR model selection is persisted in SharedPreferences; the app automatically loads the last-selected model on restart

**SettingsActivity skeleton**
- Dark theme, back button + title bar, three placeholder sections: model management, default model selection, about
- Reserved as a future entry point for LLMhub-style model download management

**Parallel model loading**
- Vosk, Recasepunc, and Gemma are loaded in parallel using `coroutineScope { async(Dispatchers.IO) }`
- Status bar displays real-time progress: "Loading models (1/3)... (2/3)... (3/3)..."
- Parallel loading was slower in v2.3 (reverted to serial); re-enabled in v3.1 with comparable performance, so the parallel approach is retained
