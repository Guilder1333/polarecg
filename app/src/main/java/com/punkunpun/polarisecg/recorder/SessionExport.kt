package com.punkunpun.polarisecg.recorder

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.punkunpun.polarisecg.storage.BeatDao
import com.punkunpun.polarisecg.storage.BeatEntity
import com.punkunpun.polarisecg.storage.PolarisDatabase
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
 * With [database] the zip also gets `polaris.db` (a copy of the whole database) and `beats.csv` (the beats saved for the
 * session's time range, to compare with `analyze` of the same raw files, P5.3). **Once the zip is complete, the exported
 * beats are deleted from the database**: the owner exports everything, so the database does not need to keep it.
 * Only beats up to the moment of the copy are deleted; beats that arrive while exporting stay. The raw files are never touched.
 */
fun zipSession(context: Context, session: File, database: PolarisDatabase? = null): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() } // keep only the latest export
    val zip = File(dir, "${session.name}.zip")
    var exportedUpTo: Long? = null
    ZipOutputStream(zip.outputStream().buffered()).use { out ->
        session.listFiles()?.sortedBy { it.name }?.forEach { f ->
            out.putNextEntry(ZipEntry("${session.name}/${f.name}"))
            f.inputStream().use { it.copyTo(out) }
            out.closeEntry()
        }
        if (database != null) {
            val dao = database.beatDao()
            exportedUpTo = dao.latestUtcMs()
            out.putNextEntry(ZipEntry("${session.name}/beats.csv"))
            writeBeatsCsv(out, sessionBeats(session, dao))
            out.closeEntry()
            // Room keeps recent writes in a side file (-wal); a checkpoint moves them into polaris.db before it is copied.
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            out.putNextEntry(ZipEntry("${session.name}/polaris.db"))
            context.getDatabasePath(PolarisDatabase.FILE_NAME).inputStream().use { it.copyTo(out) }
            out.closeEntry()
        }
    }
    // TODO(P5.3b): temporary. Remove this clearing (and "and clear saved beats" on the button) once the database is the
    // main store (summary, review, labels): then the export must not delete anything. See the plan, P5.3b.
    // Here the zip is closed and complete. Only now is anything deleted.
    if (database != null && exportedUpTo != null && zip.length() > 0) {
        database.beatDao().deleteBefore(exportedUpTo + 1)
        database.openHelper.writableDatabase.execSQL("VACUUM") // give the space back to the file system
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
