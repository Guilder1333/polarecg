"""ECG segments with UTC time, the same way the Kotlin analysis builds them.

Mirrors core-dsp ``dsp/analysis/EcgSegments.kt`` and ``dsp/time/TimeMapper.kt`` so that the
sample times computed here match ``beats.csv`` written by ``:tools:cli analyze``.
If you change the rules there, change them here too (``test_polaris_segments.py`` checks
the converted reference records, where the answer is known exactly).
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass

import numpy as np

from polaris_raw import ECG_RATE_HZ, SENSOR_EPOCH_UNIX_MS, Ecg

CONTIGUOUS_TOLERANCE_NS = 25 * 1_000_000


class TimeMapper:
    """Sensor clock -> phone UTC. The estimate is the smallest recent (phone - sensor) offset."""

    def __init__(self, jump_threshold_ms=1_000, window_ms=60_000, confirm_frames=5):
        self.jump_threshold_ms = jump_threshold_ms
        self.window_ms = window_ms
        self.confirm_frames = confirm_frames
        self.connection = -1
        self._window: deque[tuple[int, int]] = deque()   # (phone_ms, offset_ms)
        self._pending_up = 0
        self.estimate: int | None = None

    def start_connection(self):
        self.connection += 1
        self._window.clear()
        self._pending_up = 0
        self.estimate = None

    def observe(self, sensor_ns: int, phone_ms: int):
        if self.connection < 0:
            self.start_connection()
        offset = phone_ms - (sensor_ns // 1_000_000 + SENSOR_EPOCH_UNIX_MS)
        if self.estimate is None:
            self._accept(phone_ms, offset)
            return
        deviation = offset - self.estimate
        if deviation < -self.jump_threshold_ms:
            self._jump(phone_ms, offset)
        elif deviation > self.jump_threshold_ms:
            self._pending_up += 1
            if self._pending_up >= self.confirm_frames:
                self._jump(phone_ms, offset)
        else:
            self._pending_up = 0
            self._accept(phone_ms, offset)

    def to_utc_ms(self, sensor_ns: int) -> int | None:
        if self.estimate is None:
            return None
        return sensor_ns // 1_000_000 + SENSOR_EPOCH_UNIX_MS + self.estimate

    def _accept(self, phone_ms, offset):
        self._window.append((phone_ms, offset))
        while self._window[0][0] < phone_ms - self.window_ms:
            self._window.popleft()
        self.estimate = min(o for _, o in self._window)

    def _jump(self, phone_ms, offset):
        self._window.clear()
        self._pending_up = 0
        self._accept(phone_ms, offset)


@dataclass
class Segment:
    """A gap-free stretch of ECG. ``start_utc_ms`` is the UTC of its first sample.

    Time follows the sensor clock, not the nominal 130 Hz: the H10 really samples at ~130.27 Hz.
    ``frame_start_index`` / ``frame_sensor_ns`` give, per frame, the index of its first sample and
    its sensor time; positions in between are interpolated (as in the Kotlin ``EcgSegment``).
    Without frame data the nominal rate is used.
    """
    index: int
    start_utc_ms: int
    samples_uv: np.ndarray
    frame_start_index: np.ndarray | None = None
    frame_sensor_ns: np.ndarray | None = None

    def _knots(self) -> tuple[np.ndarray, np.ndarray]:
        """(sample index, ms since the first sample) at every frame start and at the segment end."""
        n = len(self.samples_uv)
        if self.frame_start_index is None or len(self.frame_start_index) == 0:
            return np.array([0.0, float(n)]), np.array([0.0, n * 1000 / ECG_RATE_HZ])
        idx = self.frame_start_index.astype(float)
        ms = (self.frame_sensor_ns - self.frame_sensor_ns[0]).astype(float) / 1e6
        if len(idx) >= 2:
            ms_per_sample = (ms[-1] - ms[-2]) / (idx[-1] - idx[-2])
        else:
            ms_per_sample = 1000 / ECG_RATE_HZ
        return np.append(idx, float(n)), np.append(ms, ms[-1] + (n - idx[-1]) * ms_per_sample)

    @property
    def end_utc_ms(self) -> float:
        idx, ms = self._knots()
        return self.start_utc_ms + ms[-1]

    def times_ms(self, lo: int = 0, hi: int | None = None) -> np.ndarray:
        """UTC ms of samples ``lo:hi`` (float; Kotlin rounds to whole ms)."""
        hi = len(self.samples_uv) if hi is None else hi
        idx, ms = self._knots()
        return self.start_utc_ms + np.interp(np.arange(lo, hi), idx, ms)

    def index_at(self, utc_ms: float) -> int:
        """Index of the sample nearest to ``utc_ms`` (clamped to the segment)."""
        idx, ms = self._knots()
        i = np.interp(utc_ms - self.start_utc_ms, ms, idx)
        return int(min(max(round(i), 0), len(self.samples_uv) - 1))


def build_segments(ecg: Ecg) -> list[Segment]:
    """Cuts the frames into gap-free segments (a frame must start within 25 ms of where the
    previous one ended on the sensor clock)."""
    mapper = TimeMapper()
    segments: list[Segment] = []
    chunks: list[np.ndarray] = []
    frame_starts: list[int] = []
    frame_ns: list[int] = []
    seg_samples = 0
    seg_start = 0
    expected_ns: int | None = None
    last_sensor_ns: int | None = None

    def close():
        nonlocal seg_samples
        if chunks:
            segments.append(Segment(len(segments), seg_start, np.concatenate(chunks),
                                    np.array(frame_starts, dtype=np.int64), np.array(frame_ns, dtype=np.int64)))
            chunks.clear()
            frame_starts.clear()
            frame_ns.clear()
            seg_samples = 0

    pos = 0
    for phone_ms, sensor_ns, n in zip(ecg.frame_phone_ms.tolist(),
                                      ecg.frame_sensor_ns.tolist(),
                                      ecg.frame_n.tolist()):
        samples = ecg.samples_uv[pos:pos + n]
        pos += n
        if n == 0:
            continue
        if last_sensor_ns is not None and sensor_ns < last_sensor_ns - 1_000_000_000:
            mapper.start_connection()
        last_sensor_ns = sensor_ns
        last_sample_ns = sensor_ns + (n - 1) * 1_000_000_000 // ECG_RATE_HZ
        mapper.observe(last_sample_ns, phone_ms)

        contiguous = expected_ns is not None and abs(sensor_ns - expected_ns) <= CONTIGUOUS_TOLERANCE_NS
        if not contiguous:
            close()
            utc = mapper.to_utc_ms(sensor_ns)
            seg_start = phone_ms if utc is None else utc
        chunks.append(samples)
        frame_starts.append(seg_samples)
        frame_ns.append(sensor_ns)
        seg_samples += n
        expected_ns = sensor_ns + n * 1_000_000_000 // ECG_RATE_HZ
    close()
    return segments
