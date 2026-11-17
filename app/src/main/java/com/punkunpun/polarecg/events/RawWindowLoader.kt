package com.punkunpun.polarecg.events

import com.punkunpun.polarecg.dsp.event.RawEcgWindow
import com.punkunpun.polarecg.dsp.event.accFilesFor
import com.punkunpun.polarecg.dsp.event.cutWindowAnchored
import com.punkunpun.polarecg.dsp.event.ecgFilesFor
import com.punkunpun.polarecg.dsp.live.EcgClip
import com.punkunpun.polarecg.dsp.motion.ACC_SAMPLE_RATE_HZ
import com.punkunpun.polarecg.dsp.motion.AccWindow
import com.punkunpun.polarecg.dsp.motion.cutAccWindow
import com.punkunpun.polarecg.dsp.raw.RawRecord
import com.punkunpun.polarecg.dsp.raw.listSegmentFiles
import com.punkunpun.polarecg.dsp.raw.readRawFile
import com.punkunpun.polarecg.recorder.SessionFolders
import java.io.File
import java.time.Instant

/** A window cut from the raw files: the ECG, and the accelerometer on the same time line ([acc] is null without ACC data there). */
class RawBeatWindow(val ecg: EcgClip, val acc: AccWindow?)

/**
 * The raw ECG of `center ± halfWidthMs` from the recordings in [sessionsDir], with the accelerometer of the same time, or null if
 * no session has the ECG (the raw files were deleted, the center lies in a hole of the signal, or the time is outside every
 * recording). Blocking and heavy: it reads the session from its first file up to the window (about 2 MB per recorded hour, so a
 * night is 20-40 MB) because the beats' clock starts there (see [ecgFilesFor]): call it off the UI thread. Newest session first, so
 * a recent beat is found at once.
 */
fun loadRawWindow(sessionsDir: File, centerUtcMs: Long, halfWidthMs: Long): RawBeatWindow? {
    val sessions = sessionsDir.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: return null
    for (dir in sessions) {
        // A session that was stopped before the window cannot hold it: skip it without reading its (large) files.
        val meta = SessionFolders.readMeta(dir)
        val end = meta?.optString("endUtc")?.takeIf { it.isNotEmpty() && it != "null" }
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
        val window = cutWindowAnchored(frames, centerUtcMs, halfWidthMs) ?: continue
        val accRate = meta?.optJSONObject("acc")?.optInt("rateHz", ACC_SAMPLE_RATE_HZ) ?: ACC_SAMPLE_RATE_HZ
        // the ACC is extra: if its files are missing or broken, the ECG is still shown
        val acc = runCatching { loadAccWindow(dir, window, accRate) }.getOrNull()
        return RawBeatWindow(window.clip, acc)
    }
    return null
}

/** A file's stamp is the phone time of its first frame, which arrives a little after its samples: look this much further. */
private const val ACC_FILE_MARGIN_MS = 5_000L

/**
 * The accelerometer of the ECG [window], from the ACC files of session [dir]. Only the one or two hourly files around the window are
 * read (about 1 MB each): the samples are placed through the strap's clock, which the ECG window gives ([cutAccWindow]).
 */
private fun loadAccWindow(dir: File, window: RawEcgWindow, rateHz: Int): AccWindow? {
    val from = window.clip.startUtcMs
    val to = window.clip.endUtcMs
    val frames = ArrayList<RawRecord.Acc>()
    for (f in accFilesFor(listSegmentFiles(dir, "acc"), from - ACC_FILE_MARGIN_MS, to + ACC_FILE_MARGIN_MS)) {
        val records = runCatching { readRawFile(f.readBytes()).records }.getOrNull() ?: continue
        records.filterIsInstanceTo(frames)
    }
    return cutAccWindow(frames, window.anchorUtcMs, window.anchorSensorNs, from, to, rateHz)
}
