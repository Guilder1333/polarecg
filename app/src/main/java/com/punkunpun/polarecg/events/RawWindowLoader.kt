package com.punkunpun.polarecg.events

import com.punkunpun.polarecg.dsp.event.cutWindow
import com.punkunpun.polarecg.dsp.event.ecgFilesFor
import com.punkunpun.polarecg.dsp.live.EcgClip
import com.punkunpun.polarecg.dsp.raw.RawRecord
import com.punkunpun.polarecg.dsp.raw.listSegmentFiles
import com.punkunpun.polarecg.dsp.raw.readRawFile
import com.punkunpun.polarecg.recorder.SessionFolders
import java.io.File
import java.time.Instant

/**
 * The raw ECG of `center ± halfWidthMs` from the recordings in [sessionsDir], or null if no session has it (the raw files were
 * deleted, the center lies in a hole of the signal, or the time is outside every recording). Blocking and heavy: it reads the
 * session from its first file up to the window (about 2 MB per recorded hour, so a night is 20-40 MB) because the beats'
 * clock starts there (see [ecgFilesFor]): call it off the UI thread. Newest session first, so a recent beat is found at once.
 */
fun loadRawWindow(sessionsDir: File, centerUtcMs: Long, halfWidthMs: Long): EcgClip? {
    val sessions = sessionsDir.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: return null
    for (dir in sessions) {
        // A session that was stopped before the window cannot hold it: skip it without reading its (large) files.
        val end = SessionFolders.readMeta(dir)?.optString("endUtc")?.takeIf { it.isNotEmpty() && it != "null" }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        if (end != null && end + 60_000L < centerUtcMs) continue
        val files = ecgFilesFor(listSegmentFiles(dir, "ecg"), centerUtcMs, halfWidthMs)
        if (files.isEmpty()) continue
        val frames = ArrayList<RawRecord.Ecg>()
        for (f in files) {
            // an unreadable file (bad header) is skipped, like in readStream: the others may still hold the window
            val records = runCatching { readRawFile(f.readBytes()).records }.getOrNull() ?: continue
            records.filterIsInstanceTo(frames)
        }
        cutWindow(frames, centerUtcMs, halfWidthMs)?.let { return it }
    }
    return null
}
