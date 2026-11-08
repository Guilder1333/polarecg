# PolarECG

Android app (Kotlin, Jetpack Compose) for real-time on-device ECG monitoring with a Polar H10:
premature beat (PVC/PAC) detection and HRV. Everything runs on the phone; no cloud.

- **The plan and task list live in `docs/DEVELOPMENT_PLAN.md`.** Read it before starting work.
  Tasks have IDs (e.g. `P1.4`) and "Done when" criteria. Mark finished tasks with `- [x]`.
- Signal processing / classification code goes in `:core-dsp` (pure Kotlin, no Android imports),
  so it can be unit-tested on the PC and run by `:tools:cli`.
- The owner is still learning Kotlin: explain non-obvious Kotlin/Android constructs briefly
  when you write them.
- Never commit recordings or health data; they belong in `data/` (git-ignored).

## Raw recording rules (decided with the owner; details in `docs/RAW_FORMAT.md` and plan §8)

- **Rotating-log layout.** Files being written have no timestamp (`ecg.bin`, `acc.bin`, `hr.bin`,
  `session.json`). Finished files are renamed `<prefix>_<yyyyMMdd'T'HHmmss'Z'>.bin` using the
  **first record's `phoneTimeMs`**, to the second. Rotate every UTC hour, on stop, on recovery.
- **Never delete or overwrite recording files.** If a name is taken, add a counter suffix (`_1`) and
  retry. Header-only files are kept and marked `_empty`, unreadable ones `_invalid`.
- **One session = one folder, across kills.** `session.json` without `endUtc` = not stopped
  properly; the next start **resumes** it (archive leftovers, add an `interruptions` entry).
  Only the user's Stop writes `endUtc`, and the next Start is then a new session.
  Do not treat `Service.onDestroy` as a user stop.
- `session.json` is written atomically (temp file + move). Naming/recovery logic lives in
  `:core-dsp` (`dsp/raw/RawSegments.kt`) so it is unit-tested on the PC.
- If you change the layout, update `docs/RAW_FORMAT.md` and the "Decisions" list in plan §8.

## Deep links (open a beat on the phone)

`polarecg://beat/<utcMs>` opens the beat window for the beat at that UTC time (ms, the `utcMs` of `beats.csv` or the
database) on the phone. Use it instead of rendering ECG pictures when the owner asks to look at a beat: send the link,
the owner looks at the real screen and can label it there.

```
MSYS_NO_PATHCONV=1 adb shell am start -a android.intent.action.VIEW -d "polarecg://beat/1794120032399"
```

- Works whether the app is closed or open on any screen (`MainActivity` is `singleTop`; `onNewIntent` handles the open case).
  It shows the browse behind the beat (class of the beat, its local day) so Prev/Next work; the permissions screen
  comes first if the permissions are missing.
- Code: `MainActivity.readDeepLink`, `BrowseRoute(deepBeatMs)`, `BeatDao.beatAt`; manifest intent filter on `MainActivity`.
- The beat must be in the phone's database (the window is cut from the raw files on the phone). A time with no beat
  still opens, showing "no ECG" if the raw files do not cover it.
- Do not take screenshots of the phone to check it unless the owner asks: the screen shows health data.
