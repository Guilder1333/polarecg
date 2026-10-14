# analysis/ — Python tools

PC-side tools for PolarisECG: read recordings, download reference databases, view ECG, score
detections. Everything here runs on your PC; nothing is sent anywhere.

Contents now:

| File | What |
|---|---|
| `polaris_raw.py` | Reader for raw recordings (`read_session(folder)`), see `docs/RAW_FORMAT.md` |
| `test_polaris_raw.py` | Tests of the reader |
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

Expected: `Ran 3 tests ... OK`.

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
