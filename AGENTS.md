# PockeTTS contributor instructions

- Read README.md, BUILDING.md, and THIRD_PARTY.md before changing code.
- This is a personal Android reader. Only two devices have been used for phone tests: one Motorola Edge 60 Pro and one HiSense Touch Lite.
- Keep all inference local. Do not add Internet permission without an explicit design decision.
- Preserve upstream credits, source revisions, voice licences, and runtime compatibility checks.
- Do not add personal voice recordings, custom voice profiles, private books, tokens, signing keys, or device identifiers.
- Do not add audio-to-voice generation tools to this Android release.
- Read VOICE_FORMAT.md before changing profile import. Reject incompatible data.
- Keep source in UTF-8. Check process exit codes and compiler warnings.
- Get permission before downloads, dependency installations, inference, or device installation.
- Run automated tests and test changed behavior on the contributor's own phone.
- Do not report device support or speech quality from automated tests alone.
- Keep outstanding work in PLAN.md. Keep ARCHIVE.md for necessary information that cannot be recovered from code or Git.
- The original author makes no support, update, or pull-request review commitment.
