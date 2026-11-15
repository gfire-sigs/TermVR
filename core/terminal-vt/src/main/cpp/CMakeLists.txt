cmake_minimum_required(VERSION 3.22.1)
project(termvr_vt LANGUAGES C CXX)

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(
    TERMVR_REQUIRE_UPSTREAM_STATIC
    "Fail CMake configuration when staged upstream static archives are absent"
    OFF
)
option(
    TERMVR_SANITIZE
    "Enable AddressSanitizer and UndefinedBehaviorSanitizer instrumentation"
    OFF
)

set(TERMVR_PERSISTENT_DEPS_DEFAULT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../.native-deps")
set(TERMVR_MODULE_DEPS_DEFAULT "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/native-deps")
set(TERMVR_ROOT_DEPS_DEFAULT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../build/native-deps")

if(DEFINED ANDROID_ABI AND NOT "${ANDROID_ABI}" STREQUAL "")
    set(TERMVR_TARGET_ABI_DIR "${ANDROID_ABI}")
else()
    set(TERMVR_TARGET_ABI_DIR "host")
endif()

if(DEFINED TERMVR_NATIVE_DEPS_DIR AND NOT "${TERMVR_NATIVE_DEPS_DIR}" STREQUAL "")
    set(TERMVR_RESOLVED_DEPS_ROOT "${TERMVR_NATIVE_DEPS_DIR}")
elseif(EXISTS "${TERMVR_PERSISTENT_DEPS_DEFAULT}/${TERMVR_TARGET_ABI_DIR}/lib/libghostty-vt.a")
    set(TERMVR_RESOLVED_DEPS_ROOT "${TERMVR_PERSISTENT_DEPS_DEFAULT}")
elseif(EXISTS "${TERMVR_MODULE_DEPS_DEFAULT}/${TERMVR_TARGET_ABI_DIR}/lib/libghostty-vt.a")
    set(TERMVR_RESOLVED_DEPS_ROOT "${TERMVR_MODULE_DEPS_DEFAULT}")
elseif(EXISTS "${TERMVR_ROOT_DEPS_DEFAULT}/${TERMVR_TARGET_ABI_DIR}/lib/libghostty-vt.a")
    set(TERMVR_RESOLVED_DEPS_ROOT "${TERMVR_ROOT_DEPS_DEFAULT}")
else()
    set(TERMVR_RESOLVED_DEPS_ROOT "${TERMVR_PERSISTENT_DEPS_DEFAULT}")
endif()

set(TERMVR_ABI_DEPS_DIR "${TERMVR_RESOLVED_DEPS_ROOT}/${TERMVR_TARGET_ABI_DIR}")

set(TERMVR_GHOSTTY_ARCHIVE "${TERMVR_ABI_DEPS_DIR}/lib/libghostty-vt.a")
set(TERMVR_FREETYPE_ARCHIVE "${TERMVR_ABI_DEPS_DIR}/lib/libfreetype.a")
set(TERMVR_HARFBUZZ_ARCHIVE "${TERMVR_ABI_DEPS_DIR}/lib/libharfbuzz.a")
set(TERMVR_UPSTREAM_INCLUDE_DIR "${TERMVR_ABI_DEPS_DIR}/include")

if(EXISTS "${TERMVR_GHOSTTY_ARCHIVE}"
   AND EXISTS "${TERMVR_FREETYPE_ARCHIVE}"
   AND EXISTS "${TERMVR_HARFBUZZ_ARCHIVE}"
   AND IS_DIRECTORY "${TERMVR_UPSTREAM_INCLUDE_DIR}")
    set(TERMVR_HAS_UPSTREAM_DEPS 1)
    message(STATUS "TermVR VT: linking staged upstream static archives from ${TERMVR_ABI_DEPS_DIR}")
else()
    if(TERMVR_REQUIRE_UPSTREAM_STATIC)
        message(
            FATAL_ERROR
            "TermVR VT: TERMVR_REQUIRE_UPSTREAM_STATIC is ON, but required archives or headers "
            "are missing under ${TERMVR_ABI_DEPS_DIR}. Run scripts/build-native-deps.sh first."
        )
    endif()
    set(TERMVR_HAS_UPSTREAM_DEPS 0)
    message(STATUS "TermVR VT: building self-contained C17/C++20 fallback engine")
endif()

set(TERMVR_VT_SOURCES
    vt_session_jni.cpp
    font_manager.cpp
    glyph_atlas.cpp
    gles3_renderer.cpp
)

if(TERMVR_HAS_UPSTREAM_DEPS)
    list(APPEND TERMVR_VT_SOURCES ghostty_vt_upstream.cpp)
else()
    list(APPEND TERMVR_VT_SOURCES ghostty_vt_fallback.cpp)
endif()

add_library(termvr_vt SHARED ${TERMVR_VT_SOURCES})

target_include_directories(termvr_vt PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

target_compile_definitions(
    termvr_vt
    PRIVATE
    TERMVR_HAS_UPSTREAM_DEPS=${TERMVR_HAS_UPSTREAM_DEPS}
)

target_compile_options(
    termvr_vt
    PRIVATE
    "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=."
    "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=."
    "-fstack-protector-strong"
    "-fvisibility=hidden"
    "-Wall"
    "-Wextra"
    "-Wformat"
    "-Wformat-security"
    "-Werror=format-security"
    "$<$<CONFIG:Debug>:-O1>"
    "-U_FORTIFY_SOURCE"
    "-D_FORTIFY_SOURCE=2"
)

if(ANDROID OR (UNIX AND NOT APPLE))
    target_link_options(
        termvr_vt
        PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
        "-Wl,--build-id=none"
        "-Wl,-z,relro"
        "-Wl,-z,now"
        "-Wl,-z,noexecstack"
    )
endif()

if(TERMVR_SANITIZE)
    set(TERMVR_SAN_FLAGS
        -fsanitize=address,undefined
        -fno-sanitize-recover=all
        -fno-omit-frame-pointer
        -g
    )
    target_compile_options(termvr_vt PRIVATE ${TERMVR_SAN_FLAGS})
    target_link_options(termvr_vt PRIVATE ${TERMVR_SAN_FLAGS})
endif()

if(TERMVR_HAS_UPSTREAM_DEPS)
    target_include_directories(
        termvr_vt
        PRIVATE
        "${TERMVR_UPSTREAM_INCLUDE_DIR}"
        "${TERMVR_UPSTREAM_INCLUDE_DIR}/freetype2"
        "${TERMVR_UPSTREAM_INCLUDE_DIR}/harfbuzz"
    )
    target_link_libraries(
        termvr_vt
        PRIVATE
        "${TERMVR_GHOSTTY_ARCHIVE}"
        "${TERMVR_HARFBUZZ_ARCHIVE}"
        "${TERMVR_FREETYPE_ARCHIVE}"
        "${TERMVR_HARFBUZZ_ARCHIVE}"
    )
endif()

if(ANDROID)
    find_library(ANDROID_LIB android REQUIRED)
    find_library(EGL_LIB EGL REQUIRED)
    find_library(GLESV3_LIB GLESv3 REQUIRED)
    find_library(LOG_LIB log REQUIRED)

    target_link_libraries(
        termvr_vt
        PRIVATE
        "${GLESV3_LIB}"
        "${EGL_LIB}"
        "${ANDROID_LIB}"
        "${LOG_LIB}"
    )
endif()
