# 俄中实时同声传译器

完全离线的 Android 实时俄语→中文同声传译应用。无需网络，所有推理均在手机端完成。

## 技术栈

```
麦克风 → Vosk ASR（俄语语音识别）
       → vosk-recasepunc（标点/大小写恢复）
       → 句子分段器（俄语连接词智能断句）
       → Gemma 3 4B-IT（llama.cpp，俄→中翻译）
       → 彩色对照 UI 显示
```

## 界面

- 顶部状态栏：显示当前 App 状态（加载中/监听中/翻译中）
- 开始/停止按钮
- 俄语识别区（可滚动）：实时 ASR 输出，已定稿文本彩色显示，缓冲区灰色
- 中文翻译区（可滚动）：翻译结果，与对应俄语段落颜色一致

**彩色对照功能**：每个俄语文本段和对应的中文翻译使用相同颜色，相邻段落颜色不同（10色循环），方便一眼看出翻译对应关系。

## 硬件要求

- Android 手机 arm64-v8a 架构（基本所有现代安卓手机）
- Android 10+（API 29）
- **8GB+ 内存**（推荐 16GB）
- 约 5GB 存储空间用于模型文件
- 推荐骁龙 8 系列或同等性能芯片

## 模型文件（不包含在仓库中）

三个模型需手动推送到手机：

| 模型 | 大小 | 用途 | 下载地址 |
|------|------|------|----------|
| vosk-model-small-ru-0.22 | ~50MB | 俄语语音识别（默认，轻量） | [Vosk Models](https://alphacephei.com/vosk/models) |
| vosk-model-ru-0.42 | ~1.8GB | 俄语语音识别（可选，大模型） | [Vosk Models](https://alphacephei.com/vosk/models) |
| vosk-recasepunc-ru-0.22 | ~680MB | 标点/大小写恢复 | [Vosk Models](https://alphacephei.com/vosk/models) |
| gemma-3-4b-it-Q4_K_M | ~2.5GB | 俄→中翻译引擎 | 见下方 |

> **ASR 模型对比**：经实测，小模型（50MB）与大模型（1.8GB）在清晰语音（新闻、会议、演讲）场景下识别准确率几乎无差别，但加载速度快数十倍。大模型优势在噪音环境/方言/口齿不清场景。默认使用小模型，后续版本将支持 UI 一键切换。

### 下载 Gemma 翻译模型

```bash
pip install huggingface_hub
huggingface-cli download unsloth/gemma-3-4b-it-GGUF gemma-3-4b-it-Q4_K_M.gguf --local-dir gemma-3-4b-it-Q4_K_M
```

### 推送模型到手机

PowerShell（Windows）：

```powershell
$adb = "C:\Users\你的用户名\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dest = "/sdcard/Android/data/com.bohanli.ruzhtranslator/files/models"

& $adb push "D:\你的路径\vosk-model-ru-0.42" "$dest/vosk-model-ru-0.42/"
& $adb push "D:\你的路径\vosk-recasepunc-ru-0.22" "$dest/vosk-recasepunc-ru-0.22/"
& $adb push "D:\你的路径\gemma-3-4b-it-Q4_K_M" "$dest/gemma-3-4b-it-Q4_K_M/"
```

## 编译与部署

1. Android Studio 打开项目
2. 确保安装 NDK（SDK Manager → SDK Tools → NDK）
3. **Build Variants 选择 `release`**（release 编译优化对 llama.cpp 至关重要，debug 模式会慢 25-30 倍）
4. USB 连接手机，开启 USB 调试
5. 点击绿色运行按钮
6. 首次编译会编译 llama.cpp（约 5-10 分钟），后续增量编译很快

## 项目结构

```
app/src/main/
  cpp/
    llama_jni.cpp               # JNI 桥接层（llama.cpp ↔ Kotlin）
    CMakeLists.txt              # CMake 构建配置
    llama.cpp/                  # llama.cpp 子模块（git submodule）
  java/.../
    MainActivity.kt             # 主界面 + 管线调度 + 彩色对照显示
    asr/
      VoskAsrManager.kt         # Vosk 语音识别封装
      RecasepuncProcessor.kt    # ONNX 标点/大小写恢复
    core/
      ModelManager.kt           # 模型路径管理
      AppStatus.kt              # UI 状态定义
    segmentation/
      SentenceSegmenter.kt      # 5 规则智能断句（标点/逗号/连接词/暂停/强制）
    translation/
      GemmaTranslator.kt        # Gemma 翻译器 Kotlin 封装
      TranslationQueue.kt       # 后台翻译队列（保序）
  res/layout/
    activity_main.xml           # 界面布局（深色主题）
```

## 核心技术细节

- **翻译引擎**：llama.cpp 静态链接，通过 git submodule 集成，CMake add_subdirectory 编译
- **量化格式**：Gemma 3 4B-IT Q4_K_M（约 2.5GB，4-bit 量化）
- **KV Cache 前缀复用**：固定 prompt 前缀（翻译指令部分）在模型加载时预先解码并保存 KV 缓存快照，每次翻译调用时恢复快照而非重新解码，节省约 300-500ms/次
- **流式输出**：翻译生成过程中每 2 个 token 通过 JNI 回调实时推送到 UI，打字机效果逐步显示翻译结果，视觉响应速度大幅提升
- **分段策略**：v2.3 起使用 Vosk 原生 VAD 分段；v2.5 通过 model.conf 的 `min-utterance-length=2.5` 参数优化，短句不易误切，长句自然分段
- **线程配置**：6 线程 + n_batch=512（prompt 批处理加速）
- **JNI 命名**：包名中下划线 `ruzhtranslator` → JNI 中 `ruzhtranslator`
- **Prompt 模板**：`<start_of_turn>user\nTranslate...<end_of_turn>\n<start_of_turn>model\n`

## 性能数据

骁龙 8 Elite（16GB RAM）实测：

| 指标 | 数值 |
|------|------|
| Vosk 模型加载（小模型） | <1 秒 |
| Gemma 模型加载 | ~1-2 秒 |
| KV Cache 前缀预计算 | ~5 秒（仅加载时一次） |
| Prompt 处理（含前缀复用） | 仅需解码后缀，~700ms-3s |
| 翻译生成 | ~8-10 tok/s |
| 单段翻译延迟 | 1-5 秒（视输入长度） |
| 语音识别延迟 | ~1 秒 |

> **重要**：必须使用 Release 构建。Debug 构建 llama.cpp 无编译优化，速度仅为 Release 的 1/30。

## 已知限制

- **长时间运行发热后速度下降**：手机持续高负载运行后 CPU 降频，翻译速度可能跟不上识别速度
- **Vosk 原生分段较长**：依赖 Vosk VAD 分段，单段文本可能较长（60-90 tokens），翻译耗时随之增加
- Gemma 4B 极偶尔会在中文翻译中输出半句英语（约每 5-10 段出现一次）
- Vosk 小模型偶尔在说话人磕巴时将两个短词错误合并为一个词
- 标点恢复模型对语音片段效果有限（大小写恢复正常工作，标点预测较弱）
- 纯 CPU 推理（Adreno GPU Vulkan 计算着色器与 llama.cpp 不兼容，ErrorDeviceLost）

## 开源协议

本项目使用以下开源组件：
- [Vosk](https://alphacephei.com/vosk/) — Apache 2.0
- [llama.cpp](https://github.com/ggml-org/llama.cpp) — MIT
- [Gemma](https://ai.google.dev/gemma) — Gemma Terms of Use
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) — MIT

## 版本历史

- **v2.5 — Vosk 分段调优 + 颜色优化**：通过 model.conf 的 Kaldi endpointer 参数（`min-utterance-length=2.5`）优化分段行为，短句不易误切，长句自然分段；段落颜色改为彩虹渐变序列，相邻段落颜色更协调
- **v2.4 — 流式输出**：翻译生成时每 2 个 token 实时推送到 UI（JNI 回调），打字机效果逐步显示，视觉响应大幅提升
- **v2.3 — Vosk 原生分段 + 小模型默认**：移除自定义断句规则和 partial 稳定性确认，改用 Vosk 原生 VAD 分段，段落语义完整性更好；ASR 默认切换至 vosk-model-small-ru-0.22（50MB），加载速度大幅提升，清晰语音场景识别准确率与大模型（1.8GB）几乎无差别
- **v2.2 — KV Cache 前缀复用 + Partial 稳定性确认**：固定 prompt 前缀预解码并缓存 KV 状态，每次翻译恢复快照而非重新解码（~300-500ms/次）；跟踪 Vosk partial 结果稳定性，前缀词连续多次不变则提前确认送入翻译管线，降低整体延迟
- **v2.1 — Gemma 4B 升级 + 连词精简**：翻译模型从 Gemma 3 1B 升级至 4B（Q4_K_M），翻译质量显著提升；连词断句列表从 33 个精简至 6 个强句界连词（но/однако/поэтому/хотя/зато/потому），减少碎片化断句
- **v2.0 — Gemma 翻译引擎 + 彩色对照**：迁移至 llama.cpp + Gemma 3 1B，新增俄语连接词断句、彩色段落对照、停止时保留未定稿文本
- **v1.0 — 初始版本**：Vosk + NLLB CTranslate2 架构
