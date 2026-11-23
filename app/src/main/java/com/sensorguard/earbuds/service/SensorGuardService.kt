package com.sensorguard.earbuds.service

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.*
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sensorguard.earbuds.R
import com.sensorguard.earbuds.ble.AirPodsBleParser
import com.sensorguard.earbuds.data.GuardRepository
import com.sensorguard.earbuds.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/**
 * Foreground service (connectedDevice) handling:
 * 1. Bluetooth A2DP device tracking & Apple 0x004C BLE battery scanning.
 * 2. Fallback Phantom-Pause Guard via AudioManager.AudioPlaybackCallback + isMusicActive +
 *    KEYCODE_MEDIA_PLAY whenever NotificationListenerService is unavailable or restricted.
 */
class SensorGuardService : Service() {

    companion object {
        const val ACTION_START = "com.sensorguard.earbuds.ACTION_START"
        const val ACTION_STOP = "com.sensorguard.earbuds.ACTION_STOP"
        const val ACTION_BYPASS_PAUSE = "com.sensorguard.earbuds.ACTION_BYPASS_PAUSE"
        const val ACTION_TOGGLE_GUARD = "com.sensorguard.earbuds.ACTION_TOGGLE_GUARD"

        private const val NOTIFICATION_ID = 4101
        private const val CHANNEL_ID = "sensorguard_connected_device_channel"

        private const val BLE_SCAN_WINDOW_MS = 4_000L
        private const val BLE_SCAN_INTERVAL_MS = 25_000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanningBle = false
    private var bleLoopJob: Job? = null
    private var playbackWatchdogJob: Job? = null

    // Fallback AudioPlaybackGuard state (used automatically when NotificationListener is not connected)
    private var fallbackWasPlaying = false
    private var fallbackPlayingStartedElapsed = 0L
    private val fallbackPauseTimestamps = ArrayDeque<Long>()
    private val fallbackAutoResumeTimestamps = ArrayDeque<Long>()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            GuardRepository.refreshAudioDevicesAndBondedList(applicationContext)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            GuardRepository.refreshAudioDevicesAndBondedList(applicationContext)
        }
    }

    private val audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            evaluateFallbackAudioState()
            // Re-check slightly later in case isMusicActive updates a few ms after config callback
            mainHandler.postDelayed({ evaluateFallbackAudioState() }, 120L)
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val record = result?.scanRecord ?: return
            val appleData = record.getManufacturerSpecificData(AirPodsBleParser.APPLE_COMPANY_ID) ?: return
            val parsed = AirPodsBleParser.parseAppleManufacturerData(
                rawBytes = appleData,
                nowMillis = System.currentTimeMillis()
            ) ?: return
            GuardRepository.updateBatteryState(parsed)
        }
    }

    override fun onCreate() {
        super.onCreate()
        GuardRepository.initialize(applicationContext)
        createNotificationChannel()
        startForegroundWithConnectedDeviceType()
        GuardRepository.setServiceRunning(true)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        audioManager?.registerAudioPlaybackCallback(audioPlaybackCallback, mainHandler)
        GuardRepository.refreshAudioDevicesAndBondedList(applicationContext)

        // Seed initial music state
        val initialActive = audioManager?.isMusicActive == true
        fallbackWasPlaying = initialActive
        fallbackPlayingStartedElapsed = if (initialActive) SystemClock.elapsedRealtime() else 0L

        val modeLabel = if (GuardRepository.listenerConnected.value) {
            "MediaSession mode"
        } else {
            "AudioPlayback fallback mode (no Notification Access needed)"
        }
        GuardRepository.appendLog("SensorGuardService started ($modeLabel)")

        startPeriodicBleBatteryScan()
        startFallbackPlaybackWatchdog()

        serviceScope.launch {
            combine(
                GuardRepository.guardEnabled,
                GuardRepository.batteryState,
                GuardRepository.activeA2dpNames,
                GuardRepository.listenerConnected
            ) { enabled, battery, a2dpNames, _ ->
                Triple(enabled, battery, a2dpNames)
            }.collect {
                updateForegroundNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BYPASS_PAUSE -> {
                GuardRepository.triggerManualBypassPause(
                    origin = "Notification Action",
                    nowElapsedRealtime = SystemClock.elapsedRealtime()
                )
                updateForegroundNotification()
            }
            ACTION_TOGGLE_GUARD -> {
                GuardRepository.toggleGuardEnabled()
                updateForegroundNotification()
            }
            ACTION_STOP -> {
                GuardRepository.appendLog("Stopping SensorGuardService")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                GuardRepository.refreshAudioDevicesAndBondedList(applicationContext)
                updateForegroundNotification()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopBleScanSafely()
        bleLoopJob?.cancel()
        playbackWatchdogJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager?.unregisterAudioPlaybackCallback(audioPlaybackCallback)
        GuardRepository.setServiceRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startFallbackPlaybackWatchdog() {
        playbackWatchdogJob?.cancel()
        playbackWatchdogJob = serviceScope.launch {
            while (isActive) {
                delay(400L)
                evaluateFallbackAudioState()
            }
        }
    }

    /**
     * Fallback Phantom-Pause detector that works on ALL Android phones without Notification Access.
     * Only acts when NotificationListenerService is NOT connected.
     */
    private fun evaluateFallbackAudioState() {
        val mgr = audioManager ?: return
        val nowElapsed = SystemClock.elapsedRealtime()
        val isPlayingNow = mgr.isMusicActive

        // If MediaMonitorListener is connected, keep fallback state synced but let listener act
        if (GuardRepository.listenerConnected.value) {
            fallbackWasPlaying = isPlayingNow
            fallbackPlayingStartedElapsed = if (isPlayingNow) nowElapsed else 0L
            return
        }

        if (isPlayingNow) {
            if (!fallbackWasPlaying) {
                fallbackWasPlaying = true
                fallbackPlayingStartedElapsed = nowElapsed
            }
            return
        }

        // Transition: PLAYING -> PAUSED/STOPPED detected via AudioManager
        if (fallbackWasPlaying) {
            fallbackWasPlaying = false
            val playedDurationMs = if (fallbackPlayingStartedElapsed > 0L) {
                nowElapsed - fallbackPlayingStartedElapsed
            } else {
                0L
            }
            fallbackPlayingStartedElapsed = 0L

            if (!GuardRepository.guardEnabled.value) return
            if (nowElapsed < GuardRepository.manualCooldownUntilElapsed.value) return
            if (nowElapsed < GuardRepository.loopLockoutUntilElapsed.value) return
            if (!GuardRepository.isGuardedA2dpConnected(applicationContext)) return

            val rapidWindowMs = GuardRepository.rapidPauseWindowSec.value * 1000L
            val rapidThreshold = GuardRepository.rapidPauseCount.value
            fallbackPauseTimestamps.addLast(nowElapsed)
            while (fallbackPauseTimestamps.isNotEmpty() && nowElapsed - fallbackPauseTimestamps.first() > rapidWindowMs) {
                fallbackPauseTimestamps.removeFirst()
            }

            val playedAtLeastTwoSec = playedDurationMs >= 2_000L
            val isRapidPauseBurst = fallbackPauseTimestamps.size > rapidThreshold
            if (!playedAtLeastTwoSec && !isRapidPauseBurst) {
                GuardRepository.appendLog(
                    "Fallback ignored pause: played ${playedDurationMs}ms (<2000ms), window=${fallbackPauseTimestamps.size}/$rapidThreshold"
                )
                return
            }

            while (fallbackAutoResumeTimestamps.isNotEmpty() && nowElapsed - fallbackAutoResumeTimestamps.first() > 30_000L) {
                fallbackAutoResumeTimestamps.removeFirst()
            }
            if (fallbackAutoResumeTimestamps.size >= 4) {
                GuardRepository.setLoopLockoutUntil(nowElapsed + 60_000L)
                GuardRepository.appendLog("LOOP PROTECTION (Fallback): 4 auto-resumes in 30s. Halted 60s.")
                return
            }

            val delayMs = GuardRepository.resumeDelayMs.value.toLong()
            fallbackAutoResumeTimestamps.addLast(nowElapsed)
            GuardRepository.appendLog(
                "Fallback detected phantom pause (played ${playedDurationMs}ms) -> sending MEDIA_PLAY in ${delayMs}ms"
            )

            mainHandler.postDelayed({
                val checkTime = SystemClock.elapsedRealtime()
                if (!GuardRepository.listenerConnected.value &&
                    !mgr.isMusicActive &&
                    GuardRepository.guardEnabled.value &&
                    checkTime >= GuardRepository.manualCooldownUntilElapsed.value &&
                    checkTime >= GuardRepository.loopLockoutUntilElapsed.value &&
                    GuardRepository.isGuardedA2dpConnected(applicationContext)
                ) {
                    try {
                        mgr.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                        mgr.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                        GuardRepository.appendLog("Fallback resumed playback via KEYCODE_MEDIA_PLAY")
                    } catch (e: Exception) {
                        GuardRepository.appendLog("Fallback MEDIA_PLAY failed: ${e.message}")
                    }
                }
            }, delayMs)
        }
    }

    private fun startForegroundWithConnectedDeviceType() {
        val notification = buildStatusNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, buildStatusNotification())
    }

    private fun buildStatusNotification(): Notification {
        val guardOn = GuardRepository.guardEnabled.value
        val a2dpList = GuardRepository.activeA2dpNames.value
        val a2dpLabel = if (a2dpList.isEmpty()) "No A2DP sink" else a2dpList.joinToString(", ")
        val modeTag = if (GuardRepository.listenerConnected.value) "Session" else "AudioFallback"
        val titleText = "Guard: ${if (guardOn) "ON" else "OFF"} ($modeTag) • $a2dpLabel"
        val bodyText = GuardRepository.batteryState.value.toNotificationSummary()

        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bypassPausePendingIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, SensorGuardService::class.java).apply { action = ACTION_BYPASS_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleGuardPendingIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, SensorGuardService::class.java).apply { action = ACTION_TOGGLE_GUARD },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentTitle(titleText)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_qs_tile,
                getString(R.string.action_pause_bypass),
                bypassPausePendingIntent
            )
            .addAction(
                R.drawable.ic_qs_tile,
                getString(R.string.action_toggle_guard),
                toggleGuardPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_guard_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_guard_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun startPeriodicBleBatteryScan() {
        bleLoopJob?.cancel()
        bleLoopJob = serviceScope.launch {
            while (isActive) {
                if (hasBluetoothScanPermission()) {
                    startLowPowerBleWindow()
                    delay(BLE_SCAN_WINDOW_MS)
                    stopBleScanSafely()
                }
                delay(BLE_SCAN_INTERVAL_MS)
            }
        }
    }

    private fun startLowPowerBleWindow() {
        if (isScanningBle) return
        val btMgr = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter: BluetoothAdapter = btMgr.adapter ?: return
        if (!adapter.isEnabled) return

        val scanner = adapter.bluetoothLeScanner ?: return
        bleScanner = scanner

        val filter = ScanFilter.Builder()
            .setManufacturerData(AirPodsBleParser.APPLE_COMPANY_ID, byteArrayOf())
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(0L)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, bleScanCallback)
            isScanningBle = true
        } catch (_: SecurityException) {
            isScanningBle = false
        } catch (_: Exception) {
            isScanningBle = false
        }
    }

    private fun stopBleScanSafely() {
        if (!isScanningBle) return
        try {
            bleScanner?.stopScan(bleScanCallback)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        } finally {
            isScanningBle = false
        }
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
