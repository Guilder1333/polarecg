"""Label your own beats, or train your eye on MIT-BIH beats first (docs/LABELING_TOOL.md).

    python analysis/label_beats.py --gallery                       # ~10 min of practice, scored
    python analysis/label_beats.py data/sessions/20261021T162147Z   # label your own recording
    python analysis/label_beats.py data/sessions/20261021T162147Z --review     # only disagreements and ? items
    python analysis/label_beats.py data/sessions/20261021T162147Z --save item.png --index 5   # no window

Own recordings need the output of ``:tools:cli analyze`` (docs/ANALYSIS_FORMAT.md). The first
start builds ``data/labels/<session>/queue.csv`` (about 600 items, random order); every label is
appended to ``labels.csv`` at once, so you can stop at any time (Esc) and continue later.

Keys:  n s v q  label the beat      b  blocked early beat (gap items only)      ?  unsure
       <- / ->  previous / next item (relabelling is allowed)     u  undo the last label
       t  template overlay          g  reveal our class and features             h  help
       Esc  save and quit           (gallery: space / Enter shows the next beat after the answer)
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import eval_labels  # noqa: E402
import label_data as ld  # noqa: E402
from polaris_raw import ECG_RATE_HZ, read_ecg  # noqa: E402
from polaris_segments import Segment, build_segments  # noqa: E402
from view_ecg import _fmt_time, default_analysis_dir  # noqa: E402

HALF_WINDOW_MS = 3000          # the strip shows about 6 s centred on the item
TEMPLATE_FROM_MS, TEMPLATE_TO_MS = -250, 400
HIGHLIGHT = {"BEAT": "#ffe08a", "GAP": "#9ecae8"}

HELP = """\
THREE QUESTIONS FOR EVERY BEAT
 1. Is it EARLY?   2. Does it LOOK LIKE ITS NEIGHBOURS?   3. What happens AFTER? (weakest clue)

 n  NORMAL        thin sharp spike, same shape as its neighbours, on time
 s  S             early, but looks like its neighbours (same thin spike)
 v  V             looks DIFFERENT: wider, often taller, odd shape, the next wave often points the
                  other way, no small bump (P wave) before it; usually a longer pause after
 q  Q             cannot tell: noise, motion, a broken-looking signal
 b  B             (gap items) a small early bump with NO spike after it, then a pause; often on the
                  T wave of the previous beat. Click on the bump first to store where it is.
 ?  unsure        skip - a skipped beat is better than a guess

 <- ->  previous / next      u  undo      t  template      g  reveal our class      Esc  save, quit

HONEST LIMITS: at 130 Hz the P wave is hard to see. Some S beats look wide and V-like (doctors
disagree too). With a bundle-branch block normal beats are wide. Noise mimics anything: use q.
You describe what the beat LOOKS like; it is not a diagnosis.
"""


# -- signal helpers (no GUI) -------------------------------------------------------------------
def signal_window(segments: list[Segment], lo_ms: float, hi_ms: float) -> list[tuple[np.ndarray, np.ndarray]]:
    """(times in s, µV) pieces of the signal inside [lo_ms, hi_ms], one per segment."""
    pieces = []
    for seg in segments:
        if seg.end_utc_ms < lo_ms or seg.start_utc_ms > hi_ms:
            continue
        i0 = max(0, seg.index_at(lo_ms) - 1)
        i1 = min(len(seg.samples_uv), seg.index_at(hi_ms) + 2)
        if i1 > i0:
            pieces.append((seg.times_ms(i0, i1) / 1000, seg.samples_uv[i0:i1]))
    return pieces


def beat_waveform(segments: list[Segment], utc_ms: float, offsets_ms: np.ndarray) -> np.ndarray | None:
    """The signal at utc_ms + offsets (linear interpolation), or None if it is not all inside one segment."""
    for seg in segments:
        if seg.start_utc_ms <= utc_ms + offsets_ms[0] and utc_ms + offsets_ms[-1] <= seg.end_utc_ms:
            i0 = max(0, seg.index_at(utc_ms + offsets_ms[0]) - 2)
            i1 = min(len(seg.samples_uv), seg.index_at(utc_ms + offsets_ms[-1]) + 3)
            return np.interp(utc_ms + offsets_ms, seg.times_ms(i0, i1), seg.samples_uv[i0:i1])
    return None


def build_template(segments: list[Segment], beats: list[ld.BeatRow], max_beats: int = 200):
    """(offsets in ms, median waveform with its mean removed) of clean normal beats spread over the
    recording; None if there are none. Only for the optional overlay (key t)."""
    clean = [b for b in beats if b.cls == "N" and b.good and b.rr_ms is not None]
    if not clean:
        return None
    step = max(1, len(clean) // max_beats)
    offsets = np.arange(TEMPLATE_FROM_MS, TEMPLATE_TO_MS, 1000 / ECG_RATE_HZ)
    waves = []
    for b in clean[::step]:
        w = beat_waveform(segments, b.utc_ms, offsets)
        if w is not None:
            waves.append(w - w.mean())
    if not waves:
        return None
    return offsets, np.median(np.array(waves), axis=0)


def draw_strip(ax, segments, beat_utc: np.ndarray, centre_ms: float, kind: str, *,
               span_ms: tuple[float, float] | None = None, bump_ms: float | None = None,
               template=None, half_ms: float = HALF_WINDOW_MS) -> None:
    """One item: the ECG, a tick on every neighbouring beat (not coloured by our class), the RR
    intervals in ms, the item highlighted."""
    from matplotlib.ticker import FuncFormatter
    ax.clear()
    lo, hi = centre_ms - half_ms, centre_ms + half_ms
    if span_ms is None:
        span_ms = (centre_ms - 120, centre_ms + 120)
    ax.axvspan(span_ms[0] / 1000, span_ms[1] / 1000, color=HIGHLIGHT[kind], alpha=0.55, lw=0)
    pieces = signal_window(segments, lo, hi)
    for t, uv in pieces:
        ax.plot(t, uv, color="black", lw=0.9)
    if pieces:
        ys = np.concatenate([uv for t, uv in pieces])
        pad = max((ys.max() - ys.min()) * 0.12, 50)
        ax.set_ylim(ys.min() - pad, ys.max() + pad)
    else:
        ax.text(0.5, 0.5, "no signal here", transform=ax.transAxes, ha="center")
    inside = beat_utc[(beat_utc >= lo) & (beat_utc <= hi)]
    tr = ax.get_xaxis_transform()
    ax.scatter(inside / 1000, np.full(len(inside), 0.97), marker="v", s=28, color="#555555",
               transform=tr, zorder=3, clip_on=False)
    for a, b in zip(inside[:-1], inside[1:]):
        ax.text((a + b) / 2000, 0.90, f"{b - a:.0f}", transform=tr, ha="center", fontsize=9, color="#333333")
    if template is not None and kind == "BEAT":
        offsets, wave = template
        w = beat_waveform(segments, centre_ms, offsets)
        if w is not None:
            ax.plot((centre_ms + offsets) / 1000, wave + w.mean(), color="#1f77b4", lw=1.6, ls="--", zorder=4,
                    label="normal-beat template")
            ax.legend(loc="lower right", fontsize=8)
    if bump_ms is not None:
        ax.axvline(bump_ms / 1000, color="#d62728", lw=1.5)
    ax.set_xlim(lo / 1000, hi / 1000)
    ax.xaxis.set_major_formatter(FuncFormatter(_fmt_time))
    ax.set_ylabel("ECG, µV")
    ax.set_xlabel("UTC time (▼ = detected beats, numbers = RR in ms)")
    ax.grid(alpha=0.3)


# -- sessions: where the items come from ----------------------------------------------------------
class OwnSession:
    """Labelling your own recording."""
    reveal_after_answer = False

    def __init__(self, recording: Path, analysis: Path, labels_root: Path, review: bool = False):
        ecg, _ = read_ecg(recording)
        self.segments = build_segments(ecg)
        if not self.segments:
            raise SystemExit(f"No ECG samples in {recording}")
        if not (analysis / "beats.csv").is_file():
            raise SystemExit(f"{analysis / 'beats.csv'} not found. Run the analyze command first "
                             f"(docs/ANALYSIS_FORMAT.md) or pass --analysis.")
        self.name = recording.resolve().name
        self.beats = ld.read_beats(analysis)
        self.beat_utc = np.array([b.utc_ms for b in self.beats], dtype=np.int64)
        folder = ld.labels_dir(labels_root, self.name)
        queue_path = folder / "queue.csv"
        if not queue_path.is_file():
            q = ld.build_queue(self.beats, ld.read_events(analysis))
            ld.write_queue(queue_path, q)
            groups = {g: sum(1 for x in q if x.group == g) for g in ("V", "S", "N", "Q", "PATTERN", "GAP")}
            print(f"built {queue_path}: {len(q)} items {groups}")
        self.queue = ld.read_queue(queue_path)
        self.labels_path = folder / "labels.csv"
        self.current = ld.current_labels(ld.read_label_rows(self.labels_path))
        self.review = review
        if review:                                          # disagreements and your ? items
            pairs = eval_labels.review_items(eval_labels.labelled_items(folder))
            self.queue = [q for q, _ in pairs]
            if not self.queue:
                raise SystemExit("no disagreements or unsure (?) labels to review (yet)")
        self.undo_stack: list[tuple[int, str]] = []         # (index, label before)
        self._template = False                              # not built yet

    def __len__(self):
        return len(self.queue)

    def start_index(self) -> int:
        return 0 if self.review else ld.first_unlabeled(self.queue, self.current)

    def kind(self, i): return self.queue[i].kind
    def centre_ms(self, i): return self.queue[i].utc_ms
    def label_of(self, i): return self.current.get(self.queue[i].item, {}).get("label", "")
    def segments_for(self, i): return self.segments
    def beat_utc_for(self, i): return self.beat_utc
    def valid(self, i): return ld.valid_labels(self.kind(i))
    def done_count(self): return len(self.current)

    def span_ms(self, i):
        q = self.queue[i]
        if q.kind != "GAP":
            return None
        k = int(np.searchsorted(self.beat_utc, q.utc_ms))
        if 0 < k < len(self.beat_utc):
            return float(self.beat_utc[k - 1]), float(self.beat_utc[k])
        return None

    def template(self):
        if self._template is False:
            self._template = build_template(self.segments, self.beats)
        return self._template

    def title(self, i):
        q = self.queue[i]
        t = datetime.fromtimestamp(q.utc_ms / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
        if self.review:
            return f"{self.name}   REVIEW of disagreements   {i + 1} / {len(self)}   {t} UTC"
        return f"{self.name}   item {i + 1} / {len(self)}   labelled {self.done_count()}   {t} UTC"

    def question(self, i):
        if self.review:
            ours = self.queue[i].our_class or "(gap item)"
            said = "were unsure (?)" if self.label_of(i) == "?" else f"said {self.label_of(i)}"
            return (f"You {said}, we said {ours}. Press a label key to change "
                    f"your label, -> to keep it   (g shows our features)")
        if self.kind(i) == "GAP":
            return ("Long interval (blue). Is there a blocked early beat in it - a small early bump with no spike "
                    "after it?   b yes · n no · ? unsure   (click on the bump to mark it)")
        return "Beat in yellow:   n normal · s early, same shape · v different shape · q can't tell · ? unsure   (h = help)"

    def reveal_text(self, i):
        q = self.queue[i]
        if q.kind == "GAP":
            k = int(np.searchsorted(self.beat_utc, q.utc_ms))
            b = self.beats[min(k, len(self.beats) - 1)].raw
            return f"gap: RR {b.get('rrMs')} ms, reference {b.get('rrRefMs')} ms"
        r = self.beats[int(np.searchsorted(self.beat_utc, q.utc_ms))].raw
        keys = ("rrMs", "rrRefMs", "prematurity", "shortening", "compensation", "corr", "qrsWidthMs", "widthRatio", "sqi", "driftRatio", "vDoubt")
        return f"ours: {r['class']}   " + "  ".join(f"{k} {r.get(k) or '-'}" for k in keys)

    def answer(self, i, label, bump_ms=None):
        q = self.queue[i]
        before = self.label_of(i)
        ld.append_label(self.labels_path, q.item, q.utc_ms, q.kind, label,
                        bump_utc_ms=None if bump_ms is None else round(bump_ms))
        self.current = ld.current_labels(ld.read_label_rows(self.labels_path))
        self.undo_stack.append((i, before))
        return None

    def undo(self) -> int | None:
        if not self.undo_stack:
            return None
        i, before = self.undo_stack.pop()
        q = self.queue[i]
        ld.append_label(self.labels_path, q.item, q.utc_ms, q.kind, before)   # appended, nothing deleted
        self.current = ld.current_labels(ld.read_label_rows(self.labels_path))
        return i


class GallerySession:
    """MIT-BIH beats with the cardiologists' label hidden until you have answered."""
    reveal_after_answer = True
    review = False

    def __init__(self, reference_db: Path, per_class: int, labels_root: Path, hard: bool = False):
        self.db = reference_db
        self.items = ld.pick_gallery(reference_db, per_class, hard=hard)
        if not self.items:
            raise SystemExit(f"no reference records in {reference_db}; run fetch_physionet.py first")
        self.path = labels_root / "gallery.csv"
        self.score = ld.GalleryScore()
        self.answers: dict[int, str] = {}
        self._cache: dict[str, tuple] = {}
        self.name = "gallery"

    def __len__(self): return len(self.items)
    def start_index(self): return 0
    def kind(self, i): return "BEAT"
    def centre_ms(self, i): return self.items[i].utc_ms
    def label_of(self, i): return self.answers.get(i, "")
    def valid(self, i): return ld.BEAT_LABELS
    def done_count(self): return len(self.answers)
    def span_ms(self, i): return None
    def template(self): return None
    def undo(self): return None

    def _record(self, i):
        rec = self.items[i].record
        if rec not in self._cache:
            ecg, _ = read_ecg(self.db / rec)
            beats, _ = ld.read_reference_beats(self.db / rec)
            self._cache[rec] = (build_segments(ecg), np.array([b[0] for b in beats], dtype=np.int64))
        return self._cache[rec]

    def segments_for(self, i): return self._record(i)[0]
    def beat_utc_for(self, i): return self._record(i)[1]

    def title(self, i):
        return f"gallery (MIT-BIH, not your data)   beat {i + 1} / {len(self)}   score {self.score.correct} / {self.score.total}"

    def question(self, i):
        return "Beat in yellow:   n normal · s early, same shape · v different shape · q can't tell · ? unsure   (h = help)"

    def reveal_text(self, i):
        return f"cardiologists: {self.items[i].truth}   (record {self.items[i].record})"

    def answer(self, i, label, bump_ms=None):
        item = self.items[i]
        self.answers[i] = label
        self.score.add(item.truth, label)
        ld.append_gallery_answer(self.path, item.record, item.utc_ms, item.truth, label)
        verdict = "correct" if label == item.truth else "different"
        lines = [f"You: {label}   Cardiologists: {item.truth}   -> {verdict}   (score {self.score.correct} / {self.score.total})"]
        lines += self.score.mistakes()[:3]
        lines.append("space / Enter: next beat")
        return "\n".join(lines)


# -- the window ---------------------------------------------------------------------------------
@dataclass
class _State:
    index: int = 0
    show_help: bool = False
    show_template: bool = False
    reveal: bool = False
    feedback: str = ""
    waiting: bool = False          # gallery: answered, waiting for space
    bump_ms: float | None = None


class Labeler:
    def __init__(self, session, index: int | None = None):
        import matplotlib.pyplot as plt
        for k in [k for k in plt.rcParams if k.startswith("keymap.")]:
            plt.rcParams[k] = []                            # our keys must not trigger matplotlib's
        self.plt, self.s = plt, session
        self.st = _State(session.start_index() if index is None else index, reveal=session.review)
        self.fig = plt.figure(figsize=(13, 6))
        self.ax = self.fig.add_axes([0.06, 0.2, 0.92, 0.62])
        self.title = self.fig.suptitle("", fontsize=10)
        self.question = self.fig.text(0.06, 0.9, "", fontsize=10)
        self.info = self.fig.text(0.06, 0.04, "", fontsize=10, va="bottom")
        self.helpbox = self.fig.text(0.5, 0.5, HELP, ha="center", va="center", family="monospace", fontsize=9,
                                     bbox=dict(boxstyle="round", fc="white", ec="black"), visible=False, zorder=10)
        self.fig.canvas.mpl_connect("key_press_event", self._on_key)
        self.fig.canvas.mpl_connect("button_press_event", self._on_click)
        self.draw()

    def draw(self):
        s, st = self.s, self.st
        st.index = max(0, min(st.index, len(s)))
        if st.index >= len(s):
            self.ax.clear()
            self.title.set_text(f"{s.name}: all {len(s)} items are labelled" if s.done_count() >= len(s) else "end of the queue")
            self.question.set_text("Press <- to go back, Esc to quit.")
            self.info.set_text("")
            self.fig.canvas.draw_idle()
            return
        i = st.index
        span = s.span_ms(i)
        draw_strip(self.ax, s.segments_for(i), s.beat_utc_for(i), s.centre_ms(i), s.kind(i), span_ms=span,
                   bump_ms=st.bump_ms, template=s.template() if st.show_template else None)
        self.title.set_text(s.title(i))
        self.question.set_text(s.question(i))
        mine = s.label_of(i)
        lines = []
        if mine and not s.reveal_after_answer:
            lines.append(f"your label: {mine}")
        if st.feedback:
            lines.append(st.feedback)
        elif st.reveal:
            lines.append(s.reveal_text(i))
        self.info.set_text("\n".join(lines))
        self.helpbox.set_visible(st.show_help)
        self.fig.canvas.draw_idle()

    def _go(self, index: int):
        self.st.index = index
        self.st.feedback, self.st.waiting, self.st.bump_ms = "", False, None
        self.st.reveal = self.s.review
        self.draw()

    def _on_key(self, ev):
        k, s, st = ev.key, self.s, self.st
        if k == "escape":
            self._finish()
            self.plt.close(self.fig)
            return
        if k == "h":
            st.show_help = not st.show_help
        elif st.waiting:
            if k in (" ", "enter", "right"):
                self._go(st.index + 1)
                return
        elif k == "right":
            self._go(st.index + 1)
            return
        elif k == "left":
            self._go(st.index - 1)
            return
        elif k == "u":
            back = s.undo()
            if back is None:
                st.feedback = "nothing to undo" if not s.reveal_after_answer else "undo is not available in the gallery"
            else:
                self._go(back)
                return
        elif k == "t":
            if s.template() is None:
                st.feedback = "no template available here"
            else:
                st.show_template = not st.show_template
        elif k == "g":
            st.reveal = not st.reveal
        elif st.index < len(s) and k is not None and k.upper() in s.valid(st.index) and len(k) == 1:
            fb = s.answer(st.index, k.upper() if k != "?" else "?", st.bump_ms if k.upper() == "B" else None)
            if s.reveal_after_answer:
                st.feedback, st.waiting = fb, True
            else:
                self._go(st.index + 1)
                return
        self.draw()

    def _on_click(self, ev):
        tb = getattr(self.fig.canvas, "toolbar", None)
        if (ev.inaxes is self.ax and ev.button == 1 and self.st.index < len(self.s)
                and self.s.kind(self.st.index) == "GAP" and not (tb is not None and tb.mode)):
            self.st.bump_ms = ev.xdata * 1000
            self.draw()

    def _finish(self):
        s = self.s
        print(f"{s.done_count()} of {len(s)} items labelled.")
        if s.reveal_after_answer and s.score.total:
            print(f"gallery score: {s.score.correct} / {s.score.total}")
            for line in s.score.mistakes():
                print("  " + line)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("recording", nargs="?", type=Path, help="session folder with ecg*.bin")
    ap.add_argument("--gallery", action="store_true", help="practice on MIT-BIH beats instead")
    ap.add_argument("--analysis", type=Path, help="output folder of `analyze` (default: like the CLI)")
    ap.add_argument("--labels", type=Path, default=Path("data/labels"), help="where labels are kept")
    ap.add_argument("--reference", type=Path, default=Path("data/reference/mitdb"), help="gallery: converted MIT-BIH")
    ap.add_argument("--hard", action="store_true",
                    help="gallery: all labels as the cardiologists gave them (many S are not visibly early)")
    ap.add_argument("--per-class", type=int, default=20, help="gallery: beats of each of N, S, V")
    ap.add_argument("--index", type=int, help="start at this item (1-based); default: first unlabelled")
    ap.add_argument("--review", action="store_true",
                    help="show only the disagreements and your ? items, with our class revealed")
    ap.add_argument("--save", type=Path, help="write the first strip as a PNG instead of opening a window")
    args = ap.parse_args(argv)
    if args.gallery == (args.recording is not None):
        ap.error("give either a session folder or --gallery")

    if args.gallery:
        session = GallerySession(args.reference, args.per_class, args.labels, args.hard)
    else:
        session = OwnSession(args.recording, args.analysis or default_analysis_dir(args.recording), args.labels,
                              review=args.review)
    if args.save:
        import matplotlib
        matplotlib.use("Agg")
    else:
        import matplotlib
        import matplotlib.pyplot  # noqa: F401  (selects the backend)
        if matplotlib.get_backend().lower() == "agg":      # no GUI toolkit found: it would exit silently
            raise SystemExit("matplotlib has no window backend (this Python has no tkinter).\n"
                             "Fix once:  pip install PyQt6      (see analysis/README.md)")
    app = Labeler(session, None if args.index is None else args.index - 1)
    if args.save:
        app.fig.savefig(args.save, dpi=100)
        print(f"wrote {args.save}")
    else:
        app.plt.show()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
