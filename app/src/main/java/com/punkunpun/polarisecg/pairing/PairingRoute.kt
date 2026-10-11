package com.punkunpun.polarisecg.pairing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.punkunpun.polarisecg.PolarisApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

private const val SCAN_DURATION_MS = 20_000L

/**
 * Holds the state and logic for pairing (scan, selection) and feeds the stateless [PairingScreen].
 * Needs BLUETOOTH_SCAN permission to have been granted before it is shown.
 */
@Composable
fun PairingRoute(app: PolarisApp, modifier: Modifier = Modifier) {
    var devices by remember { mutableStateOf(emptyList<FoundDevice>()) }
    var scanRequested by remember { mutableStateOf(true) } // start scanning on first show
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf(app.deviceStore.deviceId) }

    // Re-runs whenever scanRequested flips to true. If the screen leaves composition the
    // coroutine is cancelled automatically, which stops the BLE scan.
    LaunchedEffect(scanRequested) {
        if (!scanRequested) return@LaunchedEffect
        scanning = true
        error = null
        try {
            withTimeoutOrNull(SCAN_DURATION_MS) {
                H10Scanner(app.polarApi).scan().collect { devices = it }
            }
        } catch (e: CancellationException) {
            throw e // never swallow cancellation, or the coroutine would not really stop
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            scanning = false
            scanRequested = false
        }
    }

    PairingScreen(
        devices = devices,
        scanning = scanning,
        errorMessage = error,
        selectedDeviceId = selectedId,
        onRescan = { scanRequested = true },
        onSelect = {
            app.deviceStore.deviceId = it.deviceId
            selectedId = it.deviceId
        },
        connectionPanel = { ConnectionTestPanel(app, selectedId) },
        modifier = modifier,
    )
}
