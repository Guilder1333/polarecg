"""Run a trained beat model (train_beats.py) on one of your recorded sessions (plan P7.1d).

    python analysis/classify_session.py data/models/<run> data/sessions/<session>

The model looks at the beats the app detected (``beats.csv``), the way it will on the phone:
every detection, including the app's Q and false ones, gets a window cut from the raw ECG,
filtered with the app's morphology chain, plus RR features from the neighbouring detections.

Writes ``data/model-out/<session>_<run>.csv`` (one row per app beat: utcMs, app class, model class,
model probabilities, RR) and prints how the model's classes compare with the app's.
Nothing in the session folder is changed.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from build_dataset import cut_window, filter_like_app, morphology_delay_samples, rr_context  # noqa: E402
from polar_raw import read_ecg  # noqa: E402
from polar_segments import build_segments  # noqa: E402
import train_beats as tb  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "data" / "model-out"


def read_beats(session: Path) -> list[dict]:
    with open(session / "beats.csv", newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def session_windows(session: Path, beats: list[dict]):
    """(x, rr features, segment index per beat); beats outside any ECG segment get segment -1.

    RR features are computed per gap-free segment from the app's detections (all classes), as the
    app sees them: a false detection makes the next real beat look early, exactly the case the
    model has to cope with."""
    ecg, _ = read_ecg(session)
    segments = build_segments(ecg)
    delay = morphology_delay_samples()
    utc = np.array([int(b["utcMs"]) for b in beats], dtype=np.int64)
    x = np.zeros((len(beats), 1, tb.PRE + tb.POST), np.float32)
    feats = np.zeros((len(beats), tb.N_RR), np.float32)
    seg_of = np.full(len(beats), -1, np.int64)
    for seg in segments:
        sel = np.flatnonzero((utc >= seg.start_utc_ms) & (utc <= seg.end_utc_ms))
        if len(sel) == 0:
            continue
        filtered = filter_like_app(seg.samples_uv.astype(np.float64))
        pos = np.array([seg.index_at(u) for u in utc[sel]], dtype=np.int64) + delay
        rr = rr_context(pos)
        for k, (i, p) in enumerate(zip(sel, pos)):
            x[i, 0] = tb.normalize_window(cut_window(filtered, int(p), tb.PRE, tb.POST))
            feats[i] = tb.rr_features(rr["rr_pre"][k], rr["rr_post"][k], rr["rr_pre2"][k], rr["rr_local"][k])
        seg_of[sel] = seg.index
    return x, feats, seg_of


def load_model(run: Path, device):
    import torch
    config = json.loads((run / "config.json").read_text(encoding="utf-8"))
    model = tb.build_model(config["width"]).to(device)
    model.load_state_dict(torch.load(run / "model.pt", map_location=device))
    model.eval()
    return model


def predict_proba(model, x: np.ndarray, rr: np.ndarray, device, batch: int = 2048) -> np.ndarray:
    import torch
    out = []
    with torch.no_grad():
        for i in range(0, len(x), batch):
            logits = model(torch.from_numpy(x[i:i + batch]).to(device), torch.from_numpy(rr[i:i + batch]).to(device))
            out.append(torch.softmax(logits, 1).cpu().numpy())
    return np.concatenate(out) if out else np.zeros((0, len(tb.CLASSES)), np.float32)


def crosstab(app: list[str], model: list[str]) -> str:
    app_classes = sorted(set(app), key=lambda c: ("NSVQ".find(c) if c in "NSVQ" else 9, c))
    lines = ["app\\model" + "".join(f"{c:>9}" for c in tb.CLASSES) + "    total"]
    for a in app_classes:
        row = [sum(1 for x, y in zip(app, model) if x == a and y == c) for c in tb.CLASSES]
        lines.append(f"{a:<9}" + "".join(f"{v:>9}" for v in row) + f"{sum(row):>9}")
    return "\n".join(lines)


def main(argv=None) -> int:
    import torch
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("run", type=Path, help="model run folder (model.pt, config.json)")
    ap.add_argument("session", type=Path, help="session folder with beats.csv and ecg_*.bin")
    ap.add_argument("--out", type=Path, default=OUT)
    args = ap.parse_args(argv)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    beats = read_beats(args.session)
    x, rr, seg_of = session_windows(args.session, beats)
    proba = predict_proba(load_model(args.run, device), x, rr, device)
    pred = proba.argmax(1)
    ok = seg_of >= 0

    args.out.mkdir(parents=True, exist_ok=True)
    out = args.out / f"{args.session.name}_{args.run.name}.csv"
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["utcMs", "utc", "appClass", "vDoubt", "model", *(f"p{c}" for c in tb.CLASSES),
                    "rrPreRatio", "rrPostRatio", "rrLocalMs"])
        for b, p, pr, f_, good in zip(beats, pred, proba, rr, ok):
            w.writerow([b["utcMs"], b["utc"], b["class"], b.get("vDoubt", ""),
                        tb.CLASSES[p] if good else "", *(f"{v:.3f}" for v in pr),
                        f"{f_[0]:.3f}", f"{f_[1]:.3f}", round(float(f_[3]) * 1000)])
    app = [b["class"] for b, g in zip(beats, ok) if g]
    model = [tb.CLASSES[p] for p, g in zip(pred, ok) if g]
    print(f"{len(beats)} app beats, {int((~ok).sum())} outside the ECG segments; written {out}\n")
    print(crosstab(app, model))
    return 0


if __name__ == "__main__":
    sys.exit(main())
