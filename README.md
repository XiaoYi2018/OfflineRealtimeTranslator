# 俄中实时同声传译器

完全离线的 Android 实时俄语→中文同声传译应用。无需网络，所有推理均在手机端完成。

## 技术栈

```
麦克风 → Vosk ASR（俄语语音识别）
       → vosk-recasepunc（标点/大小写恢复）
       → 句子分段器（俄语连接词智能断句）
       → Gemma 3 1B-IT（llama.cpp，俄→中翻译）
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
- Android 8.0+（API 26）
- **8GB+ 内存**（推荐 16GB）
- 约 3.5GB 存储空间用于模型文件
- 推荐骁龙 8 系列或同等性能芯片

## 模型文件（不包含在仓库中）

三个模型需手动推送到手机：

| 模型 | 大小 | 用途 | 下载地址 |
|------|------|------|----------|
| vosk-model-ru-0.42 | ~1.8GB | 俄语语音识别 | [Vosk Models](https://alphacephei.com/vosk/models) |
| vosk-recasepunc-ru-0.22 | ~680MB | 标点/大小写恢复 | [Vosk Models](https://alphacephei.com/vosk/models) |
| gemma-3-1b-it-Q4_K_M | ~700MB | 俄→中翻译引擎 | 见下方 |

### 下载 Gemma 翻译模型

```bash
pip install huggingface_hub
huggingface-cli download unsloth/gemma-3-1b-it-GGUF gemma-3-1b-it-Q4_K_M.gguf --local-dir gemma-3-1b-it-Q4_K_M
```

### 推送模型到手机

PowerShell（Windows）：

```powershell
$adb = "C:\Users\你的用户名\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dest = "/sdcard/Android/data/com.bohanli.ruzhtranslator/files/models"

& $adb push "D:\你的路径\vosk-model-ru-0.42" "$dest/vosk-model-ru-0.42/"
& $adb push "D:\你的路径\vosk-recasepunc-ru-0.22" "$dest/vosk-recasepunc-ru-0.22/"
& $adb push "D:\你的路径\gemma-3-1b-it-Q4_K_M" "$dest/gemma-3-1b-it-Q4_K_M/"
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
- **量化格式**：Gemma 3 1B-IT Q4_K_M（约 700MB，4-bit 量化）
- **线程配置**：6 线程 + n_batch=512（prompt 批处理加速）
- **智能断句**：5 级规则——句末标点 > 逗号分割 > 俄语连接词断句（и/а/но/что/когда 等 30+ 词）> 语音暂停 > 10 词强制分割
- **JNI 命名**：包名中下划线 `ruzhtranslator` → JNI 中 `ruzhtranslator`
- **Prompt 模板**：`<start_of_turn>user\nTranslate...<end_of_turn>\n<start_of_turn>model\n`

## 性能数据

骁龙 8 Elite（16GB RAM）实测：

| 指标 | 数值 |
|------|------|
| 模型加载 | ~1 秒 |
| Prompt 处理 | ~60 tok/s |
| 翻译生成 | ~40 tok/s |
| 单段翻译延迟 | 1-3 秒 |
| 语音识别延迟 | ~1 秒 |

> **重要**：必须使用 Release 构建。Debug 构建 llama.cpp 无编译优化，速度仅为 Release 的 1/30。

## 已知限制

- Gemma 1B 偶尔会在中文翻译中保留个别俄语词（人名、口语词），换更大模型可改善
- 标点恢复模型对语音片段效果有限（大小写恢复正常工作，标点预测较弱）
- 首次启动加载语音识别模型需 10-15 秒

## 开源协议

本项目使用以下开源组件：
- [Vosk](https://alphacephei.com/vosk/) — Apache 2.0
- [llama.cpp](https://github.com/ggml-org/llama.cpp) — MIT
- [Gemma](https://ai.google.dev/gemma) — Gemma Terms of Use
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) — MIT

## 版本历史

- **v2.0 — Gemma 翻译引擎 + 彩色对照**：迁移至 llama.cpp + Gemma 3 1B，新增俄语连接词断句、彩色段落对照、停止时保留未定稿文本
- **v1.0 — 初始版本**：Vosk + NLLB CTranslate2 架构
