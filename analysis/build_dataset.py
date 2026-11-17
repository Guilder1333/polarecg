"""Training dataset for the learned beat classifier (plan P7.1a).

    python analysis/build_dataset.py                 # every database found in data/physionet/
    python analysis/build_dataset.py --db mitdb      # one database
    python analysis/build_dataset.py --force         # rebuild records that already exist

Reads the original PhysioNet files that ``fetch_physionet.py`` keeps in ``data/physionet/<db>/``
and writes ``data/dataset/``:

    <db>/<record>.npz   every lead of the record, 130 Hz, µV (int16), filtered like the app,
                        plus one row per annotated beat (position, AAMI class, RR context)
    noise/<name>.npz    NSTDB noise records (bw = baseline wander, em = electrode motion,
                        ma = muscle), same processing, for augmentation and a "not a beat" class
    manifest.csv        one row per record: database, patient, split, class counts
    build.json          builder version and the processing settings

Why it looks like this:

* **Every lead.** The H10's chest lead matches none of the standard leads, so the model must not
  learn one lead's shape. Each lead of each record becomes training material.
* **Filtered like the phone.** The app judges beat shape on the causal 0.5-40 Hz chain of
  ``EcgFilters`` (Kotlin, :core-dsp). The same Butterworth filters are applied here, and beat
  positions are shifted by the chain's group delay, as the app shifts its beat times.
* **Split by patient, never by beat.** Beats of one person in both train and test make the figures
  meaningless. MIT-BIH uses the standard de Chazal split (DS1 train/val, DS2 test; note: records
  201 and 202 are the same patient and sit in DS1 and DS2, kept for comparability with the
  literature). Other databases: whole patients, picked so that each split gets about its share
  of the N, S and V beats (``search_split``). The split is in ``manifest.csv`` only.
* **Continuous signals, windows cut at training time** (``cut_window``), so the window length can
  change without rebuilding. That also keeps the files small: one copy of each sample, not one
  per beat.
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from scipy.signal import butter, group_delay, sos2tf, sosfilt, sosfilt_zi

sys.path.insert(0, str(Path(__file__).parent))
from fetch_physionet import AAMI, BEAT_SYMBOLS, mv_to_uv, resample_to_130hz  # noqa: E402
from polar_raw import ECG_RATE_HZ  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
RAW_ROOT = ROOT / "data" / "physionet"
OUT_ROOT = ROOT / "data" / "dataset"

# Bump when the output changes; records built by an older version are rebuilt.
#   2: split moved from the record files to manifest.csv (stratified pick); records store "pool"
BUILDER_VERSION = 2

# Same settings as EcgFilterConfig (core-dsp filter/EcgFilters.kt), morphology chain.
MORPH_HP_HZ, MORPH_HP_ORDER = 0.5, 2
MORPH_LP_HZ, MORPH_LP_ORDER = 40.0, 4
DELAY_REFERENCE_HZ = 10.0

AAMI_CLASSES = ("N", "S", "V", "F", "Q")

# de Chazal et al. 2004, the usual inter-patient split of MIT-BIH (paced records left out).
MITDB_DS1 = {"101", "106", "108", "109", "112", "114", "115", "116", "118", "119", "122",
             "124", "201", "203", "205", "207", "208", "209", "215", "220", "223", "230"}
MITDB_DS2 = {"100", "103", "105", "111", "113", "117", "121", "123", "200", "202", "210",
             "212", "213", "214", "219", "221", "222", "228", "231", "232", "233", "234"}
MITDB_PACED = {"102", "104", "107", "217"}

NOISE_RECORDS = ("bw", "em", "ma")

# Share per split (of patients, and of the S and V beats) for databases without a standard split.
TEST_PERCENT, VAL_PERCENT = 25, 15
# Share of MIT-BIH DS1 that goes to validation instead of training.
MITDB_VAL_PERCENT = 20

RR_LOCAL_BEATS = 8   # rr_local = median of up to this many RR intervals before the beat


# ---------------------------------------------------------------- processing (pure functions)

def morphology_sos(fs: float = ECG_RATE_HZ) -> np.ndarray:
    """The app's 0.5-40 Hz morphology chain as second-order sections.

    Kotlin builds it from RBJ-cookbook biquads with Butterworth Qs; for a Butterworth filter that
    is the same transfer function as scipy's bilinear design (both pre-warp the cut-off)."""
    hp = butter(MORPH_HP_ORDER, MORPH_HP_HZ, "highpass", fs=fs, output="sos")
    lp = butter(MORPH_LP_ORDER, MORPH_LP_HZ, "lowpass", fs=fs, output="sos")
    return np.vstack([hp, lp])


def morphology_delay_samples(fs: float = ECG_RATE_HZ) -> int:
    """Group delay of the chain at 10 Hz, rounded to whole samples (as the app reports it)."""
    b, a = sos2tf(morphology_sos(fs))
    _, gd = group_delay((b, a), w=[DELAY_REFERENCE_HZ], fs=fs)
    return int(round(float(gd[0])))


def filter_like_app(x: np.ndarray, fs: float = ECG_RATE_HZ) -> np.ndarray:
    """Causal filtering; the filter starts settled on the first sample (like Biquad.settle)."""
    sos = morphology_sos(fs)
    x = np.asarray(x, dtype=np.float64)
    if len(x) == 0:
        return x
    y, _ = sosfilt(sos, x, zi=sosfilt_zi(sos) * x[0])
    return y


def to_int16_uv(x_uv: np.ndarray) -> np.ndarray:
    return np.clip(np.rint(x_uv), -32768, 32767).astype(np.int16)


def rr_context(beat_samples: np.ndarray, fs: float = ECG_RATE_HZ) -> dict[str, np.ndarray]:
    """RR intervals around each beat, in ms; -1 where they do not exist.

    rr_pre: from the previous beat; rr_post: to the next beat; rr_pre2: the interval before rr_pre;
    rr_local: median of up to RR_LOCAL_BEATS intervals ending at the previous beat (the "usual"
    rhythm before this beat, rr_pre itself excluded so an early beat does not pull it down)."""
    s = np.asarray(beat_samples, dtype=np.float64)
    n = len(s)
    rr = np.diff(s) * 1000.0 / fs  # rr[i] = beat i -> beat i+1
    pre = np.full(n, -1.0)
    post = np.full(n, -1.0)
    pre2 = np.full(n, -1.0)
    local = np.full(n, -1.0)
    if n >= 2:
        pre[1:] = rr
        post[:-1] = rr
        pre2[2:] = rr[:-1]
        for i in range(2, n):
            # intervals before rr_pre: rr[0 .. i-2]
            window = rr[max(0, i - 1 - RR_LOCAL_BEATS):i - 1]
            local[i] = np.median(window)
    to_int = lambda a: np.rint(a).astype(np.int32)  # noqa: E731
    return {"rr_pre": to_int(pre), "rr_post": to_int(post), "rr_pre2": to_int(pre2),
            "rr_local": to_int(local)}


def beat_table(ann_samples, ann_symbols, fs: float, n_out: int, delay: int) -> dict[str, np.ndarray]:
    """Beats only (rhythm/noise/comment annotations dropped), on the 130 Hz filtered grid."""
    rows = [(int(round(s * ECG_RATE_HZ / fs)) + delay, sym)
            for s, sym in zip(ann_samples, ann_symbols) if sym in BEAT_SYMBOLS]
    rows = [(p, sym) for p, sym in rows if 0 <= p < n_out]
    samples = np.array([p for p, _ in rows], dtype=np.int32)
    table = {
        "beat_sample": samples,
        "beat_symbol": np.array([sym for _, sym in rows], dtype="<U1"),
        "beat_aami": np.array([AAMI.get(sym, "Q") for _, sym in rows], dtype="<U1"),
    }
    table.update(rr_context(samples))
    return table


def cut_window(signal: np.ndarray, center: int, pre: int, post: int) -> np.ndarray:
    """``signal[..., center-pre : center+post]``; parts outside the signal are zero.

    Works for one lead (n,) or several (leads, n). Used by training and tests."""
    signal = np.asarray(signal)
    n = signal.shape[-1]
    out = np.zeros(signal.shape[:-1] + (pre + post,), dtype=signal.dtype)
    lo, hi = center - pre, center + post
    src_lo, src_hi = max(lo, 0), min(hi, n)
    if src_lo < src_hi:
        out[..., src_lo - lo:src_hi - lo] = signal[..., src_lo:src_hi]
    return out


# ---------------------------------------------------------------- patients and splits

def _bucket(key: str) -> int:
    """Stable 0..99 bucket (crc32, not Python's hash(), which changes between runs)."""
    return zlib.crc32(key.encode("utf-8")) % 100


def incart_patient(header_comments: list[str]) -> str | None:
    """INCART headers carry a line like '# patient 12' (75 records from 32 patients)."""
    for c in header_comments:
        m = re.search(r"patient\s+(\d+)", c)
        if m:
            return m.group(1)
    return None


@dataclass
class Assignment:
    patient: str      # "<db>:<id>", unique across databases
    pool: str         # "test" / "excluded" (fixed), or the pool the split is picked from


def assign(db: str, record: str, header_comments: list[str]) -> Assignment:
    """Patient id and split pool of a record. The split itself is decided by ``assign_splits``
    once every record's class counts are known."""
    if db == "mitdb":
        patient = "mitdb:" + record
        if record in MITDB_PACED:
            return Assignment(patient, "excluded")
        if record in MITDB_DS2:
            return Assignment(patient, "test")
        if record in MITDB_DS1:
            return Assignment(patient, "mitdb-ds1")
        return Assignment(patient, "excluded")  # the few records outside DS1/DS2/paced
    if db == "incartdb":
        pid = incart_patient(header_comments)
        if pid is None:
            raise ValueError(f"incartdb {record}: no '# patient N' in the header")
        return Assignment("incartdb:" + pid, "incartdb")
    # svdb and other single-record-per-patient databases
    return Assignment(f"{db}:{record}", db)


SPLIT_CLASSES = ("N", "S", "V")  # each split should hold about its share of these beats
SPLIT_TRIES = 20_000             # random whole-patient splits tried; the closest to target wins
SPLIT_SEED = 0                   # fixed: the same data always gives the same split


def search_split(patients: dict[str, dict[str, int]], shares: dict[str, float],
                 tries: int = SPLIT_TRIES, seed: int = SPLIT_SEED) -> dict[str, str]:
    """Whole patients -> split, so that each split holds about its share of the N, S and V beats
    and of the patients.

    ``patients``: patient -> {class: beats}; ``shares``: split -> share (summing to 1).
    A plain random patient split cannot be used: S beats sit in very few patients (one INCART
    patient has more than half of INCART's S), so validation could end up with almost none.
    Instead ``tries`` random splits are drawn (seeded, so the result is reproducible) and the one
    with the smallest relative deviation from the targets is kept. Every split gets at least one
    patient."""
    names = sorted(patients)
    splits = list(shares)
    target = np.array([shares[s] for s in splits])
    # columns: N, S, V beats, and 1 per patient
    counts = np.array([[patients[p].get(c, 0) for c in SPLIT_CLASSES] + [1] for p in names], float)
    totals = counts.sum(0)
    used = totals > 0
    rng = np.random.default_rng(seed)
    draws = rng.choice(len(splits), size=(tries, len(names)), p=target)
    best, best_score = None, np.inf
    for chunk in np.array_split(draws, max(1, tries // 2000)):
        onehot = np.eye(len(splits))[chunk]                     # (tries, patients, splits)
        got = np.einsum("tps,pc->tsc", onehot, counts)          # beats per try, split, class
        frac = got[..., used] / totals[used]                    # share of each class per split
        score = (((frac - target[None, :, None]) / target[None, :, None]) ** 2).sum((1, 2))
        score[(got[..., -1] == 0).any(1)] = np.inf              # a split without patients
        i = int(np.argmin(score))
        if score[i] < best_score:
            best, best_score = chunk[i], score[i]
    return {p: splits[k] for p, k in zip(names, best)}


def assign_splits(rows: list[dict]) -> None:
    """Sets ``row['split']`` for every manifest row (all records of a patient get the same split)."""
    patients: dict[str, dict[str, int]] = {}
    pool_of: dict[str, str] = {}
    for r in rows:
        counts = patients.setdefault(r["patient"], dict.fromkeys(AAMI_CLASSES, 0))
        for c in AAMI_CLASSES:
            counts[c] += int(r[f"n_{c}"])
        pool_of[r["patient"]] = r["pool"]
    split: dict[str, str] = {}
    for pool in sorted(set(pool_of.values())):
        members = {p: patients[p] for p in patients if pool_of[p] == pool}
        if pool in ("test", "excluded"):
            split.update(dict.fromkeys(members, pool))
        elif pool == "mitdb-ds1":  # DS2 is the test set; validation comes out of DS1
            v = MITDB_VAL_PERCENT / 100
            split.update(search_split(members, {"train": 1 - v, "val": v}))
        else:
            t, v = TEST_PERCENT / 100, VAL_PERCENT / 100
            split.update(search_split(members, {"train": 1 - t - v, "val": v, "test": t}))
    for r in rows:
        r["split"] = split[r["patient"]]


# ---------------------------------------------------------------- files

# db -> annotation extension
ANNOTATION_EXT = {"mitdb": "atr", "incartdb": "atr", "svdb": "atr"}


def list_local_records(raw_dir: Path, ext: str) -> list[str]:
    """Records that have .hea, .dat and the annotation file (half downloads are skipped)."""
    out = []
    for hea in sorted(raw_dir.glob("*.hea")):
        rec = hea.stem
        if (raw_dir / f"{rec}.dat").exists() and (raw_dir / f"{rec}.{ext}").exists():
            out.append(rec)
    return out


def _is_current(path: Path) -> bool:
    if not path.exists():
        return False
    try:
        with np.load(path) as z:
            return int(z["builder_version"]) == BUILDER_VERSION
    except (OSError, KeyError, ValueError):
        return False


def _read_all_leads_uv(local: str) -> tuple[np.ndarray, list[str], float, list[str]]:
    import wfdb  # imported here so the pure helpers above work without wfdb
    rec = wfdb.rdrecord(local)
    fs = float(rec.fs)
    leads = []
    for ch in range(rec.p_signal.shape[1]):
        uv = mv_to_uv(resample_to_130hz(rec.p_signal[:, ch], fs)).astype(np.float64)
        leads.append(to_int16_uv(filter_like_app(uv)))
    return np.vstack(leads), list(rec.sig_name), fs, list(rec.comments or [])


def build_record(db: str, record: str, raw_dir: Path, out_dir: Path, delay: int, force: bool) -> dict:
    import wfdb
    out = out_dir / db / f"{record}.npz"
    local = str(raw_dir / record)
    if not force and _is_current(out):  # up to date: read what the manifest needs from the file
        with np.load(out) as z:
            return _manifest_row(db, record, str(z["patient"]), str(z["pool"]), list(z["leads"]),
                                 int(z["signal"].shape[1]), z["beat_aami"], "skipped (exists)")
    signal, leads, fs, comments = _read_all_leads_uv(local)
    ann = wfdb.rdann(local, ANNOTATION_EXT[db])
    table = beat_table(ann.sample, ann.symbol, fs, signal.shape[1], delay)
    a = assign(db, record, comments)
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_name(out.stem + ".tmp.npz")
    # The split is not stored here: it is decided over all records and lives in manifest.csv.
    np.savez_compressed(tmp, signal=signal, leads=np.array(leads), fs=ECG_RATE_HZ,
                        original_fs=fs, patient=a.patient, pool=a.pool,
                        builder_version=BUILDER_VERSION, **table)
    tmp.replace(out)  # whole file or nothing
    return _manifest_row(db, record, a.patient, a.pool, leads, signal.shape[1], table["beat_aami"], "ok")


def build_noise(raw_dir: Path, out_dir: Path, force: bool) -> list[str]:
    done = []
    for name in NOISE_RECORDS:
        if not (raw_dir / f"{name}.hea").exists():
            continue
        out = out_dir / "noise" / f"{name}.npz"
        if not force and _is_current(out):
            done.append(f"{name}: skipped (exists)")
            continue
        signal, leads, fs, _ = _read_all_leads_uv(str(raw_dir / name))
        out.parent.mkdir(parents=True, exist_ok=True)
        tmp = out.with_name(out.stem + ".tmp.npz")
        np.savez_compressed(tmp, signal=signal, leads=np.array(leads), fs=ECG_RATE_HZ,
                            original_fs=fs, builder_version=BUILDER_VERSION)
        tmp.replace(out)
        done.append(f"{name}: ok ({signal.shape[1] / ECG_RATE_HZ / 60:.1f} min, {len(leads)} channels)")
    return done


def _manifest_row(db, record, patient, pool, leads, n, aami, status) -> dict:
    counts = {c: int(np.sum(np.asarray(aami) == c)) for c in AAMI_CLASSES}
    return {"db": db, "record": record, "patient": patient, "pool": pool, "split": "",
            "leads": len(leads), "minutes": round(n / ECG_RATE_HZ / 60, 1),
            **{f"n_{c}": counts[c] for c in AAMI_CLASSES}, "status": status}


def summarize(rows: list[dict]) -> str:
    """Per split and class: beats (×leads = windows the model can see)."""
    lines = [f"{'split':<9}{'patients':>9}" + "".join(f"{c:>10}" for c in AAMI_CLASSES) + f"{'windows':>12}"]
    for split in ("train", "val", "test", "excluded"):
        sel = [r for r in rows if r["split"] == split]
        if not sel:
            continue
        patients = len({r["patient"] for r in sel})
        beats = {c: sum(r[f"n_{c}"] for r in sel) for c in AAMI_CLASSES}
        windows = sum(sum(r[f"n_{c}"] for c in AAMI_CLASSES) * r["leads"] for r in sel)
        lines.append(f"{split:<9}{patients:>9}" + "".join(f"{beats[c]:>10}" for c in AAMI_CLASSES)
                     + f"{windows:>12}")
    return "\n".join(lines)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--db", nargs="*", default=None, help="databases (default: all found)")
    ap.add_argument("--raw", type=Path, default=RAW_ROOT)
    ap.add_argument("--out", type=Path, default=OUT_ROOT)
    ap.add_argument("--force", action="store_true", help="rebuild records that already exist")
    args = ap.parse_args(argv)

    delay = morphology_delay_samples()
    dbs = args.db or [d for d in ANNOTATION_EXT if (args.raw / d).is_dir()]
    rows = []
    for db in dbs:
        raw_dir = args.raw / db
        records = list_local_records(raw_dir, ANNOTATION_EXT[db])
        print(f"{db}: {len(records)} records", flush=True)
        for rec in records:
            row = build_record(db, rec, raw_dir, args.out, delay, args.force)
            rows.append(row)
            print(f"  {rec}: {row['patient']}, {row['leads']} leads, {row['minutes']} min - {row['status']}",
                  flush=True)
    if (args.raw / "nstdb").is_dir():
        for line in build_noise(args.raw / "nstdb", args.out, args.force):
            print(f"  noise {line}", flush=True)

    # The manifest covers every built record, also of databases not rebuilt in this run.
    manifest = args.out / "manifest.csv"
    old = []
    if manifest.exists() and args.db:
        with open(manifest, newline="", encoding="utf-8") as f:
            old = [r for r in csv.DictReader(f) if r["db"] not in dbs]
        for r in old:
            for k in ("leads", *(f"n_{c}" for c in AAMI_CLASSES)):
                r[k] = int(r[k])
    rows = old + rows
    assign_splits(rows)
    args.out.mkdir(parents=True, exist_ok=True)
    fields = ["db", "record", "patient", "pool", "split", "leads", "minutes",
              *(f"n_{c}" for c in AAMI_CLASSES)]
    with open(manifest, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fields, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    (args.out / "build.json").write_text(json.dumps({
        "builderVersion": BUILDER_VERSION, "rateHz": ECG_RATE_HZ, "unit": "uV (int16)",
        "filter": {"highPassHz": MORPH_HP_HZ, "highPassOrder": MORPH_HP_ORDER,
                   "lowPassHz": MORPH_LP_HZ, "lowPassOrder": MORPH_LP_ORDER, "causal": True},
        "beatDelaySamples": delay, "classes": AAMI_CLASSES,
        "split": {"mitdb": "de Chazal DS1 (train/val) / DS2 (test), paced excluded",
                  "other": f"whole patients, stratified on S/V: test {TEST_PERCENT}%, val {VAL_PERCENT}%"},
    }, indent=2), encoding="utf-8")
    print()
    print(summarize(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
