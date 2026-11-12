package com.punkunpun.polarecg.annotations

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarecg.PolarApp
import com.punkunpun.polarecg.R
import com.punkunpun.polarecg.log.formatJst
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Time moves of the edit dialog, in seconds. */
private val TIME_STEPS_S = listOf(-300, -60, -10, 10, 60, 300)

private fun stepLabel(s: Int): String {
    val sign = if (s < 0) "−" else "+"
    val a = kotlin.math.abs(s)
    return sign + if (a >= 60) "${a / 60} min" else "$a s"
}

/** A few emoji to pick from when a name gets its icon; any other emoji can be typed in the field. */
private val SUGGESTED_ICONS = listOf("🧘", "🧹", "🍳", "🚗", "🚌", "🚴", "🏊", "💼", "🖥️", "📱", "🛒", "🚿", "🍺", "🎮", "🎧", "🐕", "🔹", "📍")

/** A field for one emoji, with a row of suggestions under it. [onChange] gets the first symbol typed, or "" when the field is empty. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPicker(value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(firstSymbol(it).orEmpty()) },
            label = { Text(stringResource(R.string.ann_icon)) },
            singleLine = true,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in SUGGESTED_ICONS) TextButton(onClick = { onChange(i) }) { Text(i, style = MaterialTheme.typography.titleLarge) }
        }
    }
}

/**
 * Corrects or deletes one action or event of the session folder [session] (the dialog of a tap on the marks rows of the chart).
 * Nothing is overwritten: the change is a new line with the same id (docs/RAW_FORMAT.md). A deleted mark gets a line with
 * `deleted:true` and is simply no longer shown; an action that was ended by it now runs on until the next one.
 *
 * The icon belongs to the name (all marks of that name on this phone), not to the single mark.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditMarkDialog(app: PolarApp, session: File, id: String, onDone: (changed: Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = app.annotations
    val original = remember(session, id) { store.readAll(session).firstOrNull { it.id == id } }
    if (original == null) { onDone(false); return }
    val types = store.types
    var name by remember { mutableStateOf(original.name) }
    var timeMs by remember { mutableStateOf(original.timeMs) }
    var note by remember { mutableStateOf(original.note) }
    var icon by remember(name) { mutableStateOf(name?.let { store.types.icon(original.kind, it) ?: markIcon(original.kind, it) }.orEmpty()) }
    val names = remember { (types.names(original.kind) + listOfNotNull(original.name)).distinct() }
    val isAction = original.kind == AnnotationKind.ACTION

    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text(stringResource(if (isAction) R.string.ann_edit_action else R.string.ann_edit_event)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in names) {
                        FilterChip(selected = n == name, onClick = { name = n }, label = { Text(types.iconOf(original.kind, n) + " " + n) })
                    }
                }
                Text(formatJst(timeMs).substring(11, 19), style = MaterialTheme.typography.headlineSmall)
                if (timeMs != original.timeMs) {
                    Text(stringResource(R.string.ann_was, formatJst(original.timeMs).substring(11, 19)), style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in TIME_STEPS_S) OutlinedButton(onClick = { timeMs += s * 1000L }) { Text(stepLabel(s)) }
                }
                if (!isAction) OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(stringResource(R.string.ann_note)) }, singleLine = true)
                if (name != null) IconPicker(icon, onChange = { icon = it })
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            name?.let { n -> types.setIcon(original.kind, n, icon.ifEmpty { null }) }
                            store.correct(original, timeMs = timeMs, name = name, note = note, session = session) != null
                        }
                        if (!ok) Toast.makeText(context, R.string.ann_not_saved, Toast.LENGTH_LONG).show()
                        onDone(ok)
                    }
                }) { Text(stringResource(R.string.ann_save)) }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.delete(original, session) }
                        onDone(true)
                    }
                }) { Text(stringResource(R.string.ann_delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { onDone(false) }) { Text(stringResource(R.string.ann_cancel)) }
            }
        },
    )
}
