# Voice import format

The Android importer is `android/app/src/main/java/nl/user/pockettest/LocalData.kt`.
Its runtime identity is in `android/app/src/main/assets/runtime-contract.json`.
Use a supplied profile as a schema example. Do not reuse its speaker hash for another voice.

- Extension: `.pttsvoice`. Container: ZIP, with stored/uncompressed entries.
- Exactly two files: `manifest.json` and `speaker.safetensors`.
- Schema: `android-custom-tts.voice-profile`. Profile version: 1.
- `adapter`, `adapter_version`, `model`, `configuration`, and `tokenizer` must equal the runtime identity.
- `language` must currently be `en-US`. This is an importer compatibility value; UK voice labels are still allowed.
- `producer_runtime` records the producer ID and its SHA-256. It does not require Windows on the phone.
- `speaker` records the state format, path, SHA-256, byte length, and tensor dimensions.
- Six transformer layers are required. Each F32 cache has shape `[2, 1, time, 16, 64]`.
- Each layer has an I64 `offset` with shape `[1]`. An optional zero `pad` is accepted.
- Tensor payload lengths, offsets, shapes, cache lengths, names, and values are validated.
- Settings contain `temperature`, `sampler_decode_steps`, `eos_threshold`, `max_tokens`, `frames_after_eos`, and `quantize`.
- `quantize` must be false. Unknown fields and unexpected files are rejected.
- The ZIP does not contain a model, tokenizer, or source audio.

Only an embedding made for the exact compatible model can work. A valid ZIP or matching dimensions alone do not establish model compatibility.
This repository does not supply an audio-to-voice converter. Obtain compatible community profiles from their publishers and retain their licences.
