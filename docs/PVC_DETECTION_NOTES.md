# PVC detection: rules vs. neural network

Notes from a design discussion (2026-10-13). Background for Phase 4 (rule-based classifier) and
Phase 7 (optional learned classifier) in `DEVELOPMENT_PLAN.md`; see plan §7.5–7.6 for the actual
features and rules.

## Question

Instead of training a 1D neural network, can PVCs be detected mathematically from timing alone:
three beats where the middle one is much closer to the previous beat than to the next
(`RRpre ≪ RRpost`, "short–long")?

## Answer in short

Yes for the approach (rules first, no NN), but **not with timing alone**. Use the short–long
pattern to find *candidate* beats, and the beat's **shape** (morphology) to decide what each
candidate really is. That is what plan §7.5–7.6 already describes.

## Where timing-only breaks

1. **QRS detector errors look identical to ectopy.** A T-wave or motion spike counted as a beat
   gives short–long. A missed small beat gives long, then normal. These are the most common
   chest-strap errors, so timing alone reports a fake PVC burden on noisy nights.
2. **PVC vs PAC.** Both come early. A full compensatory pause (`RRpre + RRpost ≈ 2·RRref`) points
   to PVC and a shorter one to PAC, but there are many exceptions. Shape is the reliable
   difference: a PVC has a wide, odd-looking QRS.
3. **Sinus arrhythmia (normal breathing variation).** At rest and in sleep, especially in fit
   people, RR swings 15–25 % with each breath. Comparing only to the two neighbours gives false
   positives. Compare against a running reference instead: `RRref` = median of the last ~8–10
   *normal* RR intervals.
4. **PVCs that timing misses:**
   - *Interpolated* PVCs sit between two normal beats with no pause (common at slow heart rates).
   - *Late-coupled* PVCs come only slightly early.
5. **Bigeminy and atrial fibrillation.** In N-V-N-V rhythm the reference gets polluted unless only
   normal beats update it. In AF every beat is irregular, so a timing rule fires constantly.

## Existing algorithms worth borrowing

| Approach | What it is | Why it's relevant |
|---|---|---|
| **Lipponen & Tarvainen 2019**, "A robust algorithm for heart rate variability time series artefact correction using novel beat classification" (the Kubios method) | RR-only beat classification (ectopic, missed, extra, long/short) using successive RR differences with adaptive thresholds. Open implementations: NeuroKit2 `signal_fixpeaks(method="kubios")`, `systole` (Python). | It's the short–long idea done carefully. Use as the timing stage and to clean RRs before HRV. |
| **Tsipouras et al. 2005**, "Arrhythmia classification using the RR-interval duration signal" | Rules over a sliding 3-RR window detecting PVC, couplets, bigeminy, VT. | Very close to the 3-beat rule, already worked out. |
| **OSEA** — Open Source ECG Analysis (Patrick Hamilton / EP Limited) | Open-source C: QRS detection + beat classification via template matching, RR timing, QRS width, noise checks. | Essentially Phase 4 already written. Reference for porting ideas; **check the license before copying code**. |
| **de Chazal et al. 2004** | Linear discriminant on RR + shape features; standard inter-patient evaluation on AAMI classes N/S/V/F/Q. | Shows simple features get most of the way; defines how to evaluate fairly. |
| Holter-style **beat clustering** | Group beats by shape; label each cluster once. | Single user → one personal template works very well, better than a network trained on hospital data. |

## Recommended pipeline (matches plan §7.5–7.6)

1. **Quality gate:** ACC motion + high-frequency noise ratio. Failing beats → `Q`, never `V`.
2. **Timing stage:**
   - `prematurity = RRpre / RRref`
   - `compensation = (RRpre + RRpost) / (2·RRref)`
3. **Shape stage:** correlation with the running normal template, coarse QRS width, polarity flip.
   Decides V vs S and rejects artefacts that passed the timing test.
4. Only clean `N` beats update the template and `RRref`; re-learn the template if many consecutive
   beats look "abnormal" (posture change), instead of reporting a V-storm.

## Why not a neural network (yet)

Public training data (MIT-BIH: hospital leads, 360 Hz) transfers poorly to a Polar H10 chest lead
at 130 Hz. A CNN only becomes worth it (Phase 7) if the rules plateau on **our own labelled data**.

## How to validate

- Run the classifier via `:tools:cli` on the **MIT-BIH Arrhythmia Database** and **INCART**
  (PhysioNet), resampled to 130 Hz. Measure sensitivity and positive predictive value
  (how many reported PVCs are real) for V beats.
- Hand-label a few of our own nights and measure the same.
- Compare timing-only vs timing+shape on both — expected to show timing-only fails mainly
  because of detector errors (point 1 above).
