# PolarisECG

Android app (Kotlin, Jetpack Compose) for real-time on-device ECG monitoring with a Polar H10:
premature beat (PVC/PAC) detection and HRV. Everything runs on the phone; no cloud.

- **The plan and task list live in `docs/DEVELOPMENT_PLAN.md`.** Read it before starting work.
  Tasks have IDs (e.g. `P1.4`) and "Done when" criteria. Mark finished tasks with `- [x]`.
- Signal processing / classification code goes in `:core-dsp` (pure Kotlin, no Android imports),
  so it can be unit-tested on the PC and run by `:tools:cli`.
- The owner is still learning Kotlin: explain non-obvious Kotlin/Android constructs briefly
  when you write them.
- Never commit recordings or health data; they belong in `data/` (git-ignored).
