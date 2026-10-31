package com.punkunpun.polarisecg.cli

import com.punkunpun.polarisecg.dsp.annotations.parseOwnerLabels
import com.punkunpun.polarisecg.dsp.annotations.renderDayReport
import com.punkunpun.polarisecg.dsp.raw.RawRecord
import com.punkunpun.polarisecg.dsp.raw.readStream
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """PolarisECG CLI

  analyze <recording-folder> [--out <dir>] [--labels <dir>]

    <recording-folder>  a session folder (data/sessions/<start>) or a converted reference
                        record (data/reference/<db>/<record>): any folder with ecg*.bin files
    --out <dir>         where to write beats.csv, windows.csv, events.csv, ... (+ activities.csv and
                        action_summary.csv if the folder has annotations.jsonl)
                        (default: data/analysis/<parent>-<folder>)
    --labels <dir>      the owner's labels of this session (data/labels/<session>): also writes report_labelled.md,
                        the day report with the owner's class for every labelled beat (report.md stays automatic)

  live-check <recording-folder>

    Feeds the recording to the live pipeline (the phone's code) in arrival order and compares the
    beats with `analyze`. Prints both class counts and the beats that differ.
"""

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "analyze" -> exitProcess(analyzeCommand(args.drop(1)))
        "live-check" -> exitProcess(args.getOrNull(1)?.let { liveCheckCommand(File(it)) } ?: usageError("Missing <recording-folder>"))
        null, "-h", "--help", "help" -> print(USAGE)
        else -> {
            System.err.println("Unknown command '${args.first()}'.\n")
            System.err.print(USAGE)
            exitProcess(2)
        }
    }
}

private fun analyzeCommand(args: List<String>): Int {
    var folder: File? = null
    var out: File? = null
    var labelsDir: File? = null
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--out" -> out = args.getOrNull(++i)?.let(::File) ?: return usageError("--out needs a folder")
            "--labels" -> labelsDir = args.getOrNull(++i)?.let(::File) ?: return usageError("--labels needs a folder")
            else -> {
                if (a.startsWith("--") || folder != null) return usageError("Unexpected argument '$a'")
                folder = File(a)
            }
        }
        i++
    }
    if (folder == null) return usageError("Missing <recording-folder>")
    if (!folder.isDirectory) return usageError("Not a folder: $folder")

    val outDir = out ?: File("data/analysis").resolve(
        listOfNotNull(folder.absoluteFile.parentFile?.name, folder.absoluteFile.name).joinToString("-"),
    )

    val data = readStream(folder, "ecg")
    for (f in data.unreadable) System.err.println("Skipped unreadable file: ${f.name}")
    val frames = data.records.filterIsInstance<RawRecord.Ecg>()
    if (frames.isEmpty()) {
        System.err.println("No ECG records found in $folder")
        return 1
    }

    val acc = readStream(folder, "acc").records.filterIsInstance<RawRecord.Acc>()
    if (acc.isEmpty()) System.err.println("No ACC records found: motion is not used for the quality")

    val result = runAnalysis(frames, acc)
    outDir.mkdirs()
    val marks = readMarks(folder)
    val spans = spansFor(result, marks)
    writeCsvs(result, outDir, spans)
    writeAnnotationCsvs(result, marks, spans, outDir)
    File(outDir, "report.md").writeText(renderDayReport(result, marks, spans, folder.name))
    labelsDir?.let { dir ->
        val file = File(dir, "labels.csv")
        if (!file.isFile) return usageError("No labels.csv in $dir")
        val labels = parseOwnerLabels(file.readLines())
        File(outDir, "report_labelled.md").writeText(renderDayReport(result, marks, spans, folder.name + " (with the owner's labels)", labels = labels))
        println("report_labelled.md: ${labels.size} beats take the owner's class")
    }

    val hours = result.segments.sumOf { it.durationMs } / 3_600_000.0
    println(
        "%.2f h of ECG in %d segment(s), %d beats, %d windows, %d events -> %s".format(
            hours, result.segments.size, result.beats.size, result.windows.size, result.events.size, outDir,
        ),
    )
    printActionSummary(result, marks, spans)
    return 0
}

private fun usageError(message: String): Int {
    System.err.println("$message\n")
    System.err.print(USAGE)
    return 2
}
