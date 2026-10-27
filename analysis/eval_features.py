"""How well do the beat features (P4.1/P4.2) separate the reference beat classes?

    python analysis/eval_features.py data/reference/mitdb --exclude-paced
    python analysis/eval_features.py data/reference/mitdb/119

Joins the feature columns of ``:tools:cli analyze`` (``data/analysis/<db>-<record>/beats.csv``)
with the reference annotations (AAMI classes N / S / V / F / Q) of the matched beats and prints,
per class, the median and the 10th / 90th percentile of each feature. Then, for the pairs that
matter, the area under the ROC curve (AUC): the chance that a random beat of the first class has a
higher (or, for ``corr``, lower) value than a random beat of the second one. 0.5 = no help, 1.0 =
perfect. Only good-quality beats are used (the classifier says Q for the others).
"""
from __future__ import annotations

import argparse
import csv
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from score_beats import find_records, load_reference, match_beats  # noqa: E402

FEATURES = ["corr", "qrsWidthMs", "ampRatio", "areaRatio", "prematurity", "compensation"]
# (positive class, negative class, feature, higher value means positive?)
PAIRS = [
    ("V", "N", "corr", False), ("V", "N", "qrsWidthMs", True), ("V", "N", "areaRatio", True),
    ("V", "N", "prematurity", False),
    ("S", "N", "prematurity", False), ("S", "N", "corr", False),
    ("V", "S", "corr", False), ("V", "S", "qrsWidthMs", True),
]


def load_features(analysis_dir: Path) -> list[dict]:
    with open(analysis_dir / "beats.csv", newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def value(row: dict, name: str) -> float | None:
    s = row.get(name, "")
    return float(s) if s != "" else None


def auc(pos: np.ndarray, neg: np.ndarray, higher_is_positive: bool) -> float:
    """P(random positive scores above random negative), ties count half (rank-sum formula)."""
    if len(pos) == 0 or len(neg) == 0:
        return float("nan")
    if not higher_is_positive:
        pos, neg = -pos, -neg
    allv = np.concatenate([pos, neg])
    order = allv.argsort(kind="mergesort")
    ranks = np.empty(len(allv))
    ranks[order] = np.arange(1, len(allv) + 1)
    # average the ranks of equal values
    sorted_v = allv[order]
    i = 0
    while i < len(sorted_v):
        j = i
        while j + 1 < len(sorted_v) and sorted_v[j + 1] == sorted_v[i]:
            j += 1
        if j > i:
            ranks[order[i:j + 1]] = (i + 1 + j + 1) / 2
        i = j + 1
    return (ranks[:len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg))


def collect(records: list[Path], analysis_root: Path, tol_ms: float, skip_s: float):
    """Feature values per reference class over all records (matched, good-quality beats only)."""
    from score_beats import START_MS
    values = defaultdict(lambda: defaultdict(list))
    counts = defaultdict(int)
    for rec in records:
        folder = analysis_root / f"{rec.parent.name}-{rec.name}"
        if not (folder / "beats.csv").exists():
            continue
        ref_t, ref_c = load_reference(rec)
        rows = load_features(folder)
        if not rows or "corr" not in rows[0]:
            print(f"{rec.name}: beats.csv has no feature columns, run `analyze` again", file=sys.stderr)
            continue
        det_t = np.array([float(r["utcMs"]) for r in rows])
        for i, j in match_beats(ref_t, det_t, tol_ms):
            if ref_t[i] < START_MS + skip_s * 1000:
                continue
            row = rows[j]
            cls = ref_c[i]
            counts[cls] += 1
            if row["good"] != "1":
                continue
            counts[cls + "_good"] += 1
            for name in FEATURES:
                v = value(row, name)
                if v is not None:
                    values[cls][name].append(v)
    return values, counts


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("paths", nargs="+", type=Path)
    p.add_argument("--analysis-root", type=Path, default=Path("data/analysis"))
    p.add_argument("--tolerance", type=float, default=150.0)
    p.add_argument("--skip-start", type=float, default=30.0, help="ignore the first N seconds (template learning)")
    p.add_argument("--exclude-paced", action="store_true")
    args = p.parse_args(argv)

    records = find_records(args.paths, args.exclude_paced, None)
    values, counts = collect(records, args.analysis_root, args.tolerance, args.skip_start)
    classes = [c for c in "NSVFQ" if counts[c]]
    print("matched beats (good quality / all): " + ", ".join(f"{c} {counts[c + '_good']}/{counts[c]}" for c in classes))
    print(f"\n{'class':<6}{'feature':<13}{'p10':>9}{'median':>9}{'p90':>9}{'n':>8}")
    for c in classes:
        for name in FEATURES:
            v = np.array(values[c][name])
            if len(v):
                print(f"{c:<6}{name:<13}{np.percentile(v, 10):>9.2f}{np.median(v):>9.2f}{np.percentile(v, 90):>9.2f}{len(v):>8}")
    print("\nAUC (1.0 = perfect separation, 0.5 = useless)")
    for pos, neg, name, higher in PAIRS:
        a = auc(np.array(values[pos][name]), np.array(values[neg][name]), higher)
        print(f"  {pos} vs {neg}  {name:<13}{'(high = ' + pos + ')' if higher else '(low = ' + pos + ')':<16}{a:6.3f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
