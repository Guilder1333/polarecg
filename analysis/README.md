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
