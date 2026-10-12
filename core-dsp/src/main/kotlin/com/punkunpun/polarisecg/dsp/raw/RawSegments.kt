package com.punkunpun.polarisecg.dsp.raw

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * Rotating-log naming for raw recordings (see docs/RAW_FORMAT.md, "Files and rotation").
 *
 * The file being written is always called `<prefix>.bin` (ecg.bin, acc.bin, hr.bin). When it is
 * finished (hour boundary, clean stop, or recovery after a kill) it is renamed to
 * `<prefix>_<stamp>.bin`, where <stamp> is the phone UTC time of its FIRST record, to the second.
 * Pure JVM: used by the phone and testable on the PC.
 */

object RawNames {
    /** Stream prefixes, in file names. */
    val PREFIXES = listOf("ecg", "acc", "hr")

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** `20261012T221530Z` */
    fun stamp(ms: Long): String = STAMP.format(Instant.ofEpochMilli(ms))

    fun currentFile(dir: File, prefix: String) = File(dir, "$prefix.bin")
}

/** What a `.bin` file turned out to contain. [marker] is added to the file name when not OK. */
enum class SegmentStatus(val marker: String?) {
    /** At least one complete record. */
    OK(null),

    /** A valid header and no complete record. Kept, so we can see that it happened. */
    EMPTY("empty"),

    /** Not a readable raw file (too short, wrong magic/version). Kept for the same reason. */
    INVALID("invalid"),
}

/** [firstPhoneMs]/[lastPhoneMs] are the phone UTC times of the first record and of the latest one. */
class SegmentInfo(val status: SegmentStatus, val firstPhoneMs: Long?, val lastPhoneMs: Long?)

/** Reads [file] and reports its status and time range. A truncated last record is ignored. */
fun inspectSegment(file: File): SegmentInfo {
    val bytes = try {
        file.readBytes()
    } catch (e: IOException) {
        return SegmentInfo(SegmentStatus.INVALID, null, null)
    }
    val parsed = try {
        readRawFile(bytes)
    } catch (e: IllegalArgumentException) {
        return SegmentInfo(SegmentStatus.INVALID, null, null)
    }
    if (parsed.records.isEmpty()) return SegmentInfo(SegmentStatus.EMPTY, null, null)
    return SegmentInfo(
        SegmentStatus.OK,
        parsed.records.first().phoneTimeMs,
        parsed.records.maxOf { it.phoneTimeMs },
    )
}

class ArchivedSegment(val file: File, val info: SegmentInfo)

/**
 * Renames the current file [file] to its timestamped name and returns the result, or null if
 * [file] does not exist. Never deletes anything and never overwrites: if the target name is
 * taken, a counter is added (`_1`, `_2`, ...) and the rename is tried again.
 *
 * The timestamp is the first record's phone time; for EMPTY/INVALID files, which have no record,
 * it is the file's last-modified time (or [nowMs] if the file system does not know it).
 */
fun archiveSegment(file: File, prefix: String, nowMs: Long = System.currentTimeMillis()): ArchivedSegment? {
    if (!file.exists()) return null
    val info = inspectSegment(file)
    val timeMs = info.firstPhoneMs ?: file.lastModified().takeIf { it > 0 } ?: nowMs
    val base = buildString {
        append(prefix).append('_').append(RawNames.stamp(timeMs))
        info.status.marker?.let { append('_').append(it) }
    }
    val dir = file.absoluteFile.parentFile
    for (counter in 0..MAX_NAME_ATTEMPTS) {
        val target = File(dir, if (counter == 0) "$base.bin" else "${base}_$counter.bin")
        try {
            // No REPLACE_EXISTING: if the name is occupied this throws and we try the next one.
            Files.move(file.toPath(), target.toPath())
            return ArchivedSegment(target, info)
        } catch (e: FileAlreadyExistsException) {
            continue
        }
    }
    throw IOException("No free name for $base.bin after $MAX_NAME_ATTEMPTS attempts")
}

private const val MAX_NAME_ATTEMPTS = 1000

class RecoveryResult(val archived: List<ArchivedSegment>, val lastDataMs: Long?)

/**
 * Archives whatever current files (ecg.bin, acc.bin, hr.bin) a killed run left in [dir].
 * [RecoveryResult.lastDataMs] is the newest record time found: when the run really ended.
 * Safe to call repeatedly and after being interrupted itself; with nothing to do it does nothing.
 */
fun recoverCurrentFiles(dir: File): RecoveryResult {
    val archived = RawNames.PREFIXES.mapNotNull { archiveSegment(RawNames.currentFile(dir, it), it) }
    return RecoveryResult(archived, archived.mapNotNull { it.info.lastPhoneMs }.maxOrNull())
}
