# Third-party credits

## Kyutai Pocket TTS

Kyutai created Pocket TTS. Its listed authors are Manu Orsini, Simon Rouard, Gabriel de Marmiesse, Václav Volhejn, Neil Zeghidour, and Alexandre Défossez.
Source: https://github.com/kyutai-labs/pocket-tts
Pinned commit: `a922b912dfa366975294afb568d815b305acde5d`.
The original MIT terms are preserved in [licenses/pocket-tts-MIT.txt](licenses/pocket-tts-MIT.txt).

`native/engine` contains the adapted Rust library. `native/pocket_tts` contains upstream configuration and the static page required by its build script. No upstream web server or CLI is built by this project.
`native/provenance.json` records module checksums and whether each module differs from the pinned upstream source.
PockeTTS adds the C interface, tensor validation, local file loader, JNI bridge, and Android reader. The model queue and missing-end-marker behavior differ from upstream.

## xn-ptts and xn

The pinned dependency comes from Gabriel de Marmiesse's branch of [Gradium's xn-ptts](https://github.com/gradium-ai/xn-ptts).
Source: https://github.com/gabrieldemarmiesse/xn-ptts
Pinned commit: `7efa26973f81bba42cf2ace8da2c1eb9b605d54d`.
Its MIT and Apache-2.0 licence texts are in `licenses/`.
`xn` is Laurent Mazare's tensor library (https://github.com/LaurentMazare/xn), MIT or Apache-2.0. The lockfile resolves it to 0.2.12.

## Other Rust crates

[licenses/rust-crates.md](licenses/rust-crates.md) lists all 173 crates compiled into the Android native library, with version, licence, and repository.
Exact versions are in `native/engine/Cargo.lock`. Cargo downloads the crates at build time. This repository does not copy their code.
Their licences apply when you distribute a built APK or library. Most crates use MIT, Apache-2.0, or both.
The Symphonia audio crates by Philip Deljanov use MPL-2.0. They are used unmodified. Their source is at https://github.com/pdeljanov/Symphonia.

## Android build

The Android app uses no third-party Android libraries. It uses only the Android platform API.
A built APK also contains the Kotlin standard library (JetBrains, Apache-2.0) and `libc++_shared.so` from the Android NDK (LLVM project, Apache-2.0 with LLVM exceptions).

## Voices

[voices/catalog.json](voices/catalog.json) records each source URL, licence, source-audio checksum, and profile checksum.
Recordings were converted into a Pocket TTS state and packaged for PockeTTS. Raw recordings are not included.

- VCTK: Junichi Yamagishi, Christophe Veaux, and Kirsten MacDonald. CSTR VCTK Corpus, version 0.92 (2019), University of Edinburgh, CSTR. https://doi.org/10.7488/ds/2645. CC BY 4.0. Kyutai supplies enhanced versions of selected recordings.
- Voice-Zero: the named LibriVox readers and the [Voice-Zero project](https://github.com/OwenTyme/voice-zero). Kyutai identifies its selected files as CC0.
- EARS: the [EARS dataset authors](https://github.com/facebookresearch/ears_dataset) and its participating speaker p010. Kyutai supplies the enhanced Jean recording. CC BY-NC 4.0; non-commercial use only.

Licence links: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/), [CC0](https://creativecommons.org/publicdomain/zero/1.0/).
The source licence listing is [Kyutai's voice card](https://huggingface.co/kyutai/tts-voices).
No endorsement by a speaker, dataset author, Kyutai, or Gradium is implied.

## Screenshot book

Arthur Conan Doyle, *The Adventures of Sherlock Holmes*. Project Gutenberg ebook 1661.
https://www.gutenberg.org/ebooks/1661
Electronic edition credits: an anonymous Project Gutenberg volunteer and Jose Menendez.
The source page identifies it as public domain in the USA. Screenshots show the reader and chapter list. The EPUB is not redistributed here.
EPUB SHA-256 used for screenshots: `310ddf29f2b98b870b0e970061d236c9ce7c65163e0e172c254a1db3d070d3f4`.
