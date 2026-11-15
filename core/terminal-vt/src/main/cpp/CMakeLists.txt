cmake_minimum_required(VERSION 3.22.1)
project(termvr_vt LANGUAGES C CXX)

set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 20)

# Enforce 16 KB ELF segment alignment and strip non-deterministic GNU build IDs.
add_link_options(
    "-Wl,-z,max-page-size=16384"
    "-Wl,--build-id=none"
)

# Milestone 1 wires libghostty-vt (vt-1.0.1), FreeType, HarfBuzz, and JNI bridge sources here.
