"""Tests of the labeling window without opening it (Agg backend, simulated key presses)."""
import csv
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

import matplotlib

matplotlib.use("Agg")
import numpy as np  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
import label_beats as lb  # noqa: E402
import label_data as ld  # noqa: E402
from polaris_raw import write_ecg_file  # noqa: E402

START = 1_577_836_800_000  # 2020-01-01T00:00:00Z
RATE = 130


def fake_ecg(path: Path, seconds=120):
    """A spike every 0.8 s, so that the strips have something to show."""
    n = RATE * seconds
    x = np.zeros(n)
    x[::104] = 1000
    write_ecg_file(path / "ecg_20200101T000000Z.bin", x, START)


def fake_analysis(folder: Path, seconds=120):
    folder.mkdir()
    cols = "utcMs,utc,segment,rrMs,class,sqi,good,rrRefMs"
    rows = []
    for i in range(1, seconds * 1000 // 800 - 2):
        t = START + i * 800
        cls = "V" if i % 10 == 3 else "N"
        rr = 800 if i != 50 else 1200            # one long interval (a GAP item)
        rows.append(f"{t},x,0,{rr},{cls},1.0,1,800")
    (folder / "beats.csv").write_text("\n".join([cols, *rows]) + "\n", encoding="utf-8")


def key(k):
    return SimpleNamespace(key=k)


class LabelerTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)
        rec = self.tmp / "rec"
        rec.mkdir()
        fake_ecg(rec)
        fake_analysis(self.tmp / "an")
        self.rec, self.an, self.labels = rec, self.tmp / "an", self.tmp / "labels"

    def _app(self):
        s = lb.OwnSession(self.rec, self.an, self.labels)
        app = lb.Labeler(s)
        self.addCleanup(app.plt.close, app.fig)
        return s, app

    def test_label_moves_on_saves_and_resumes(self):
        s, app = self._app()
        first, second = s.queue[0], s.queue[1]
        valid = "n" if first.kind == "GAP" else "v"
        app._on_key(key(valid))
        self.assertEqual(app.st.index, 1)
        rows = ld.read_label_rows(s.labels_path)
        self.assertEqual([(r["item"], r["label"]) for r in rows], [(first.item, valid.upper())])
        # a new session (a new sitting) resumes at the next unlabelled item
        s2, app2 = self._app()
        self.assertEqual(app2.st.index, 1)
        self.assertEqual(s2.queue[1].item, second.item)

    def test_wrong_key_for_kind_is_ignored(self):
        s, app = self._app()
        i = next(k for k, q in enumerate(s.queue) if q.kind == "BEAT")
        app._go(i)
        app._on_key(key("b"))                       # b only exists for gap items
        self.assertFalse(ld.read_label_rows(s.labels_path))
        self.assertEqual(app.st.index, i)

    def test_undo_appends_and_restores(self):
        s, app = self._app()
        item = s.queue[0]
        k = "n"
        app._on_key(key(k))
        app._on_key(key("u"))
        self.assertEqual(app.st.index, 0)
        rows = ld.read_label_rows(s.labels_path)
        self.assertEqual([r["label"] for r in rows], ["N", ""])      # nothing deleted
        self.assertNotIn(item.item, ld.current_labels(rows))

    def test_gap_item_stores_the_clicked_bump(self):
        s, app = self._app()
        gi = next(k for k, q in enumerate(s.queue) if q.kind == "GAP")
        app._go(gi)
        app._on_click(SimpleNamespace(inaxes=app.ax, button=1, xdata=(s.queue[gi].utc_ms + 100) / 1000))
        app._on_key(key("b"))
        row = ld.read_label_rows(s.labels_path)[-1]
        self.assertEqual((row["kind"], row["label"], row["bumpUtcMs"]), ("GAP", "B", str(s.queue[gi].utc_ms + 100)))

    def test_reveal_and_help_and_template_do_not_crash(self):
        s, app = self._app()
        app._on_key(key("g"))
        self.assertIn("ours", app.info.get_text())
        app._on_key(key("right"))
        self.assertEqual(app.info.get_text(), "")           # the reveal is per item
        for k in ("t", "h", "h", "left", "right"):
            app._on_key(key(k))
        self.assertIsNotNone(s.template())

    def test_template_is_the_spike_shape(self):
        s, _ = self._app()
        offsets, wave = s.template()
        self.assertAlmostEqual(offsets[np.argmax(wave)], 0, delta=10)

    def test_review_shows_only_disagreements_with_our_class_revealed(self):
        s, app = self._app()
        beats = [(k, q) for k, q in enumerate(s.queue) if q.kind == "BEAT"][:6]
        for k, q in beats:                                  # agree on the first 3, disagree on the rest
            wrong = {"N": "V", "V": "N", "S": "N", "Q": "N"}[q.our_class]
            ld.append_label(s.labels_path, q.item, q.utc_ms, "BEAT", q.our_class if k in [b[0] for b in beats[:3]] else wrong)
        extra = [q for _, q in [(k, q) for k, q in enumerate(s.queue) if q.kind == "BEAT"][6:8]]
        ld.append_label(s.labels_path, extra[0].item, extra[0].utc_ms, "BEAT", "?")   # unsure: reviewed too
        ld.append_label(s.labels_path, extra[1].item, extra[1].utc_ms, "BEAT", extra[1].our_class)  # agree: not
        r = lb.OwnSession(self.rec, self.an, self.labels, review=True)
        self.assertEqual(len(r), 4)
        self.assertIn(extra[0].item, [q.item for q in r.queue])
        self.assertNotIn(extra[1].item, [q.item for q in r.queue])
        self.assertEqual([q.utc_ms for q in r.queue], sorted(q.utc_ms for q in r.queue))
        app2 = lb.Labeler(r)
        self.addCleanup(app2.plt.close, app2.fig)
        self.assertIn("ours", app2.info.get_text())          # revealed without pressing g
        app2._on_key(key("right"))
        self.assertIn("ours", app2.info.get_text())          # and on every item
        self.assertIn("You said", app2.question.get_text())

    def test_review_with_nothing_to_review_explains(self):
        self._app()
        with self.assertRaises(SystemExit):
            lb.OwnSession(self.rec, self.an, self.labels, review=True)

    def test_end_of_queue_shows_message(self):
        s, app = self._app()
        app._go(len(s))
        self.assertIn("Esc", app.question.get_text())


class GalleryTest(unittest.TestCase):
    def test_answer_reveals_scores_and_waits_for_space(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            db = tmp / "mitdb"
            for rec in ("100", "101"):
                (db / rec).mkdir(parents=True)
                fake_ecg(db / rec)
                (db / rec / "reference.json").write_text('{"startUtc": "2020-01-01T00:00:00Z"}', encoding="utf-8")
                with (db / rec / "annotations.csv").open("w", newline="", encoding="utf-8") as f:
                    w = csv.writer(f)
                    w.writerow(["sample", "time_ms", "symbol", "beat", "aami", "aux"])
                    for i in range(1, 140):
                        cls = "V" if i % 10 == 3 else "S" if i % 10 == 7 else "N"
                        w.writerow([i * 104, i * 800, cls, 1, cls, ""])
            s = lb.GallerySession(db, 3, tmp / "labels")
            app = lb.Labeler(s)
            truth = s.items[0].truth
            app._on_key(key("n"))
            self.assertTrue(app.st.waiting)
            self.assertIn(truth, app.info.get_text())
            app._on_key(key("v"))                    # ignored while waiting for space
            self.assertEqual(s.score.total, 1)
            app._on_key(key(" "))
            self.assertEqual(app.st.index, 1)
            self.assertEqual(len(ld.read_label_rows(tmp / "labels" / "gallery.csv")), 1)
            app.plt.close(app.fig)


if __name__ == "__main__":
    unittest.main()
