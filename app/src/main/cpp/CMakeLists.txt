cmake_minimum_required(VERSION 3.22.1)
project("translator")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# llama.cpp configuration — matched to working OfflineTranslator config
# ---------------------------------------------------------------------------
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)

# --- OpenCL setup for Android (Adreno) ---
list(INSERT CMAKE_MODULE_PATH 0 "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
# Headers from KhronosGroup/OpenCL-Headers, stub .so pulled from device
set(OpenCL_FOUND TRUE)
set(OpenCL_INCLUDE_DIRS "${CMAKE_CURRENT_SOURCE_DIR}/OpenCL-Headers" CACHE PATH "" FORCE)
set(OpenCL_LIBRARIES "${CMAKE_CURRENT_SOURCE_DIR}/libOpenCL.so" CACHE FILEPATH "" FORCE)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

# ---------------------------------------------------------------------------
# Our JNI library
# ---------------------------------------------------------------------------
add_library(translator SHARED llama_jni.cpp)

target_include_directories(translator PRIVATE
    llama.cpp/include
)

target_link_libraries(translator
    android
    log
    llama
)
