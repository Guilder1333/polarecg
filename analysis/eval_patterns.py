"""Compare the patterns we detect (P4.4) with the same patterns counted in the reference annotations.

    python analysis/eval_patterns.py data/reference/mitdb --exclude-paced

The reference sequence is the AAMI class of every annotated beat (N, S, V, F, Q); the rules are the
same as in ``dsp/pattern/Patterns.kt`` (couplet 2, triplet 3, run >= 4 in a row; bigeminy / trigeminy
>= 3 cycles with N in between; any other class ends a pattern). Our events come from
``data/analysis/<db>-<record>/events.csv``. An event of ours counts as found if a reference event of
the same type starts within ``--tolerance`` ms of it. Pauses are not compared (the reference has no
such annotation).
"""
from __future__ import annotations

import argparse
import csv
import sys
from collections import Counter
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from score_beats import find_records, load_reference  # noqa: E402

TYPES = ["COUPLET", "TRIPLET", "RUN", "BIGEMINY", "TRIGEMINY"]


def reference_events(times: np.ndarray, classes: list[str], min_cycles: int = 3) -> list[tuple[str, float]]:
    """(type, start ms) like the Kotlin detector, for V only (S is too rare in the reference to judge)."""
    out = []
    cls = "V"
    i = 0
    n = len(classes)
    while i < n:  # runs
        if classes[i] == cls:
            j = i
            while j + 1 < n and classes[j + 1] == cls:
                j += 1
            k = j - i + 1
            if k >= 2:
                out.append((("COUPLET", "TRIPLET")[k - 2] if k <= 3 else "RUN", times[i]))
            i = j + 1
        else:
            i += 1
    for period, name in ((2, "BIGEMINY"), (3, "TRIGEMINY")):
        i = 0
        while i < n:
            if classes[i] != cls:
                i += 1
                continue
            end, cycles = i, 1
            while end + period < n and all(classes[k] == "N" for k in range(end + 1, end + period)) \
                    and classes[end + period] == cls:
                end += period
                cycles += 1
            if cycles >= min_cycles:
                out.append((name, times[i]))
                i = end + 1
            else:
                i += 1
    return out


def our_events(folder: Path) -> list[tuple[str, float]]:
    out = []
    with open(folder / "events.csv", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["type"].startswith("V_"):
                out.append((row["type"][2:], float(row["utcMs"])))
    return out


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("paths", nargs="+", type=Path)
    p.add_argument("--analysis-root", type=Path, default=Path("data/analysis"))
    p.add_argument("--tolerance", type=float, default=2000.0, help="ms between starts to count as the same event")
    p.add_argument("--exclude-paced", action="store_true")
    args = p.parse_args(argv)

    ref_n, our_n, found, matched_ours = Counter(), Counter(), Counter(), Counter()
    for rec in find_records(args.paths, args.exclude_paced, None):
        folder = args.analysis_root / f"{rec.parent.name}-{rec.name}"
        if not (folder / "events.csv").exists():
            continue
        t, c = load_reference(rec)
        ref = reference_events(t, c)
        ours = our_events(folder)
        used = set()
        for typ, start in ref:
            ref_n[typ] += 1
            for j, (ot, os_) in enumerate(ours):
                if j not in used and ot == typ and abs(os_ - start) <= args.tolerance:
                    used.add(j)
                    found[typ] += 1
                    break
        for typ, _ in ours:
            our_n[typ] += 1
        for j in used:
            matched_ours[ours[j][0]] += 1

    print(f"{'type':<11}{'reference':>10}{'ours':>8}{'found':>8}{'Se%':>7}{'+P%':>7}")
    for typ in TYPES:
        se = 100 * found[typ] / ref_n[typ] if ref_n[typ] else float("nan")
        ppv = 100 * matched_ours[typ] / our_n[typ] if our_n[typ] else float("nan")
        print(f"{typ:<11}{ref_n[typ]:>10}{our_n[typ]:>8}{found[typ]:>8}{se:>7.0f}{ppv:>7.0f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
