#!/usr/bin/env bash
# Compila o projeto para iOS (arm64) usando o SDK Darwin do xtool.
# Uso: ./build.sh [-c release] [outros argumentos do swift build]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

SDK="${SWIFT_SDK:-darwin}"
TRIPLE="${TARGET_TRIPLE:-arm64-apple-ios}"

command -v swift >/dev/null 2>&1 || {
  echo "swift não encontrado no PATH (swiftly: . ~/.local/share/swiftly/env.sh)" >&2
  exit 1
}

if ! swift sdk list | grep -x "$SDK" >/dev/null; then
  echo "SDK '$SDK' não instalado. Rode: xtool sdk install /caminho/Xcode.xip" >&2
  exit 1
fi

filter() { sed '/ld64.lld: warning: This version of lld does not support linking for platform/d'; }
build() { swift build --swift-sdk "$SDK" --triple "$TRIPLE" "$@"; }

log="$(mktemp)"
trap 'rm -f "$log"' EXIT

if build "$@" >"$log" 2>&1; then
  filter <"$log"
elif grep -q "unable to find platform" "$log"; then
  echo "[build.sh] build system padrão não achou o platform; tentando --build-system native (deprecated)..." >&2
  build --build-system native "$@" 2>&1 | filter
else
  filter <"$log"
  exit 1
fi

echo "[build.sh] OK -> $(pwd)/.build/$TRIPLE"
