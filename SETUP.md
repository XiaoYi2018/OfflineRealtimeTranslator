# 开发笔记 & 升级路线

> 本文件记录项目开发过程中踩过的坑、技术决策和后续规划。
> 即使更换 AI 助手，阅读此文件即可避免重复踩坑。

---

## 1. 环境信息

| 项目 | 值 |
|------|------|
| 开发机 | Windows + WSL（开发工具在 WSL 中运行） |
| IDE | Android Studio（Windows 侧） |
| NDK | 28.2.13676358 |
| adb 路径 | `C:\Users\Acerola\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| 测试手机 | K90 Pro Max，骁龙 8 Elite，16GB RAM，Android 14+ |
| 模型存储路径 | `/sdcard/Android/data/com.bohanli.ruzhtranslator/files/models/` |
| GitHub | https://github.com/XiaoYi2018/RU-ZH-Translator.git（私有） |

**关键约束**：
- **绝对不要在 WSL 中运行 gradlew、powershell、apt 等命令**，构建必须在 Android Studio（Windows 侧）完成
- 需要用户执行的系统命令（adb push、git push 等）提供 PowerShell 格式

---

## 2. 踩过的坑

### 2.1 Vulkan GPU 加速 — 不可用

**尝试过的方案**：
1. 启用 `GGML_VULKAN=ON`，编译 llama.cpp 的 Vulkan 后端
2. NDK 28.2 只有 C Vulkan 头文件，缺少 `vulkan/vulkan.hpp` → 手动下载了 KhronosGroup/Vulkan-Headers v1.3.275
3. `vkGetPhysicalDeviceFeatures2` 链接失败 → 需要 Vulkan 1.1（API 29+），将 minSdk 从 26 改为 29
4. 编译通过后运行：`vk::Queue::submit: ErrorDeviceLost`
   - `n_gpu_layers=99` → 手机巨烫、卡退
   - `n_gpu_layers=12` → 仍然 ErrorDeviceLost
   - `n_gpu_layers=0` → **仍然 ErrorDeviceLost**！

**根本原因**：`GGML_VULKAN=ON` 会让 Vulkan 后端即使在 0 GPU layers 下也会执行 compute dispatch。Adreno GPU 的 Vulkan 计算着色器与 llama.cpp 不兼容。

**最终决策**：`GGML_VULKAN OFF`，纯 CPU 模式。minSdk 保留 29（K90 Pro Max 是 Android 14+，无影响）。

**遗留文件**（可删除）：
- `app/src/main/cpp/vulkan-headers/` — 下载的 Vulkan C++ 头文件，已不使用
- `app/src/main/cpp/host-toolchain.cmake` — 用 TDM-GCC 编译 vulkan-shaders-gen 的工具链

### 2.2 GPU 加速替代方案 — 待研究

- **OpenCL**：LLM-HUB 项目在 Adreno 上用 MediaPipe/LiteRT + OpenCL 成功运行 LLM。llama.cpp 有 `GGML_OPENCL` 后端，值得尝试
- **不要再尝试 Vulkan**

### 2.3 并行加载模型 — 实测更慢

尝试用 `async` 并行加载 Vosk + Recasepunc + Gemma，结果加载反而更慢。原因是三个模型同时抢 I/O 和 CPU，互相拖慢。**保持串行加载**。

### 2.4 Vosk API 限制（v0.3.47 Android）

- `setEndpointerDelays()` 和 `setEndpointerMode()` 在 0.3.47 Android 版中**不存在**
- 可用方法仅有：`setMaxAlternatives`, `setWords`, `setPartialWords`, `setSpeakerModel`, `setGrammar`
- 无法通过 API 控制 Vosk 的端点检测灵敏度

### 2.5 自定义分段 vs Vosk 原生分段

**v2.0-v2.2 的自定义分段方案**（SentenceSegmenter.kt）：
- 5 级规则：句末标点 > 逗号 > 俄语连词 > 停顿 > 强制分割
- 配合 partial 稳定性确认（跟踪 Vosk partial 前缀稳定性，连续 N 次不变则提前确认）
- **问题**：分段太碎，非语义断句导致翻译模型缺乏上下文，翻译质量下降

**v2.3 改为 Vosk 原生分段**：
- Vosk final result 直接送翻译，不做二次切分
- 段落更长（60-90 tokens），语义更完整，翻译质量更好
- **代价**：单段翻译耗时增加，实时性略降

**SentenceSegmenter.kt 代码保留但未使用**，MainActivity 中已注释掉相关调用。

### 2.6 Vosk 大小模型对比

| 模型 | 大小 | 加载时间 | 清晰语音准确率 |
|------|------|----------|--------------|
| vosk-model-ru-0.42 | ~1.8GB | 10-15 秒 | 高 |
| vosk-model-small-ru-0.22 | ~50MB | <1 秒 | 几乎相同 |

结论：清晰语音（新闻/会议/演讲）场景下差别几乎为零。大模型优势在噪音/方言/口齿不清场景。**v2.3 起默认小模型**。

### 2.7 手机发热降频

长时间运行后 CPU 降频严重：
- 正常：翻译生成 ~8-10 tok/s
- 降频后：~1.5 tok/s（慢 5-6 倍）

暂无解决方案，属于硬件限制。

---

## 3. 当前架构（v2.3）

```
麦克风 → Vosk ASR（vosk-model-small-ru-0.22，原生 VAD 分段）
       → vosk-recasepunc（ONNX，标点/大小写恢复）
       → Gemma 3 4B-IT（llama.cpp，Q4_K_M，纯 CPU，KV Cache 前缀复用）
       → 彩色对照 UI
```

**KV Cache 前缀复用**：
- 固定 prompt 前缀在 `nativeCreate()` 中预解码，保存 KV 状态（~3.5MB）
- 每次 `nativeTranslate()` 恢复快照 + 只解码后缀（俄语文本 + 闭合标签）
- 节省 ~300-500ms/次

**关键文件**：
| 文件 | 作用 |
|------|------|
| `app/src/main/cpp/llama_jni.cpp` | JNI 层，KV Cache 前缀复用 + 流式 token 回调 |
| `app/src/main/cpp/CMakeLists.txt` | llama.cpp 编译配置（Vulkan/OpenCL 均 OFF） |
| `MainActivity.kt` | 主管线调度，Vosk → Recasepunc → 翻译 |
| `SentenceSegmenter.kt` | 自定义分段（v2.3 已注释，保留备用） |
| `VoskAsrManager.kt` | Vosk 封装 |
| `GemmaTranslator.kt` | Gemma 翻译器 Kotlin 封装 |
| `TranslationQueue.kt` | 后台翻译队列（保序） |

---

## 4. 后续升级路线

### 4.1 流式输出（优先级：高）
还原 LLM 逐字输出效果，参考 `D:\AndroidWorkspace\OfflineTranslator` 实现。
考虑 2-4 字一批输出，减少 UI 刷新开销。视觉上加速用户体验。

### 4.2 ASR 模型切换按钮（优先级：中）
主界面加按钮，默认小模型，一键切换大模型。检测手机上已有的模型，有大模型才显示切换。

### 4.3 OpenCL GPU 加速（优先级：中）
研究 llama.cpp 的 `GGML_OPENCL` 后端在 Adreno 上的兼容性。
参考 LLM-HUB 项目（GitHub 可搜到，也在 Google Play 上架）。

### 4.4 Vosk 分段参数调优（优先级：低）
研究 Vosk 源码或更高版本 API，找到控制 final result 输出频率的方法。
当前 0.3.47 Android 版无可用端点检测参数。

### 4.5 翻译模型探索（优先级：低）
- 更大模型（Gemma 12B）可能提升翻译质量，但手机内存和速度是瓶颈
- 更小模型（Gemma 1B）速度快很多，质量差一些但可能对简短句子够用

---

## 5. 构建注意事项

- **必须用 Release 构建**：Debug 模式 llama.cpp 无编译优化，速度仅为 Release 的 1/30
- llama.cpp 通过 git submodule 集成：`app/src/main/cpp/llama.cpp/`
- 首次编译 llama.cpp 约 5-10 分钟，后续增量编译很快
- Gradle sync（大象图标）随时可点，无副作用
- JNI 命名规则：包名 `ruzhtranslator` 中的下划线 → JNI 中 `ruzhtranslator`

---

## 6. 变更历史（只增不减）

> 记录每次迭代的关键 bug、修复过程和最终结论。

### v1.0（初始版本）
- Vosk ASR + NLLB CTranslate2 架构，基本可用

### v2.0 — Gemma 翻译引擎 + 彩色对照
- 翻译引擎从 NLLB CTranslate2 迁移至 llama.cpp + Gemma 3 1B-IT（Q4_K_M）
- 新增俄语连接词断句（SentenceSegmenter，33 个连词）
- 新增彩色段落对照显示（10色循环，俄中颜色一一对应）
- 新增停止时保留未定稿文本

### v2.1 — Gemma 4B 升级 + Vulkan 尝试失败
- **翻译模型升级**：Gemma 1B → 4B（Q4_K_M），翻译质量显著提升
- **Vulkan GPU 加速尝试**（共 4 轮修复，最终放弃）：
  1. `vulkan/vulkan.hpp` 找不到 → NDK 28.2 只有 C 头文件，手动下载 Vulkan-Headers v1.3.275 → **已修复**
  2. `undefined symbol: vkGetPhysicalDeviceFeatures2` → NDK API 26 只有 Vulkan 1.0，该符号需要 1.1（API 29+），改 minSdk 26→29 → **已修复**
  3. 运行时 `vk::Queue::submit: ErrorDeviceLost`（n_gpu_layers=99）→ 手机巨烫卡退 → 降到 12 → 仍然崩溃 → 降到 0 → **仍然崩溃**
  4. 根因：`GGML_VULKAN=ON` 即使 0 GPU layers 也会 dispatch Vulkan compute，Adreno GPU 不兼容
  - **最终结论**：关闭 `GGML_VULKAN`，纯 CPU 模式。**不要再尝试 Vulkan on Adreno**

### v2.2 — KV Cache 前缀复用 + 分段优化
- **KV Cache 前缀复用**：固定 prompt 前缀（25 tokens）在模型加载时预解码，保存 KV 快照（~3.5MB），每次翻译恢复快照，节省 ~300-500ms/次。实测正常工作。
- **parse_special bug**（1 轮修复）：
  - 现象：有一条翻译输出了 889 字符，包含完整 prompt 模板的中文翻译 + 原始俄语文本 + 实际翻译
  - 原因：suffix tokenize 时用了 `parse_special=false`，`<end_of_turn>` 和 `<start_of_turn>` 被当作普通文本
  - 修复：改为 `parse_special=true` → **已修复**
- **连词列表精简**：从 33 个减至 6 个强句界连词（但/然而/因此/虽然/不过/因为），减少碎片化
- **Partial 稳定性确认**：跟踪 Vosk partial 前缀稳定性，连续 8 次不变则确认前 60% 送翻译
  - 效果：速度确实加快，但分段太碎，语义断裂导致翻译质量下降
  - 状态：v2.3 中已移除

### v2.3 — Vosk 原生分段 + 小模型
- **移除自定义分段**：注释掉 SentenceSegmenter 和 partial 稳定性确认，改用 Vosk 原生 VAD 分段
  - 效果：段落语义完整性好，翻译质量提升，但单段更长（60-90 tokens），翻译耗时增加
- **ASR 模型切换**：默认改为 vosk-model-small-ru-0.22（50MB）
  - 对比结果：清晰语音场景（新闻/会议/演讲）与大模型（1.8GB）准确率几乎无差别
  - 加载时间：<1s vs 10-15s
- **并行加载尝试**（已回退）：
  - 用 `async` 并行加载 Vosk + Recasepunc + Gemma
  - 结果：加载反而更慢（三个模型抢 I/O 和 CPU），且疑似影响后续翻译性能
  - **结论**：保持串行加载
- **Vosk endpointer API 不可用**：
  - 尝试调用 `setEndpointerDelays(false, 0.5f, 0.5f, 15.0f)` → 编译失败
  - 反编译 vosk-android-0.3.47.aar 确认：该版本无 `setEndpointerDelays` 和 `setEndpointerMode` 方法
  - **结论**：0.3.47 版本无法控制端点检测，需要等更高版本或研究源码

### v2.4 — 流式输出（打字机效果）
- **JNI 流式回调**：翻译生成过程中每 2 个 token 通过 JNI 回调实时推送到 UI
  - `llama_jni.cpp`：在生成循环中 resolve `GemmaTranslator.onStreamToken(String)` 方法，累积 token 文本，每 2 个 token flush 一次
  - `GemmaTranslator.kt`：新增 `@Keep` 注解的 `onStreamToken()` 回调方法，防止 R8 混淆移除
  - `TranslationQueue.kt`：新增 `onStreamToken` 和 `onStreamStart` 回调参数，翻译前设置回调、翻译后清除
  - `MainActivity.kt`：新增 `startChineseStream()` / `appendStreamToken()` / `finalizeChinese()` 三个方法管理流式显示状态
- **视觉效果**：翻译结果逐步出现（打字机效果），用户不再需要等待整段翻译完成才能看到结果
- **leading whitespace 处理**：跳过生成开头的空白字符，避免流式输出开头出现空格
- **end_of_turn 检测**：流式输出中检测 `<end_of_turn>` 标记并截断，不会将控制标签推送到 UI
