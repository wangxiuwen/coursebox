#!/usr/bin/env python3
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



# Sentence splitters — same regexes as NcePlayerScreen.expandToSentences.
EN_SENTENCE_SPLIT = re.compile(r"(?<=[.!?])\s+(?=[A-Z\"'‘“])")
CN_SENTENCE_SPLIT = re.compile(r"(?<=[。！？!?])\s*")
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
    otherwise fold the 课文/翻译 sections into parallel lines — text
    sections carry a plain text[], the newer dialogue sections carry a
    dialogue[] of {sender, content} (NCE1's second half is dialogue
    lessons whose lines[] use sender/content and would decode empty);
    otherwise split "English — 中文" rows of the first text section."""
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

    def sec_lines(s: dict) -> list[str]:
        if s.get("type") == "text":
            return [str(t).strip() for t in (s.get("text") or []) if str(t).strip()]
        if s.get("type") == "dialogue":
            return [(d.get("content") or "").strip()
                    for d in (s.get("dialogue") or []) if isinstance(d, dict)]
        return []

    en = next((sec_lines(s) for s in sections
               if "课文" in (s.get("title") or "")), None) or []
    cn = next((sec_lines(s) for s in sections
               if "翻译" in (s.get("title") or "")), None) or []
    if en or cn:
        n = max(len(en), len(cn))
        return [{"en": en[i] if i < len(en) else "",
                 "cn": cn[i] if i < len(cn) else "",
                 "start_ms": -1, "end_ms": -1} for i in range(n)]
    merged = next((sec_lines(s) for s in sections if sec_lines(s)), None) or []
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

def detect_speech_segments(wav: Path) -> list[tuple[float, float]]:
    """Silence-based speech segmentation with the device player's
    semantics: >=800 ms of silence splits, <180 ms utterances dropped,
    +-100 ms padding."""
    proc = subprocess.run(
        ["ffmpeg", "-i", str(wav), "-af", "silencedetect=noise=-35dB:d=0.8",
         "-f", "null", "-"],
        capture_output=True, text=True,
    )
    silences: list[list[float]] = []
    for line in (proc.stderr or "").splitlines():
        m = re.search(r"silence_start: ([\d.]+)", line)
        if m:
            silences.append([float(m.group(1)), -1.0])
            continue
        m = re.search(r"silence_end: ([\d.]+)", line)
        if m and silences and silences[-1][1] < 0:
            silences[-1][1] = float(m.group(1))
    out = subprocess.run(
        ["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
         "-of", "csv=p=0", str(wav)],
        capture_output=True, text=True,
    )
    try:
        total = float(out.stdout.strip())
    except ValueError:
        total = 0.0
    speech: list[tuple[float, float]] = []
    prev = 0.0
    for s, e in silences:
        if s - prev >= 0.18:
            speech.append((prev, s))
        prev = e if e > 0 else s
    if total - prev >= 0.18:
        speech.append((prev, total))
    pad = 0.1
    return [
        (max(0.0, a - pad), min(total, b + pad))
        for a, b in speech
    ]


def start_whisper_server(model: Path, port: int) -> subprocess.Popen:
    """Long-lived whisper.cpp HTTP server: the 547 MB model loads once and
    every VAD segment then transcribes in a fraction of a second —
    per-segment whisper-cli calls would each pay a 2-3 s model load.
    Retries on the next port if one is held by a stale server — a dead
    server behind a live port used to poison every subsequent lesson."""
    import time
    import urllib.request
    for attempt in range(10):
        candidate = port + attempt
        proc = subprocess.Popen(
            ["/opt/homebrew/bin/whisper-server", "-m", str(model),
             "--port", str(candidate), "--inference-path", "/inference"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        ok = False
        for _ in range(60):
            time.sleep(0.5)
            if proc.poll() is not None:
                break  # died (port held?) — try the next port
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{candidate}/", timeout=1)
                ok = True
                break
            except Exception:
                continue
        if ok and proc.poll() is None:
            return proc, candidate
        proc.kill()
    sys.exit("whisper-server failed to start on any port 9100-9109")


def stop_whisper_server(proc: subprocess.Popen) -> None:
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()


_SPECIAL_TOK = re.compile(r"^_.*_$|^\\[.*\\]$")


def transcribe_segments(wav: Path, speech: list[tuple[float, float]],
                        work: Path, model: Path, language: str,
                        server_port: int, cache_dir: Path | None,
                        cache_key: str) -> list[dict]:
    """Transcribe each VAD segment independently through the whisper
    server: clip the segment, POST it, take the text. Segment boundaries
    come from the VAD (the reader's real pauses) and never from whisper
    timestamps, which drift. Returns one unit per segment."""
    cache_file = None
    if cache_dir is not None:
        cache_dir.mkdir(parents=True, exist_ok=True)
        cache_file = cache_dir / f"{cache_key}.v5.json"
        if cache_file.is_file():
            cached = json.loads(cache_file.read_text(encoding="utf-8"))
            # An empty result is a poison pill from a failed run (dead
            # server, missing import...) — treat it as a miss so the
            # lesson can ever recover.
            if cached:
                return cached

    special = re.compile(r"^_.*_$|^\[.*\]$")

    def clean(t: str) -> str:
        return special.sub("", t)

    import http.client
    units: list[dict] = []
    boundary = "----courseboxform"
    for a, b in speech:
        clip = work / "seg.wav"
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-ss", f"{a:.3f}",
             "-to", f"{b:.3f}", "-i", str(wav), "-ar", "16000", "-ac", "1",
             str(clip)],
            check=True,
        )
        body = bytearray()
        body += f"--{boundary}\r\n".encode()
        body += b'Content-Disposition: form-data; name="file"; filename="seg.wav"\r\n'
        body += b"Content-Type: audio/wav\r\n\r\n"
        body += Path(clip).read_bytes()
        body += f"\r\n--{boundary}--\r\n".encode()
        conn = http.client.HTTPConnection("127.0.0.1", server_port, timeout=120)
        conn.request("POST", "/inference", bytes(body), {
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        })
        payload = json.loads(conn.getresponse().read().decode("utf-8"))
        conn.close()
        text = clean(payload.get("text") or "").strip()
        if not text:
            continue
        units.append({
            "start_ms": int(a * 1000),
            "end_ms": int(b * 1000),
            "text": text,
        })
    if cache_file is not None and units:
        cache_file.write_text(json.dumps(units, ensure_ascii=False), encoding="utf-8")
    return units


END_PUNCT = ".!?。！？"


def split_bucket_on_punctuation(bucket: dict) -> list[dict]:
    """One speech unit can carry several lesson sentences when the
    reader's pause was shorter than the 800 ms split. Re-split it on
    sentence punctuation using the unit's tokens."""
    toks = bucket.get("tokens") or []
    ends = sum(
        1 for t in toks
        if t["text"].strip().rstrip("”’\"'“‘）)…").endswith(tuple(END_PUNCT))
    )
    if ends <= 1 or not toks:
        return [bucket]
    out: list[dict] = []
    cur: list[dict] = []
    for t in toks:
        cur.append(t)
        if t["text"].strip().rstrip("”’\"'“‘）)…").endswith(tuple(END_PUNCT)):
            text = "".join(x["text"] for x in cur).strip()
            if text:
                out.append({"start_ms": cur[0]["start_ms"], "end_ms": cur[-1]["end_ms"],
                            "text": text, "tokens": cur})
            cur = []
    if cur:
        text = "".join(x["text"] for x in cur).strip()
        if text:
            out.append({"start_ms": cur[0]["start_ms"], "end_ms": cur[-1]["end_ms"],
                        "text": text, "tokens": cur})
    return out or [bucket]


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
    """(start_ms, end_ms) per text sentence: matched → unit times,
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


def tighten_span_to_text(unit_tokens: list[dict], sentence: str,
                         start: int, end: int) -> tuple[int, int]:
    """A matched unit can carry extra speech — e.g. an announcement glued
    to the first sentence when the pause before the text ran under the
    800 ms split. Slide over the unit's tokens to find the sentence's own
    first/last word and tighten the span to them; the announcement falls
    back out of the sentence."""
    words = [w for w in norm(sentence).split() if w]
    if not words or not unit_tokens:
        return start, end
    first_w, last_w = words[0], words[-1]

    def token_core(t: dict) -> str:
        return norm(t["text"]).strip()

    start_at = end_at = None
    for i, t in enumerate(unit_tokens):
        core = token_core(t)
        if not core:
            continue
        if start_at is None and (first_w in core or core in first_w):
            start_at = t["start_ms"]
        if last_w in core or (core and core in last_w):
            end_at = t["end_ms"]
            break_marker = i
    if start_at is None or end_at is None or end_at < start_at:
        return start, end
    return max(start, start_at), min(end, max(end_at, start_at + 300))


def align_lesson(
    lesson: dict, audio_full: Path, model: Path, work: Path, language: str,
    cache_dir: Path | None = None, server_port: int | None = None,
) -> bool:
    en_all, cn_all = lesson_sentence_pools(lesson)
    wav = work / "audio16k.wav"
    if not wav.is_file():
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", str(audio_full),
             "-ar", "16000", "-ac", "1", str(wav)],
            check=True,
        )
    speech = detect_speech_segments(wav)
    if not speech:
        return False
    cache_key = sha256_file(audio_full)
    segments = transcribe_segments(wav, speech, work, model, language,
                                   server_port or 9100, cache_dir, cache_key)
    if not segments:
        return False
    # A VAD segment can carry several lesson sentences when the reader's
    # pause was shorter than the 800 ms split — split it on punctuation
    # in the segment text (boundary interpolated inside the segment).
    units: list[dict] = []
    for seg in segments:
        parts = [p.strip() for p in EN_SENTENCE_SPLIT.split(seg["text"]) if p.strip()]
        if len(parts) <= 1:
            units.append(seg)
            continue
        total = sum(len(x) for x in parts)
        span = seg["end_ms"] - seg["start_ms"]
        pos = seg["start_ms"]
        for x in parts:
            dur = int(span * len(x) / max(total, 1))
            units.append({"start_ms": pos, "end_ms": pos + dur, "text": x})
            pos += dur

    if not en_all:
        # No transcript in the package (THINK exercise/video tracks): the
        # ASR transcript *is* the text, timed on the pause boundaries.
        lesson["lines"] = [
            {"en": u["text"], "cn": "",
             "start_ms": u["start_ms"], "end_ms": u["end_ms"]}
            for u in units
        ]
        return bool(lesson["lines"])

    # Lesson text exists: NW-match units to lesson sentences. Matching
    # only decides *which* canonical text a pause-boundary unit carries —
    # the timings stay on the pause boundaries the player uses.
    matches = align_sentences(units, [(e, "") for e in en_all])
    spans = timestamps_for([(e, "") for e in en_all], units, matches)
    cns = pair_chinese_by_time(
        [(e, s, e2) for e, (s, e2) in zip(en_all, spans)], cn_all
    )
    lines = []
    prev_end = 0
    for i in range(len(en_all)):
        start, end = spans[i]
        start = max(start, prev_end)
        end = max(end, start + 300)
        prev_end = end
        lines.append({"en": en_all[i], "cn": cns[i],
                      "start_ms": start, "end_ms": end})
    lesson["lines"] = lines
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
    server, server_port = start_whisper_server(model, 9100)
    try:
        _run_alignment(unpack_dir, out_zip, model, language, course_id,
                       limit, cache_dir, server_port)
    finally:
        stop_whisper_server(server)


def _run_alignment(unpack_dir: Path, out_zip: Path, model: Path, language: str,
                   course_id: str | None, limit: int | None,
                   cache_dir: Path | None, server_port: int) -> None:
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
        print(f"DEBUG: lessons {len(lessons)}, 前3: {[l.get('id') for l in lessons[:3]]}", flush=True)

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
                ok = align_lesson(lesson, unpack_dir / obj, model, work, language,
                                  cache_dir, server_port)
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
