"""Reader for PolarisECG raw recordings (docs/RAW_FORMAT.md).

Mirrors core-dsp ``dsp/raw/RawFormat.kt`` and ``RawSession.kt``. Only numpy is needed.

    from polaris_raw import read_session
    s = read_session("data/sessions/20261012T221530Z")
    s.ecg.samples_uv                          # one entry per sample, 130 Hz
    s.hr.hr_bpm, s.hr.rr_ms, s.hr.rr_phone_ms
"""
from __future__ import annotations

import json
import struct
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

MAGIC = b"PECG"
VERSION = 1
HEADER_SIZE = 8
STREAM_IDS = {"ecg": 1, "acc": 2, "hr": 3}
ECG_RATE_HZ = 130
ACC_RATE_HZ = 50
SENSOR_EPOCH_UNIX_MS = 946_684_800_000  # sensor clock counts ns from 2000-01-01 UTC


@dataclass
class Ecg:
    """Per frame: ``frame_*`` arrays. Per sample: ``samples_uv`` and ``frame_index``."""
    frame_phone_ms: np.ndarray    # int64, phone UTC ms when the frame arrived
    frame_sensor_ns: np.ndarray   # int64, sensor clock of the first sample
    frame_n: np.ndarray           # samples per frame
    samples_uv: np.ndarray        # int32, concatenated over all frames
    frame_index: np.ndarray       # which frame each sample came from


@dataclass
class Acc:
    frame_phone_ms: np.ndarray
    frame_sensor_ns: np.ndarray
    frame_n: np.ndarray
    xyz_mg: np.ndarray            # int16, shape (samples, 3)
    frame_index: np.ndarray


@dataclass
class Hr:
    phone_ms: np.ndarray          # one per notification
    hr_bpm: np.ndarray
    contact: np.ndarray           # int8: 1 contact, 0 none, -1 not reported
    rr_ms: np.ndarray             # all RR intervals, concatenated
    rr_phone_ms: np.ndarray       # phone time of the notification each RR came with


@dataclass
class Session:
    ecg: Ecg
    acc: Acc
    hr: Hr
    meta: dict = field(default_factory=dict)
    unreadable: list = field(default_factory=list)


def list_segment_files(folder, prefix: str) -> list[Path]:
    """Finished files of one stream oldest first (name order), then the current ``<prefix>.bin``."""
    folder = Path(folder)
    finished = sorted(p for p in folder.glob(f"{prefix}_*.bin") if p.is_file())
    current = folder / f"{prefix}.bin"
    return finished + ([current] if current.is_file() else [])


def _header(data: bytes) -> int:
    """Validates the header and returns the stream id. Raises ValueError if bad."""
    if len(data) < HEADER_SIZE:
        raise ValueError("file too short for a header")
    magic, version, stream = struct.unpack_from("<4sHH", data, 0)
    if magic != MAGIC:
        raise ValueError("not a PolarisECG raw file")
    if version != VERSION:
        raise ValueError(f"unsupported raw format version {version}")
    return stream


def _ecg_records(data: bytes):
    """Yields (phone_ms, sensor_ns, samples) per complete record; a truncated tail is ignored."""
    pos, end = HEADER_SIZE, len(data)
    while end - pos >= 18:
        phone, sensor, n = struct.unpack_from("<qqH", data, pos)
        if end - pos - 18 < 4 * n:
            break
        yield phone, sensor, np.frombuffer(data, "<i4", n, pos + 18)
        pos += 18 + 4 * n


def _acc_records(data: bytes):
    pos, end = HEADER_SIZE, len(data)
    while end - pos >= 18:
        phone, sensor, n = struct.unpack_from("<qqH", data, pos)
        if end - pos - 18 < 6 * n:
            break
        yield phone, sensor, np.frombuffer(data, "<i2", 3 * n, pos + 18).reshape(n, 3)
        pos += 18 + 6 * n


def _hr_records(data: bytes):
    pos, end = HEADER_SIZE, len(data)
    while end - pos >= 12:
        phone, hr, contact, n = struct.unpack_from("<qHbB", data, pos)
        if end - pos - 12 < 2 * n:
            break
        yield phone, hr, contact, np.frombuffer(data, "<u2", n, pos + 12)
        pos += 12 + 2 * n


def _load(folder, prefix: str, parse):
    """Parses all segments of a stream. Returns (list of records, list of unreadable files)."""
    records, unreadable = [], []
    for path in list_segment_files(folder, prefix):
        data = path.read_bytes()
        try:
            if _header(data) != STREAM_IDS[prefix]:
                raise ValueError("wrong stream type")
        except ValueError:
            unreadable.append(path)
            continue
        records.extend(parse(data))
    return records, unreadable


def read_ecg(folder) -> tuple[Ecg, list]:
    recs, bad = _load(folder, "ecg", _ecg_records)
    n = np.array([len(r[2]) for r in recs], dtype=np.int64)
    samples = np.concatenate([r[2] for r in recs]).astype(np.int32) if recs else np.zeros(0, np.int32)
    return Ecg(
        np.array([r[0] for r in recs], dtype=np.int64),
        np.array([r[1] for r in recs], dtype=np.int64),
        n,
        samples,
        np.repeat(np.arange(len(recs)), n),
    ), bad


def read_acc(folder) -> tuple[Acc, list]:
    recs, bad = _load(folder, "acc", _acc_records)
    n = np.array([len(r[2]) for r in recs], dtype=np.int64)
    xyz = np.concatenate([r[2] for r in recs]).astype(np.int16) if recs else np.zeros((0, 3), np.int16)
    return Acc(
        np.array([r[0] for r in recs], dtype=np.int64),
        np.array([r[1] for r in recs], dtype=np.int64),
        n,
        xyz,
        np.repeat(np.arange(len(recs)), n),
    ), bad


def read_hr(folder) -> tuple[Hr, list]:
    recs, bad = _load(folder, "hr", _hr_records)
    counts = np.array([len(r[3]) for r in recs], dtype=np.int64)
    phone = np.array([r[0] for r in recs], dtype=np.int64)
    rr = np.concatenate([r[3] for r in recs]).astype(np.uint16) if recs else np.zeros(0, np.uint16)
    return Hr(
        phone,
        np.array([r[1] for r in recs], dtype=np.uint16),
        np.array([r[2] for r in recs], dtype=np.int8),
        rr,
        np.repeat(phone, counts),
    ), bad


def write_ecg_file(path, samples_uv, start_phone_ms: int, frame_len: int = 70) -> int:
    """Writes ``samples_uv`` (130 Hz, microvolts) as one ECG raw file; returns the frame count.

    Used for converted reference databases. Frames of ``frame_len`` samples get sensor times from
    the sample index (no gaps). The phone time of a frame is the time of its LAST sample, like a
    phone that receives a frame the moment it is complete, so the time mapping of
    ``TimeMapper`` (core-dsp) recovers ``start_phone_ms`` for sample 0 exactly.
    """
    samples = np.asarray(samples_uv).astype("<i4")
    parts = [MAGIC + struct.pack("<HH", VERSION, STREAM_IDS["ecg"])]
    frames = 0
    for start in range(0, len(samples), frame_len):
        chunk = samples[start:start + frame_len]
        first_ms = start_phone_ms + round(start * 1000 / ECG_RATE_HZ)
        phone_ms = start_phone_ms + round((start + len(chunk) - 1) * 1000 / ECG_RATE_HZ)
        sensor_ns = (first_ms - SENSOR_EPOCH_UNIX_MS) * 1_000_000
        parts.append(struct.pack("<qqH", phone_ms, sensor_ns, len(chunk)) + chunk.tobytes())
        frames += 1
    Path(path).write_bytes(b"".join(parts))
    return frames


def read_session(folder) -> Session:
    """Reads a whole session folder: all three streams plus ``session.json`` (if present)."""
    folder = Path(folder)
    ecg, bad_e = read_ecg(folder)
    acc, bad_a = read_acc(folder)
    hr, bad_h = read_hr(folder)
    meta_path = folder / "session.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.is_file() else {}
    return Session(ecg, acc, hr, meta, bad_e + bad_a + bad_h)
