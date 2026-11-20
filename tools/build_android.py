"""Build the Android APK from an existing ARM64 native library. Offline by default."""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def run(command, environment):
    result = subprocess.run(command, cwd=ROOT / "android", env=environment,
                            capture_output=True, text=True, encoding="utf-8", check=False)
    print(result.stdout, end="")
    print(result.stderr, end="")
    if result.returncode or re.search(r"\bwarning\b|\bdeprecated\b", result.stdout + result.stderr, re.I):
        raise RuntimeError("Build command failed or reported warnings")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--gradle", required=True, type=Path)
    parser.add_argument("--library", required=True, type=Path)
    parser.add_argument("--maven-cache", type=Path)
    parser.add_argument("--allow-downloads", action="store_true")
    args = parser.parse_args()
    if os.name != "nt":
        raise RuntimeError("This build helper supports Windows. Other build hosts are not verified.")
    toolchain = args.ndk.resolve() / "toolchains/llvm/prebuilt/windows-x86_64"
    compiler = toolchain / "bin/clang++.exe"
    runtime = toolchain / "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
    for path in (compiler, runtime, args.gradle, args.library, args.java_home / "bin/java.exe"):
        if not path.is_file():
            raise FileNotFoundError(path)
    environment = os.environ.copy()
    environment.pop("VIRTUAL_ENV", None)
    environment.update(JAVA_HOME=str(args.java_home.resolve()), ANDROID_HOME=str(args.sdk.resolve()),
                       ANDROID_SDK_ROOT=str(args.sdk.resolve()))
    if args.maven_cache:
        environment["SETUP_MAVEN_DIR"] = str(args.maven_cache.resolve())
    libraries = ROOT / "android/app/src/main/jniLibs/arm64-v8a"
    libraries.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.library, libraries / "libpocket_ffi.so")
    shutil.copyfile(runtime, libraries / "libc++_shared.so")
    run([str(compiler), "--target=aarch64-linux-android26", "-shared", "-fPIC", "-std=c++17", "-O2",
         "-Wall", "-Wextra", "-Werror", "-Wl,--no-undefined", "-Wl,-z,max-page-size=16384",
         "-I", str(ROOT / "native/pocket_ffi"), str(ROOT / "native/android/pocket_jni.cpp"),
         "-L", str(libraries), "-lpocket_ffi", "-o", str(libraries / "libpocket_jni.so")], environment)
    command = [str(args.gradle.resolve()), "--no-daemon", "--max-workers=2", "--console=plain", "--warning-mode=all"]
    if not args.allow_downloads:
        command.append("--offline")
    run(command + ["assembleRelease"], environment)
    print("PASS: Android APK build; exit code 0 and no warnings")


if __name__ == "__main__":
    main()
