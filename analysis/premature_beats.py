"""Rough count of premature beats (PAC-like / PVC-like) per time period, before P4 exists.

    python analysis/premature_beats.py data/sessions/20261021T162147Z
    python analysis/premature_beats.py data/sessions/20261026T110150Z data/sessions/20261026T150114Z --bin 3h
    python analysis/premature_beats.py data/sessions/20261021T162147Z --csv data/analysis/night_beats.csv

**Not validated for medical use.** A simplified version of the plan's rules (§7.6), to get an idea
of how often premature beats happen and when. Needs `analyze` to have run first.

Per beat:

* ``Q`` (not judged): poor signal quality (``good`` = 0 in ``beats.csv``), first or last beat of a
  segment, or too few normal beats nearby to compare the shape with.
* ``V`` (PVC-like): the QRS shape differs from the *template* of the user's own normal beats
  around it (correlation below ``--corr``, default 0.90) *and* the beat is at least a little early
  (``RRpre`` < ``--v-early`` x reference RR, default 0.95). A different shape at normal timing is
  mostly the QRS changing (posture, electrode contact), not a PVC. The template is the median of
  up to 60 normal-timed, good beats around the beat, so it follows slow shape changes.
* ``S`` (PAC-like): normal shape, but early (``RRpre`` < ``--early`` x reference RR, default 0.85)
  *and* followed by a longer interval (``RRpost`` >= ``--pause`` x ``RRpre``, default 1.15). The
  pause rule keeps a gradual speed-up of the heart (breathing, sinus arrhythmia) from counting.
* The reference RR is the median of up to 20 intervals around the beat (both sides) between two
  beats of normal shape, so a slow change of heart rate or a stretch of dropped beats moves it too.
* ``N``: everything else.

How good is it? On MIT-BIH (44 non-paced records, without the 7 with atrial fibrillation, where
"early" means nothing; thresholds were chosen on these same records, so this is optimistic):
V sensitivity 81 %, positive predictivity 91 %. S is much weaker: about 40 % of PACs are found
(half of the labelled PACs are less than 15 % early, which timing cannot tell from normal
variation), and about 58 % of S calls are PACs. So read S as "clearly early beats", a lower bound.

Output: one row per period (``--bin``, in the time zone ``--tz``, default the PC's own): usable
hours (10 s windows with quality >= 0.5), beats, S and V counts, per hour and as % of judged beats,
and ``S+V %``: all premature beats together as % of judged beats.
``--csv`` writes every S / V beat with its time (UTC for ``view_ecg.py --at``, and local).
``--out-beats DIR`` writes a copy of ``beats.csv`` with the classes filled in: ``view_ecg.py
--analysis DIR`` then colours the beats, ``score_beats.py --analysis-root`` scores them.
"""
from __future__ import annotations

import argparse
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.signal import butter, sosfiltfilt

sys.path.insert(0, str(Path(__file__).resolve().parent))
from polaris_raw import ECG_RATE_HZ, read_ecg  # noqa: E402
from polaris_segments import build_segments  # noqa: E402

# Shape window around the R peak (samples at 130 Hz): QRS and the start of ST, not P or T, so a
# PAC's different P wave does not make it look abnormal.
PRE, POST = 13, 20            # -100 ms .. +154 ms
MAX_LAG = 4                   # the R mark may sit on S instead of R: try shifts up to ±4 samples
TEMPLATE_HALF = 30            # template from up to 30 normal beats before and 30 after
TEMPLATE_MIN = 8              # fewer normal beats nearby: the shape cannot be judged
REF_HALF = 10               # reference RR from up to 10 intervals before and 10 after
MIN_QUALITY = 0.5             # windows.csv quality below this: unusable (as in the viewer)


def default_analysis_dir(recording: Path) -> Path:
    rec = recording.resolve()
    return Path("data") / "analysis" / f"{rec.parent.name}-{rec.name}"


def parse_bin(text: str) -> pd.Timedelta:
    m = re.fullmatch(r"(\d+)\s*(min|h|d)", text.strip())
    if not m:
        raise argparse.ArgumentTypeError("use e.g. 30min, 1h, 6h, 1d")
    return pd.Timedelta(int(m[1]), unit={"min": "min", "h": "h", "d": "D"}[m[2]])


def parse_tz(text: str | None):
    if text is None:
        return datetime.now().astimezone().tzinfo
    if text.upper() == "UTC":
        return timezone.utc
    m = re.fullmatch(r"([+-])(\d{1,2}):?(\d{2})?", text)
    if not m:
        raise argparse.ArgumentTypeError("use UTC or an offset like +09:00")
    sign = 1 if m[1] == "+" else -1
    return timezone(sign * timedelta(hours=int(m[2]), minutes=int(m[3] or 0)))


def beat_windows(recording: Path, beats: pd.DataFrame) -> np.ndarray:
    """ECG (0.5-40 Hz) around every beat, ``PRE + MAX_LAG`` before to ``POST + MAX_LAG`` after the R
    mark; NaN rows where the window does not fit in its segment."""
    ecg, _ = read_ecg(recording)
    segments = build_segments(ecg)
    m = MAX_LAG
    out = np.full((len(beats), PRE + POST + 1 + 2 * m), np.nan)
    sos = butter(2, [0.5, 40], btype="band", fs=ECG_RATE_HZ, output="sos")
    # beats.csv numbers segments the same way as build_segments (both mirror the Kotlin code)
    for seg_no, rows in beats.groupby("segment").groups.items():
        if seg_no >= len(segments):
            continue
        seg = segments[seg_no]
        x = seg.samples_uv.astype(float)
        x = sosfiltfilt(sos, x) if len(x) > 30 else x - np.median(x)
        # sample index of every R mark at once (Segment.index_at does one, rebuilding its tables)
        idx, ms = seg._knots()
        r_all = np.rint(np.interp(beats.utcMs.to_numpy(float)[rows] - seg.start_utc_ms, ms, idx)).astype(int)
        for row, r in zip(rows, r_all):
            lo, hi = r - PRE - m, r + POST + m + 1
            if lo >= 0 and hi <= len(x):
                out[row] = x[lo:hi]
    return out


def best_corr(w: np.ndarray, template: np.ndarray) -> float:
    """Highest correlation of the beat with the template over shifts of ±MAX_LAG samples."""
    n = len(template)
    t = template - template.mean()
    tn = np.linalg.norm(t)
    best = -1.0
    for lag in range(2 * MAX_LAG + 1):
        s = w[lag:lag + n]
        s = s - s.mean()
        d = np.linalg.norm(s) * tn
        if d > 0:
            best = max(best, float(s @ t / d))
    return best


def classify(beats: pd.DataFrame, windows: np.ndarray, corr_min: float, early: float, pause: float,
             v_early: float) -> pd.DataFrame:
    n = len(beats)
    rr_pre = beats.rrMs.to_numpy(float)                       # NaN at the first beat of a segment
    same_seg_next = np.append(beats.segment.to_numpy()[1:] == beats.segment.to_numpy()[:-1], False)
    rr_post = np.where(same_seg_next, np.append(rr_pre[1:], np.nan), np.nan)
    good = beats.good.to_numpy() == 1
    valid = ~np.isnan(windows).any(axis=1)

    # Beats for the template: good, and both intervals close to the local median (normal timing).
    local = pd.Series(rr_pre).rolling(15, center=True, min_periods=5).median().to_numpy()
    normal_timed = good & valid & (np.abs(rr_pre / local - 1) < 0.15) & (np.abs(rr_post / local - 1) < 0.15)
    core = windows[:, MAX_LAG:MAX_LAG + PRE + POST + 1]
    normal_idx = np.flatnonzero(normal_timed)

    corr = np.full(n, np.nan)
    for i in range(n):
        if not (good[i] and valid[i]):
            continue
        k = np.searchsorted(normal_idx, i)
        near = normal_idx[max(0, k - TEMPLATE_HALF):k + TEMPLATE_HALF + 1]
        near = near[near != i]
        if len(near) >= TEMPLATE_MIN:
            corr[i] = best_corr(windows[i], np.median(core[near], axis=0))

    judged = ~np.isnan(corr) & ~np.isnan(rr_pre) & ~np.isnan(rr_post)
    odd_shape = judged & (corr < corr_min)
    # Reference RR: median of the intervals on both sides of the beat (REF_HALF before, REF_HALF
    # after; not the beat's own two) whose two beats both have a normal shape.
    ok_rr = ~np.isnan(rr_pre) & ~odd_shape & ~np.append(False, odd_shape[:-1])
    prematurity = np.full(n, np.nan)
    for i in range(n):
        lo, hi = max(0, i - REF_HALF), min(n, i + REF_HALF + 2)
        near = [rr_pre[j] for j in range(lo, hi) if ok_rr[j] and j not in (i, i + 1)]
        if len(near) >= 4:
            prematurity[i] = rr_pre[i] / np.median(near)
    cls = np.full(n, "Q", dtype=object)
    cls[judged] = "N"
    # A different shape at normal timing is mostly the QRS changing (posture, the R mark moving),
    # not a PVC: those come early.
    # No reference (e.g. bigeminy: no two normal beats in a row): the shape decides alone.
    cls[odd_shape & (np.isnan(prematurity) | (prematurity < v_early))] = "V"
    cls[judged & ~odd_shape & (prematurity < early) & (rr_post >= pause * rr_pre)] = "S"
    out = beats.copy()
    out["class"] = cls
    out["corr"] = np.round(corr, 3)
    out["prematurity"] = np.round(prematurity, 3)
    out["rrPostMs"] = rr_post
    return out


def usable_hours(analysis: Path) -> pd.Series:
    """Start time (UTC ms) of every usable 10 s window."""
    w = pd.read_csv(analysis / "windows.csv")
    return w.startUtcMs[w.quality >= MIN_QUALITY]


def summary(beats: pd.DataFrame, usable_ms: pd.Series, bin_size: pd.Timedelta, tz) -> pd.DataFrame:
    def local(ms):
        return pd.to_datetime(ms, unit="ms", utc=True).dt.tz_convert(tz).dt.floor(bin_size)

    b = beats.assign(period=local(beats.utcMs))
    usable = pd.DataFrame({"period": local(usable_ms)}).groupby("period").size() * 10 / 3600
    counts = b.groupby(["period", "class"]).size().unstack(fill_value=0)
    for c in "NSVQ":
        if c not in counts:
            counts[c] = 0
    t = pd.DataFrame({"hours": usable}).join(counts[list("NSVQ")], how="outer").fillna(0)
    t.loc["total"] = t.sum()
    judged = t.N + t.S + t.V
    hours = t.hours.where(t.hours > 0)
    return pd.DataFrame({
        "usable h": t.hours.round(2),
        "beats": (judged + t.Q).astype(int),
        "not judged %": (100 * t.Q / (judged + t.Q)).round(1),
        "S (PAC?)": t.S.astype(int),
        "S/h": (t.S / hours).round(1),
        "S %": (100 * t.S / judged).round(2),
        "V (PVC?)": t.V.astype(int),
        "V/h": (t.V / hours).round(1),
        "V %": (100 * t.V / judged).round(2),
        "S+V %": (100 * (t.S + t.V) / judged).round(2),
    })


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("recordings", nargs="+", type=Path, help="session folder(s), analysed with `analyze`")
    p.add_argument("--analysis", type=Path, help="analysis folder (only with one recording)")
    p.add_argument("--bin", type=parse_bin, default=pd.Timedelta(1, unit="h"), help="period length: 30min, 1h (default), 6h, 1d")
    p.add_argument("--tz", type=parse_tz, default=None, help="time zone of the table: UTC or +09:00 (default: this PC's)")
    p.add_argument("--corr", type=float, default=0.90, help="shape correlation below this = different shape (default 0.90)")
    p.add_argument("--early", type=float, default=0.85, help="RRpre below this x reference RR = early (default 0.85)")
    p.add_argument("--pause", type=float, default=1.15, help="S needs RRpost >= this x RRpre (default 1.15; 0 = off)")
    p.add_argument("--v-early", type=float, default=0.95, help="V also needs RRpre below this x reference RR (default 0.95; 9 = off)")
    p.add_argument("--csv", type=Path, help="write the S and V beats with their times to this CSV")
    p.add_argument("--out-beats", type=Path, help="write beats.csv with the classes to this folder (one recording)")
    args = p.parse_args(argv)
    tz = args.tz or parse_tz(None)
    if len(args.recordings) > 1 and (args.analysis or args.out_beats):
        p.error("--analysis and --out-beats work with one recording only")

    all_beats, all_usable = [], []
    for rec in args.recordings:
        analysis = args.analysis or default_analysis_dir(rec)
        if not (analysis / "beats.csv").is_file():
            print(f"{analysis / 'beats.csv'} not found: run `analyze` on {rec} first", file=sys.stderr)
            return 1
        beats = pd.read_csv(analysis / "beats.csv")
        classified = classify(beats, beat_windows(rec, beats), args.corr, args.early, args.pause, args.v_early)
        if args.out_beats:
            args.out_beats.mkdir(parents=True, exist_ok=True)
            classified[beats.columns].to_csv(args.out_beats / "beats.csv", index=False)
        all_beats.append(classified.assign(recording=rec.name))
        all_usable.append(usable_hours(analysis))
    beats = pd.concat(all_beats, ignore_index=True)

    table = summary(beats, pd.concat(all_usable), args.bin, tz)
    print(f"Rough premature-beat count, NOT validated for medical use. Periods in {tz}.")
    print("S = early beat with normal shape (PAC-like), V = different shape (PVC-like); % of judged beats.\n")
    idx = [i.strftime("%Y-%m-%d %H:%M") if i != "total" else "total" for i in table.index]
    print(table.set_axis(idx).to_string())

    if args.csv:
        ev = beats[beats["class"].isin(["S", "V"])].copy()
        ev["local"] = pd.to_datetime(ev.utcMs, unit="ms", utc=True).dt.tz_convert(tz).dt.strftime("%Y-%m-%d %H:%M:%S")
        ev["utcTime"] = pd.to_datetime(ev.utcMs, unit="ms", utc=True).dt.strftime("%H:%M:%S")
        ev[["recording", "utcTime", "local", "class", "rrMs", "rrPostMs", "prematurity", "corr", "sqi"]].to_csv(args.csv, index=False)
        print(f"\n{len(ev)} S/V beats written to {args.csv} (utcTime works with view_ecg.py --at)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
