"""Offline tests of the review's pure parts (no phone, no model)."""
import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import review_night as rn  # noqa: E402


def check(**kw):
    c = {"app": "N", "model": "N", "seedsAgree": True, "strap": "beat", "motion": "still", "shape": "normal",
         "timing": "on time", "pause": "-"}
    c.update(kw)
    return c


class VerdictTest(unittest.TestCase):
    def test_timing(self):
        self.assertEqual(rn.timing_verdict(1.0, 1.0), ("on time", "-"))
        self.assertEqual(rn.timing_verdict(0.63, 1.38), ("early", "compensatory"))   # owner's V, sum 2.01
        self.assertEqual(rn.timing_verdict(0.56, 1.06), ("early", "resets"))         # owner's S, sum 1.62
        self.assertEqual(rn.timing_verdict(0.54, 1.33), ("early", "between"))        # sum 1.87

    def test_shape_and_strap(self):
        self.assertEqual(rn.shape_verdict(0.95), "normal")
        self.assertEqual(rn.shape_verdict(-0.4), "different")
        self.assertEqual(rn.shape_verdict(0.75), "between")
        self.assertEqual(rn.shape_verdict(float("nan")), "-")
        self.assertEqual(rn.strap_verdict(True, True), "beat")
        self.assertEqual(rn.strap_verdict(False, False), "no beat")
        self.assertEqual(rn.strap_verdict(True, False), "unclear")
        self.assertEqual(rn.strap_verdict(None, True), "unclear")
        self.assertEqual(rn.strap_verdict(None, None), "-")


class GroupTest(unittest.TestCase):
    def test_priority_order(self):
        self.assertEqual(rn.group_of(check(model="V", app="S", seedsAgree=False)), "model V, app not V")
        self.assertEqual(rn.group_of(check(app="V", model="N")), "app V, model not V")
        self.assertEqual(rn.group_of(check(model="X")), "model noise, app beat")
        self.assertEqual(rn.group_of(check(app="X")), "app noise, model beat")
        self.assertEqual(rn.group_of(check(strap="no beat")), "strap: no beat")
        self.assertEqual(rn.group_of(check(seedsAgree=False)), "seeds disagree")
        self.assertEqual(rn.group_of(check(shape="different")), "all N, shape different")
        self.assertIsNone(rn.group_of(check()))

    def test_agreed(self):
        self.assertEqual(rn.agreed_class(check()), "N")
        self.assertIsNone(rn.agreed_class(check(motion="moving")))
        self.assertEqual(rn.agreed_class(check(app="S", timing="early", pause="resets")), "S")
        self.assertIsNone(rn.agreed_class(check(app="S", timing="early", pause="between")))
        self.assertEqual(rn.agreed_class(check(app="V", model="V", shape="different", timing="early")), "V")
        self.assertEqual(rn.agreed_class(check(app="X", model="X", strap="unclear", motion="moving")), "X")


class QueueTest(unittest.TestCase):
    def test_pick_spread_covers_the_night(self):
        idx = np.arange(1000)
        got = rn.pick_spread(idx, 10, np.random.default_rng(0))
        self.assertEqual(len(got), 10)
        self.assertTrue(all(100 * k <= g < 100 * (k + 1) for k, g in enumerate(got)))
        self.assertEqual(rn.pick_spread(np.arange(3), 10, np.random.default_rng(0)).tolist(), [0, 1, 2])

    def test_build_queue_caps_and_order(self):
        checks = [dict(check(), utcMs=i, group="seeds disagree" if i % 2 else "", agreed="" if i % 2 else "N")
                  for i in range(40)]
        q = rn.build_queue(checks, per_group=5, per_agreed=3)
        self.assertEqual([r["group"] for r in q], ["seeds disagree"] * 5 + ["agreed N"] * 3)
        self.assertEqual([r["item"] for r in q], list(range(1, 9)))
        first = [r["utcMs"] for r in q[:5]]
        self.assertEqual(first, sorted(first))

    def test_latest_decisions_ignore_old_labels(self):
        rows = [(1, "V", 50), (1, "N", 150), (2, "S", 120), (2, "", 130), (3, "X", 99)]
        self.assertEqual(rn.latest_decisions(rows, 100), {1: "N"})   # 2 undone, 3 too old


class StretchTest(unittest.TestCase):
    def setUp(self):
        self.t = np.arange(0, 60_000, 1000, dtype=np.int64)          # one beat a second

    def marks(self, t, paired=None):
        paired = paired if paired is not None else [None] + [True] * (len(t) - 1)
        return rn.mark_stretches(t, np.zeros(len(t), np.int64), np.full(len(t), 1000.0), paired)

    def test_clean_rhythm_has_no_stretch(self):
        self.assertEqual(set(self.marks(self.t)), {""})

    def test_one_false_detection_stays_reviewable(self):
        t = np.sort(np.append(self.t, 30_400))                        # 2 short intervals only
        self.assertEqual(set(self.marks(t)), {""})

    def test_burst_is_crowded_with_margin(self):
        t = np.sort(np.concatenate([self.t, [30_200, 30_450, 30_700]]))
        m = self.marks(t)
        crowded = t[[k for k, x in enumerate(m) if x == "crowded"]]
        self.assertLessEqual(crowded.min(), 28_000)
        self.assertGreaterEqual(crowded.max(), 32_000)
        self.assertEqual(m[0], "")

    def test_long_interval_strap_does_not_have_is_missed_beats(self):
        t = np.delete(self.t, [30, 31])                               # 3 s gap
        paired = [None] + [True] * (len(t) - 1)
        paired[30] = False                                            # the gap's interval: no strap pair
        self.assertEqual(self.marks(t, paired)[30], "missed beats")
        paired[30] = True                                             # strap has it too: a real pause
        self.assertEqual(set(self.marks(t, paired)), {""})

    def test_summary(self):
        marks = [""] * 5 + ["crowded"] * 3 + [""] * 2 + ["missed beats"] * 2
        t = np.arange(12) * 60_000
        s = rn.stretch_summary(t, marks)
        self.assertEqual(s["crowded"], (1, 2.0))
        self.assertEqual(s["missed beats"], (1, 1.0))

    def test_nearest_decision(self):
        self.assertEqual(rn.nearest_decision(1000, {1410: "Q", 5000: "N", 1000: "V"}), (410, "Q"))
        self.assertIsNone(rn.nearest_decision(1000, {3000: "N"}))


class TimeTest(unittest.TestCase):
    def test_local_time(self):
        from datetime import timedelta
        self.assertEqual(rn.local_time(1794854893271, timedelta(hours=9)), "2026-11-17 03:48:13.271")


if __name__ == "__main__":
    unittest.main()
