# Analysis output (`:tools:cli analyze`)

```
./gradlew :tools:cli:run --args="analyze data/reference/mitdb/100"
./gradlew :tools:cli:run --args="analyze data/sessions/20261012T221530Z --out data/analysis/night1"
```

Input: any folder with `ecg*.bin` files (a session folder or a converted reference record).
Output (default `data/analysis/<parent>-<folder>/`, git-ignored; overwritten on every run):
UTF-8 CSV, `,` separated, `.` decimals, header row, times as epoch ms (`*Ms`, for code) and
ISO-8601 UTC (for reading).

> Beats come from the streaming Pan-Tompkins detector `detect/QrsDetector.kt` (P3.2); `utcMs` is
> the R peak in the original signal (refined to sub-sample precision with a parabola, rounded to whole ms).
> Nothing is reported during the first 2 s of a segment (threshold learning). Classes are `U`
> until P4.

## Segments

The ECG frames are cut into gap-free *segments*: a new one starts when a frame does not follow
the previous one on the sensor clock (tolerance 25 ms). Detectors never run across a gap.
Time inside a segment follows the sensor clock, frame by frame (the H10 samples at ~130.27 Hz, not
130 Hz, so counting samples would drift by seconds per hour); the segment start is mapped to phone UTC
with `TimeMapper`. Code: `dsp/analysis/EcgSegments.kt`.

## `beats.csv`

| Column | Meaning |
|---|---|
| `utcMs`, `utc` | time of the R peak |
| `segment` | segment number (0-based) |
| `rrMs` | interval to the previous beat of the **same segment**; empty for the first beat of a segment |
| `class` | `N` normal, `S` premature with normal shape, `V` ventricular-looking, `Q` cannot be judged (poor signal or no template yet); rules and thresholds in `dsp/classify/BeatClassifier.kt` (`ClassifierConfig`), see below |
| `sqi` | signal quality index of the beat, 0..1 (see below) |
| `good` | 1 if `sqi` ≥ 0.6, else 0 |
| `amplitudeUv` | peak-to-peak height of the beat in the 0.5–40 Hz signal |
| `slopeRatio` | typical sample-to-sample change near the beat (outside the QRS) relative to `amplitudeUv`; electrode/motion noise raises it; clean beats are mostly below 0.03, noisy ones above 0.04 |
| `hfRatio` | RMS of the >40 Hz signal near the beat relative to `amplitudeUv` (muscle noise); clean beats below 0.03 |
| `agreed` | 1 if the second, differently built detector also saw the beat |
| `motionMg` | accelerometer motion in the second around the beat (highest of the 1 s bins within ±0.5 s), mG; empty without ACC data |
| `rrRefMs` | reference RR: median of the last 9 normal intervals (between good beats); empty until 3 are known |
| `rrSpread` | irregularity of the recent normal intervals: median distance from `rrRefMs` as a fraction of it (steady sinus rhythm ~0.02; above 0.05 `S` is not called) |
| `prematurity` | `rrMs / rrRefMs`; below 1 = early (a premature beat is typically below 0.85) |
| `shortening` | `rrMs` / the interval before it. A premature beat is short against the one before (0.6-0.8); a gradual speed-up of the heart rate is about 0.95 per beat |
| `compensation` | `(rrPre + rrPost) / (2 · rrRef)`: about 1 after a ventricular beat (full compensatory pause), clearly below 1 for many atrial ones; empty for the last beat of a segment |
| `corr` | correlation (−1..1) of the beat (−250 … +400 ms of the 0.5–40 Hz signal) with the running normal-beat template at the best alignment (±5 samples); empty while the template is still being learned, for poor-quality-only stretches, or if the segment ends too close to the beat |
| `shift` | that alignment in samples (fractional): how far after the marked R position the beat's own R feature lies, relative to the template |
| `qrsWidthMs` | QRS width from the slope (coarse, ±8 ms at 130 Hz): normal beats 54–92 ms, ventricular ones often wider |
| `widthRatio` | `qrsWidthMs` relative to the template's width (a wearer with a bundle branch block has wide normal beats) |
| `ampRatio`, `areaRatio` | QRS height / area (±92 ms around R) divided by the template's; empty while there is no template |
| `flip` | 1 if the beat looks like the template upside down (inverted correlation ≥ 0.6 and better than the upright one) |
| `qRescue` | 1 = a beat with `good` = 0 that was classified N/S/V instead of Q because it matches the template (rule 1 below). `good` and `sqi` are unchanged |
| `driftRatio` | baseline wander around the beat: scatter of the samples left from a straight line through the stretches before and after the QRS, relative to the QRS height (P4.7). Flat baseline about 0.1; 0.15 and more = drifting, the shape comparison (`corr`, `areaRatio`) is unreliable there |
| `vDoubt` | 1 = class `V` that may be a normal beat, **to be reviewed**: `driftRatio` > 0.15, or the beat is on time (`prematurity` ≥ 0.85) and only the correlation/area rules call it V. Still counted as V. Never set for `flip` or wide-and-dissimilar beats |
| `alignMs` | time correction in ms (P3.8): 0 for almost all beats; ≥ 2 samples (15 ms) of difference from the usual `shift` of the recent beats means the beat was marked on another point of the QRS than its neighbours, and `utcMs + alignMs` is the time on the same feature as theirs. Not applied to `utcMs`/`rrMs` |

`sqi` = amplitude score × slope score × hf score × motion score × (1 if `agreed`, else 0.4). Amplitude score: 0 for a
flat line (< 20 µV), a tiny beat (< 15 % of the median of the last 200 beats) or a spike (> 6×; between 3× and 6× the score falls linearly), rising to
1 at 50 %. Slope score (noise relative to the smaller of the beat's height and the usual height, P3.7): 1 at `slopeRatio` ≤ 0.02, 0 at ≥ 0.07. hf score: 1 at ≤ 0.03, 0 at ≥ 0.10. Motion score: 1 at ≤ 100 mG, 0 at ≥ 600 mG (1 without ACC data).
All thresholds are in `QualityConfig` (`dsp/quality/EcgBeatStream.kt`). Beats are scored 0.4 s
after they happen (the noise after the beat counts too). The template is in the feature columns (`corr`); it is not part of `sqi` yet.

### Classes

Rules (P4.3, plan §7.6), in this order:

1. `Q` if the beat is poor quality (`good` = 0) or has no `corr` (no template yet, segment edge). **Rescue (P4.7):** a poor beat is judged anyway if `corr` ≥ 0.9, `agreed` = 1, `slopeRatio` ≤ 0.15 and `hfRatio` ≤ 0.05 (`qRescue` = 1); it keeps `good` = 0 for windows, quality and the reference RR.
2. `V` if the shape is abnormal: `flip` = 1, or `corr` < 0.5, or (`widthRatio` ≥ 1.5 and `corr` < 0.9), or (`areaRatio` > 2 and `corr` < 0.9: the same shape only larger is not a different beat), or the beat is early (`prematurity` < 0.85) and `corr` < 0.72 (looks different AND comes early; P4.7).
3. `S` if `prematurity` < 0.85, `shortening` ≤ 0.9 (abrupt, not a gradual speed-up) and `rrSpread` ≤ 0.05 (a steady rhythm; in an irregular one "early" says little).
4. `N` otherwise.

Shape comparisons are done after removing a fitted parabola (baseline wander) from the beat and the template.
`S` is the weakest class: it rests on timing alone. All thresholds are in `ClassifierConfig`.

## `windows.csv`

One row per 10 s window aligned to UTC (`startUtcMs` is a multiple of 10 000), from the first to
the last sample.

| Column | Meaning |
|---|---|
| `startUtcMs`, `startUtc`, `durationMs` | window |
| `samples` | ECG samples received in it (1300 when complete) |
| `coverage` | `samples` / 1300, 0..1 |
| `beats` | beats whose R peak is in the window |
| `meanHrBpm` | 60000 / mean RR of the beats in the window with 250 ≤ RR ≤ 3000 ms; empty if none |
| `motionMg` | highest motion of the 1 s bins in the window (mG); empty without ACC data |
| `goodBeats` | beats of the window with `good` = 1 |
| `quality` | `goodBeats` / `beats`, 0..1; 0 if the window has no beat. Below 0.5 the window is unusable (red in the viewer, to be left out of burden and HRV) |

## `motion.csv`

Written only when the recording has `acc*.bin` files. One row per UTC second with enough ACC samples
(at least 25 of 50; seconds mostly inside a gap are left out).

| Column | Meaning |
|---|---|
| `startUtcMs`, `startUtc` | start of the second |
| `motionMg` | RMS deviation of the acceleration vector from its average over the second, mG. Gravity alone is not motion: lying still gives 3–5 mG, walking hundreds. A posture change shows up because gravity swings through the axes |
| `samples` | ACC samples in the second (50 when complete) |

ACC samples are put on the UTC time line with the same sensor-clock mapping as the ECG
(`dsp/motion/Motion.kt`).

## `events.csv`

| Column | Meaning |
|---|---|
| `utcMs`, `utc` | start of the event |
| `type` | `GAP` = no signal between two segments; `PAUSE` = more than 2 s between two good beats of one segment; patterns of ectopic beats `V_…` (ventricular) and `S_…` (premature): `COUPLET` (exactly 2 in a row), `TRIPLET` (3), `RUN` (4 or more), `BIGEMINY` (N X N X N X…, at least 3 cycles), `TRIGEMINY` (N N X N N X…, at least 3 cycles). A Q beat or a gap ends a pattern |
| `durationMs` | length (for a pattern: first to last beat) |
| `detail` | free text: beats and rate of a run, cycles of a bigeminy, seconds of a pause |

## `burden.csv`

One row per UTC hour with at least one beat.

| Column | Meaning |
|---|---|
| `startUtcMs`, `startUtc` | start of the hour |
| `n`, `s`, `v`, `q` | number of beats of each class |
| `vDoubt` | of the `v` beats, how many are questionable (`vDoubt` = 1 in beats.csv); the V burden is between `v - vDoubt` and `v` |
| `vPercent`, `sPercent` | share of V / S among the beats that could be judged (`n + s + v`; `q` is left out); empty if there are none |
