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

# GitHub Actions checks out the exact OpenBMB-tested llama.cpp-omni revision here.
if(NOT DEFINED LLAMA_SRC)
    set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../llama.cpp-omni)
endif()
get_filename_component(LLAMA_SRC ${LLAMA_SRC} ABSOLUTE)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp-omni not found at ${LLAMA_SRC}")
endif()

add_subdirectory(${LLAMA_SRC} ${CMAKE_BINARY_DIR}/build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED llama_jni.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
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
