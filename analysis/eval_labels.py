"""Our classifier against the owner's own labels (docs/LABELING_TOOL.md; P4.5 on own data).

    python analysis/eval_labels.py data/labels/20261021T162147Z
    python analysis/eval_labels.py data/labels/*          # pooled over several sessions
    python analysis/eval_labels.py data/labels/<session> --plot eval.png   # + picture

To go through the disagreements: python analysis/label_beats.py data/sessions/<session> --review

Counts are corrected for the sampling: every labelled item counts with the weight stored in
queue.csv (how many beats of the night it stands for). "unsure" (?) and Q labels are reported
separately and left out of Se / +P.
"""
from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from label_data import current_labels, read_label_rows, read_queue  # noqa: E402

CLASSES = ("N", "S", "V")


def current_classes(analysis: Path) -> dict[int, str]:
    """utcMs -> class in the latest `analyze` output (beats.csv); empty if there is none."""
    import csv
    path = analysis / "beats.csv"
    if not path.is_file():
        return {}
    with path.open(newline="", encoding="utf-8") as f:
        return {int(r["utcMs"]): r["class"] for r in csv.DictReader(f)}


def labelled_items(session_dir: Path, analysis: Path | None = None) -> list[tuple]:
    """(queue item, label) for every queue item that has a current label.

    With ``analysis`` (the `analyze` output folder of the session) the beat items carry the CURRENT class of
    that output instead of the one frozen in queue.csv, so that a changed classifier can be evaluated on the
    same labels. The sampling weights stay valid: they only describe how the items were drawn."""
    queue = read_queue(session_dir / "queue.csv")
    if analysis is not None:
        now = current_classes(analysis)
        queue = [replace(q, our_class=now.get(q.utc_ms, q.our_class)) if q.kind == "BEAT" else q for q in queue]
    current = current_labels(read_label_rows(session_dir / "labels.csv"))
    return [(q, current[q.item]["label"]) for q in queue if q.item in current]


def confusion(pairs: list[tuple], weighted: bool = True) -> dict[tuple[str, str], float]:
    """(our class, owner's label) -> weighted count, for BEAT items."""
    out: dict[tuple[str, str], float] = defaultdict(float)
    for q, label in pairs:
        if q.kind == "BEAT":
            out[(q.our_class, label)] += q.weight if weighted else 1
    return dict(out)


def se_ppv(conf: dict[tuple[str, str], float], cls: str) -> tuple[float | None, float | None]:
    """Sensitivity and positive predictive value of ``cls`` against the owner's N/S/V labels.
    Se = our cls among the beats the owner called cls; +P = owner called it cls among our cls beats
    that the owner judged (not ? or Q)."""
    tp = conf.get((cls, cls), 0.0)
    owner_cls = sum(w for (o, l), w in conf.items() if l == cls)
    ours_judged = sum(w for (o, l), w in conf.items() if o == cls and l in CLASSES)
    return (tp / owner_cls if owner_cls else None, tp / ours_judged if ours_judged else None)


def gap_summary(pairs: list[tuple]) -> dict[str, float]:
    out: dict[str, float] = defaultdict(float)
    for q, label in pairs:
        if q.kind == "GAP":
            out[label] += q.weight
    return dict(out)


def disagreements(pairs: list[tuple]) -> list[tuple]:
    """BEAT items where our class and the owner's N/S/V label differ, in time order."""
    rows = [(q, label) for q, label in pairs
            if q.kind == "BEAT" and label in CLASSES and q.our_class != label]
    return sorted(rows, key=lambda p: p[0].utc_ms)


def v_doubt_split(pairs: list[tuple], analysis: Path) -> list[str]:
    """Our V items split by the `vDoubt` flag of beats.csv (P4.7), against your labels. Raw item counts
    (V items are all taken, weight 1). Empty if beats.csv has no vDoubt column."""
    import csv
    path = analysis / "beats.csv"
    if not path.is_file():
        return []
    with path.open(newline="", encoding="utf-8") as f:
        rows = {int(r["utcMs"]): r.get("vDoubt") for r in csv.DictReader(f)}
    if not any(rows.values()):
        return []
    out = ["our V items by the drift flag (vDoubt), against your label:"]
    for flag, name in (("0", "confident V"), ("1", "questionable V")):
        counts: dict[str, int] = defaultdict(int)
        for q, label in pairs:
            if q.kind == "BEAT" and q.our_class == "V" and rows.get(q.utc_ms) == flag:
                counts[label] += 1
        n_v, n_n = counts.get("V", 0), counts.get("N", 0)
        ppv = f"{100 * n_v / (n_v + n_n):.0f} %" if n_v + n_n else "-"
        out.append(f"  {name:15s} you said V {n_v}, N {n_n}, S {counts.get('S', 0)}, Q {counts.get('Q', 0)}, ? {counts.get('?', 0)}   (V among N/V: {ppv})")
    return out


def review_items(pairs: list[tuple]) -> list[tuple]:
    """What to look at again (label_beats.py --review): the disagreements and everything you marked
    unsure (?), beats and gap items, in time order."""
    rows = disagreements(pairs) + [(q, l) for q, l in pairs if l == "?"]
    return sorted(rows, key=lambda p: p[0].utc_ms)


def _pct(x: float | None) -> str:
    return "  -  " if x is None else f"{100 * x:5.1f}%"


def report(pairs: list[tuple], title: str) -> str:
    lines = [f"== {title}"]
    beats = [(q, l) for q, l in pairs if q.kind == "BEAT"]
    lines.append(f"labelled beats: {len(beats)}  (unsure {sum(1 for _, l in beats if l == '?')}, "
                 f"Q {sum(1 for _, l in beats if l == 'Q')})")
    conf, raw = confusion(pairs), confusion(pairs, weighted=False)
    labels = ("N", "S", "V", "Q", "?")
    lines.append("")
    lines.append("weighted confusion (rows: our class, columns: your label; counts of beats of the night)")
    lines.append("our \\ you  " + "".join(f"{l:>10}" for l in labels) + "   | raw items")
    for o in ("N", "S", "V", "Q"):
        row = "".join(f"{conf.get((o, l), 0.0):10.0f}" for l in labels)
        lines.append(f"{o:>9}  {row}   | " + " ".join(f"{int(raw.get((o, l), 0)):>4}" for l in labels))
    lines.append("")
    for cls in ("V", "S"):
        se, ppv = se_ppv(conf, cls)
        n_raw = sum(int(n) for (o, l), n in raw.items() if l == cls)
        lines.append(f"{cls}: Se {_pct(se)}   +P {_pct(ppv)}   ({n_raw} items labelled {cls})")
    gaps = gap_summary(pairs)
    if gaps:
        lines.append("")
        lines.append("gap items (weighted): " + ", ".join(
            f"{ {'B': 'blocked early beat', 'N': 'nothing', '?': 'unsure'}[k] } {v:.0f}" for k, v in sorted(gaps.items())))
    dis = disagreements(pairs)
    lines.append("")
    lines.append(f"disagreements ({len(dis)}); look at these together, the rule or the label may be wrong:")
    for q, label in dis:
        t = datetime.fromtimestamp(q.utc_ms / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
        lines.append(f"  {t}Z  ours {q.our_class}  you {label}")
    return "\n".join(lines)


def plot(pairs: list[tuple], title: str, path: Path) -> None:
    """Left: weighted confusion (our class x your label). Right: every labelled beat on the time line of
    the night, one row per our class; red = you disagree, so you can see WHEN the disagreements are."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.dates import DateFormatter
    beats = [(q, l) for q, l in pairs if q.kind == "BEAT"]
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(14, 5), gridspec_kw={"width_ratios": [1, 2]})
    rows, cols = ("N", "S", "V", "Q"), ("N", "S", "V", "Q", "?")
    conf = confusion(pairs)
    m = [[conf.get((o, l), 0.0) for l in cols] for o in rows]
    a1.imshow(m, cmap="Blues")
    a1.set_xticks(range(len(cols)), cols)
    a1.set_yticks(range(len(rows)), rows)
    a1.set_xlabel("your label")
    a1.set_ylabel("our class")
    a1.set_title("weighted beats of the night")
    for i, o in enumerate(rows):
        for j, l in enumerate(cols):
            if m[i][j]:
                a1.text(j, i, f"{m[i][j]:.0f}", ha="center", va="center", color="#d62728" if o != l and l in CLASSES else "black")
    y = {c: k for k, c in enumerate(rows)}
    for q, l in beats:
        t = datetime.fromtimestamp(q.utc_ms / 1000, timezone.utc)
        bad = l in CLASSES and q.our_class != l
        a2.scatter(t, y[q.our_class] + (0.18 if bad else 0), s=45 if bad else 14,
                   color="#d62728" if bad else ("#999999" if l == "?" else "#2a9d3f"), zorder=3 if bad else 2)
    a2.set_yticks(range(len(rows)), [f"ours {c}" for c in rows])
    a2.xaxis.set_major_formatter(DateFormatter("%H:%M"))
    a2.set_xlabel("UTC time")
    a2.set_title("green = agree, red = you disagree, grey = unsure   (open them with --review)")
    a2.grid(alpha=0.3)
    fig.suptitle(title)
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    plt.close(fig)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sessions", nargs="+", type=Path, help="label folders data/labels/<session>")
    ap.add_argument("--plot", type=Path, help="also write a PNG: confusion table and time line (one per session: name-<session>)")
    ap.add_argument("--frozen-classes", action="store_true",
                    help="use the class stored in queue.csv (the classifier of the day the queue was built) "
                         "instead of the current analyze output")
    args = ap.parse_args(argv)
    pooled: list[tuple] = []
    for s in args.sessions:
        if not (s / "queue.csv").is_file():
            print(f"skipping {s}: no queue.csv")
            continue
        analysis = Path("data/analysis") / f"sessions-{s.name}"        # where the CLI puts it
        pairs = labelled_items(s, None if args.frozen_classes else analysis)
        pooled += pairs
        print(report(pairs, s.name))
        split = v_doubt_split(pairs, analysis)
        print("\n".join(split), end="\n\n" if split else "")
        print()
        if args.plot:
            out = args.plot if len(args.sessions) == 1 else args.plot.with_name(f"{args.plot.stem}-{s.name}{args.plot.suffix}")
            plot(pairs, s.name, out)
            print(f"wrote {out}")
    if len(args.sessions) > 1:
        print(report(pooled, "pooled"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
