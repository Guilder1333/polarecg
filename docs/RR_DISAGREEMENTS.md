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

## Options for fixing it (for tomorrow)

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
