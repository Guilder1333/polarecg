package com.punkunpun.polarisecg.recorder

import com.punkunpun.polarisecg.dsp.raw.RawNames
import com.punkunpun.polarisecg.dsp.raw.recoverCurrentFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

internal const val SESSION_JSON = "session.json"
internal const val TIME_MAP_FILE = "timemap.csv"

/** Layout number written to session.json; sessions without it come from before the rotating layout. */
internal const val LAYOUT_VERSION = 2

/**
 * A session folder that is open for writing.
 * [timeMapConnectionBase] is the number the first connection of this run gets in timemap.csv, so
 * connection numbers keep increasing across a resume instead of starting from 0 again.
 */
class OpenedSession(
    val dir: File,
    val startMs: Long,
    val resumed: Boolean,
    val meta: JSONObject,
    val timeMapConnectionBase: Int,
)

/**
 * Session lifecycle (decisions are in docs/RAW_FORMAT.md, "Sessions"):
 *  - A session is one folder `<startUtc>/` that holds ALL its data, also across crashes.
 *  - session.json without `endUtc` means "not stopped properly": killed by Android or by the user.
 *    The next start RESUMES that session: leftovers are archived and an entry is added to
 *    `interruptions`.
 *  - Only an explicit stop writes `endUtc`. After that, the next start is a completely new session.
 */
object SessionFolders {

    /**
     * Opens the session to write into. Resumes the newest folder if it was never stopped properly,
     * was written with the current layout, and belongs to the same strap; otherwise starts a new
     * session. Every other unfinished folder is closed as "recovered" so nothing stays open forever.
     */
    fun open(sessionsDir: File, deviceId: String, nowMs: Long, newMeta: (startMs: Long) -> JSONObject): OpenedSession {
        sessionsDir.mkdirs()
        val folders = sessionsDir.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name }.orEmpty()
        var resumable: Pair<File, JSONObject>? = null

        for ((index, dir) in folders.withIndex()) {
            val meta = readMeta(dir)
            if (meta != null && !meta.isNull("endUtc")) continue // stopped properly
            val canResume = index == 0 && meta != null &&
                meta.optInt("layout", 1) == LAYOUT_VERSION && meta.optString("deviceId") == deviceId
            if (canResume) resumable = dir to meta!! else abandon(dir, meta, nowMs)
        }

        if (resumable != null) {
            val (dir, meta) = resumable
            val recovery = recoverCurrentFiles(dir)
            val interruptions = meta.optJSONArray("interruptions") ?: JSONArray().also { meta.put("interruptions", it) }
            interruptions.put(
                JSONObject()
                    .put("lastDataUtc", recovery.lastDataMs?.let { iso(it) } ?: JSONObject.NULL)
                    .put("resumedUtc", iso(nowMs)),
            )
            writeMeta(dir, meta)
            return OpenedSession(dir, parseStart(meta, dir, nowMs), true, meta, lastTimeMapConnection(dir) + 1)
        }

        var dir = File(sessionsDir, RawNames.stamp(nowMs))
        var counter = 0
        while (dir.exists()) dir = File(sessionsDir, "${RawNames.stamp(nowMs)}_${++counter}") // two starts in one second
        dir.mkdirs()
        val meta = newMeta(nowMs).put("layout", LAYOUT_VERSION).put("interruptions", JSONArray())
        writeMeta(dir, meta)
        return OpenedSession(dir, nowMs, false, meta, 0)
    }

    /** The user pressed stop: record the end time. The session is finished and will not be resumed. */
    fun end(session: OpenedSession, endMs: Long) {
        session.meta.put("endUtc", iso(endMs))
        writeMeta(session.dir, session.meta)
    }

    /**
     * Closes a folder nobody will resume: archives its leftovers and sets `endUtc` to the time of
     * its last data (or of its newest file), plus `"recovered": true` so it is not mistaken for a
     * clean stop. [meta] is null when session.json is missing or unreadable.
     */
    internal fun abandon(dir: File, meta: JSONObject?, nowMs: Long) {
        val recovery = recoverCurrentFiles(dir)
        val endMs = recovery.lastDataMs
            ?: dir.listFiles()?.maxOfOrNull { it.lastModified() }?.takeIf { it > 0 }
            ?: nowMs
        val m = meta ?: JSONObject().put("formatVersion", 1).put("startUtc", iso(folderStartMs(dir) ?: endMs))
        m.put("endUtc", iso(endMs)).put("recovered", true)
        writeMeta(dir, m)
    }

    /** Parsed session.json, or null if it is missing or damaged (e.g. killed while writing). */
    internal fun readMeta(dir: File): JSONObject? = try {
        JSONObject(File(dir, SESSION_JSON).readText())
    } catch (e: Exception) {
        null
    }

    /**
     * Written to a temporary file and moved into place: the file is either the old or the new
     * version, never half-written, even if the process is killed in the middle.
     */
    internal fun writeMeta(dir: File, meta: JSONObject) {
        val tmp = File(dir, "$SESSION_JSON.tmp")
        tmp.writeText(meta.toString(2))
        Files.move(tmp.toPath(), File(dir, SESSION_JSON).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Connection number in the last row of timemap.csv, or -1 if there is none. */
    private fun lastTimeMapConnection(dir: File): Int {
        val file = File(dir, TIME_MAP_FILE)
        if (!file.exists()) return -1
        return file.readLines().asReversed()
            .firstNotNullOfOrNull { it.substringBefore(',').toIntOrNull() } ?: -1
    }

    private fun parseStart(meta: JSONObject, dir: File, fallbackMs: Long): Long =
        runCatching { Instant.parse(meta.getString("startUtc")).toEpochMilli() }.getOrNull()
            ?: folderStartMs(dir) ?: fallbackMs

    private val FOLDER_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** Start time encoded in a folder name like `20261012T221530Z` (a `_1` suffix is ignored). */
    private fun folderStartMs(dir: File): Long? = runCatching {
        LocalDateTime.parse(dir.name.take(16), FOLDER_STAMP).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()

    internal fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
}
