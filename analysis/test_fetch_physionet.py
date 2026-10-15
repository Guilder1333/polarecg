"""Offline tests of the conversion helpers (no network)."""
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import fetch_physionet as fp  # noqa: E402
import polaris_raw as pr  # noqa: E402


class ConversionTest(unittest.TestCase):
    def test_resample_360_to_130_keeps_frequency_and_length(self):
        fs = 360.0
        t = np.arange(int(fs * 10)) / fs
        out = fp.resample_to_130hz(np.sin(2 * np.pi * 5 * t), fs)  # 5 Hz sine, 10 s
        self.assertEqual(len(out), 1300)
        t2 = np.arange(len(out)) / 130
        # away from the edges it should still match a 5 Hz sine
        err = np.abs(out[100:-100] - np.sin(2 * np.pi * 5 * t2)[100:-100]).max()
        self.assertLess(err, 0.02)

    def test_resample_incart_257hz(self):
        out = fp.resample_to_130hz(np.zeros(257 * 4), 257.0)
        self.assertEqual(len(out), 130 * 4)

    def test_annotations_are_moved_to_130hz_grid_and_classified(self):
        rows = fp.convert_annotations(
            samples=[360, 720, 1080, 1440], symbols=["N", "V", "+", "A"],
            aux=["", "", "(AFIB", ""], fs=360.0, n_out=1000)
        self.assertEqual([r["sample"] for r in rows], [130, 260, 390, 520])
        self.assertEqual([r["aami"] for r in rows], ["N", "V", "", "S"])
        self.assertEqual([r["beat"] for r in rows], [1, 1, 0, 1])
        self.assertEqual(rows[2]["aux"], "(AFIB")
        self.assertEqual(rows[0]["time_ms"], 1000)

    def test_annotations_outside_signal_are_dropped(self):
        rows = fp.convert_annotations([100000], ["N"], [""], 360.0, 1000)
        self.assertEqual(rows, [])

    def test_pick_lead(self):
        self.assertEqual(fp.pick_lead(["V1", "MLII"], ("MLII", "II")), 1)
        self.assertEqual(fp.pick_lead(["X", "Y"], ("II",)), 0)

    def test_written_file_round_trips(self):
        samples = np.arange(-100, 150, dtype=np.int32)  # 250 samples -> 4 frames of <= 70
        with tempfile.TemporaryDirectory() as d:
            pr.write_ecg_file(Path(d) / "ecg_20200101T000000Z.bin", samples, fp.START_PHONE_MS)
            ecg, bad = pr.read_ecg(d)
        self.assertEqual(bad, [])
        self.assertEqual(list(ecg.samples_uv), list(samples))
        self.assertEqual(len(ecg.frame_phone_ms), 4)
        # phone time = time of the last sample of the frame; sensor time = first sample
        self.assertEqual(ecg.frame_phone_ms[0], fp.START_PHONE_MS + round(69 * 1000 / 130))
        self.assertEqual(ecg.frame_sensor_ns[0], (fp.START_PHONE_MS - pr.SENSOR_EPOCH_UNIX_MS) * 1_000_000)
        self.assertEqual(ecg.frame_phone_ms[1], fp.START_PHONE_MS + round(139 * 1000 / 130))


if __name__ == "__main__":
    unittest.main()
