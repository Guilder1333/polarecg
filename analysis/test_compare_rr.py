"""Tests for the RR comparison: python -m unittest discover -s analysis"""
import sys
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare_rr as cr  # noqa: E402


def make(n=300, seed=1):
    """A heart-beat series: beat times (ms) and the RR intervals between them."""
    rnd = np.random.default_rng(seed)
    rr = 800 + 40 * np.sin(np.arange(n) / 7) + rnd.normal(0, 8, n)
    return np.cumsum(rr), rr


class PairingTest(unittest.TestCase):
    def test_pairs_identical_sequences_one_to_one(self):
        t, rr = make()
        arrival = t + 600                      # the strap reports ~0.6 s after the beat
        paired = cr.pair_intervals(t[1:], rr[1:], arrival[1:], rr[1:])
        self.assertEqual(list(paired), list(range(len(rr) - 1)))

    def test_strap_has_extra_intervals_at_the_start(self):
        t, rr = make()
        # our list starts at beat 5; the strap list from beat 0
        paired = cr.pair_intervals(t[6:], rr[6:], t + 600, rr)
        self.assertEqual(list(paired), list(range(6, len(rr))))

    def test_small_differences_are_kept_and_measured(self):
        t, rr = make()
        ours = rr + np.random.default_rng(3).normal(0, 3, len(rr))
        paired = cr.pair_intervals(t, ours, t + 600, rr)
        self.assertEqual(list(paired), list(range(len(rr))))

    def test_a_missed_beat_leaves_the_merged_interval_unpaired_and_resyncs(self):
        t, rr = make()
        # we missed the beat between interval 100 and 101: one interval of rr[100] + rr[101]
        our_t = np.concatenate([t[:101], t[102:]])
        our_rr = np.concatenate([rr[:100], [rr[100] + rr[101]], rr[102:]])
        paired = cr.pair_intervals(our_t, our_rr, t + 600, rr)
        self.assertEqual(paired[100], -1)
        self.assertEqual(list(paired[:100]), list(range(100)))
        self.assertEqual(list(paired[101:]), list(range(102, len(rr))))

    def test_an_extra_beat_of_ours_leaves_both_halves_unpaired(self):
        t, rr = make()
        half = rr[100] * 0.45
        our_t = np.concatenate([t[:100], [t[99] + half], t[100:]])
        our_rr = np.concatenate([rr[:100], [half, rr[100] - half], rr[101:]])
        paired = cr.pair_intervals(our_t, our_rr, t + 600, rr)
        self.assertEqual(list(paired[:100]), list(range(100)))
        self.assertEqual(list(paired[100:102]), [-1, -1])
        self.assertEqual(list(paired[102:]), list(range(101, len(rr))))

    def test_strap_notifications_lost_do_not_break_the_pairing(self):
        t, rr = make()
        keep = np.ones(len(rr), bool)
        keep[150:154] = False                  # four intervals never arrived
        paired = cr.pair_intervals(t, rr, (t + 600)[keep], rr[keep])
        self.assertTrue(all(p == -1 for p in paired[150:154]))
        self.assertEqual(paired[149], 149)
        self.assertEqual(paired[154], 150)     # strap index shifted by the four lost ones

    def test_nothing_to_pair(self):
        self.assertEqual(list(cr.pair_intervals(np.array([1000.0]), np.array([800.0]), np.array([]), np.array([]))), [-1])
        self.assertEqual(len(cr.pair_intervals(np.array([]), np.array([]), np.array([1.0]), np.array([800.0]))), 0)


class IntervalsTest(unittest.TestCase):
    def test_intervals_stop_at_segment_borders_and_carry_quality(self):
        beats = pd.DataFrame({
            "utcMs": [1000, 1800, 2600, 10000, 10800],
            "segment": [0, 0, 0, 1, 1],
            "good": [1, 1, 0, 1, 1],
        })
        o = cr.our_intervals(beats)
        self.assertEqual(list(o.rr), [800, 800, 800])
        self.assertEqual(list(o.t), [1800, 2600, 10800])
        self.assertEqual(list(o.good), [True, False, True])


if __name__ == "__main__":
    unittest.main()
