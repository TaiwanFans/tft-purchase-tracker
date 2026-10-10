cmake_minimum_required(VERSION 3.22.1)
project("minicpm_v_demo" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

if(DEFINED ANDROID_ABI)
    message(STATUS "TFT MiniCPM Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    else()
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    endif()
endif()

# CI pins both repositories to the exact revisions used by OpenBMB's Android demo.
if(NOT DEFINED LLAMA_SRC)
    set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../llama.cpp-omni)
endif()
if(NOT DEFINED MINICPM_APPS_SRC)
    set(MINICPM_APPS_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../minicpm-v-apps)
endif()
get_filename_component(LLAMA_SRC ${LLAMA_SRC} ABSOLUTE)
get_filename_component(MINICPM_APPS_SRC ${MINICPM_APPS_SRC} ABSOLUTE)

if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp-omni not found at ${LLAMA_SRC}")
endif()

set(OFFICIAL_ANDROID_CPP ${MINICPM_APPS_SRC}/MiniCPM-V-demo-Android/app/src/main/cpp)
if(NOT EXISTS ${OFFICIAL_ANDROID_CPP}/llama_jni.cpp)
    message(FATAL_ERROR "OpenBMB MiniCPM-V Android JNI source not found at ${OFFICIAL_ANDROID_CPP}")
endif()

add_subdirectory(${LLAMA_SRC} ${CMAKE_BINARY_DIR}/build-llama)

# Compile OpenBMB's official Android MiniCPM-V JNI bridge unchanged. The Kotlin wrapper keeps
# the original com.example.minicpm_v_demo.LlamaEngine class name so JNI symbols match exactly.
add_library(${CMAKE_PROJECT_NAME} SHARED ${OFFICIAL_ANDROID_CPP}/llama_jni.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${OFFICIAL_ANDROID_CPP}
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src
        ${LLAMA_SRC}/tools/mtmd
        ${LLAMA_SRC}/vendor)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        mtmd
        android
        log)
