package com.punkunpun.polarisecg

import com.punkunpun.polarisecg.annotations.Annotation
import com.punkunpun.polarisecg.annotations.AnnotationKind
import com.punkunpun.polarisecg.annotations.AnnotationStore
import com.punkunpun.polarisecg.annotations.AnnotationTypes
import com.punkunpun.polarisecg.annotations.currentAction
import com.punkunpun.polarisecg.annotations.foldAnnotations
import com.punkunpun.polarisecg.annotations.parseAnnotationLine
import com.punkunpun.polarisecg.recorder.SessionFolders
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationsTest {
    private val t0 = 1_793_300_000_000L

    private fun action(id: String, name: String?, at: Long) = Annotation(id, AnnotationKind.ACTION, name, at, at)

    private fun tempDir(): File = Files.createTempDirectory("ann").toFile().also { it.deleteOnExit() }

    private fun newSession(root: File, startMs: Long = t0) =
        SessionFolders.open(root, "DEV", startMs) { s ->
            JSONObject().put("formatVersion", 1).put("startUtc", SessionFolders.iso(s)).put("endUtc", JSONObject.NULL)
        }

    @Test
    fun aLineSurvivesTheRoundTripIncludingQuotesAndNull() {
        val a = Annotation("id1", AnnotationKind.EVENT, "Dizzy \"a bit\"", t0, t0 + 5, note = "stood up, line\nbreak")
        assertEquals(a, parseAnnotationLine(a.toJsonLine()))
        val none = action("id2", null, t0)
        assertNull(parseAnnotationLine(none.toJsonLine())!!.name)
    }

    @Test
    fun aDamagedLineIsSkipped() {
        val good = action("a", "Walking", t0).toJsonLine()
        val folded = foldAnnotations(listOf(good, "{\"id\":\"b\",\"kind\":\"act")) // cut off by a kill
        assertEquals(1, folded.size)
    }

    @Test
    fun theNewestLineOfAnIdWinsAndTheResultIsSortedByTime() {
        val first = action("a", "Walking", t0 + 1000)
        val corrected = first.copy(timeMs = t0 - 600_000, recordedMs = t0 + 2000) // "it was 10 minutes earlier"
        val other = action("b", "Sitting", t0)
        val folded = foldAnnotations(listOf(first, other, corrected).map { it.toJsonLine() })
        assertEquals(listOf("a", "b"), folded.map { it.id })
        assertEquals(t0 - 600_000, folded[0].timeMs)
        assertEquals(t0 + 1000, folded[0].pressedMs) // the press time is kept
    }

    @Test
    fun theCurrentActionIsTheLastOneStartedAndNoActionEndsIt() {
        val walking = action("a", "Walking", t0)
        val running = action("b", "Running", t0 + 1000)
        val none = action("c", null, t0 + 2000)
        val event = Annotation("d", AnnotationKind.EVENT, "Dizzy", t0 + 3000, t0 + 3000)
        assertEquals("Running", currentAction(listOf(walking, running, event))!!.name)
        assertNull(currentAction(listOf(walking, running, none, event)))
        assertNull(currentAction(emptyList()))
    }

    @Test
    fun storeWritesIntoTheOpenSessionOnly() {
        val root = tempDir()
        val store = AnnotationStore(root, AnnotationTypes(File(tempDir(), "types.json")))
        assertNull(store.record(AnnotationKind.ACTION, "Walking", t0)) // no session at all

        val session = newSession(root)
        val dir = session.dir
        val a = store.record(AnnotationKind.ACTION, "Walking", t0 + 1000)
        assertNotNull(a)
        store.record(AnnotationKind.EVENT, "Dizzy", t0 + 2000, note = "  after coffee ")
        assertEquals(2, store.readAll(dir).size)
        assertEquals("Walking", store.currentAction.value!!.name)
        assertEquals("after coffee", store.readAll(dir)[1].note)

        store.correct(a!!, timeMs = t0 - 5000)
        val all = store.readAll(dir)
        assertEquals(2, all.size) // a correction replaces, it does not add
        assertEquals(t0 - 5000, all[0].timeMs)
        assertEquals(3, File(dir, "annotations.jsonl").readLines().size) // but the history is still in the file

        SessionFolders.end(session, t0 + 10_000)
        assertNull(store.record(AnnotationKind.EVENT, "Stress", t0 + 20_000)) // stopped: no open session
    }

    @Test
    fun typesAreHiddenNotDeletedAndRecentOnesComeFirst() {
        val file = File(tempDir(), "types.json")
        val types = AnnotationTypes(file)
        assertTrue("Walking" in types.names(AnnotationKind.ACTION))
        types.markUsed(AnnotationKind.ACTION, "Running", t0)
        assertEquals("Running", types.names(AnnotationKind.ACTION).first())

        types.setHidden(AnnotationKind.ACTION, "Running", true)
        assertFalse("Running" in types.names(AnnotationKind.ACTION))
        assertTrue("Running" in types.names(AnnotationKind.ACTION, includeHidden = true))

        assertEquals("Cycling", types.add(AnnotationKind.ACTION, "  Cycling "))
        assertEquals("Running", types.add(AnnotationKind.ACTION, "running")) // same name again: shown again, no copy
        assertNull(types.add(AnnotationKind.EVENT, "   "))

        val reloaded = AnnotationTypes(file) // survives a restart
        assertTrue("Cycling" in reloaded.names(AnnotationKind.ACTION))
        assertEquals(1, reloaded.names(AnnotationKind.ACTION).count { it == "Running" })
    }
}
