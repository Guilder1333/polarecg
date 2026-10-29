package com.punkunpun.polarisecg.recorder

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.punkunpun.polarisecg.storage.BeatDao
import com.punkunpun.polarisecg.storage.BeatEntity
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
 * With [beats] (the database's beat table) the zip also gets `beats.csv` with the beats the phone saved for
 * the session's time range, to compare with `analyze` of the same raw files (P5.3).
 * The database file itself is not included: it holds up to 90 days of beats, far too much for a night's export.
 */
fun zipSession(context: Context, session: File, beats: BeatDao? = null): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() } // keep only the latest export
    val zip = File(dir, "${session.name}.zip")
    ZipOutputStream(zip.outputStream().buffered()).use { out ->
        session.listFiles()?.sortedBy { it.name }?.forEach { f ->
            out.putNextEntry(ZipEntry("${session.name}/${f.name}"))
            f.inputStream().use { it.copyTo(out) }
            out.closeEntry()
        }
        if (beats != null) {
            out.putNextEntry(ZipEntry("${session.name}/beats.csv"))
            writeBeatsCsv(out, sessionBeats(session, beats))
            out.closeEntry()
        }
    }
    return zip
}

/** The beats saved between the session's start and its end (or now, if it was not stopped properly or is running). */
private fun sessionBeats(session: File, dao: BeatDao): List<BeatEntity> {
    val meta = SessionFolders.readMeta(session)
    val start = meta?.optString("startUtc")?.takeIf { it.isNotEmpty() }?.let { Instant.parse(it).toEpochMilli() } ?: 0L
    val end = meta?.optString("endUtc")?.takeIf { it.isNotEmpty() && it != "null" }?.let { Instant.parse(it).toEpochMilli() }
        ?: System.currentTimeMillis()
    return dao.beatsBetween(start, end + 60_000L) // a minute of slack: the last beats come out after the stop
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
