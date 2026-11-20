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


class TimeTest(unittest.TestCase):
    def test_local_time(self):
        from datetime import timedelta
        self.assertEqual(rn.local_time(1794854893271, timedelta(hours=9)), "2026-11-17 03:48:13.271")


if __name__ == "__main__":
    unittest.main()
