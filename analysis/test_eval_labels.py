"""Tests of eval_labels.py: python -m unittest discover -s analysis"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import eval_labels as ev  # noqa: E402


class SsEvaluationTest(unittest.TestCase):
    # (our class, owner's label) -> weighted count
    conf = {("SS", "SS"): 8.0, ("SS", "N"): 2.0, ("N", "SS"): 1.0, ("S", "S"): 20.0, ("SS", "S"): 1.0, ("S", "SS"): 1.0,
            ("Q", "SS"): 3.0, ("S", "X"): 5.0}

    def test_ss_alone(self):
        se, ppv = ev.se_ppv(self.conf, "SS")
        self.assertAlmostEqual(se, 8 / 13)      # owner SS: 8 + 1 (N) + 1 (S) + 3 (Q)
        self.assertAlmostEqual(ppv, 8 / 11)     # our SS: 8 + 2 (N) + 1 (S); the X and Q rows are not judged

    def test_the_s_family_counts_s_and_ss_as_one(self):
        se, ppv = ev.se_ppv(self.conf, ev.S_FAMILY)
        # in the family both ways: SS/SS 8, S/S 20, SS/S 1, S/SS 1 = 30; owner S or SS: 8+1+3+20+1(SS->S)+1(S->SS) = 34
        self.assertAlmostEqual(se, 30 / 34)
        # our S or SS and judged (N, S, SS, V): SS 8+2+1, S 20+1 = 32 (the S/X 5 is not judged)
        self.assertAlmostEqual(ppv, 30 / 32)

    def test_a_single_class_still_works_and_empty_is_none(self):
        self.assertEqual(ev.se_ppv({}, "V"), (None, None))
        se, ppv = ev.se_ppv({("V", "V"): 3.0, ("V", "N"): 1.0}, "V")
        self.assertAlmostEqual(ppv, 0.75)


if __name__ == "__main__":
    unittest.main()
