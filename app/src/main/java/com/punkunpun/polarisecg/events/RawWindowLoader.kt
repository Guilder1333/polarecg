package com.punkunpun.polarisecg.events

import com.punkunpun.polarisecg.dsp.event.cutWindow
import com.punkunpun.polarisecg.dsp.event.ecgFilesFor
import com.punkunpun.polarisecg.dsp.live.EcgClip
import com.punkunpun.polarisecg.dsp.raw.RawRecord
import com.punkunpun.polarisecg.dsp.raw.listSegmentFiles
import com.punkunpun.polarisecg.dsp.raw.readRawFile
import java.io.File

/**
 * The raw ECG of `center ± halfWidthMs` from the recordings in [sessionsDir], or null if no session has it (the raw files were
 * deleted, the center lies in a hole of the signal, or the time is outside every recording). Blocking and heavy (it reads
 * up to three hourly files, about 1-2 MB each): call it off the UI thread. Newest session first, so a recent beat is found at once.
 */
fun loadRawWindow(sessionsDir: File, centerUtcMs: Long, halfWidthMs: Long): EcgClip? {
    val sessions = sessionsDir.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: return null
    for (dir in sessions) {
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
