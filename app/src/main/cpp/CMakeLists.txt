# Builds libneuro_llama.so from llama.cpp sources (pinned submodule). No prebuilt binaries (F-Droid rule).
cmake_minimum_required(VERSION 3.22.1)
project(neuro_llama LANGUAGES C CXX)

# Reproducible builds: strip absolute paths of BOTH the sources and the build tree.
# CMake compiles its compiler-probe objects (CMakeCCompilerId.o) inside the binary dir,
# so mapping only the sources is not enough.
set(_NEURO_PATH_MAP "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=. -ffile-prefix-map=${CMAKE_BINARY_DIR}=. -fdebug-prefix-map=${CMAKE_BINARY_DIR}=.")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${_NEURO_PATH_MAP}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${_NEURO_PATH_MAP}")
# Android does not use RPATH; keep build paths out of the ELF.
set(CMAKE_SKIP_RPATH TRUE)
set(CMAKE_SKIP_BUILD_RPATH TRUE)
set(CMAKE_SKIP_INSTALL_RPATH TRUE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Reduce the native build to what we need.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

set(LLAMA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp submodule missing: run `git submodule update --init --recursive`")
endif()
add_subdirectory("${LLAMA_SRC}" llama_build EXCLUDE_FROM_ALL)

add_library(neuro_llama SHARED neuro_llama_jni.cpp)
target_include_directories(neuro_llama PRIVATE "${LLAMA_SRC}/include")
target_link_libraries(neuro_llama PRIVATE llama log android)
