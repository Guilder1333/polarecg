package com.punkunpun.polarecg.pairing

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.punkunpun.polarecg.PolarApp
import com.punkunpun.polarecg.annotations.AnnotationBar
import com.punkunpun.polarecg.annotations.AnnotationKind

/**
 * The main screen: shows the H10 in use (chosen on the connection screen, read again each time this screen is shown), the
 * recording panel and the annotation buttons. See [ConnectionRoute] for scanning and choosing the strap.
 */
@Composable
fun PairingRoute(app: PolarApp, onOpenConnection: () -> Unit, onOpenDiagnostics: () -> Unit, onOpenLive: () -> Unit, onOpenSummary: () -> Unit, onOpenEvents: () -> Unit, onOpenBrowse: () -> Unit, onOpenReplay: () -> Unit, onAnnotate: (AnnotationKind, Long) -> Unit, modifier: Modifier = Modifier) {
    PairingScreen(
        selectedDeviceId = app.deviceStore.deviceId,
        onOpenConnection = onOpenConnection,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenLive = onOpenLive,
        onOpenSummary = onOpenSummary,
        onOpenEvents = onOpenEvents,
        onOpenBrowse = onOpenBrowse,
        onOpenReplay = onOpenReplay,
        annotationPanel = {
            // recording (start/stop, state, raw switch, export) and the action/event buttons
            val deviceId = app.deviceStore.deviceId
            if (deviceId != null) ConnectionTestPanel(app, deviceId)
            AnnotationBar(app, onAnnotate)
        },
        modifier = modifier,
    )
}
