package com.punkunpun.polarecg.workout

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.punkunpun.polarecg.PolarApp
import com.punkunpun.polarecg.annotations.AnnotationKind
import com.punkunpun.polarecg.dsp.workout.DistanceTracker
import com.punkunpun.polarecg.dsp.workout.HrStats
import com.punkunpun.polarecg.service.AcquisitionService
import com.punkunpun.polarecg.storage.WorkoutEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the bicycle screen shows about the ride that is running (inactive: all zero). */
data class WorkoutState(
    val active: Boolean = false,
    val startMs: Long = 0,
    val distanceM: Double = 0.0,
    /** From the last GPS fix, m/s; null if the fix had none or there is no GPS yet. */
    val speedMps: Double? = null,
    /** Location updates are switched on (the permission was given). False = the ride is recorded without distance. */
    val gpsOn: Boolean = false,
    /** Accuracy of the last fix in metres: null until the first fix arrives. */
    val gpsAccuracyM: Double? = null,
)

const val WORKOUT_TYPE_BICYCLE = "bicycle"
private const val ACTION_RIDING = "Bicycle"
private const val ACTION_AFTER = "Walking"
private const val SAVE_EVERY_MS = 15_000L

/**
 * A bicycle ride (one object for the whole app: [PolarApp.workouts]). [start] keeps the screen on (MainActivity watches
 * [state]), counts the distance from GPS, collects the heart rate and marks the action "Bicycle" in the session;
 * [stop] marks "Walking", and saves the ride in the workouts database ([com.punkunpun.polarecg.storage.WorkoutDatabase]).
 *
 * The row is written when the ride starts and updated every 15 s, so a killed app still leaves the ride (without an end
 * time) in the database. A ride is not resumed after a kill. The distance only counts while the app is on the screen:
 * Android stops GPS for an app in the background (that is why the screen is kept on).
 *
 * Call [start] and [stop] on the main thread.
 */
class WorkoutTracker(private val app: PolarApp) {
    // Main.immediate: coroutines start on the main thread, which is where the GPS callbacks also arrive, so
    // `distance` and `hrStats` are only ever touched from one thread. Database work switches to Dispatchers.IO.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(WorkoutState())
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private var distance = DistanceTracker()
    private var hrStats = HrStats()
    private var actionMarked = false
    private var gpsOn = false
    private var job: Job? = null
    private var rowId: Deferred<Long>? = null
    private var listener: LocationListener? = null

    fun start() {
        if (_state.value.active) return
        val now = System.currentTimeMillis()
        distance = DistanceTracker()
        hrStats = HrStats()
        actionMarked = false
        // The screen needs the strap's data and the session folder (for the action marks): make sure the service runs.
        if (!AcquisitionService.running.value && app.deviceStore.deviceId != null) AcquisitionService.start(app)
        gpsOn = startLocation()
        _state.value = WorkoutState(active = true, startMs = now, gpsOn = gpsOn)

        val dao = app.workoutDatabase.workoutDao()
        // async, not launch: stop() must be able to wait for the id even after the periodic job is cancelled.
        val id = scope.async(Dispatchers.IO) { dao.insert(WorkoutEntity(startUtcMs = now, endUtcMs = null, type = WORKOUT_TYPE_BICYCLE, data = dataJson(now))) }
        rowId = id
        job = scope.launch {
            launch { app.polarConnection.hr.collect { hrStats.add(it.heartRateBpm) } }
            launch { actionMarked = markAction(ACTION_RIDING, now) }
            val idNow = id.await()
            while (true) {
                delay(SAVE_EVERY_MS)
                val json = dataJson(System.currentTimeMillis())
                withContext(Dispatchers.IO) { dao.updateData(idNow, json) }
            }
        }
    }

    fun stop() {
        if (!_state.value.active) return
        val now = System.currentTimeMillis()
        stopLocation()
        // The final figures are taken now, so a quick new start() cannot change them while the saving below is under way.
        val result = JSONObject(dataJson(now))
        val bicycleMarked = actionMarked
        val running = job
        val id = rowId
        job = null
        rowId = null
        _state.value = WorkoutState() // the screen is released at once
        val dao = app.workoutDatabase.workoutDao()
        scope.launch {
            running?.cancelAndJoin()
            val walkingMarked = markAction(ACTION_AFTER, now)
            result.put("actionMarked", bicycleMarked && walkingMarked)
            withContext(Dispatchers.IO) { id?.let { dao.finish(it.await(), now, result.toString()) } }
        }
    }

    /** The free-form `data` column: see [WorkoutEntity]. */
    private fun dataJson(nowMs: Long): String = JSONObject().apply {
        put("distanceM", Math.round(distance.distanceM * 10) / 10.0)
        hrStats.mean?.let { put("avgHr", Math.round(it).toInt()) }
        if (hrStats.max > 0) put("maxHr", hrStats.max)
        put("gps", gpsOn)
        put("actionMarked", actionMarked)
        put("updatedUtcMs", nowMs)
    }.toString()

    /**
     * Writes [name] as the running action into the session's annotations, with the time [timeMs] of the press. A session
     * folder may need a moment to exist (the service was only just started), so it tries for up to 10 s, but only when a
     * session is going to appear. Returns false if nothing could be written (no strap chosen, raw recording off).
     */
    private suspend fun markAction(name: String, timeMs: Long): Boolean = withContext(Dispatchers.IO) {
        val types = app.annotations.types
        // A name the owner hid stays hidden: use the stored spelling if there is one, add the name only if it is new.
        val canonical = types.names(AnnotationKind.ACTION, includeHidden = true).firstOrNull { it.equals(name, ignoreCase = true) }
            ?: types.add(AnnotationKind.ACTION, name) ?: name
        val attempts = if (app.deviceStore.rawRecording && app.deviceStore.deviceId != null) 20 else 1
        repeat(attempts) {
            if (app.annotations.record(AnnotationKind.ACTION, canonical, timeMs) != null) return@withContext true
            delay(500)
        }
        false
    }

    /** Registers for GPS fixes; false if the permission is missing or the phone has no GPS. */
    private fun startLocation(): Boolean {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        val manager = app.getSystemService(LocationManager::class.java) ?: return false
        // One abstract method, so a lambda is enough; it runs on the main thread (the looper below).
        val l = LocationListener { loc ->
            val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
            distance.add(loc.latitude, loc.longitude, accuracy, loc.elapsedRealtimeNanos / 1_000_000L)
            _state.update {
                it.copy(
                    distanceM = distance.distanceM,
                    speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                    gpsAccuracyM = accuracy,
                )
            }
        }
        return try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, l, Looper.getMainLooper())
            listener = l
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false // no GPS provider on this phone
        }
    }

    private fun stopLocation() {
        listener?.let { app.getSystemService(LocationManager::class.java)?.removeUpdates(it) }
        listener = null
    }
}
