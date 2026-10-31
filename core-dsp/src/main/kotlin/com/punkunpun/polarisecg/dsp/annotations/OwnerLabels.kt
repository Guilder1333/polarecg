package com.punkunpun.polarisecg.dsp.annotations

/*
 * The owner's beat labels (`data/labels/<session>/labels.csv`, written by analysis/label_beats.py; format in
 * docs/LABELING_TOOL.md): append-only, the LAST row per item wins, an empty label means "undone".
 */

/** The labels a beat can get that change its class: N, S, V, Q. ("?" means unsure and leaves the automatic class alone.) */
private val CLASS_LABELS = setOf("N", "S", "V", "Q")

/** Splits one CSV line into fields (RFC 4180 quotes: `"a,b"`, `""` for a quote). Returns null for a line cut off inside quotes. */
internal fun splitCsvLine(line: String): List<String>? {
    val fields = ArrayList<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            inQuotes && c == '"' && line.getOrNull(i + 1) == '"' -> { sb.append('"'); i++ }
            c == '"' -> inQuotes = !inQuotes
            c == ',' && !inQuotes -> { fields += sb.toString(); sb.clear() }
            else -> sb.append(c)
        }
        i++
    }
    if (inQuotes) return null
    fields += sb.toString()
    return fields
}

/**
 * Current class label of every beat the owner labelled: beat time (`utcMs`) -> "N" | "S" | "V" | "Q".
 * Gap items, unsure ("?"), undone labels and damaged lines are left out. [lines] include the header line.
 */
fun parseOwnerLabels(lines: List<String>): Map<Long, String> {
    val header = lines.firstOrNull()?.let(::splitCsvLine) ?: return emptyMap()
    val iUtc = header.indexOf("utcMs")
    val iKind = header.indexOf("kind")
    val iLabel = header.indexOf("label")
    if (minOf(iUtc, iKind, iLabel) < 0) return emptyMap()
    val last = LinkedHashMap<Long, String>() // beat time -> newest label ("" = undone)
    for (line in lines.drop(1)) {
        val f = splitCsvLine(line) ?: continue
        if (f.size <= maxOf(iUtc, iKind, iLabel) || f[iKind] != "BEAT") continue
        val utc = f[iUtc].toLongOrNull() ?: continue
        last[utc] = f[iLabel]
    }
    return last.filterValues { it in CLASS_LABELS }
}
