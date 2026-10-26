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
Time inside a segment follows the sensor clock; the segment start is mapped to phone UTC with
`TimeMapper`. Code: `dsp/analysis/EcgSegments.kt`.

## `beats.csv`

| Column | Meaning |
|---|---|
| `utcMs`, `utc` | time of the R peak |
| `segment` | segment number (0-based) |
| `rrMs` | interval to the previous beat of the **same segment**; empty for the first beat of a segment |
| `class` | `U` = unclassified (N / S / V arrive with P4) |
| `sqi` | signal quality index of the beat, 0..1 (see below) |
| `good` | 1 if `sqi` ≥ 0.6, else 0 |
| `amplitudeUv` | peak-to-peak height of the beat in the 0.5–40 Hz signal |
| `slopeRatio` | typical sample-to-sample change near the beat (outside the QRS) relative to `amplitudeUv`; electrode/motion noise raises it; clean beats are mostly below 0.03, noisy ones above 0.04 |
| `hfRatio` | RMS of the >40 Hz signal near the beat relative to `amplitudeUv` (muscle noise); clean beats below 0.03 |
| `agreed` | 1 if the second, differently built detector also saw the beat |
| `motionMg` | accelerometer motion in the second around the beat (highest of the 1 s bins within ±0.5 s), mG; empty without ACC data |

`sqi` = amplitude score × slope score × hf score × motion score × (1 if `agreed`, else 0.4). Amplitude score: 0 for a
flat line (< 20 µV), a tiny beat (< 15 % of the median of the last 20 beats) or a spike (> 6×), rising to
1 at 50 %. Slope score: 1 at `slopeRatio` ≤ 0.02, 0 at ≥ 0.07. hf score: 1 at ≤ 0.03, 0 at ≥ 0.10. Motion score: 1 at ≤ 100 mG, 0 at ≥ 600 mG (1 without ACC data).
All thresholds are in `QualityConfig` (`dsp/quality/EcgBeatStream.kt`). Beats are scored 0.4 s
after they happen (the noise after the beat counts too). Comparison with a normal-beat template is
added with P4.2.

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
| `type` | `GAP` = no signal between two segments. Premature-beat events arrive with P4 |
| `durationMs` | length |
| `detail` | free text |
