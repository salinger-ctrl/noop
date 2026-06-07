package com.noop.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noop.analytics.IllnessWatch
import com.noop.analytics.IntelligenceEngine
import com.noop.analytics.UserProfile
import com.noop.ble.LiveState
import com.noop.ble.WhoopBleClient
import com.noop.ble.WhoopModel
import com.noop.data.DailyMetric
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.protocol.CommandNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The single app-wide view model. Holds the BLE client and the Room-backed
 * repository, re-publishes the BLE [LiveState], maintains a spike-filtered/smoothed
 * BPM for the big read-outs, and runs the on-device illness watch over cached
 * daily metrics. Mirrors the macOS AppModel responsibilities (LiveState bridge,
 * `bpm` smoothing, health-alert string) without any networking.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    // Offline store.
    private val repository: WhoopRepository =
        WhoopRepository(WhoopDatabase.get(app.applicationContext).whoopDao())

    // BLE client — owns the GATT connection, emits LiveState, AND persists decoded live + historical
    // streams into [repository] (shares the same process-wide DB).
    val ble = WhoopBleClient(app.applicationContext, repository = repository)

    val repo: WhoopRepository get() = repository

    // Body profile (age/sex/weight/height + HR-max override) — the same SharedPreferences
    // store the Settings screen edits. Feeds the on-device scorer's HRmax/zones/calories.
    private val profileStore = ProfileStore.from(app.applicationContext)

    /** The imported strap source id (raw streams + imported history live under this). */
    private val deviceId = "my-whoop"

    /** Live connection + biometric snapshot, surfaced straight from the BLE client. */
    val live: StateFlow<LiveState> = ble.state

    /** Which strap the user is pairing — drives the scan filter in [connect]. Defaults to WHOOP 4.0. */
    private val _selectedModel = MutableStateFlow(WhoopModel.WHOOP4)
    val selectedModel: StateFlow<WhoopModel> = _selectedModel.asStateFlow()
    fun setSelectedModel(model: WhoopModel) { _selectedModel.value = model }

    // MARK: - Smoothed BPM (median over a short window, mirrors AppModel.bpm)

    private val hrWindow = ArrayDeque<Int>()
    private val hrWindowSize = 5
    private val _bpm = MutableStateFlow<Int?>(null)
    /** Spike-filtered, smoothed heart rate for the hero number. Null until data arrives. */
    val bpm: StateFlow<Int?> = _bpm.asStateFlow()

    // MARK: - Illness watch banner

    private val _healthAlert = MutableStateFlow<String?>(null)
    /** Non-null when the illness watch flags an early-warning pattern. Drives the banner. */
    val healthAlert: StateFlow<String?> = _healthAlert.asStateFlow()

    // MARK: - Today's cached metrics

    private val _today = MutableStateFlow<DailyMetric?>(null)
    val today: StateFlow<DailyMetric?> = _today.asStateFlow()

    /**
     * Recent daily metrics (newest last), backing the Today grid + illness watch.
     * MERGED: imported "my-whoop" rows win per day; on-device computed "my-whoop-noop"
     * rows (from [IntelligenceEngine]) gap-fill, so recovery/strain/sleep populate from
     * the strap with no WHOOP import.
     */
    val recentDays: StateFlow<List<DailyMetric>> =
        repository.daysMergedFlow(deviceId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Smooth HR from each LiveState emission.
        viewModelScope.launch {
            ble.state.collect { state ->
                state.heartRate?.let { ingestHr(it) }
            }
        }
        // Recompute the illness banner + today's row whenever cached days change.
        viewModelScope.launch {
            recentDays.collect { days ->
                _today.value = days.lastOrNull()
                _healthAlert.value = IllnessWatch.evaluate(days)
            }
        }

        // Turn the strap's offloaded raw data into dashboard scores on launch and every
        // 15 minutes, so recovery / strain / sleep populate from the strap itself with no
        // import. IntelligenceEngine computes, persists under "my-whoop-noop", and the
        // merged daysMergedFlow above republishes the freshly computed scores to the UI.
        // Mirrors macOS AppModel's launch + 15-min analyze loop.
        viewModelScope.launch {
            delay(FIRST_OFFLOAD_GRACE_MS) // give the first offload a moment
            while (isActive) {
                runCatching {
                    IntelligenceEngine.analyzeRecent(
                        repo = repository,
                        profile = currentProfile(),
                        importedDeviceId = deviceId,
                        maxHROverride = profileStore.hrMaxOverride
                            .takeIf { it > 0 }?.toDouble(),
                    )
                }
                delay(ANALYZE_INTERVAL_MS) // 15 min, matches the offload cadence
            }
        }
    }

    /** Snapshot the user's body profile from SharedPreferences as an analytics [UserProfile]. */
    private fun currentProfile(): UserProfile = UserProfile(
        weightKg = profileStore.weightKg,
        heightCm = profileStore.heightCm,
        age = profileStore.age.toDouble(),
        sex = profileStore.sex,
    )

    // MARK: - HR smoothing (median filter)

    private fun ingestHr(raw: Int) {
        if (raw <= 0) return
        hrWindow.addLast(raw)
        while (hrWindow.size > hrWindowSize) hrWindow.removeFirst()
        val sorted = hrWindow.sorted()
        _bpm.value = sorted[sorted.size / 2]
    }

    // MARK: - Strap controls (thin pass-throughs to the BLE client)

    fun connect() = ble.connect(_selectedModel.value)

    fun disconnect() {
        ble.disconnect()
        hrWindow.clear()
        _bpm.value = null
    }

    /** Toggle the strap's real-time HR stream on. */
    fun startRealtimeHr() = ble.send(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(1))

    /** Toggle the strap's real-time HR stream off. */
    fun stopRealtimeHr() = ble.send(CommandNumber.TOGGLE_REALTIME_HR, byteArrayOf(0))

    /** Ask the strap for its current battery level. */
    fun getBattery() = ble.send(CommandNumber.GET_BATTERY_LEVEL)

    /** Fire a haptic buzz on the strap (requires a bonded connection). */
    fun buzz(loops: Int = 2) = ble.buzz(loops)

    override fun onCleared() {
        super.onCleared()
        ble.disconnect()
        ble.shutdown()   // release the BLE client's background persistence scope
    }

    private companion object {
        /** Grace before the first scoring pass, letting the first BLE offload land. */
        const val FIRST_OFFLOAD_GRACE_MS = 6_000L
        /** On-device scoring cadence — 15 min, matching the strap offload cadence. */
        const val ANALYZE_INTERVAL_MS = 15 * 60 * 1_000L
    }
}
