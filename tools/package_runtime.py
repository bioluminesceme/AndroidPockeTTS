"""Package already-downloaded model files. No network calls or inference."""

import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def checked_files(model, tokenizer):
    contract = json.loads((ROOT / "android/app/src/main/assets/runtime-contract.json").read_text(encoding="utf-8"))
    files = {"english-offline-v4.0.1.yaml": ROOT / "runtime-config/english-offline-v4.0.1.yaml",
             "assets/english/model.safetensors": model, "assets/english/tokenizer.json": tokenizer}
    for name, path in files.items():
        with path.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        expected = contract["files"][name]
        if digest != expected["sha256"] or path.stat().st_size != expected["size"]:
            raise ValueError(f"Runtime identity mismatch: {name}")
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--tokenizer", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "release/pocket-runtime.zip")
    args = parser.parse_args()
    files = checked_files(args.model, args.tokenizer)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "x", compression=zipfile.ZIP_STORED) as archive:
        for name, path in files.items():
            archive.write(path, name)
    print("PASS: Runtime ZIP contains the exact pinned files")


if __name__ == "__main__":
    main()
