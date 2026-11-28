cmake_minimum_required(VERSION 3.22.1)
project(stickcut_whisper)

# Step 90: builds whisper.cpp (downloaded by the GitHub workflow into ./whisper.cpp)
# and our small bridge library.

# Debug builds are very slow for speech recognition, so always optimise.
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp whisper_build)

add_library(stickcut_whisper SHARED stickcut_whisper.cpp)
target_link_libraries(stickcut_whisper whisper log)
