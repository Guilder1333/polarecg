"""Review one night with several independent checks; the owner decides on the phone (plan P7.1e).

    python analysis/review_night.py build  data/sessions/<session>      # checks for every beat + review queue
    python analysis/review_night.py next   data/sessions/<session>      # next undecided beat: checks + open on the phone
    python analysis/review_night.py status data/sessions/<session>      # decisions so far against each check

The owner's earlier labels are not trusted (2026-11-20), so nothing here learns from them. Instead every
beat the app detected gets six checks that do not depend on each other:

* **app**      the app's class (N, S/SS, V, Q -> X) and its "questionable V" flag;
* **model**    the learned model (all seeds of a multi-seed run, probabilities averaged) and whether all
               seeds agree: on the test split the beats where they disagree hold 31 % errors (P7.1e item 4);
* **timing**   how early the beat is and whether the pause after it is compensatory (early + pause = about
               two normal intervals: a PVC's typical sign) or resets the rhythm (a PAC's typical sign);
* **shape**    correlation (-100..+200 ms) with the median of the clean normal beats around it, and its height
               compared with them;
* **strap**    whether the H10's own RR intervals pair with ours before and after the beat
               (``compare_rr.pair_intervals``): a false detection splits one strap interval into two that pair
               with nothing;
* **motion**   the app's accelerometer movement at the beat.

Two kinds of stretches are left out of the review, because single beats there cannot be judged
(owner, 2026-11-20): **crowded** stretches (3 or more impossibly short intervals within a few seconds: a
burst of noise or movement, not beats; one false detection between two real beats gives only 2 short
intervals and stays reviewable) and **missed beats** (a long interval of ours that the strap does not
have: the strap counted beats there that the app skipped, so the RR-based checks around it mean nothing).
They are counted in the summary instead.

Beats where the checks disagree are sorted into groups (``GROUPS``); a few of each group, spread over the
night, form the review queue, plus a few beats where all checks agree (to catch checks that are wrong
together). ``next`` opens the beat on the phone (``polarecg://beat/<utcMs>``); the owner labels it there
(N / S / SS / V / Q / X / ?) and presses Accept. Decisions are read back from the phone's ``label`` table;
only rows written after the review was built count, so earlier labels play no part.

Files: ``data/review/<session>/`` (git-ignored): ``checks.csv`` (all beats), ``queue.csv``, ``review.json``
and ``phone/`` (the copy of the phone database the decisions were read from).
"""
from __future__ import annotations

import argparse
import csv
import json
import sqlite3
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from compare_rr import pair_intervals  # noqa: E402
from polar_raw import read_session  # noqa: E402
import train_beats as tb  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
REVIEW = ROOT / "data" / "review"
DEFAULT_MODEL = ROOT / "data" / "models" / "20261120-230114"
PACKAGE = "com.punkunpun.polarecg"

EARLY = 0.85            # rr_pre / usual RR at or below this: early
COMPENSATORY = 1.9      # early + pause after >= this (in usual RRs): compensatory
RESETS = 1.85           # <= this: the rhythm was reset
SHAPE_NORMAL = 0.9      # correlation with the nearby normal beats
SHAPE_DIFFERENT = 0.6
MOVING_MG = 30.0        # as compare_rr.py
TEMPLATE_SIDE = 15      # clean normal beats taken on each side for the local template
SHAPE_SPAN = (-13, 26)  # samples around the beat for shape: -100 .. +200 ms at 130 Hz
SHORT_RR = 0.5          # an interval below this x the usual one (or below 300 ms) is impossibly short
SHORT_MIN_MS = 300
CROWDED_COUNT = 3       # this many short intervals ...
CROWDED_WINDOW_MS = 3000  # ... within this time: a crowded stretch
LONG_RR = 1.7           # an interval above this x usual that the strap does not have: missed beats
STRETCH_MARGIN_MS = 2000  # beats this close to a stretch are left out too (their RR and windows touch it)
NEAR_MS = 600           # a decision this close to a queue beat is reported by `next`

APP_VOTE = {"N": "N", "S": "S", "SS": "S", "V": "V", "Q": "X"}

# Review groups, in priority order: a beat goes into the first group it fits.
GROUPS = (
    ("model V, app not V", lambda c: c["model"] == "V" and c["app"] != "V"),
    ("app V, model not V", lambda c: c["app"] == "V" and c["model"] != "V"),
    ("model noise, app beat", lambda c: c["model"] == "X" and c["app"] != "X"),
    ("app noise, model beat", lambda c: c["app"] == "X" and c["model"] != "X"),
    ("strap: no beat", lambda c: c["strap"] == "no beat"),
    ("seeds disagree", lambda c: not c["seedsAgree"]),
    ("all N, shape different", lambda c: c["app"] == "N" and c["model"] == "N" and c["shape"] == "different"),
)
AGREED = (("agreed N", "N"), ("agreed S", "S"), ("agreed V", "V"), ("agreed noise", "X"))


# ---------------------------------------------------------------- checks (pure)

def timing_verdict(pre: float, post: float) -> tuple[str, str]:
    """(``early`` / ``on time``, pause: ``compensatory`` / ``resets`` / ``between`` / ``-``) from the RR
    ratios to the usual interval before (``pre``) and after (``post``) the beat."""
    if pre > EARLY:
        return "on time", "-"
    total = pre + post
    if total >= COMPENSATORY:
        return "early", "compensatory"
    if total <= RESETS:
        return "early", "resets"
    return "early", "between"


def shape_verdict(corr: float) -> str:
    if not np.isfinite(corr):
        return "-"
    return "normal" if corr >= SHAPE_NORMAL else "different" if corr <= SHAPE_DIFFERENT else "between"


def strap_verdict(pre_paired: bool | None, post_paired: bool | None) -> str:
    """``beat`` if the strap has both intervals, ``no beat`` if neither, else ``unclear``
    (None = no interval on that side, e.g. at a segment edge)."""
    known = [p for p in (pre_paired, post_paired) if p is not None]
    if not known:
        return "-"
    if all(known) and len(known) == 2:
        return "beat"
    if not any(known):
        return "no beat"
    return "unclear"


def mark_stretches(t_ms: np.ndarray, seg: np.ndarray, usual_ms: np.ndarray, pre_paired: list) -> list[str]:
    """Per beat: "crowded", "missed beats" or "" (see the module docstring).

    ``t_ms`` beat times, ``seg`` segment per beat, ``usual_ms`` the usual RR at each beat, ``pre_paired[i]``
    whether the interval ending at beat i paired with a strap interval (None: no interval)."""
    n = len(t_ms)
    out = [""] * n
    if n < 2:
        return out
    same = seg[1:] == seg[:-1]
    rr = np.diff(t_ms).astype(float)
    usual = usual_ms[1:]
    short_end = np.flatnonzero(same & (rr < np.maximum(SHORT_MIN_MS, SHORT_RR * usual))) + 1
    long_end = [e for e in np.flatnonzero(same & (rr > LONG_RR * usual)) + 1 if pre_paired[e] is False]
    spans = []
    st = t_ms[short_end].astype(float)
    for k in range(len(short_end)):
        j = np.searchsorted(st, st[k] + CROWDED_WINDOW_MS, side="right")
        if j - k >= CROWDED_COUNT:
            spans.append((st[k] - rr[short_end[k] - 1], st[j - 1], "crowded"))
    for e in long_end:
        spans.append((float(t_ms[e - 1]), float(t_ms[e]), "missed beats"))
    for lo, hi, kind in spans:
        a = np.searchsorted(t_ms, lo - STRETCH_MARGIN_MS, side="left")
        b = np.searchsorted(t_ms, hi + STRETCH_MARGIN_MS, side="right")
        for i in range(a, b):
            if out[i] != "crowded":          # crowded wins when both apply
                out[i] = kind
    return out


def stretch_summary(t_ms: np.ndarray, marks: list[str]) -> dict[str, tuple[int, float]]:
    """Per kind: (number of stretches = runs of consecutive marked beats, their total length in minutes)."""
    out = {}
    for kind in ("crowded", "missed beats"):
        runs, total, start = 0, 0.0, None
        for i, m in enumerate(marks + [""]):
            if m == kind and start is None:
                start = i
            elif m != kind and start is not None:
                runs += 1
                total += (t_ms[i - 1] - t_ms[start]) / 60000
                start = None
        out[kind] = (runs, total)
    return out


def group_of(check: dict) -> str | None:
    for name, fits in GROUPS:
        if fits(check):
            return name
    return None


def agreed_class(check: dict) -> str | None:
    """The class all checks agree on, or None. S: app S with model N (the model has no S class)."""
    if not check["seedsAgree"] or check["strap"] == "no beat":
        return None
    app, model = check["app"], check["model"]
    if app == "X" and model == "X":
        return "X"
    if check["strap"] != "beat" or check["motion"] == "moving":
        return None
    if app == "N" and model == "N" and check["shape"] == "normal" and check["timing"] == "on time":
        return "N"
    if app == "S" and model == "N" and check["timing"] == "early" and check["pause"] == "resets":
        return "S"
    if app == "V" and model == "V" and check["shape"] == "different" and check["timing"] == "early":
        return "V"
    return None


def pick_spread(indices: np.ndarray, k: int, rng: np.random.Generator) -> np.ndarray:
    """Up to ``k`` of ``indices`` (in time order), one at random from each of k equal stretches: spread over the night."""
    if len(indices) <= k:
        return np.asarray(indices)
    parts = np.array_split(np.asarray(indices), k)
    return np.array([p[rng.integers(len(p))] for p in parts])


def build_queue(checks: list[dict], per_group: int, per_agreed: int, seed: int = 0) -> list[dict]:
    """The review items: up to ``per_group`` of each disagreement group, then ``per_agreed`` of each agreed class."""
    rng = np.random.default_rng(seed)
    out = []
    groups = [(name, lambda c, n=name: c["group"] == n, per_group) for name, _ in GROUPS]
    groups += [(name, lambda c, k=cls: c["group"] == "" and c["agreed"] == k, per_agreed) for name, cls in AGREED]
    for name, fits, k in groups:
        idx = np.array([i for i, c in enumerate(checks) if fits(c)], dtype=np.int64)
        for i in sorted(pick_spread(idx, k, rng).tolist()):
            out.append({"item": len(out) + 1, "group": name, "utcMs": checks[i]["utcMs"]})
    return out


def latest_decisions(rows, since_ms: int) -> dict[int, str]:
    """beat utcMs -> the owner's latest label among rows written at or after ``since_ms``
    (``rows``: (beatUtcMs, label, createdAtUtcMs) in insertion order; "" = undone)."""
    out: dict[int, str] = {}
    for beat, label, created in rows:
        if created >= since_ms:
            out[int(beat)] = label
    return {b: lab for b, lab in out.items() if lab}


# ---------------------------------------------------------------- computing the checks

def local_shape(x: np.ndarray, clean: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Correlation and height ratio of each beat with the median of the clean normal beats around it."""
    a, b = tb.PRE + SHAPE_SPAN[0], tb.PRE + SHAPE_SPAN[1]
    w = x[:, 0, a:b].astype(np.float64)
    clean_idx = np.flatnonzero(clean)
    corr = np.full(len(x), np.nan)
    height = np.full(len(x), np.nan)
    if len(clean_idx) < 5:
        return corr, height
    pos = np.searchsorted(clean_idx, np.arange(len(x)))
    for i in range(len(x)):
        lo, hi = max(0, pos[i] - TEMPLATE_SIDE), min(len(clean_idx), pos[i] + TEMPLATE_SIDE + 1)
        near = clean_idx[lo:hi]
        near = near[near != i]
        if len(near) < 5:
            continue
        t = np.median(w[near], axis=0)
        if w[i].std() > 0 and t.std() > 0:
            corr[i] = float(np.corrcoef(w[i], t)[0, 1])
        height[i] = float(np.abs(w[i]).max() / max(np.abs(t).max(), 1e-6))
    return corr, height


def strap_pairs(beats: list[dict], session: Path) -> tuple[list, list]:
    """Per beat: is our interval before / after it paired with a strap interval (None = no interval there)."""
    hr = read_session(session).hr
    t = np.array([float(b["utcMs"]) for b in beats])
    seg = np.array([int(b["segment"]) for b in beats])
    same = seg[1:] == seg[:-1]
    ends = np.flatnonzero(same) + 1                      # beat index at the end of each interval
    pre: list = [None] * len(beats)
    post: list = [None] * len(beats)
    if len(hr.rr_ms) == 0 or len(ends) == 0:
        return pre, post
    paired = pair_intervals(t[ends], t[ends] - t[ends - 1], np.asarray(hr.rr_phone_ms, float),
                            np.asarray(hr.rr_ms, float))
    for e, p in zip(ends, paired):
        pre[e] = bool(p >= 0)
        post[e - 1] = bool(p >= 0)
    return pre, post


def model_votes(run: Path, x: np.ndarray, rr: np.ndarray):
    """(averaged probabilities over the seeds, per-seed classes) with the run's class list."""
    import torch
    import classify_session as cs
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    probs, votes, classes = 0.0, [], None
    dirs = tb.model_dirs(run)
    for d in dirs:
        model, classes = cs.load_model(d, device)
        p = cs.predict_proba(model, x, rr, device, len(classes))
        probs = probs + p / len(dirs)
        votes.append(p.argmax(1))
    if tuple(classes) != tb.CLASSES:
        raise SystemExit(f"the review needs an N/V/X model, {run} has {classes}")
    return np.asarray(probs), np.stack(votes)


def jst_offset(session: Path) -> timedelta:
    """The session's local time offset, from session.json (startUtc vs startJst)."""
    try:
        s = json.loads((session / "session.json").read_text(encoding="utf-8"))
        utc = datetime.strptime(s["startUtc"][:19], "%Y-%m-%dT%H:%M:%S")
        local = datetime.strptime(s["startJst"][:19], "%Y-%m-%d %H:%M:%S")
        return timedelta(minutes=round((local - utc).total_seconds() / 60))
    except (OSError, KeyError, ValueError):
        return timedelta(hours=9)


def local_time(utc_ms: int, offset: timedelta) -> str:
    return (datetime.fromtimestamp(utc_ms / 1000, timezone.utc) + offset).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]


def compute_checks(session: Path, run: Path) -> list[dict]:
    import classify_session as cs
    beats = cs.read_beats(session)
    x, rr, seg_of = cs.session_windows(session, beats)
    probs, votes = model_votes(run, x, rr)
    model = probs.argmax(1)
    agree = (votes == votes[0]).all(0)
    app = [APP_VOTE.get(b["class"], "?") for b in beats]
    good = np.array([b.get("good") == "1" for b in beats])
    clean = (np.array(app) == "N") & (model == 0) & agree & good & (seg_of >= 0)
    corr, height = local_shape(x, clean)
    pre_p, post_p = strap_pairs(beats, session)
    t_ms = np.array([int(b["utcMs"]) for b in beats], dtype=np.int64)
    stretch = mark_stretches(t_ms, np.array([int(b["segment"]) for b in beats]), rr[:, 3] * 1000.0, pre_p)
    checks = []
    for i, b in enumerate(beats):
        timing, pause = timing_verdict(float(rr[i, 0]), float(rr[i, 1]))
        motion = float(b["motionMg"]) if b.get("motionMg") not in (None, "") else float("nan")
        c = {
            "utcMs": int(b["utcMs"]), "appClass": b["class"], "app": app[i], "vDoubt": b.get("vDoubt", ""),
            "model": tb.CLASSES[model[i]], "pN": probs[i, 0], "pV": probs[i, 1], "pX": probs[i, 2],
            "seeds": "".join(tb.CLASSES[v] for v in votes[:, i]), "seedsAgree": bool(agree[i]),
            "rrPre": float(rr[i, 0]), "rrPost": float(rr[i, 1]), "timing": timing, "pause": pause,
            "corr": corr[i], "height": height[i], "shape": shape_verdict(corr[i]),
            "strap": strap_verdict(pre_p[i], post_p[i]), "motionMg": motion,
            "motion": "moving" if motion >= MOVING_MG else "still" if np.isfinite(motion) else "-",
            "inEcg": bool(seg_of[i] >= 0), "stretch": stretch[i],
        }
        if not c["inEcg"] or c["stretch"]:
            c["group"], c["agreed"] = "", ""
        else:
            c["group"] = group_of(c) or ""
            c["agreed"] = (agreed_class(c) or "") if not c["group"] else ""
        checks.append(c)
    return checks


# ---------------------------------------------------------------- phone

def adb(*args: str, binary: bool = False):
    r = subprocess.run(["adb", *args], capture_output=True)
    if r.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed: {r.stderr.decode(errors='replace').strip()}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


def pull_phone_labels(dest: Path) -> list[tuple[int, str, int]]:
    """Copy the phone's database (with its write-ahead log, where the newest rows are) and read the label table."""
    dest.mkdir(parents=True, exist_ok=True)
    for name in ("polar.db", "polar.db-wal"):
        r = subprocess.run(["adb", "exec-out", "run-as", PACKAGE, "cat", f"databases/{name}"], capture_output=True)
        if r.returncode != 0 or (name == "polar.db" and not r.stdout.startswith(b"SQLite format 3")):
            if name == "polar.db":
                raise SystemExit("could not copy the phone database (is the phone connected, `adb devices`?)")
            (dest / name).unlink(missing_ok=True)
            continue
        (dest / name).write_bytes(r.stdout)
    (dest / "polar.db-shm").unlink(missing_ok=True)
    con = sqlite3.connect(dest / "polar.db")
    try:
        return con.execute("SELECT beatUtcMs, label, createdAtUtcMs FROM label ORDER BY id").fetchall()
    finally:
        con.close()


def open_on_phone(utc_ms: int) -> None:
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", f"polarecg://beat/{utc_ms}")


# ---------------------------------------------------------------- commands

def folder_of(session: Path) -> Path:
    return REVIEW / session.name


def fmt(v, spec=".2f") -> str:
    return "-" if v is None or (isinstance(v, float) and not np.isfinite(v)) else format(v, spec)


def describe(c: dict, offset: timedelta) -> str:
    pause_sum = c["rrPre"] + c["rrPost"]
    lines = [
        f"  app      {c['appClass']}" + ("  (questionable V)" if c["vDoubt"] == "1" else ""),
        f"  model    {c['model']}  N {c['pN']:.2f} / V {c['pV']:.2f} / noise {c['pX']:.2f}   seeds {c['seeds']}"
        + ("" if c["seedsAgree"] else "  (disagree)"),
        f"  timing   {c['timing']}: before {c['rrPre']:.2f}, after {c['rrPost']:.2f} of the usual interval, "
        f"sum {pause_sum:.2f}" + (f" -> {c['pause']}" if c["pause"] != "-" else ""),
        f"  shape    {c['shape']}: correlation with the normal beats around it {fmt(c['corr'])}, "
        f"height {fmt(c['height'])} x theirs",
        f"  strap    {c['strap']}",
        f"  motion   {c['motion']} ({fmt(c['motionMg'], '.0f')} mG)",
    ]
    return f"{local_time(c['utcMs'], offset)}  (utcMs {c['utcMs']})\n" + "\n".join(lines)


def read_csv(path: Path) -> list[dict]:
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def load_checks(folder: Path) -> dict[int, dict]:
    out = {}
    for r in read_csv(folder / "checks.csv"):
        for k in ("pN", "pV", "pX", "rrPre", "rrPost", "corr", "height", "motionMg"):
            r[k] = float(r[k]) if r[k] not in ("", "nan") else float("nan")
        r["seedsAgree"] = r["seedsAgree"] == "True"
        r["utcMs"] = int(r["utcMs"])
        out[r["utcMs"]] = r
    return out


def cmd_build(args) -> int:
    folder = folder_of(args.session)
    folder.mkdir(parents=True, exist_ok=True)
    meta_path = folder / "review.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.exists() else {}
    t0 = time.time()
    checks = compute_checks(args.session, args.model)
    keys = list(checks[0].keys())
    with open(folder / "checks.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, keys)
        w.writeheader()
        for c in checks:
            w.writerow({k: (f"{v:.4f}" if isinstance(v, float) else v) for k, v in c.items()})
    queue = build_queue(checks, args.per_group, args.per_agreed, args.seed)
    with open(folder / "queue.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, ["item", "group", "utcMs"])
        w.writeheader()
        w.writerows(queue)
    sizes = {name: sum(c["group"] == name for c in checks) for name, _ in GROUPS}
    sizes.update({name: sum(c["agreed"] == k for c in checks) for name, k in AGREED})
    in_ecg = sum(c["inEcg"] for c in checks)
    stretches = stretch_summary(np.array([c["utcMs"] for c in checks]), [c["stretch"] for c in checks])
    # Decisions count from the FIRST build on, so rebuilding (new model, other caps) keeps them.
    meta.setdefault("builtAtUtcMs", int(time.time() * 1000))
    meta.update({"session": args.session.name, "model": str(args.model), "beats": len(checks), "inEcg": in_ecg,
                 "groupSizes": sizes, "queue": len(queue),
                 "stretches": {k: {"count": n, "minutes": round(m, 1)} for k, (n, m) in stretches.items()}, "lastBuildUtcMs": int(time.time() * 1000)})
    meta_path.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(f"{len(checks)} beats ({in_ecg} with ECG), checks in {time.time() - t0:.0f}s -> {folder}")
    print("\nleft out (single beats cannot be judged there):")
    for kind, (n, minutes) in stretches.items():
        beats_in = sum(c["stretch"] == kind for c in checks)
        print(f"  {kind:<26}{n:>5} stretches, {minutes:.1f} min, {beats_in} beats (incl. 2 s margin)")
    print("\nbeats per group (the queue takes a few of each, spread over the night):")
    for name, n in sizes.items():
        print(f"  {name:<26}{n:>7}   in queue {sum(q['group'] == name for q in queue)}")
    rest = in_ecg - sum(sizes.values()) - sum(c["stretch"] != "" for c in checks)
    print(f"  {'no disagreement, not all agreeing':<26}{rest:>7}   (not reviewed)")
    print(f"\nqueue: {len(queue)} beats. Next: python analysis/review_night.py next {args.session}")
    return 0


def decisions_for(args, folder: Path) -> dict[int, str]:
    meta = json.loads((folder / "review.json").read_text(encoding="utf-8"))
    rows = pull_phone_labels(folder / "phone")
    return latest_decisions(rows, meta["builtAtUtcMs"])


def read_skipped(folder: Path) -> set[int]:
    path = folder / "skipped.txt"
    return {int(x) for x in path.read_text(encoding="utf-8").split()} if path.exists() else set()


def nearest_decision(utc_ms: int, decided: dict[int, str]) -> tuple[int, str] | None:
    """(signed distance in ms, label) of the closest decided beat within NEAR_MS, other than the beat itself."""
    near = [(b - utc_ms, lab) for b, lab in decided.items() if b != utc_ms and abs(b - utc_ms) <= NEAR_MS]
    return min(near, key=lambda d: abs(d[0])) if near else None


def cmd_next(args) -> int:
    folder = folder_of(args.session)
    queue = read_csv(folder / "queue.csv")
    checks = load_checks(folder)
    decided = decisions_for(args, folder)
    offset = jst_offset(args.session)
    skipped = read_skipped(folder)
    if args.skip:
        first = next((q for q in queue if int(q["utcMs"]) not in decided and int(q["utcMs"]) not in skipped), None)
        if first:
            skipped.add(int(first["utcMs"]))
            (folder / "skipped.txt").write_text("\n".join(map(str, sorted(skipped))) + "\n", encoding="utf-8")
            print(f"skipped item {first['item']}")
    todo = [q for q in queue if int(q["utcMs"]) not in decided and int(q["utcMs"]) not in skipped]
    done = len(queue) - len(todo)
    if not todo:
        print(f"all {len(queue)} beats of the queue are decided. See: python analysis/review_night.py status {args.session}")
        return 0
    q = todo[0]
    in_group = [r for r in queue if r["group"] == q["group"]]
    k = in_group.index(q) + 1
    print(f"[{done + 1}/{len(queue)}]  group \"{q['group']}\" ({k} of {len(in_group)})")
    print(describe(checks[int(q["utcMs"])], offset))
    near = nearest_decision(int(q["utcMs"]), decided)
    if near:
        print(f"\n  note: you marked a beat {near[0] / 1000:+.2f} s from this one ({near[1]}), not this beat itself.\n"
              f"  Mark the selected beat, or run `next --skip` to leave this one out.")
    if not args.no_open:
        open_on_phone(int(q["utcMs"]))
        print("\nopened on the phone: label it there (N S SS V Q X ?) and press Accept, then run `next` again.")
    return 0


def cmd_status(args) -> int:
    folder = folder_of(args.session)
    queue = read_csv(folder / "queue.csv")
    checks = load_checks(folder)
    decided = decisions_for(args, folder)
    skipped = read_skipped(folder)
    print(f"{sum(int(q['utcMs']) in decided for q in queue)} of {len(queue)} queue beats decided, "
          f"{sum(int(q['utcMs']) in skipped for q in queue)} skipped\n")
    print(f"{'group':<26}{'decided':>9}   owner's decisions")
    for name in [g for g, _ in GROUPS] + [g for g, _ in AGREED]:
        items = [q for q in queue if q["group"] == name]
        labs = [decided[int(q["utcMs"])] for q in items if int(q["utcMs"]) in decided]
        counts = ", ".join(f"{lab} {labs.count(lab)}" for lab in sorted(set(labs)))
        print(f"{name:<26}{len(labs):>5}/{len(items):<3}   {counts}")
    owner_vote = {"N": "N", "S": "S", "SS": "S", "V": "V", "X": "X"}
    judged = [(checks[int(q["utcMs"])], owner_vote[decided[int(q["utcMs"])]]) for q in queue
              if decided.get(int(q["utcMs"])) in owner_vote]
    if judged:
        print(f"\nhow often each check matched the owner (on {len(judged)} decided beats labelled N/S/SS/V/X):")
        app_ok = sum(c["app"] == o for c, o in judged)
        model_ok = sum((c["model"] == o) or (c["model"] == "N" and o == "S") for c, o in judged)
        print(f"  app class          {app_ok}/{len(judged)}")
        print(f"  model (S counts as N) {model_ok}/{len(judged)}")
        noise = [(c, o) for c, o in judged if c["strap"] in ("beat", "no beat")]
        if noise:
            ok = sum((c["strap"] == "no beat") == (o == "X") for c, o in noise)
            print(f"  strap beat/no beat {ok}/{len(noise)}")
    others = {lab for lab in decided.values() if lab not in owner_vote}
    if others:
        print(f"\nother marks: " + ", ".join(f"{lab} {list(decided.values()).count(lab)}" for lab in sorted(others)))
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(required=True)
    b = sub.add_parser("build", help="compute the checks and the review queue")
    b.add_argument("session", type=Path)
    b.add_argument("--model", type=Path, default=DEFAULT_MODEL, help="N/V/X run (multi-seed: all seeds are averaged)")
    b.add_argument("--per-group", type=int, default=12, help="beats per disagreement group in the queue")
    b.add_argument("--per-agreed", type=int, default=4, help="beats per class where all checks agree")
    b.add_argument("--seed", type=int, default=0)
    b.set_defaults(func=cmd_build)
    n = sub.add_parser("next", help="show the next undecided beat and open it on the phone")
    n.add_argument("session", type=Path)
    n.add_argument("--no-open", action="store_true", help="only print the checks")
    n.add_argument("--skip", action="store_true", help="leave out the current beat (cannot be judged) and go on")
    n.set_defaults(func=cmd_next)
    s = sub.add_parser("status", help="decisions so far, per group and against the checks")
    s.add_argument("session", type=Path)
    s.set_defaults(func=cmd_status)
    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
