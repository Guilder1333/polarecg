# Learned classifier: setting up a PC and resuming the work

Everything for the learned beat classifier (plan Phase 7, tasks P7.1a–P7.1e, P7.2) runs from `analysis/`.
`data/` is git-ignored, so on a new PC the databases are downloaded and the dataset is rebuilt (or copied).
**Training needs no personal recordings**: only public PhysioNet databases.

Status on 2026-11-19: dataset built (MIT-BIH + INCART + svdb + NSTDB noise), model v1 trained
(`data/models/20261118-220745`, results in plan P7.1c/P7.1d). **Next step: model v2, plan P7.1e.**

## 1. Python and packages

Python 3.10 or newer (`py --version`). From the repository root:

```powershell
py -m pip install -r analysis\requirements.txt
```

PyTorch is installed separately, as the **CUDA build** (the plain `pip install torch` gives the CPU-only build).
Pick the CUDA version on <https://pytorch.org/get-started/locally/> (Windows, Pip, CUDA). On the first PC this was:

```powershell
py -m pip install --force-reinstall --no-deps torch==2.14.1 torchvision==0.29.2 --index-url https://download.pytorch.org/whl/cu132
```

- `--force-reinstall` is needed if a CPU build of the same version is already installed (pip otherwise says
  "Requirement already satisfied" and keeps the CPU build).
- The CUDA build of PyTorch carries its own CUDA; a separately installed CUDA Toolkit is not used.
- `cu126` (CUDA 12.6) also works; the NVIDIA driver must support the chosen CUDA version (`nvidia-smi`).

Check:

```powershell
py -c "import torch; print(torch.__version__, torch.cuda.is_available(), torch.cuda.get_device_name(0))"
```

## 2. Data

Either copy `data\dataset\` (≈ 330 MB) and, if wanted, `data\models\` (a few MB) from the other PC, or rebuild:

```powershell
py analysis\fetch_physionet.py --db mitdb incartdb nstdb svdb   # ≈ 1 GB, resumes and retries; rerun to continue
py analysis\build_dataset.py                                    # → data\dataset\, a few minutes
```

`build_dataset.py` prints the per-split class counts; they should match plan P7.1b (train 82 patients, N 220,637 /
S 9,135 / V 20,719 ...). The split is deterministic.

## 3. Tests

```powershell
py -m unittest discover -s analysis -p "test_*.py"
```

## 4. Train, evaluate, run on a session

```powershell
py analysis\train_beats.py train                                  # → data\models\<time>\ (model.pt, config.json, history.json)
py analysis\train_beats.py evaluate data\models\<run>             # test split, per database
py analysis\train_beats.py train --seeds 0 1 2 3 4                # 5 models (~2 min each): compare changes on mean ± sd
py analysis\train_beats.py evaluate data\models\<run> --weights best_epoch.pt   # best single epoch instead of the average
py analysis\classify_session.py data\models\<run> data\sessions\<session>   # own night → data\model-out\
```

`classify_session.py` needs a recorded session (`beats.csv` + `ecg_*.bin`); run it on the PC that has the
recordings, copying only the model folder there.

Timing: 20 epochs × 200k windows ≈ 27 min on the first PC (GTX 1650 Ti, batches made on one core);
**113 s** on the second PC (RTX 4070 Ti, 24 cores) with batches made in 6 worker processes (`--workers`, default
up to 6; each worker loads the training set, ≈ 280 MB of memory). `--workers 0` makes batches in the training process.

Model folders store the dataset path of the PC that trained them; `evaluate` uses `--dataset` (default
`data\dataset` of this repository) instead, so a model folder copied from another PC works.

## 5. Where things are written down

- Plan and results: `docs/DEVELOPMENT_PLAN.md`, Phase 7 (P7.1a–e, P7.2 phone requirements).
- Telling PVC / PAC / noise apart, worked examples: `docs/READING_BEATS.md`.
- Scripts: `analysis/build_dataset.py`, `analysis/train_beats.py`, `analysis/classify_session.py`,
  `analysis/fetch_physionet.py` (each has its own docstring).
