cmake_minimum_required(VERSION 3.22.1)
project("translator")

# ---------------------------------------------------------------------------
# CTranslate2 prebuilt detection
#
# To enable real translation, place prebuilt arm64-v8a shared libraries at:
#   app/src/main/jniLibs/arm64-v8a/libctranslate2.so
#   app/src/main/jniLibs/arm64-v8a/libsentencepiece.so
#
# And optionally the CTranslate2 C++ headers at:
#   app/src/main/cpp/ctranslate2_include/
#
# Without these files, the library compiles in stub mode:
# every translate() call returns a helpful error string.
# ---------------------------------------------------------------------------

set(JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a")
set(CT2_LIB     "${JNILIBS_DIR}/libctranslate2.so")
set(SP_LIB      "${JNILIBS_DIR}/libsentencepiece.so")
set(CT2_HEADERS "${CMAKE_CURRENT_SOURCE_DIR}/ctranslate2_include")

add_library(translator SHARED ctranslate2_jni.cpp)

if(EXISTS "${CT2_LIB}" AND EXISTS "${SP_LIB}")
    message(STATUS "[translator] CTranslate2 prebuilt libs found — building with full translation")

    add_library(ctranslate2_prebuilt SHARED IMPORTED)
    set_target_properties(ctranslate2_prebuilt PROPERTIES
        IMPORTED_LOCATION "${CT2_LIB}")

    add_library(sentencepiece_prebuilt SHARED IMPORTED)
    set_target_properties(sentencepiece_prebuilt PROPERTIES
        IMPORTED_LOCATION "${SP_LIB}")

    if(EXISTS "${CT2_HEADERS}")
        target_include_directories(translator PRIVATE "${CT2_HEADERS}")
    endif()

    # SentencePiece headers
    set(SP_HEADERS "${CMAKE_CURRENT_SOURCE_DIR}/sentencepiece_include")
    if(EXISTS "${SP_HEADERS}")
        target_include_directories(translator PRIVATE "${SP_HEADERS}")
    endif()

    target_compile_definitions(translator PRIVATE CTRANSLATE2_AVAILABLE=1)
    target_link_libraries(translator
        android
        log
        ctranslate2_prebuilt
        sentencepiece_prebuilt)
else()
    message(STATUS "[translator] CTranslate2 prebuilt libs NOT found — building stub")
    target_compile_definitions(translator PRIVATE CTRANSLATE2_AVAILABLE=0)
    target_link_libraries(translator
        android
        log)
endif()
