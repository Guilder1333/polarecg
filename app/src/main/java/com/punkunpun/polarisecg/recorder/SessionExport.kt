package com.punkunpun.polarisecg.recorder

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Newest session folder (names are UTC timestamps, so the alphabetical maximum is the newest). */
fun latestSession(sessionsDir: File): File? =
    sessionsDir.listFiles { f -> f.isDirectory }?.maxByOrNull { it.name }

/** Zips one session folder into the cache directory and returns the zip. Blocking: call off the UI thread. */
fun zipSession(context: Context, session: File): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() } // keep only the latest export
    val zip = File(dir, "${session.name}.zip")
    ZipOutputStream(zip.outputStream().buffered()).use { out ->
        session.listFiles()?.sortedBy { it.name }?.forEach { f ->
            out.putNextEntry(ZipEntry("${session.name}/${f.name}"))
            f.inputStream().use { it.copyTo(out) }
            out.closeEntry()
        }
    }
    return zip
}

/** Opens Android's share sheet for [zip]; the receiving app gets temporary read access. */
fun shareZip(context: Context, zip: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
