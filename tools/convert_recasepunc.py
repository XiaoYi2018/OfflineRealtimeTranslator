#!/usr/bin/env python3
"""
Convert vosk-recasepunc (PyTorch Lightning / benob format) -> ONNX
Outputs: model.onnx + vocab.txt in the same directory as the checkpoint.

Usage:
  python convert_recasepunc.py [model_dir]
Default model_dir: D:\models\vosk-recasepunc-ru-0.22

Requirements:
  pip install torch transformers onnx onnxruntime
"""

import sys, pathlib, torch, torch.nn as nn
import pickle, types

# ── Safe unpickler: replaces ANY missing class with a harmless stub.
#    Checkpoints saved with old transformers embed the full tokenizer object
#    which we don't need at all — we only want state_dict (plain tensors). ──────
class _Stub:
    def __init__(self, *a, **kw): pass
    def __setstate__(self, state):
        if isinstance(state, dict): self.__dict__.update(state)

class _SafeUnpickler(pickle.Unpickler):
    def find_class(self, module, name):
        try:
            return super().find_class(module, name)
        except (AttributeError, ImportError, ModuleNotFoundError):
            print(f"  [stub] {module}.{name}")
            return _Stub

_safe_pickle = types.ModuleType("_safe_pickle")
_safe_pickle.Unpickler = _SafeUnpickler
_safe_pickle.load      = pickle.load
_safe_pickle.loads     = pickle.loads
_safe_pickle.dump      = pickle.dump
_safe_pickle.dumps     = pickle.dumps
_safe_pickle.HIGHEST_PROTOCOL = pickle.HIGHEST_PROTOCOL
_safe_pickle.DEFAULT_PROTOCOL = pickle.DEFAULT_PROTOCOL

MODEL_DIR = pathlib.Path(
    sys.argv[1] if len(sys.argv) > 1 else r"D:\models\vosk-recasepunc-ru-0.22"
)
OUT_DIR = MODEL_DIR

# ── 1. List files ─────────────────────────────────────────────────────────────
print("=== Files in model directory ===")
for f in sorted(MODEL_DIR.rglob("*")):
    if f.is_file():
        print(f"  {str(f.relative_to(MODEL_DIR)):<50}  {f.stat().st_size/1024/1024:.1f} MB")

# ── 2. Find checkpoint ────────────────────────────────────────────────────────
ckpt_path = None
for pat in ["*.ckpt", "checkpoint", "model.pt", "model.pth", "*.pt", "*.pth"]:
    hits = list(MODEL_DIR.glob(pat)) + list(MODEL_DIR.rglob(pat))
    if hits:
        ckpt_path = max(hits, key=lambda p: p.stat().st_size)
        break
if ckpt_path is None:
    big = [
        f for f in MODEL_DIR.rglob("*")
        if f.is_file() and f.suffix not in (".txt", ".py", ".json", ".yaml", ".md", ".cfg")
    ]
    if big:
        ckpt_path = max(big, key=lambda p: p.stat().st_size)

assert ckpt_path, "ERROR: no checkpoint file found in directory"
print(f"\n>>> Loading checkpoint: {ckpt_path}")
ckpt = torch.load(ckpt_path, map_location="cpu", weights_only=False,
                  pickle_module=_safe_pickle)

# ── 3. Extract state_dict & config ───────────────────────────────────────────
assert isinstance(ckpt, dict), f"Unexpected checkpoint type: {type(ckpt)}"
print(f"Top-level keys: {list(ckpt.keys())}")

# This checkpoint uses 'model_state_dict' (not 'state_dict')
raw_sd = ckpt.get("model_state_dict") or ckpt.get("state_dict") or ckpt
cfg    = ckpt.get("config", {})

# cfg may be a dict, a Stub object, or have a __dict__
if hasattr(cfg, "__dict__"):
    cfg = cfg.__dict__
if not isinstance(cfg, dict):
    cfg = {}
try:
    print(f"config type: {type(cfg)}")
    # Only print simple fields, avoid triggering broken __repr__
    if isinstance(cfg, dict):
        print(f"config: {cfg}")
    else:
        for attr in ("bert_type", "pretrained_model", "output_size", "num_labels", "n_labels"):
            if hasattr(cfg, attr):
                print(f"  cfg.{attr} = {getattr(cfg, attr)}")
except Exception as e:
    print(f"config: (cannot print: {e})")

print(f"\nALL model_state_dict keys:")
for k, v in raw_sd.items():
    if hasattr(v, 'shape'):
        print(f"  {k:70s}  {list(v.shape)}")
    else:
        print(f"  {k:70s}  (non-tensor: {type(v).__name__})")

# Strip common prefixes: "model.", "module."
cleaned = {}
for k, v in raw_sd.items():
    key = k
    for pfx in ("module.", "model."):
        if key.startswith(pfx):
            key = key[len(pfx):]
    cleaned[key] = v

# Determine BERT hidden size from embedding weight
hidden_size = None
for k, v in cleaned.items():
    if "word_embeddings.weight" in k:
        hidden_size = v.shape[1]
        print(f"  Embedding: vocab={v.shape[0]}, hidden={v.shape[1]}")
        break

# Detect separate punc / case heads
punc_size = cleaned["punc.weight"].shape[0] if "punc.weight" in cleaned else None
case_size = cleaned["case.weight"].shape[0] if "case.weight" in cleaned else None
print(f"  punc head: {punc_size} labels")
print(f"  case head: {case_size} labels")
assert punc_size and case_size, "ERROR: could not find punc/case heads in state_dict"

bert_type = "bert-base-multilingual-cased"   # confirmed from checkpoint
print(f"bert_type   = {bert_type}")

# ── 4. Build model ────────────────────────────────────────────────────────────
from transformers import AutoModel, AutoTokenizer

print(f"\n>>> Building BERT model ({bert_type}) ...")
try:
    base = AutoModel.from_pretrained(bert_type)
except Exception as e:
    print(f"  Cannot download from HuggingFace ({e}), reconstructing from weights ...")
    vocab_size  = cleaned["bert.embeddings.word_embeddings.weight"].shape[0]
    hidden_size = cleaned["bert.embeddings.word_embeddings.weight"].shape[1]
    num_layers  = sum(1 for k in cleaned if k.startswith("bert.encoder.layer.") and k.endswith(".attention.self.query.weight"))
    print(f"  Detected: vocab={vocab_size}, hidden={hidden_size}, layers={num_layers}")
    from transformers import BertConfig, BertModel
    cfg  = BertConfig(vocab_size=vocab_size, hidden_size=hidden_size, num_hidden_layers=max(num_layers, 12))
    base = BertModel(cfg)

# Model has two separate heads: case and punc (no shared fc)
class BertPunc(nn.Module):
    def __init__(self, base_model, n_punc, n_case):
        super().__init__()
        h = base_model.config.hidden_size
        self.bert = base_model
        self.punc = nn.Linear(h, n_punc)
        self.case = nn.Linear(h, n_case)

    def forward(self, input_ids, attention_mask):
        hidden = self.bert(input_ids, attention_mask=attention_mask).last_hidden_state
        # Return (case_logits, punc_logits) — matches RecasepuncProcessor output order
        return self.case(hidden), self.punc(hidden)

model = BertPunc(base, punc_size, case_size)

missing, unexpected = model.load_state_dict(cleaned, strict=False)
print(f"Missing keys    ({len(missing)}): {missing[:3]}")
print(f"Unexpected keys ({len(unexpected)}): {unexpected[:3]}")
model.eval()

# ── 5. Export ONNX ────────────────────────────────────────────────────────────
seq_len    = 128
dummy_ids  = torch.zeros(1, seq_len, dtype=torch.long)
dummy_mask = torch.ones(1, seq_len, dtype=torch.long)
onnx_path  = OUT_DIR / "model.onnx"

print(f"\n>>> Exporting ONNX -> {onnx_path}  (legacy single-file exporter)")
# dynamo=False forces the legacy TorchScript exporter which embeds ALL weights
# into a single self-contained .onnx file (no external data files).
torch.onnx.export(
    model,
    (dummy_ids, dummy_mask),
    str(onnx_path),
    input_names  = ["input_ids", "attention_mask"],
    output_names = ["case_logits", "punc_logits"],
    dynamic_axes = {
        "input_ids":      {1: "seq"},
        "attention_mask": {1: "seq"},
        "case_logits":    {1: "seq"},
        "punc_logits":    {1: "seq"},
    },
    opset_version = 14,
    dynamo        = False,   # legacy exporter: single self-contained file
)
print("ONNX export done.")

# ── 6. Save vocab.txt ─────────────────────────────────────────────────────────
vocab_path = OUT_DIR / "vocab.txt"
if not vocab_path.exists():
    try:
        tok   = AutoTokenizer.from_pretrained(bert_type)
        vocab = sorted(tok.vocab.items(), key=lambda x: x[1])
        vocab_path.write_text("\n".join(w for w, _ in vocab), encoding="utf-8")
        print(f"Saved vocab.txt ({len(vocab)} tokens)")
    except Exception as e:
        print(f"  HuggingFace tokenizer unavailable: {e}")
        print("  Generating placeholder vocab.txt from embedding size ...")
        n = cleaned["bert.embeddings.word_embeddings.weight"].shape[0]
        vocab_path.write_text("\n".join(f"[token_{i}]" for i in range(n)), encoding="utf-8")
        print(f"  Placeholder vocab saved ({n} entries). NOTE: WordPiece lookup will fall back to [UNK].")
else:
    print(f"vocab.txt already exists, skipping.")

# ── 7. Verify with ONNX Runtime ───────────────────────────────────────────────
import numpy as np
import onnxruntime as ort

sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
ids  = np.zeros((1, seq_len), dtype=np.int64)
mask = np.ones( (1, seq_len), dtype=np.int64)
out  = sess.run(None, {"input_ids": ids, "attention_mask": mask})

print(f"\n{'='*50}")
print(f"  output[0] case_logits : {out[0].shape}  ({out[0].shape[2]} case labels)")
print(f"  output[1] punc_logits : {out[1].shape}  ({out[1].shape[2]} punc labels)")
print(f"{'='*50}")
print("Conversion complete! Files written to:", OUT_DIR)
