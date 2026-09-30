#!/usr/bin/env python3
"""
Forced-align NCE-style lesson audio to per-sentence start_ms/end_ms using
local whisper.cpp ASR — the follow-up to align_lessons.py.

Why not aeneas: these packages carry *paragraph*-sized transcript lines
(one "line" = several sentences, and dialogue quotes break naive sentence
pairing), so aeneas's line-level alignment still leaves the player
averaging inside a paragraph. Whisper gives real sentence-level timings;
we fuzzy-match its transcript against the lesson text (the recording and
the text edition differ) and write one sentence per line entry.

Per lesson:
  1. ffmpeg → 16 kHz mono wav
  2. whisper-cli (-oj) → sentence segments with timestamps
  3. split lesson paragraphs into sentences (same regexes as the app)
  4. Needleman–Wunsch align ASR sentences ↔ text sentences on
     normalised text (SequenceMatcher ratio)
  5. matched sentences take the ASR timestamps; unmatched ones are
     interpolated between their matched neighbours
  6. lines[] is replaced with sentence-grained entries (en, cn, start_ms,
     end_ms); Chinese pairs by rounded index ratio, like the app does

The lessons manifest is re-hashed and the package repacked exactly like
align_lessons.py does.

Usage:
    align_whisper.py <input.cx|.zip> <output.cx> \
        [--model ggml-*.bin] [--course <id>] [--limit N] [--language en]

External deps: whisper-cli, ffmpeg (on PATH).
"""
from __future__ import annotations

import argparse
import bisect
import difflib
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Any

# Same sentence splitters as NcePlayerScreen.expandToSentences.
EN_SENTENCE_SPLIT = re.compile(r"(?<=[.!?])\s+(?=[A-Z\"'‘“])")
CN_SENTENCE_SPLIT = re.compile(r"(?<=[。！？!?])\s*")

# Normalise for matching: letters/digits/space only, lowercased.
_NORM_RE = re.compile(r"[^a-z0-9 ]+")


def check_bins() -> None:
    missing = [b for b in ("whisper-cli", "ffmpeg") if shutil.which(b) is None]
    if missing:
        sys.exit(f"error: required binaries not on PATH: {', '.join(missing)}")


def sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def norm(s: str) -> str:
    return " ".join(_NORM_RE.sub(" ", s.lower()).split())


def find_object_path(manifest: dict, audio_hash: str) -> str | None:
    if not audio_hash:
        return None
    for r in manifest.get("resources", []):
        if r.get("hash") == audio_hash:
            return r.get("path")
    return None


def find_audio_for_lesson(manifest: dict, lesson: dict) -> str | None:
    """audio_hash if present; else match audio_local against the
    resources' origin / lesson:<id> tag (older packages carry the audio
    as a logical path, not an inline hash)."""
    direct = find_object_path(manifest, lesson.get("audio_hash") or lesson.get("video_hash") or "")
    if direct:
        return direct
    local = (lesson.get("audio_local") or "").strip()
    if local.startswith("assets/"):
        local = local[len("assets/"):]
    lid = str(lesson.get("id") or "")
    for r in manifest.get("resources", []):
        origin = (r.get("origin") or "").strip()
        if origin.startswith("assets/"):
            origin = origin[len("assets/"):]
        if local and (origin == local or origin.endswith("/" + local)):
            return r.get("path")
        if lid and f"lesson:{lid}" in (r.get("tags") or []):
            return r.get("path")
    return None


# ----------------------------------------------------------------------------
# sentence splitting — mirrors the app
# ----------------------------------------------------------------------------

def build_lines(lesson: dict) -> list[dict]:
    """Reproduce NceLesson.kt's decode order exactly: en/cn lines win;
    otherwise fold the 课文/翻译 sections into parallel lines; otherwise
    split "English — 中文" rows of the first text section."""
    lines: list[dict] = []
    for ln in lesson.get("lines") or []:
        if not isinstance(ln, dict):
            continue
        en = (ln.get("en") or ln.get("english") or "").strip()
        cn = (ln.get("cn") or ln.get("chinese") or "").strip()
        if en or cn:
            lines.append({"en": en, "cn": cn,
                          "start_ms": ln.get("start_ms", -1) or -1,
                          "end_ms": ln.get("end_ms", -1) or -1})
    if lines:
        return lines
    sections = [s for s in (lesson.get("sections") or []) if isinstance(s, dict)]
    en = next((s.get("text") for s in sections
               if s.get("type") == "text" and "课文" in (s.get("title") or "")), None) or []
    cn = next((s.get("text") for s in sections
               if s.get("type") == "text" and "翻译" in (s.get("title") or "")), None) or []
    if en or cn:
        n = max(len(en), len(cn))
        return [{"en": str(en[i]).strip() if i < len(en) else "",
                 "cn": str(cn[i]).strip() if i < len(cn) else "",
                 "start_ms": -1, "end_ms": -1} for i in range(n)]
    merged = next((s.get("text") for s in sections
                   if s.get("type") == "text" and s.get("text")), None) or []
    out = []
    leading_number = re.compile(r"^\s*\d+[.、)]\s*")
    for raw in merged:
        stripped = leading_number.sub("", str(raw)).strip()
        parts = re.split(r"\s*[—–]\s*", stripped, maxsplit=1)
        out.append({"en": parts[0].strip(),
                    "cn": parts[1].strip() if len(parts) == 2 else "",
                    "start_ms": -1, "end_ms": -1})
    return out


def lesson_sentence_pools(lesson: dict) -> tuple[list[str], list[str]]:
    """All English / Chinese sentences of the lesson, in order. Pooling is
    global: pairing within a paragraph misaligns badly when a paragraph's
    translation splits into a different number of sentences (NCE
    translations are free paraphrases)."""
    lines = build_lines(lesson)
    en_all: list[str] = []
    cn_all: list[str] = []
    for ln in lines:
        en_all += [p.strip() for p in EN_SENTENCE_SPLIT.split(ln.get("en") or "") if p.strip()]
        cn_all += [p.strip() for p in CN_SENTENCE_SPLIT.split(ln.get("cn") or "") if p.strip()]
    # Fold pure-punctuation leftovers (quoted-speech ”。) into the
    # previous translation.
    folded: list[str] = []
    for p in cn_all:
        if folded and len(re.sub(r"[\s“”„\"'‘’。！？，、；：()（）]", "", p)) < 4:
            folded[-1] += p
        else:
            folded.append(p)
    return en_all, folded


def pair_chinese_by_time(
    en_timed: list[tuple[str, int, int]], cn_all: list[str]
) -> list[str]:
    """One Chinese sentence per English sentence. The translations are laid
    over the lesson's [first_start, last_end] span weighted by character
    count (translation length ≈ spoken duration, even for free
    translations), and each English sentence takes the translation its
    midpoint falls into. Index-ratio pairing stacked the first sentences
    onto the translation's first line; this follows the actual timings."""
    if not cn_all:
        return [""] * len(en_timed)
    weights = [max(len(c), 1) for c in cn_all]
    total = sum(weights)
    t0 = en_timed[0][1]
    t1 = max(e for _, _, e in en_timed)
    span = max(t1 - t0, 1)
    bounds = [t0]
    acc = 0
    for w in weights:
        acc += w
        bounds.append(t0 + span * acc / total)
    out = []
    for _, s, e in en_timed:
        mid = (s + e) / 2
        k = bisect.bisect_right(bounds, mid) - 1
        k = min(max(k, 0), len(cn_all) - 1)
        out.append(cn_all[k])
    return out


# ----------------------------------------------------------------------------
# whisper transcription
# ----------------------------------------------------------------------------

def transcribe(audio_path: Path, model: Path, work: Path, language: str,
               cache_dir: Path | None) -> list[dict]:
    """Run whisper-cli, return [{'start_ms','end_ms','text'}]. Results are
    cached by audio content hash — re-running the alignment to tweak
    pairing/matching skips the (expensive) transcription."""
    cache_file = None
    if cache_dir is not None:
        cache_dir.mkdir(parents=True, exist_ok=True)
        cache_file = cache_dir / f"{sha256_file(audio_path)}.json"
        if cache_file.is_file():
            return json.loads(cache_file.read_text(encoding="utf-8"))
    wav = work / "audio16k.wav"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(audio_path),
         "-ar", "16000", "-ac", "1", str(wav)],
        check=True,
    )
    out_prefix = work / "asr"
    proc = subprocess.run(
        ["whisper-cli", "-m", str(model), "-f", str(wav), "-oj", "-of", str(out_prefix),
         "-l", language, "-pp"],
        capture_output=True, text=True,
    )
    json_path = Path(f"{out_prefix}.json")
    if not json_path.is_file():
        sys.exit(f"whisper-cli produced no json (rc={proc.returncode}):\n{proc.stderr[-2000:]}")
    data = json.loads(json_path.read_text(encoding="utf-8"))

    segments = []
    for seg in data.get("transcription", []):
        text = (seg.get("text") or "").strip()
        if not text:
            continue
        # whisper timestamps "00:00:01,240" → ms
        def to_ms(stamp: str) -> int:
            h, m, rest = stamp.split(":")
            s, ms = rest.split(",")
            return ((int(h) * 60 + int(m)) * 60 + int(s)) * 1000 + int(ms)
        start = to_ms(seg["timestamps"]["from"])
        end = to_ms(seg["timestamps"]["to"])
        segments.append({"start_ms": start, "end_ms": end, "text": text})
    if cache_file is not None:
        cache_file.write_text(json.dumps(segments, ensure_ascii=False), encoding="utf-8")
    return segments


def refine_segment_boundaries(segments: list[dict]) -> list[dict]:
    """Split each whisper segment into sentences, interpolating timestamps
    within the segment by character share."""
    out = []
    for seg in segments:
        text = seg["text"].strip()
        parts = [p.strip() for p in EN_SENTENCE_SPLIT.split(text) if p.strip()]
        if len(parts) <= 1:
            out.append(seg)
            continue
        total = sum(len(p) for p in parts)
        span = seg["end_ms"] - seg["start_ms"]
        pos = seg["start_ms"]
        for p in parts:
            dur = int(span * len(p) / max(total, 1))
            out.append({"start_ms": pos, "end_ms": pos + dur, "text": p})
            pos += dur
    return out


# ----------------------------------------------------------------------------
# Needleman–Wunsch fuzzy alignment
# ----------------------------------------------------------------------------

def align_sentences(
    asr: list[dict], text_pairs: list[tuple[str, str]], min_ratio: float = 0.5
) -> list[int | None]:
    """For each text sentence, the matching ASR index or None.
    Needleman–Wunsch global alignment so order is respected and greedy
    mismatches are avoided; a genuine match (ratio >= min_ratio) must
    beat skipping both sides (2*ratio-1 >= -1)."""
    n, m = len(text_pairs), len(asr)
    if n == 0 or m == 0:
        return [None] * n
    t_norm = [norm(t) for t, _ in text_pairs]
    a_norm = [norm(s["text"]) for s in asr]

    ratio_cache: dict[tuple[int, int], float] = {}

    def ratio(i: int, j: int) -> float:
        key = (i, j)
        if key not in ratio_cache:
            ratio_cache[key] = difflib.SequenceMatcher(None, t_norm[i], a_norm[j]).ratio()
        return ratio_cache[key]

    gap = -0.35

    def match_gain(i: int, j: int) -> float:
        r = ratio(i, j)
        return (2.0 * r - 1.0) if r >= min_ratio else -1.0

    score = [[0.0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        score[i][0] = score[i - 1][0] + gap
    for j in range(1, m + 1):
        score[0][j] = score[0][j - 1] + gap
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            score[i][j] = max(
                score[i - 1][j - 1] + match_gain(i - 1, j - 1),
                score[i - 1][j] + gap,
                score[i][j - 1] + gap,
            )

    # Traceback: prefer diag (match/skip-pair), then up (text gap), then left.
    EPS = 1e-9
    match_of_text: list[int | None] = [None] * n
    i, j = n, m
    while i > 0 and j > 0:
        if abs(score[i][j] - (score[i - 1][j - 1] + match_gain(i - 1, j - 1))) < EPS:
            if ratio(i - 1, j - 1) >= min_ratio:
                match_of_text[i - 1] = j - 1
            i, j = i - 1, j - 1
        elif abs(score[i][j] - (score[i - 1][j] + gap)) < EPS:
            i -= 1
        else:
            j -= 1
    return match_of_text


def timestamps_for(
    text_pairs: list[tuple[str, str]],
    asr: list[dict],
    matches: list[int | None],
) -> list[tuple[int, int]]:
    """(start_ms, end_ms) per text sentence: matched → ASR times,
    otherwise interpolate between neighbouring matched anchors."""
    n = len(text_pairs)
    spans: list[tuple[int, int] | None] = [None] * n
    for i, j in enumerate(matches):
        if j is not None:
            spans[i] = (asr[j]["start_ms"], asr[j]["end_ms"])
    last_end = 0
    i = 0
    while i < n:
        if spans[i] is not None:
            last_end = spans[i][1]
            i += 1
            continue
        # find next anchor
        k = i
        while k < n and spans[k] is None:
            k += 1
        next_start = spans[k][0] if k < n else (asr[-1]["end_ms"] if asr else last_end)
        count = k - i
        step = max((next_start - last_end) // (count + 1), 500)
        for t in range(count):
            start = last_end + step * t
            spans[i + t] = (start, start + step)
        if k < n:
            last_end = spans[k][1]
        i = k
    return [s for s in spans if s is not None]


# ----------------------------------------------------------------------------
# per-lesson alignment
# ----------------------------------------------------------------------------

def align_lesson(
    lesson: dict, audio_full: Path, model: Path, work: Path, language: str,
    cache_dir: Path | None = None,
) -> bool:
    en_all, cn_all = lesson_sentence_pools(lesson)
    if not en_all:
        return False
    segments = refine_segment_boundaries(
        transcribe(audio_full, model, work, language, cache_dir)
    )
    if not segments:
        return False
    matches = align_sentences(segments, [(e, "") for e in en_all])
    spans = timestamps_for([(e, "") for e in en_all], segments, matches)
    cns = pair_chinese_by_time(
        [(e, s, e2) for e, (s, e2) in zip(en_all, spans)], cn_all
    )
    lesson["lines"] = [
        {"en": en_all[i], "cn": cns[i], "start_ms": spans[i][0], "end_ms": spans[i][1]}
        for i in range(len(en_all))
    ]
    return True


# ----------------------------------------------------------------------------
# package plumbing — same conventions as align_lessons.py
# ----------------------------------------------------------------------------

def align_package(in_zip: Path, out_zip: Path, model: Path, language: str,
                  course_id: str | None, limit: int | None,
                  cache_dir: Path | None = None) -> None:
    if not in_zip.is_file():
        sys.exit(f"input zip not found: {in_zip}")
    out_zip.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="align-whisper-") as td:
        tmp = Path(td)
        unpack_dir = tmp / "unpack"
        unpack_dir.mkdir()
        with zipfile.ZipFile(in_zip, "r") as zf:
            zf.extractall(unpack_dir)
        _align_in_dir(unpack_dir, out_zip, model, language, course_id, limit, cache_dir)


def align_dir(unpack_dir: Path, out_zip: Path, model: Path, language: str,
              course_id: str | None, limit: int | None,
              cache_dir: Path | None = None) -> None:
    """Package already unpacked (multi-part packs: unzip every part into
    the same directory — each part is its own zip; manifest comes from
    the first, objects merge)."""
    _align_in_dir(unpack_dir, out_zip, model, language, course_id, limit, cache_dir)


def _align_in_dir(unpack_dir: Path, out_zip: Path, model: Path, language: str,
                  course_id: str | None, limit: int | None,
                  cache_dir: Path | None) -> None:
    with tempfile.TemporaryDirectory(prefix="align-whisper-work-") as td:
        tmp = Path(td)

        manifest_path = unpack_dir / "manifest.json"
        if not manifest_path.is_file():
            sys.exit("manifest.json missing — not a coursebox package")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

        courses = manifest.get("courses") or []
        course = next((c for c in courses if c.get("id") == course_id), courses[0])
        old_lessons_path = course.get("lessons_manifest")
        if not old_lessons_path:
            sys.exit("course is missing lessons_manifest")
        lessons = json.loads((unpack_dir / old_lessons_path).read_text(encoding="utf-8"))

        aligned = skipped = 0
        for lesson in lessons:
            if not isinstance(lesson, dict):
                continue
            if limit is not None and aligned >= limit:
                break
            lid = lesson.get("id")
            obj = find_audio_for_lesson(manifest, lesson)
            if not obj or not (unpack_dir / obj).is_file():
                print(f"  skip {lid}: no audio object", file=sys.stderr)
                skipped += 1
                continue
            work = tmp / f"work-{lid}"
            work.mkdir(exist_ok=True)
            try:
                ok = align_lesson(lesson, unpack_dir / obj, model, work, language, cache_dir)
            except Exception as exc:
                print(f"  fail {lid}: {exc}", file=sys.stderr)
                ok = False
            if ok:
                aligned += 1
                print(f"  aligned {lid}")
            else:
                skipped += 1

        new_bytes = json.dumps(lessons, ensure_ascii=False, indent=2).encode("utf-8")
        new_digest = sha256_bytes(new_bytes)
        new_lessons_path = f"objects/{new_digest}.json"
        for r in manifest.get("resources", []):
            if r.get("path") == old_lessons_path:
                r["hash"] = f"sha256:{new_digest}"
                r["path"] = new_lessons_path
                r["size"] = len(new_bytes)
                r["type"] = "application/json"
                break
        course["lessons_manifest"] = new_lessons_path
        manifest["resources"] = sorted(
            manifest.get("resources", []), key=lambda r: r.get("hash", "")
        )
        (unpack_dir / new_lessons_path).parent.mkdir(parents=True, exist_ok=True)
        (unpack_dir / new_lessons_path).write_bytes(new_bytes)
        old = unpack_dir / old_lessons_path
        if old.is_file() and new_lessons_path != old_lessons_path:
            old.unlink()
        manifest_path.write_bytes(
            json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
        )

        print(f"writing {out_zip}  (aligned {aligned}, skipped {skipped})")
        with zipfile.ZipFile(out_zip, "w", compression=zipfile.ZIP_STORED) as zf:
            for root, _dirs, files in os.walk(unpack_dir):
                for name in files:
                    abs_path = Path(root) / name
                    zf.write(abs_path, abs_path.relative_to(unpack_dir).as_posix())


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("input", type=Path, nargs="?", default=None,
                    help="input .cx/.zip (not needed with --from-dir)")
    ap.add_argument("output", type=Path)
    ap.add_argument("--model", type=Path,
                    default=Path.home() / "models/whisper/ggml-large-v3-turbo-q5_0.bin")
    ap.add_argument("--course", default=None, help="course id (default: first course)")
    ap.add_argument("--limit", type=int, default=None, help="align only the first N lessons")
    ap.add_argument("--language", default="en")
    ap.add_argument("--from-dir", type=Path, default=None,
                    help="align an already-unpacked package directory "
                         "(multi-part packs: all parts unzipped together)")
    ap.add_argument("--cache-dir", type=Path,
                    default=Path.home() / ".cache/align-whisper",
                    help="whisper transcript cache (empty string disables)")
    args = ap.parse_args()
    check_bins()
    if args.from_dir:
        align_dir(args.from_dir, args.output, args.model, args.language,
                  args.course, args.limit, args.cache_dir or None)
    else:
        align_package(args.input, args.output, args.model, args.language,
                      args.course, args.limit, args.cache_dir or None)


if __name__ == "__main__":
    main()
