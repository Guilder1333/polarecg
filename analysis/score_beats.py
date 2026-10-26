"""Score detected beats against reference annotations (P2.6).

    python analysis/score_beats.py data/reference/mitdb/100
    python analysis/score_beats.py data/reference/mitdb --exclude-paced --split DS2
    python analysis/score_beats.py data/reference/mitdb --csv data/analysis/scores.csv

Each argument is either one converted record folder (``data/reference/<db>/<record>``) or a
database folder holding several of them. The detections come from ``:tools:cli analyze`` and are
looked up the way the CLI names its output: ``data/analysis/<db>-<record>/beats.csv``
(use ``--analysis-root`` to change the parent folder).

A detection and a reference beat match when they are within ``--tolerance`` ms (default 150).
Every beat is used at most once, so a double detection counts as one hit and one false positive.

* Detection: TP / FP / FN, sensitivity Se = TP/(TP+FN), positive predictivity +P = TP/(TP+FP).
* Classes (AAMI N/S/V/F/Q): for matched pairs, reference class against our class. Se and +P per
  class are shown only for classes that occur; until P4 every detection is ``U`` and this part
  is skipped.
"""
from __future__ import annotations

import argparse
import csv
import sys
from collections import Counter
from pathlib import Path

import numpy as np

START_MS = 1_577_836_800_000  # fake start of every converted record (see fetch_physionet.py)
ECG_RATE_HZ = 130

# MIT-BIH records with paced rhythm: AAMI EC57 leaves them out of the evaluation.
PACED = {"102", "104", "107", "217"}
# Train / test split by de Chazal et al. (2004). Tune on DS1, report on DS2.
DS1 = {"101", "106", "108", "109", "112", "114", "115", "116", "118", "119", "122", "124",
       "201", "203", "205", "207", "208", "209", "215", "220", "223", "230"}
DS2 = {"100", "103", "105", "111", "113", "117", "121", "123", "200", "202", "210", "212",
       "213", "214", "219", "221", "222", "228", "231", "232", "233", "234"}
CLASSES = "NSVFQ"


def load_reference(record_dir: Path) -> tuple[np.ndarray, list[str]]:
    """Beat times (epoch ms, sorted) and AAMI classes from ``annotations.csv``."""
    times, classes = [], []
    with open(record_dir / "annotations.csv", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["beat"] == "1":
                times.append(START_MS + int(row["sample"]) * 1000 / ECG_RATE_HZ)
                classes.append(row["aami"] or "Q")
    return np.array(times, dtype=float), classes


def load_detections(analysis_dir: Path) -> tuple[np.ndarray, list[str], list[bool] | None]:
    """Beat times (epoch ms, sorted), classes and "good quality" flags from the CLI's ``beats.csv``.

    The flags are None if the file has no ``good`` column (written before P3.4).
    """
    times, classes, good = [], [], []
    with open(analysis_dir / "beats.csv", newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        has_good = "good" in (reader.fieldnames or [])
        for row in reader:
            times.append(float(row["utcMs"]))
            classes.append(row["class"])
            if has_good:
                good.append(row["good"] == "1")
    order = np.argsort(times, kind="stable")
    return (np.array(times, dtype=float)[order], [classes[i] for i in order],
            [good[i] for i in order] if has_good else None)


def match_beats(ref: np.ndarray, det: np.ndarray, tol_ms: float) -> list[tuple[int, int]]:
    """Pairs ``(reference index, detection index)`` within ``tol_ms``, each beat used once.

    Walks the reference beats in time order and takes the nearest detection that is still free.
    Both arrays must be sorted.
    """
    used = np.zeros(len(det), dtype=bool)
    pairs = []
    for i, t in enumerate(ref):
        lo = np.searchsorted(det, t - tol_ms, side="left")
        hi = np.searchsorted(det, t + tol_ms, side="right")
        best, best_d = -1, tol_ms + 1
        for j in range(lo, hi):
            d = abs(det[j] - t)
            if not used[j] and d < best_d:
                best, best_d = j, d
        if best >= 0:
            used[best] = True
            pairs.append((i, best))
    return pairs


def score_record(ref_t, ref_c, det_t, det_c, tol_ms=150.0, skip_start_s=0.0, det_good=None) -> dict:
    """Counts for one record: ``tp, fp, fn`` and ``confusion[(ref class, our class)]``.

    With ``det_good`` (one flag per detection) it also counts how many true and false detections
    were flagged as poor quality: ``tp_bad``, ``fp_bad``, and ``judged`` = detections with a flag.
    """
    if skip_start_s > 0:  # ignore the first seconds on both sides
        cut = START_MS + skip_start_s * 1000
        keep_r, keep_d = ref_t >= cut, det_t >= cut
        ref_t, ref_c = ref_t[keep_r], [c for c, k in zip(ref_c, keep_r) if k]
        det_t, det_c = det_t[keep_d], [c for c, k in zip(det_c, keep_d) if k]
        if det_good is not None:
            det_good = [g for g, k in zip(det_good, keep_d) if k]
    pairs = match_beats(ref_t, det_t, tol_ms)
    confusion = Counter((ref_c[i], det_c[j]) for i, j in pairs)
    matched_det = {j for _, j in pairs}
    # Detections without a reference beat, by our class (for +P per class).
    false_pos = Counter(det_c[j] for j in range(len(det_c)) if j not in matched_det)
    tp_bad = fp_bad = 0
    if det_good is not None:
        tp_bad = sum(1 for j in matched_det if not det_good[j])
        fp_bad = sum(1 for j in range(len(det_good)) if j not in matched_det and not det_good[j])
    return {
        "tp": len(pairs), "fp": len(det_t) - len(pairs), "fn": len(ref_t) - len(pairs),
        "tp_bad": tp_bad, "fp_bad": fp_bad, "judged": len(det_t) if det_good is not None else 0,
        "confusion": confusion, "false_pos_by_class": false_pos,
        "ref_by_class": Counter(ref_c), "ref_beats": len(ref_t), "det_beats": len(det_t),
    }


def merge(a: dict, b: dict) -> dict:
    """Sum two result dicts (for the pooled total)."""
    out = {}
    for k in a:
        out[k] = a[k] + b[k]  # ints add, Counters add
    return out


def ratio(num: int, den: int) -> float | None:
    return None if den == 0 else num / den


def class_stats(r: dict) -> dict[str, dict]:
    """Per class: reference count, Se and +P. Only classes that we can judge are returned."""
    stats = {}
    for c in CLASSES:
        n_ref = r["ref_by_class"][c]
        hits = r["confusion"][(c, c)]
        ours = sum(v for (_, dc), v in r["confusion"].items() if dc == c) + r["false_pos_by_class"][c]
        if n_ref or ours:
            stats[c] = {"ref": n_ref, "hits": hits, "se": ratio(hits, n_ref), "ppv": ratio(hits, ours)}
    return stats


def pct(x: float | None) -> str:
    return "  n/a" if x is None else f"{100 * x:5.1f}"


def bad_cols(r: dict) -> str:
    """Share of true / false detections flagged as poor quality (blank without quality flags)."""
    if not r["judged"]:
        return f"{'':>16}"
    return f"{pct(ratio(r['tp_bad'], r['tp'])):>8}{pct(ratio(r['fp_bad'], r['fp'])):>8}"


def find_records(paths: list[Path], exclude_paced: bool, split: str | None) -> list[Path]:
    """Expand database folders to record folders and apply the paced / split filters."""
    records = []
    for p in paths:
        if (p / "annotations.csv").exists():
            records.append(p)
        else:
            records.extend(sorted(d for d in p.iterdir() if (d / "annotations.csv").exists()))
    if exclude_paced:
        records = [r for r in records if r.name not in PACED]
    if split:
        wanted = DS1 if split == "DS1" else DS2
        records = [r for r in records if r.name in wanted]
    return records


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("paths", nargs="+", type=Path, help="record folder(s) or database folder(s)")
    p.add_argument("--analysis-root", type=Path, default=Path("data/analysis"))
    p.add_argument("--tolerance", type=float, default=150.0, help="match window in ms (default 150)")
    p.add_argument("--skip-start", type=float, default=0.0, help="ignore the first N seconds")
    p.add_argument("--exclude-paced", action="store_true", help="leave out MIT-BIH 102, 104, 107, 217")
    p.add_argument("--split", choices=["DS1", "DS2"], help="only the MIT-BIH tuning (DS1) or test (DS2) records")
    p.add_argument("--csv", type=Path, help="also write per-record results to this CSV")
    args = p.parse_args(argv)

    records = find_records(args.paths, args.exclude_paced, args.split)
    if not records:
        print("no records found", file=sys.stderr)
        return 1

    total, rows, missing = None, [], []
    print(f"{'record':<14}{'ref':>7}{'det':>7}{'TP':>7}{'FP':>6}{'FN':>6}{'Se%':>7}{'+P%':>7}"
          f"{'TPbad%':>8}{'FPbad%':>8}")
    for rec in records:
        analysis = args.analysis_root / f"{rec.parent.name}-{rec.name}"
        if not (analysis / "beats.csv").exists():
            missing.append(rec.name)
            continue
        ref_t, ref_c = load_reference(rec)
        det_t, det_c, det_good = load_detections(analysis)
        r = score_record(ref_t, ref_c, det_t, det_c, args.tolerance, args.skip_start, det_good)
        total = r if total is None else merge(total, r)
        se, ppv = ratio(r["tp"], r["tp"] + r["fn"]), ratio(r["tp"], r["tp"] + r["fp"])
        print(f"{rec.parent.name + '/' + rec.name:<14}{r['ref_beats']:>7}{r['det_beats']:>7}"
              f"{r['tp']:>7}{r['fp']:>6}{r['fn']:>6}{pct(se):>7}{pct(ppv):>7}{bad_cols(r)}")
        rows.append({"db": rec.parent.name, "record": rec.name, "ref": r["ref_beats"], "det": r["det_beats"],
                     "tp": r["tp"], "fp": r["fp"], "fn": r["fn"], "se": se, "ppv": ppv})

    if missing:
        print(f"\nno beats.csv for {len(missing)} record(s), run `analyze` first: {', '.join(missing)}")
    if total is None:
        return 1

    se, ppv = ratio(total["tp"], total["tp"] + total["fn"]), ratio(total["tp"], total["tp"] + total["fp"])
    print(f"{'TOTAL':<14}{total['ref_beats']:>7}{total['det_beats']:>7}{total['tp']:>7}"
          f"{total['fp']:>6}{total['fn']:>6}{pct(se):>7}{pct(ppv):>7}{bad_cols(total)}")
    if total["judged"]:
        print("TPbad% = true detections flagged poor quality (lost), FPbad% = false detections flagged poor quality (caught)")

    judged = {c: s for c, s in class_stats(total).items()}
    if any(dc != "U" for (_, dc) in total["confusion"]) or any(c != "U" for c in total["false_pos_by_class"]):
        print(f"\n{'class':<8}{'ref':>8}{'hits':>8}{'Se%':>7}{'+P%':>7}   (matched beats only)")
        for c, s in judged.items():
            print(f"{c:<8}{s['ref']:>8}{s['hits']:>8}{pct(s['se']):>7}{pct(s['ppv']):>7}")
        print("\nconfusion (rows = reference, columns = ours)")
        ours = sorted({dc for (_, dc) in total["confusion"]})
        print(f"{'':<6}" + "".join(f"{c:>7}" for c in ours))
        for rc in CLASSES:
            if total["ref_by_class"][rc]:
                print(f"{rc:<6}" + "".join(f"{total['confusion'][(rc, c)]:>7}" for c in ours))
    else:
        print("\nclass scores skipped: all detections are unclassified (U) until P4")

    if args.csv:
        args.csv.parent.mkdir(parents=True, exist_ok=True)
        with open(args.csv, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, list(rows[0]))
            w.writeheader()
            w.writerows(rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
