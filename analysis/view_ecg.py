"""Interactive ECG viewer: signal, beats coloured by class, quality shaded, jump to time/event.

    python analysis/view_ecg.py data/reference/mitdb/100
    python analysis/view_ecg.py data/sessions/20261012T221530Z --at 03:12:40
    python analysis/view_ecg.py data/sessions/20261012T221530Z --event GAP --width 20
    python analysis/view_ecg.py data/reference/mitdb/100 --save out.png     # no window

Needs the output of ``:tools:cli analyze`` (docs/ANALYSIS_FORMAT.md); by default it is looked up
in ``data/analysis/<parent>-<folder>`` like the CLI does, or give ``--analysis <folder>``.

Keys:  <- / ->  pan half a window    PageUp / PageDown  pan a whole window
       + / -    zoom                 n / p  next / previous beat
       e / E    next / previous event (gaps, later premature beats)
       Home / End  start / end of the recording
The box at the bottom jumps to a time: ``HH:MM:SS`` or ``HH:MM:SS.mmm`` (UTC, date of the
recording), a full ISO time ``2026-10-12T22:15:30Z``, or ``+90`` / ``+1:30`` for seconds
from the start of the recording. The toolbar's magnifier / hand also zoom and pan freely.
"""
from __future__ import annotations

import argparse
import csv
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from polaris_raw import ECG_RATE_HZ, read_ecg  # noqa: E402
from polaris_segments import Segment, build_segments  # noqa: E402

# Colours chosen to differ also for colour-blind eyes; U = not classified yet.
CLASS_COLORS = {"N": "#2a9d3f", "S": "#e08a00", "V": "#d62728", "F": "#9467bd", "Q": "#7f7f7f", "U": "#1f77b4"}
SHADE_COLORS = {"gap": "#999999", "coverage": "#ffb74d", "quality": "#ef5350"}
SHADE_NAMES = {"gap": "no signal (gap)", "coverage": "samples missing", "quality": "poor quality"}


@dataclass
class Beats:
    utc_ms: np.ndarray
    cls: np.ndarray            # array of single-letter strings


@dataclass
class Shade:
    start_ms: float
    end_ms: float
    kind: str                  # "gap" | "coverage" | "quality"


@dataclass
class Event:
    utc_ms: int
    type: str
    duration_ms: int
    detail: str


@dataclass
class ViewData:
    segments: list[Segment]
    beats: Beats
    shades: list[Shade] = field(default_factory=list)
    events: list[Event] = field(default_factory=list)

    @property
    def start_ms(self) -> float:
        return self.segments[0].start_utc_ms

    @property
    def end_ms(self) -> float:
        return max(s.end_utc_ms for s in self.segments)


def default_analysis_dir(recording: Path) -> Path:
    """Same rule as the CLI: data/analysis/<parent>-<folder>."""
    rec = recording.resolve()
    return Path("data") / "analysis" / f"{rec.parent.name}-{rec.name}"


def _read_csv(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def load_view_data(recording: Path, analysis: Path) -> ViewData:
    ecg, _bad = read_ecg(recording)
    segments = build_segments(ecg)
    if not segments:
        raise SystemExit(f"No ECG samples in {recording}")
    if not (analysis / "beats.csv").is_file():
        raise SystemExit(f"{analysis / 'beats.csv'} not found. Run the analyze command first "
                         f"(docs/ANALYSIS_FORMAT.md) or pass --analysis.")

    beat_rows = _read_csv(analysis / "beats.csv")
    beats = Beats(np.array([int(r["utcMs"]) for r in beat_rows], dtype=np.int64),
                  np.array([r["class"] for r in beat_rows], dtype=object))

    shades: list[Shade] = []
    for r in _read_csv(analysis / "windows.csv"):
        start, dur = int(r["startUtcMs"]), int(r["durationMs"])
        if r.get("quality"):                          # column arrives with P3.4
            if float(r["quality"]) < 0.5:
                shades.append(Shade(start, start + dur, "quality"))
        elif float(r["coverage"]) < 0.99:
            shades.append(Shade(start, start + dur, "coverage"))

    events = [Event(int(r["utcMs"]), r["type"], int(r["durationMs"] or 0), r.get("detail", ""))
              for r in _read_csv(analysis / "events.csv")]
    events.sort(key=lambda e: e.utc_ms)
    for e in events:
        if e.type == "GAP":
            shades.append(Shade(e.utc_ms, e.utc_ms + e.duration_ms, "gap"))
    return ViewData(segments, beats, shades, events)


def parse_time(text: str, data: ViewData) -> float:
    """Text typed by the user -> UTC ms. Raises ValueError with a readable message."""
    text = text.strip()
    if not text:
        raise ValueError("empty time")
    if text.startswith("+"):
        secs = 0.0
        for p in text[1:].split(":"):
            secs = secs * 60 + float(p)
        return data.start_ms + secs * 1000
    if "T" in text or "-" in text:
        dt = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.timestamp() * 1000
    h, m, *rest = text.split(":")
    s = float(rest[0]) if rest else 0.0
    day = datetime.fromtimestamp(data.start_ms / 1000, timezone.utc).replace(
        hour=0, minute=0, second=0, microsecond=0)
    ms = day.timestamp() * 1000 + ((int(h) * 60 + int(m)) * 60 + s) * 1000
    if ms < data.start_ms - 1000:       # recording crossed midnight: the time is on the next day
        ms += 86_400_000
    return ms


def window_content(data: ViewData, lo_ms: float, hi_ms: float):
    """What to draw for [lo_ms, hi_ms): ECG pieces (times_s, uv) per segment, beats
    (times_s, y_uv, class) and shades."""
    pieces = []
    for seg in data.segments:
        if seg.end_utc_ms < lo_ms or seg.start_utc_ms > hi_ms:
            continue
        i0 = max(0, seg.index_at(lo_ms) - 1)
        i1 = min(len(seg.samples_uv), seg.index_at(hi_ms) + 2)
        if i1 > i0:
            pieces.append((seg.times_ms(i0, i1) / 1000, seg.samples_uv[i0:i1]))
    b = data.beats
    sel = (b.utc_ms >= lo_ms) & (b.utc_ms < hi_ms)
    beat_t, beat_c = b.utc_ms[sel], b.cls[sel]
    beat_y = np.full(len(beat_t), np.nan)
    for k, t in enumerate(beat_t):          # y = the sample nearest to the R peak
        for seg in data.segments:
            if seg.start_utc_ms <= t <= seg.end_utc_ms:
                idx = seg.index_at(t)
                beat_y[k] = seg.samples_uv[idx]
                break
    shades = [s for s in data.shades if s.end_ms > lo_ms and s.start_ms < hi_ms]
    return pieces, (beat_t / 1000, beat_y, beat_c), shades


def _fmt_time(sec: float, _pos=None) -> str:
    return datetime.fromtimestamp(sec, timezone.utc).strftime("%H:%M:%S")


class Viewer:
    def __init__(self, data: ViewData, title: str, width_s: float = 10.0, center_ms: float | None = None):
        import matplotlib.pyplot as plt
        from matplotlib.ticker import FuncFormatter
        from matplotlib.widgets import TextBox

        self.plt, self.data, self.width_ms = plt, data, width_s * 1000
        self.fig = plt.figure(figsize=(13, 5.5))
        self.ax = self.fig.add_axes([0.06, 0.2, 0.92, 0.68])
        self.formatter = FuncFormatter(_fmt_time)
        self.fig.suptitle(title, fontsize=10)
        self.box = TextBox(self.fig.add_axes([0.2, 0.04, 0.2, 0.06]), "Go to (UTC): ", initial="")
        self.box.on_submit(self._on_submit)
        self.status = self.fig.text(0.45, 0.06, "", fontsize=9)
        self.fig.canvas.mpl_connect("key_press_event", self._on_key)
        self.ax.callbacks.connect("xlim_changed", self._on_xlim)
        self._drawing = False
        self.left_ms = data.start_ms if center_ms is None else center_ms - self.width_ms / 2
        self._clamp()
        self.draw()

    # -- navigation -------------------------------------------------------------------------
    def _clamp(self):
        last = max(self.data.end_ms - self.width_ms, self.data.start_ms)
        self.left_ms = min(max(self.left_ms, self.data.start_ms), last)

    def _on_submit(self, text: str):
        try:
            self.left_ms = parse_time(text, self.data) - self.width_ms / 2
        except (ValueError, IndexError) as e:
            self.status.set_text(f"cannot read time: {e}")
            self.fig.canvas.draw_idle()
            return
        self._clamp()
        self.draw()

    def _on_xlim(self, ax):
        """Toolbar zoom/pan: redraw the data for the new range (we only plot what is visible)."""
        if self._drawing:
            return
        lo, hi = (v * 1000 for v in ax.get_xlim())
        self.left_ms, self.width_ms = lo, max(hi - lo, 200)
        self.draw(keep_xlim=True)

    def _on_key(self, ev):
        k, w = ev.key, self.width_ms
        center = self.left_ms + w / 2
        if k == "right": self.left_ms += w / 2
        elif k == "left": self.left_ms -= w / 2
        elif k == "pagedown": self.left_ms += w
        elif k == "pageup": self.left_ms -= w
        elif k in ("+", "="): self.width_ms = max(w / 2, 1000); self.left_ms = center - self.width_ms / 2
        elif k in ("-", "_"): self.width_ms = min(w * 2, 600_000); self.left_ms = center - self.width_ms / 2
        elif k == "home": self.left_ms = self.data.start_ms
        elif k == "end": self.left_ms = self.data.end_ms
        elif k in ("n", "p"):
            t = self.data.beats.utc_ms
            if k == "n":
                i = int(np.searchsorted(t, center + 1, "left"))
            else:
                i = int(np.searchsorted(t, center - 1, "left")) - 1
            if 0 <= i < len(t):
                self.left_ms = t[i] - w / 2
        elif k in ("e", "E"):
            ts = [e.utc_ms for e in self.data.events]
            cands = [t for t in ts if t > center + 1] if k == "e" else [t for t in ts if t < center - 1][::-1]
            if cands:
                self.left_ms = cands[0] - w / 2
        else:
            return
        self._clamp()
        self.draw()

    # -- drawing ----------------------------------------------------------------------------
    def draw(self, keep_xlim: bool = False):
        from matplotlib.patches import Patch
        self._drawing = True
        try:
            ax = self.ax
            ylim = ax.get_ylim() if keep_xlim else None
            ax.clear()
            lo, hi = self.left_ms, self.left_ms + self.width_ms
            pieces, (bt, by, bc), shades = window_content(self.data, lo, hi)
            for s in shades:
                ax.axvspan(s.start_ms / 1000, s.end_ms / 1000, color=SHADE_COLORS[s.kind], alpha=0.3, lw=0)
            for t, uv in pieces:
                ax.plot(t, uv, color="black", lw=0.8)
            for cls in sorted(set(bc)):
                m = bc == cls
                ax.scatter(bt[m], by[m], s=45, color=CLASS_COLORS.get(cls, "#000000"),
                           zorder=3, label=f"{cls} ({int(m.sum())})")
            for e in self.data.events:
                if lo <= e.utc_ms < hi and e.type != "GAP":
                    ax.axvline(e.utc_ms / 1000, color="#d62728", ls=":", lw=1)
            ax.set_xlim(lo / 1000, hi / 1000)
            if ylim:
                ax.set_ylim(ylim)
            ax.xaxis.set_major_formatter(self.formatter)
            ax.set_ylabel("ECG, µV")
            ax.grid(alpha=0.3)
            handles, _ = ax.get_legend_handles_labels()
            handles += [Patch(color=SHADE_COLORS[k], alpha=0.3, label=SHADE_NAMES[k])
                        for k in sorted({s.kind for s in shades})]
            if handles:
                ax.legend(handles=handles, loc="upper right", fontsize=8, ncol=min(len(handles), 4))
            if not pieces:
                ax.text(0.5, 0.5, "no signal in this window", transform=ax.transAxes, ha="center")
            day = datetime.fromtimestamp(lo / 1000, timezone.utc).strftime("%Y-%m-%d")
            ax.set_xlabel(f"UTC time, {day}   (window {self.width_ms / 1000:g} s)")
            self.status.set_text("")
            self.fig.canvas.draw_idle()
        finally:
            self._drawing = False


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("recording", type=Path, help="folder with ecg*.bin (session or reference record)")
    ap.add_argument("--analysis", type=Path, help="output folder of `analyze` (default: like the CLI)")
    ap.add_argument("--at", help="start centred on this time (formats: see keys help)")
    ap.add_argument("--event", metavar="TYPE", help="start at the first event of this type, e.g. GAP")
    ap.add_argument("--width", type=float, default=10.0, help="window width in seconds (default 10)")
    ap.add_argument("--save", type=Path, help="write a PNG instead of opening a window")
    args = ap.parse_args(argv)

    analysis = args.analysis or default_analysis_dir(args.recording)
    data = load_view_data(args.recording, analysis)

    center = None
    if args.at:
        center = parse_time(args.at, data)
    elif args.event:
        match = [e for e in data.events if e.type.upper() == args.event.upper()]
        if not match:
            raise SystemExit(f"no event of type {args.event} in {analysis / 'events.csv'}")
        center = match[0].utc_ms + match[0].duration_ms / 2

    if args.save:
        import matplotlib
        matplotlib.use("Agg")
    v = Viewer(data, f"{args.recording}  ({len(data.segments)} segments, {len(data.beats.utc_ms)} beats)",
               args.width, center)
    if args.save:
        v.fig.savefig(args.save, dpi=110)
        print(f"wrote {args.save}")
    else:
        v.plt.show()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
