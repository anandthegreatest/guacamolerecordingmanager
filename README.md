# Guacamole Recording Manager (Spring Boot)

This repository contains a practical implementation plan for a Spring Boot application that can ingest, analyze, play, summarize, and export Apache Guacamole (`.guac`) recordings.

## Goals

1. Read and play `.guac` recordings.
2. Show an activity histogram on the player timeline.
3. Summarize recording content using sampled screenshots + an LLM.
4. Show timestamped keystrokes in a side panel.
5. Track playback/audit events in a database.
6. Export recordings to MP4.

## Recommended Architecture

- **Backend:** Spring Boot 3.x (Java 21), modular monolith.
- **Frontend:** Server-rendered Thymeleaf + JavaScript _or_ separate React/Vue SPA.
- **Database:** PostgreSQL.
- **Cache/Queue (optional but useful):** Redis + Spring task executor for async jobs.
- **Storage:** Local/NFS/S3-compatible object storage for `.guac` and generated artifacts.
- **Media tooling:** Guacamole recording parser + FFmpeg for MP4 rendering.
- **LLM integration:** OpenAI API (vision-capable model) for screenshot-based summaries.

## Core Modules

### 1) Recording Ingestion

- Accept upload or import from a watched directory.
- Persist metadata:
  - file path / blob key
  - file size
  - sha256 checksum
  - start/end timestamps (if derivable)
  - duration
- Create async jobs:
  - parse events
  - build activity timeline
  - extract keystrokes
  - generate preview frames

### 2) `.guac` Parsing & Playback API

Implement a parser that reads Guacamole protocol instructions from the recording stream and emits normalized events:

- `MOUSE_MOVE`, `MOUSE_CLICK`
- `KEY_DOWN`, `KEY_UP`, `TEXT_INPUT` (if derivable)
- `FRAME_UPDATE` / drawing operations
- timestamped packet boundaries

Expose APIs for playback:

- `GET /api/recordings/{id}` metadata
- `GET /api/recordings/{id}/stream` playback chunks
- `GET /api/recordings/{id}/events` event stream (paged)

> Tip: Build the parser and event model first; most features depend on it.

### 3) Activity Histogram

Compute activity score per time bucket (e.g., 1s or 2s):

```text
score = a*mouseMoves + b*clicks + c*keyEvents + d*pixelChange
```

- Normalize into [0..100] for UI bars.
- Store as JSONB in DB for quick retrieval.
- Endpoint:
  - `GET /api/recordings/{id}/histogram?bucket=1s`

Frontend timeline:

- Render bars under scrubber.
- Click bar to seek.
- Tooltip: timestamp + activity score.

### 4) Keystroke Timeline

From parsed key events, build:

- timestamp
- key value
- modifiers (ctrl/alt/shift/meta)
- optional text reconstruction

Endpoint:

- `GET /api/recordings/{id}/keystrokes?from=...&to=...`

UI:

- Right panel list with sticky time.
- Filter options: printable only, include modifiers, search string.

### 5) LLM-Based Summarization

Workflow:

1. Sample frames at meaningful segments:
   - fixed interval (e.g., every 30s)
   - plus high-activity peaks from histogram
2. For each sample, send image + timestamp to LLM.
3. Ask for structured output:
   - observed application/context
   - notable user actions
   - suspicious/sensitive moments (if any)
4. Aggregate per-frame notes into a final summary.

Store:

- raw frame-level notes
- final summary
- model/version/prompt hash for traceability

Endpoint:

- `POST /api/recordings/{id}/summaries` (create)
- `GET /api/recordings/{id}/summaries/latest`

### 6) Playback Tracking / Audit

Track:

- who viewed recording
- when playback started/stopped
- seek/jump events (optional)
- duration watched

Useful tables:

- `recordings`
- `recording_events` (parsed low-level events; optional to store all)
- `recording_histograms`
- `recording_keystrokes`
- `recording_summaries`
- `playback_sessions`
- `playback_actions`

### 7) MP4 Export

Pipeline:

1. Re-render `.guac` session to image frames or a raw video stream.
2. Encode using FFmpeg (`libx264`, AAC optional).
3. Optional overlay:
   - timestamp watermark
   - keypress captions
   - activity markers

Endpoints:

- `POST /api/recordings/{id}/exports/mp4`
- `GET /api/exports/{exportId}` status/download

Because exports are expensive, run asynchronously and store artifacts with retention policy.

## Suggested Data Model (Minimal)

```sql
create table recordings (
  id uuid primary key,
  filename text not null,
  storage_key text not null,
  checksum_sha256 text not null unique,
  started_at timestamptz,
  ended_at timestamptz,
  duration_ms bigint,
  created_at timestamptz not null default now()
);

create table playback_sessions (
  id uuid primary key,
  recording_id uuid not null references recordings(id),
  user_id text not null,
  started_at timestamptz not null,
  ended_at timestamptz,
  watched_ms bigint default 0
);

create table recording_summaries (
  id uuid primary key,
  recording_id uuid not null references recordings(id),
  model text not null,
  summary text not null,
  created_at timestamptz not null default now()
);
```

## API Sketch

- `POST /api/recordings` upload/import recording
- `GET /api/recordings` list recordings
- `GET /api/recordings/{id}` details
- `GET /api/recordings/{id}/histogram`
- `GET /api/recordings/{id}/keystrokes`
- `POST /api/recordings/{id}/summaries`
- `POST /api/recordings/{id}/exports/mp4`
- `GET /api/exports/{id}`
- `POST /api/playback-sessions` start tracking
- `PATCH /api/playback-sessions/{id}` stop/update tracking

## Security & Compliance Notes

- Recordings and keystrokes may contain secrets/PII.
- Add role-based access (`VIEW_RECORDING`, `EXPORT_RECORDING`, `VIEW_KEYSTROKES`).
- Encrypt at rest and in transit.
- Mask sensitive keystrokes where policy requires.
- Add immutable audit logs for who accessed what and when.

## Implementation Roadmap

1. **Phase 1:** ingestion + metadata + basic player streaming.
2. **Phase 2:** parser + histogram + keystroke panel.
3. **Phase 3:** playback audit + authorization hardening.
4. **Phase 4:** MP4 export pipeline.
5. **Phase 5:** LLM summarization with guardrails and cost controls.

## Technical Risks / Unknowns

- Fidelity of `.guac` re-rendering for MP4 export.
- Scale/cost of storing full low-level events.
- LLM hallucinations in summaries.
- Handling keyboard locale/layout differences for accurate text reconstruction.

## Practical Next Step

Start by implementing a **single recording parser service** with tests and a JSON event output format. Once this is stable, the histogram, keystroke side panel, summary pipeline, and export logic all become straightforward incremental features.
