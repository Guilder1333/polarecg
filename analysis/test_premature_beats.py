"""Offline tests of the rough premature-beat count: python -m unittest discover -s analysis"""
import sys
import unittest
from datetime import timedelta, timezone
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import premature_beats as pb  # noqa: E402

LEN = pb.PRE + pb.POST + 1 + 2 * pb.MAX_LAG
T = np.arange(LEN) - (pb.MAX_LAG + pb.PRE)  # samples from the R mark


def normal_shape(rng):
    return 1000 * np.exp(-T ** 2 / 4) - 300 * np.exp(-(T - 4) ** 2 / 4) + rng.normal(0, 10, LEN)


def wide_shape(rng):  # a PVC: wide, opposite direction
    return -900 * np.exp(-T ** 2 / 40) + rng.normal(0, 10, LEN)


def beats(rr):
    t = np.cumsum(rr)
    return pd.DataFrame({"utcMs": t, "segment": 0, "rrMs": [np.nan] + list(rr[1:]), "good": 1})


class ClassifyTest(unittest.TestCase):
    def run_case(self, rr, shapes, good=None):
        b = beats(np.array(rr, float))
        if good is not None:
            b["good"] = good
        out = pb.classify(b, np.array(shapes), corr_min=0.9, early=0.85, pause=1.15, v_early=0.95)
        return out["class"].tolist()

    def test_early_normal_beat_is_s_and_early_wide_beat_is_v(self):
        rng = np.random.default_rng(1)
        rr = [800.0] * 120
        rr[40], rr[41] = 560, 1000          # PAC: early, then a (non-compensatory) pause
        rr[80], rr[81] = 520, 1080          # PVC: early, full compensatory pause
        shapes = [wide_shape(rng) if i == 80 else normal_shape(rng) for i in range(120)]
        cls = self.run_case(rr, shapes)
        self.assertEqual(cls[40], "S")
        self.assertEqual(cls[80], "V")
        self.assertEqual(cls[0], "Q")       # no RRpre
        self.assertEqual(cls[-1], "Q")      # no RRpost
        self.assertEqual(sum(c in "SV" for c in cls), 2)

    def test_different_shape_at_normal_timing_is_not_v(self):
        rng = np.random.default_rng(2)
        shapes = [wide_shape(rng) if i == 60 else normal_shape(rng) for i in range(120)]
        self.assertEqual(self.run_case([800.0] * 120, shapes)[60], "N")

    def test_gradual_speed_up_is_not_s(self):
        # breathing: RR falls 20 % over a few beats and comes back; no pause after the short ones
        rng = np.random.default_rng(3)
        rr = [800 - 160 * max(0, 1 - abs(i % 12 - 6) / 3) for i in range(120)]
        cls = self.run_case(rr, [normal_shape(rng) for _ in range(120)])
        self.assertNotIn("S", cls)

    def test_bigeminy_is_found_without_a_normal_reference(self):
        rng = np.random.default_rng(4)
        rr = [800.0] * 20 + [560.0, 1040.0] * 40 + [800.0] * 20
        shapes = [wide_shape(rng) if 20 <= i < 100 and i % 2 == 0 else normal_shape(rng) for i in range(120)]
        cls = self.run_case(rr, shapes)
        self.assertGreaterEqual(sum(cls[i] == "V" for i in range(20, 100, 2)), 38)

    def test_poor_quality_beat_is_not_judged(self):
        rng = np.random.default_rng(5)
        good = [1] * 120
        good[50] = 0
        self.assertEqual(self.run_case([800.0] * 120, [normal_shape(rng) for _ in range(120)], good)[50], "Q")


class ArgsTest(unittest.TestCase):
    def test_tz_and_bin(self):
        self.assertEqual(pb.parse_tz("+09:00"), timezone(timedelta(hours=9)))
        self.assertEqual(pb.parse_tz("UTC"), timezone.utc)
        self.assertEqual(pb.parse_bin("30min"), pd.Timedelta(minutes=30))
        self.assertEqual(pb.parse_bin("6h"), pd.Timedelta(hours=6))


if __name__ == "__main__":
    unittest.main()
