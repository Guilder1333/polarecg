"""Download reference ECG databases from PhysioNet and convert them to PolarisECG's raw format.

    python analysis/fetch_physionet.py                       # all three databases (slow, large)
    python analysis/fetch_physionet.py --db mitdb --records 100 101
    python analysis/fetch_physionet.py --db mitdb --limit 3  # quick try

For every record this writes ``data/reference/<db>/<record>/``:

    ecg_20200101T000000Z.bin   one lead, resampled to 130 Hz, in our raw ECG format
    annotations.csv            reference annotations, positions on the 130 Hz grid
    reference.json             where it came from (db, record, lead, original rate)

The original PhysioNet files (all leads, original rate) are kept in ``data/physionet/<db>/``, so
the conversion can be redone without downloading again. Files that exist are never overwritten, and
converted records are skipped. ``data/`` is git-ignored.
Data come from https://physionet.org (ODC-By / Open Data Commons licences; cite the databases).
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from fractions import Fraction
from pathlib import Path

import numpy as np
from scipy.signal import resample_poly

sys.path.insert(0, str(Path(__file__).parent))
from polaris_raw import ECG_RATE_HZ, write_ecg_file  # noqa: E402

# Fake start time of every converted record (their real dates are unknown or anonymised).
START_PHONE_MS = 1_577_836_800_000  # 2020-01-01T00:00:00Z
START_NAME = "20200101T000000Z"

# db -> (PhysioNet name, annotation extension, lead names to prefer, in order)
DATABASES = {
    "mitdb": ("mitdb", "atr", ("MLII", "II")),
    "incartdb": ("incartdb", "atr", ("II",)),
    "nstdb": ("nstdb", "atr", ("MLII", "II")),
}

# AAMI EC57 classes of MIT-BIH beat symbols. Anything else that is a beat becomes Q.
AAMI = {
    **dict.fromkeys("NLRej", "N"),
    **dict.fromkeys("AaJS", "S"),
    **dict.fromkeys("VE", "V"),
    "F": "F",
    **dict.fromkeys("/fQ", "Q"),
}
# Symbols that mark a beat (all others: rhythm changes, noise, signal quality, comments).
BEAT_SYMBOLS = set("NLRBAaJSVrFejnE/fQ?")


def pick_lead(sig_names: list[str], preferred: tuple[str, ...]) -> int:
    """Index of the first preferred lead present, else channel 0."""
    for name in preferred:
        if name in sig_names:
            return sig_names.index(name)
    return 0


def resample_to_130hz(signal: np.ndarray, fs: float) -> np.ndarray:
    """Polyphase resampling (includes the anti-alias filter). Returns float array at 130 Hz."""
    ratio = Fraction(ECG_RATE_HZ / fs).limit_denominator(1000)
    return resample_poly(np.asarray(signal, dtype=np.float64), ratio.numerator, ratio.denominator)


def mv_to_uv(signal_mv: np.ndarray) -> np.ndarray:
    return np.rint(np.nan_to_num(signal_mv) * 1000.0).astype(np.int32)


def convert_annotations(samples, symbols, aux, fs: float, n_out: int) -> list[dict]:
    """Rows for annotations.csv with positions moved to the 130 Hz grid."""
    rows = []
    for s, sym, a in zip(samples, symbols, aux):
        pos = int(round(s * ECG_RATE_HZ / fs))
        if not 0 <= pos < n_out:
            continue
        beat = sym in BEAT_SYMBOLS
        rows.append({
            "sample": pos,
            "time_ms": round(pos * 1000 / ECG_RATE_HZ),
            "symbol": sym,
            "beat": int(beat),
            "aami": AAMI.get(sym, "Q") if beat else "",
            "aux": (a or "").strip("\x00"),
        })
    return rows


def convert_record(db_key: str, record: str, out_root: Path, raw_root: Path) -> str:
    import wfdb  # imported here so the pure helpers above work without the download stack

    pn_name, ext, preferred = DATABASES[db_key]
    out_dir = out_root / db_key / record
    if (out_dir / "reference.json").exists():
        return "skipped (exists)"

    # Keep the original files (all leads, original rate) so we can reprocess without downloading.
    # overwrite=False: files already there are left alone.
    raw_dir = raw_root / db_key
    raw_dir.mkdir(parents=True, exist_ok=True)
    wfdb.dl_database(pn_name, str(raw_dir), records=[record], overwrite=False)
    local = str(raw_dir / record)

    header = wfdb.rdheader(local)
    lead = pick_lead(header.sig_name, preferred)
    rec = wfdb.rdrecord(local, channels=[lead])
    ann = wfdb.rdann(local, ext)

    fs = float(rec.fs)
    uv = mv_to_uv(resample_to_130hz(rec.p_signal[:, 0], fs))
    rows = convert_annotations(ann.sample, ann.symbol, ann.aux_note, fs, len(uv))

    out_dir.mkdir(parents=True, exist_ok=True)
    write_ecg_file(out_dir / f"ecg_{START_NAME}.bin", uv, START_PHONE_MS)
    with open(out_dir / "annotations.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, ["sample", "time_ms", "symbol", "beat", "aami", "aux"])
        w.writeheader()
        w.writerows(rows)
    (out_dir / "reference.json").write_text(json.dumps({
        "db": pn_name, "record": record, "lead": header.sig_name[lead], "originalRateHz": fs,
        "rateHz": ECG_RATE_HZ, "samples": int(len(uv)), "unit": "uV",
        "startUtc": "2020-01-01T00:00:00Z", "annotations": len(rows),
    }, indent=2), encoding="utf-8")
    return f"ok ({len(uv) / ECG_RATE_HZ / 60:.1f} min, lead {header.sig_name[lead]}, {len(rows)} annotations)"


def main(argv=None) -> int:
    import wfdb

    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db", choices=list(DATABASES), nargs="+", default=list(DATABASES))
    p.add_argument("--records", nargs="+", help="record names (only sensible with one --db)")
    p.add_argument("--limit", type=int, help="only the first N records of each database")
    p.add_argument("--out", type=Path, default=Path("data/reference"), help="converted records")
    p.add_argument("--raw", type=Path, default=Path("data/physionet"), help="original downloads")
    args = p.parse_args(argv)

    failures = 0
    for db_key in args.db:
        records = args.records or wfdb.get_record_list(DATABASES[db_key][0])
        if args.limit:
            records = records[: args.limit]
        for record in records:
            try:
                status = convert_record(db_key, record, args.out, args.raw)
            except Exception as e:  # keep going: one bad record should not stop a long download
                failures += 1
                status = f"FAILED: {type(e).__name__}: {e}"
            print(f"{db_key}/{record}: {status}", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
