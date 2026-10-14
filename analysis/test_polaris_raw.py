"""Run from the repo root:  python -m unittest discover -s analysis"""
import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import polaris_raw as pr  # noqa: E402


def header(stream):
    return b"PECG" + struct.pack("<HH", 1, stream)


def ecg_rec(phone, sensor, samples):
    return struct.pack("<qqH", phone, sensor, len(samples)) + struct.pack(f"<{len(samples)}i", *samples)


def acc_rec(phone, sensor, xyz):
    flat = [v for t in xyz for v in t]
    return struct.pack("<qqH", phone, sensor, len(xyz)) + struct.pack(f"<{len(flat)}h", *flat)


def hr_rec(phone, hr, contact, rr):
    return struct.pack("<qHbB", phone, hr, contact, len(rr)) + struct.pack(f"<{len(rr)}H", *rr)


class ReaderTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_ecg_segments_in_order_and_truncated_tail_ignored(self):
        (self.dir / "ecg_20261012T221530Z.bin").write_bytes(
            header(1) + ecg_rec(1000, 10, [1, -2, 3]) + ecg_rec(1500, 20, [4, 5]))
        (self.dir / "ecg_20261012T225000Z_invalid.bin").write_bytes(b"xyz")
        # current file, last record cut in the middle
        (self.dir / "ecg.bin").write_bytes(header(1) + ecg_rec(2000, 30, [7]) + ecg_rec(2500, 40, [8, 9])[:-3])

        ecg, bad = pr.read_ecg(self.dir)

        self.assertEqual(list(ecg.frame_phone_ms), [1000, 1500, 2000])
        self.assertEqual(list(ecg.samples_uv), [1, -2, 3, 4, 5, 7])
        self.assertEqual(list(ecg.frame_index), [0, 0, 0, 1, 1, 2])
        self.assertEqual([p.name for p in bad], ["ecg_20261012T225000Z_invalid.bin"])

    def test_acc_and_hr(self):
        (self.dir / "acc.bin").write_bytes(header(2) + acc_rec(1000, 1, [(1, 2, 3), (-4, 5, -6)]))
        (self.dir / "hr.bin").write_bytes(
            header(3) + hr_rec(1000, 60, 1, [1000, 990]) + hr_rec(2000, 61, -1, []))

        acc, _ = pr.read_acc(self.dir)
        hr, _ = pr.read_hr(self.dir)

        self.assertEqual(acc.xyz_mg.tolist(), [[1, 2, 3], [-4, 5, -6]])
        self.assertEqual(list(hr.hr_bpm), [60, 61])
        self.assertEqual(list(hr.contact), [1, -1])
        self.assertEqual(list(hr.rr_ms), [1000, 990])
        self.assertEqual(list(hr.rr_phone_ms), [1000, 1000])

    def test_empty_session(self):
        s = pr.read_session(self.dir)
        self.assertEqual(len(s.ecg.samples_uv), 0)
        self.assertEqual(s.acc.xyz_mg.shape, (0, 3))
        self.assertEqual(len(s.hr.rr_ms), 0)


if __name__ == "__main__":
    unittest.main()
