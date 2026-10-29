package com.punkunpun.polarisecg.annotations

import com.punkunpun.polarisecg.recorder.SessionFolders
import com.punkunpun.polarisecg.recorder.latestSession
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val ANNOTATIONS_FILE = "annotations.jsonl"

/**
 * Writes the owner's actions and events into the running session's folder (P5.10) and keeps the picker's [types].
 * Blocking file work on small files: call from `Dispatchers.IO`.
 */
class AnnotationStore(private val sessionsDir: File, val types: AnnotationTypes) {
    private val _currentAction = MutableStateFlow<Annotation?>(null)

    /** The action running now in the open session, null if none. Updated by [record] and [refresh]. */
    val currentAction: StateFlow<Annotation?> = _currentAction.asStateFlow()

    /** The folder of the session that is being recorded: the newest one, if it was not ended by a Stop. */
    fun openSession(): File? =
        latestSession(sessionsDir)?.takeIf { SessionFolders.readMeta(it)?.isNull("endUtc") == true }

    /** Reads the open session's log again (after the service started or stopped, or the app was restarted). */
    @Synchronized
    fun refresh() {
        _currentAction.value = openSession()?.let { currentAction(readAll(it)) }
    }

    fun readAll(session: File): List<Annotation> =
        File(session, ANNOTATIONS_FILE).takeIf { it.isFile }?.let { foldAnnotations(it.readLines()) }.orEmpty()

    /**
     * Appends one record to the open session: [name] null = "no action". [pressedMs] is the time of the button press.
     * Returns the record, or null if no session is being recorded (nothing is written then).
     */
    @Synchronized
    fun record(kind: AnnotationKind, name: String?, pressedMs: Long, note: String = ""): Annotation? {
        val dir = openSession() ?: return null
        val a = Annotation(newAnnotationId(), kind, name, pressedMs, timeMs = pressedMs, note = note.trim(), recordedMs = System.currentTimeMillis())
        append(dir, a)
        if (name != null) types.markUsed(kind, name, pressedMs)
        _currentAction.value = currentAction(readAll(dir))
        return a
    }

    /** Appends a correction of [original] (same id): a new time, name or note. The press time and the earlier lines stay. */
    @Synchronized
    fun correct(original: Annotation, timeMs: Long = original.timeMs, name: String? = original.name, note: String = original.note): Annotation? {
        val dir = openSession() ?: return null
        val a = original.copy(name = name, timeMs = timeMs, note = note.trim(), recordedMs = System.currentTimeMillis())
        append(dir, a)
        _currentAction.value = currentAction(readAll(dir))
        return a
    }

    private fun append(dir: File, a: Annotation) {
        // Append mode + sync: the line is on disk before we say "recorded"; a kill can at worst leave one damaged last line.
        FileOutputStream(File(dir, ANNOTATIONS_FILE), true).use { out ->
            out.write((a.toJsonLine() + "\n").toByteArray())
            out.fd.sync()
        }
    }
}
