package com.punkunpun.polarecg.annotations

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.punkunpun.polarecg.service.AcquisitionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "14:05" in JST: the part of `formatJst`'s "2026-10-29 14:05:33" that matters when glancing at the screen. */
private fun clock(ms: Long) = formatJst(ms).substring(11, 16)

/**
 * The "Action" and "Event" buttons (live and main screen) and, under them, the action that is running now.
 * Pressing a button takes the time right then and hands it to [onPress]; the picker opens afterwards, so a slow choice
 * does not move the time.
 */
@Composable
fun AnnotationBar(app: PolarApp, onPress: (AnnotationKind, Long) -> Unit, modifier: Modifier = Modifier) {
    val running by AcquisitionService.running.collectAsState()
    val current by app.annotations.currentAction.collectAsState()
    // Marks go into the running session's folder, so there must be one: recording on, and started.
    val enabled = running && app.deviceStore.rawRecording
    LaunchedEffect(running) { withContext(Dispatchers.IO) { app.annotations.refresh() } }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled, onClick = { onPress(AnnotationKind.ACTION, System.currentTimeMillis()) }) {
                Text(stringResource(R.string.ann_action))
            }
            OutlinedButton(enabled = enabled, onClick = { onPress(AnnotationKind.EVENT, System.currentTimeMillis()) }) {
                Text(stringResource(R.string.ann_event))
            }
        }
        if (!enabled) {
            Text(stringResource(R.string.ann_needs_recording), style = MaterialTheme.typography.bodySmall)
        }
        current?.let { Text(stringResource(R.string.ann_now, app.annotations.types.iconOf(AnnotationKind.ACTION, it.name) + " " + it.name.orEmpty(), clock(it.timeMs))) }
    }
}

/**
 * Choose what happened at [pressedMs]. An action: tap a name (or "No action" to end the running one). An event: tap a
 * name, with an optional note typed first. "New" adds a name; "Hide names" turns the next taps into hiding.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun AnnotationScreen(
    app: PolarApp,
    kind: AnnotationKind,
    pressedMs: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDone) // backing out records nothing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val types = app.annotations.types
    var names by remember { mutableStateOf(types.names(kind)) }
    var hiding by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var iconFor by remember { mutableStateOf<String?>(null) } // the name whose icon is being changed (long press)
    var changes by remember { mutableIntStateOf(0) } // bumped to re-read the names after hiding/adding

    LaunchedEffect(changes) { names = types.names(kind) }

    fun save(name: String?) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { app.annotations.record(kind, name, pressedMs, note) }
            if (saved == null) Toast.makeText(context, R.string.ann_not_saved, Toast.LENGTH_LONG).show()
            onDone()
        }
    }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(if (kind == AnnotationKind.ACTION) R.string.ann_title_action else R.string.ann_title_event, clock(pressedMs)),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (kind == AnnotationKind.EVENT) {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.ann_note)) },
                singleLine = true,
            )
        }
        if (hiding) Text(stringResource(R.string.ann_hiding_hint), color = MaterialTheme.colorScheme.error)
        else Text(stringResource(R.string.ann_longpress_hint), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (name in names) {
                // A Button has no long press, so the same look is made from a Surface: tap = choose, long press = change the icon.
                Surface(
                    shape = ButtonDefaults.shape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            if (hiding) {
                                types.setHidden(kind, name, true)
                                changes++
                            } else {
                                save(name)
                            }
                        },
                        onLongClick = { iconFor = name },
                    ),
                ) {
                    Text(types.iconOf(kind, name) + " " + name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                }
            }
            if (kind == AnnotationKind.ACTION && !hiding) {
                OutlinedButton(onClick = { save(null) }) { Text(stringResource(R.string.ann_no_action)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.ann_new)) }
            OutlinedButton(onClick = { hiding = !hiding }) {
                Text(stringResource(if (hiding) R.string.ann_hide_done else R.string.ann_hide))
            }
            OutlinedButton(onClick = onDone) { Text(stringResource(R.string.ann_cancel)) }
        }
    }

    iconFor?.let { n ->
        var icon by remember(n) { mutableStateOf(types.iconOf(kind, n)) }
        AlertDialog(
            onDismissRequest = { iconFor = null },
            title = { Text(n) },
            text = { IconPicker(icon, onChange = { icon = it }) },
            confirmButton = {
                TextButton(onClick = { types.setIcon(kind, n, icon.ifEmpty { null }); iconFor = null; changes++ }) { Text(stringResource(R.string.ann_save)) }
            },
            dismissButton = { TextButton(onClick = { iconFor = null }) { Text(stringResource(R.string.ann_cancel)) } },
        )
    }

    if (adding) {
        var text by remember { mutableStateOf("") }
        var icon by remember { mutableStateOf(markIcon(kind, null)) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.ann_new)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                    IconPicker(icon, onChange = { icon = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val added = types.add(kind, text, icon.ifEmpty { null })
                    adding = false
                    // A new name is a deliberate choice for this moment: record it straight away.
                    if (added != null) save(added)
                }) { Text(stringResource(R.string.ann_add)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.ann_cancel)) } },
        )
    }
}
