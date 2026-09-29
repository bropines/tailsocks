APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
APP_PLATFORM := android-21
APP_STL := none
APP_CFLAGS += -DPKGNAME=io/github/bropines/tailscaled/core -DCLSNAME=TunVpnService
APP_LDFLAGS += -Wl,--build-id=none
# 16 KB pages: Android 15+ devices may run with 16 KB memory pages, where a
# library whose LOAD segments are aligned to 4 KB does not load at all, and
# Google Play refuses such 64-bit libraries since November 2025. Every other
# library here was already 16 KB aligned; libbyedpi.so was the one at 0x1000.
APP_LDFLAGS += -Wl,-z,max-page-size=16384
