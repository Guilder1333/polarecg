"""Compare our RR intervals with the H10's own RR stream (P3.6).

    python analysis/compare_rr.py data/sessions/20261026T110150Z
    python analysis/compare_rr.py data/sessions/20261026T110150Z --plot rr.png --worst 15

The strap sends RR intervals (1/1024 s resolution, already converted to ms) about once a second,
without per-beat timestamps. So the two sequences have to be paired first: this script walks
through our intervals in order and, for each, looks only at strap intervals that arrived at
about the same time (phone clock), then picks the best monotonic pairing with a small dynamic
programme (Viterbi). Beats one side missed or invented simply stay unpaired.

For the pairs it reports how far our interval is from the strap's (ours - strap, in ms):
the headline number is the median absolute difference on pairs where both our beats have good
signal quality (plan §9 target: < 5 ms). Needs `analyze` to have run first
(``data/analysis/<parent>-<folder>/beats.csv``).
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
from polaris_raw import read_session  # noqa: E402

# Strap intervals considered for our interval ending at t: those that arrived in this window
# around t (ms, phone clock). Notifications come ~1 s after the beat, sometimes batched later.
WINDOW_BEFORE_MS = 500
WINDOW_AFTER_MS = 3500
# Pairs further apart than this are not accepted as the same interval.
MAX_PAIR_DIFF_MS = 80.0
# Cost (ms) of leaving one of our intervals unpaired / of skipping one strap interval.
UNPAIRED_COST = 80.0
SKIP_COST = 30.0
MAX_STATES = 40
# Intervals outside this range are not physiological (or are a missed beat) and are not judged.
RR_MIN_MS, RR_MAX_MS = 300, 2000


def our_intervals(beats: pd.DataFrame) -> pd.DataFrame:
    """One row per interval between consecutive beats of the same segment.

    Columns: ``t`` (UTC ms of the later beat), ``rr`` (ms), ``good`` (both beats good).
    """
    b = beats.reset_index(drop=True)
    same = b.segment.to_numpy()[1:] == b.segment.to_numpy()[:-1]
    t = b.utcMs.to_numpy(float)
    good = b.good.to_numpy() == 1
    out = pd.DataFrame({
        "t": t[1:][same],
        "rr": np.diff(t)[same],
        "good": (good[1:] & good[:-1])[same],
    })
    if "motionMg" in b:
        out["motionMg"] = b.motionMg.to_numpy(float)[1:][same]
    return out.reset_index(drop=True)


def pair_intervals(our_t, our_rr, strap_t, strap_rr) -> np.ndarray:
    """For each of our intervals the index of the paired strap interval, or -1.

    ``strap_t`` (arrival time) must be non-decreasing, ``our_t`` ascending. Pairs are monotonic:
    if interval i is paired with j, a later interval is only paired with something after j.
    """
    n = len(our_t)
    lo = np.searchsorted(strap_t, np.asarray(our_t) - WINDOW_BEFORE_MS, side="left")
    hi = np.searchsorted(strap_t, np.asarray(our_t) + WINDOW_AFTER_MS, side="right")

    # state = index of the last paired strap interval (-1 = none yet) -> (cost, back-pointer)
    states: dict[int, float] = {-1: 0.0}
    history: list[dict[int, tuple[int, int]]] = []  # per interval: new state -> (previous state, paired j or -1)
    for i in range(n):
        new: dict[int, tuple[float, int, int]] = {}
        for p, c in states.items():
            cost = c + UNPAIRED_COST  # leave interval i unpaired: the state stays
            if p not in new or cost < new[p][0]:
                new[p] = (cost, p, -1)
            for j in range(max(lo[i], p + 1), hi[i]):
                diff = abs(our_rr[i] - strap_rr[j])
                if diff > MAX_PAIR_DIFF_MS:
                    continue
                skipped = (j - p - 1) if p >= 0 else 0
                cost = c + diff + SKIP_COST * min(skipped, 4)
                if j not in new or cost < new[j][0]:
                    new[j] = (cost, p, j)
        best = sorted(new.items(), key=lambda kv: kv[1][0])[:MAX_STATES]
        states = {s: v[0] for s, v in best}
        history.append({s: (v[1], v[2]) for s, v in best})

    paired = np.full(n, -1, dtype=int)
    if not states:
        return paired
    state = min(states, key=states.get)
    for i in range(n - 1, -1, -1):
        prev, j = history[i][state]
        paired[i] = j
        state = prev
    return paired


def compare(ours: pd.DataFrame, strap_t, strap_rr) -> pd.DataFrame:
    """The pairing as a table: our interval, the strap's, and the difference."""
    paired = pair_intervals(ours.t.to_numpy(), ours.rr.to_numpy(), np.asarray(strap_t, float), np.asarray(strap_rr, float))
    df = ours.copy()
    df["strapRr"] = np.where(paired >= 0, np.asarray(strap_rr, float)[np.maximum(paired, 0)], np.nan)
    df["diff"] = df.rr - df.strapRr
    return df


def summarize(name: str, d: pd.Series) -> str:
    if len(d) == 0:
        return f"{name:<34}      0"
    a = d.abs()
    return (f"{name:<34}{len(d):7d}  median |d| {a.median():5.1f}  p90 {a.quantile(.9):5.1f}  p99 {a.quantile(.99):5.1f}"
            f"  bias {d.mean():+5.1f}  std {d.std():5.1f}")


def analysis_folder(session: Path, root: Path) -> Path:
    session = session.resolve()
    return root / f"{session.parent.name}-{session.name}"


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("session", type=Path, help="session folder with hr*.bin files")
    p.add_argument("--analysis", type=Path, help="analysis folder (default data/analysis/<parent>-<folder>)")
    p.add_argument("--worst", type=int, default=10, help="list the N largest disagreements on good pairs")
    p.add_argument("--plot", type=Path, help="write a PNG with the difference histogram and its course over time")
    p.add_argument("--csv", type=Path, help="write all pairs to this CSV")
    args = p.parse_args(argv)

    folder = args.analysis or analysis_folder(args.session, Path("data/analysis"))
    beats = pd.read_csv(folder / "beats.csv")
    if "good" not in beats:
        print("beats.csv has no quality columns: run `analyze` again", file=sys.stderr)
        return 1
    hr = read_session(args.session).hr
    strap_rr = np.asarray(hr.rr_ms, float)
    strap_t = np.asarray(hr.rr_phone_ms, float)
    if len(strap_rr) == 0:
        print("no RR intervals in the session", file=sys.stderr)
        return 1

    ours = our_intervals(beats)
    df = compare(ours, strap_t, strap_rr)
    paired = df.strapRr.notna()
    plausible = df.rr.between(RR_MIN_MS, RR_MAX_MS) & df.strapRr.between(RR_MIN_MS, RR_MAX_MS)

    print(f"our intervals {len(df)}, strap intervals {len(strap_rr)}, paired {paired.sum()} "
          f"({100 * paired.mean():.1f} % of ours, {100 * paired.sum() / len(strap_rr):.1f} % of the strap's)")
    print("difference = ours - strap, in ms")
    print(summarize("all pairs", df["diff"][paired & plausible]))
    print(summarize("both beats good", df["diff"][paired & plausible & df.good]))
    print(summarize("a beat flagged poor", df["diff"][paired & plausible & ~df.good]))
    if "motionMg" in df:
        calm = df.motionMg < 30
        print(summarize("good, motion < 30 mG", df["diff"][paired & plausible & df.good & calm]))
        print(summarize("good, motion >= 30 mG", df["diff"][paired & plausible & df.good & ~calm]))
    good = df[paired & plausible & df.good]
    for lo_, hi_ in [(300, 600), (600, 800), (800, 1000), (1000, 2001)]:
        print(summarize(f"good, RR {lo_}-{hi_ - 1} ms", good["diff"][good.strapRr.between(lo_, hi_ - 1)]))
    within = (good["diff"].abs() <= 5).mean() * 100 if len(good) else float("nan")
    print(f"good pairs within 5 ms: {within:.1f} %   (target: median |d| < 5 ms)")

    if args.worst and len(good):
        print(f"\nlargest disagreements on good pairs (open with view_ecg.py --at <time>):")
        worst = good.reindex(good["diff"].abs().sort_values(ascending=False).index).head(args.worst)
        for _, r in worst.iterrows():
            when = pd.to_datetime(r.t, unit="ms", utc=True).strftime("%H:%M:%S")
            print(f"  {when}  ours {r.rr:6.0f}  strap {r.strapRr:6.0f}  diff {r['diff']:+6.0f}")

    if args.csv:
        args.csv.parent.mkdir(parents=True, exist_ok=True)
        df.to_csv(args.csv, index=False)
    if args.plot:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(1, 2, figsize=(13, 4.5))
        ax[0].hist(good["diff"].clip(-30, 30), bins=np.arange(-30.5, 31.5, 1))
        ax[0].set(title="ours - strap RR, good pairs (clipped to ±30 ms)", xlabel="ms", ylabel="intervals")
        hours = (good.t - good.t.iloc[0]) / 3.6e6
        ax[1].plot(hours, good["diff"], ",", alpha=.4)
        ax[1].set(title="difference over the recording", xlabel="hours", ylabel="ms", ylim=(-40, 40))
        fig.tight_layout()
        fig.savefig(args.plot, dpi=110)
        print(f"plot written to {args.plot}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
