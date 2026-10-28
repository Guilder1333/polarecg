package com.punkunpun.polarisecg

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.punkunpun.polarisecg.diagnostics.DiagnosticsScreen
import com.punkunpun.polarisecg.live.LiveScreen
import com.punkunpun.polarisecg.replay.ReplayScreen
import com.punkunpun.polarisecg.pairing.PairingRoute
import com.punkunpun.polarisecg.permissions.AppPermissions
import com.punkunpun.polarisecg.permissions.PermissionsScreen
import com.punkunpun.polarisecg.ui.theme.PolarisECGTheme

class MainActivity : ComponentActivity() {

    // Compose re-draws when a "state" value changes. Bumping this counter in onResume() lets the
    // screen re-check permissions after the user returns from a system dialog or settings page.
    // `by` delegates the property to the state object, so we read/write it like a plain Int.
    private var resumeCount by mutableIntStateOf(0)

    // True while the app is the small floating window (Picture-in-Picture): the screen then shows
    // only the live trace. Set by the system callback onPictureInPictureModeChanged below.
    private var inPip by mutableStateOf(false)

    /**
     * Allowed to turn itself into a floating window when the user swipes up to Home. Only offered while
     * the live screen is visible: elsewhere a floating window would show nothing useful.
     */
    private fun setPipAllowed(allowed: Boolean) {
        setPictureInPictureParams(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setAutoEnterEnabled(allowed)
                .setSeamlessResizeEnabled(false) // the trace is redrawn, not scaled: no blurry cross-fade
                .build(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PolarisECGTheme {
                var bluetoothGranted by remember { mutableStateOf(AppPermissions.hasBluetooth(this)) }
                var notificationsGranted by remember {
                    mutableStateOf(AppPermissions.hasNotifications(this))
                }
                var batteryGranted by remember {
                    mutableStateOf(AppPermissions.isIgnoringBatteryOptimizations(this))
                }

                // Launchers must be created during composition; they hand the result to the lambda.
                val bluetoothLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { bluetoothGranted = AppPermissions.hasBluetooth(this) }
                val notificationsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { notificationsGranted = it }
                val batteryLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { batteryGranted = AppPermissions.isIgnoringBatteryOptimizations(this) }

                // Runs again whenever resumeCount changes, i.e. each time the app returns to the foreground.
                LaunchedEffect(resumeCount) {
                    bluetoothGranted = AppPermissions.hasBluetooth(this@MainActivity)
                    notificationsGranted = AppPermissions.hasNotifications(this@MainActivity)
                    batteryGranted = AppPermissions.isIgnoringBatteryOptimizations(this@MainActivity)
                }

                // rememberSaveable survives screen rotation, plain remember does not.
                var permissionsAcknowledged by rememberSaveable { mutableStateOf(false) }
                var showDiagnostics by rememberSaveable { mutableStateOf(false) }
                var showLive by rememberSaveable { mutableStateOf(false) }
                var showReplay by rememberSaveable { mutableStateOf(false) }
                val allGranted = bluetoothGranted && notificationsGranted && batteryGranted
                val showPairing = bluetoothGranted && (allGranted || permissionsAcknowledged)

                // The live screen is the only one that may float. Re-evaluated when it opens or closes.
                val liveVisible = showPairing && showLive
                LaunchedEffect(liveVisible) { setPipAllowed(liveVisible) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (liveVisible) {
                        LiveScreen(
                            app = application as PolarisApp,
                            onBack = { showLive = false },
                            // The floating window is small: no system bars padding there.
                            modifier = if (inPip) Modifier else Modifier.padding(innerPadding),
                            pip = inPip,
                            onEnterPip = {
                                setPipAllowed(true)
                                enterPictureInPictureMode(
                                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
                                )
                            },
                        )
                    } else if (showPairing && showReplay) {
                        ReplayScreen(
                            app = application as PolarisApp,
                            onBack = { showReplay = false },
                            onOpenLive = { showLive = true },
                            modifier = Modifier.padding(innerPadding),
                        )
                    } else if (showPairing && showDiagnostics) {
                        DiagnosticsScreen(
                            log = (application as PolarisApp).connectionLog,
                            onBack = { showDiagnostics = false },
                            modifier = Modifier.padding(innerPadding),
                        )
                    } else if (showPairing) {
                        PairingRoute(
                            app = application as PolarisApp,
                            onOpenDiagnostics = { showDiagnostics = true },
                            onOpenLive = { showLive = true },
                            onOpenReplay = { showReplay = true },
                            modifier = Modifier.padding(innerPadding),
                        )
                    } else PermissionsScreen(
                        bluetoothGranted = bluetoothGranted,
                        notificationsGranted = notificationsGranted,
                        batteryGranted = batteryGranted,
                        onRequestBluetooth = { bluetoothLauncher.launch(AppPermissions.bluetooth) },
                        onRequestNotifications = {
                            notificationsLauncher.launch(AppPermissions.NOTIFICATIONS)
                        },
                        onRequestBattery = {
                            batteryLauncher.launch(AppPermissions.batteryOptimizationIntent(this))
                        },
                        onContinue = { permissionsAcknowledged = true },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }
}
