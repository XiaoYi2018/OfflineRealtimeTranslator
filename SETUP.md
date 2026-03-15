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
| AGP | 9.1.0（内置 Kotlin，不可额外添加 kotlin-android 插件） |
| KSP | 2.1.0-1.0.29（Room 注解处理器） |
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

### 2.2 OpenCL GPU 加速 — v2.6 已成功

llama.cpp 有完整 OpenCL 后端（12,544 行 C++ + 98 个 kernel），Adreno 830 为一等公民。

**编译配置**（`CMakeLists.txt`）：
```cmake
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
```

**编译依赖**：
1. OpenCL Headers — `git clone https://github.com/KhronosGroup/OpenCL-Headers.git` 放到 `app/src/main/cpp/OpenCL-Headers/`
2. libOpenCL.so — 从手机 `adb pull /system/vendor/lib64/libOpenCL.so` 放到 `app/src/main/cpp/libOpenCL.so`（仅编译时链接用）
3. Python3 — 用于 `embed_kernel.py` 将 .cl 内核嵌入二进制
4. 自定义 `cmake/FindOpenCL.cmake` 和 `cmake/FindPython3.cmake` 绕过 Android NDK 交叉编译时的 `find_package` 问题

**关键踩坑**：
- `libOpenCL.so` **不能打包进 APK**（否则 dlopen 时因 `libcutils.so` 找不到而崩溃）。在 `build.gradle.kts` 中排除：`jniLibs { excludes += "**/libOpenCL.so" }`
- `AndroidManifest.xml` 中必须声明 `<uses-native-library android:name="libOpenCL.so" android:required="false" />`（放在 `<application>` 标签内），运行时从系统 vendor 路径加载
- **不要再尝试 Vulkan**（Adreno Vulkan compute shader 不兼容 llama.cpp）

**性能对比**（骁龙 8 Elite / Adreno 830）：

| 指标 | CPU（v2.5） | OpenCL GPU（v2.6） | 提升 |
|------|-----------|-------------------|------|
| 生成速度 | ~8-10 tok/s | ~11-14 tok/s | +40% |
| Prompt 处理 | ~700ms-3s | ~550ms-1.7s | 更快 |
| 发热 | 严重，降频到 1.5 tok/s | 大幅改善，可持续运行 | 最大收益 |

### 2.3 并行加载模型 — 实测更慢

尝试用 `async` 并行加载 Vosk + Recasepunc + Gemma，结果加载反而更慢。原因是三个模型同时抢 I/O 和 CPU，互相拖慢。**保持串行加载**。

### 2.4 Vosk API 限制（v0.3.47 Android）

- `setEndpointerDelays()` 和 `setEndpointerMode()` 在 0.3.47 Android 版中**不存在**
- 可用方法仅有：`setMaxAlternatives`, `setWords`, `setPartialWords`, `setSpeakerModel`, `setGrammar`
- **但可以通过模型目录下 `conf/model.conf` 直接控制 Kaldi endpointer 参数**（见 2.8）

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

### 2.8 Vosk model.conf 参数调优

Vosk 基于 Kaldi，模型目录下 `conf/model.conf` 可直接控制解码和 endpointer 参数，修改后 adb push 到手机重启 App 即生效，**无需重新编译**。

#### 解码参数

| 参数 | 原始值 | 含义 |
|------|--------|------|
| min-active | 200 | 解码器最少保留候选数 |
| max-active | 3000 | 解码器最多活跃候选数 |
| beam | 10.0 | 搜索宽度，越大越精准但越慢 |
| lattice-beam | 2.0 | 输出候选路径宽度，越大备选越多 |
| acoustic-scale | 1.0 | 声学模型得分权重，一般不动 |
| frame-subsampling-factor | 3 | 每3帧取1帧，模型结构决定，不能改 |
| silence-phones | 1:2:3:...10 | 哪些音素算"静音"，用于 endpoint 检测 |

#### Endpoint 规则

| 参数 | 原始值 | 含义 |
|------|--------|------|
| rule2.min-trailing-silence | 0.5 | 检测到语音后，静默多久触发切分 |
| rule2.min-utterance-length | 0.0(默认) | 说话不到此秒数则 rule2 不触发 |
| rule3.min-trailing-silence | 1.0 | rule2 未触发时的兜底，静默此秒数切 |
| rule4.min-trailing-silence | 2.0 | 绝对兜底，静默此秒数必切 |

#### 调参实验记录

| 操作 | 效果 | 满意度 |
|------|------|--------|
| 只改 rule2 静默 0.5→1.0 | 分段变长了 | 不是想要的效果 |
| 只加 min-utterance-length=2.5（解码参数保持原始） | 候选最长半到三分之二输入框，短句不会只蹦单词 | **最满意** |
| beam 10→13, lattice-beam 2→4, max-active 3000→5000 | 候选又变成大段落，endpoint 规则似乎失效 | 不满意，已还原 |

#### 最佳配置（v2.5）

解码参数全部保持原始值，只加 `--endpoint.rule2.min-utterance-length=2.5`。

**注意**：解码参数（beam/lattice-beam/max-active）和 endpoint 规则互相影响，调大解码宽度后 endpoint 触发行为会改变。识别精准度问题不应通过调 beam 解决。

### 2.9 AGP 9.1.0 + KSP + Room 兼容性链（v3.0 踩坑）

v3.0 引入 Room 数据库时遇到的三连环编译问题：

1. **kotlin-android 插件冲突**：AGP 9.1.0 内置 Kotlin 编译支持，手动添加 `kotlin-android` 插件会报 `Cannot add extension with name 'kotlin', as there is an extension already registered`。**解决**：从 `build.gradle.kts` 移除 `kotlin-android` 插件，只保留 `android.application` + `ksp`。

2. **KSP sourceSets 冲突**：KSP 尝试使用 `kotlin.sourceSets` DSL 但 AGP 9 不允许。**解决**：在 `gradle.properties` 中添加 `android.disallowKotlinSourceSets=false`。

3. **Room 2.6.1 + KSP 2.x 不兼容**：Room 2.6.1 的注解处理器在 KSP 2.x 下报 `unexpected jvm signature V`。**解决**：升级 Room 从 2.6.1 到 2.7.1。

**正确配置**：
- `build.gradle.kts`（root）：只声明 `android.application` + `ksp`（不声明 `kotlin-android`）
- `app/build.gradle.kts`：只 apply `android.application` + `ksp`
- `gradle.properties`：`android.disallowKotlinSourceSets=false`
- Room 2.7.1 + KSP 2.1.0-1.0.29

### 2.10 翻译队列色块匹配问题（v3.0 踩坑）

**现象**：停止监听后重新开始，中文和俄文的段落颜色不再对应。

**原因**：`stopListening` 时翻译队列可能还有待处理的翻译请求。重新 `startListening` 后清空了俄语段落计数，但旧翻译结果仍在 channel 中排队，回调时使用了新的颜色索引。

**修复**：
- `TranslationQueue` 新增 `clear()` 方法，内部用 `AtomicInteger` 生成计数器（generation counter），`clear()` 时递增 generation
- Channel 发送 `Pair<Int, String>`（generation + text），消费端检查 generation 是否匹配当前值，不匹配则丢弃
- `startListening()` 中先调 `translationQueue.clear()` 再保存旧会话、清空 UI

---

## 3. 当前架构（v3.0）

```
麦克风 → Vosk ASR（vosk-model-small-ru-0.22，原生 VAD 分段 + min-utterance-length=2.5）
       → vosk-recasepunc（ONNX，标点/大小写恢复）
       → Gemma 3 4B-IT（llama.cpp，Q4_K_M，OpenCL GPU Adreno 830，KV Cache 前缀复用）
       → 流式 token 回调 → 彩色对照 UI（彩虹渐变 16 色）
       → Room 数据库 → 翻译历史 + 收藏管理
```

**KV Cache 前缀复用**：
- 固定 prompt 前缀在 `nativeCreate()` 中预解码，保存 KV 状态（~3.5MB）
- 每次 `nativeTranslate()` 恢复快照 + 只解码后缀（俄语文本 + 闭合标签）
- 节省 ~300-500ms/次

**翻译历史系统**（v3.0 新增）：
- Room 数据库存储翻译记录（`TranslationRecord` 实体，双标志位 `isHistory`/`isFavorite`）
- 每次开始新的监听会话时自动保存上一次会话（在 `startListening()` 中触发）
- `HistoryActivity`：双 tab（收藏/历史），支持时间筛选、排序、清理、多选操作
- 删除逻辑：从历史删除只清 `isHistory` 标志，从收藏删除只清 `isFavorite` 标志，两个标志都为 false 时物理删除
- 导出功能：生成带 BOM 的 UTF-8 文本文件到 Download 文件夹（通过 MediaStore API）

**关键文件**：
| 文件 | 作用 |
|------|------|
| `app/src/main/cpp/llama_jni.cpp` | JNI 层，KV Cache 前缀复用 + 流式 token 回调 |
| `app/src/main/cpp/CMakeLists.txt` | llama.cpp 编译配置（OpenCL ON，Vulkan OFF） |
| `app/src/main/cpp/cmake/FindOpenCL.cmake` | 自定义 OpenCL 查找（Android 交叉编译用） |
| `app/src/main/cpp/cmake/FindPython3.cmake` | 自定义 Python3 查找（kernel 嵌入用） |
| `MainActivity.kt` | 主管线调度，Vosk → Recasepunc → 翻译，会话保存 |
| `SentenceSegmenter.kt` | 自定义分段（v2.3 已注释，保留备用） |
| `VoskAsrManager.kt` | Vosk 封装 |
| `GemmaTranslator.kt` | Gemma 翻译器 Kotlin 封装 |
| `TranslationQueue.kt` | 后台翻译队列（保序 + generation counter） |
| `history/TranslationRecord.kt` | Room 实体（双标志位设计） |
| `history/TranslationDao.kt` | Room DAO（历史/收藏分离查询 + 软删除） |
| `history/AppDatabase.kt` | Room 数据库单例 |
| `history/HistoryAdapter.kt` | RecyclerView 适配器（星标 + 多选 checkbox） |
| `history/HistoryActivity.kt` | 历史/收藏管理界面 |

---

## 4. 后续升级路线

### 4.1 流式输出 ✅（v2.4 已完成）

### 4.2 Vosk 分段参数调优 ✅（v2.5 已完成，见 2.8）

### 4.3 OpenCL GPU 加速 ✅（v2.6 已完成，见 2.2）

### 4.4 翻译历史 + 收藏 ✅（v3.0 已完成，见第 3 节）

### 4.5 ASR 模型切换按钮（优先级：中，待做）
主界面加按钮，默认小模型，一键切换大模型。检测手机上已有的模型，有大模型才显示切换。

### 4.6 Vosk 参数继续调优（优先级：中，待做）
继续微调 endpoint 规则和解码参数，提升候选段落长度稳定性及个别单词识别精准度。
注意：beam/lattice-beam/max-active 与 endpoint 规则互相影响（见 2.8），需谨慎逐个测试。

### 4.7 翻译 prompt 优化（优先级：低，待做）
当前 Gemma 极偶尔输出半句英语（约每 5-10 段一次），可尝试在 prompt 中用中文强调"只输出中文"。

### 4.8 翻译模型探索（优先级：低）
- 更大模型（Gemma 12B）可能提升翻译质量，但手机内存和速度是瓶颈
- 更小模型（Gemma 1B）速度快很多，质量差一些但可能对简短句子够用

---

## 5. 构建注意事项

- **必须用 Release 构建**：Debug 模式 llama.cpp 无编译优化，速度仅为 Release 的 1/30
- llama.cpp 通过 git submodule 集成：`app/src/main/cpp/llama.cpp/`
- 首次编译 llama.cpp 约 5-10 分钟，后续增量编译很快
- Gradle sync（大象图标）随时可点，无副作用
- JNI 命名规则：包名 `ruzhtranslator` 中的下划线 → JNI 中 `ruzhtranslator`
- **AGP 9.1.0 不可添加 kotlin-android 插件**（已内置），只用 `android.application` + `ksp`
- **Room 需要 2.7.1+** 才能兼容 KSP 2.x（见 2.9）
- `gradle.properties` 必须有 `android.disallowKotlinSourceSets=false`

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

### v2.5 — Vosk 分段参数调优
- **发现 model.conf 可控制 endpointer**：Vosk 基于 Kaldi，模型目录 `conf/model.conf` 中的 endpoint 参数可直接控制分段行为，无需重编译 Vosk 或调用不存在的 API
- **调参实验**：
  - rule2 静默 0.5→1.0：分段变长但不是想要的效果
  - 加 min-utterance-length=2.5：**最佳效果** — 短句不蹦单词，长句不超过半到三分之二输入框
  - 调大 beam/lattice-beam/max-active：候选又变成大段落，endpoint 规则失效，已还原
- **最终配置**：解码参数保持原始值，只加 `--endpoint.rule2.min-utterance-length=2.5`
- **重要发现**：解码参数和 endpoint 规则互相影响，不能独立调整。识别精准度不应通过调 beam 解决

### v2.6 — OpenCL GPU 加速（Adreno 830）
- **启用 GGML_OPENCL 后端**：llama.cpp 内置完整 OpenCL 后端（12,544 行 C++ + 98 个 kernel），Adreno 830 为一等公民，有专用优化 kernel（noshuffle GEMV/GEMM）
- **编译环境搭建**：
  - 下载 KhronosGroup/OpenCL-Headers 到 `app/src/main/cpp/OpenCL-Headers/`
  - 从手机 `adb pull /system/vendor/lib64/libOpenCL.so` 作为编译时链接 stub
  - 编写自定义 `cmake/FindOpenCL.cmake` 和 `cmake/FindPython3.cmake` 绕过 NDK 交叉编译限制
- **关键踩坑 — libOpenCL.so 不能打包进 APK**：
  - 现象：App 闪退，`dlopen failed: library "libcutils.so" not found: needed by libOpenCL.so`
  - 原因：从手机 pull 的 libOpenCL.so 被打包进 APK，运行时加载 APK 内副本而非系统库，该副本依赖 `libcutils.so`（系统内部库，app namespace 不可见）
  - 修复：`build.gradle.kts` 中 `jniLibs { excludes += "**/libOpenCL.so" }`
- **AndroidManifest 声明**：`<uses-native-library android:name="libOpenCL.so" android:required="false" />`（必须放在 `<application>` 标签内，不是 `<manifest>`）
- **性能对比**（骁龙 8 Elite / Adreno 830）：

  | 指标 | CPU（v2.5） | OpenCL GPU（v2.6） | 提升 |
  |------|-----------|-------------------|------|
  | 生成速度 | ~8-10 tok/s | ~11-14 tok/s | +40% |
  | Prompt 处理 | ~700ms-3s | ~550ms-1.7s | 更快 |
  | 发热 | 严重，降频到 1.5 tok/s | 大幅改善，可持续运行 | 最大收益 |

- **已知问题**：Gemma 4B 极偶尔输出日语/英语（约每 10-15 段一次），属于多语言模型幻觉，后续通过 prompt 优化解决

### v3.0 — 翻译历史 + 收藏 + UI 大改版

**新增功能：翻译历史与收藏系统**
- **Room 数据库**（Room 2.7.1 + KSP 2.1.0-1.0.29）：`TranslationRecord` 实体，双标志位设计（`isHistory` + `isFavorite`），支持独立管理历史和收藏
- **自动保存会话**：每次点击开始监听时自动保存上一次会话的俄中文本到数据库（在 `startListening()` 中触发，避免与待处理翻译产生竞态）
- **HistoryActivity 管理界面**：
  - 底部双 tab 切换（收藏 | 历史），tab 文字旁显示条目数量
  - 顶部筛选栏：时间范围（全部/最近一周/最近一月）、排序（最新优先/最早优先）、清理（一周前/一月前/一年前）、多选按钮
  - 多选操作栏：删除、导出、复制、收藏、全选 checkbox + 已选计数
  - 条目卡片：左侧星标按钮（单击切换收藏）、标题（`MM-dd HH:mm` + 前 2 个中文字）、中文/俄文预览
  - 单击条目：弹出详情对话框（完整俄中文本 + 复制/导出按钮）
  - 长按条目：重命名对话框（预填当前名称）
- **双标志删除逻辑**：
  - 从历史 tab 删除：只清 `isHistory` 标志（如果已收藏，收藏中仍可见）
  - 从收藏 tab 删除：只清 `isFavorite` 标志（历史中仍可见）
  - 两个标志都为 false 时物理删除记录
- **清理功能**：按时间批量清理，同样遵循双标志逻辑，两次确认弹窗防误操作
- **导出功能**：通过 MediaStore API 导出为带 BOM 的 UTF-8 文本文件到系统 Download 文件夹
- **多选安全逻辑**：未选中任何条目时点击复制/导出/收藏/删除均提示"请先选择条目"；批量删除和清理操作需要二次确认

**UI 改版**
- **主界面分隔区精简**：移除分隔横线和"俄语识别""中文翻译"文字标签，只保留收藏星标（40dp）、历史时钟（40dp）、开始/停止按钮（44dp）
- **彩色段落扩展**：10 色 → 16 色彩虹渐变（无白色），颜色区分度更高
- **智能自动滚动**：俄语和中文区域分别跟踪用户滚动状态，用户手动上滚时暂停自动滚动并显示"滚动到底部"按钮，点击后恢复自动滚动
- **翻译队列 generation counter**：`TranslationQueue.clear()` 递增 generation 计数器，channel 中残留的旧 generation 翻译结果自动丢弃，解决停止/重启后色块不匹配问题
- **俄语提示文字**：输入区提示语从中文改为俄语 "Ожидание голосового ввода..."
- **深色主题 Spinner**：自定义 `spinner_item.xml` / `spinner_dropdown_item.xml`，浅色文字 + 深色下拉背景，解决系统默认黑色文字在深色界面看不清的问题

**编译兼容性修复**（见 2.9）：
- AGP 9.1.0 内置 Kotlin → 不可添加 `kotlin-android` 插件
- KSP sourceSets 冲突 → `android.disallowKotlinSourceSets=false`
- Room 2.6.1 + KSP 2.x 不兼容 → 升级 Room 到 2.7.1
