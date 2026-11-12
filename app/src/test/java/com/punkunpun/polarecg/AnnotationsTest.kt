package com.punkunpun.polarecg

import com.punkunpun.polarecg.annotations.Annotation
import com.punkunpun.polarecg.annotations.AnnotationKind
import com.punkunpun.polarecg.annotations.AnnotationStore
import com.punkunpun.polarecg.annotations.AnnotationTypes
import com.punkunpun.polarecg.annotations.currentAction
import com.punkunpun.polarecg.annotations.foldAnnotations
import com.punkunpun.polarecg.annotations.parseAnnotationLine
import com.punkunpun.polarecg.recorder.SessionFolders
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
    fun thePcToolReadsWhatThePhoneWrites() {
        // Two implementations of one format (app: org.json, :core-dsp: a small reader). They must agree, also on awkward text.
        val tricky = Annotation("id1", AnnotationKind.EVENT, "Dizzy \"a bit\" / caf\u00e9", t0, t0 + 5, note = "back\\slash, comma\nline \u00e9 tab\t")
        val none = action("id2", null, t0 + 10)
        val marks = com.punkunpun.polarecg.dsp.annotations.parseMarks(listOf(tricky, none).map { it.toJsonLine() })
        assertEquals(tricky.name, marks[0].name)
        assertEquals(tricky.note, marks[0].note)
        assertEquals(tricky.timeMs, marks[0].timeMs)
        assertEquals(tricky.pressedMs, marks[0].pressedMs)
        assertFalse(marks[0].isAction)
        assertNull(marks[1].name)
        assertTrue(marks[1].isAction)
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

    @Test
    fun aMarkOfAFinishedSessionCanBeCorrectedAndDeleted() {
        val root = tempDir()
        val opened = newSession(root)
        val store = AnnotationStore(root, AnnotationTypes(File(root, "types.json")))
        val walk = store.record(AnnotationKind.ACTION, "Walking", t0 + 1000)!!
        val sit = store.record(AnnotationKind.ACTION, "Sitting", t0 + 5000)!!
        // the session is stopped: nothing is open any more, but the folder can still be corrected
        val meta = SessionFolders.readMeta(opened.dir)!!.put("endUtc", SessionFolders.iso(t0 + 9000))
        SessionFolders.writeMeta(opened.dir, meta)
        assertNull(store.record(AnnotationKind.EVENT, "Coffee", t0 + 6000))
        assertNotNull(store.correct(walk, timeMs = t0 + 2000, session = opened.dir))
        assertEquals(t0 + 2000, store.readAll(opened.dir).first { it.id == walk.id }.timeMs)
        assertEquals(t0 + 1000, store.readAll(opened.dir).first { it.id == walk.id }.pressedMs) // the press time stays
        assertTrue(store.delete(sit, opened.dir))
        assertEquals(listOf(walk.id), store.readAll(opened.dir).map { it.id }) // no longer shown
        assertEquals(4, File(opened.dir, "annotations.jsonl").readLines().size)  // but nothing was removed from the file
    }

    @Test
    fun aNameKeepsItsIconAndAPresetKeepsItsDefault() {
        val f = File(tempDir(), "types.json")
        val types = AnnotationTypes(f)
        assertEquals("🚶", types.iconOf(AnnotationKind.ACTION, "Walking"))
        types.add(AnnotationKind.ACTION, "Gardening", "🌱")
        assertEquals("🌱", types.iconOf(AnnotationKind.ACTION, "Gardening"))
        types.add(AnnotationKind.ACTION, "Cooking")
        assertEquals("🔹", types.iconOf(AnnotationKind.ACTION, "Cooking"))
        types.setIcon(AnnotationKind.ACTION, "Cooking", "🍳")
        types.setIcon(AnnotationKind.ACTION, "Walking", "🐕")
        val again = AnnotationTypes(f) // read back from the file
        assertEquals("🍳", again.iconOf(AnnotationKind.ACTION, "Cooking"))
        assertEquals("🐕", again.iconOf(AnnotationKind.ACTION, "Walking"))
        again.setIcon(AnnotationKind.ACTION, "Walking", null)
        assertEquals("🚶", again.iconOf(AnnotationKind.ACTION, "Walking"))
    }
}
