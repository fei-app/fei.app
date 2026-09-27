#!/usr/bin/env bash
set -euo pipefail

# O script está em fei.app/src/, então cd "$(dirname "$0")" já nos coloca lá
cd "$(dirname "$0")"

RUST_DIR="biblioteca_rust"
OUT_DIR="android/app/src/main/rustLibs"

if [[ ! -d "$RUST_DIR" ]]; then
  echo "ERRO: pasta $RUST_DIR não encontrada."
  echo "Verifique se o script está dentro de fei.app/src/"
  exit 1
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ERRO: ANDROID_NDK_HOME não está definido."
  echo "Exemplo:"
  echo "  export ANDROID_HOME=\$HOME/Android/Sdk"
  echo "  export ANDROID_NDK_HOME=\$ANDROID_HOME/ndk/SUA_VERSAO_DO_NDK"
  exit 1
fi

build_target() {
  local RUST_TARGET="$1"
  local ANDROID_ABI="$2"

  echo ""
  echo "=========================================="
  echo "Compilando Rust para ABI: $ANDROID_ABI"
  echo "Target Rust: $RUST_TARGET"
  echo "=========================================="

  (
    cd "$RUST_DIR"
    cargo ndk -t "$ANDROID_ABI" build --release
  )

  mkdir -p "$OUT_DIR/$ANDROID_ABI"
  cp "$RUST_DIR/target/$RUST_TARGET/release/libopenfei_core.a" \
     "$OUT_DIR/$ANDROID_ABI/libopenfei_core.a"

  echo "OK: $OUT_DIR/$ANDROID_ABI/libopenfei_core.a"
}

build_target "aarch64-linux-android" "arm64-v8a"
build_target "armv7-linux-androideabi" "armeabi-v7a"
build_target "x86_64-linux-android" "x86_64"
build_target "i686-linux-android" "x86"

echo ""
echo "=========================================="
echo "Bibliotecas Rust geradas em:"
echo "$OUT_DIR"
echo "=========================================="
ls -R "$OUT_DIR"
