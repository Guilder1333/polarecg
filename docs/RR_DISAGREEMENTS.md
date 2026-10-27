# Where our RR intervals differ from the strap's

Notes from P3.6 (2026-10-26/27). Background for fixing the R-peak position before Phase 4, and for
HRV in P5.5. Tool: `analysis/compare_rr.py`; see "How to reproduce" at the end.

## The comparison in one paragraph

The H10 sends its own RR intervals (1/1024 s resolution) next to the ECG. P3.6 pairs them with ours
(`compare_rr.py` matches the two sequences in order, using arrival times as a guide) and looks at
`ours - strap`. On a night (6.6 h, 25 347 good pairs) and a day (3 h, 15 614 good pairs) the median
absolute difference is **0 ms**, the 99th percentile 1–2 ms and 99.6–99.9 % of good pairs agree within
5 ms. So the timing is sound. The rest of this note is about the few percent of a percent that do not.

| Session | Good pairs | Differ by > 10 ms | Of those, one beat marked at another point of the QRS |
|---|---|---|---|
| Day, 3.0 h | 15 614 | 10 (0.06 %) | 4 (+ a burst of 5 more at 13:18:33–13:18:40, same pattern) |
| Night, 6.6 h | 25 347 | 102 (0.40 %) | 91 |

## Before this: a time-axis bug that is already fixed

The first version of the comparison showed a median difference of 13 ms and strap arrival times that
drifted 13 s away from our beat times. Cause: the H10 samples at **130.27 Hz**, not 130 Hz (measured from
its frame timestamps, identical in both sessions), and we placed samples on the time axis by counting.
Segment time now follows the sensor clock frame by frame (commit `e4eff06`). Everything below is *after*
that fix.

## The main cause: the detector marks R in one beat and S in the next

### What the pattern looks like

A beat that is marked x ms away from where the strap puts it makes the interval ending at that beat
x ms too long and the next one x ms too short: a pair `+x, -x`. In the data x is always the same:

- day: 24–25 ms = 3 samples
- night: 28–30 ms = 3.5–4 samples

A fixed jump of a few samples is not noise (noise would give a spread of values). It is a different
*point of the QRS complex*.

### What the waveform shows

Offsets are samples from the position we reported, values are µV above the local median.

Day, 13:18:19 (QRS = upward R then deeper S). Normal beat before it, marked at its R peak:
`... 92 344 [610] 416 -327 -992 -857 -284 ...`. The displaced beat, marked at 0:
`... 173 410 403 -220 -1007 [-1041] -405 -12 ...`: here R is only 410 and **we marked the S trough**
(-1041), 3 samples after where the R peak would have been.

Night, 20:23:20: normal beat `... 33 144 [238] 217 40 -182 -256 ...` (R 238, S -256: almost equal); the
displaced beat is marked on the S trough (-284) with the R (+314) four samples earlier.

So the QRS in this lead is **biphasic (R and S of similar size)**. Which of the two extremes is larger
changes from beat to beat with breathing, posture and electrode contact, and our rule is sensitive to
it.

### Why our rule flips

`QrsDetector.localizeR` (the R-peak position after the beat is detected):

1. takes the window before the detection (integration window + 6 samples),
2. subtracts a straight line between the two ends of the window,
3. finds the largest upward and the largest downward deviation,
4. uses the *learned polarity* (a decaying vote of recent beats) and picks that direction, **unless the
   opposite one is more than `polarityOverride` (2.0) times bigger**.

Two things go wrong:

- Day example: S = 1041, R = 410, ratio 2.5 > 2.0, so the rule switches to the S trough although every
  neighbouring beat used R.
- Night example: the window ends sit on the T wave of the previous beat and on the next beat's P wave
  (values 288 on the left, -100 on the right), so "the straight line" is a poor baseline and the
  deviations (and so the ratio) are distorted.

### Why it happens more around premature beats

At night 16 % of the displaced beats sit next to a clearly premature beat (strap RR < 0.85 or > 1.15 of
its local median), against 0.9 % of beats in general: about 18 times more often. A premature beat has a
different shape, so R and S are even more likely to swap places. (It also means: when we classify
premature beats in P4, the R-peak position of exactly those beats matters.)

### Why the strap does not have it

Presumably it marks one consistent feature of each QRS. That also means our *RR values are not wrong
because of noise*, they are wrong because we sometimes follow a different feature than we did one beat
earlier.

### Evidence that this is the cause

Experiment (reverted): set `polarityOverride = 100`, i.e. never switch to the opposite direction.

| | Good pairs differing > 10 ms |  | Std of difference |
|---|---|---|---|
| Day | 10 -> **1** | | 1.0 -> 0.8 ms |
| Night | 102 -> **12** | | 2.1 -> 1.2 ms |

The remaining ones are the other two causes below.

### Why `polarityOverride = 100` is not the fix

With the switch disabled the polarity memory feeds itself: the chosen direction reinforces the vote, so it
can never change back. If the QRS polarity truly changes (posture, electrode shift) the detector would
stay on the old direction. The value 2.0 was chosen so that this adaptation works (the upside-down test
in `QrsDetectorTest` covers a recording that starts upside down).

## Other causes (about a tenth of the large differences)

### Disturbed moments

At night mostly 16:23:17-16:23:23, when the strap was being adjusted (30 mV artifacts, normal beat 0.8 mV):
differences of 12–44 ms and neighbouring intervals that could not be paired. Expected, and flagged by the
quality score (amplitude, slope noise).

### Intervals without a partner (night 276 = 1.1 %, day 35 = 0.2 %)

Night: 99 of our intervals are much shorter than the local median (an extra detection: noise, or a doubled
beat), 117 look normal (a neighbouring beat on either side was not paired, so this one stays alone), 0 are
much longer. **We never miss a beat that the strap sees**, which is the important direction. 113 of the 276
(day: 22 of 35) were already marked poor quality by the quality index.

## Why this matters

- **HRV.** RMSSD works on differences between successive intervals. A `+25, -25` pair is two successive
  differences of ±25 ms, far larger than normal beat-to-beat variation at rest (a few ms to 20-30 ms in
  total). A handful per hour is not a problem for the 5-minute RMSSD, but a 20-second burst like day
  13:18:19-13:18:40 (about every other beat) is.
- **Premature-beat detection.** The prematurity and the compensatory pause (plan §7.5) are computed from
  RR intervals; a 25 ms error is small against a 300 ms premature beat, but the beats most often affected
  are premature ones.
- **Not a detection problem.** Counts of beats agree (mean difference ~0 per 10 s window); only the
  position within the QRS is unstable.

## Options for fixing it (written before trying them; results in the next section)

Goal: the reported position of a beat is the *same feature* in every beat, while still adapting if the
QRS polarity changes for good.

1. **Better baseline.** Measure R and S against the median of the window (or of the signal around the
   QRS) instead of a line between the window ends, so T and P waves of neighbours do not distort the
   deviations.
2. **Hysteresis.** Switch direction only when the opposite extreme is clearly bigger *and* has been in
   several consecutive beats (or raise `polarityOverride` to 4-5 and keep the decaying vote).
3. **A polarity-independent point.** Mark the steepest slope of the QRS (the maximum of |derivative| in the
   band-passed signal, or the centre of the energy of the squared derivative). It exists in R, S and RS
   complexes alike and does not flip. Costs: the point is not the R peak, so the position is a few samples
   from where the strap and the viewer show the peak, and a PVC's wide QRS has several steep edges.
4. **Keep the choice, smooth it.** Take the extreme of the learned polarity if it is at least 50 % of the
   opposite one.

How to decide: for each candidate run `analyze` on both sessions and `compare_rr.py`, and compare (a) the
number of good pairs differing by more than 10 ms (now 10 and 102), (b) the spread of the difference
(now 1.0 ms day / 2.1 ms night), (c) MIT-BIH sensitivity/+P (now 99.6 % / 99.3 %), and (d) that the
upside-down and polarity-change tests in `QrsDetectorTest` still pass. A new test should use a synthetic
biphasic beat with R and S of varying ratio.

## What was tried (P3.8, 2026-10-27) and what was kept

Each variant was run on both sessions and all MIT-BIH records. "MIT-BIH jumps" = how often our position
relative to the reference annotation changes by > 10 ms from one beat to the next (Se/+P cannot show
position changes, it matches within ±150 ms; it stayed at 99.6 / 99.3 % for every variant).

| Variant | Day > 10 ms | Night > 10 ms | MIT-BIH jumps |
|---|---|---|---|
| Before (`polarityOverride` 2) | 10 | 102 (0.40 %) | 4.0 % |
| 1: baseline = median of the signal around the QRS | 23 | 529 | 4.1 % |
| 2: vote counts the bigger extreme, flip past a margin of 3 | 64 | 13 | 6.9 % |
| 2, vote weighted by the R/S ratio | 548 | 13 | 6.9 % |
| 2, vote counts only clear beats (ratio > 2) | 2788 | 13 | 6.7 % |
| **`polarityOverride` 4 (kept)** | **1** | **54 (0.21 %)** | **4.0 %** |
| `polarityOverride` 8 | 1 | 27 (0.11 %) | 6.3 % |
| `polarityOverride` 20 | 1 | 14 (0.055 %) | 6.5 % |
| `polarityOverride` 100 (never switch) | 1 | 12 (0.047 %) | 8.7 % |

What this showed:

- **The line baseline is not the problem**: the median baseline makes flips five times as frequent.
- **A vote on "which extreme is bigger" fails on the day lead.** R is never clearly bigger than S there, so the
  vote drifts, or settles on S. The S trough is a poor fiducial: it moves against the strap's beat time
  (std 10 ms vs 1 ms on R). The old self-reinforcing vote is actually what keeps the detector on R.
- **The trade-off is between the night and inverted ventricular beats.** At night 68 of the 102 large
  differences are one stretch around 20:00, and only a high override removes them. On MIT-BIH, records with
  many inverted ventricular beats (200, 233) are annotated on the big extreme; a high override marks those
  beats on the small one, 25-40 ms away. Above ~20 a strap that is turned around is no longer followed.
- **Kept: `polarityOverride = 4`.** Day fixed, night halved, MIT-BIH unchanged, a truly inverted QRS (ratio far
  above 4) still switches. Tests: `biphasicQrsIsMarkedOnTheSameFeatureEveryBeat` (S 0.8-3.2 times R; fails
  with 2, every deep beat 4 samples late) and `followsAPolarityChangeDuringTheRecording`.

Still open: the P3.8 target (< 0.05 % at night) is not reached with the kept setting. Step 2 (below) looked at
the 20:00 stretch and found a fix that reaches it, which was **not adopted for now**.

## Step 2 (2026-10-27): the 20:00 stretch and the steepest-slope experiment

### The 20:00 stretch

Of the 54 night differences > 10 ms that remain with `polarityOverride` 4, 22 fall in 20:30-20:39 and most of
the rest in 20:2x-20:5x (counts by 10 minutes: 20:3x 22, 20:2x 6, 21:3x 5, 20:5x 4, 22:4x 4, 20:4x 3, the rest
1-4 each). View: `view_ecg.py data/sessions/20261021T162147Z --at 20:30:30 --width 8`.

What the signal looks like there:

- **Large baseline wander**, about ±1500 µV swings over 1-2 s (breathing or posture), against QRS complexes of
  only 300-600 µV. The QRS often sits on a steep slope of the baseline.
- The beats are the same biphasic R-then-S shape as before. In the displaced beats the **R is small next to the
  S** (e.g. R +228, S -361...-515, µV above the local median, R 4 samples before the S trough), because the
  falling baseline lifts the left end of the window and sinks the right end. After subtracting the straight
  line between the window ends, S is about 4 times (or more) bigger than R, which is at the `polarityOverride`
  limit, so the detector marks the S trough, 3-4 samples (28-30 ms) after the R the neighbouring beats use.
- Normal beats next to them: R +186...+389, S -220...-316. The pair pattern is the same `+x, -x` as in the
  main section, with x = 28-30 ms.
- So raising the override further (8 -> 27, 20 -> 14, 100 -> 12) removes them, but costs inverted ventricular
  beats in MIT-BIH (see the table above). The cause is not the baseline *line*; it is that "which extreme is
  bigger" is not a stable feature in this lead.

### Experiment: mark the steepest slope (option 3)

`QrsConfig.steepestSlope` (default **off**; `QrsDetector.localizeR`): in the window used for R, take
the sample where `|x[j+1] - x[j-1]|` is largest, with the parabolic sub-sample refinement applied to the
slope. For an R-S complex that is the down-stroke between the peak and the trough; it lies between the two
extremes whether R or S is the bigger one, so it does not flip.

| | Day > 10 ms | Night > 10 ms | Std of difference (good pairs) | MIT-BIH Se / +P | TPbad |
|---|---|---|---|---|---|
| `polarityOverride` 4 (kept) | 1 | 54 (0.21 %) | 0.8 ms day / 1.6 ms night | 99.6 / 99.3 | 2.6 % |
| `steepestSlope` on | 1 of 15 620 | **11 of 25 350 (0.043 %)** | 0.8 ms day / 1.1 ms night | 99.6 / 99.3 | 2.9 % |

Night "good pairs within 5 ms" 99.8 % -> 99.9 %. The "MIT-BIH jumps" figure was not re-measured in this step.

Why it was not adopted:

- The marked point is **about 2 samples (15 ms) after the R peak**, so "R position" would no longer mean the R
  peak. The viewer, the sub-sample accuracy test (P3.3) and, later, template matching in P4 all assume the R
  peak.
- Three unit tests fail with it on, because they compare with the true R peak: `QrsDetectorTest`
  `followsAPolarityChangeDuringTheRecording` (31 differences instead of 0),
  `biphasicQrsIsMarkedOnTheSameFeatureEveryBeat` (the offsets printed are consistent, about 2.0 samples, but
  the test expects 0) and `SubSampleTimingTest.refinedPositionsBeatWholeSampleRounding` (12.8 ms error).
- The RR values themselves are fine either way (a constant offset cancels in an interval).

### Decision and how to pick this up

Decision (owner): accept 0.21 % at night for now. P3.8 stays open; the numbers above are the starting point.
Options for later, in the order I would try them:

1. **Switch the fiducial to the steepest slope** (turn the option on, rewrite the three tests to check
   consistency and a fixed offset instead of the R peak, update plan §8). Simplest, reaches the target.
2. **Use the steepest slope only to choose between R and S**, then still report the R peak (the extreme within
   a few samples before the steepest down-stroke). Keeps the viewer and tests, but is a second rule that
   could itself flip. Not tried.
3. **Align beats to a running template (P4.2)**: the fiducial is then fixed by construction. Probably the
   right long-term answer; revisit when the template exists.

Whichever is chosen, check against the same four criteria as before (day and night > 10 ms counts, MIT-BIH
Se/+P and jumps, `QrsDetectorTest` polarity tests).

## How to reproduce

```powershell
./gradlew :tools:cli:installDist
tools/cli/build/install/cli/bin/cli.bat analyze data/sessions/20261026T110150Z
python analysis/compare_rr.py data/sessions/20261026T110150Z --csv data/analysis/rr_day.csv --worst 15
python analysis/view_ecg.py data/sessions/20261026T110150Z --at 13:18:19 --width 20
```

`--worst` lists the largest disagreements with their time; `view_ecg.py --at` opens the ECG there. The
night session is `20261021T162147Z` (e.g. `--at 20:23:20`). The CSV has one row per interval of ours with
`strapRr` and `diff`.
