# Build and change PockeTTS

## Start here

Give this file, AGENTS.md, README.md, and THIRD_PARTY.md to your own AI coding model.
Ask it to inspect the code before changes, preserve the pinned runtime contract, and test changes on your own phone.
This guide describes a Windows build. Builds on other host systems are not verified.
There is no supported installer for the development tools. Install them with your own toolchain manager.

## Required tools

| Tool | Version used |
| --- | --- |
| Python | 3.13; standard library only for these helpers |
| JDK | 17 |
| Gradle | 8.13; no wrapper is included |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.3.10 |
| Android SDK platform | 34 |
| Android build tools | 35.0.0 |
| Android NDK | 30.0.16248370 |
| Rust | 1.97.1 |
| cargo-ndk | 4.1.2 |
| Rust Android target | `aarch64-linux-android` |
| Native build tools | CMake, Ninja, and Visual Studio 2022 C++ tools |

The APK targets ARM64 only. Minimum API is 26. Target API is 34.
Native dependencies are pinned in `native/engine/Cargo.lock`.
Initial dependency setup needs network access. Ask permission before any AI tool starts downloads or installs.
The build helper defaults to offline mode. Use `--allow-downloads` only when you intend Gradle to contact Google Maven and Maven Central.

## Source layout

- `android/app/src/main`: Android reader, resources, manifest, and runtime contract.
- `android/core-tests`: Kotlin tests that do not need a phone.
- `native/engine`: adapted Kyutai Rust library and pinned dependency lockfile.
- `native/android`: JNI bridge and Android CMake toolchain file.
- `native/pocket_ffi`: C interface header.
- `native/pocket_tts`: upstream build-time configuration resources.
- `runtime-config`: local-path English model configuration.
- `voices`: only the selected Kyutai profiles and their source records.

## Build the Rust library

Use a Visual Studio 2022 x64 developer shell. Set `ANDROID_NDK_HOME` to the installed NDK directory.
Set `CMAKE_TOOLCHAIN_FILE` to the absolute path of `native/android/android.toolchain.cmake`.
Put Ninja on PATH and set `CMAKE_GENERATOR` to `Ninja`.
Remove an inherited `VIRTUAL_ENV` before starting build subprocesses.

From the repository root:

```powershell
cargo ndk -t arm64-v8a --platform 26 build --release --lib --locked --manifest-path native/engine/Cargo.toml
```

After dependencies are cached, add `--offline`.
The output is `native/engine/target/aarch64-linux-android/release/libpocket_ffi.so`, unless `CARGO_TARGET_DIR` sets another directory.
Do not substitute a native library from a different model revision.

## Build the APK

Set the variables below to your installed tools and the Rust build output. The paths are local choices, not fixed project paths.

```powershell
$sdk = "$env:LOCALAPPDATA/Android/Sdk"
$ndk = "$sdk/ndk/30.0.16248370"
$jdk = "C:/Program Files/Java/jdk-17"
$gradle = "C:/Tools/gradle-8.13/bin/gradle.bat"
$library = "native/engine/target/aarch64-linux-android/release/libpocket_ffi.so"
py tools/build_android.py --sdk $sdk --ndk $ndk --java-home $jdk --gradle $gradle --library $library
```

An optional `--maven-cache PATH` uses a local Maven-layout repository. Without it, Gradle uses Google Maven and Maven Central, subject to offline mode.
The helper copies the Rust library and NDK C++ runtime, compiles JNI, then builds the APK.
Add `--bundle` to put `release/pocket-runtime.zip` and the 15 files in `voices/` inside the APK (about 320 MB). The app installs them on first start. Without `--bundle`, users import the runtime ZIP and voices from **Menu > Setup**.
Output: `android/app/build/outputs/apk/release/app-release.apk`.
Signing: when `PTTS_KEYSTORE`, `PTTS_KEYSTORE_PASSWORD`, `PTTS_KEY_ALIAS`, and `PTTS_KEY_PASSWORD` are set, the release key signs the APK. Otherwise the local Android debug key signs it. An APK signed with a different key cannot update an installed one.
Keep signing keys and passwords private. Never commit them.

## Prepare the model runtime

Obtain these exact files from Kyutai. Neither repository needs an account or an agreement:

- Model: `kyutai/pocket-tts-without-voice-cloning`, revision `a7056f505a4a8ca72d8b5fb1440071d6f1a35739`, path `languages/english/model.safetensors`.
- Tokenizer: `kyutai/pocket-tts-without-voice-cloning`, revision `00eac05ed3d16bdc3f6b5d598874019c34a89214`, path `languages/english/tokenizer.json`.

The supplied voices were encoded with Kyutai's gated `kyutai/pocket-tts` model (revision `db04f0e653473e5c7333c69686ac92b7460b0c1d`), which `runtime-contract.json` names as the voice identity.
The ungated model differs only in the `mimi.encoder` tensors, which only voice cloning uses. On Windows both models produced the same audio for three voices, within one 16-bit PCM step, the same as two runs of one model.

```powershell
py tools/package_runtime.py --model C:/Models/model.safetensors --tokenizer C:/Models/tokenizer.json
```

The helper checks hashes and sizes against the app contract. It creates `release/pocket-runtime.zip` without inference or network calls. It refuses to replace an existing ZIP.
Changing model versions requires new compatible profiles and validation. Do not edit expected hashes merely to bypass a mismatch.

## Tests

```powershell
py tools/test_release.py
py tools/test_core.py --maven-cache C:/Caches/maven --java-home "C:/Program Files/Java/jdk-17"
cargo test --release --lib --locked --manifest-path native/engine/Cargo.toml
```

The core-test helper needs the seven compiler JARs listed in `tools/test_core.py`, in Maven directory layout. Obtain those exact versions from Maven Central if they are absent.
Native tests run on the Windows host in a Visual Studio developer shell. Remove Android-only CMake environment settings before host tests.
Run native tests offline once dependencies are cached. A pass requires `PASS`, exit code 0, and no warnings. Cargo reports `test result: ok`; verify its exit code and warnings before recording a native pass.

On your own phone, test runtime and profile import, EPUB/DOCX/TXT, chapters, restart positions, play/stop, seeking, timer expiry, background and screen-off playback, voice favorites, speed, and pitch.
Listen to varied text. Check logs, thermal behavior, and sound. Automated tests do not prove these device behaviors.

## Verified scope

The original app was tested only on the author's Motorola Edge 60 Pro (Android 16, Fast phone setting) and HiSense Touch Lite (Android 11, Slower phone setting).
Some integration checks and speed/pitch listening checks remain incomplete. No other phone is claimed to work.
The publication review records which checks were repeated on this exported source copy.
Contributors own testing and maintenance of their forks. The original author does not offer device support or promise updates.
