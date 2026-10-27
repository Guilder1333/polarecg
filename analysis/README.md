# analysis/ — Python tools

PC-side tools for PolarisECG: read recordings, download reference databases, view ECG, score
detections. Everything here runs on your PC; nothing is sent anywhere.

Contents now:

| File | What |
|---|---|
| `polaris_raw.py` | Reader for raw recordings (`read_session(folder)`), see `docs/RAW_FORMAT.md` |
| `test_polaris_raw.py` | Tests of the reader |
| `fetch_physionet.py` | Downloads MIT-BIH / INCART / NSTDB and converts them (see below) |
| `test_fetch_physionet.py` | Offline tests of the conversion |
| `eval_features.py` | How well the beat features (P4.1/P4.2) separate the MIT-BIH classes: percentiles per class and AUC |
| `eval_patterns.py` | Counts V patterns (couplets, runs, bigeminy...) in the MIT-BIH annotations and compares them with ours |
| `view_ecg.py` | Interactive ECG viewer for a recording + its `analyze` output (see below) |
| `polaris_segments.py` | Gap-free segments with UTC time; mirrors the Kotlin `EcgSegments`/`TimeMapper` |
| `test_view_ecg.py` | Offline tests of the viewer's data side |
| `premature_beats.py` | Rough PAC-like / PVC-like counts per hour, before P4 (see below) |
| `test_premature_beats.py` | Tests of the rough count |
| `requirements.txt` | Python packages |

## Setup on Windows (once)

You need Python 3.10 or newer. Check in PowerShell:

```powershell
py --version
```

If that fails, install Python from <https://www.python.org/downloads/> and tick
**"Add python.exe to PATH"** in the installer.

> Note: `python` may point to the Microsoft Store stub and fail with "cannot find the path".
> Use `py` (the Python launcher) instead, or turn the stub off in
> *Settings → Apps → Advanced app settings → App execution aliases*.

From the repository root (`C:\workspace\polarisecg`):

```powershell
py -m venv analysis\.venv
analysis\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -r analysis\requirements.txt
```

The virtual environment (`analysis\.venv`) keeps these packages away from the rest of your
system; it is git-ignored. If PowerShell refuses to run `Activate.ps1` ("running scripts is
disabled"), run this once and retry:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

In a new terminal, activate again with `analysis\.venv\Scripts\Activate.ps1`. The prompt then
starts with `(.venv)`.

## Check that it works

```powershell
python -m unittest discover -s analysis
```

Expected: all tests `OK`.

## Reference databases (PhysioNet)

```powershell
python analysis\fetch_physionet.py --db mitdb --limit 3      # quick try, 3 records
python analysis\fetch_physionet.py                           # everything (needs internet)
python analysis\fetch_physionet.py --db mitdb --records 100 119
```

Databases: `mitdb` (MIT-BIH Arrhythmia, 48 records, 360 Hz), `incartdb` (INCART, 75 records, 257 Hz,
12 leads; this is the big one, expect a long download) and `nstdb` (MIT-BIH Noise Stress Test).

Per record the script writes `data/reference/<db>/<record>/`:

- `ecg_20200101T000000Z.bin`: **one lead** (MLII for MIT-BIH/NSTDB, II for INCART), low-pass
  filtered and resampled to **130 Hz**, in our raw format. The start time is a fixed fake
  (2020-01-01); the real dates are unknown. It can be read with `read_session` or the Kotlin
  `readStream` like a phone recording.
- `annotations.csv`: `sample` (index on the 130 Hz grid), `time_ms` (from record start), `symbol`
  (original PhysioNet symbol), `beat` (1 = a beat, 0 = rhythm/noise/comment marker), `aami`
  (N, S, V, F or Q for beats), `aux` (e.g. `(AFIB`).
- `reference.json`: source, lead, original rate.

The **original downloads** (all leads, original sampling rate: `.hea`, `.dat`, `.atr`) are kept in
`data/physionet/<db>/`, so you can reprocess (another lead, another resampler) without downloading
again; delete the converted record folder and run the script again, it reuses the local files.
This costs roughly 50 MB for MIT-BIH, ~800 MB for INCART and ~20 MB for NSTDB.

Converted records that are up to date are skipped; ones made by an older version of the converter
are redone automatically from the local originals (no download). Downloaded originals are never
overwritten. Delete a record folder to force a conversion.

## Reading a recording

Copy an exported session into `data/sessions/` (git-ignored; never commit health data), then:

```python
import sys; sys.path.insert(0, "analysis")
from polaris_raw import read_session

s = read_session("data/sessions/20261012T221530Z")
print(len(s.ecg.samples_uv) / 130, "seconds of ECG")
print(s.hr.rr_ms[:10])          # RR intervals the H10 reported
print(s.unreadable)             # files that could not be read, if any
```

ECG is in microvolts at 130 Hz; `frame_phone_ms` / `frame_sensor_ns` give the time of every frame.

## Viewing a recording

First run the analysis (`docs/ANALYSIS_FORMAT.md`), then:

```powershell
python analysis/view_ecg.py data/reference/mitdb/100
python analysis/view_ecg.py data/sessions/20261012T221530Z --at 03:12:40 --width 20
python analysis/view_ecg.py data/sessions/20261012T221530Z --event GAP
python analysis/view_ecg.py data/reference/mitdb/100 --save beats.png     # PNG, no window
```

The analysis folder is found the same way the CLI names it (`data/analysis/<parent>-<folder>`);
use `--analysis <folder>` if you wrote it elsewhere. Beats are colored by class (`U` blue until
P4); grey bands = no signal, orange = samples missing in a 10 s window, red = poor quality
(from P3.4). Keys: arrows pan, PageUp/PageDown full window, `+`/`-` zoom, `n`/`p` next/previous
beat, `e`/`E` next/previous event, Home/End. The "Go to" box takes `HH:MM:SS` (UTC), an ISO time,
or `+90` / `+1:30` seconds from the start. The toolbar magnifier/hand zoom and pan freely.

## Scoring detections

Run the analysis for each record (`docs/ANALYSIS_FORMAT.md`), then compare with the reference
annotations of the converted PhysioNet records:

```powershell
python analysis/score_beats.py data/reference/mitdb/100
python analysis/score_beats.py data/reference/mitdb --exclude-paced --split DS2
python analysis/score_beats.py data/reference/mitdb --csv data/analysis/scores.csv
```

A detection matches a reference beat within ±150 ms (`--tolerance`), each beat used once; a
double detection is one hit plus one false positive. Output: reference / detected beats, TP, FP, FN,
sensitivity (Se) and positive predictivity (+P) per record and pooled. Once beats carry classes
(P4) it also prints Se / +P per class (N, S, V, F, Q) and the confusion matrix.

`--exclude-paced` leaves out MIT-BIH 102, 104, 107, 217 (AAMI EC57). `--split DS1` / `DS2` selects
the de Chazal tuning / test records: tune on DS1, report on DS2. Records without `beats.csv` are
listed, not scored.

## Comparing with the strap's RR intervals

Needs a session folder and its `analyze` output:

```powershell
python analysis/compare_rr.py data/sessions/20261026T110150Z
python analysis/compare_rr.py data/sessions/20261026T110150Z --plot rr.png --worst 15 --csv rr_pairs.csv
```

The H10 sends RR intervals without per-beat timestamps, so the script first pairs them with ours
(in order, using the arrival time as a guide; beats one side missed stay unpaired), then reports
`ours - strap` in ms: for all pairs, for pairs where both our beats are good, by motion and by
heart rate. `--worst` lists the biggest disagreements with their time for `view_ecg.py --at`.

## Rough premature-beat count (before P4)

Needs a session folder and its `analyze` output. **Not validated for medical use**: a stop-gap until
the real classifier (P4) exists.

```powershell
python analysis/premature_beats.py data/sessions/20261021T162147Z
python analysis/premature_beats.py data/sessions/20261026T110150Z data/sessions/20261026T150114Z --bin 3h --tz +09:00
python analysis/premature_beats.py data/sessions/20261021T162147Z --csv data/analysis/night_sv.csv
```

Per period (`--bin`, default 1 h, in the PC's time zone unless `--tz` is given): usable hours, beats,
S (early with normal shape, PAC-like) and V (different shape and early, PVC-like) per hour and in %,
and both together (`S+V %`, premature beats as a share of all judged beats).
Several sessions are combined into one table. `--csv` lists every S / V beat with its UTC time for
`view_ecg.py --at`; `--out-beats DIR` writes `beats.csv` with the classes so `view_ecg.py --analysis
DIR` colours them. The rules, the options and the MIT-BIH result (V about 81 % / 91 %, S only about
40 % / 58 %: read S as a lower bound) are at the top of the script.
