set(ANDROID_ABI arm64-v8a CACHE STRING "Android ABI")
set(ANDROID_PLATFORM android-26 CACHE STRING "Android API")
include("$ENV{ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake")
