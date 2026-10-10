cmake_minimum_required(VERSION 3.22.1)

project(openfei_core_jni)

set(CMAKE_CXX_STANDARD 17)

add_library(openfei_core_jni SHARED
        jni_bridge.cpp
)

target_include_directories(openfei_core_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)

# Biblioteca Rust estática
add_library(openfei_core STATIC IMPORTED)

set(RUST_LIB_PATH
        ${CMAKE_CURRENT_SOURCE_DIR}/../rustLibs/${ANDROID_ABI}/libopenfei_core.a
)

set_target_properties(openfei_core PROPERTIES
        IMPORTED_LOCATION ${RUST_LIB_PATH}
)

find_library(log-lib log)

target_link_libraries(openfei_core_jni
        openfei_core
        ${log-lib}
        -Wl,--gc-sections
)