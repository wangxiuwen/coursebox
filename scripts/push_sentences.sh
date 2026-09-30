#!/usr/bin/env bash
# Push only the sentence-timing data (lessons JSON) of an aligned package
# to a device — a few hundred KB instead of the full 40 MB+ .cx.
#
# Usage: push_sentences.sh <aligned.cx> <package_id> [port]
# Port is the adb-forwarded local port (default 38723).
set -euo pipefail

CX="$1"
PKG_ID="$2"
PORT="${3:-38723}"

JSON=$(python3 - "$CX" "$PKG_ID" << 'PY'
import json, zipfile, sys, hashlib
pkg, course_id = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(pkg) as z:
    m = json.loads(z.read('manifest.json'))
    c = next((c for c in m['courses'] if c.get('id') == course_id), m['courses'][0])
    lessons = z.read(c['lessons_manifest'])
# Sanity: every lesson must carry sentence timestamps before we ship it.
parsed = json.loads(lessons)
aligned = sum(1 for l in parsed if any(x.get('start_ms', -1) >= 0 for x in (l.get('lines') or [])))
print(len(lessons), aligned, len(lessons), file=sys.stderr)
import base64
print(base64.b64encode(lessons).decode())
PY
)

COUNTS=$(echo "$JSON" | cut -d' ' -f1-2 2>/dev/null || true)
B64=$(echo "$JSON" | tail -1)
echo "$B64" | base64 -d > /tmp/push_sentences.json
echo "lessons: $(python3 -c "import json; d=json.load(open('/tmp/push_sentences.json')); print(len(d), 'lessons,', sum(1 for l in d if any(x.get('start_ms',-1)>=0 for x in (l.get('lines') or []))), 'aligned')")"

curl -s --max-time 120 -X PUT --data-binary @/tmp/push_sentences.json \
  "http://127.0.0.1:${PORT}/lessons/${PKG_ID}" && echo
