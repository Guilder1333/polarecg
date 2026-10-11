package com.punkunpun.polarisecg

import android.os.Bundle
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
import com.punkunpun.polarisecg.pairing.PairingRoute
import com.punkunpun.polarisecg.permissions.AppPermissions
import com.punkunpun.polarisecg.permissions.PermissionsScreen
import com.punkunpun.polarisecg.ui.theme.PolarisECGTheme

class MainActivity : ComponentActivity() {

    // Compose re-draws when a "state" value changes. Bumping this counter in onResume() lets the
    // screen re-check permissions after the user returns from a system dialog or settings page.
    // `by` delegates the property to the state object, so we read/write it like a plain Int.
    private var resumeCount by mutableIntStateOf(0)

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
                val allGranted = bluetoothGranted && notificationsGranted && batteryGranted
                val showPairing = bluetoothGranted && (allGranted || permissionsAcknowledged)

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (showPairing) {
                        PairingRoute(
                            app = application as PolarisApp,
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

    override fun onResume() {
        super.onResume()
        resumeCount++
    }
}
