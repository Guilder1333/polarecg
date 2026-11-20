# Reading beats on the H10 ECG: PVC, PAC or noise?

A practical guide for looking at single beats in the app (beat window, `polarecg://beat/<utcMs>`) or in
`analysis/view_ecg.py`. Written from the questions that came up while reviewing real nights.

> This explains how to read the *signal*. It is not medical advice. A single chest lead at 130 Hz
> cannot settle every case; a doctor with a 12-lead ECG or a Holter can.

## 1. What the beats are

| Name | Where it starts | What it looks like on the ECG | App class |
|---|---|---|---|
| **Normal (sinus) beat** | Sinus node, at the top of the atria | Small P wave, then a narrow QRS spike (≈ 60–100 ms), then a smooth T wave. Same shape every beat, regular rhythm | `N` |
| **PAC** (premature atrial contraction) | Somewhere else in the atria | **Early**, but the QRS usually looks **normal and narrow** (it travels down the normal wiring). P wave may be hidden or odd-shaped | `S` |
| **PVC** (premature ventricular contraction) | In a ventricle | **Early**, QRS **wide** (> ≈ 120 ms) and **different** from your normal beats, no P wave before it, big **T wave pointing the opposite way** to the main QRS deflection | `V` |
| **Noise / artifact** | Not the heart: muscle, electrode contact, movement | Anything; often fuzzy/jagged, does not disturb the rhythm | `Q` |

## 2. The rhythm tells you more than the shape

**Always time the beats first ("march them out").** Take two clean normal beats, measure their RR interval,
and step that interval forward. Then ask of each suspicious beat:

1. **Is it early?** A PAC or PVC comes *before* the next normal beat was due. A beat that lands exactly where the
   next sinus beat was due **is a sinus beat**, whatever its shape looks like on a messy baseline.
2. **What happens after it? (the pause)**
   - **Compensatory pause → typical of a PVC.** The sinus node keeps ticking undisturbed; the sinus beat that
     would have come is blocked, so the next one comes on the *old* schedule.
     Check: `coupling + pause ≈ 2 × normal RR`.
   - **Non-compensatory pause → typical of a PAC.** The early atrial beat resets the sinus node, so the rhythm
     restarts from the PAC. Check: `coupling + pause < 2 × normal RR`.
   - **No pause at all (interpolated PVC).** At slow heart rates a PVC can squeeze between two sinus beats
     without disturbing them. Even then, the next sinus QRS comes only **after the PVC's T wave has finished**,
     often with a slightly longer PR.

   The pause is a strong hint, not proof: there are exceptions both ways.

## 3. The refractory rule (the most useful test against noise)

After a real ventricular depolarisation (any QRS, normal or PVC) the ventricles **cannot fire again** for roughly
250–300 ms and not during that beat's T wave (the ST–T segment is the refractory period).

So:

- If a **normal-looking narrow spike** appears inside, or within ~250–300 ms after, something you think is a wide PVC,
  the "PVC" is **not cardiac**. It is noise.
- If a "complex" sits 150–200 ms before a perfectly normal QRS, it cannot be ventricular either (the normal QRS
  could not follow so closely).

## 4. Signs of artifact

- **Fuzzy, jagged texture** (many small spikes) on top of the wave. Heart signals are smooth at this scale;
  muscle activity (EMG) is fuzzy.
- **Straight ramps and sudden steps** in the baseline (an electrode recovering), not a QRS-then-T sequence.
- **No T wave afterwards.** Every real ventricular beat leaves a T wave behind.
- **The normal spikes keep marching through the mess** at the regular rhythm.
- **The same odd shape appears where it cannot be cardiac** (e.g. right before a normal QRS).

**A still accelerometer does not rule out artifact.** Tensing chest muscles, a deep breath or sigh, swallowing,
or a change in electrode contact (strap pressure, sweat) all make noise without any body movement.

## 5. Checklist for one beat

1. March out the rhythm. Early or on time?
2. If on time → almost certainly **sinus (N)**, even if the shape looks odd.
3. If early → look at the QRS: narrow and like your normal beats → **PAC (S)** likely; wide and different → **PVC (V)** likely.
4. Check the pause: compensatory (PVC-like) or reset (PAC-like)?
5. Look for a T wave of opposite polarity after a suspected PVC.
6. Apply the refractory rule: any normal spike too close to the "PVC"? → noise.
7. Fuzzy, ramps, steps, no T wave → **noise (Q)**.
8. Cannot decide → it is fine to leave it undecided. One lead at 130 Hz has limits.

## 6. How the app's labels can mislead

- **A false detection poisons the next beat.** If noise is detected as a beat (`Q`), the next *real* beat gets a
  short `rrMs` and looks premature, so it can be called `V` or `S`. Measure prematurity from the last good beat.
- **`vDoubt = 1`** means a V resting only on the drift-sensitive shape rules while the baseline drifts
  ("questionable V"). Treat those with extra suspicion.
- **Low `corr`** (shape match to your normal template) can come from a drifting baseline, not a different beat.
- **`qrsWidthMs`** at 130 Hz is coarse (one sample ≈ 7.7 ms).

## 7. Worked example: session 20261116T140043Z, 2026-11-17 06:44:39–42 JST

Context: lying perfectly still (accelerometer flat apart from the heartbeat bump); sinus rate had just slowed
from ≈ 105 to ≈ 50 bpm in about 10 s.

| Time (JST) | App | Reading |
|---|---|---|
| 06:44:39.964 | N | Sinus beat; a jagged +600 µV step 160 ms before it is noise (refractory rule: no ventricular event can sit that close before a normal QRS) |
| 06:44:40.894 | Q, 238 ms, sqi 0.18 | **Noise**: broad dip to −470 µV and a quick swing; a normal narrow spike follows only ≈ 220 ms later |
| 06:44:41.117 | **V**, RR 223 ms | **Sinus beat**: 1153 ms after the previous sinus beat, exactly on schedule (1214 → 1204 → 1153 → 1111 → 1079 ms), normal narrow spike. Called V only because of the false Q before it |
| 06:44:41.7–42.2 | (no beat) | **Noise**: dip, straight slow ramp, vertical +530 µV step with fuzzy plateau, no T wave after; the same step shape as at 39.80. The H10's own RR stream also saw no beat |
| 06:44:42.228 | N | Sinus beat, on time |

Fuzziness measured between beats: ≈ 3–9 in clean stretches, 22–42 inside this burst.

Same night, the app's 50 most confident `V` beats: early (≈ 500 ms) but the pause after them is **not**
compensatory (average `(coupling + pause) / (2 × previous RR)` = 0.88; only 1 of 50 ≥ 0.95), which is more
typical of PACs than PVCs. Not proof; to be rechecked with the learned classifier (plan P7.1).

### What the first learned model said about this night (2026-11-18)

- 06:44:40.894 → noise (98 %), as read above. 06:44:49.408 (app V) → normal.
- 06:44:41.117 → noise (95 %): **the model is wrong here**. It never saw a real beat right after a false
  detection; to be fixed in the next model.
- The app's 50 most confident V beats are **one family**: early, a small upward then a deep downward deflection
  (the opposite way round to the normal beats), a larger T wave after, and a pause that resets the rhythm.
  A shape so different from normal points to the ventricles (PVC) *or* to a PAC conducted abnormally ("aberrant");
  the reset pause points to a PAC, but a PVC that conducts backwards into the atria can also reset them.
  The model itself is undecided (on average 57 % S, 39 % V). **This one beat type is exactly what to show a
  doctor**: one clear strip of it (with normal beats either side) is often enough for a cardiologist to say
  what it is, even without a Holter.

### The family is not one beat type: owner's reading of three of them (2026-11-20)

The N/V/X model (`20261120-185445`, no S class: an early "N" is a PAC) split the family N 32 / V 20. Three
of them, looked at on the phone:

| beat (JST) | link | timing: early · pause after · sum | model | owner |
|---|---|---|---|---|
| 2026-11-17 00:25:09.115 | `polarecg://beat/1794842709115` | 0.56 · 1.06 · 1.62 (resets) | N 0.97 → PAC | **S** |
| 2026-11-17 03:48:13.271 | `polarecg://beat/1794854893271` | 0.63 · 1.38 · 2.01 (compensatory) | V 0.94 | **V** |
| 2026-11-17 02:07:26.446 | `polarecg://beat/1794848846446` | 0.54 · 1.21 · 1.74 (in between) | N 0.49 / V 0.47 | **V**, looks the same as the one above |

What this shows:

- **PACs and PVCs both occur in this family,** and the pause after them tells them apart better than the shape
  of the early beat on this lead: the S resets the rhythm (sum 1.62), the clear V is fully compensatory (2.01).
- **A PVC's pause is not always fully compensatory.** The third beat is a V with a sum of 1.74: a pause can
  come out shorter when the sinus rhythm speeds up a little, or when the PVC conducts back into the atria.
  So "pause in between" means: decide by the shape.
- Its QRS matches the clear V almost exactly (correlation 0.97). The model was unsure because the beat is
  **smaller than the normal beats around it** (the clear V is as tall as them): in the training databases
  PVCs are usually taller than normal beats, so a small flipped PVC on the H10 chest lead is new to the model.
  Swapping in the clear V's timing only moved it to V 0.59. On-time, the same shape is called normal (0.96).

## 8. Where real ground truth could come from

- **Artifact test recording:** lie still, awake, and do one thing at a time with written clock times
  (tense chest, deep breath / sigh, swallow, hold breath, press the strap, cough). This shows what *your* noise
  looks like with a flat accelerometer.
- **H10 worn during a clinical Holter:** the cardiologist's reading of the Holter gives trustworthy labels for the
  same moments in the H10 data.
- **Expert-labelled public databases** (MIT-BIH, INCART, svdb): what the learned classifier is trained on
  (plan P7.1); good for "what does a PVC usually look like", not for your exact lead.
