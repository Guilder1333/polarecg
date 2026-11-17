"""Offline tests of the dataset builder's pure parts (no PhysioNet files needed)."""
import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import build_dataset as bd  # noqa: E402


class FilterTest(unittest.TestCase):
    def test_delay_is_small_and_positive(self):
        d = bd.morphology_delay_samples()
        self.assertGreaterEqual(d, 0)
        self.assertLessEqual(d, 4)  # a few samples at 130 Hz

    def test_shifted_annotation_lands_on_filtered_peak(self):
        fs = bd.ECG_RATE_HZ
        x = np.zeros(fs * 20)
        beats = np.arange(fs, len(x) - fs, fs)  # 60 bpm
        t = np.arange(-6, 7)
        for b in beats:
            x[b + t] += 1000 * np.exp(-0.5 * (t / 1.5) ** 2)  # narrow QRS-like bump
        y = bd.filter_like_app(x)
        d = bd.morphology_delay_samples()
        for b in beats[2:]:
            peak = b - 5 + int(np.argmax(y[b - 5:b + 10]))
            self.assertLessEqual(abs(peak - (b + d)), 1)

    def test_filter_starts_settled_on_offset(self):
        y = bd.filter_like_app(np.full(1000, 500.0))
        self.assertLess(np.abs(y).max(), 1.0)  # no start-up step from the DC offset

    def test_int16_clips(self):
        out = bd.to_int16_uv(np.array([40000.0, -40000.0, 12.4]))
        self.assertEqual(out.tolist(), [32767, -32768, 12])


class BeatTableTest(unittest.TestCase):
    def test_rr_context(self):
        fs = bd.ECG_RATE_HZ
        s = np.array([0, 130, 260, 325, 520]) # 1000, 1000, 500 (early), 1500 ms
        rr = bd.rr_context(s, fs)
        self.assertEqual(rr["rr_pre"].tolist(), [-1, 1000, 1000, 500, 1500])
        self.assertEqual(rr["rr_post"].tolist(), [1000, 1000, 500, 1500, -1])
        self.assertEqual(rr["rr_pre2"].tolist(), [-1, -1, 1000, 1000, 500])
        # rr_local excludes rr_pre: for the early beat it is the usual 1000, not pulled down
        self.assertEqual(rr["rr_local"].tolist(), [-1, -1, 1000, 1000, 1000])

    def test_beat_table_drops_non_beats_and_maps_aami(self):
        t = bd.beat_table([10, 360, 720, 1080, 1440], ["+", "N", "A", "V", "~"], 360.0, 10_000, 2)
        self.assertEqual(t["beat_symbol"].tolist(), ["N", "A", "V"])
        self.assertEqual(t["beat_aami"].tolist(), ["N", "S", "V"])
        self.assertEqual(t["beat_sample"].tolist(), [132, 262, 392])  # 130 Hz grid + delay 2

    def test_beat_table_drops_beats_outside_signal(self):
        t = bd.beat_table([360, 3600], ["N", "N"], 360.0, 500, 0)
        self.assertEqual(t["beat_sample"].tolist(), [130])


class WindowTest(unittest.TestCase):
    def test_inside(self):
        w = bd.cut_window(np.arange(100), 50, 3, 2)
        self.assertEqual(w.tolist(), [47, 48, 49, 50, 51])

    def test_zero_padding_at_edges_and_multi_lead(self):
        sig = np.vstack([np.arange(1, 11), -np.arange(1, 11)])
        w = bd.cut_window(sig, 1, 3, 2)
        self.assertEqual(w.shape, (2, 5))
        self.assertEqual(w[0].tolist(), [0, 0, 1, 2, 3])
        w = bd.cut_window(sig, 9, 2, 3)
        self.assertEqual(w[1].tolist(), [-8, -9, -10, 0, 0])


class SplitTest(unittest.TestCase):
    def test_mitdb_pools(self):
        self.assertEqual(bd.assign("mitdb", "100", []).pool, "test")
        self.assertEqual(bd.assign("mitdb", "102", []).pool, "excluded")
        self.assertEqual(bd.assign("mitdb", "101", []).pool, "mitdb-ds1")
        self.assertEqual(len(bd.MITDB_DS1), 22)
        self.assertEqual(len(bd.MITDB_DS2), 22)
        self.assertFalse(bd.MITDB_DS1 & bd.MITDB_DS2)

    def test_incart_records_of_one_patient_share_patient(self):
        a = bd.assign("incartdb", "I04", ["<age>: 59 <sex>: M", " patient 2", " PVCs"])
        b = bd.assign("incartdb", "I05", ["<age>: 59 <sex>: M", " patient 2", " VT"])
        self.assertEqual(a.patient, "incartdb:2")
        self.assertEqual(a, b)

    def test_incart_without_patient_line_fails(self):
        with self.assertRaises(ValueError):
            bd.assign("incartdb", "I99", ["no id here"])

    @staticmethod
    def _row(patient, pool, n=1000, s=0, v=0, record=None):
        return {"patient": patient, "pool": pool, "record": record or patient,
                "n_N": n, "n_S": s, "n_V": v, "n_F": 0, "n_Q": 0}

    def test_search_split_shares_rare_beats_and_keeps_a_dominant_patient_in_train(self):
        # S concentrated in a few patients, as in INCART; one patient holds more than half of it
        rng = np.random.default_rng(1)
        patients = {f"p{i}": {"N": int(rng.integers(1000, 8000)), "S": 0,
                              "V": int(rng.integers(0, 600))} for i in range(30)}
        for p, s in (("p1", 120), ("p5", 160), ("p9", 400), ("p20", 110), ("p25", 1100)):
            patients[p]["S"] = s
        shares = {"train": 0.6, "val": 0.15, "test": 0.25}
        split = bd.search_split(patients, shares)
        self.assertEqual(split, bd.search_split(patients, shares))  # deterministic
        self.assertEqual(split["p25"], "train")
        for c in ("N", "S", "V"):
            total = sum(v[c] for v in patients.values())
            for name in ("val", "test"):
                got = sum(patients[p][c] for p in patients if split[p] == name) / total
                self.assertGreater(got, 0.4 * shares[name], (c, name))
                self.assertLess(got, 1.8 * shares[name], (c, name))

    def test_assign_splits_keeps_patient_records_together_and_fixed_pools(self):
        rows = [self._row("mitdb:100", "test"), self._row("mitdb:102", "excluded")]
        rows += [self._row("incartdb:1", "incartdb", s=50, record="I01"),
                 self._row("incartdb:1", "incartdb", v=40, record="I02")]
        rows += [self._row(f"incartdb:{i}", "incartdb", s=i % 2 * 30, v=i % 3 * 20) for i in range(2, 30)]
        rows += [self._row(f"mitdb:{r}", "mitdb-ds1", s=40, v=40) for r in sorted(bd.MITDB_DS1)]
        bd.assign_splits(rows)
        self.assertEqual(rows[0]["split"], "test")
        self.assertEqual(rows[1]["split"], "excluded")
        self.assertEqual(rows[2]["split"], rows[3]["split"])
        ds1 = {r["split"] for r in rows if r["pool"] == "mitdb-ds1"}
        self.assertEqual(ds1, {"train", "val"})  # DS1 never goes to test
        incart = [r for r in rows if r["pool"] == "incartdb"]
        for split in ("train", "val", "test"):
            self.assertGreater(sum(r["n_S"] for r in incart if r["split"] == split), 0, split)
            self.assertGreater(sum(r["n_V"] for r in incart if r["split"] == split), 0, split)


if __name__ == "__main__":
    unittest.main()
