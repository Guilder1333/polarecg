package com.punkunpun.polarisecg.annotations

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val PRESET_ACTIONS = listOf("Sitting", "Standing", "Lying down", "Walking", "Running", "Workout", "Eating", "Sleeping")
private val PRESET_EVENTS = listOf("Dizzy", "Stress", "Palpitations", "Chest discomfort", "Coffee", "Felt tired")

/**
 * The names the picker offers (not the records: those carry their own name, so a session folder is complete by itself).
 * Lives in one small file in the app's storage, shared by all sessions. Types are hidden, never deleted, so the list
 * can always show what was used; the most recently used come first.
 */
class AnnotationTypes(private val file: File) {
    private class Entry(val kind: AnnotationKind, val name: String, var hidden: Boolean, var lastUsedMs: Long)

    private val entries: MutableList<Entry> = load()

    private fun load(): MutableList<Entry> {
        val loaded = try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val kind = AnnotationKind.fromKey(o.getString("kind")) ?: return@mapNotNull null
                Entry(kind, o.getString("name"), o.optBoolean("hidden", false), o.optLong("lastUsedMs", 0))
            }.toMutableList()
        } catch (e: Exception) {
            null // no file yet, or damaged: start from the presets
        }
        return loaded ?: (PRESET_ACTIONS.map { Entry(AnnotationKind.ACTION, it, false, 0) } +
            PRESET_EVENTS.map { Entry(AnnotationKind.EVENT, it, false, 0) }).toMutableList()
    }

    /** Names to offer for [kind]: most recently used first; never used ones keep their preset order. */
    @Synchronized
    fun names(kind: AnnotationKind, includeHidden: Boolean = false): List<String> =
        entries.filter { it.kind == kind && (includeHidden || !it.hidden) }
            .sortedByDescending { it.lastUsedMs } // stable: ties keep their order
            .map { it.name }

    @Synchronized
    fun isHidden(kind: AnnotationKind, name: String): Boolean = find(kind, name)?.hidden == true

    /** Adds a type (or shows a hidden one again). Returns the cleaned name, or null if it was blank. */
    @Synchronized
    fun add(kind: AnnotationKind, rawName: String): String? {
        val name = rawName.trim().replace(Regex("\\s+"), " ").take(40)
        if (name.isEmpty()) return null
        val existing = entries.firstOrNull { it.kind == kind && it.name.equals(name, ignoreCase = true) }
        if (existing != null) existing.hidden = false else entries += Entry(kind, name, false, 0)
        save()
        return existing?.name ?: name
    }

    @Synchronized
    fun setHidden(kind: AnnotationKind, name: String, hidden: Boolean) {
        find(kind, name)?.let { it.hidden = hidden; save() }
    }

    @Synchronized
    fun markUsed(kind: AnnotationKind, name: String, atMs: Long) {
        find(kind, name)?.let { it.lastUsedMs = atMs; save() }
    }

    private fun find(kind: AnnotationKind, name: String) = entries.firstOrNull { it.kind == kind && it.name == name }

    private fun save() {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().put("kind", e.kind.key).put("name", e.name).put("hidden", e.hidden).put("lastUsedMs", e.lastUsedMs))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
