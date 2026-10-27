"""Data side of the labeling tool (docs/LABELING_TOOL.md): queue building, append-only labels,
gallery picks and score. No GUI imports, so everything here is unit-tested on the PC.

Files (all under ``data/labels/``, git-ignored):
    <session>/queue.csv    items to label, built once from beats.csv + events.csv
    <session>/labels.csv   append-only; the LAST row per item wins; label "" = undone
    gallery.csv            answers in gallery mode
"""
from __future__ import annotations

import csv
import json
import math
import random
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

SEED = 20261027
LIMITS = {"V": 150, "S": 150, "N": 300, "Q": 50, "GAP": 100}   # how many items per group
GAP_FACTOR = 1.35            # a GAP item: RR at least this many times the reference RR
PATTERN_TOLERANCE_MS = 60    # an event start/end must lie this close to a detected beat

BEAT_LABELS = ("N", "S", "V", "Q", "?")
GAP_LABELS = ("B", "N", "?")
QUEUE_COLUMNS = ["item", "kind", "utcMs", "group", "weight", "ourClass"]
LABEL_COLUMNS = ["item", "utcMs", "kind", "label", "labeledAtUtc", "note", "bumpUtcMs"]
GALLERY_COLUMNS = ["record", "utcMs", "truth", "answer", "answeredAtUtc"]


# -- reading the analysis output ---------------------------------------------------------------
@dataclass
class BeatRow:
    utc_ms: int
    segment: int
    rr_ms: float | None
    rr_ref_ms: float | None
    cls: str
    good: bool
    raw: dict            # the whole csv row, for the "reveal" text


def _num(text: str | None) -> float | None:
    return float(text) if text not in (None, "") else None


def read_beats(analysis: Path) -> list[BeatRow]:
    with (analysis / "beats.csv").open(newline="", encoding="utf-8") as f:
        return [BeatRow(int(r["utcMs"]), int(r["segment"]), _num(r["rrMs"]), _num(r["rrRefMs"]),
                        r["class"], r["good"] == "1", r) for r in csv.DictReader(f)]


def read_events(analysis: Path) -> list[dict]:
    path = analysis / "events.csv"
    if not path.is_file():
        return []
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


# -- queue -----------------------------------------------------------------------------------
@dataclass
class QueueItem:
    item: str              # "B<utcMs>" for a beat, "G<utcMs>" for a gap (utcMs = middle of the interval)
    kind: str              # "BEAT" | "GAP"
    utc_ms: int
    group: str             # V S N Q PATTERN GAP
    weight: float          # group size / number taken: how many beats of the night this item stands for
    our_class: str         # "" for GAP items


def gap_items(beats: list[BeatRow]) -> list[tuple[int, BeatRow]]:
    """(index of the beat that ends the interval, that beat) for every interval that is long for its
    context and has good beats on both sides."""
    out = []
    for i in range(1, len(beats)):
        a, b = beats[i - 1], beats[i]
        if (a.segment == b.segment and a.good and b.good and b.rr_ms is not None
                and b.rr_ref_ms is not None and b.rr_ms >= GAP_FACTOR * b.rr_ref_ms):
            out.append((i, b))
    return out


def pattern_beat_indices(beats: list[BeatRow], events: list[dict]) -> set[int]:
    """First and last beat of every pause and every V_/S_ pattern (those are what is reported)."""
    times = np.array([b.utc_ms for b in beats])
    found: set[int] = set()
    if len(times) == 0:
        return found

    def nearest(t: float) -> int | None:
        k = int(np.searchsorted(times, t))
        cands = [j for j in (k - 1, k) if 0 <= j < len(times)]
        j = min(cands, key=lambda c: abs(times[c] - t))
        return j if abs(times[j] - t) <= PATTERN_TOLERANCE_MS else None

    for e in events:
        if e["type"] == "PAUSE" or e["type"].startswith(("V_", "S_")):
            start = int(e["utcMs"])
            for t in (start, start + int(e["durationMs"] or 0)):
                j = nearest(t)
                if j is not None:
                    found.add(j)
    return found


def spread_sample(items: list, k: int, rng: random.Random) -> list:
    """k items spread over the whole list (which is in time order): the list is cut into k equal parts
    and one random item is taken from each, so a night is covered evenly."""
    n = len(items)
    if k >= n:
        return list(items)
    bounds = [round(j * n / k) for j in range(k + 1)]
    return [items[rng.randrange(bounds[j], bounds[j + 1])] for j in range(k)]


def build_queue(beats: list[BeatRow], events: list[dict], seed: int = SEED,
                limits: dict[str, int] | None = None) -> list[QueueItem]:
    """The items to label, in random order. Beats are split into strata that do not overlap (pattern
    beats first, then our class); every stratum gets a weight = size / number taken, so that results
    can be corrected for the sampling (docs/LABELING_TOOL.md)."""
    limits = LIMITS if limits is None else limits
    rng = random.Random(seed)
    pattern = pattern_beat_indices(beats, events)
    strata: dict[str, list[int]] = {"V": [], "S": [], "N": [], "Q": []}
    for i, b in enumerate(beats):
        if i not in pattern and b.cls in strata:
            strata[b.cls].append(i)

    queue: list[QueueItem] = []

    def add_beats(group: str, idxs: list[int], take: int):
        chosen = spread_sample(idxs, take, rng)
        weight = len(idxs) / len(chosen) if chosen else 0.0
        for i in chosen:
            b = beats[i]
            queue.append(QueueItem(f"B{b.utc_ms}", "BEAT", b.utc_ms, group, weight, b.cls))

    add_beats("PATTERN", sorted(pattern), len(pattern))
    for g in ("V", "S", "N", "Q"):
        add_beats(g, strata[g], limits[g])

    gaps = gap_items(beats)
    chosen = spread_sample(gaps, limits["GAP"], rng)
    for i, b in chosen:
        mid = round(b.utc_ms - b.rr_ms / 2)
        queue.append(QueueItem(f"G{mid}", "GAP", mid, "GAP", len(gaps) / len(chosen), ""))

    rng.shuffle(queue)
    return queue


def write_queue(path: Path, queue: list[QueueItem]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", newline="", encoding="utf-8") as f:   # "x": never overwrite an existing queue
        w = csv.writer(f)
        w.writerow(QUEUE_COLUMNS)
        for q in queue:
            w.writerow([q.item, q.kind, q.utc_ms, q.group, f"{q.weight:.6g}", q.our_class])


def read_queue(path: Path) -> list[QueueItem]:
    with path.open(newline="", encoding="utf-8") as f:
        return [QueueItem(r["item"], r["kind"], int(r["utcMs"]), r["group"], float(r["weight"]), r["ourClass"])
                for r in csv.DictReader(f)]


# -- labels (append-only) --------------------------------------------------------------------
def valid_labels(kind: str) -> tuple[str, ...]:
    return GAP_LABELS if kind == "GAP" else BEAT_LABELS


def _utc_now_text() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def append_label(path: Path, item: str, utc_ms: int, kind: str, label: str,
                 note: str = "", bump_utc_ms: int | None = None, now: str | None = None) -> None:
    """Adds one row. ``label`` "" means "undone". Rows are never changed or removed."""
    if label != "" and label not in valid_labels(kind):
        raise ValueError(f"label {label!r} is not allowed for a {kind} item")
    path.parent.mkdir(parents=True, exist_ok=True)
    new = not path.is_file()
    with path.open("a", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        if new:
            w.writerow(LABEL_COLUMNS)
        w.writerow([item, utc_ms, kind, label, now or _utc_now_text(), note,
                    "" if bump_utc_ms is None else bump_utc_ms])
        f.flush()


def read_label_rows(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def current_labels(rows: list[dict]) -> dict[str, dict]:
    """item -> its last row; items whose last row is an undo are left out."""
    last: dict[str, dict] = {}
    for r in rows:
        last[r["item"]] = r
    return {k: r for k, r in last.items() if r["label"] != ""}


def first_unlabeled(queue: list[QueueItem], current: dict[str, dict]) -> int:
    for i, q in enumerate(queue):
        if q.item not in current:
            return i
    return len(queue)


def labels_dir(root: Path, session: str) -> Path:
    return root / session


# -- gallery (MIT-BIH beats with the cardiologists' labels hidden) -----------------------------
@dataclass
class GalleryItem:
    record: str
    utc_ms: int
    truth: str             # N | S | V


def read_reference_beats(record_dir: Path) -> tuple[list[tuple[int, str, str]], int]:
    """([(utc_ms, aami class, original symbol)], start utc ms) of a converted reference record;
    only real beats."""
    meta = json.loads((record_dir / "reference.json").read_text(encoding="utf-8"))
    start = int(datetime.fromisoformat(meta["startUtc"].replace("Z", "+00:00")).timestamp() * 1000)
    out = []
    with (record_dir / "annotations.csv").open(newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["beat"] == "1":
                out.append((start + int(r["time_ms"]), r["aami"], r["symbol"]))
    return out, start


def is_clear_example(times: np.ndarray, i: int, cls: str, symbol: str) -> bool:
    """Is beat i an example that can be judged from the strip (as our own S/V rules would)?
    MIT-BIH's S includes aberrated and nodal beats, and many S/N labels sit in irregular rhythm or
    after long pauses where nothing shows "early" (only 1 in 3 S beats is visibly early), so the
    default gallery keeps: S = atrial premature beat, early (< 0.85 of the usual interval), regular
    rhythm; N = symbol N, on time (0.9-1.1), regular rhythm; V = any V."""
    if cls == "V":
        return True
    if i < 9:
        return False
    recent = np.diff(times[i - 9:i])                        # the 8 intervals before this beat
    ref = float(np.median(recent))
    spread = float(np.median(np.abs(recent - ref))) / ref
    prematurity = (times[i] - times[i - 1]) / ref
    if spread > 0.05:
        return False
    if cls == "S":
        return symbol == "A" and prematurity < 0.85
    return symbol == "N" and 0.9 <= prematurity <= 1.1


def pick_gallery(reference_db: Path, per_class: int = 20, seed: int = SEED,
                 hard: bool = False) -> list[GalleryItem]:
    """About ``per_class`` beats each of N, S and V, spread over several records (at most
    ceil(per_class / 5) from one record per class), in random order. Unless ``hard``, only clear
    examples (see ``is_clear_example``); ``hard`` takes the cardiologists' labels as they come."""
    rng = random.Random(seed)
    cap = math.ceil(per_class / 5)
    cands: dict[str, list[tuple[str, int]]] = {"N": [], "S": [], "V": []}
    for rec in sorted(p for p in reference_db.iterdir() if (p / "annotations.csv").is_file()):
        beats, _ = read_reference_beats(rec)
        times = np.array([b[0] for b in beats])
        for i in range(5, len(beats) - 5):                # keep a few neighbours on both sides
            _, cls, symbol = beats[i]
            if cls in cands and (hard or is_clear_example(times, i, cls, symbol)):
                cands[cls].append((rec.name, beats[i][0]))
    items: list[GalleryItem] = []
    for cls, lst in cands.items():
        rng.shuffle(lst)
        per_record: Counter = Counter()
        chosen: list[tuple[str, int]] = []
        for rec, utc in lst:                              # first pass: respect the per-record cap
            if len(chosen) >= per_class:
                break
            if per_record[rec] < cap:
                per_record[rec] += 1
                chosen.append((rec, utc))
        if len(chosen) < per_class:                       # few records: fill up beyond the cap
            left = [c for c in lst if c not in chosen]
            chosen += left[:per_class - len(chosen)]
        items += [GalleryItem(rec, utc, cls) for rec, utc in chosen]
    rng.shuffle(items)
    return items


class GalleryScore:
    """Running score and the confusion of the owner's answers against the real labels."""

    def __init__(self):
        self.confusion: Counter = Counter()          # (truth, answer) -> count

    def add(self, truth: str, answer: str) -> None:
        self.confusion[(truth, answer)] += 1

    @property
    def total(self) -> int:
        return sum(self.confusion.values())

    @property
    def correct(self) -> int:
        return sum(n for (t, a), n in self.confusion.items() if t == a)

    def mistakes(self) -> list[str]:
        """Most frequent first, e.g. "S taken for V: 4 times"."""
        out = []
        for (t, a), n in self.confusion.most_common():
            if t != a:
                what = "skipped as unsure" if a == "?" else f"taken for {a}"
                out.append(f"{t} {what}: {n} time{'s' if n != 1 else ''}")
        return out


def append_gallery_answer(path: Path, record: str, utc_ms: int, truth: str, answer: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    new = not path.is_file()
    with path.open("a", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        if new:
            w.writerow(GALLERY_COLUMNS)
        w.writerow([record, utc_ms, truth, answer, _utc_now_text()])
