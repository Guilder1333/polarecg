# Raw recording format (version 1)

Written by the phone (`RawRecorder` in `:app`), read by `:core-dsp`
(`readRawFile` in `dsp/raw/RawFormat.kt`; the byte layout below must match that file).
Never commit recordings: copy exported sessions into `data/sessions/` (git-ignored).

## Folder layout

```
sessions/<startUtc>/            e.g. 20261012T221530Z   (UTC, yyyyMMdd'T'HHmmss'Z')
    session.json
    ecg_<hourUtc>.bin           e.g. ecg_20261012T22Z.bin   (one file per UTC clock hour)
    acc_<hourUtc>.bin
    hr_<hourUtc>.bin
    timemap.csv
```

- One session = one run of the acquisition service. Reconnects continue in the same files;
  a gap shows up as a jump in `phoneTimeMs` between records.
- The hour in a file name is the UTC hour of the **phone time** of its records.
- Records are flushed one by one, so a killed app loses at most the record being written.
  A truncated last record must be ignored by readers (ours does).

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

Written at start (`endUtc` = null) and rewritten when the session ends cleanly; if `endUtc` is
null the app was killed. Fields: `formatVersion`, `startUtc`, `endUtc` (ISO-8601 UTC), `deviceId`,
`firmware` (null for now), `appVersion`, `phone`, `ecg` (`rateHz`, `resolutionBits`, `unit`),
`acc` (`rateHz`, `resolutionBits`, `rangeG`, `unit`).

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
