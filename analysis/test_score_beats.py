"""Tests for the beat scorer: python -m unittest discover -s analysis"""
import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import score_beats as sb  # noqa: E402


def score(ref, det, ref_c=None, det_c=None, tol=150.0):
    ref_c = ref_c or ["N"] * len(ref)
    det_c = det_c or ["U"] * len(det)
    return sb.score_record(np.array(ref, float), ref_c, np.array(det, float), det_c, tol)


class MatchTest(unittest.TestCase):
    def test_perfect(self):
        r = score([1000, 2000, 3000], [1010, 1995, 3020])
        self.assertEqual((r["tp"], r["fp"], r["fn"]), (3, 0, 0))

    def test_missed_beat_is_fn(self):
        r = score([1000, 2000, 3000], [1000, 3000])
        self.assertEqual((r["tp"], r["fp"], r["fn"]), (2, 0, 1))

    def test_extra_beat_is_fp(self):
        r = score([1000, 3000], [1000, 2000, 3000])
        self.assertEqual((r["tp"], r["fp"], r["fn"]), (2, 1, 0))

    def test_double_detection_counts_once(self):
        r = score([1000], [990, 1060])
        self.assertEqual((r["tp"], r["fp"], r["fn"]), (1, 1, 0))

    def test_tolerance_edges(self):
        self.assertEqual(score([1000], [1150])["tp"], 1)   # exactly on the edge: match
        self.assertEqual(score([1000], [1151])["tp"], 0)   # just outside: miss and extra
        self.assertEqual(score([1000], [849])["tp"], 0)

    def test_nearest_detection_wins(self):
        pairs = sb.match_beats(np.array([1000.0]), np.array([900.0, 1020.0, 1100.0]), 150)
        self.assertEqual(pairs, [(0, 1)])

    def test_empty_inputs(self):
        r = score([], [])
        self.assertEqual((r["tp"], r["fp"], r["fn"]), (0, 0, 0))
        self.assertIsNone(sb.ratio(0, 0))

    def test_skip_start_drops_early_beats_on_both_sides(self):
        t = sb.START_MS
        r = sb.score_record(np.array([t + 1000, t + 20000.0]), ["N", "N"],
                            np.array([t + 20000.0]), ["U"], 150, skip_start_s=10)
        self.assertEqual((r["tp"], r["fp"], r["fn"]), (1, 0, 0))


class ClassTest(unittest.TestCase):
    def test_per_class_se_and_ppv(self):
        # ref: N V V S ; ours: N V N (the 2nd V is called N) and an extra V in the noise
        r = score([1000, 2000, 3000, 4000], [1000, 2000, 3000, 9000],
                  ["N", "V", "V", "S"], ["N", "V", "N", "V"])
        s = sb.class_stats(r)
        self.assertEqual(s["V"]["se"], 0.5)          # 1 of 2 reference V found
        self.assertEqual(s["V"]["ppv"], 0.5)         # ours: 1 correct V + 1 unmatched V
        self.assertEqual(s["S"]["se"], 0.0)
        self.assertIsNone(s["S"]["ppv"])             # we never said S
        self.assertEqual(r["confusion"][("V", "N")], 1)


class FilterTest(unittest.TestCase):
    def test_splits_are_disjoint_and_exclude_paced(self):
        self.assertFalse(sb.DS1 & sb.DS2)
        self.assertEqual(len(sb.DS1) + len(sb.DS2), 44)  # 48 records minus 4 paced
        self.assertFalse((sb.DS1 | sb.DS2) & sb.PACED)


if __name__ == "__main__":
    unittest.main()
