#!/usr/bin/env python3
"""
Pack a flat folder of mp3 / mp4 / m4a / wav files as a `.cx` archive.

Each file becomes one lesson. The packer auto-detects audio vs video from
the file's MIME and writes the sha256 hash to `audio_hash` or `video_hash`
accordingly (the two are mutually exclusive per lesson). The course `type`
field is derived from the mix:
  - all-audio pack → "nce"   (unchanged)
  - all-video pack → "video"
  - mixed         → "mixed"

NcePlayerScreen handles both; the Kotlin side gates `hasVideo` on
`video_hash` being non-blank and routes ExoPlayer's MediaItem to the
resolved video URI.

Usage:
    pack_media_folder.py <src_folder> <out_zip> --id <course-id> --title "<显示标题>"
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

MIME_BY_EXT = {
    ".mp3": "audio/mpeg",
    ".m4a": "audio/mp4",
    ".wav": "audio/wav",
    ".opus": "audio/opus",
    ".aac": "audio/aac",
    ".mp4": "video/mp4",
    ".mov": "video/quicktime",
    ".webm": "video/webm",
}

MEDIA_EXTS = set(MIME_BY_EXT.keys())


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def humanize_filename(stem: str) -> tuple[str, str | None]:
    """Derive (title, subtitle) from a Cambridge-style media filename like
    `Thk2e_BE_L0_SB_Unit_1_p12_t01`."""
    # Replace underscores, collapse double spaces
    cleaned = stem.replace("_", " ").strip()
    # Try to pull "Unit N" and trailing "pNN tNN"
    m = re.search(r"Unit[\s_]*(\d+)(?:[\.\-_]*(\d+))?", stem, re.I)
    unit = m.group(1) if m else None
    sub_unit = m.group(2) if m and m.group(2) else None
    page_match = re.search(r"p(\d+)", stem, re.I)
    track_match = re.search(r"t(\d+)", stem, re.I)
    bits: list[str] = []
    if unit:
        if sub_unit:
            bits.append(f"Unit {unit}.{sub_unit}")
        else:
            bits.append(f"Unit {unit}")
    if page_match:
        bits.append(f"p{page_match.group(1)}")
    if track_match:
        bits.append(f"t{track_match.group(1)}")
    title = " · ".join(bits) if bits else cleaned
    return title, cleaned


def sort_key(path: Path):
    """Natural-sort by unit / page / track so the lesson order matches the book."""
    name = path.stem.lower()
    m_unit = re.search(r"unit[\s_]*(\d+)(?:[\.\-_]*(\d+))?", name)
    m_page = re.search(r"p(\d+)", name)
    m_track = re.search(r"t(\d+)", name)
    return (
        int(m_unit.group(1)) if m_unit else 0,
        int(m_unit.group(2)) if m_unit and m_unit.group(2) else 0,
        int(m_page.group(1)) if m_page else 0,
        int(m_track.group(1)) if m_track else 0,
        name,
    )


def pack(src: Path, out: Path, course_id: str, title: str, description: str) -> None:
    if not src.is_dir():
        sys.exit(f"src folder not found: {src}")
    files = sorted(
        (p for p in src.iterdir() if p.is_file() and p.suffix.lower() in MEDIA_EXTS),
        key=sort_key,
    )
    if not files:
        sys.exit(f"no media files under {src}")

    resources: list[dict] = []
    lessons: list[dict] = []
    lesson_index: list[dict] = []
    # de-dup objects by sha256 across the package
    seen_hashes: dict[str, dict] = {}
    audio_count = 0
    video_count = 0

    for idx, f in enumerate(files, start=1):
        digest = sha256_of(f)
        ext = f.suffix.lower()
        obj_path = f"objects/{digest}{ext}"
        mime = MIME_BY_EXT.get(ext, "application/octet-stream")
        if digest not in seen_hashes:
            resources.append({
                "hash": f"sha256:{digest}",
                "path": obj_path,
                "size": f.stat().st_size,
                "type": mime,
                "origin": f.name,
            })
            seen_hashes[digest] = {"src": f, "path": obj_path}
        # Split the sha into audio_hash vs video_hash by mime family so the
        # Kotlin decoder can route each lesson to the correct ExoPlayer
        # MediaItem and so `hasVideo` flips correctly on lesson change
        # instead of waiting for onVideoSizeChanged.
        is_video = mime.startswith("video/")
        sha_ref = f"sha256:{digest}"
        if is_video:
            video_count += 1
            audio_hash = ""
            video_hash = sha_ref
        else:
            audio_count += 1
            audio_hash = sha_ref
            video_hash = ""
        primary_title, subtitle = humanize_filename(f.stem)
        lesson_id = f"{course_id}-{idx:03d}"
        lessons.append({
            "id": lesson_id,
            "book": 0,
            "lesson": idx,
            "title_en": primary_title,
            "title_cn": "",
            "question": "",
            "audio_hash": audio_hash,
            "video_hash": video_hash,
            "audio_local": "",
            "audio_url": "",
            "article_html": "",
            "lines": [],
            "sections": [],
        })
        lesson_index.append({
            "id": lesson_id,
            "title": primary_title,
            "subtitle": subtitle or "",
            "audio_hash": audio_hash,
            "video_hash": video_hash,
            "metadata": {"book": 0, "lesson": idx},
        })

    if video_count > 0 and audio_count == 0:
        course_type = "video"
    elif audio_count > 0 and video_count == 0:
        course_type = "nce"
    else:
        course_type = "mixed"

    lessons_bytes = json.dumps(lessons, ensure_ascii=False, indent=2).encode("utf-8")
    lessons_digest = hashlib.sha256(lessons_bytes).hexdigest()
    lessons_path = f"objects/{lessons_digest}.json"
    resources.append({
        "hash": f"sha256:{lessons_digest}",
        "path": lessons_path,
        "size": len(lessons_bytes),
        "type": "application/json",
        "origin": "lessons.json",
    })

    manifest = {
        "format": "parrot-course-package",
        "version": 1,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "generator": "scripts/pack_media_folder.py",
        "resources": resources,
        "courses": [
            {
                "id": course_id,
                "title": title,
                "description": description,
                "type": course_type,
                "lessons_manifest": lessons_path,
                "lesson_index": lesson_index,
            }
        ],
    }
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")

    out.parent.mkdir(parents=True, exist_ok=True)
    print(f"writing {out}  ({len(files)} lessons, {len(seen_hashes)} unique objects)")
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_STORED) as zf:
        zf.writestr("manifest.json", manifest_bytes)
        zf.writestr(lessons_path, lessons_bytes)
        for digest, info in seen_hashes.items():
            zf.write(info["src"], info["path"])
    size_mb = out.stat().st_size / 1024 / 1024
    print(f"  → {size_mb:.1f} MB")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--id", required=True)
    ap.add_argument("--title", required=True)
    ap.add_argument("--description", default="")
    args = ap.parse_args()
    pack(args.src, args.out, args.id, args.title, args.description)


if __name__ == "__main__":
    main()
