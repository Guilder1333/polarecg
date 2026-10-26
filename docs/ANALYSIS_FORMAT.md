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
> the R peak in the original signal (integer sample, sub-sample refinement arrives with P3.3).
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

Signal-quality columns are added with P3.4.

## `events.csv`

| Column | Meaning |
|---|---|
| `utcMs`, `utc` | start of the event |
| `type` | `GAP` = no signal between two segments. Premature-beat events arrive with P4 |
| `durationMs` | length |
| `detail` | free text |
