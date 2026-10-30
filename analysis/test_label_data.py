"""Tests of the labeling tool's data side: python -m unittest discover -s analysis"""
import csv
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import eval_labels  # noqa: E402
import label_data as ld  # noqa: E402

T0 = 1_792_600_000_000


def beat(i, cls="N", rr=800.0, ref=800.0, good=True, segment=0):
    raw = {"utcMs": str(T0 + i * 800)}
    return ld.BeatRow(T0 + i * 800, segment, rr, ref, cls, good, raw)


def night(n=2000):
    """n normal beats, with a few V, S, Q and long intervals mixed in."""
    beats = [beat(i) for i in range(n)]
    for i in range(100, 100 + 40):
        beats[i * 3 % n].cls = "V"
    for i in range(200, 200 + 60):
        beats[i * 5 % n].cls = "S"
    for i in range(0, 20):
        beats[1500 + i].cls = "Q"
    beats[700].rr_ms = 1200.0                      # 1.5 x the reference: a gap item
    beats[701].good = False
    beats[900].rr_ms = 1200.0
    beats[900].segment = 1                         # new segment: not a gap item
    return beats


class QueueTest(unittest.TestCase):
    def test_gap_items_need_good_neighbours_and_same_segment(self):
        gaps = ld.gap_items(night())
        self.assertEqual([i for i, _ in gaps], [700])

    def test_early_n_group_takes_early_clean_n_out_of_n(self):
        beats = night()
        for i in range(400, 460):
            beats[i].raw = {"prematurity": "0.7"}
        beats[400].good = False                           # not clean: stays in N
        q = ld.build_queue(beats, [], limits={"EARLY_N": ld.ALL, "V": ld.ALL, "Q": ld.ALL})
        early = [x for x in q if x.group == "EARLY_N"]
        n_before = sum(1 for b in beats[400:460] if b.cls == "N" and b.good)
        self.assertEqual(len(early), n_before)
        self.assertTrue(all(x.weight == 1.0 for x in early))
        self.assertAlmostEqual(sum(x.weight for x in q if x.kind == "BEAT"), len(beats), places=6)
        off = ld.build_queue(beats, [])                   # default: group off
        self.assertEqual(sum(1 for x in off if x.group == "EARLY_N"), 0)

    def test_parse_limits(self):
        self.assertEqual(ld.parse_limits("V=all, q=ALL,S=500"), {"V": ld.ALL, "Q": ld.ALL, "S": 500})
        with self.assertRaises(ValueError):
            ld.parse_limits("X=5")

    def test_pattern_beats_are_first_and_last_of_event(self):
        beats = night()
        ev = [{"type": "V_COUPLET", "utcMs": str(beats[50].utc_ms), "durationMs": "800"},
              {"type": "GAP", "utcMs": str(beats[60].utc_ms), "durationMs": "800"},
              {"type": "PAUSE", "utcMs": str(beats[80].utc_ms + 3), "durationMs": "2400"}]
        self.assertEqual(ld.pattern_beat_indices(beats, ev), {50, 51, 80, 83})

    def test_limits_weights_and_partition(self):
        beats = night()
        ev = [{"type": "S_COUPLET", "utcMs": str(beats[300].utc_ms), "durationMs": "800"}]
        q = ld.build_queue(beats, ev, limits={"V": 10, "S": 10, "N": 30, "Q": 5, "GAP": 100})
        ids = [x.item for x in q]
        self.assertEqual(len(ids), len(set(ids)))
        by = {}
        for x in q:
            by.setdefault(x.group, []).append(x)
        self.assertEqual(len(by["V"]), 10)
        self.assertEqual(len(by["N"]), 30)
        self.assertEqual(len(by["GAP"]), 1)
        self.assertEqual(len(by["PATTERN"]), 2)
        self.assertEqual({x.weight for x in by["PATTERN"]}, {1.0})
        # weights add up to the size of each stratum: the strata do not overlap
        total_beats = sum(x.weight for x in q if x.kind == "BEAT")
        self.assertAlmostEqual(total_beats, len(beats), places=6)

    def test_same_seed_same_queue_and_order_is_shuffled(self):
        beats = night()
        a = ld.build_queue(beats, [])
        b = ld.build_queue(beats, [])
        self.assertEqual([x.item for x in a], [x.item for x in b])
        self.assertNotEqual([x.item for x in a], sorted(x.item for x in a))

    def test_spread_sample_covers_the_whole_list(self):
        import random
        out = ld.spread_sample(list(range(1000)), 10, random.Random(1))
        self.assertEqual([x // 100 for x in out], list(range(10)))

    def test_queue_file_round_trip_and_never_overwritten(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "s" / "queue.csv"
            q = ld.build_queue(night(), [])
            ld.write_queue(path, q)
            back = ld.read_queue(path)
            self.assertEqual([x.item for x in back], [x.item for x in q])
            self.assertAlmostEqual(back[0].weight, q[0].weight, places=4)
            with self.assertRaises(FileExistsError):
                ld.write_queue(path, q)


class LabelsTest(unittest.TestCase):
    def test_append_only_last_row_wins_and_undo(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "labels.csv"
            ld.append_label(p, "B1", 1, "BEAT", "V")
            ld.append_label(p, "B2", 2, "BEAT", "N")
            ld.append_label(p, "B1", 1, "BEAT", "S")          # relabel
            ld.append_label(p, "B2", 2, "BEAT", "")           # undo
            rows = ld.read_label_rows(p)
            self.assertEqual(len(rows), 4)                    # nothing removed
            cur = ld.current_labels(rows)
            self.assertEqual({k: r["label"] for k, r in cur.items()}, {"B1": "S"})

    def test_label_must_fit_the_item_kind(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "labels.csv"
            with self.assertRaises(ValueError):
                ld.append_label(p, "B1", 1, "BEAT", "B")
            with self.assertRaises(ValueError):
                ld.append_label(p, "G1", 1, "GAP", "V")
            ld.append_label(p, "G1", 1, "GAP", "B", bump_utc_ms=12345)
            self.assertEqual(ld.read_label_rows(p)[0]["bumpUtcMs"], "12345")

    def test_resume_goes_to_first_unlabeled(self):
        q = [ld.QueueItem(f"B{i}", "BEAT", i, "N", 1.0, "N") for i in range(4)]
        cur = {"B0": {"label": "N"}, "B1": {"label": "N"}, "B3": {"label": "N"}}
        self.assertEqual(ld.first_unlabeled(q, cur), 2)
        cur["B2"] = {"label": "N"}
        self.assertEqual(ld.first_unlabeled(q, cur), 4)


class GalleryTest(unittest.TestCase):
    def _db(self, root: Path):
        for rec, n in (("100", 80), ("101", 80), ("102", 80)):
            d = root / rec
            d.mkdir(parents=True)
            (d / "reference.json").write_text('{"startUtc": "2020-01-01T00:00:00Z"}', encoding="utf-8")
            with (d / "annotations.csv").open("w", newline="", encoding="utf-8") as f:
                w = csv.writer(f)
                w.writerow(["sample", "time_ms", "symbol", "beat", "aami", "aux"])
                w.writerow([6, 46, "+", 0, "", "(N"])
                t = 1000
                for i in range(n):
                    # every 10th beat is S (atrial, 300 ms early) or V; the beat after an S comes late
                    # so that the rhythm stays regular; the 8 beats before an S are regular too
                    cls = "V" if i % 10 == 3 else "S" if i % 10 == 9 else "N"
                    t += 500 if cls == "S" else 1100 if i % 10 == 0 else 800
                    symbol = "A" if cls == "S" else cls
                    w.writerow([i * 100, t, symbol, 1, cls, ""])

    def test_pick_is_balanced_and_spread_over_records(self):
        with tempfile.TemporaryDirectory() as d:
            self._db(Path(d))
            items = ld.pick_gallery(Path(d), per_class=9, seed=3)
            truths = [i.truth for i in items]
            self.assertEqual({t: truths.count(t) for t in "NSV"}, {"N": 9, "S": 9, "V": 9})
            self.assertEqual(len({i.record for i in items}), 3)
            self.assertEqual(items, ld.pick_gallery(Path(d), per_class=9, seed=3))
            first = ld.read_reference_beats(Path(d) / "100")[0][0]
            self.assertEqual(first[0], 1_577_836_800_000 + 2100)

    def test_default_gallery_keeps_only_clear_examples(self):
        with tempfile.TemporaryDirectory() as d:
            self._db(Path(d))
            clear = ld.pick_gallery(Path(d), per_class=5, seed=1)
            for it in clear:
                beats = dict((u, (c, sy)) for u, c, sy in ld.read_reference_beats(Path(d) / it.record)[0])
                self.assertEqual(beats[it.utc_ms][0], it.truth)
                if it.truth == "S":
                    ts = sorted(beats)
                    k = ts.index(it.utc_ms)
                    self.assertLess(ts[k] - ts[k - 1], 0.85 * 800)       # visibly early
                if it.truth == "N":
                    ts = sorted(beats)
                    k = ts.index(it.utc_ms)
                    self.assertIn(ts[k] - ts[k - 1], (800,))             # on time
            self.assertEqual(sum(i.truth == "S" for i in clear), 5)

    def test_score_and_mistakes(self):
        s = ld.GalleryScore()
        for truth, ans in [("S", "V"), ("S", "V"), ("S", "S"), ("V", "?"), ("N", "N")]:
            s.add(truth, ans)
        self.assertEqual((s.correct, s.total), (2, 5))
        self.assertEqual(s.mistakes(), ["S taken for V: 2 times", "V skipped as unsure: 1 time"])


class EvalTest(unittest.TestCase):
    def test_weighted_se_ppv_and_disagreements(self):
        Q = ld.QueueItem
        pairs = [
            (Q("B1", "BEAT", 1000, "V", 2.0, "V"), "V"),      # we V, owner V          weight 2
            (Q("B2", "BEAT", 2000, "V", 2.0, "V"), "S"),      # we V, owner S (false V)
            (Q("B3", "BEAT", 3000, "N", 10.0, "N"), "V"),     # we N, owner V (missed) weight 10
            (Q("B4", "BEAT", 4000, "N", 10.0, "N"), "N"),
            (Q("B5", "BEAT", 5000, "N", 10.0, "N"), "?"),     # unsure: left out
            (Q("G6", "GAP", 6000, "GAP", 5.0, ""), "B"),
        ]
        conf = eval_labels.confusion(pairs)
        se, ppv = eval_labels.se_ppv(conf, "V")
        self.assertAlmostEqual(se, 2 / 12)                    # owner V: 2 + 10, we found 2
        self.assertAlmostEqual(ppv, 2 / 4)                    # our V: 2 + 2, half confirmed
        self.assertEqual(eval_labels.gap_summary(pairs), {"B": 5.0})
        self.assertEqual([q.item for q, _ in eval_labels.disagreements(pairs)], ["B2", "B3"])
        self.assertIn("Se", eval_labels.report(pairs, "t"))

    def test_plot_is_written(self):
        Q = ld.QueueItem
        pairs = [(Q(f"B{i}", "BEAT", 1_792_600_000_000 + i * 600_000, "N", 5.0, "N"), "V" if i % 3 == 0 else "N")
                 for i in range(12)]
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "e.png"
            eval_labels.plot(pairs, "t", out)
            self.assertGreater(out.stat().st_size, 5000)

    def test_end_to_end_from_files(self):
        with tempfile.TemporaryDirectory() as d:
            s = Path(d) / "sess"
            ld.write_queue(s / "queue.csv", [ld.QueueItem("B1", "BEAT", 1, "V", 3.0, "V"),
                                             ld.QueueItem("B2", "BEAT", 2, "N", 1.0, "N")])
            ld.append_label(s / "labels.csv", "B1", 1, "BEAT", "V")
            pairs = eval_labels.labelled_items(s)
            self.assertEqual([(q.item, l) for q, l in pairs], [("B1", "V")])


if __name__ == "__main__":
    unittest.main()
