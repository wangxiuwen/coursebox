# CourseBox Course Package Format

**Version:** 1
**Format ID:** `parrot-course-package` (legacy name, kept for compatibility)
**File extension:** `.coursebox.zip` (or any `.zip` — the magic is `manifest.json`)

A *course package* is a plain zip archive containing:

```
manifest.json
objects/
    <sha256>.json
    <sha256>.mp3
    <sha256>.pdf
    ...
```

The archive is content-addressed: every file under `objects/` is named by
the sha256 of its bytes. The same audio used in two lessons appears once.
Repeated imports skip files that are already on disk.

## `manifest.json`

```json
{
  "format": "parrot-course-package",
  "version": 1,
  "generated_at": "2026-05-16T13:40:00Z",
  "generator": "coursebox-packager 0.1.0",
  "resources": [ ... ],
  "courses":   [ ... ]
}
```

### `resources[]`

The flat list of every file under `objects/`. Each entry:

| Field    | Type     | Notes |
|----------|----------|-------|
| `hash`   | string   | `"sha256:<hex>"`; must match the file content |
| `path`   | string   | `objects/<sha256>.<ext>` |
| `size`   | integer  | Bytes |
| `type`   | string   | MIME type. `application/json`, `audio/mpeg`, `application/pdf`, etc. |
| `origin` | string   | Optional. Original logical path (e.g. `"poetry/tangshi300.json"`). When provided, lets the renderer resolve resources by their pre-packaging path. |
| `tags`   | string[] | Free-form, used for filtering. |

### `courses[]`

Each course inside the package:

| Field              | Type     | Notes |
|--------------------|----------|-------|
| `id`               | string   | Unique within an app install. Re-importing replaces the same id. |
| `title`            | string   | Display name |
| `description`     | string   | One-paragraph blurb |
| `type`             | string   | See "Course types" below. Renderers fall back to `audio_course` for unknown types. |
| `lessons_manifest` | string   | `objects/<sha256>.json` — a path *inside* the zip pointing at the lesson detail file. |
| `lesson_index`     | array    | Lightweight per-lesson summary used by list UIs (no transcript bodies). |
| `metadata`         | object   | Arbitrary; forwarded to the renderer. |

#### `lesson_index[]`

| Field        | Type     | Notes |
|--------------|----------|-------|
| `id`         | string   | Unique within the course |
| `title`      | string   | Primary display text |
| `subtitle`   | string   | Optional secondary line |
| `audio_hash` | string   | `"sha256:..."` or `""` for text-only lessons |
| `tags`       | string[] | Free-form |
| `metadata`   | object   | Renderer-specific fields (e.g. `book`/`lesson` for NCE) |

#### `lessons.json` (referenced by `lessons_manifest`)

A JSON array of lesson objects with all the rich fields the renderer
needs. Schema depends on the course `type`:

##### `audio_course` (generic — the safest default)

```json
[
  {
    "id": "intro",
    "title": "Introduction",
    "subtitle": "Welcome",
    "audio_hash": "sha256:...",
    "lyrics": [
      { "time_ms": 0,    "duration_ms": 3200, "text": "Hello world", "translation": "你好世界" },
      { "time_ms": 3300, "duration_ms": 2800, "text": "Let's get started" }
    ],
    "article_html": "<p>...optional HTML body...</p>",
    "metadata": {}
  }
]
```

##### `nce` (English textbook with bilingual transcript)

```json
{
  "id": "book2-lesson1",
  "book": 2,
  "lesson": 1,
  "title_en": "A private conversation",
  "title_cn": "私人谈话",
  "question": "Why did the writer complain?",
  "audio_hash": "sha256:...",
  "audio_local": "nce-mp3/2/lesson1.mp3",
  "lines": [
    { "en": "Last week I went to the theatre.", "cn": "上周我去看戏。" }
  ],
  "sections": [
    {
      "title": "Vocabulary",
      "type": "words",
      "words": [
        { "word": "private", "pron": "['praivit]", "pos": "adj.", "def": "私人的" }
      ]
    }
  ]
}
```

##### `chinese_poetry` (classical Chinese works)

The package may bundle several source JSON files (e.g. one per anthology)
referenced via `origin`. The renderer reads them by logical path.

```json
{
  "title": "在嶽詠蟬",
  "author": "駱賓王",
  "paragraphs": [
    "西陸蟬聲唱，南冠客思侵。",
    "那堪玄鬢影，來對白頭吟。"
  ],
  "tags": ["唐诗三百首", "咏物"]
}
```

##### `music` (music study)

Items can be audio (`type: audio`) or PDF score (`type: pdf`).

```json
{
  "id": "music-001",
  "title": "中音 do 视唱",
  "subtitle": "初级 · 视唱练耳",
  "audio_hash": "sha256:...",
  "metadata": {
    "grade": "初级",
    "subject": "视唱练耳",
    "type": "audio"
  }
}
```

## Authoring rules

1. **Hash every file.** Both `objects/<filename>` and the `hash` field
   must agree. The renderer rejects packages where they don't.
2. **Sort `resources[]` by hash.** This makes the manifest reproducible
   and easy to diff.
3. **Always set `format` and `version`.** Renderers refuse to import
   packages that don't claim `"parrot-course-package"` v1.
4. **Use UTF-8.** All text fields are UTF-8 strings. No BOM in JSON.
5. **Use forward slashes.** Path separators in `path` and `origin` are
   always `/`, regardless of authoring OS.
6. **Don't include `..` in paths.** The renderer rejects them.

## Reproducibility

Two packagings of the same source folder must yield byte-identical zips
when:

- Resources are sorted by hash.
- File timestamps inside the zip are normalised to a fixed epoch.
- `generated_at` and `generator` are the only "wall-clock" fields, and
  both can be overridden.

This is convenient for content distribution (CDN caching, P2P sharing)
and lets reviewers verify a `.coursebox.zip` matches a known good
manifest hash.
