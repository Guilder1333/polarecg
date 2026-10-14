package com.punkunpun.polarisecg.dsp.raw

import java.io.File

/*
 * Reading a whole session folder (docs/RAW_FORMAT.md, "Files and rotation"): all rotated files of
 * one stream, in time order, as one list of records. The Python reader in analysis/polaris_raw.py
 * does the same; keep the two in sync.
 */

/**
 * The `.bin` files of stream [prefix] ("ecg", "acc" or "hr") in [dir], oldest first.
 *
 * Finished files are `<prefix>_<stamp>[_empty|_invalid][_n].bin`; the stamp sorts chronologically,
 * so sorting by name is sorting by time. A current file (`<prefix>.bin`) is the newest data and
 * comes last. Files that are not part of this stream are not returned.
 */
fun listSegmentFiles(dir: File, prefix: String): List<File> {
    val files = dir.listFiles() ?: return emptyList()
    val finished = files
        .filter { it.isFile && it.name.startsWith("${prefix}_") && it.name.endsWith(".bin") }
        .sortedBy { it.name }
    val current = files.filter { it.isFile && it.name == "$prefix.bin" }
    return finished + current
}

/** Records of one stream, concatenated over all segment files, plus files that could not be read. */
class RawStreamData(val records: List<RawRecord>, val unreadable: List<File>)

/**
 * Reads every segment of stream [prefix] in [dir]. Unreadable files (bad header) are skipped and
 * reported in [RawStreamData.unreadable] instead of failing the whole session. Records are returned
 * in file order, which is arrival order; they are not re-sorted.
 */
fun readStream(dir: File, prefix: String): RawStreamData {
    val records = ArrayList<RawRecord>()
    val unreadable = ArrayList<File>()
    for (file in listSegmentFiles(dir, prefix)) {
        try {
            records += readRawFile(file.readBytes()).records
        } catch (e: IllegalArgumentException) {
            unreadable += file
        }
    }
    return RawStreamData(records, unreadable)
}
