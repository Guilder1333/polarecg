"""Learned beat classifier (plan P7.1c): a small 1D-CNN trained on the expert-labelled databases.

    python analysis/train_beats.py train                       # train, writes data/models/<time>/
    python analysis/train_beats.py train --epochs 2 --samples-per-epoch 20000   # quick try
    python analysis/train_beats.py evaluate data/models/<run>  # test-split figures per database

Input: ``data/dataset/`` from ``build_dataset.py`` (all leads, 130 Hz, filtered like the app,
split by patient in ``manifest.csv``).

What the model sees for one beat
--------------------------------
* **About 3 s of ECG** (``PRE`` + ``POST`` = 384 samples) centred on the beat: the previous beat,
  the beat and the next beat, so it can compare the beat's shape with its neighbours.
  Each window is centred on its median and divided by its 99th percentile of |x| (roughly the
  R height), so the lead and the person's amplitude do not matter.
* **RR features**: how early the beat is (``rr_pre / rr_local``), the pause after it
  (``rr_post / rr_local``), the interval before (``rr_pre2 / rr_local``), the usual RR in seconds
  and ``rr_post / rr_pre``. The pause is what tells a PVC (compensatory) from a PAC (reset).

Classes
-------
``N`` normal, ``S`` supraventricular (PAC), ``V`` ventricular (PVC) and ``X`` = **not a beat**.
X windows are made from training ECG with strong NSTDB noise added, centred on the steepest
point *between* two real beats: what the app's detector does when it fires on noise (as at
2026-11-17 06:44:40.894 JST). Their RR features are what the app would compute for such a false
beat. AAMI ``F`` (fusion) and ``Q`` (unclassifiable) are left out of training: too few and
ambiguous.

Training
--------
Batches are class-balanced (N 40 %, S 20 %, V 20 %, X 20 %), a random lead each time.
Augmentation on training windows only: random polarity flip (H10's lead can be upside down
compared with any standard lead), centre jitter of ±2 samples (detector timing), and mild
NSTDB noise on some beats (a real beat in noise must stay a beat). The NSTDB noise records are
split in time: the first 70 % for training, the rest for validation/test.
The model with the best validation score (mean sensitivity over N, S, V, X) is kept.

PyTorch notes for reading the code: an ``nn.Module`` is a layer or a network; ``forward`` is what
runs on a batch. Tensors have shape (batch, channels, samples). ``model.train()`` /
``model.eval()`` switch batch-norm between learning and fixed statistics.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from build_dataset import cut_window  # noqa: E402
from polar_raw import ECG_RATE_HZ  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
DATASET = ROOT / "data" / "dataset"
MODELS = ROOT / "data" / "models"

CLASSES = ("N", "S", "V", "X")
CLASS_OF_AAMI = {"N": 0, "S": 1, "V": 2}          # F and Q: not used (-1)
X_CLASS = 3
BATCH_MIX = (0.4, 0.2, 0.2, 0.2)                  # share of N, S, V, X in a training batch

PRE, POST = 192, 192                              # window: 1.48 s before, 1.48 s after the beat
N_RR = 5
MIN_AMPLITUDE_UV = 50.0                           # window scale never below this (flat signal)
NOISE_TRAIN_PART = 0.7                            # first 70 % of each noise record: training
GAP_MARGIN = 26                                   # X centre at least 200 ms from a real beat
JITTER = 2                                        # ± samples of centre jitter in training

# The lead each database is usually evaluated on (MIT-BIH MLII, INCART II, svdb ECG1).
PRIMARY_LEAD = {"mitdb": ("MLII",), "incartdb": ("II",), "svdb": ("ECG1",)}


# ---------------------------------------------------------------- data

@dataclass
class Record:
    db: str
    record: str
    patient: str
    split: str
    signal: np.ndarray      # (leads, n) int16 µV
    leads: list[str]
    beat_sample: np.ndarray  # int32
    beat_class: np.ndarray   # index into CLASSES, -1 = not used
    rr: np.ndarray           # (beats, 4) ms: rr_pre, rr_post, rr_pre2, rr_local (-1 = none)

    @property
    def primary_lead(self) -> int:
        for name in PRIMARY_LEAD.get(self.db, ()):
            if name in self.leads:
                return self.leads.index(name)
        return 0


def load_records(dataset: Path, splits: set[str]) -> list[Record]:
    with open(dataset / "manifest.csv", newline="", encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f) if r["split"] in splits]
    out = []
    for r in rows:
        with np.load(dataset / r["db"] / f"{r['record']}.npz") as z:
            aami = z["beat_aami"]
            out.append(Record(
                r["db"], r["record"], r["patient"], r["split"], z["signal"], [str(x) for x in z["leads"]],
                z["beat_sample"].astype(np.int64),
                np.array([CLASS_OF_AAMI.get(a, -1) for a in aami], dtype=np.int64),
                np.stack([z["rr_pre"], z["rr_post"], z["rr_pre2"], z["rr_local"]], 1).astype(np.float32),
            ))
    return out


def load_noise(dataset: Path, part: str) -> list[np.ndarray]:
    """NSTDB noise channels (float µV); ``part`` 'train' = first 70 % in time, else the rest."""
    out = []
    for path in sorted((dataset / "noise").glob("*.npz")):
        with np.load(path) as z:
            sig = z["signal"].astype(np.float32)
        cut = int(sig.shape[1] * NOISE_TRAIN_PART)
        for ch in sig:
            out.append(ch[:cut] if part == "train" else ch[cut:])
    return out


# ---------------------------------------------------------------- features (pure functions)

def normalize_window(w: np.ndarray) -> np.ndarray:
    """Centre on the median, divide by the 99th percentile of |x| (about the R height)."""
    w = np.asarray(w, dtype=np.float32)
    w = w - np.median(w)
    scale = max(float(np.percentile(np.abs(w), 99)), MIN_AMPLITUDE_UV)
    return w / scale


def rr_features(rr_pre: float, rr_post: float, rr_pre2: float, rr_local: float) -> np.ndarray:
    """The N_RR timing features; missing intervals (-1) count as 'usual' (ratio 1)."""
    local = rr_local if rr_local > 0 else rr_pre if rr_pre > 0 else 800.0
    ratio = lambda v: min(max(v / local, 0.0), 4.0) if v > 0 else 1.0  # noqa: E731
    post_over_pre = min(max(rr_post / rr_pre, 0.0), 4.0) if rr_pre > 0 and rr_post > 0 else 1.0
    return np.array([ratio(rr_pre), ratio(rr_post), ratio(rr_pre2), local / 1000.0, post_over_pre],
                    dtype=np.float32)


def noise_center(noisy: np.ndarray, lo: int, hi: int) -> int:
    """Where a detector would fire in ``noisy[lo:hi]``: the steepest point (largest |slope|)."""
    d = np.abs(np.diff(noisy[lo:hi + 1]))
    return lo + int(np.argmax(d))


def noise_piece(noise: list[np.ndarray], length: int, rng: np.random.Generator) -> np.ndarray:
    ch = noise[rng.integers(len(noise))]
    start = rng.integers(0, len(ch) - length)
    return ch[start:start + length]


def scaled_to(signal: np.ndarray, noise: np.ndarray, ratio: float) -> np.ndarray:
    """``noise`` scaled so its RMS is ``ratio`` x the RMS of ``signal``."""
    s = float(np.sqrt(np.mean(signal.astype(np.float64) ** 2))) or 1.0
    n = float(np.sqrt(np.mean(noise.astype(np.float64) ** 2))) or 1.0
    return noise * (ratio * s / n)


# ---------------------------------------------------------------- window makers

class WindowMaker:
    """Makes (window, rr features, class) examples from the loaded records."""

    def __init__(self, records: list[Record], noise: list[np.ndarray]):
        self.records = records
        self.noise = noise
        # (record index, beat index) per class
        self.by_class = []
        for c in range(len(CLASSES) - 1):
            pairs = [(ri, bi) for ri, r in enumerate(records) for bi in np.flatnonzero(r.beat_class == c)]
            self.by_class.append(np.array(pairs, dtype=np.int64).reshape(-1, 2))
        # gaps wide enough for an X centre: (record, beat i) with beat i+1 far enough away
        gaps = []
        for ri, r in enumerate(records):
            s = r.beat_sample
            ok = np.flatnonzero(np.diff(s) > 2 * GAP_MARGIN + 2)
            gaps += [(ri, bi) for bi in ok if bi >= 1]
        self.gaps = np.array(gaps, dtype=np.int64).reshape(-1, 2)

    def beat(self, ri: int, bi: int, lead: int, rng: np.random.Generator | None, augment: bool):
        r = self.records[ri]
        center = int(r.beat_sample[bi])
        if augment:
            center += int(rng.integers(-JITTER, JITTER + 1))
        w = cut_window(r.signal[lead], center, PRE, POST).astype(np.float32)
        if augment and self.noise and rng.random() < 0.4:  # mild noise: a beat in noise stays a beat
            w = w + scaled_to(w, noise_piece(self.noise, len(w), rng), rng.uniform(0.05, 0.6))
        if augment and rng.random() < 0.5:
            w = -w
        return normalize_window(w), rr_features(*r.rr[bi]), int(r.beat_class[bi])

    def not_a_beat(self, rng: np.random.Generator, augment: bool):
        """X: strong noise on real ECG, centred on the steepest point between two real beats."""
        ri, bi = self.gaps[rng.integers(len(self.gaps))]
        r = self.records[ri]
        lead = int(rng.integers(len(r.leads)))
        b0, b1 = int(r.beat_sample[bi]), int(r.beat_sample[bi + 1])
        lo, hi = b0 - PRE - 2, b1 + POST + 2                   # region that any window may touch
        ecg = cut_window(r.signal[lead], lo, 0, hi - lo).astype(np.float32)
        noisy = ecg + scaled_to(ecg, noise_piece(self.noise, len(ecg), rng), rng.uniform(0.5, 3.0))
        c = noise_center(noisy, b0 - lo + GAP_MARGIN, b1 - lo - GAP_MARGIN)
        w = cut_window(noisy, c, PRE, POST)
        if augment and rng.random() < 0.5:
            w = -w
        rr_pre2 = r.rr[bi][0]
        rr_local = r.rr[bi + 1][3]
        feats = rr_features((c - (b0 - lo)) * 1000 / ECG_RATE_HZ, ((b1 - lo) - c) * 1000 / ECG_RATE_HZ,
                            rr_pre2, rr_local)
        return normalize_window(w), feats, X_CLASS

    def batch(self, size: int, rng: np.random.Generator, augment: bool = True, mix=BATCH_MIX):
        counts = rng.multinomial(size, mix)
        xs, rs, ys = [], [], []
        for c, k in enumerate(counts):
            for _ in range(k):
                if c == X_CLASS:
                    w, f, y = self.not_a_beat(rng, augment)
                else:
                    pairs = self.by_class[c]
                    ri, bi = pairs[rng.integers(len(pairs))]
                    lead = int(rng.integers(len(self.records[ri].leads)))
                    w, f, y = self.beat(int(ri), int(bi), lead, rng, augment)
                xs.append(w)
                rs.append(f)
                ys.append(y)
        order = rng.permutation(size)
        return (np.stack(xs)[order][:, None, :], np.stack(rs)[order], np.array(ys, dtype=np.int64)[order])


def fixed_set(maker: WindowMaker, per_class: int, seed: int):
    """A fixed, balanced validation set (same windows every epoch): up to ``per_class`` per class."""
    rng = np.random.default_rng(seed)
    xs, rs, ys = [], [], []
    for c in range(len(CLASSES)):
        if c == X_CLASS:
            items = [maker.not_a_beat(rng, augment=False) for _ in range(per_class)]
        else:
            pairs = maker.by_class[c]
            pick = pairs[rng.choice(len(pairs), size=min(per_class, len(pairs)), replace=False)]
            items = [maker.beat(int(ri), int(bi), int(rng.integers(len(maker.records[ri].leads))), None, False)
                     for ri, bi in pick]
        for w, f, y in items:
            xs.append(w)
            rs.append(f)
            ys.append(y)
    return np.stack(xs)[:, None, :], np.stack(rs), np.array(ys, dtype=np.int64)


# ---------------------------------------------------------------- metrics (pure)

def confusion(y_true: np.ndarray, y_pred: np.ndarray, n: int = len(CLASSES)) -> np.ndarray:
    """``m[true, predicted]`` counts."""
    m = np.zeros((n, n), dtype=np.int64)
    np.add.at(m, (y_true, y_pred), 1)
    return m


def se_ppv(m: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Sensitivity (recall) and positive predictivity (precision) per class, in %; NaN if undefined."""
    with np.errstate(invalid="ignore", divide="ignore"):
        se = 100.0 * np.diag(m) / m.sum(1)
        ppv = 100.0 * np.diag(m) / m.sum(0)
    return se, ppv


def format_confusion(m: np.ndarray) -> str:
    se, ppv = se_ppv(m)
    lines = ["true\\pred " + "".join(f"{c:>9}" for c in CLASSES) + "     Se %    +P %"]
    for i, c in enumerate(CLASSES):
        if m[i].sum() == 0 and m[:, i].sum() == 0:
            continue
        lines.append(f"{c:<10}" + "".join(f"{v:>9}" for v in m[i]) + f"{se[i]:>9.1f}{ppv[i]:>8.1f}")
    return "\n".join(lines)


# ---------------------------------------------------------------- model

def build_model(width: int = 32):
    import torch
    from torch import nn

    def block(cin, cout):
        return nn.Sequential(
            nn.Conv1d(cin, cout, 7, padding=3, bias=False), nn.BatchNorm1d(cout), nn.ReLU(),
            nn.Conv1d(cout, cout, 7, padding=3, bias=False), nn.BatchNorm1d(cout), nn.ReLU(),
            nn.MaxPool1d(2),
        )

    class BeatNet(nn.Module):
        """4 conv blocks (384 -> 24 samples), then: the whole window (average) and the part around
        the beat (centre 4 positions = about 0.5 s) side by side, plus the RR features."""

        def __init__(self):
            super().__init__()
            self.features = nn.Sequential(block(1, width), block(width, 2 * width),
                                          block(2 * width, 4 * width), block(4 * width, 4 * width))
            self.rr = nn.Sequential(nn.Linear(N_RR, 16), nn.ReLU())
            self.head = nn.Sequential(nn.Linear(8 * width + 16, 64), nn.ReLU(), nn.Dropout(0.2),
                                      nn.Linear(64, len(CLASSES)))

        def forward(self, x, rr):
            f = self.features(x)                      # (batch, 4w, 24)
            mid = f.shape[-1] // 2
            centre = f[..., mid - 2:mid + 2].mean(-1)
            whole = f.mean(-1)
            return self.head(torch.cat([whole, centre, self.rr(rr)], 1))

    return BeatNet()


def predict(model, x: np.ndarray, rr: np.ndarray, device, batch: int = 2048) -> np.ndarray:
    import torch
    model.eval()
    out = []
    with torch.no_grad():
        for i in range(0, len(x), batch):
            logits = model(torch.from_numpy(x[i:i + batch]).to(device),
                           torch.from_numpy(rr[i:i + batch]).to(device))
            out.append(logits.argmax(1).cpu().numpy())
    return np.concatenate(out) if out else np.zeros(0, np.int64)


def score(m: np.ndarray) -> float:
    """Model selection: mean sensitivity over the classes (the validation set is balanced)."""
    se, _ = se_ppv(m)
    return float(np.nanmean(se))


# ---------------------------------------------------------------- commands

def _device():
    import torch
    return torch.device("cuda" if torch.cuda.is_available() else "cpu")


def train(args) -> int:
    import torch
    from torch import nn

    torch.manual_seed(args.seed)
    device = _device()
    t0 = time.time()
    train_maker = WindowMaker(load_records(args.dataset, {"train"}), load_noise(args.dataset, "train"))
    val_maker = WindowMaker(load_records(args.dataset, {"val"}), load_noise(args.dataset, "eval"))
    val = fixed_set(val_maker, args.val_per_class, args.seed + 1)
    print(f"data loaded in {time.time() - t0:.0f}s on {device}; train beats per class "
          f"{[len(p) for p in train_maker.by_class]}, val set {len(val[2])} windows", flush=True)

    run = args.out / time.strftime("%Y%m%d-%H%M%S")
    run.mkdir(parents=True, exist_ok=False)
    config = {k: (str(v) if isinstance(v, Path) else v) for k, v in vars(args).items() if k != "func"}
    config.update({"classes": CLASSES, "pre": PRE, "post": POST, "nRr": N_RR, "rateHz": ECG_RATE_HZ,
                   "batchMix": BATCH_MIX})
    (run / "config.json").write_text(json.dumps(config, indent=2), encoding="utf-8")

    model = build_model(args.width).to(device)
    n_params = sum(p.numel() for p in model.parameters())
    steps_per_epoch = max(1, args.samples_per_epoch // args.batch)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=args.epochs * steps_per_epoch)
    loss_fn = nn.CrossEntropyLoss(label_smoothing=0.05)
    rng = np.random.default_rng(args.seed)
    print(f"model {n_params:,} parameters; run folder {run}", flush=True)

    best = -1.0
    history = []
    for epoch in range(1, args.epochs + 1):
        model.train()
        t_epoch, total = time.time(), 0.0
        for _ in range(steps_per_epoch):
            x, rr, y = train_maker.batch(args.batch, rng)
            x, rr, y = (torch.from_numpy(a).to(device) for a in (x, rr, y))
            opt.zero_grad()
            loss = loss_fn(model(x, rr), y)
            loss.backward()
            opt.step()
            sched.step()
            total += loss.item()
        m = confusion(val[2], predict(model, val[0], val[1], device))
        s = score(m)
        se, _ = se_ppv(m)
        history.append({"epoch": epoch, "loss": total / steps_per_epoch, "valScore": s,
                        "valSe": dict(zip(CLASSES, np.round(se, 1).tolist()))})
        mark = ""
        if s > best:
            best = s
            torch.save(model.state_dict(), run / "model.pt")
            (run / "val_confusion.txt").write_text(format_confusion(m) + "\n", encoding="utf-8")
            mark = "  * saved"
        print(f"epoch {epoch}/{args.epochs} loss {total / steps_per_epoch:.3f} val mean Se {s:.1f} "
              f"(" + ", ".join(f"{c} {v:.1f}" for c, v in zip(CLASSES, se)) + f") "
              f"{time.time() - t_epoch:.0f}s{mark}", flush=True)
        (run / "history.json").write_text(json.dumps(history, indent=2), encoding="utf-8")
    print(f"\nbest validation mean Se {best:.1f}; validation confusion of the saved model:\n"
          + (run / "val_confusion.txt").read_text(encoding="utf-8"))
    print(f"next: python analysis/train_beats.py evaluate {run}")
    return 0


def beat_windows(records: list[Record], primary_only: bool):
    """All usable beats of the records, unaugmented: (x, rr, y, db) in record order."""
    xs, rs, ys, dbs = [], [], [], []
    for r in records:
        leads = [r.primary_lead] if primary_only else range(len(r.leads))
        use = np.flatnonzero(r.beat_class >= 0)
        for lead in leads:
            for bi in use:
                xs.append(normalize_window(cut_window(r.signal[lead], int(r.beat_sample[bi]), PRE, POST)))
                rs.append(rr_features(*r.rr[bi]))
                ys.append(int(r.beat_class[bi]))
                dbs.append(r.db)
    return (np.stack(xs)[:, None, :], np.stack(rs), np.array(ys, dtype=np.int64), np.array(dbs))


def evaluate(args) -> int:
    import torch

    device = _device()
    config = json.loads((args.run / "config.json").read_text(encoding="utf-8"))
    model = build_model(config["width"]).to(device)
    model.load_state_dict(torch.load(args.run / "model.pt", map_location=device))
    # The dataset path stored in config.json is absolute and only valid on the PC that trained the model,
    # so evaluation uses --dataset (default: this repository's data/dataset).
    records = load_records(args.dataset, {args.split})
    report = [f"split {args.split}, model {args.run.name}, primary lead of each database, all beats "
              f"(natural class mix, F and Q left out)"]
    x, rr, y, dbs = beat_windows(records, primary_only=True)
    pred = predict(model, x, rr, device)
    for db in sorted(set(dbs)) + ["all"]:
        sel = np.ones(len(y), bool) if db == "all" else dbs == db
        report += ["", f"== {db}: {sel.sum()} beats", format_confusion(confusion(y[sel], pred[sel]))]
    # Not-a-beat windows: how many are recognised, from the evaluation part of the noise records.
    maker = WindowMaker(records, load_noise(args.dataset, "eval"))
    rng = np.random.default_rng(12345)
    xn = [maker.not_a_beat(rng, augment=False) for _ in range(args.noise_windows)]
    pn = predict(model, np.stack([w for w, _, _ in xn])[:, None, :], np.stack([f for _, f, _ in xn]), device)
    counts = np.bincount(pn, minlength=len(CLASSES))
    report += ["", f"== {args.noise_windows} not-a-beat windows (noise between real beats): called "
               + ", ".join(f"{c} {100 * k / len(pn):.1f}%" for c, k in zip(CLASSES, counts))]
    text = "\n".join(report)
    print(text)
    (args.run / f"eval_{args.split}.txt").write_text(text + "\n", encoding="utf-8")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(required=True)
    t = sub.add_parser("train", help="train a model")
    t.add_argument("--dataset", type=Path, default=DATASET)
    t.add_argument("--out", type=Path, default=MODELS)
    t.add_argument("--epochs", type=int, default=20)
    t.add_argument("--samples-per-epoch", type=int, default=200_000)
    t.add_argument("--batch", type=int, default=256)
    t.add_argument("--lr", type=float, default=2e-3)
    t.add_argument("--width", type=int, default=32, help="channels of the first conv block")
    t.add_argument("--val-per-class", type=int, default=3000)
    t.add_argument("--seed", type=int, default=0)
    t.set_defaults(func=train)
    e = sub.add_parser("evaluate", help="figures on a split (default: test)")
    e.add_argument("run", type=Path, help="run folder with model.pt and config.json")
    e.add_argument("--split", default="test", choices=("train", "val", "test"))
    e.add_argument("--noise-windows", type=int, default=5000)
    e.add_argument("--dataset", type=Path, default=DATASET, help="dataset folder (default: data/dataset)")
    e.set_defaults(func=evaluate)
    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
