"""Offline tests of classify_session.py helpers."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import classify_session as cs  # noqa: E402


class CrosstabTest(unittest.TestCase):
    def test_counts_and_row_order(self):
        # model v1 classes (N, S, V, X): the class list comes from the model's config.json
        text = cs.crosstab(["Q", "N", "V", "N"], ["X", "N", "S", "N"], ("N", "S", "V", "X")).splitlines()
        self.assertTrue(text[1].startswith("N"))   # app classes in N, S, V, Q order
        self.assertEqual(text[1].split()[1:], ["2", "0", "0", "0", "2"])
        self.assertEqual(text[2].split()[1:], ["0", "1", "0", "0", "1"])  # V -> S
        self.assertEqual(text[3].split()[1:], ["0", "0", "0", "1", "1"])  # Q -> X

    def test_default_classes_are_n_v_x(self):
        text = cs.crosstab(["S", "V"], ["N", "V"]).splitlines()
        self.assertEqual(text[0].split()[1:4], ["N", "V", "X"])
        self.assertEqual(text[1].split()[1:], ["1", "0", "0", "1"])  # app S, model N


if __name__ == "__main__":
    unittest.main()
