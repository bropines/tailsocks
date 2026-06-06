APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
APP_PLATFORM := android-21
APP_STL := none
APP_CFLAGS += -DPKGNAME=io/github/bropines/tailscaled/core -DCLSNAME=TunVpnService
APP_LDFLAGS += -Wl,--build-id=none
