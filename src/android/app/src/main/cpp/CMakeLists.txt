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

# ★ Seções por função/dado: permite ao --gc-sections descartar código morto.
target_compile_options(openfei_core_jni PRIVATE
        -ffunction-sections
        -fdata-sections
)

target_link_options(openfei_core_jni PRIVATE
        "-Wl,--gc-sections"
        # ★ PLAY STORE: alinhamento de 16 KB dos segmentos LOAD (obrigatório
        # para apps com código nativo). Verifique com:
        #   llvm-readelf -l libopenfei_core_jni.so | grep LOAD   → Align 0x4000
        "-Wl,-z,max-page-size=16384"
        # ★ Não exporta símbolos da lib estática do Rust (openfei_login etc.)
        # na tabela dinâmica do .so — só os Java_* do bridge precisam sair.
        # Reduz a dynsym e o tamanho do binário.
        "-Wl,--exclude-libs,ALL"
)

target_link_libraries(openfei_core_jni
        openfei_core
        ${log-lib}
)