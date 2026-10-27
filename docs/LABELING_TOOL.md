# Labeling tool for our own beats

Plan for the tool that gives us **ground truth on the owner's own ECG** (tasks P4.5/P4.6 in
`DEVELOPMENT_PLAN.md`). Status: **built** (L1-L6 done, 2026-10-27); not yet used on a real sitting.

## Why

- The classifier (P4.3) was tuned on MIT-BIH, whose beats were labelled by cardiologists. How well it
  works on the owner's signal (one lead, chest strap, 130 Hz) is unknown.
- The tool the owner used before cannot export anything useful and has many false positives, so there is no
  reference to compare with. The only trustworthy reference is **labelling our own beats**.
- The labels are also the training data for the neural-network phase (P7).
- The app's review screen (P5.7) does not exist yet; this is a small local Python tool until it does.

## Who labels, and what a label means

The owner labels; they are not a doctor. So a label means **"this is what the beat looks like"** (shape and
timing), not a diagnosis. That is exactly what our rules can see, so it is the right question to ask.
Unsure is always allowed (`?`), and a skipped beat is better than a guess. Medical meaning of any count is for
the owner's cardiologist.

### Labels (keys)

| Key | Label | Meaning (easy version) |
|---|---|---|
| `n` | **N** normal | Thin sharp spike, same shape as its neighbours, on time. |
| `s` | **S** early, normal shape | Comes **early**, but **looks like its neighbours** (same thin spike). Starts in the upper chambers and uses the normal fast wiring. |
| `v` | **V** early/odd, ventricular | **Looks different**: wider, often taller, strange shape, the next wave often points the opposite way, no small bump (P wave) before it. Usually followed by a longer pause (before + after = about two normal intervals). |
| `q` | **Q** can't tell | Noise, motion, a broken-looking signal. |
| `b` | **B** blocked early beat (optional) | A small **early bump with no QRS after it**, then a pause. Often sits on the T wave of the previous beat. Only offered for *gap* items (below). |
| `?` | unsure | Skip, don't guess. |

Three questions for every strip: **1. Is it early?** **2. Does it look like its neighbours?** (same = S,
clearly different = V) **3. What happens after?** (long pause points to V, short one to S; the weakest clue).

Honest limits (also shown in the tool's help): at 130 Hz the P wave is hard to see; some S beats look wide and
V-like (doctors disagree too); a wearer with a bundle-branch block has wide normal beats; noise mimics
anything (use `q`).

## Two modes

### 1. Gallery (training the eye, ~10 minutes)

`python analysis/label_beats.py --gallery`

- Shows MIT-BIH beats drawn exactly like the owner's strips, picked from several records, balanced between
  N / S / V (and a few hard ones). Cardiologist labels are hidden.
- The owner presses a key; the tool then reveals the real label and keeps a running score and a confusion
  table (for example "S taken for V: 4 times"), so it is clear what to look at more closely.
- Nothing from the gallery goes into the owner's labels.

### 2. Labeling own recordings

`python analysis/label_beats.py data/sessions/<session>`

Needs the output of `:tools:cli analyze` for that session. Opens a queue of items, one at a time.

## What is shown

- About **6 s of ECG** centred on the item, with the item highlighted, the neighbouring beats marked only by a
  tick (**not coloured by our class**: labelling is blind by default so our guess does not steer the label),
  time axis in UTC, and the RR intervals around it in ms.
- Optionally (key `t`) the normal-beat template laid over the item for comparison.
- Key `g` reveals our class (and features) for the current item; always shown in review mode.
- Item types:
  - **BEAT item**: centred on one detected beat. Answer `n s v q ?`.
  - **GAP item**: centred on an interval that is long for its context (RR at least 1.35 times the reference,
    both neighbours good). The question is "is there a blocked early beat in this gap?": `b` yes, `n` no,
    `?` unsure. Optional: click on the bump to store its time.

Keys: `n s v q b ?` label and go to the next item; `←`/`→` previous/next (relabelling allowed); `u` undo the
last label; `t` template overlay; `g` reveal our class; `h` help (the checklist above); `Esc` save and quit.

## Which items are in the queue (sampling)

Labelling everything is impossible (25 000 beats a night), and most are N. The queue is built once per
session from `beats.csv` with a fixed random seed and written to `data/labels/<session>/queue.csv`:

| Group | How many | Why |
|---|---|---|
| beats we call **V** | all, up to 150 | few, and false V matter most |
| beats we call **S** | 150 random + all in S patterns | S is our weakest class |
| beats we call **N** | 300 random, spread over the hours | finds the V/S we miss |
| beats we call **Q** | 50 random | is Q really noise? |
| **GAP items** | up to 100, random | blocked early beats |
| first/last beat of each pause, couplet, run | all | patterns are what is reported |

About 600-700 items; at 3-5 s each that is **30-45 minutes**, and it can be split over several sittings (the
queue remembers where it stopped). Items are shown in random order, so the owner cannot guess the group.
Each queue row stores its group and sampling weight (group size / number taken) so that the results can be
corrected for the sampling.

## Files

Everything lives under `data/labels/` which is git-ignored (health data, never committed; see CLAUDE.md).

- `data/labels/<session>/queue.csv`: `item, kind (BEAT|GAP), utcMs, group, weight, ourClass`.
- `data/labels/<session>/labels.csv`, **append-only**: `item, utcMs, kind, label, labeledAtUtc, note,
  bumpUtcMs`. Relabelling or undo appends a new row; the **last row per item wins**. Nothing is ever deleted
  or overwritten (same rule as for the raw recordings).
- `data/labels/gallery.csv`: gallery answers, for the owner's own interest.

## What the labels are used for

- `analysis/eval_labels.py` (to build): per session and pooled, a confusion table our class against the owner's
  label, and Se / +P for V and S **weighted by the sampling weights**, with unsure and Q items reported
  separately. Also lists the disagreements with times, to look at together and decide whether the rule or the
  label is wrong. This is P4.5 for own data.
- P4.6: the labels are the set of "a few hundred V/S beats and their neighbours" for tuning thresholds.
- P7: training and held-out test data for the neural-network classifier.
- `B` labels decide whether a blocked-beat detector (`S_BLOCKED` event) is worth building and give examples to
  design it against. Until then they are only recorded.

## Build tasks

- [x] **L1** `analysis/label_beats.py` skeleton: window with the ECG, item list, keys, autosave, resume
  (matplotlib, same stack as `view_ecg.py`; reuse its data loading).
- [x] **L2** Queue builder from `beats.csv` and `events.csv` (groups, seed, weights), `queue.csv`.
- [x] **L3** Gap items and the `b` label (and the optional click on the bump).
- [x] **L4** Gallery mode on MIT-BIH, with reveal, running score and confusion table.
- [x] **L5** `analysis/eval_labels.py` and tests (queue building, append-only labels, weighting).
- [x] **L6** Help overlay with the checklist and the honest limits; a short section in `analysis/README.md`.

Code: `analysis/label_data.py` (queue, labels, gallery picks), `label_beats.py` (window), `eval_labels.py`;
tests `test_label_data.py`, `test_label_beats.py` (window driven by simulated key presses).

L2, L3 and L5 are plain data code and are unit-tested on the PC; L1/L4/L6 are the interactive part.

## Not goals

- Not a medical tool and not a diagnosis. No cloud, no sharing of recordings.
- Not the final review screen of the app (P5.7); labels are kept in a simple CSV that the app can import later.
- No automatic labelling from our own classifier ("accept all"), it would only confirm what we already do.

## Open questions for the owner

- Which sessions first: the first night (`20261021T162147Z`, the most beats and 71 V) is the natural start.
- Is it acceptable to label in several short sittings (the queue is saved after every label)?
- Does the owner want to see the template overlay by default, or only on request (default: on request)?

## Built as designed, with these details

- The queue is built on the first start and **never rebuilt or overwritten** (labels refer to it, and the
  weights must stay consistent). To start over, move the folder away by hand.
- Strata do not overlap: the first and last beat of every pause/pattern form their own group `PATTERN`
  (weight 1), the other beats are split by our class. So the weights of the beat items add up to the number of
  beats of the night.
- Item ids: `B<utcMs>` (beat), `G<utcMs>` (middle of a long interval). Undo appends a row with an empty label.
- Gallery: 20 beats each of N, S, V (`--per-class`), at most 4 per class from one record, answers in
  `data/labels/gallery.csv`. By default only **clear examples**: S = atrial
  premature beat that is early (< 0.85 of the usual interval) in regular rhythm, N = on time (0.9-1.1) in regular
  rhythm, V = any. Reason (measured): only 32 % of MIT-BIH's S beats are visibly early (aberrated/nodal beats,
  irregular rhythm, long pauses; one record, 232, has half of them), so a raw gallery teaches nothing about
  "early". `--hard` gives the cardiologists' labels as they are.
- Template overlay (`t`) is the median of up to 200 clean N beats of the session, aligned on `utcMs` only.
- `label_beats.py --review`: opens only the items where your N/S/V label differs from our class and all your `?` items, in time order, with our
  class and features shown; a label key there appends a new row (relabel), `->` keeps it. `eval_labels.py --plot` draws
  the weighted confusion and a time line (green = agree, red = disagree).
