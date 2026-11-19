package com.sensorguard.earbuds.data

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.guardDataStore: DataStore<Preferences> by preferencesDataStore(name = "sensor_guard_prefs")

data class EarbudBatteryState(
    val modelName: String = "No beacon detected",
    val isReliableModel: Boolean = false,
    val leftPercent: Int? = null,
    val rightPercent: Int? = null,
    val casePercent: Int? = null,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,
    val lastUpdatedMillis: Long = 0L
) {
    fun formatSlot(percent: Int?, charging: Boolean): String {
        if (!isReliableModel || percent == null) return "unavailable"
        return if (charging) "$percent% ⚡" else "$percent%"
    }

    fun toNotificationSummary(): String {
        if (lastUpdatedMillis == 0L) return "Battery: waiting for BLE advertisement..."
        if (!isReliableModel) return "Battery ($modelName): unavailable"
        return "L: ${formatSlot(leftPercent, leftCharging)}  •  " +
            "R: ${formatSlot(rightPercent, rightCharging)}  •  " +
            "Case: ${formatSlot(casePercent, caseCharging)}"
    }
}

data class PairedBtDevice(
    val name: String,
    val address: String
)

data class LogItem(
    val id: Long,
    val timestamp: String,
    val message: String
)

/**
 * Shared in-process repository connecting SensorGuardService, MediaMonitorListener,
 * BypassPauseTileService, and MainActivity via StateFlow.
 */
object GuardRepository {

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null

    private val KEY_GUARD_ENABLED = booleanPreferencesKey("guard_enabled")
    private val KEY_RESUME_DELAY_MS = intPreferencesKey("resume_delay_ms")
    private val KEY_RAPID_PAUSE_COUNT = intPreferencesKey("rapid_pause_count")
    private val KEY_RAPID_PAUSE_WINDOW_SEC = intPreferencesKey("rapid_pause_window_sec")
    private val KEY_WHITELIST_ADDRESSES = stringSetPreferencesKey("whitelist_addresses")

    private val _guardEnabled = MutableStateFlow(true)
    val guardEnabled: StateFlow<Boolean> = _guardEnabled.asStateFlow()

    private val _resumeDelayMs = MutableStateFlow(150)
    val resumeDelayMs: StateFlow<Int> = _resumeDelayMs.asStateFlow()

    private val _rapidPauseCount = MutableStateFlow(2)
    val rapidPauseCount: StateFlow<Int> = _rapidPauseCount.asStateFlow()

    private val _rapidPauseWindowSec = MutableStateFlow(5)
    val rapidPauseWindowSec: StateFlow<Int> = _rapidPauseWindowSec.asStateFlow()

    private val _whitelistedAddresses = MutableStateFlow<Set<String>>(emptySet())
    val whitelistedAddresses: StateFlow<Set<String>> = _whitelistedAddresses.asStateFlow()

    private val _activeA2dpAddresses = MutableStateFlow<Set<String>>(emptySet())
    val activeA2dpAddresses: StateFlow<Set<String>> = _activeA2dpAddresses.asStateFlow()

    private val _activeA2dpNames = MutableStateFlow<List<String>>(emptyList())
    val activeA2dpNames: StateFlow<List<String>> = _activeA2dpNames.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<PairedBtDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedBtDevice>> = _pairedDevices.asStateFlow()

    private val _batteryState = MutableStateFlow(EarbudBatteryState())
    val batteryState: StateFlow<EarbudBatteryState> = _batteryState.asStateFlow()

    private val _manualCooldownUntilElapsed = MutableStateFlow(0L)
    val manualCooldownUntilElapsed: StateFlow<Long> = _manualCooldownUntilElapsed.asStateFlow()

    private val _loopLockoutUntilElapsed = MutableStateFlow(0L)
    val loopLockoutUntilElapsed: StateFlow<Long> = _loopLockoutUntilElapsed.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    private val _eventLogs = MutableStateFlow<List<LogItem>>(emptyList())
    val eventLogs: StateFlow<List<LogItem>> = _eventLogs.asStateFlow()

    @Volatile
    var pauseAllSessionsAction: (() -> Int)? = null

    private var logSequence = 0L
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app

        repoScope.launch {
            app.guardDataStore.data.collect { prefs ->
                _guardEnabled.value = prefs[KEY_GUARD_ENABLED] ?: true
                _resumeDelayMs.value = (prefs[KEY_RESUME_DELAY_MS] ?: 150).coerceIn(50, 500)
                _rapidPauseCount.value = (prefs[KEY_RAPID_PAUSE_COUNT] ?: 2).coerceIn(1, 8)
                _rapidPauseWindowSec.value = (prefs[KEY_RAPID_PAUSE_WINDOW_SEC] ?: 5).coerceIn(2, 20)
                _whitelistedAddresses.value = prefs[KEY_WHITELIST_ADDRESSES] ?: emptySet()
            }
        }
        refreshAudioDevicesAndBondedList(app)
    }

    fun appendLog(message: String) {
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val entry = LogItem(
            id = ++logSequence,
            timestamp = stamp,
            message = message
        )
        _eventLogs.update { current ->
            (listOf(entry) + current).take(200)
        }
    }

    fun clearLogs() {
        _eventLogs.value = emptyList()
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    fun setListenerConnected(connected: Boolean) {
        _listenerConnected.value = connected
    }

    fun setGuardEnabled(enabled: Boolean) {
        _guardEnabled.value = enabled
        appendLog("Guard master switch set to ${if (enabled) "ON" else "OFF"}")
        persist { it[KEY_GUARD_ENABLED] = enabled }
    }

    fun toggleGuardEnabled(): Boolean {
        val next = !_guardEnabled.value
        setGuardEnabled(next)
        return next
    }

    fun setResumeDelayMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(50, 500)
        _resumeDelayMs.value = clamped
        persist { it[KEY_RESUME_DELAY_MS] = clamped }
    }

    fun setRapidPauseCount(count: Int) {
        val clamped = count.coerceIn(1, 8)
        _rapidPauseCount.value = clamped
        persist { it[KEY_RAPID_PAUSE_COUNT] = clamped }
    }

    fun setRapidPauseWindowSec(seconds: Int) {
        val clamped = seconds.coerceIn(2, 20)
        _rapidPauseWindowSec.value = clamped
        persist { it[KEY_RAPID_PAUSE_WINDOW_SEC] = clamped }
    }

    fun toggleWhitelistedAddress(address: String, checked: Boolean) {
        val normalized = address.uppercase(Locale.US)
        val updated = _whitelistedAddresses.value.toMutableSet().apply {
            if (checked) add(normalized) else remove(normalized)
        }
        _whitelistedAddresses.value = updated
        appendLog("Whitelist ${if (checked) "added" else "removed"} $normalized")
        persist { it[KEY_WHITELIST_ADDRESSES] = updated }
    }

    fun updateBatteryState(state: EarbudBatteryState) {
        _batteryState.value = state
    }

    /**
     * Queries AudioManager directly for active Bluetooth A2DP output sinks and bonded devices.
     * Callable from SensorGuardService, MediaMonitorListener, and MainActivity so A2DP detection
     * stays accurate even if the foreground service was not manually started.
     */
    fun refreshAudioDevicesAndBondedList(context: Context) {
        val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioMgr != null) {
            val outputs = audioMgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val a2dpSinks = outputs.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

            val activeAddresses = a2dpSinks.mapNotNull { info ->
                val rawAddr = info.address?.trim()?.uppercase(Locale.US)
                if (!rawAddr.isNullOrEmpty() && rawAddr != "00:00:00:00:00:00") {
                    rawAddr
                } else {
                    info.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                }
            }.toSet()

            val activeNames = a2dpSinks.map { info ->
                info.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "Bluetooth A2DP"
            }.distinct()

            _activeA2dpAddresses.value = activeAddresses
            _activeA2dpNames.value = activeNames
        }

        val hasBtConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (hasBtConnect) {
            try {
                val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val bonded = btMgr?.adapter?.bondedDevices.orEmpty().map { device ->
                    PairedBtDevice(
                        name = device.name ?: "Paired Earbuds",
                        address = device.address.uppercase(Locale.US)
                    )
                }.sortedBy { it.name.lowercase(Locale.US) }
                _pairedDevices.value = bonded
            } catch (_: SecurityException) {
            }
        }
    }

    /**
     * Live check for whether a guarded Bluetooth A2DP sink is currently routing audio.
     * Empty whitelist means any active Bluetooth A2DP output is guarded.
     */
    fun isGuardedA2dpConnected(context: Context? = appContext): Boolean {
        if (context != null) {
            refreshAudioDevicesAndBondedList(context)
        }
        val activeAddrs = _activeA2dpAddresses.value
        val activeNames = _activeA2dpNames.value
        if (activeAddrs.isEmpty() && activeNames.isEmpty()) return false

        val whitelist = _whitelistedAddresses.value
        if (whitelist.isEmpty()) return true

        // Match by MAC address first; if OEM hides MAC in AudioDeviceInfo, also check bonded device name match
        if (activeAddrs.any { whitelist.contains(it.uppercase(Locale.US)) }) return true

        val whitelistedNames = _pairedDevices.value
            .filter { whitelist.contains(it.address.uppercase(Locale.US)) }
            .map { it.name.trim().lowercase(Locale.US) }
            .toSet()

        return activeNames.any { name -> whitelistedNames.contains(name.trim().lowercase(Locale.US)) }
    }

    fun triggerManualBypassPause(origin: String, nowElapsedRealtime: Long) {
        val cooldownEnd = nowElapsedRealtime + 30_000L
        _manualCooldownUntilElapsed.value = cooldownEnd
        val pausedSessions = pauseAllSessionsAction?.invoke() ?: 0
        appendLog("Manual bypass pause ($origin): paused $pausedSessions session(s), guard suppressed for 30s")
    }

    fun setLoopLockoutUntil(untilElapsedRealtime: Long) {
        _loopLockoutUntilElapsed.value = untilElapsedRealtime
    }

    private fun persist(block: (MutablePreferences) -> Unit) {
        val ctx = appContext ?: return
        repoScope.launch {
            ctx.guardDataStore.edit(block)
        }
    }
}
