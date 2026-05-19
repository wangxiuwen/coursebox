#!/usr/bin/env python3
"""
Re-encode the audio resources inside an existing `.coursebox.zip` to Opus.

Works on the zip directly — no need to find the original mp3 sources. For
each audio resource (mp3/m4a/wav) the script:

  1. extracts to a temp file
  2. runs `ffmpeg -c:a libopus -b:a <bitrate> -ac 1 -application audio`
  3. recomputes the sha256 of the opus output
  4. rewrites the resource entry in manifest.json (new hash + .opus path + size)
  5. rewrites every reference to the old hash inside lessons.json
     (audio_hash, video_hash — kept as-is for video) and the
     lesson_index in manifest.json
  6. recomputes the lessons.json digest + repoints `lessons_manifest`

Video resources are passed through untouched — re-encoding H.264 to a
different codec is a different cost/quality tradeoff and out of scope for
"shrink the audio packs".

Defaults: 32 kbps mono opus, application=audio (Opus's natural
choice for mixed speech + brief music). For English language-learning
content this is the sweet spot — clearly above the speech intelligibility
threshold and ~half the bytes of the typical 64 kbps mp3.

Usage:
    transcode_pack_to_opus.py input.coursebox.zip output.coursebox.zip \\
        [--bitrate 32k]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

AUDIO_EXTS = {".mp3", ".m4a", ".wav", ".aac", ".ogg", ".opus"}


def sha256_of_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def sha256_of_file(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def transcode(src: Path, dst: Path, bitrate: str) -> None:
    # -application audio chooses Opus's general-content mode (handles speech
    # + occasional music intros without clipping). -ac 1 = mono. -ar 24000
    # is Opus's natural narrow-band rate for speech, but we leave -ar at
    # the default (48 kHz internal) so the encoder can pick.
    cmd = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-i", str(src),
        "-c:a", "libopus",
        "-b:a", bitrate,
        "-ac", "1",
        "-application", "audio",
        "-vn",  # in case an mp3 has an embedded album art "video stream"
        str(dst),
    ]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(
            f"ffmpeg failed for {src.name}: {proc.stderr.decode(errors='replace')[:400]}",
        )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("input_zip", type=Path)
    ap.add_argument("output_zip", type=Path)
    ap.add_argument("--bitrate", default="32k", help="opus bitrate (default 32k)")
    args = ap.parse_args()

    if not args.input_zip.exists():
        sys.exit(f"missing input: {args.input_zip}")

    tmp = Path(tempfile.mkdtemp(prefix="cb-opus-"))
    try:
        with zipfile.ZipFile(args.input_zip) as zf:
            manifest = json.loads(zf.read("manifest.json"))
            # Pre-extract everything to tmp so we can stream-rewrite without
            # going back to the zip; the largest packs are a few hundred
            # MB which is fine for a workstation.
            zf.extractall(tmp)

        # Map old sha → (new sha, new ext, new path, new size) for audio
        # resources only. Video and JSON resources pass through.
        old_to_new: dict[str, dict] = {}
        new_resources = []
        audio_transcoded = 0
        bytes_before = 0
        bytes_after = 0

        for r in manifest["resources"]:
            kind = r.get("type", "")
            path = r.get("path", "")
            ext = Path(path).suffix.lower()
            if kind.startswith("audio/") or ext in AUDIO_EXTS:
                src_obj = tmp / path
                if not src_obj.exists():
                    raise RuntimeError(f"missing in zip: {path}")
                bytes_before += src_obj.stat().st_size

                # Transcode to a sibling .opus file, hash, place in new
                # objects/ layout indexed by new sha.
                dst_tmp = tmp / "_opus_workspace"
                dst_tmp.mkdir(exist_ok=True)
                interim = dst_tmp / (src_obj.stem + ".opus")
                transcode(src_obj, interim, args.bitrate)
                new_sha = sha256_of_file(interim)
                new_path = f"objects/{new_sha}.opus"
                final_obj = tmp / new_path
                final_obj.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(interim), str(final_obj))

                new_size = final_obj.stat().st_size
                bytes_after += new_size

                old_hash = r["hash"]  # "sha256:..."
                new_hash = f"sha256:{new_sha}"
                old_to_new[old_hash] = {
                    "hash": new_hash,
                    "path": new_path,
                    "size": new_size,
                    "type": "audio/ogg",
                    "origin": r.get("origin", ""),
                    "tags": r.get("tags", []),
                }
                # Drop the old object from the working tree so it's not
                # accidentally rezipped.
                src_obj.unlink()

                new_resources.append(old_to_new[old_hash])
                audio_transcoded += 1
            else:
                # Non-audio resource (video / json / image / data) passes
                # through unchanged. Lessons.json is rewritten below; we'll
                # update its hash entry at the end.
                new_resources.append(r)

        # Rewrite every lessons_manifest JSON file: swap audio_hash to the
        # new opus hash where applicable. Also fix the resource entry for
        # lessons.json itself (it has a new hash because content changed).
        course_idx_updates: dict[str, dict] = {}
        for course in manifest["courses"]:
            lm = course["lessons_manifest"]
            lm_path = tmp / lm
            if not lm_path.exists():
                raise RuntimeError(f"lessons_manifest missing: {lm}")
            lessons = json.loads(lm_path.read_text(encoding="utf-8"))
            for L in lessons:
                ah = L.get("audio_hash") or ""
                if ah and ah in old_to_new:
                    L["audio_hash"] = old_to_new[ah]["hash"]
            # Also fix lesson_index in manifest
            for li in course.get("lesson_index", []):
                ah = li.get("audio_hash") or ""
                if ah and ah in old_to_new:
                    li["audio_hash"] = old_to_new[ah]["hash"]

            new_lessons_bytes = json.dumps(lessons, ensure_ascii=False, indent=2).encode("utf-8")
            new_lm_digest = sha256_of_bytes(new_lessons_bytes)
            new_lm_path = f"objects/{new_lm_digest}.json"

            # Delete old, write new
            lm_path.unlink()
            (tmp / new_lm_path).parent.mkdir(parents=True, exist_ok=True)
            (tmp / new_lm_path).write_bytes(new_lessons_bytes)

            course["lessons_manifest"] = new_lm_path
            course_idx_updates[lm] = {
                "old_hash": f"sha256:{Path(lm).stem}",
                "new_hash": f"sha256:{new_lm_digest}",
                "new_path": new_lm_path,
                "new_size": len(new_lessons_bytes),
            }

        # Patch new_resources: any json entry whose path matches an old
        # lessons_manifest gets rewritten to the new digest. Anything else
        # passes through.
        patched_resources = []
        seen_paths: set[str] = set()
        for r in new_resources:
            updated = False
            for old_lm, info in course_idx_updates.items():
                if r.get("path") == old_lm:
                    r = {
                        **r,
                        "hash": info["new_hash"],
                        "path": info["new_path"],
                        "size": info["new_size"],
                    }
                    updated = True
                    break
            if r["path"] in seen_paths:
                continue  # dedupe — shared resources kept once
            seen_paths.add(r["path"])
            patched_resources.append(r)

        manifest["resources"] = patched_resources
        manifest["generator"] = manifest.get("generator", "") + " + transcode_pack_to_opus"

        # Write the rebuilt manifest, then re-zip everything that survived.
        (tmp / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

        # Re-zip. Stored (no compression) — opus is already compressed and
        # zip's DEFLATE on opus payloads is wasted CPU.
        out = args.output_zip
        out.parent.mkdir(parents=True, exist_ok=True)
        if out.exists():
            out.unlink()
        with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_STORED) as zf:
            zf.write(tmp / "manifest.json", "manifest.json")
            for r in manifest["resources"]:
                src = tmp / r["path"]
                if not src.exists():
                    raise RuntimeError(f"missing rebuilt resource: {r['path']}")
                zf.write(src, r["path"])

        out_size = out.stat().st_size
        in_size = args.input_zip.stat().st_size
        print(f"{args.input_zip.name}")
        print(f"  audio transcoded: {audio_transcoded} files")
        print(f"  audio bytes: {bytes_before/1e6:7.1f} MB → {bytes_after/1e6:7.1f} MB  ({bytes_after/max(1,bytes_before)*100:.0f}%)")
        print(f"  total zip:   {in_size/1e6:7.1f} MB → {out_size/1e6:7.1f} MB  ({out_size/in_size*100:.0f}%)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
