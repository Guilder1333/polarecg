package com.punkunpun.polarisecg.polar

import android.util.Log
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleDisconnectInfo
import com.polar.sdk.api.model.EcgSample
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarSensorSetting
import com.polar.sdk.api.model.PolarSensorSetting.SettingType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "PolarConnection"

/** ECG: the H10 only supports 130 Hz / 14 bit. */
private const val ECG_RATE_HZ = 130
private const val ECG_RESOLUTION_BITS = 14

/** ACC: 50 Hz, 16 bit, +-8 g is plenty for detecting body movement. */
private const val ACC_RATE_HZ = 50
private const val ACC_RESOLUTION_BITS = 16
private const val ACC_RANGE_G = 8

/** How long one connect attempt may take before we give up on it and retry. */
private const val CONNECT_TIMEOUT_MS = 30_000L

/**
 * Wraps the Polar BLE SDK: connect to one H10, start ECG + ACC + HR streams, and republish them
 * as typed Kotlin Flows ([ecg], [acc], [hr]) plus a [state].
 *
 * Once [connect] is called it keeps the link up until [disconnect]: dropped links and failed
 * streams are retried with back-off (see [ReconnectBackoff]). It must be created once and shared
 * (see PolarisApp).
 */
class PolarConnection(private val api: PolarBleApi) {

    // All stream collection runs in this scope. SupervisorJob: one failing stream (say ACC) does
    // not cancel the others. Dispatchers.Default: not the UI thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // A SharedFlow broadcasts to any number of collectors (UI, recorder, DSP) and does not remember
    // old values. extraBufferCapacity gives collectors some slack before the producer suspends, so
    // a slow consumer does not make us lose frames in the common case (~512 frames is ~4 minutes).
    private val _ecg = MutableSharedFlow<EcgFrame>(extraBufferCapacity = 512)
    private val _acc = MutableSharedFlow<AccFrame>(extraBufferCapacity = 512)
    private val _hr = MutableSharedFlow<HrSample>(extraBufferCapacity = 512)
    val ecg: SharedFlow<EcgFrame> = _ecg.asSharedFlow()
    val acc: SharedFlow<AccFrame> = _acc.asSharedFlow()
    val hr: SharedFlow<HrSample> = _hr.asSharedFlow()

    @Volatile private var deviceId: String? = null
    private var streamJobs: List<Job> = emptyList()

    // True from connect() until disconnect(): "the session is active, keep trying forever".
    @Volatile private var wantConnected = false
    private val backoff = ReconnectBackoff()

    // The one running recovery action: either the reconnect loop or a stream restart. At most one
    // at a time, so replacing it always cancels the old one (see replaceRecoveryJob).
    private var recoveryJob: Job? = null

    private val callback = object : PolarBleApiCallback() {
        override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
            if (polarDeviceInfo.deviceId == deviceId) _state.value = ConnectionState.Connecting
        }

        override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
            if (polarDeviceInfo.deviceId == deviceId) _state.value = ConnectionState.Connected
        }

        // Called by the SDK when it says a feature can now be used. Streaming must wait for this.
        override fun bleSdkFeatureReady(identifier: String, feature: PolarBleSdkFeature) {
            if (identifier == deviceId && feature == PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING) {
                startStreams(identifier)
            }
        }

        override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
            onDisconnected(polarDeviceInfo, null)
        }

        // Required by the SDK interface in 8.4.0 but not needed for an H10.
        override fun disInformationReceived(identifier: String, disInfo: DisInfo) = Unit

        override fun htsNotificationReceived(
            identifier: String,
            data: PolarHealthThermometerData,
        ) = Unit

        override fun deviceDisconnected(
            polarDeviceInfo: PolarDeviceInfo,
            disconnectInfo: PolarBleDisconnectInfo,
        ) {
            onDisconnected(polarDeviceInfo, disconnectInfo.reason.name)
        }
    }

    init {
        api.setApiCallback(callback)
    }

    /**
     * Start connecting to the strap with this ID (e.g. "A1B2C3D4"). Returns immediately.
     * From now on we keep the link up: if it drops or never comes up, we retry with back-off
     * until [disconnect] is called.
     */
    fun connect(deviceId: String) {
        this.deviceId = deviceId
        wantConnected = true
        backoff.reset()
        startReconnectLoop(firstDelayMs = 0)
    }

    /** Stop streams and drop the link. Also ends the automatic reconnecting. */
    fun disconnect() {
        wantConnected = false
        replaceRecoveryJob(null)
        val id = deviceId ?: return
        stopStreams()
        try {
            api.disconnectFromDevice(id)
        } catch (e: Exception) {
            Log.w(TAG, "disconnectFromDevice failed", e)
        }
        _state.value = ConnectionState.Disconnected()
        deviceId = null
    }

    /** Release everything. The object cannot be used afterwards. */
    fun close() {
        disconnect()
        scope.cancel()
    }

    private fun onDisconnected(info: PolarDeviceInfo, reason: String?) {
        if (info.deviceId != deviceId) return
        stopStreams()
        _state.value = ConnectionState.Disconnected(reason)
        // Unless the user asked for this, the strap went out of range / lost contact: get it back.
        if (wantConnected) startReconnectLoop(firstDelayMs = backoff.next())
    }

    /**
     * Calls connectToDevice, waits up to [CONNECT_TIMEOUT_MS] for the link to come up, and if it
     * does not, waits (back-off) and tries again. Ends when connected or when [wantConnected] is
     * false. Streams are started by the SDK callback (bleSdkFeatureReady), not here.
     */
    private fun startReconnectLoop(firstDelayMs: Long) {
        replaceRecoveryJob(
            scope.launch {
                var wait = firstDelayMs
                while (isActive && wantConnected) {
                    if (wait > 0) delay(wait)
                    val id = deviceId ?: return@launch
                    _state.value = ConnectionState.Connecting
                    try {
                        api.connectToDevice(id)
                    } catch (e: Exception) {
                        Log.w(TAG, "connectToDevice failed", e)
                    }
                    // `first { }` suspends until the state flow emits a matching value.
                    val up = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                        _state.first { it is ConnectionState.Connected || it is ConnectionState.Streaming }
                    }
                    if (up != null) return@launch
                    Log.w(TAG, "connect attempt timed out, retrying")
                    wait = backoff.next()
                }
            },
        )
    }

    /**
     * One of the streams died while the link is still up (e.g. the SDK rejected a stream).
     * Restart all three after a back-off delay. If the link itself is gone, the disconnect
     * callback handles it instead, so we only act while Streaming.
     */
    private fun onStreamFailed() {
        synchronized(this) {
            if (!wantConnected || recoveryJob?.isActive == true) return
            if (_state.value != ConnectionState.Streaming) return
            recoveryJob = scope.launch {
                stopStreams()
                _state.value = ConnectionState.Connected
                delay(backoff.next())
                deviceId?.let { if (wantConnected) startStreams(it) }
            }
        }
    }

    /** Cancels the current recovery job (if any) and remembers the new one. */
    @Synchronized
    private fun replaceRecoveryJob(new: Job?) {
        recoveryJob?.cancel()
        recoveryJob = new
    }

    @Synchronized
    private fun startStreams(id: String) {
        if (streamJobs.any { it.isActive }) return // already running

        val ecgSettings = PolarSensorSetting(
            mapOf(SettingType.SAMPLE_RATE to ECG_RATE_HZ, SettingType.RESOLUTION to ECG_RESOLUTION_BITS),
        )
        val accSettings = PolarSensorSetting(
            mapOf(
                SettingType.SAMPLE_RATE to ACC_RATE_HZ,
                SettingType.RESOLUTION to ACC_RESOLUTION_BITS,
                SettingType.RANGE to ACC_RANGE_G,
            ),
        )

        streamJobs = listOf(
            launchStream("ECG") {
                api.startEcgStreaming(id, ecgSettings).collect { data ->
                    backoff.reset() // data is really flowing: the next failure starts from 2 s again
                    val phoneMs = System.currentTimeMillis()
                    val samples = data.samples.filterIsInstance<EcgSample>()
                    if (samples.isNotEmpty()) {
                        _ecg.emit(
                            EcgFrame(
                                sensorTimeNs = samples.first().timeStamp,
                                phoneTimeMs = phoneMs,
                                samplesUv = IntArray(samples.size) { samples[it].voltage },
                            ),
                        )
                    }
                }
            },
            launchStream("ACC") {
                api.startAccStreaming(id, accSettings).collect { data ->
                    val phoneMs = System.currentTimeMillis()
                    val s = data.samples
                    if (s.isNotEmpty()) {
                        _acc.emit(
                            AccFrame(
                                sensorTimeNs = s.first().timeStamp,
                                phoneTimeMs = phoneMs,
                                xMg = IntArray(s.size) { s[it].x },
                                yMg = IntArray(s.size) { s[it].y },
                                zMg = IntArray(s.size) { s[it].z },
                            ),
                        )
                    }
                }
            },
            launchStream("HR") {
                api.startHrStreaming(id).collect { data ->
                    val phoneMs = System.currentTimeMillis()
                    for (s in data.samples) {
                        _hr.emit(
                            HrSample(
                                phoneTimeMs = phoneMs,
                                heartRateBpm = s.hr,
                                rrMs = s.rrsMs.toIntArray(),
                                contactDetected = if (s.contactStatusSupported) s.contactStatus else null,
                            ),
                        )
                    }
                }
            },
        )
        _state.value = ConnectionState.Streaming
    }

    /**
     * Runs one stream collector. If it throws (strap out of range, stream rejected...) we log it
     * and ask for the streams to be restarted. P1.6 will turn these into ConnectionLog entries.
     */
    private fun launchStream(name: String, block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e // normal shutdown; must be rethrown so the coroutine really cancels
        } catch (e: Exception) {
            Log.w(TAG, "$name stream failed", e)
            onStreamFailed()
        }
    }

    @Synchronized
    private fun stopStreams() {
        streamJobs.forEach { it.cancel() }
        streamJobs = emptyList()
    }
}
