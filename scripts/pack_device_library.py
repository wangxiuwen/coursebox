#!/usr/bin/env python3
"""
Repack one package from an app's expanded coursebox_library directory
(as extracted from an adb backup) into a .cx course package.

The on-device layout is content-addressed and flat:
    coursebox_library/
      library_index.json                  # package records + lesson_index
      packages/lessons_<sha>.json         # the lessons manifest
      objects/<sha>.opus|...              # media objects (filename = sha256)

The .cx shape follows the packager's zip: manifest.json (resources[] +
courses[]) plus objects/. Since object filenames are already their sha256,
resources[] is built by walking the directory; the lessons manifest is
copied into objects/ under its own hash and referenced from courses[].

Usage:
    pack_device_library.py <library_dir> <package_id> <output.cx>
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path

MIME_BY_EXT = {
    ".opus": "audio/opus",
    ".mp3": "audio/mpeg",
    ".m4a": "audio/mp4",
    ".aac": "audio/aac",
    ".wav": "audio/wav",
    ".mp4": "video/mp4",
    ".webm": "video/webm",
    ".json": "application/json",
}


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("library_dir", type=Path)
    ap.add_argument("package_id")
    ap.add_argument("output", type=Path)
    args = ap.parse_args()

    lib = args.library_dir
    index = json.loads((lib / "library_index.json").read_text(encoding="utf-8"))
    record = next(
        (p for p in index.get("packages", []) if p.get("id") == args.package_id), None
    )
    if record is None:
        sys.exit(f"package {args.package_id} not in library_index.json")

    lessons_src = Path(record["lessons_manifest_path"])
    # library_index stores an absolute on-device path; take the bare name.
    lessons_name = lessons_src.name
    lessons_file = lib / "packages" / lessons_name
    if not lessons_file.is_file():
        sys.exit(f"lessons manifest not found: {lessons_file}")
    lessons_bytes = lessons_file.read_bytes()
    lessons_digest = hashlib.sha256(lessons_bytes).hexdigest()
    lessons_obj = f"objects/{lessons_digest}.json"

    resources = []
    objects_dir = lib / "objects"
    if objects_dir.is_dir():
        for f in sorted(objects_dir.iterdir()):
            if not f.is_file():
                continue
            name = f.name
            digest = name.split(".")[0]
            ext = f.suffix.lower()
            actual = sha256_file(f)
            if actual != digest:
                print(f"  warn: {name} content hash mismatch ({actual[:12]}…), using actual",
                      file=sys.stderr)
            resources.append({
                "hash": f"sha256:{actual}",
                "path": f"objects/{actual}{ext}",
                "size": f.stat().st_size,
                "type": MIME_BY_EXT.get(ext, "application/octet-stream"),
            })
    resources.append({
        "hash": f"sha256:{lessons_digest}",
        "path": lessons_obj,
        "size": len(lessons_bytes),
        "type": "application/json",
    })
    resources.sort(key=lambda r: r["hash"])

    manifest = {
        "format": record.get("format", "coursebox.cx"),
        "version": record.get("version", 1),
        "generated_at": record.get("importedAt") or record.get("generatedAt") or "",
        "generator": "pack_device_library.py",
        "courses": [{
            "id": record["id"],
            "title": record.get("title", record["id"]),
            "description": record.get("description", ""),
            "type": record.get("type", "nce"),
            "metadata": record.get("metadata", {}),
            "lessons_manifest": lessons_obj,
            "lesson_index": record.get("lesson_index", []),
        }],
        "resources": resources,
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_STORED) as zf:
        zf.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        zf.writestr(lessons_obj, lessons_bytes)
        for f in sorted(objects_dir.iterdir()) if objects_dir.is_dir() else []:
            if not f.is_file():
                continue
            # Stored under the *actual* content hash we advertised.
            actual = sha256_file(f)
            ext = f.suffix.lower()
            zf.write(f, f"objects/{actual}{ext}")
    print(f"wrote {args.output}  ({len(resources)} resources)")


if __name__ == "__main__":
    main()
