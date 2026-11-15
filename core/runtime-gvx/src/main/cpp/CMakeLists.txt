cmake_minimum_required(VERSION 3.22.1)
project(termvr_pty LANGUAGES C)

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(termvr_pty SHARED
    pty_jni.c
)

target_compile_options(termvr_pty PRIVATE
    -Wall
    -Wextra
    -Werror
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
)

target_link_options(termvr_pty PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--build-id=none"
)

find_library(log-lib log)

target_link_libraries(termvr_pty PRIVATE
    ${log-lib}
)
