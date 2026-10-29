# PolarisECG — Development Plan

Personal Android app for **continuous, real-time, on-device ECG monitoring** with a **Polar H10**
chest strap. Main goal: detect and quantify **premature beats (PVCs / PACs)** live, and compute
**HRV** that is not corrupted by those beats. Only metrics and short ECG clips around events are
stored long-term; nothing leaves the phone.

- Target phone: **Nothing Phone 3a, Android 16** (`minSdk 36` is fine for this).
- Sensor: **Polar H10** (the only Polar sensor that streams raw ECG).
- Out of scope for now: sleep staging (see [§10](#10-later--maybe)).

> **Not a medical device.** This app is for personal insight. If it shows a high PVC burden
> (roughly >10 % of beats), runs of 3+ PVCs, or long pauses, confirm with a cardiologist / Holter.

---

## Table of contents

1. [How to use this document](#1-how-to-use-this-document)
2. [What the hardware gives us](#2-what-the-hardware-gives-us)
3. [Known pitfalls (read before coding)](#3-known-pitfalls-read-before-coding)
4. [Architecture](#4-architecture)
5. [Why Kotlin-first, and where Python fits](#5-why-kotlin-first-and-where-python-fits)
6. [Phases and tasks](#6-phases-and-tasks)
7. [Algorithms in detail](#7-algorithms-in-detail)
8. [Data model and storage](#8-data-model-and-storage)
9. [Validation strategy](#9-validation-strategy)
10. [Later / maybe](#10-later--maybe)
11. [Glossary](#11-glossary)

---

## 1. How to use this document

Every task has an ID (e.g. `P1.3`) and an owner tag:

| Tag | Meaning |
|---|---|
| **[AI]** | Good fit for an AI coding assistant. Give it the task ID and point it at this file. |
| **[YOU]** | Needs a human: physical device, wearing the strap, judgement, labeling, decisions. |
| **[YOU+AI]** | AI writes it, you review/test/tune; or a good task for you to learn Kotlin on, with AI help. |

Owner tags are suggestions — reassign freely.

**Working with AI on this project**

- One task (or a few small related ones) per session. Say: *"Do task P1.4 from docs/DEVELOPMENT_PLAN.md"*.
- Each task has **Done when** criteria — ask the AI to meet them and tell you how to verify.
- Ask the AI to explain any Kotlin it writes that you don't understand; that is the fastest way to learn.
- Tick tasks off here (`- [x]`) as they finish so the next session knows the state.
- Never commit recordings or personal health data to git — keep them in `data/` (git-ignored).

---

## 2. What the hardware gives us

The H10 exposes three useful streams over Bluetooth Low Energy (BLE). We use **all three at once**.

| Stream | Source | Rate | What we use it for |
|---|---|---|---|
| **ECG** | Polar PMD service (via Polar BLE SDK) | **130 Hz**, values in µV, sent in frames of ~70 samples with a sensor timestamp | Beat detection, beat **shape** (morphology) → PVC/PAC classification, event clips |
| **Accelerometer (ACC)** | Polar PMD service | 25/50/100/200 Hz, 3 axes, mG | Detect movement → reject motion artifacts; later sleep/wake, posture |
| **HR + RR intervals** | Standard BLE Heart Rate service | ~1 notification/s, RR in 1/1024 s | Very precise beat-to-beat **timing** for HRV; cross-check of our detector |

What this means in practice:

- **130 Hz is a low sampling rate** (the strap's timestamps show 130.27 Hz in practice; time always follows its clock, see P3.6). One sample every 7.7 ms. Fine for finding beats and judging
  their shape, but R-peak timing from ECG alone is coarse. We refine peaks with interpolation
  (§7.2), and use H10's own RR intervals where precise timing matters.
- **The H10 cannot store ECG internally.** If the phone is out of range or the app dies, that data
  is gone. Reliability of the phone side is therefore the #1 engineering problem.
- **Battery:** streaming ECG + ACC drains the CR2025 coin cell much faster than HR-only use.
  We measure it (task P1.9) and plan replacements accordingly.
- **Connection slots:** close Polar Flow / Polar Beat / other HR apps — they can occupy the H10's
  connection and block or disturb ours.
- **Lead:** H10 records one lead across the chest. It is *similar to* a chest lead, not identical
  to the standard leads in medical databases. QRS polarity and T-wave size vary per person.

---

## 3. Known pitfalls (read before coding)

These are the problems that typically kill projects like this. Each one maps to concrete tasks.

1. **Android kills background apps.** Overnight, Android will stop a normal app. We need a
   *foreground service* (persistent notification), battery-optimization exemption, and automatic
   reconnect with back-off. Every gap must be **logged**, never silently ignored.
   → P1.4, P1.5, P1.6
2. **Dry electrodes lose contact** (lying on side, electrodes drying, strap shifting). Noise looks
   like strange beats. Every beat and every time window gets a **signal quality** score, and bad
   segments are excluded from metrics. → P3.4
3. **Motion artifacts and big T-waves mimic PVCs.** A detector that double-counts T-waves or
   treats a movement spike as a beat will report a fake PVC burden. Use ACC to flag motion and
   test against T-wave-heavy recordings. → P3.2, P3.4
4. **Premature beats destroy HRV numbers.** One PVC + its pause can double a 5-minute RMSSD.
   Ectopic beats must be detected **before** HRV is computed, and intervals touching them excluded.
   → P5.5
5. **PAC vs PVC at 130 Hz, single lead.**
   - PVC: early, *wide and differently shaped* QRS, usually followed by a full compensatory pause → detectable.
   - PAC: early but *normal-looking* QRS; the proof (an abnormal P-wave) is often not visible at
     130 Hz on this lead. Realistically we label these **"S" = premature with normal shape (likely
     supraventricular)**, not "certain PAC".
6. **"Metrics only" makes validation impossible at first.** During development we record **all
   raw data** (≈ 30–50 MB/day — cheap) so algorithms can be replayed and tuned. In normal use we
   keep only metrics + **10–20 s ECG clips** around each event, plus a rolling buffer of the last
   few minutes. Raw recording stays available as a developer toggle. → P1.7, P5.4
7. **Clocks drift.** The sensor clock and the phone clock disagree and drift. Store a mapping per
   connection and everything in **UTC**, so time zones / daylight saving don't break day boundaries. → P1.8
8. **Causal vs offline filtering differ.** A real-time (causal) filter delays and slightly reshapes
   the signal compared to offline zero-phase filters (e.g. Python `filtfilt`). If we prototype with
   one and ship the other, results won't match. Solution: run *the same Kotlin code* offline. → §5
9. **Public arrhythmia databases ≠ our sensor.** MIT-BIH is 360 Hz, different leads, 1970s
   hardware. Resampled to 130 Hz it's a good sanity check, but **your own labeled beats** (and your
   existing analysis tool's output) are the real ground truth. → §9
10. **Alert fatigue.** No night-time alerts by default. Morning summary instead; optional alerts
    only for rare, severe patterns, configurable.

---

## 4. Architecture

```
 Polar H10
    │  BLE
    ▼
┌───────────────────────────── :app (Android) ─────────────────────────────┐
│ AcquisitionService (foreground, type connectedDevice)                    │
│   Polar BLE SDK ─► ECG frames, ACC frames, HR/RR   + ConnectionLog (gaps) │
│        │                                                                  │
│        ├─► RawRecorder (dev toggle) ─► data files                         │
│        ├─► RingBuffer (last N minutes ECG) ─► event clips                 │
│        ▼                                                                  │
│   ┌──────────────── :core-dsp (pure Kotlin, no Android) ───────────────┐  │
│   │ Filters → QRS detector → peak refine → Signal quality → Features   │  │
│   │   → Beat classifier (N / S / V / Q) → Pattern detector (couplets,  │  │
│   │     runs, bigeminy…) → Metrics (HR, burden, HRV windows)           │  │
│   └────────────────────────────────────────────────────────────────────┘  │
│        ▼                                                                  │
│   Room database (beats, summaries, HRV, events, gaps, labels)             │
│        ▼                                                                  │
│   Compose UI: Live · Summary · Events/Review · Diagnostics · Settings     │
└───────────────────────────────────────────────────────────────────────────┘

 PC side:
   :tools:cli (Kotlin JVM) — runs :core-dsp over recordings / MIT-BIH → CSV
   analysis/ (Python)      — plots CSVs, scores against reference annotations
```

### Modules

| Module | Type | Contents | Depends on |
|---|---|---|---|
| `:app` | Android app | BLE, service, storage, UI | `:core-dsp` |
| `:core-dsp` | Pure Kotlin/JVM library | All signal processing and classification. **No Android imports.** | — |
| `:tools:cli` | Kotlin/JVM console app | Reads recordings / PhysioNet records, runs `:core-dsp`, writes CSV | `:core-dsp` |
| `analysis/` | Python scripts (not Gradle) | Plotting, scoring, downloading MIT-BIH | reads CSV |

**Rule:** anything that is math goes in `:core-dsp`. This makes it testable with plain unit tests on
the PC (fast, no phone), and lets the CLI run *exactly* the code the phone runs.

### Data flow & threading (app)

- Polar SDK delivers data on background threads (RxJava). The service forwards frames into a single
  processing thread/coroutine (one at a time, in order).
- ECG frame (~0.5 s of data) → DSP → zero or more finished beats. A beat is "finished" only when the
  **next** beat is found (we need the following RR interval to judge compensatory pause), so
  classification latency is ~1–2 s. That is "real time" for our purposes.
- DB writes are batched (e.g. every 5–10 s) to save battery.
- UI observes state via Kotlin `Flow`s; UI being closed must not affect the service.

---

## 5. Why Kotlin-first, and where Python fits

Writing algorithms in Python first and then re-writing them in Kotlin means **two implementations**
that must be kept identical — painful, especially when you are not fluent in Kotlin.

Instead:

- **All algorithms are written once, in Kotlin**, in `:core-dsp`.
- On the PC, `:tools:cli` runs `:core-dsp` on a recording and writes CSV
  (beat times, class, quality, HRV windows…).
- **Python is only a viewer/scorer**: short scripts that plot the CSVs on top of the ECG and compute
  accuracy against reference annotations (`wfdb` reads PhysioNet databases; `matplotlib` plots).
  You only need to run them; AI writes and maintains them.
- Python becomes "real" only if we later train a neural network (training happens in PyTorch,
  then the model is exported to run on the phone) — Phase 7, optional.

---

## 6. Phases and tasks

Rough effort estimates assume part-time work with AI assistance.

### Phase 0 — Project setup (≈ 1 day)

- [x] **P0.1 [YOU]** Run `git init` and make a first commit. Create a private remote if you want backups.
- [x] **P0.2 [AI]** Add `data/`, `analysis/.venv/`, `*.bin`, `*.csv.gz` etc. to `.gitignore`.
- [x] **P0.3 [YOU]** Update H10 firmware via Polar Flow app (ECG streaming needs recent firmware),
  then **force-close Polar Flow** and disable its background activity while testing.
- [x] **P0.4 [AI]** Create modules `:core-dsp` (Kotlin JVM library) and `:tools:cli` (Kotlin JVM
  application), wire `:app` → `:core-dsp`. Note: the project uses AGP 9 with built-in Kotlin for
  Android; JVM modules need the `org.jetbrains.kotlin.jvm` plugin at the same Kotlin version
  (2.2.10 in `gradle/libs.versions.toml`).
  **Done when:** `./gradlew build` passes and a trivial test in `:core-dsp` runs.
- [x] **P0.5 [AI]** Add Polar BLE SDK dependency (check the latest release on GitHub
  `polarofficial/polar-ble-sdk` for the correct coordinates — it has historically been published via
  JitPack and needs RxJava3).
  **Done when:** app builds with the SDK.
- [x] **P0.6 [AI]** Create `CLAUDE.md` / README pointer to this plan (done if already present).

### Phase 1 — Reliable acquisition (≈ 1–2 weeks) ⭐ most important

Goal: the app connects to the H10, streams ECG + ACC + RR all night, survives Android, records raw
data, and logs every gap. Everything else depends on this.

- [x] **P1.1 [AI]** Permissions & manifest: `BLUETOOTH_SCAN` (with `neverForLocation`),
  `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `WAKE_LOCK`.
  Runtime permission flow in UI with clear explanations.
- [x] **P1.2 [AI]** Device pairing screen: scan, show H10 by device ID, save chosen device ID.
- [x] **P1.3 [AI]** `PolarConnection` wrapper around the SDK: connect, start ECG (130 Hz),
  ACC (e.g. 50 Hz, ±8 g), HR/RR. Expose Kotlin `Flow`s of typed frames
  (`EcgFrame(sensorTimeNs, phoneTimeMs, samplesUv: IntArray)`, etc.).
- [x] **P1.4 [AI]** `AcquisitionService`: foreground service (`connectedDevice` type) with a
  persistent notification showing status (connected / reconnecting / HR). Start/stop from UI.
- [x] **P1.5 [AI]** Reconnect logic: on disconnect, retry with back-off (e.g. 2 s → 5 s → 15 s → 30 s
  cap), restart streams after reconnect, keep retrying indefinitely while the session is active.
- [x] **P1.6 [AI]** `ConnectionLog`: write every connect / disconnect / stream error / app start with
  timestamps; compute gaps. Diagnostics screen listing them.
- [x] **P1.7 [AI]** `RawRecorder` (dev toggle, default ON during development): per session folder
  `data/sessions/<startUtc>/` with hourly-rotated binary files for ECG, ACC, RR + `session.json`
  (device ID, firmware, settings). Document the binary format in `docs/RAW_FORMAT.md`.
  Add an "export session" action (share as zip) to get files onto the PC.
  **Amended after P1.8 (rotating-log layout, see §8 "Raw files" for the decisions):** current files
  are `ecg.bin`/`acc.bin`/`hr.bin`; finished ones are renamed `<prefix>_<firstRecordUtc to the second>.bin`;
  a killed session is resumed in the same folder; only an explicit stop ends it.
- [x] **P1.8 [AI]** Time mapping: for each connection record (sensor time, phone UTC) pairs; convert
  sample times to UTC. Detect and log large jumps.
- [x] **P1.9 [YOU]** Battery tests: one full night + one full day streaming. Note phone battery %
  used and estimate strap battery drain (Polar Flow shows strap battery level).
- [x] **P1.10 [YOU+AI]** Live screen (Compose): scrolling ECG plot (last ~5 s), current HR, connection
  status, gap count. *Good first Compose task for you with AI help.*
- [x] **P1.11 [YOU]** **Soak test: 5+ nights.** Moisten electrodes before bed. Each morning, check
  the diagnostics screen and export.
  **Done when:** < 1 % data loss per night on average, no night with the service dead for > 10 min.
- [x] **P1.12 [YOU]** If nights fail: check Nothing OS settings (Battery → app → *Unrestricted*,
  disable adaptive battery for the app), report symptoms to AI for fixes.
- [x] **P1.13 [AI]** Strap battery level in the app (asked for after P3.6): `PolarConnection.batteryPercent`
  (the SDK's pushed level on connect and on change, plus a re-read 3 s after the streams start and every
  30 min), shown on the live screen and in the notification. Last known value is kept over disconnects.
  **Done when:** you see a plausible percentage on the phone after starting a session (confirmed on a
  strap).
- [x] **P1.14 [YOU+AI]** Floating live ECG (Picture-in-Picture): when you swipe up to Home while the live
  screen is open, the app shrinks to a small window that stays on top of other apps and shows the scrolling
  ECG and the heart rate. Needs `supportsPictureInPicture` on `MainActivity`, `setAutoEnterEnabled` with a
  wide aspect ratio, and a compact variant of the live screen without buttons. No extra permission. The window
  exists only while the Activity is alive; recording is not affected either way (the foreground service owns it).
  Built (`MainActivity`: auto-enter only while the live screen is open, plus a "Floating window" button;
  `LiveScreen(pip = true)`: trace, heart rate, strap battery); tried on the phone: works.
  **Done when:** swiping home during a session leaves a movable ECG window; it shows live data and costs no
  gaps. A free-floating overlay (`SYSTEM_ALERT_WINDOW`) was considered and rejected for now: special permission,
  more restrictions.

### Phase 2 — PC analysis toolchain (≈ 3–5 days)

- [x] **P2.1 [AI]** Raw file reader in `:core-dsp` (or `:tools:cli`) + Python reader in `analysis/`.
  (`dsp/raw/RawSession.kt`: `readStream`; `analysis/polaris_raw.py`: `read_session`.)
- [x] **P2.2 [AI]** `analysis/` setup: `requirements.txt` (`numpy`, `scipy`, `matplotlib`, `pandas`,
  `wfdb`), README with step-by-step setup on Windows.
- [x] **P2.3 [AI]** Script to download **MIT-BIH Arrhythmia DB**, **INCART** and **MIT-BIH Noise
  Stress Test DB** from PhysioNet, resample to 130 Hz, convert to our raw format + reference
  annotation CSV. (`analysis/fetch_physionet.py` → `data/reference/<db>/<record>/`; one lead per
  record: MLII / II.)
- [x] **P2.4 [AI]** CLI command `analyze <recording>` → `beats.csv`, `windows.csv`, `events.csv`.
  (Format: `docs/ANALYSIS_FORMAT.md`. Beats come from `QrsDetector` (P3.2); classes
  `U`, events only `GAP` until P4.)
- [x] **P2.5 [AI]** Python viewer: plot ECG with detected beats colored by class, quality shaded,
  zoomable; jump to a timestamp or event.
  (`analysis/view_ecg.py`; segment/time logic mirrored in `analysis/polaris_segments.py`. Shades
  gaps and low-coverage windows now, and the `quality` column once P3.4 adds it.)
- [x] **P2.6 [AI]** Python scorer: compare detections to reference annotations (beat matching within
  ±150 ms), output sensitivity / positive predictivity per class (AAMI-style, §9).
  (`analysis/score_beats.py`, tests in `test_score_beats.py`; per-class scores need P4.)
- [x] **P2.7 [YOU]** Find out what your **existing analysis tool** can export (beat list? event list
  with timestamps? PDF only?). If it exports beats/events, AI writes an importer so it becomes
  reference annotations for your own recordings. Nothing, doesn't have any export functions, can view only in app.

### Phase 3 — Beat detection & signal quality (≈ 1–2 weeks)

Details in §7.1–7.4.

- [x] **P3.1 [AI]** Streaming filters in `:core-dsp` (IIR biquads): baseline removal / band-pass for
  display & morphology, separate narrower band-pass for QRS detection. Unit tests on synthetic sine
  inputs.
  (`dsp/filter/`: `Biquad`, `FilterCascade`, `EcgFilters` + `EcgFilterConfig`; tests in
  `FilterTest.kt`. Both chains report their group delay.)
- [x] **P3.2 [AI]** Streaming QRS detector (Pan-Tompkins style) with adaptive thresholds, refractory
  period, search-back for missed beats, automatic polarity handling.
  (`dsp/detect/QrsDetector.kt` + `QrsConfig`; tests in `QrsDetectorTest.kt`. First untuned run on
  MIT-BIH, 44 non-paced records, 5 s skipped: Se 99.5 %, +P 99.3 %. Weak spots: 108, 207, 105, 203.
  Fixed after the first real night (P3.4): 20-40 mV artifacts (strap being adjusted) inflated the beat level
  so that the detector found no beat for up to 1.5 h; now one candidate counts at most 4x the level and the
  level halves per candidate after 3 s without a beat. 11 300 -> 25 850 beats on that night, MIT-BIH unchanged.)
- [x] **P3.3 [AI]** R-peak refinement (parabolic interpolation) → sub-sample timing.
  (`detect/PeakRefine.kt`; `QrsDetector` now reports fractional sample positions. Synthetic test: mean
  timing error 0.12 ms vs 2.04 ms for whole samples. Real data will be worse, check in P3.6.)
- [x] **P3.4 [AI]** Signal Quality Index per beat and per 10 s window (§7.4), using ACC.
  - [x] Step 1, ECG only (`dsp/quality/`: `EcgBeatStream`, `SlopeBeatDetector`; tests in
    `EcgBeatStreamTest.kt`): amplitude, slope noise, >40 Hz noise, second-detector agreement. Columns
    `sqi`, `good`, ... in `beats.csv` and `goodBeats`, `quality` in `windows.csv`. Calibrated on
    MIT-BIH + NSTDB: 2.6 % of true beats flagged on MIT-BIH, 89 % of its false beats; on NSTDB 11 % /
    57 %. Scorer shows this (`TPbad%`, `FPbad%`). Template correlation waits for P4.2.
  - [x] Step 2, motion (`dsp/motion/Motion.kt`; `BeatQuality.withMotion`; tests in `MotionTest.kt`): ACC RMS
    deviation per UTC second, `motion.csv`, `motionMg` columns, a motion score (1 at <= 100 mG, 0 at >= 600 mG)
    in the beat score. Calibrated on one night and one day: in strong motion the ECG stays mostly clean and
    the strap's RR counts still agree, so motion only discards extreme cases (1.4 % of the day's beats).
- [x] **P3.5 [YOU+AI]** Evaluate on MIT-BIH @130 Hz: target **Se ≥ 99.5 %, +P ≥ 99.5 %** on clean
  records. You run the scorer; AI tunes.
  (Step 1, 2026-10-27, 44 non-paced records: Se 99.6 %, +P 99.3 % raw. Without the 8 hard records (105, 108,
  201, 203, 207, 210, 228, 232): Se 99.9 %, +P 99.98 %. The +P gap is almost all noise/flutter bursts
  (207: 329 false beats in 1541-1638 s, 108: 80 + 32 in 1670-1794 s), 89-96 % of them already flagged poor by
  the quality index; "usable" +P (good-flagged beats only) is 99.9 %. Record 228 is bigeminy: the small narrow
  normal beats were missed next to the big PVCs (135 misses). New `thresholdFraction` 0.25 -> 0.15 brings
  228 from Se 93.4 to 98.5 %, total raw Se 99.6 -> 99.8 %, +P 99.3 -> 99.1 %; own night/day unchanged
  (53 / 1 differences > 10 ms, +24 beats in 6.5 h). 0.10 gives Se 99.9 / +P 98.9. Open: agree what "clean"
  means (list of excluded records or "good-flagged beats only") and then tick this.)
  **Done (decided with the owner): "clean" = detections the quality index marks good.** `score_beats.py` now
  prints `Se(g)` and `+P(g)`: poor-flagged detections are left out of the count (hits and extras), misses stay
  misses. Not a standard MIT-BIH figure, so always read it with the raw numbers. Result (44 non-paced records,
  all, at `thresholdFraction` 0.15): raw Se 99.8 % / +P 99.1 %, **Se(g) 99.8 % / +P(g) 99.9 %** (3.0 % of true
  detections left out as poor); DS2 only: 99.8 / 100.0. Weak records, per record: 108 (53 % of its beats
  flagged poor, Se(g) 95.3), 203 (+P(g) 98.4), 207 (flutter, +P(g) 98.2), 201 (Se 98.7).
- [x] **P3.6 [YOU+AI]** Evaluate on your own nights: compare our RR intervals with H10's RR stream
  (should agree within a few ms on clean beats). Look at plots of disagreements together with AI.
  (`analysis/compare_rr.py`, tests in `test_compare_rr.py`.) Result on one night (6.6 h) and one day (3 h):
  99-99.8 % of intervals pair up; on good pairs median |difference| 0 ms, p99 2 ms, 99.6-99.9 % within 5 ms,
  also in strong motion. **Found on the way:** the H10 samples at ~130.27 Hz, not 130 (from its own
  timestamps, identical in both sessions); with sample times at 130 Hz our time axis drifted 13 s in 2 h and
  every RR was 1-2 ms long. Segment time now follows the sensor clock (`EcgSegment`, `polaris_segments.py`).
  Largest remaining disagreements (25-77 ms, a few per hour) are around single beats, probably premature
  beats whose R peak the strap places differently: look at them in P4.
- [x] **P3.7 [AI]** Noise robustness on MIT-BIH NSTDB: confirm low-quality segments are flagged
  rather than producing fake beats.
  (12 records = 118 and 119 at 24, 18, 12, 6, 0, -6 dB, the first 5 s skipped, `score_beats.py data/reference/nstdb
  --skip-start 5`. At 18-24 dB nothing to flag: 1-3 false beats per record. Found: the false beats that were
  *not* flagged were tall (median 5.7 mV against 2.6 mV for true beats): noise was measured relative to the
  beat's own height, so a tall artifact looked clean. Fix in `EcgBeatStream`: noise relative to
  `min(beat height, usual height)`, usual height = median of the last 200 beats (was 20; in a noisy stretch
  the 20-beat median is inflated by the noise itself), and a beat 3-6 times the usual height loses score
  linearly (`tallAmplitudeRatio`); new test `beatsMuchTallerThanUsualAreNotGood`. Result, all 12 records:
  false beats flagged poor 61 % -> **90 %**, Se(g) 96.4 -> 96.1 %, +P(g) 92.1 -> **97.6 %**; true beats left
  out 11 % -> 19 % (they are in noise). MIT-BIH unchanged (Se(g) 99.8 / +P(g) 99.9, 3.5 % left out). Own
  night/day: good beats 98.96 -> 98.59 % / 97.23 -> 96.96 %. Remaining false beats that count as good, per
  hour of ECG: 56-258 at 12 dB or noisier (about 1-3 % of beats), 0-4 at 18 dB and cleaner; worst 119e06. The
  noisiest records (0 and -6 dB) still lose 20-30 % of their true beats to Se(g) because the noise is bigger
  than the signal. Fake V beats per hour: measure when the classifier exists (P4.5), noise should end in `Q`.)
- [x] **P3.8 [YOU+AI]** Make the reported R position stable (found in P3.6, written up in
  `docs/RR_DISAGREEMENTS.md`): in a biphasic QRS (R about as big as S) the detector sometimes marks the S
  trough instead of the R peak, 3-4 samples (25-30 ms) away, which puts a `+x, -x` error into two RR
  intervals (0.06 % of beats by day, 0.4 % at night, more around premature beats). Options and the test
  procedure are in the document. **Done when:** good pairs differing > 10 ms from the strap are rare
  (target: < 0.05 % at night), MIT-BIH scores do not drop, and polarity changes are still followed.
  (Step 1, 2026-10-27: tried a median baseline, polarity votes with hysteresis and several override values;
  table in the document. Kept `polarityOverride` 2 -> 4: > 10 ms pairs day 10 -> 1, night 102 -> 54 (0.21 %),
  MIT-BIH unchanged. New tests for a biphasic QRS and a mid-recording polarity change. Open: the night
  target; most remaining ones are one stretch around 20:00.)
  (Step 2, 2026-10-27: the 20:00 stretch is a strongly wandering baseline making R look 4x smaller than S.
  Marking the steepest slope (`steepestSlope`, off by default) gives night 54 -> 11 (0.043 %), day 1,
  MIT-BIH Se/+P unchanged, but the mark is ~2 samples after the R peak and 3 tests fail. **Owner decided to
  accept 0.21 % for now**; details and options in `docs/RR_DISAGREEMENTS.md`. Revisit with the P4.2 template.)
  (Step 3, 2026-10-27, after P4.2: the template shift corrects the displaced beats, `alignMs` in `beats.csv`:
  night 36 -> 12 of 25 227 (0.048 %, target met), day unchanged, MIT-BIH untouched. Caveat: `utcMs`/`rrMs` still
  carry the detector's marks; consumers (HRV, patterns) should add `alignMs`. **Decision (owner): leave
  `utcMs`/`rrMs` as the detector marked them; `alignMs` stays a separate column.** Details in
  `docs/RR_DISAGREEMENTS.md`, "Step 3".)

### Phase 4 — Premature beat classification (≈ 2–3 weeks)

Details in §7.5–7.6.

- [x] **P4.1 [AI]** Beat feature extraction: RR before/after, local reference RR, prematurity ratio,
  compensatory-pause ratio, correlation with normal template, QRS width estimate, amplitude/area ratios.
  (`dsp/beat/BeatFeatures.kt` (`BeatFeatureExtractor`), `BeatShape.kt`; the beat snippet comes from
  `EcgBeatStream` (`ScoredBeat.snippet`); columns in `beats.csv`, docs/ANALYSIS_FORMAT.md; tests in
  `BeatFeaturesTest.kt`. QRS width is coarse (7.7 ms per sample). `analysis/eval_features.py` shows how the
  features separate the MIT-BIH classes: AUC V vs N: corr 0.94, area ratio 0.82, prematurity 0.85, width 0.68;
  S vs N: prematurity 0.71, corr 0.68; V vs S: corr 0.88. Timing alone and shape alone are both only half
  of it, as expected: the classifier (P4.3) must combine them.)
- [x] **P4.2 [AI]** Running **normal beat template** (updated only by clean, normal-timed beats).
  (`dsp/beat/BeatTemplate.kt`: learned from 8 good, normal-timed beats (most typical beat + those that
  resemble it), updated at 5 % per beat by beats with corr >= 0.9, slides +-5 samples with sub-sample
  refinement. Safeguard: if among the last 40 good beats >= 60 % fit a second "candidate" shape and more fit
  it than the template, the template is replaced (a strap turned around, a posture change, or a template
  learned from the wrong beats: MIT-BIH 208 started on ventricular beats, V vs N AUC 0.86 -> 0.94 with this
  rule). A bigeminy or a short PVC run does not trigger it; a ventricular run of 25+ beats would, a known
  trade-off. **Side result for P3.8:** the per-beat `shift` reveals beats marked on another point of the QRS;
  `alignMs` corrects those (shift >= 2 samples from the recent median). Night RR differences > 10 ms from the
  strap: 36 -> 12 of 25 227 good pairs (0.048 %, target < 0.05 %), spread 1.30 -> 0.92 ms; day unchanged.)
- [x] **P4.3 [AI]** Rule-based classifier → `N` (normal), `S` (premature, normal shape),
  `V` (ventricular-looking), `Q` (unclassifiable / noisy). All thresholds in one config object.
  (`dsp/classify/BeatClassifier.kt`, `ClassifierConfig`; tests in `BeatClassifierTest.kt`; rules in
  docs/ANALYSIS_FORMAT.md. Thresholds chosen on MIT-BIH DS1, checked on DS2 (`score_beats.py --split`).
  Result, matched beats, poor-quality beats count as Q (a miss): **V** DS1 Se 71 % / +P 80 %, DS2 74 % / 91 %
  (80 % Se when beats called Q are left out); **S** DS1 36 % / 62 %, DS2 8 % / 26 %; **N** 94-96 % / 95-97 %
  (the rest is mostly Q: 4 % of beats are poor quality or have no template yet). F (fusion) is not a class
  we produce. S is the weak class: timing alone (no P wave at 130 Hz) and irregular rhythms; DS2 has record 232
  (1380 S beats in a slow, pausing rhythm). Findings on the way, all in the features: (1) correlation was
  hurt by half-sample misalignment of a 4-sample QRS: beats are now aligned at sub-sample precision (cubic
  interpolation): V vs N AUC 0.94 -> 0.97; (2) absolute QRS width fails for a wearer with bundle branch block
  (MIT-BIH 111): width relative to the template instead; (3) normal beats on steep baseline wander were
  called V (own night: 1015 V, clustered around 20:30 and 22:40, prematurity ~1): a fitted parabola is
  removed from beat and template before comparing: night 1015 -> 71 V; MIT-BIH V +P up; (4) S only in a
  steady rhythm (`rrSpread`) and only for an abrupt shortening (`shortening` <= 0.9, found in P4.4: a gradual
  speed-up of the rate gave runs of "S"): S +P 28 -> 62 % on DS1. Own recordings: night 0.27 % V / 0.85 % S, day 0.05 % V /
  1.6 % S, next night 0.03 % V / 2.1 % S: to be compared with the existing tool in P4.5. Known limit:
  a ventricular beat that looks like the normal one is not found by shape.)
- [x] **P4.4 [AI]** Pattern detector: couplets, triplets, runs (≥ 3 V), bigeminy, trigeminy,
  pauses > 2 s, and per-hour burden (% of beats that are V or S).
  (`dsp/pattern/Patterns.kt` (`detectPatterns`, `hourlyBurden`); tests in `PatternsTest.kt`; events in `events.csv`
  and per-hour burden in the new `burden.csv`, see docs/ANALYSIS_FORMAT.md. Definitions: couplet = exactly 2 V
  (or S) in a row, triplet 3, run 4 or more; bigeminy / trigeminy = at least 3 cycles with N in between; Q and gaps
  end a pattern; pause = more than 2 s between two *good* beats of a segment. `analysis/eval_patterns.py` counts
  the same V patterns in the MIT-BIH annotations and compares (event found = same type within 2 s):
  couplet Se 51 % / +P 61 %, triplet 23 / 26, run 29 / 18 (24 reference runs only), bigeminy 65 / 77,
  trigeminy 61 / 57. A pattern needs every one of its beats right, so these are about the beat Se (73 %) to the
  power of the length; the S patterns are not evaluated (S too weak). Own recordings: the night has 1 V couplet
  and 1 S couplet, no pauses, V burden below 1.1 % in every hour; after the `shortening` rule the false "S runs"
  of the first version (sinus speed-up, 4 runs + 10 triplets on the night) are gone.)
- [x] **P4.5 [YOU+AI]** Evaluate on MIT-BIH + INCART (AAMI classes) and on your own data vs your
  existing tool. Iterate thresholds.
  (Step 1, 2026-10-27, INCART (not used for tuning; lead II, 33 records, resampled to 130 Hz): detection raw
  Se 99.7 % / +P 98.9 %, good-quality Se(g) 99.7 % / +P(g) 99.9 %. Classes, matched beats, Q counts as a miss:
  V Se 63 % / +P 81 %, S 52 % / 59 %, N 83 % / 99 %. The N figure is low because 16 % of the true beats are
  flagged poor quality here (MIT-BIH: 3.5 %, own night: 1.5 %): the quality thresholds were calibrated on
  MIT-BIH and NSTDB, not on this lead. The existing tool the owner uses has no export (only paid images or raw
  ECG, same as ours), so a beat-by-beat comparison with it is not possible: own-data ground truth comes from
  labelling our own beats (P4.6). **Closed 2026-10-28 (owner): external data is done for now; other sources of own-data labels may come later.**)
- [ ] **P4.6 [YOU]** Label a set of your own beats in the review UI (P5.7) — at least a few hundred
  V/S beats and their neighbors. This is gold for tuning and for Phase 7.
  (Design of the labeling tool, with the `n s v q b ?` labels and the sampling plan: `docs/LABELING_TOOL.md`. The tool is built (`analysis/label_beats.py`, `eval_labels.py`); the labelling itself is up to you: start with `--gallery`, then the first night.)
- [ ] **P4.7 [AI]** Baseline drift makes the shape rules call normal beats V. Found by labelling (P4.6, first night,
  2026-10-28): of our 71 V, 37 were normal beats on a drifting baseline (V +P 39 %), all fired by `corr` < 0.5 /
  `areaRatio` > 2, with normal timing, width and height. Decision with the owner: **do not change the V rules or
  filter the drift out of the signal** (the V rule is right in most cases; filtering could bend real shapes, and
  on-time V beats exist in MIT-BIH/INCART: an "only early" gate would cost 7-11 points of V Se there, whose labels
  are partly unjudgeable on one lead anyway). Instead: new feature `driftRatio` and flag `vDoubt` = a V resting only
  on the drift-sensitive rules while `driftRatio` > 0.15 ("questionable V", still counted as V, listed for review).
  On the first night: 37 of 71 V are questionable; of those the owner called 30 N, 5 Q, 2 V; of the other 29, 22 V,
  7 N, 5 Q (confident V: precision 39 % -> 76 %). Threshold chosen on that one night, so **to be checked on the second
  night** before the number is trusted. Not done yet: V patterns (couplets, runs) still count questionable Vs;
  the Q rule (28 of 50 sampled Q were normal: motion score and slope score veto clean beats) is the next item.
  Step 2 (2026-10-28, from reviewing night 2): (a) V by `areaRatio` > 2 now also needs `corr` < 0.9 (7 same-shape beats
  at one stretch, a stale template, were V; all N for the owner); (b) an early beat (prematurity < 0.85) with
  `corr` < 0.72 is V, not S (on both nights, early beats on a flat baseline in the 0.45-0.72 zone: 33+ V, 13 S, 4 N
  for the owner; before they were S); (c) `vDoubt` also covers a V that is on time (47 of 49 such were N for the owner).
  Result on the two labelled nights (V among the owner's N/V labels): confident V 86 % / 89 %, questionable V 11 % / 22 %;
  V Se 58 -> 100 % and 70 -> 80 %, S +P 86 -> 93 % and 93 -> 94 %. MIT-BIH/INCART (simulated from the stored features,
  the Kotlin run was not repeated there): V Se +2.8 / +3.2 / +2.4 points, V +P -1.5 / -5.4 / +0.2. Remaining disagreement:
  early, different-looking beats the owner calls S where we say V (5 and 12 items): V vs S for such beats is where the
  owner is unsure too.
  Step 3 (same day): Q rescue. Of 100 sampled Q the owner called 65 N, 4 S, 1 V and 30 Q (motion >= 100 mG and
  `slopeRatio` > 0.04 veto clean beats via `sqi`). A poor beat with `corr` >= 0.9, both detectors agreeing, slope <= 0.15 and
  hf <= 0.05 is now classified (`qRescue` = 1; `good` unchanged). Owner's Q beats that were normal: 52 % recovered, 8 %
  of real Q came along; MIT-BIH true beats lost to poor quality 3.5 -> 2.7 %, INCART 16.0 -> 11.5 %, noise detections
  caught as before (MIT 93.0 -> 92.9 %, INCART 89.8, NSTDB 89.7). Disagreements with the owner's labels, nights 1 / 2:
  85 -> 74 and 63 -> 41. Open: the Q beats left (motion), windows/HRV still count rescued beats as poor.
  Step 4 (2026-10-28, from the third labelled night, 20261028, 11.8 h): on that night all 19 sampled V were N (8) or Q (11),
  none V. (a) An on-time V (prematurity >= 0.85) is now questionable even when wide and dissimilar: that exemption let
  15 such beats through on the three nights, none of them V for the owner (8 N, 7 Q); on time, 0 of 15 unflagged and 2 of
  52 flagged were V. (b) `rescueCorr` 0.9 -> 0.75: the owner's N beats lost to motion (350-875 mG) had a correlation of
  0.7-0.89; sample of poor beats with both detectors agreeing and low noise: 23 N / 2 Q at >= 0.75 (0 at >= 0.9). MIT-BIH
  (non-paced) N Se +0.3, V +P -1.0; NSTDB V +P -2.4; detection and noise flagging unchanged. (c) V pattern events say
  "k questionable" in `detail` (still reported). Disagreements with the owner's labels, nights 1 / 2 / 3: 74 -> 67, 41 -> 36,
  23 -> 16. Confident V: 91 % / 95 % V among N/V (nights 1 / 2; night 3 had no confident V with a label). (d) Window `quality` counts rescued beats as usable
  (`goodBeats` stays strictly good, for HRV to decide): unusable windows 18 -> 7, 24 -> 2, 290 -> 0 of 4252 (the motion
  stretches of night 3). Open: HRV (P5.5) decides whether rescued beats give NN intervals (their R position under motion is
  less reliable; default: no); V burden stays "between v - vDoubt and v"; a "confident only" count of V events for the
  summary/alerts (P5.6).

### Phase 5 — On-device real-time pipeline, storage & UI (≈ 2 weeks)

- [x] **P5.1 [AI]** Wire `:core-dsp` into `AcquisitionService` (single ordered processing thread).
  (Built 2026-10-29, **not yet tried on the phone**. `dsp/live/LivePipeline.kt`: the beat pipeline frame by frame (segments,
  quality, motion, features, classes); `analyzeEcg` now runs a whole recording through the same class, so the PC tool and
  the phone cannot disagree. Checked: the CLI output on the 3 labelled nights + MIT-BIH 100/207/228 is cell-for-cell
  identical to before, and `LivePipelineTest` feeds frames in arrival order and gets the same beats as the batch run (also with a
  gap and without ACC). Beats come out 2-3 s late (quality looks 0.4 s ahead, the next frame fixes the time, the motion second
  must be complete, the classifier needs the next beat). App: `processing/LiveProcessor.kt` collects `PolarConnection.ecg/acc`
  on one thread (`limitedParallelism(1)`), publishes `beats` and `LiveStats`; started/stopped by `AcquisitionService`; the
  live screen shows the counts. Nothing is stored (P5.3). **Done when:** a night with the phone shows plausible counts in the
  morning (compare with `analyze` of the same session's raw files: should be near-identical), and the CPU use is small (P6.2).
  **Checked 2026-10-29 (owner):** the night
  recorded live on the phone gave exactly the `analyze` counts: done. CPU use is still to be measured in P6.2.)
- [x] **P5.2 [AI]** Replay mode: feed a recorded session through the live pipeline at N× speed —
  lets you debug UI and pipeline at your desk without wearing the strap.
  (Built 2026-10-29, **not yet tried on the phone**. `replay/ReplayPlayer.kt` reads a session folder (`readStream`), merges the
  three streams by phone time and publishes them through new `PolarConnection.replayEcg/Acc/Hr`, i.e. the flows the strap
  uses, so `LiveProcessor` and the live screen are unchanged; no recorder runs. Pauses over 3 s are shortened; speeds 1x, 10x,
  60x, max (backpressure, no frames lost). `LiveProcessor` collectors now start undispatched so that the first frames are not
  missed. Screen: **Replay** button on the start screen; sessions from the app and from `files/replay/` (adb push, see
  `docs/RAW_FORMAT.md`). **Done when:** replaying a night shows counts equal to `analyze` of the same session.
  First try (2026-10-29, night 20261028, replay not finished): phone 65 094 beats, N 64 209, S 638, V 50 (41 questionable),
  Q 197 against `analyze` 65 545, N 64 607, S 652, V 46 (38), Q 240. `cli live-check <session>` (new) feeds the pipeline in
  arrival order on the PC and gets exactly the `analyze` result (0 of 65 545 beats differ), so the logic is fine; the cause was
  the app plumbing: ECG and ACC went through two separate flows, and in a fast replay the ECG ran seconds ahead of the ACC, so
  beats were scored without motion (the 3 s "ACC stalled" fallback). Fix: both streams go through one ordered queue in
  `LiveProcessor`; a replay hands its frames over directly, in order, with back-pressure. Repeated on the phone after the fix: Beats 65 545, N 64 607, S 652, V 46 (38), Q 240, exactly `analyze`: done.)
- [ ] **P5.3 [AI]** Room database (§8), batched writes, retention policy.
  (Built 2026-10-29, **not yet tried on the phone**. `storage/`: `PolarisDatabase` (Room, `polaris.db`), table `beat` (key = UTC ms;
  class, quality, a few features: `BeatEntity`), `BeatDao`, `BeatWriter`. The writer subscribes to `LiveProcessor.beats` when the
  service starts and writes one transaction per 5 s or 500 beats; on stop it waits for the pipeline's last beats. Retention: `beat`
  rows older than 90 days are deleted at each start. Replay does not write. The database is excluded from cloud backup.
  Other tables of §8 (`session`, `gap`, `event`, `hrv_window`, `label`...) come with the tasks that need them; `exportSchema` is off
  and there is no migration yet (the `beat` table can be rebuilt from the raw files; before `label` is added, real migrations are
  needed). The session zip (Export button) also contains `beats.csv`: the beats saved for that session's time range, with the
  first columns named as in `analyze`'s beats.csv, for comparing with it; the database file itself is not exported (P6.3). The live screen shows "Saved in the database (last 24 h)". Replaces the short-lived `liveRuns` checkpoints in
  `session.json`. **Done when:** after a night the number of saved beats equals the live count (and `analyze`); after a kill and
  restart the earlier beats are still there.)
- [ ] **P5.3b [AI] TODO, remove the clearing after export.** Temporary rule (owner, 2026-10-29): while the raw files are the
  only thing that matters, **Export last session** puts `polaris.db` and `beats.csv` into the zip and then deletes the exported
  beats from the database (and VACUUMs it); the button says so. Remove this (the `exportedUpTo` / `deleteBefore` / VACUUM part of
  `zipSession`, and "and clear saved beats" in `raw_export`) **before** the summary and review screens (P5.6/P5.7) or labels
  rely on the database, and before P6.3; from then on an export must never delete anything.
- [ ] **P5.4 [AI]** Ring buffer + **event clips**: for each event (V, S, couplet, run, pause…), save
  10 s before + 10 s after. Rate-limit clips (e.g. bigeminy for 1 hour shouldn't save 1800 clips —
  save the first few + one per N minutes).
- [ ] **P5.5 [AI]** HRV computation over 5-minute windows on clean NN intervals (§7.7).
- [ ] **P5.6 [YOU+AI]** Summary screen: today / last night / last 24 h — beats, V and S burden per
  hour (bar chart), longest run, HR min/avg/max, HRV trend, data coverage.
- [ ] **P5.7 [YOU+AI]** Event list + **review screen**: ECG clip viewer with beat markers and class
  labels; tap a beat to relabel (N/S/V/noise). Labels stored separately from automatic classes:
  a relabel never changes `beat.cls`; it appends a row to `label` (history kept, newest wins), and the screen shows
  the automatic class next to the owner's label (rule in §8).
- [ ] **P5.8 [AI]** Settings: raw recording toggle, ACC rate, classifier sensitivity presets,
  optional alerts (off by default), data export (CSV) and delete.
- [ ] **P5.10 [YOU+AI]** **Actions and events** (idea from the owner, 2026-10-29: see why the per-hour numbers change, e.g. during a
  workout or in the middle of the day). Design agreed in outline, details to settle when building:
  - *Two kinds.* **Action** = a state with a duration (walking, running, sitting, eating, sleeping...): starts when pressed, ends when
    the next action starts, on "no action", or on the session's Stop. **Event** = a moment (dizzy, stress, palpitations...): a time and
    an optional note.
  - *Buttons.* "Action" and "Event" on both the live screen and the main screen. **The time is taken at the press**, then the picker
    opens, so choosing slowly does not shift the time; backing out of the picker records nothing. The picker lists the available
    types (most recently used first, a few presets to start with) and has a "new type" button; long-press a type to rename or hide it
    (hidden, never deleted: old records keep their name). Events can get a one-line note.
  - *Mistakes.* A record can be corrected ("it was 10 min ago", wrong type) but, like labels (§8), the original press time and type are
    kept: corrections are new rows, the newest wins.
  - *Storage.* These are human input and cannot be rebuilt from the raw ECG, so the **source of truth is an append-only file in the
    session folder** (`annotations.jsonl`, one line per press/correction, JST shown but UTC stored; layout to be added to
    `docs/RAW_FORMAT.md`), so it travels with the export and survives clearing the database; the database tables (`activity_type`,
    `activity`, `event`) are an index of it, rebuilt from the files if needed.
  - **Done when:** a day of use shows correct bands and markers, an action left open at a kill is closed at the next Stop, and the export
    zip contains the annotations.
- [ ] **P5.11 [AI]** Show actions and events on the phone: bands (actions) and markers (events) on the review screen's timeline and in
  the event list; the summary (P5.6) gets "per action" figures (mean HR, V/S per hour, HRV).
- [ ] **P5.12 [AI]** Desktop: `analyze` reads `annotations.jsonl` and writes `activities.csv`, an `action` column in `windows.csv`, and a
  per-action summary (time spent, mean HR, V/S burden per hour, HRV); which of the viewers/plots get action bands is decided after the
  first week of data (candidates: the hourly burden plot, the beat viewer, the labelling tool as context).
- [ ] **P5.9 [YOU]** 1–2 weeks of daily use; keep a short note of anything wrong or annoying.

### Phase 6 — Hardening (ongoing)

- [ ] **P6.1 [AI]** Crash safety: service restarts after crash, resumes session, logs the gap.
- [ ] **P6.2 [AI]** Performance check: CPU time per minute of ECG on the phone, memory, DB size per day.
- [ ] **P6.3 [AI]** Backup/export of the database to a user-chosen folder.
- [ ] **P6.4 [AI]** Unit-test coverage for `:core-dsp` with golden files (a recording + expected
  `beats.csv`; any algorithm change shows a diff).

### Phase 7 — Optional: learned classifier

Only if rules plateau (e.g. S beats or noise misclassified too often).

- [ ] **P7.1 [AI]** PyTorch 1D-CNN: input ≈ 1 s ECG around the beat (130 samples) + RR features;
  pre-train on MIT-BIH + INCART (130 Hz), fine-tune on your labeled beats.
- [ ] **P7.2 [AI]** Export to TFLite / ONNX, run in `:app` (model input prepared by `:core-dsp`).
- [ ] **P7.3 [YOU+AI]** Compare with rule-based on held-out nights; keep rules as fallback.

---

## 7. Algorithms in detail

Numbers below are **starting points**; all must live in a config object and be tuned on data.

### 7.1 Filtering (streaming, causal)

- **Display / morphology signal:** high-pass ≈ 0.5 Hz (removes breathing baseline wander) +
  low-pass ≈ 40 Hz. Notch for 50 Hz is usually unnecessary (H10 is battery powered, close to body)
  — add only if mains noise appears in data.
- **Detection signal:** band-pass ≈ 5–20 Hz (keeps QRS energy, suppresses P/T waves and baseline).
- Implement as cascaded biquad IIR sections (cheap, streaming). Record the group delay so beat times
  can be corrected back to the original signal.

### 7.2 QRS detection (Pan-Tompkins family)

1. Detection signal → derivative → square → moving-window integration (~150 ms ≈ 20 samples).
2. Adaptive thresholds from running estimates of signal peaks vs noise peaks.
3. **Refractory period** ~200 ms; for a candidate 200–360 ms after the previous beat, require a
   steep slope (rejects T-waves).
4. **Search-back:** if no beat found within ~1.66 × average RR, re-search with a lower threshold.
5. **Polarity:** locate R as the max |amplitude| in the morphology signal near the detection; learn
   dominant polarity during the first ~30 s and per template.
6. **Refinement:** fit a parabola through the peak sample and its neighbors → sub-sample R time.
   Reduces timing error from ±3.8 ms to roughly 1–2 ms on clean beats.

### 7.3 Using the H10 RR stream

H10 sends RR intervals ~once per second **without per-beat timestamps**. To pair them with our
ECG-detected beats we align the two RR sequences (match patterns of consecutive intervals). Where
alignment is confident, HRV uses H10 RR values; otherwise it falls back to our refined ECG RR.
Disagreement between the two is also a useful quality signal. *(This alignment is a known tricky
part — if it turns out unreliable, refined ECG RR alone is acceptable for HRV.)*

### 7.4 Signal Quality Index (SQI)

Per beat, combine into a score 0–1:
- **Template correlation** with the current normal template (low for noise *and* for real PVCs —
  so not used alone).
- **Two-detector agreement (bSQI):** run a second, differently built detector; beats both agree on
  are trustworthy.
- **Amplitude sanity:** flat line (contact lost), clipping, huge spikes.
- **High-frequency noise ratio:** energy above ~40 Hz vs QRS band.
- **Motion:** ACC magnitude variance in the surrounding second.

Per 10 s window: fraction of good beats. Windows below a threshold are marked **unusable** and
excluded from burden and HRV (but counted in "coverage").

### 7.5 Beat features

For beat *i* with intervals `RRpre = t[i] - t[i-1]`, `RRpost = t[i+1] - t[i]`:
- `RRref` = median of the last ~8–10 **normal** RR intervals.
- `prematurity = RRpre / RRref` (< 1 = early).
- `compensation = (RRpre + RRpost) / (2 · RRref)` (≈ 1 → full compensatory pause, typical for PVC;
  clearly < 1 → non-compensatory, typical for PAC).
- `corr` = correlation of the beat (≈ −250 ms … +400 ms) with the normal template.
- `qrsWidth` estimate (onset/offset by slope threshold on morphology signal) — at 130 Hz this is
  coarse (±8 ms), so used together with other features, not alone.
- Amplitude ratio and QRS area ratio vs template; polarity flip flag.

### 7.6 Classification rules (v1)

Background, known algorithms (Lipponen–Tarvainen, Tsipouras, OSEA, de Chazal) and why timing alone
is not enough: `docs/PVC_DETECTION_NOTES.md`.

```
if beat SQI low                                         → Q
else if shape abnormal (corr < ~0.80 or wide QRS or polarity flip)
                                                         → V   (early or not: interpolated/late PVCs exist)
else if prematurity < ~0.85                              → S
else                                                     → N
```
- Only `N` beats with good SQI update the template and `RRref`.
- If many consecutive beats are "abnormal shape", the *template* may be wrong (e.g. posture changed
  the QRS shape, or rate-dependent changes): re-learn template rather than reporting a V-storm.
  This is a key safeguard — posture changes during the night alter chest-lead morphology.
- Patterns from the class sequence: couplet = VV, triplet = VVV, run = ≥3 V,
  bigeminy = ≥3 cycles of N V, trigeminy = ≥3 cycles of N N V; same for S. Pause = RR > 2 s.

### 7.7 HRV

- Build **NN series**: intervals where both beats are `N` with good SQI; drop intervals adjacent to
  any S/V/Q beat; drop physiologically impossible values (< 300 ms or > 2000 ms) and jumps > 20 %
  from the previous NN.
- Per 5-minute window: mean HR, **RMSSD**, **SDNN**, **pNN50**; frequency domain **LF**
  (0.04–0.15 Hz), **HF** (0.15–0.40 Hz), LF/HF via **Lomb-Scargle** (works on uneven spacing, no
  interpolation needed).
- Store `coverage` (fraction of the window that produced valid NN intervals). Hide or grey out
  windows with coverage < ~70 %.
- Most meaningful daily number: **night-time RMSSD** and resting HR (later, once sleep/wake exists).

---

## 8. Data model and storage

### Raw files (development; optional later)

`data/sessions/<startUtc>/` — rotating-log layout (exact layout in `docs/RAW_FORMAT.md`,
task P1.7). Per frame: phone UTC ms, sensor timestamp, sample count, samples. ≈ 30–50 MB/day.

**Decisions (made with the owner while building P1.8; keep unless there is a reason to change):**

1. **Rotating logs.** The file being written has no timestamp (`ecg.bin`, `acc.bin`, `hr.bin`,
   `session.json`, `timemap.csv`). Finished files are renamed `<prefix>_<yyyyMMdd'T'HHmmss'Z'>.bin`
   by the phone time of their **first record**, to the second. Rotation: every UTC hour, on stop,
   on recovery. The timestamp can always be re-derived from the file content, so a kill loses
   nothing about naming.
2. **Never delete, never overwrite.** If a target name is taken, append a counter (`_1`, `_2`)
   and retry. Files with only a header are kept and marked `_empty`; unreadable ones `_invalid`
   (time from last-modified), so that "this happened" stays visible.
3. **One session = one folder with all its data, across kills.** `session.json` has `endUtc` null
   until a proper stop. Restart after a kill (automatic or manual start) **resumes** the same
   session: leftovers are archived, an `interruptions` entry is added.
4. **Only the user's Stop ends a session** (button or notification action) and writes `endUtc`.
   The next Start is then a brand-new session. Android destroying the service is *not* a stop.
5. **`session.json` keeps its name** (one per folder; the folder name carries the start time) and is
   written atomically (temp file + move). Sessions closed by recovery get `"recovered": true`.
6. **Export = the whole newest session folder**, so a night with kills still comes out as one zip.
7. `timemap.csv` is not rotated (≈ 1 row/min); its connection numbers continue across a resume.

Not decided / open: resume has no age limit (a session killed days ago is still resumed by the
next start); the gap between `lastDataUtc` and `resumedUtc` is only in `session.json`, not yet in
the Diagnostics screen.

### Database (Room / SQLite)

| Table | Key columns | Rows/day (approx) | Retention |
|---|---|---|---|
| `session` | id, start, end, deviceId, firmware, settings JSON | 1–2 | forever |
| `gap` | sessionId, start, end, reason | few–dozens | forever |
| `beat` | time (UTC µs), rrPre, cls (N/S/V/Q), sqi, corr | ~100 000 | e.g. 90 days |
| `minute_summary` | minute, beats, hrMean, hrMin, hrMax, nS, nV, nQ, coverage, accActivity | 1 440 | forever |
| `hrv_window` | start (5 min), meanHr, rmssd, sdnn, pnn50, lf, hf, coverage | 288 | forever |
| `event` | id, start, end, type (V, S, couplet, run, bigeminy, pause…), beatCount, clipFile | varies | forever (clips may be pruned) |
| `label` | beatTime, userClass, note, createdAt | as you label | forever |

**Labels never touch the automatic result (decided with the owner, 2026-10-29).** The `beat.cls` written by the pipeline is
never changed by a relabel: the original automatic value must always be there to compare with, to measure the classifier and
to tune it. The owner's marks live only in `label`, as an **append-only history**: a changed mind or a corrected mistake adds
a new row (`beatTime`, `userClass`, `note`, `createdAt`) and never overwrites or deletes the earlier one; the current label of
a beat is its newest row. Screens show the automatic class and the owner's label side by side. The same holds for exports
(CSV): both columns, plus the history. Labels are the one thing that cannot be rebuilt from the raw files, so `label` needs real
migrations and must be part of the backup (P6.3).

Clips: `files/clips/<eventId>.bin` (20 s × 130 Hz ≈ 10 KB each).

**Privacy:** data stays in app-private storage. Exports only on explicit user action. Consider
disabling Android auto-backup to the cloud for the DB (`backup_rules.xml`) since it's health data.

---

## 9. Validation strategy

| What | Reference | Metric | Target (initial) |
|---|---|---|---|
| Beat detection | MIT-BIH @130 Hz | Sensitivity, +Predictivity | ≥ 99.5 % / ≥ 99.5 % |
| Beat timing | H10 RR stream (own data) | median abs RR diff | < 5 ms on clean beats |
| V detection | MIT-BIH + INCART (AAMI "V") | Se, +P | ≥ 90 % / ≥ 90 % |
| S detection | MIT-BIH + INCART (AAMI "S") | Se, +P | ≥ 60–70 % (hard at 130 Hz, one lead) |
| V/S burden | Your existing tool, same sessions | hourly burden difference | within a few % absolute |
| Noise handling | MIT-BIH NSTDB | fake V per hour at low SNR | as low as possible; noise → Q |
| Reliability | Your nights | data loss | < 1 % per night |

Important rules:
- **Never tune on the same records you report scores on.** Split MIT-BIH records into a tuning set
  and a test set (the standard "DS1 / DS2" split by de Chazal et al. is a good choice).
- Your own data matters more than public scores. When results disagree with your existing tool,
  look at the ECG clip yourself — either one can be wrong.

---

## 10. Later / maybe

- **Sleep/wake detection** from ACC + HR (actigraphy) — cheap and fairly reliable; enables
  "night-time HRV" and "PVCs asleep vs awake".
- **Sleep stages** — deliberately dropped. Proper staging needs EEG (brain signals); libraries like
  YASA work only with EEG. From heart rate + movement, any model (including commercial wearables)
  gives only moderate accuracy for deep/REM.
- **Breathing rate** from chest ACC / ECG-derived respiration.
- **AF screening** from RR irregularity.
- Home-screen widget with live HR and today's burden.
- Learned classifier (Phase 7).

---

## 11. Glossary

| Term | Meaning |
|---|---|
| **ECG** | Electrocardiogram — electrical activity of the heart. |
| **QRS / R-peak** | The tall spike of each heartbeat; R is its peak. Used to locate beats. |
| **RR interval** | Time between two consecutive R-peaks (ms). |
| **NN interval** | RR interval between two *normal* beats — what HRV is computed on. |
| **PVC / V** | Premature Ventricular Contraction — early beat from the ventricles; wide, odd-shaped QRS. |
| **PAC / S** | Premature Atrial (supraventricular) Contraction — early beat with normal-shaped QRS. |
| **Compensatory pause** | Longer-than-normal interval after a premature beat. |
| **Couplet / triplet / run** | 2 / 3 / ≥3 premature beats in a row. |
| **Bigeminy / trigeminy** | Every 2nd / 3rd beat is premature, repeating. |
| **Burden** | % of all beats that are premature (V or S). |
| **HRV** | Heart Rate Variability — variation between beats; reflects autonomic nervous system. |
| **RMSSD / SDNN / pNN50** | Common HRV measures (short-term variability / overall variability / % of successive differences > 50 ms). |
| **LF / HF** | Low / high frequency power of HRV; HF relates to breathing (vagal). |
| **SQI** | Signal Quality Index — how trustworthy a beat / segment is. |
| **Template** | Running average of your normal beat shape, used to spot abnormal beats. |
| **Causal filter** | Filter that only uses past samples — needed for real time. |
| **AAMI EC57** | Standard for evaluating arrhythmia detectors; classes N, S, V, F, Q. |
| **MIT-BIH / INCART / NSTDB** | Public annotated ECG databases on PhysioNet. |
| **Foreground service** | Android component that keeps running in background with a visible notification. |
| **PMD** | Polar Measurement Data — Polar's BLE service for raw ECG/ACC streaming. |
