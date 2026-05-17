#!/usr/bin/env python3
"""
Forced-align NCE-style lesson audio to per-line `start_ms` / `end_ms`.

Reads an existing `.coursebox.zip`, runs aeneas forced alignment per lesson
over its English text lines, writes the timestamps back into each lesson's
`lines[]`, re-hashes `lessons.json`, renames the corresponding object under
`objects/`, fixes up the manifest, and writes a new zip.

External deps (must already be on PATH):
  - espeak       (aeneas TTS backend)
  - ffmpeg       (aeneas audio decode)

Python dep:
  - aeneas       (lazy-installed via pip if missing)

Usage:
    align_lessons.py <input.coursebox.zip> <output.coursebox.zip> [--language eng]

The script preserves all non-aligned bytes (audio objects, sections, etc.);
only `lessons.json` and the manifest references to it change.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Any


# ----------------------------------------------------------------------------
# environment / dep checks
# ----------------------------------------------------------------------------

def check_external_binaries() -> None:
    """Hard-fail early if espeak / ffmpeg are missing — aeneas needs both."""
    missing: list[str] = []
    for binary in ("espeak", "ffmpeg"):
        if shutil.which(binary) is None:
            missing.append(binary)
    if missing:
        sys.stderr.write(
            "error: required binaries not on PATH: "
            + ", ".join(missing)
            + "\n"
            "Install with e.g.:\n"
            "  macOS:  brew install espeak ffmpeg\n"
            "  Debian: apt-get install -y espeak ffmpeg\n"
        )
        sys.exit(2)


def ensure_aeneas() -> None:
    """Import aeneas; if not importable, attempt a one-shot pip install."""
    try:
        import aeneas  # noqa: F401
        return
    except ImportError:
        pass
    print("aeneas not importable; running `pip install aeneas` …", file=sys.stderr)
    rc = subprocess.call([sys.executable, "-m", "pip", "install", "aeneas"])
    if rc != 0:
        sys.stderr.write(
            "error: pip install aeneas failed (rc=%d)\n"
            "See https://github.com/readbeyond/aeneas#installation for native build hints.\n"
            % rc
        )
        sys.exit(2)
    try:
        import aeneas  # noqa: F401
    except ImportError as exc:
        sys.stderr.write(f"error: aeneas still not importable after install: {exc}\n")
        sys.exit(2)


# ----------------------------------------------------------------------------
# helpers
# ----------------------------------------------------------------------------

EXT_BY_MIME = {
    "audio/mpeg": ".mp3",
    "audio/mp4": ".m4a",
    "audio/wav": ".wav",
    "audio/x-wav": ".wav",
    "audio/opus": ".opus",
    "audio/aac": ".aac",
}


def sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def find_object_path(manifest: dict, audio_hash: str) -> str | None:
    """Look up the in-zip path of an audio resource by its sha256: hash."""
    if not audio_hash:
        return None
    for r in manifest.get("resources", []):
        if r.get("hash") == audio_hash:
            return r.get("path")
    return None


def extract_english_lines(lesson: dict) -> list[str]:
    """Pick the lines to align. Prefer sections[type=text, title contains 课文];
    fall back to top-level lines[].en (or .content)."""
    sections = lesson.get("sections") or []
    for s in sections:
        if (
            isinstance(s, dict)
            and s.get("type") == "text"
            and isinstance(s.get("title"), str)
            and "课文" in s["title"]
        ):
            text = s.get("text")
            if isinstance(text, list) and text:
                return [str(t).strip() for t in text if str(t).strip()]
    out: list[str] = []
    for ln in lesson.get("lines") or []:
        if not isinstance(ln, dict):
            continue
        s = ln.get("en") or ln.get("english") or ln.get("content") or ""
        if isinstance(s, str) and s.strip():
            out.append(s.strip())
    return out


def align_lines_aeneas(
    audio_path: Path, lines: list[str], language: str, work_dir: Path
) -> list[tuple[int, int]]:
    """Run aeneas ExecuteTask, returning [(start_ms, end_ms), ...] per line."""
    # Lazy imports — aeneas init is heavy.
    from aeneas.executetask import ExecuteTask
    from aeneas.task import Task

    text_path = work_dir / "lines.txt"
    sync_path = work_dir / "sync.json"
    text_path.write_text("\n".join(lines), encoding="utf-8")

    config = (
        f"task_language={language}"
        "|is_text_type=plain"
        "|os_task_file_format=json"
    )
    task = Task(config_string=config)
    task.audio_file_path_absolute = str(audio_path)
    task.text_file_path_absolute = str(text_path)
    task.sync_map_file_path_absolute = str(sync_path)
    ExecuteTask(task).execute()
    task.output_sync_map_file()

    data = json.loads(sync_path.read_text(encoding="utf-8"))
    fragments = data.get("fragments") or []
    spans: list[tuple[int, int]] = []
    for f in fragments:
        begin = float(f.get("begin", 0.0))
        end = float(f.get("end", begin))
        spans.append((int(round(begin * 1000)), int(round(end * 1000))))
    # aeneas emits exactly one fragment per text line, but be defensive.
    if len(spans) < len(lines):
        spans.extend([(-1, -1)] * (len(lines) - len(spans)))
    return spans[: len(lines)]


def attach_timestamps(lesson: dict, spans: list[tuple[int, int]]) -> None:
    """Write start_ms / end_ms onto each lesson `lines[]` entry. If lines[]
    is empty (text came only from sections), synthesise a lines[] list using
    English-only entries so the player has something to highlight."""
    existing = lesson.get("lines")
    if isinstance(existing, list) and existing:
        for i, ln in enumerate(existing):
            if not isinstance(ln, dict):
                continue
            if i < len(spans) and spans[i][0] >= 0:
                ln["start_ms"] = spans[i][0]
                ln["end_ms"] = spans[i][1]
        return
    # Synthesise lines[] from English text only — leaves cn blank for the
    # renderer (acceptable; the highlight logic only needs start_ms/end_ms).
    text_lines = extract_english_lines(lesson)
    synthesised: list[dict[str, Any]] = []
    for i, en in enumerate(text_lines):
        entry: dict[str, Any] = {"en": en, "cn": ""}
        if i < len(spans) and spans[i][0] >= 0:
            entry["start_ms"] = spans[i][0]
            entry["end_ms"] = spans[i][1]
        synthesised.append(entry)
    lesson["lines"] = synthesised


# ----------------------------------------------------------------------------
# core pipeline
# ----------------------------------------------------------------------------

def align_package(in_zip: Path, out_zip: Path, language: str) -> None:
    if not in_zip.is_file():
        sys.exit(f"input zip not found: {in_zip}")
    out_zip.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="align-lessons-") as td:
        tmp = Path(td)
        unpack_dir = tmp / "unpack"
        unpack_dir.mkdir()
        with zipfile.ZipFile(in_zip, "r") as zf:
            zf.extractall(unpack_dir)

        manifest_path = unpack_dir / "manifest.json"
        if not manifest_path.is_file():
            sys.exit("manifest.json missing — not a coursebox package")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

        courses = manifest.get("courses") or []
        if not courses:
            sys.exit("manifest has no courses")
        course = courses[0]
        old_lessons_path = course.get("lessons_manifest")
        if not old_lessons_path:
            sys.exit("course is missing lessons_manifest")
        lessons_file = unpack_dir / old_lessons_path
        if not lessons_file.is_file():
            sys.exit(f"lessons file missing inside zip: {old_lessons_path}")
        lessons = json.loads(lessons_file.read_text(encoding="utf-8"))
        if not isinstance(lessons, list):
            sys.exit("lessons.json is not a JSON array")

        aligned_count = 0
        skipped_count = 0
        for lesson in lessons:
            if not isinstance(lesson, dict):
                continue
            audio_hash = lesson.get("audio_hash") or ""
            if not audio_hash:
                skipped_count += 1
                continue
            audio_obj_path = find_object_path(manifest, audio_hash)
            if not audio_obj_path:
                print(
                    f"  skip {lesson.get('id')}: audio hash {audio_hash[:18]}… not in manifest",
                    file=sys.stderr,
                )
                skipped_count += 1
                continue
            audio_full = unpack_dir / audio_obj_path
            if not audio_full.is_file():
                print(f"  skip {lesson.get('id')}: audio file missing on disk", file=sys.stderr)
                skipped_count += 1
                continue
            lines_to_align = extract_english_lines(lesson)
            if not lines_to_align:
                print(f"  skip {lesson.get('id')}: no English text lines", file=sys.stderr)
                skipped_count += 1
                continue

            work = tmp / f"work-{lesson.get('id', 'lesson')}"
            work.mkdir(exist_ok=True)
            print(f"  align {lesson.get('id')}  ({len(lines_to_align)} lines)")
            try:
                spans = align_lines_aeneas(audio_full, lines_to_align, language, work)
            except Exception as exc:
                print(f"    aeneas failed: {exc}", file=sys.stderr)
                skipped_count += 1
                continue
            attach_timestamps(lesson, spans)
            aligned_count += 1

        # Re-serialise lessons.json with new timestamps and re-hash.
        new_bytes = json.dumps(lessons, ensure_ascii=False, indent=2).encode("utf-8")
        new_digest = sha256_bytes(new_bytes)
        new_lessons_path = f"objects/{new_digest}.json"

        # Patch the manifest: update the resource entry for the old lessons
        # hash and the course's lessons_manifest pointer.
        old_hash = None
        for r in manifest.get("resources", []):
            if r.get("path") == old_lessons_path:
                old_hash = r.get("hash")
                r["hash"] = f"sha256:{new_digest}"
                r["path"] = new_lessons_path
                r["size"] = len(new_bytes)
                r["type"] = "application/json"
                break
        course["lessons_manifest"] = new_lessons_path

        # Sort resources by hash for reproducibility (matches packager).
        manifest["resources"] = sorted(
            manifest.get("resources", []), key=lambda r: r.get("hash", "")
        )

        # Materialise the renamed lessons file on disk so the rezip pass picks
        # it up; delete the old one.
        (unpack_dir / new_lessons_path).parent.mkdir(parents=True, exist_ok=True)
        (unpack_dir / new_lessons_path).write_bytes(new_bytes)
        if new_lessons_path != old_lessons_path:
            old = unpack_dir / old_lessons_path
            if old.is_file():
                old.unlink()

        manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
        manifest_path.write_bytes(manifest_bytes)

        # Repack zip — walk the unpack dir; never include the zip-relative
        # parent of manifest (we extracted directly to unpack_dir).
        print(
            f"writing {out_zip}  (aligned {aligned_count} lessons, "
            f"skipped {skipped_count}; lessons hash {old_hash} -> sha256:{new_digest})"
        )
        with zipfile.ZipFile(out_zip, "w", compression=zipfile.ZIP_STORED) as zf:
            for root, _dirs, files in os.walk(unpack_dir):
                for name in files:
                    abs_path = Path(root) / name
                    rel = abs_path.relative_to(unpack_dir).as_posix()
                    zf.write(abs_path, rel)


# ----------------------------------------------------------------------------
# entrypoint
# ----------------------------------------------------------------------------

def main() -> None:
    ap = argparse.ArgumentParser(
        description="Forced-align NCE coursebox audio to per-line start_ms/end_ms."
    )
    ap.add_argument("input", type=Path, help="input .coursebox.zip")
    ap.add_argument("output", type=Path, help="output .coursebox.zip")
    ap.add_argument(
        "--language",
        default="eng",
        help="aeneas task_language code (default: eng)",
    )
    ap.add_argument(
        "--skip-dep-check",
        action="store_true",
        help="skip espeak/ffmpeg/aeneas presence checks (useful for --help in CI)",
    )
    args = ap.parse_args()

    if not args.skip_dep_check:
        check_external_binaries()
        ensure_aeneas()
    align_package(args.input, args.output, args.language)


if __name__ == "__main__":
    main()
