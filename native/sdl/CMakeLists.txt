cmake_minimum_required(VERSION 3.24)
project(julia_sdl LANGUAGES C)
if(NOT APPLE)
    message(FATAL_ERROR "The native timestamp extension currently implements Metal on macOS only")
endif()
include(FetchContent)
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TESTS OFF CACHE BOOL "" FORCE)
set(SDL_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SDL_INSTALL OFF CACHE BOOL "" FORCE)
FetchContent_Declare(SDL
    URL https://codeload.github.com/libsdl-org/SDL/tar.gz/refs/tags/release-3.4.12
    URL_HASH SHA256=b68381f06a7580e63400b3b6eb547ec57d8c3ebde70f9f40e0aba530ba05da27
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(SDL)
# Compile inside SDL's backend so private Metal layouts never leak into Julia's Java ABI.
set(backend "${sdl_SOURCE_DIR}/src/gpu/metal/SDL_gpu_metal.m")
file(READ "${backend}" source)
string(FIND "${source}" "#include \"gpu_timestamps.m\"" patched)
if(patched EQUAL -1)
    file(APPEND "${backend}" "\n/* Julia extension: altered SDL source. */\n#include \"gpu_timestamps.m\"\n")
endif()
target_include_directories(SDL3-shared PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

set_target_properties(SDL3-shared PROPERTIES OUTPUT_NAME SDL3Julia)
