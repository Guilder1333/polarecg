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

- **130 Hz is a low sampling rate.** One sample every 7.7 ms. Fine for finding beats and judging
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
- [ ] **P1.4 [AI]** `AcquisitionService`: foreground service (`connectedDevice` type) with a
  persistent notification showing status (connected / reconnecting / HR). Start/stop from UI.
- [ ] **P1.5 [AI]** Reconnect logic: on disconnect, retry with back-off (e.g. 2 s → 5 s → 15 s → 30 s
  cap), restart streams after reconnect, keep retrying indefinitely while the session is active.
- [ ] **P1.6 [AI]** `ConnectionLog`: write every connect / disconnect / stream error / app start with
  timestamps; compute gaps. Diagnostics screen listing them.
- [ ] **P1.7 [AI]** `RawRecorder` (dev toggle, default ON during development): per session folder
  `data/sessions/<startUtc>/` with hourly-rotated binary files for ECG, ACC, RR + `session.json`
  (device ID, firmware, settings). Document the binary format in `docs/RAW_FORMAT.md`.
  Add an "export session" action (share as zip) to get files onto the PC.
- [ ] **P1.8 [AI]** Time mapping: for each connection record (sensor time, phone UTC) pairs; convert
  sample times to UTC. Detect and log large jumps.
- [ ] **P1.9 [YOU]** Battery tests: one full night + one full day streaming. Note phone battery %
  used and estimate strap battery drain (Polar Flow shows strap battery level).
- [ ] **P1.10 [YOU+AI]** Live screen (Compose): scrolling ECG plot (last ~5 s), current HR, connection
  status, gap count. *Good first Compose task for you with AI help.*
- [ ] **P1.11 [YOU]** **Soak test: 5+ nights.** Moisten electrodes before bed. Each morning, check
  the diagnostics screen and export.
  **Done when:** < 1 % data loss per night on average, no night with the service dead for > 10 min.
- [ ] **P1.12 [YOU]** If nights fail: check Nothing OS settings (Battery → app → *Unrestricted*,
  disable adaptive battery for the app), report symptoms to AI for fixes.

### Phase 2 — PC analysis toolchain (≈ 3–5 days)

- [ ] **P2.1 [AI]** Raw file reader in `:core-dsp` (or `:tools:cli`) + Python reader in `analysis/`.
- [ ] **P2.2 [AI]** `analysis/` setup: `requirements.txt` (`numpy`, `scipy`, `matplotlib`, `pandas`,
  `wfdb`), README with step-by-step setup on Windows.
- [ ] **P2.3 [AI]** Script to download **MIT-BIH Arrhythmia DB**, **INCART** and **MIT-BIH Noise
  Stress Test DB** from PhysioNet, resample to 130 Hz, convert to our raw format + reference
  annotation CSV.
- [ ] **P2.4 [AI]** CLI command `analyze <recording>` → `beats.csv`, `windows.csv`, `events.csv`.
- [ ] **P2.5 [AI]** Python viewer: plot ECG with detected beats colored by class, quality shaded,
  zoomable; jump to a timestamp or event.
- [ ] **P2.6 [AI]** Python scorer: compare detections to reference annotations (beat matching within
  ±150 ms), output sensitivity / positive predictivity per class (AAMI-style, §9).
- [ ] **P2.7 [YOU]** Find out what your **existing analysis tool** can export (beat list? event list
  with timestamps? PDF only?). If it exports beats/events, AI writes an importer so it becomes
  reference annotations for your own recordings. **This is the most valuable ground truth we can get.**

### Phase 3 — Beat detection & signal quality (≈ 1–2 weeks)

Details in §7.1–7.4.

- [ ] **P3.1 [AI]** Streaming filters in `:core-dsp` (IIR biquads): baseline removal / band-pass for
  display & morphology, separate narrower band-pass for QRS detection. Unit tests on synthetic sine
  inputs.
- [ ] **P3.2 [AI]** Streaming QRS detector (Pan-Tompkins style) with adaptive thresholds, refractory
  period, search-back for missed beats, automatic polarity handling.
- [ ] **P3.3 [AI]** R-peak refinement (parabolic interpolation) → sub-sample timing.
- [ ] **P3.4 [AI]** Signal Quality Index per beat and per 10 s window (§7.4), using ACC.
- [ ] **P3.5 [YOU+AI]** Evaluate on MIT-BIH @130 Hz: target **Se ≥ 99.5 %, +P ≥ 99.5 %** on clean
  records. You run the scorer; AI tunes.
- [ ] **P3.6 [YOU+AI]** Evaluate on your own nights: compare our RR intervals with H10's RR stream
  (should agree within a few ms on clean beats). Look at plots of disagreements together with AI.
- [ ] **P3.7 [AI]** Noise robustness on MIT-BIH NSTDB: confirm low-quality segments are flagged
  rather than producing fake beats.

### Phase 4 — Premature beat classification (≈ 2–3 weeks)

Details in §7.5–7.6.

- [ ] **P4.1 [AI]** Beat feature extraction: RR before/after, local reference RR, prematurity ratio,
  compensatory-pause ratio, correlation with normal template, QRS width estimate, amplitude/area ratios.
- [ ] **P4.2 [AI]** Running **normal beat template** (updated only by clean, normal-timed beats).
- [ ] **P4.3 [AI]** Rule-based classifier → `N` (normal), `S` (premature, normal shape),
  `V` (ventricular-looking), `Q` (unclassifiable / noisy). All thresholds in one config object.
- [ ] **P4.4 [AI]** Pattern detector: couplets, triplets, runs (≥ 3 V), bigeminy, trigeminy,
  pauses > 2 s, and per-hour burden (% of beats that are V or S).
- [ ] **P4.5 [YOU+AI]** Evaluate on MIT-BIH + INCART (AAMI classes) and on your own data vs your
  existing tool. Iterate thresholds.
- [ ] **P4.6 [YOU]** Label a set of your own beats in the review UI (P5.7) — at least a few hundred
  V/S beats and their neighbors. This is gold for tuning and for Phase 7.

### Phase 5 — On-device real-time pipeline, storage & UI (≈ 2 weeks)

- [ ] **P5.1 [AI]** Wire `:core-dsp` into `AcquisitionService` (single ordered processing thread).
- [ ] **P5.2 [AI]** Replay mode: feed a recorded session through the live pipeline at N× speed —
  lets you debug UI and pipeline at your desk without wearing the strap.
- [ ] **P5.3 [AI]** Room database (§8), batched writes, retention policy.
- [ ] **P5.4 [AI]** Ring buffer + **event clips**: for each event (V, S, couplet, run, pause…), save
  10 s before + 10 s after. Rate-limit clips (e.g. bigeminy for 1 hour shouldn't save 1800 clips —
  save the first few + one per N minutes).
- [ ] **P5.5 [AI]** HRV computation over 5-minute windows on clean NN intervals (§7.7).
- [ ] **P5.6 [YOU+AI]** Summary screen: today / last night / last 24 h — beats, V and S burden per
  hour (bar chart), longest run, HR min/avg/max, HRV trend, data coverage.
- [ ] **P5.7 [YOU+AI]** Event list + **review screen**: ECG clip viewer with beat markers and class
  labels; tap a beat to relabel (N/S/V/noise). Labels stored separately from automatic classes.
- [ ] **P5.8 [AI]** Settings: raw recording toggle, ACC rate, classifier sensitivity presets,
  optional alerts (off by default), data export (CSV) and delete.
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

`data/sessions/<startUtc>/` — hourly rotated binary files (exact layout in `docs/RAW_FORMAT.md`,
task P1.7). Per frame: phone UTC ms, sensor timestamp, sample count, samples. ≈ 30–50 MB/day.

### Database (Room / SQLite)

| Table | Key columns | Rows/day (approx) | Retention |
|---|---|---|---|
| `session` | id, start, end, deviceId, firmware, settings JSON | 1–2 | forever |
| `gap` | sessionId, start, end, reason | few–dozens | forever |
| `beat` | time (UTC µs), rrPre, cls (N/S/V/Q), sqi, corr, labelOverride? | ~100 000 | e.g. 90 days |
| `minute_summary` | minute, beats, hrMean, hrMin, hrMax, nS, nV, nQ, coverage, accActivity | 1 440 | forever |
| `hrv_window` | start (5 min), meanHr, rmssd, sdnn, pnn50, lf, hf, coverage | 288 | forever |
| `event` | id, start, end, type (V, S, couplet, run, bigeminy, pause…), beatCount, clipFile | varies | forever (clips may be pruned) |
| `label` | beatTime, userClass, note, createdAt | as you label | forever |

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
