"""Offline tests of the training script (synthetic records, no dataset needed)."""
import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import train_beats as tb  # noqa: E402


def synthetic_record(n_beats=60, rr=130, leads=2, split="train", db="mitdb"):
    """Regular beats every ``rr`` samples, every 10th beat V, every 7th S."""
    n = rr * (n_beats + 4)
    sig = np.zeros((leads, n), np.int16)
    samples = np.arange(2 * rr, 2 * rr + n_beats * rr, rr)
    t = np.arange(-4, 5)
    for s in samples:
        for lead in range(leads):
            sig[lead, s + t] += (1000 * np.exp(-0.5 * (t / 1.5) ** 2) * (1 if lead == 0 else -1)).astype(np.int16)
    cls = np.zeros(n_beats, np.int64)
    cls[::7] = 1
    cls[::10] = 2
    ms = rr * 1000 / 130
    rrm = np.full((n_beats, 4), ms, np.float32)
    rrm[0, [0, 2, 3]] = -1
    return tb.Record(db, "r1", f"{db}:r1", split, sig, ["MLII", "V1"][:leads], samples, cls, rrm)


class FeatureTest(unittest.TestCase):
    def test_normalize_window_centres_and_scales(self):
        w = np.full(384, 300.0)
        for c in (62, 192, 322):  # three beats, as in a real window; R about 5 samples wide
            w[c - 2:c + 3] = 1300.0
        out = tb.normalize_window(w)
        self.assertAlmostEqual(float(np.median(out)), 0.0, places=5)
        self.assertAlmostEqual(float(np.abs(out).max()), 1.0, places=2)

    def test_normalize_flat_window_does_not_blow_up(self):
        out = tb.normalize_window(np.full(384, 7.0) + np.arange(384) * 1e-3)
        self.assertLess(float(np.abs(out).max()), 0.1)

    def test_rr_features(self):
        f = tb.rr_features(500, 1500, 1000, 1000)  # early, then compensatory pause
        np.testing.assert_allclose(f, [0.5, 1.5, 1.0, 1.0, 3.0], rtol=1e-6)
        f = tb.rr_features(-1, -1, -1, -1)  # first beat of a record: all "usual"
        np.testing.assert_allclose(f, [1.0, 1.0, 1.0, 0.8, 1.0], rtol=1e-6)

    def test_noise_center_is_steepest_point_inside_range(self):
        x = np.zeros(100)
        x[10] = 50  # steeper but outside the range
        x[60] = 20
        self.assertIn(tb.noise_center(x, 30, 90), (59, 60))

    def test_scaled_to(self):
        rng = np.random.default_rng(0)
        s, n = rng.normal(0, 3, 1000), rng.normal(0, 1, 1000)
        out = tb.scaled_to(s, n, 2.0)
        self.assertAlmostEqual(np.sqrt(np.mean(out ** 2)) / np.sqrt(np.mean(s ** 2)), 2.0, places=5)


class MakerTest(unittest.TestCase):
    def setUp(self):
        rng = np.random.default_rng(0)
        self.maker = tb.WindowMaker([synthetic_record()], [rng.normal(0, 100, 20_000).astype(np.float32)])

    def test_classes_indexed(self):
        cls = self.maker.records[0].beat_class
        self.assertEqual(len(self.maker.by_class[2]), int((cls == 2).sum()))
        self.assertGreater(len(self.maker.gaps), 0)

    def test_batch_shapes_and_mix(self):
        x, rr, y = self.maker.batch(400, np.random.default_rng(1))
        self.assertEqual(x.shape, (400, 1, tb.PRE + tb.POST))
        self.assertEqual(rr.shape, (400, tb.N_RR))
        share = np.bincount(y, minlength=4) / 400
        np.testing.assert_allclose(share, tb.BATCH_MIX, atol=0.08)

    def test_not_a_beat_sits_between_real_beats(self):
        rng = np.random.default_rng(2)
        for _ in range(20):
            w, f, y = self.maker.not_a_beat(rng, augment=False)
            self.assertEqual(y, tb.X_CLASS)
            # rr_pre and rr_post of a centre between two beats 1000 ms apart: both >= 200 ms, sum 1000
            rr_pre = f[0] * 1000  # rr_local is 1000 ms here
            rr_post = f[1] * 1000
            self.assertGreaterEqual(rr_pre, 199)
            self.assertGreaterEqual(rr_post, 199)
            self.assertAlmostEqual(rr_pre + rr_post, 1000, delta=1)

    def test_unaugmented_beat_is_centred(self):
        w, f, y = self.maker.beat(0, 5, 0, None, augment=False)
        self.assertAlmostEqual(float(w[tb.PRE]), float(w.max()), places=5)  # neighbours are as tall

    def test_fixed_set_is_reproducible(self):
        a = tb.fixed_set(self.maker, 5, 7)
        b = tb.fixed_set(self.maker, 5, 7)
        np.testing.assert_array_equal(a[0], b[0])
        np.testing.assert_array_equal(a[2], b[2])

    def test_beat_windows_primary_lead_only(self):
        x, rr, y, dbs = tb.beat_windows(self.maker.records, primary_only=True)
        self.assertEqual(len(y), int((self.maker.records[0].beat_class >= 0).sum()))
        self.assertGreater(float(x[0, 0, tb.PRE]), 0)  # lead 0 (MLII) is the positive one


class BatchLoaderTest(unittest.TestCase):
    def setUp(self):
        rng = np.random.default_rng(0)
        self.maker = tb.WindowMaker([synthetic_record()], [rng.normal(0, 100, 20_000).astype(np.float32)])

    def test_worker_rngs_differ_and_repeat(self):
        a, b, a2 = (tb.worker_rng(5, w).integers(1 << 30, size=4) for w in (0, 1, 0))
        np.testing.assert_array_equal(a, a2)
        self.assertFalse(np.array_equal(a, b))

    def _check(self, workers):
        it = tb.batch_loader(self.maker, 64, seed=3, workers=workers)
        batches = [next(it) for _ in range(4)]
        x, rr, y = batches[0]
        self.assertEqual(tuple(x.shape), (64, 1, tb.PRE + tb.POST))
        self.assertEqual(tuple(rr.shape), (64, tb.N_RR))
        self.assertEqual(tuple(y.shape), (64,))
        return batches

    def test_in_process_matches_maker(self):
        x, _, y = self._check(workers=0)[0]
        ex, _, ey = self.maker.batch(64, tb.worker_rng(3, 0))
        np.testing.assert_array_equal(x.numpy(), ex)
        np.testing.assert_array_equal(y.numpy(), ey)

    def test_two_worker_processes_give_different_batches(self):
        b = self._check(workers=2)   # DataLoader takes batches from worker 0, 1, 0, 1
        self.assertFalse(np.array_equal(b[0][0].numpy(), b[1][0].numpy()))
        ex, _, _ = self.maker.batch(64, tb.worker_rng(3, 1))
        np.testing.assert_array_equal(b[1][0].numpy(), ex)


class MetricsTest(unittest.TestCase):
    def test_confusion_and_se_ppv(self):
        m = tb.confusion(np.array([0, 0, 1, 2, 2]), np.array([0, 1, 1, 2, 0]))
        self.assertEqual(m[0].tolist(), [1, 1, 0, 0])
        se, ppv = tb.se_ppv(m)
        self.assertAlmostEqual(se[0], 50.0)
        self.assertAlmostEqual(ppv[1], 50.0)
        self.assertTrue(np.isnan(se[3]))
        self.assertAlmostEqual(tb.score(m), np.mean([50, 100, 50]))


class ModelTest(unittest.TestCase):
    def test_forward_shape(self):
        import torch
        model = tb.build_model(8)
        out = model(torch.zeros(3, 1, tb.PRE + tb.POST), torch.zeros(3, tb.N_RR))
        self.assertEqual(tuple(out.shape), (3, len(tb.CLASSES)))


if __name__ == "__main__":
    unittest.main()
