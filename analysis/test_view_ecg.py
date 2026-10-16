"""Offline tests of the viewer's data side (no window needed): python -m unittest discover -s analysis"""
import csv
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from polaris_raw import read_ecg, write_ecg_file  # noqa: E402
from polaris_segments import build_segments  # noqa: E402
import view_ecg  # noqa: E402

START = 1_577_836_800_000  # 2020-01-01T00:00:00Z


def _record(tmp: Path, n=130 * 60):
    rec = tmp / "rec"
    rec.mkdir()
    write_ecg_file(rec / "ecg_20200101T000000Z.bin", np.arange(n) % 1000, START)
    return rec


class SegmentsTest(unittest.TestCase):
    def test_converted_record_is_one_segment_starting_exactly(self):
        with tempfile.TemporaryDirectory() as d:
            ecg, _ = read_ecg(_record(Path(d)))
            segs = build_segments(ecg)
            self.assertEqual(len(segs), 1)
            # the min-offset mapper may be 1 ms off because the sensor clock is in whole ns
            self.assertAlmostEqual(segs[0].start_utc_ms, START, delta=1)
            self.assertEqual(len(segs[0].samples_uv), 130 * 60)

    def test_missing_frames_start_a_new_segment(self):
        with tempfile.TemporaryDirectory() as d:
            ecg, _ = read_ecg(_record(Path(d)))
            keep = np.ones(len(ecg.frame_n), bool)
            keep[10:13] = False                       # drop 3 frames
            keep_s = keep[ecg.frame_index]
            ecg.frame_phone_ms, ecg.frame_sensor_ns, ecg.frame_n = (
                ecg.frame_phone_ms[keep], ecg.frame_sensor_ns[keep], ecg.frame_n[keep])
            ecg.samples_uv = ecg.samples_uv[keep_s]
            self.assertEqual(len(build_segments(ecg)), 2)


class ParseTimeTest(unittest.TestCase):
    def setUp(self):
        seg = build_segments(read_ecg(self._rec())[0])
        self.data = view_ecg.ViewData(seg, view_ecg.Beats(np.array([], np.int64), np.array([], object)))

    def _rec(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        return _record(Path(self._tmp.name))

    def test_formats(self):
        p = lambda t: view_ecg.parse_time(t, self.data)
        t0 = self.data.start_ms
        self.assertEqual(p("00:00:30"), START + 30_000)
        self.assertEqual(p("00:01:00.5"), START + 60_500)
        self.assertEqual(p("+90"), t0 + 90_000)
        self.assertEqual(p("+1:30"), t0 + 90_000)
        self.assertEqual(p("2020-01-01T00:00:10Z"), START + 10_000)
        with self.assertRaises(ValueError):
            p("")
        with self.assertRaises(ValueError):
            p("abc")


class LoadAndWindowTest(unittest.TestCase):
    def test_load_and_window(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            rec = _record(tmp)
            an = tmp / "an"
            an.mkdir()
            self._csv(an / "beats.csv", "utcMs,utc,segment,rrMs,class",
                      [f"{START + 1000},x,0,,V", f"{START + 2000},x,0,1000,N"])
            self._csv(an / "windows.csv", "startUtcMs,startUtc,durationMs,samples,coverage,beats,meanHrBpm",
                      [f"{START},x,10000,1300,1.000,2,60", f"{START + 10000},x,10000,600,0.462,0,"])
            self._csv(an / "events.csv", "utcMs,utc,type,durationMs,detail", [f"{START + 15000},x,GAP,2000,"])
            data = view_ecg.load_view_data(rec, an)
            self.assertEqual(sorted(s.kind for s in data.shades), ["coverage", "gap"])
            pieces, (bt, by, bc), shades = view_ecg.window_content(data, START, START + 5000)
            self.assertEqual(list(bc), ["V", "N"])
            self.assertFalse(np.isnan(by).any())
            self.assertEqual(len(shades), 0)
            self.assertEqual(len(pieces), 1)

    def test_missing_analysis_explains(self):
        with tempfile.TemporaryDirectory() as d:
            rec = _record(Path(d))
            with self.assertRaises(SystemExit) as cm:
                view_ecg.load_view_data(rec, Path(d) / "nope")
            self.assertIn("analyze", str(cm.exception))

    @staticmethod
    def _csv(path, header, rows):
        path.write_text("\n".join([header, *rows]) + "\n", encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
