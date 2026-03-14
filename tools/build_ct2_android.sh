#!/bin/bash
# Build CTranslate2 + SentencePiece for Android arm64-v8a in WSL2
# Run from WSL2: bash /mnt/d/AndroidWorkspace/OfflineRealtimeTranslator/tools/build_ct2_android.sh
set -e

PROJECT="/mnt/d/AndroidWorkspace/OfflineRealtimeTranslator"
JNILIBS="$PROJECT/app/src/main/jniLibs/arm64-v8a"
CT2_INCLUDE="$PROJECT/app/src/main/cpp/ctranslate2_include"

NDK_DIR="$HOME/android-ndk-r26d"
TOOLCHAIN="$NDK_DIR/build/cmake/android.toolchain.cmake"

SP_SRC="/tmp/sp"
SP_BUILD="/tmp/sp_build"
CT2_SRC="/tmp/ct2"
CT2_BUILD="/tmp/ct2_build"

# ── 1. System dependencies ─────────────────────────────────────────────────────
echo "=== Installing dependencies ==="
sudo apt-get update -qq
sudo apt-get install -y git cmake build-essential wget unzip ninja-build

# ── 2. Android NDK r26d ────────────────────────────────────────────────────────
if [ ! -d "$NDK_DIR" ]; then
    echo "=== Downloading Android NDK r26d ==="
    wget -q https://dl.google.com/android/repository/android-ndk-r26d-linux.zip -O /tmp/ndk.zip
    unzip -q /tmp/ndk.zip -d "$HOME"
    rm /tmp/ndk.zip
fi
echo "NDK: $NDK_DIR"

# ── 3. SentencePiece ───────────────────────────────────────────────────────────
echo "=== Building SentencePiece ==="
[ -d "$SP_SRC" ] || git clone --depth 1 https://github.com/google/sentencepiece "$SP_SRC"
mkdir -p "$SP_BUILD"
cmake -S "$SP_SRC" -B "$SP_BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DSPM_ENABLE_SHARED=ON \
    -DSPM_ENABLE_TCMALLOC=OFF \
    -DSPM_NO_THREADLOCAL=ON
ninja -C "$SP_BUILD"
echo "SentencePiece OK: $(ls $SP_BUILD/src/libsentencepiece.so)"

# ── 4. CTranslate2 ─────────────────────────────────────────────────────────────
echo "=== Building CTranslate2 ==="
[ -d "$CT2_SRC" ] || git clone --recursive --depth 1 \
    https://github.com/OpenNMT/CTranslate2.git "$CT2_SRC"

# Patch: stub out set_thread_affinity (Android has no pthread_setaffinity_np)
python3 << 'PYEOF'
import pathlib, re
f = pathlib.Path('/tmp/ct2/src/thread_pool.cc')
src = f.read_text()
# Replace the entire function (signature + body) with a no-op stub.
# The regex matches from the signature through the balanced closing brace,
# including any #ifdef/#endif preprocessor blocks inside.
pattern = r'(  static void set_thread_affinity\(std::thread& thread, int index\))\s*\{[^}]*(?:\{[^}]*\}[^}]*)*\}'
replacement = r'''\1 {
    (void)thread; (void)index;
    // Android: pthread_setaffinity_np not available
  }'''
new_src, count = re.subn(pattern, replacement, src, count=1, flags=re.DOTALL)
assert count == 1, f"ERROR: pattern matched {count} times (expected 1)"
f.write_text(new_src)
print('thread_pool.cc patched OK')
PYEOF

mkdir -p "$CT2_BUILD"
cmake -S "$CT2_SRC" -B "$CT2_BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DWITH_MKL=OFF \
    -DWITH_CUDA=OFF \
    -DWITH_DNNL=OFF \
    -DWITH_OPENBLAS=OFF \
    -DWITH_RUY=ON \
    -DWITH_ACCELERATE=OFF \
    -DOPENMP_RUNTIME=NONE \
    -DWITH_SENTENCEPIECE=OFF
ninja -C "$CT2_BUILD"
echo "CTranslate2 OK: $(ls $CT2_BUILD/libctranslate2.so)"

# ── 5. Copy .so to project ─────────────────────────────────────────────────────
echo "=== Copying to project ==="
mkdir -p "$JNILIBS"
cp "$CT2_BUILD/libctranslate2.so"       "$JNILIBS/"
cp "$SP_BUILD/src/libsentencepiece.so"  "$JNILIBS/"

# ── 6. Copy CTranslate2 headers ────────────────────────────────────────────────
echo "=== Copying headers ==="
mkdir -p "$CT2_INCLUDE"
cp -r "$CT2_SRC/include/ctranslate2" "$CT2_INCLUDE/"
cp -r "$CT2_SRC/include/half_float"  "$CT2_INCLUDE/"
cp -r "$CT2_SRC/include/nlohmann"    "$CT2_INCLUDE/"

# SentencePiece header
SP_INCLUDE="$PROJECT/app/src/main/cpp/sentencepiece_include"
mkdir -p "$SP_INCLUDE"
cp "$SP_SRC/src/sentencepiece_processor.h" "$SP_INCLUDE/"

echo ""
echo "======================================"
echo " Build complete!"
ls -lh "$JNILIBS/"
echo "======================================"
echo " Next: rebuild app in Android Studio"
echo "======================================"
