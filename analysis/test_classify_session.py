"""Offline tests of classify_session.py helpers."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import classify_session as cs  # noqa: E402


class CrosstabTest(unittest.TestCase):
    def test_counts_and_row_order(self):
        text = cs.crosstab(["Q", "N", "V", "N"], ["X", "N", "S", "N"]).splitlines()
        self.assertTrue(text[1].startswith("N"))   # app classes in N, S, V, Q order
        self.assertEqual(text[1].split()[1:], ["2", "0", "0", "0", "2"])
        self.assertEqual(text[2].split()[1:], ["0", "1", "0", "0", "1"])  # V -> S
        self.assertEqual(text[3].split()[1:], ["0", "0", "0", "1", "1"])  # Q -> X


if __name__ == "__main__":
    unittest.main()
