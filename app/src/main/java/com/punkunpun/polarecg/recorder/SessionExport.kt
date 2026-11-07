package com.punkunpun.polarecg.recorder

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.punkunpun.polarecg.dsp.annotations.labelsCsv
import com.punkunpun.polarecg.storage.BeatDao
import com.punkunpun.polarecg.storage.BeatEntity
import com.punkunpun.polarecg.storage.EventDao
import com.punkunpun.polarecg.storage.EventEntity
import com.punkunpun.polarecg.storage.PolarDatabase
import com.punkunpun.polarecg.storage.clipsDir
import com.punkunpun.polarecg.storage.toRecord
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Newest session folder (names are UTC timestamps, so the alphabetical maximum is the newest). */
fun latestSession(sessionsDir: File): File? =
    sessionsDir.listFiles { f -> f.isDirectory }?.maxByOrNull { it.name }

/**
 * Zips one session folder into the cache directory and returns the zip. Blocking: call off the UI thread.
 *
 * With [database] the zip also gets `polar.db` (a copy of the whole database) and `beats.csv` (the beats saved for the
 * session's time range, to compare with `analyze` of the same raw files, P5.3), `events.csv`, `labels.csv` (the owner's labels, PC format) and the ECG clips of the session's
 * events in `clips/` (P5.4; read them with `cli`'s clip format, docs/RAW_FORMAT.md). Nothing is deleted by an export (P5.3b).
 */
fun zipSession(context: Context, session: File, database: PolarDatabase? = null): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() } // keep only the latest export
    val zip = File(dir, "${session.name}.zip")
    ZipOutputStream(zip.outputStream().buffered()).use { out ->
        session.listFiles()?.sortedBy { it.name }?.forEach { f ->
            out.putNextEntry(ZipEntry("${session.name}/${f.name}"))
            f.inputStream().use { it.copyTo(out) }
            out.closeEntry()
        }
        if (database != null) {
            val dao = database.beatDao()
            out.putNextEntry(ZipEntry("${session.name}/beats.csv"))
            writeBeatsCsv(out, sessionBeats(session, dao))
            out.closeEntry()
            val events = sessionEvents(session, database.eventDao())
            out.putNextEntry(ZipEntry("${session.name}/events.csv"))
            writeEventsCsv(out, events)
            out.closeEntry()
            // The owner's labels of the session's beats, in the PC tool's format: copy it to data/labels/<session>/ for `analyze --labels`.
            out.putNextEntry(ZipEntry("${session.name}/labels.csv"))
            val labels = sessionRange(session).let { (from, to) -> database.labelDao().historyBetween(from, to) }
            out.write(labelsCsv(labels.map { it.toRecord() }).toByteArray(Charsets.UTF_8))
            out.closeEntry()
            for (name in events.mapNotNull { it.clipFile }) {
                val file = File(clipsDir(context), name).takeIf { it.isFile } ?: continue // pruned, or never written
                out.putNextEntry(ZipEntry("${session.name}/clips/$name"))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
            // Room keeps recent writes in a side file (-wal); a checkpoint moves them into polar.db before it is copied.
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            out.putNextEntry(ZipEntry("${session.name}/polar.db"))
            context.getDatabasePath(PolarDatabase.FILE_NAME).inputStream().use { it.copyTo(out) }
            out.closeEntry()
        }
    }
    return zip
}

/** The session's start and end (or now, if it was not stopped properly or is running), plus a minute of slack: the last beats come out after the stop. */
private fun sessionRange(session: File): Pair<Long, Long> {
    val meta = SessionFolders.readMeta(session)
    val start = meta?.optString("startUtc")?.takeIf { it.isNotEmpty() }?.let { Instant.parse(it).toEpochMilli() } ?: 0L
    val end = meta?.optString("endUtc")?.takeIf { it.isNotEmpty() && it != "null" }?.let { Instant.parse(it).toEpochMilli() }
        ?: System.currentTimeMillis()
    return start to end + 60_000L
}

/** The beats saved in the session's time range. */
private fun sessionBeats(session: File, dao: BeatDao): List<BeatEntity> =
    sessionRange(session).let { (from, to) -> dao.beatsBetween(from, to) }

/** The events saved in the session's time range. */
private fun sessionEvents(session: File, dao: EventDao): List<EventEntity> =
    sessionRange(session).let { (from, to) -> dao.eventsBetween(from, to) }

/** Like `events.csv` of `analyze` (utc, type, duration, detail) plus the database id, the beat count and the clip file. */
private fun writeEventsCsv(out: OutputStream, events: List<EventEntity>) {
    val w = out.bufferedWriter() // not closed: closing would close the zip; flush at the end
    w.write("id,startUtcMs,startUtc,type,durationMs,beats,detail,clipFile\n")
    for (e in events) {
        w.write(
            listOf(
                e.id, e.startUtcMs, Instant.ofEpochMilli(e.startUtcMs), e.type, e.endUtcMs - e.startUtcMs, e.beatCount,
                "\"" + e.detail.replace("\"", "'") + "\"", e.clipFile ?: "",
            ).joinToString(","),
        )
        w.write("\n")
    }
    w.flush()
}

/** Same column names and order as the first columns of `analyze`'s beats.csv (docs/ANALYSIS_FORMAT.md), then the extras. */
private fun writeBeatsCsv(out: OutputStream, beats: List<BeatEntity>) {
    val w = out.bufferedWriter() // not closed: closing would close the zip; flush at the end
    w.write("utcMs,utc,segment,rrMs,class,good,rescued,vDoubt,sqi,motionMg,prematurity,corr,qrsWidthMs,driftRatio\n")
    for (b in beats) {
        w.write(
            listOf(
                b.utcMs, Instant.ofEpochMilli(b.utcMs), b.segment, b.rrMs ?: "", b.cls, if (b.good) 1 else 0,
                if (b.rescued) 1 else 0, if (b.vDoubt) 1 else 0, "%.3f".format(Locale.ROOT, b.sqi),
                b.motionMg.fmt(1), b.prematurity.fmt(3), b.corr.fmt(3), b.qrsWidthMs.fmt(0), b.driftRatio.fmt(3),
            ).joinToString(","),
        )
        w.write("\n")
    }
    w.flush()
}

private fun Double?.fmt(decimals: Int) = if (this == null) "" else "%.${decimals}f".format(Locale.ROOT, this)

/** Opens Android's share sheet for [zip]; the receiving app gets temporary read access. */
fun shareZip(context: Context, zip: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
