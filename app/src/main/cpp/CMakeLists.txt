# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22)
project(fernix LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- TagLib (static, only the formats Fernix needs; see spec/architecture.md §7) ---
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_BINDINGS OFF CACHE BOOL "" FORCE)
set(VISIBILITY_HIDDEN ON CACHE BOOL "" FORCE)
set(WITH_ZLIB ON CACHE BOOL "" FORCE)
set(WITH_VORBIS ON CACHE BOOL "" FORCE)    # Ogg Vorbis, Opus, FLAC
set(WITH_RIFF ON CACHE BOOL "" FORCE)      # WAV
set(WITH_APE ON CACHE BOOL "" FORCE)       # APEv2 tags inside MP3 files
set(WITH_ASF OFF CACHE BOOL "" FORCE)
set(WITH_DSF OFF CACHE BOOL "" FORCE)
set(WITH_MATROSKA OFF CACHE BOOL "" FORCE)
set(WITH_MOD OFF CACHE BOOL "" FORCE)
set(WITH_MP4 ON CACHE BOOL "" FORCE)       # AAC / ALAC in .m4a
set(WITH_SHORTEN OFF CACHE BOOL "" FORCE)
set(WITH_TRUEAUDIO OFF CACHE BOOL "" FORCE)

set(TAGLIB_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/taglib)
# Always use the utfcpp pinned as TagLib's own submodule, never a host copy.
set(utf8cpp_INCLUDE_DIR ${TAGLIB_SOURCE_DIR}/3rdparty/utfcpp/source CACHE PATH "" FORCE)
add_subdirectory(${TAGLIB_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/taglib EXCLUDE_FROM_ALL)

# --- JNI library ---
add_library(fernix SHARED tag_reader.cpp)
# TagLib's target only exports install-time include paths, so add the source tree ones.
target_include_directories(fernix PRIVATE
    ${TAGLIB_SOURCE_DIR}/taglib
    ${TAGLIB_SOURCE_DIR}/taglib/toolkit)
target_link_libraries(fernix PRIVATE tag log)
target_link_options(fernix PRIVATE -Wl,--gc-sections)
