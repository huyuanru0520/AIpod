cmake_minimum_required(VERSION 3.22.1)
project("decent_usb_audio" C CXX)

set(CMAKE_CXX_STANDARD 11)

# ── libFLAC (vendored locally; was decoder-flac module upstream) ──
# 上游 CMake 用跨模块相对路径引用 decoder-flac 的 jni 目录。
# 这里把 libflac / flac_parser / include 一并 vendor 进本模块 jni，改为本地路径。
set(FLAC_MODULE_DIR "${CMAKE_CURRENT_SOURCE_DIR}")
set(WITH_OGG OFF CACHE BOOL "" FORCE)
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
add_subdirectory("${FLAC_MODULE_DIR}/libflac"
                 "${CMAKE_CURRENT_BINARY_DIR}/libflac"
                 EXCLUDE_FROM_ALL)

# ── Main library: USB audio driver + native audio engine ──
add_library(decent_usb_audio SHARED
    usb-audio-output.cpp
    native-audio-engine.cpp
    "${FLAC_MODULE_DIR}/flac_parser.cc"
)

target_include_directories(decent_usb_audio PRIVATE
    "${FLAC_MODULE_DIR}/include"
    "${FLAC_MODULE_DIR}/libflac/include"
)

find_library(log-lib log)
target_link_libraries(decent_usb_audio
    ${log-lib}
    FLAC
)
