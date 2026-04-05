#!/bin/bash
set -e

# Проверяем NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "❌ Ошибка: ANDROID_NDK_HOME не задана!"
    exit 1
fi

# Настройки для Android ARM64
export CC="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"
export CGO_ENABLED=1

TS_VERSION="main"

echo "[1/4] Подготовка исходников Tailscale..."
if [ ! -d "tailscale_src" ]; then
    curl -sL "https://github.com/tailscale/tailscale/archive/refs/heads/${TS_VERSION}.tar.gz" | tar -xz
    mv tailscale-${TS_VERSION} tailscale_src
fi

echo "[2/4] Инжектим фиксы и лечим импорты..."
# 1. Фикс интерфейсов берем из папки patches
cp patches/fix_android_netmon.go tailscale_src/cmd/tailscaled/

# 2. Лечим SSH (создаем пустой пакет)
mkdir -p tailscale_src/feature/ssh
echo "package ssh" > tailscale_src/feature/ssh/android_stub.go

echo "[3/4] Сборка ядра libtailscale.so..."
cd tailscale_src

# Качаем свежую anet
go get github.com/wlynxg/anet@latest
go mod tidy

# Собираем в c-shared.
# Перенесли -checklinkname=0 в -ldflags, где ему и место!
GOOS=android GOARCH=arm64 go build \
    -buildmode=c-shared \
    -ldflags="-s -w -checklinkname=0" \
    -o ../libtailscale.so ./cmd/tailscaled

cd ..

echo "[4/4] Сборка appctr.aar..."
go mod tidy
# Gomobile тоже получает этот флаг в ldflags, чтобы линкер не ругался на net.zoneCache
gomobile bind -ldflags='-s -w -buildid= -checklinkname=0' -trimpath -target="android/arm64" -androidapi 21 -o appctr.aar -v .

echo "📦 Копируем бинарник в jniLibs..."
mkdir -p ../app/src/main/jniLibs/arm64-v8a
cp libtailscale.so ../app/src/main/jniLibs/arm64-v8a/

echo "✅ Готово! Пробуй запускать."