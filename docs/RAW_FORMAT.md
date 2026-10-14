# Raw recording format (version 1, folder layout 2)

Written by the phone (`RawRecorder` in `:app`), read by `:core-dsp`
(`readRawFile` in `dsp/raw/RawFormat.kt`; the byte layout below must match that file).
Never commit recordings: copy exported sessions into `data/sessions/` (git-ignored).

## Folder layout

```
sessions/<startUtc>/                   e.g. 20261012T221530Z   (UTC, yyyyMMdd'T'HHmmss'Z')
    session.json                       session metadata (always this name; the folder carries the time)
    timemap.csv                        sensor clock -> UTC, appended for the whole session
    connection.log                     connection events of this session (JST + epoch ms), appended
    ecg.bin  acc.bin  hr.bin           CURRENT files, being written right now (no timestamp)
    ecg_20261012T221530Z.bin           finished files: <prefix>_<first record time, to the second>.bin
    ecg_20261012T231504Z.bin
    acc_20261012T221530Z.bin
    hr_20261012T221530Z_empty.bin      finished file that had a header but no record (kept on purpose)
    ecg_20261012T231504Z_1.bin         same name taken already: counter added
```

## Files and rotation ("rotating logs")

- The file being written has **no timestamp**: `ecg.bin`, `acc.bin`, `hr.bin`.
- A current file is **renamed** to `<prefix>_<stamp>.bin` when it is finished:
  1. at every **UTC hour change** (by the phone time of the frame being written),
  2. on a **proper stop**,
  3. on **recovery** after the app was killed (next start; see Sessions).
- `<stamp>` is the `phoneTimeMs` of the file's **first record**, UTC, to the second
  (`yyyyMMdd'T'HHmmss'Z'`). It is recoverable at any time from the file content, because every
  record starts with `phoneTimeMs`.
- Files are never deleted by the app, and a rename never overwrites. If the target name exists,
  a counter is appended (`_1`, `_2`, ...) and the rename is retried.
- A file with a valid header but no complete record is renamed with the marker `_empty`; a file that is
  not readable at all (too short, wrong magic) with `_invalid`. The stamp is then the file's
  last-modified time. They are kept so that "something went wrong here" stays visible.
- A stream that produced no frame in an hour creates no file for that hour.
- Records are flushed one by one, so a killed app loses at most the record being written.
  A truncated last record must be ignored by readers (ours does).
- To read a session in time order: sort the `.bin` files of one prefix by name (the stamp sorts
  chronologically; a `_n` counter and markers only matter for identical stamps).
- Code: `dsp/raw/RawSegments.kt` (naming, recovery), `recorder/SessionFolders.kt` (sessions).

## Sessions

- **One session = one folder that holds all of its data**, including data written after the app
  or service was killed and restarted.
- `session.json` without `endUtc` (null) means: *not stopped properly*, i.e. running, or killed
  by Android, by the user (swipe / force stop), or by a crash.
- **Next start after a kill resumes that session** (same folder, same `startUtc`): leftover current
  files are renamed as above, and an entry is added to `interruptions` in `session.json`. This is
  also what the automatic restart of the service does. A session is resumed only if it is the
  newest folder, was written with this layout (`layout` = 2) and has the same `deviceId`; any
  other unfinished folder is closed as `recovered`.
- **Stop recording** (button or notification action) writes `endUtc` and finishes the session. The
  **next start is a completely new session** with a new folder.
- Android stopping the service by itself is *not* a proper stop; only the user's stop is.
- `connection.log`: one line per event, tab-separated: `yyyy-MM-dd HH:mm:ss JST`, epoch ms, type,
  detail. Human-readable times in the app and in `session.json` (`startJst`, `endJst`,
  `interruptions[].resumedJst`) are JST; machine fields and folder/file names stay UTC.
- `timemap.csv` is not rotated (≈ 1 row per minute); its connection numbers continue across a resume.

## `.bin` files

All numbers **little-endian**. One stream per file.

Header (8 bytes): `"PECG"` (4 ASCII bytes) · `version u16` (= 1) · `stream u16` (1 = ECG, 2 = ACC, 3 = HR)

Records follow back to back, with no separators and no length fields.

**ECG (stream 1)** — one record per BLE frame (~70 samples, ~0.5 s):

| Field | Type | Meaning |
|---|---|---|
| phoneTimeMs | i64 | phone UTC ms (since 1970) when the frame arrived |
| sensorTimeNs | i64 | H10 clock, ns since 2000-01-01 UTC, of the **first** sample |
| n | u16 | number of samples |
| samples | n × i32 | microvolts, 130 Hz |

**ACC (stream 2)** — one record per frame:

| Field | Type | Meaning |
|---|---|---|
| phoneTimeMs | i64 | as above |
| sensorTimeNs | i64 | as above, first sample |
| n | u16 | number of samples |
| samples | n × (x i16, y i16, z i16) | milli-g, interleaved per sample (50 Hz, ±8 g) |

**HR (stream 3)** — one record per heart-rate notification (~1 per second):

| Field | Type | Meaning |
|---|---|---|
| phoneTimeMs | i64 | as above |
| hr | u16 | beats per minute |
| contact | i8 | 1 = skin contact, 0 = none, −1 = strap does not report it |
| nRr | u8 | number of RR intervals (0–255) |
| rr | nRr × u16 | RR intervals in ms (already converted from 1/1024 s) |

## `session.json`

Written at session start with `endUtc` = null, rewritten (atomically: temp file + move, so never
half-written) on every resume and on the proper stop. Fields: `formatVersion` (of the `.bin`
format), `layout` (2 = this folder layout), `startUtc`, `endUtc` (ISO-8601 UTC; null = not
stopped properly), `deviceId`, `firmware` (null for now), `appVersion`, `phone`,
`ecg` (`rateHz`, `resolutionBits`, `unit`), `acc` (`rateHz`, `resolutionBits`, `rangeG`, `unit`),
and:

- `interruptions`: one entry per resume, `{ "lastDataUtc": <newest record found, or null>,
  "resumedUtc": <when recording resumed> }`. The time between the two is data that does not exist.
- `recovered`: `true` only when the session was closed by recovery instead of a stop; then
  `endUtc` is the time of the last data found (or of the newest file), not a real stop time.

## `timemap.csv`

Sensor-clock → phone-UTC relation per connection (P1.8, class `TimeMapper` in `:core-dsp`).
Header `connection,phoneUtcMs,sensorNs,offsetMs,kind`. `sensorNs` is the sensor time of the last
sample of an ECG frame, `offsetMs` = phone UTC − sensor UTC, so
`utcMs = sensorNs / 1e6 + 946684800000 + offsetMs`. `connection` counts from 0 and increases at
every (re)start of the streams (the sensor clock may differ after one). `kind`: `start` (first
frame of a connection), `periodic` (every 60 s), `jump` (offset changed by more than 1 s; also
logged as `CLOCK_JUMP` in the connection log). The offset is the smallest one seen in the last
60 s, so it carries the BLE latency of the least delayed frame (tens of ms).

## Getting data to the PC

In the app: **Export last session** → share sheet → send the zip to yourself (Drive, email, USB…).
Unzip into `data/sessions/`.

## Size

ECG ≈ 520 B/s, ACC ≈ 300 B/s, HR ≈ 30 B/s → roughly 3 MB per hour, ≈ 30 MB per night.
