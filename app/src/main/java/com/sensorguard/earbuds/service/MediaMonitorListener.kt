package com.sensorguard.earbuds.service

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import com.sensorguard.earbuds.data.GuardRepository

/**
 * Primary MediaSession monitor via NotificationListenerService + MediaSessionManager.
 * If the device restricts Notification Access, SensorGuardService automatically activates its
 * AudioPlaybackCallback + KEYCODE_MEDIA_PLAY fallback guard so phantom-pause protection still works.
 *
 * ANDROID PLATFORM LIMITATION ON PAUSE ORIGIN:
 * Android's MediaSession framework does NOT expose whether a PlaybackState transition from
 * STATE_PLAYING to STATE_PAUSED was triggered by an on-screen user tap, a lock-screen button,
 * or a hardware AVRCP/in-ear proximity sensor event from Bluetooth earbuds.
 */
class MediaMonitorListener : NotificationListenerService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var audioManager: AudioManager? = null

    private data class TrackedController(
        val controller: MediaController,
        val callback: MediaController.Callback,
        var lastState: Int,
        var wasPlayingBeforeBuffer: Boolean,
        var playingStartedElapsed: Long
    )

    private val trackedSessions = mutableMapOf<MediaSession.Token, TrackedController>()
    private val recentPauseTimestamps = ArrayDeque<Long>()
    private val recentAutoResumeTimestamps = ArrayDeque<Long>()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            GuardRepository.refreshAudioDevicesAndBondedList(applicationContext)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            GuardRepository.refreshAudioDevicesAndBondedList(applicationContext)
        }
    }

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            syncControllers(controllers.orEmpty())
        }

    override fun onCreate() {
        super.onCreate()
        GuardRepository.initialize(applicationContext)
        GuardRepository.pauseAllSessionsAction = { pauseAllActiveSessions() }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        GuardRepository.setListenerConnected(true)
        GuardRepository.refreshAudioDevicesAndBondedList(applicationContext)
        GuardRepository.appendLog("NotificationListener connected; using MediaSession mode")
        registerSessionObserver()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        GuardRepository.setListenerConnected(false)
        GuardRepository.appendLog("NotificationListener disconnected; AudioPlayback fallback ready")
        unregisterSessionObserver()
    }

    override fun onDestroy() {
        if (GuardRepository.pauseAllSessionsAction != null) {
            GuardRepository.pauseAllSessionsAction = null
        }
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        unregisterSessionObserver()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun registerSessionObserver() {
        val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        sessionManager = mgr
        val comp = ComponentName(this, MediaMonitorListener::class.java)
        try {
            val initial = mgr.getActiveSessions(comp)
            syncControllers(initial)
            mgr.addOnActiveSessionsChangedListener(activeSessionsListener, comp, mainHandler)
        } catch (sec: SecurityException) {
            GuardRepository.setListenerConnected(false)
            GuardRepository.appendLog("MediaSession access denied; using AudioPlayback fallback")
        }
    }

    private fun unregisterSessionObserver() {
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (_: Exception) {
        }
        trackedSessions.values.forEach { entry ->
            try {
                entry.controller.unregisterCallback(entry.callback)
            } catch (_: Exception) {
            }
        }
        trackedSessions.clear()
    }

    private fun syncControllers(currentControllers: List<MediaController>) {
        val incomingTokens = currentControllers.map { it.sessionToken }.toSet()

        val staleTokens = trackedSessions.keys.filter { it !in incomingTokens }
        for (token in staleTokens) {
            trackedSessions.remove(token)?.let { removed ->
                try {
                    removed.controller.unregisterCallback(removed.callback)
                } catch (_: Exception) {
                }
            }
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        for (controller in currentControllers) {
            val token = controller.sessionToken
            if (trackedSessions.containsKey(token)) continue

            val initialState = controller.playbackState?.state ?: PlaybackState.STATE_NONE
            val initialPlaying = initialState == PlaybackState.STATE_PLAYING
            val initialPlayStart = if (initialPlaying) nowElapsed else 0L

            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    val newState = state?.state ?: return
                    handleStateTransition(token, controller, newState)
                }

                override fun onSessionDestroyed() {
                    trackedSessions.remove(token)?.let {
                        try {
                            controller.unregisterCallback(this)
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            controller.registerCallback(cb, mainHandler)
            trackedSessions[token] = TrackedController(
                controller = controller,
                callback = cb,
                lastState = initialState,
                wasPlayingBeforeBuffer = initialPlaying,
                playingStartedElapsed = initialPlayStart
            )
            GuardRepository.appendLog("Registered session: ${controller.packageName}")
        }
    }

    private fun handleStateTransition(
        token: MediaSession.Token,
        controller: MediaController,
        newState: Int
    ) {
        val tracked = trackedSessions[token] ?: return
        val previousState = tracked.lastState
        if (previousState == newState) return

        val nowElapsed = SystemClock.elapsedRealtime()

        when (newState) {
            PlaybackState.STATE_PLAYING -> {
                if (!tracked.wasPlayingBeforeBuffer || tracked.playingStartedElapsed == 0L) {
                    tracked.playingStartedElapsed = nowElapsed
                }
                tracked.wasPlayingBeforeBuffer = true
                tracked.lastState = newState
            }

            PlaybackState.STATE_BUFFERING -> {
                tracked.lastState = newState
            }

            PlaybackState.STATE_PAUSED -> {
                val cameFromPlaying = previousState == PlaybackState.STATE_PLAYING ||
                    (previousState == PlaybackState.STATE_BUFFERING && tracked.wasPlayingBeforeBuffer)

                tracked.lastState = newState
                tracked.wasPlayingBeforeBuffer = false

                if (cameFromPlaying) {
                    val playedDurationMs = if (tracked.playingStartedElapsed > 0L) {
                        nowElapsed - tracked.playingStartedElapsed
                    } else {
                        0L
                    }
                    evaluatePlayToPauseTransition(controller, playedDurationMs, nowElapsed)
                }
            }

            else -> {
                tracked.wasPlayingBeforeBuffer = false
                tracked.playingStartedElapsed = 0L
                tracked.lastState = newState
            }
        }
    }

    private fun evaluatePlayToPauseTransition(
        controller: MediaController,
        playedDurationMs: Long,
        nowElapsed: Long
    ) {
        val pkg = controller.packageName

        if (!GuardRepository.guardEnabled.value) {
            GuardRepository.appendLog("Ignored pause ($pkg): Guard is OFF")
            return
        }

        val cooldownUntil = GuardRepository.manualCooldownUntilElapsed.value
        if (nowElapsed < cooldownUntil) {
            val remainingSec = (cooldownUntil - nowElapsed) / 1000L
            GuardRepository.appendLog("Ignored pause ($pkg): Manual bypass cooldown (${remainingSec}s left)")
            return
        }

        val lockoutUntil = GuardRepository.loopLockoutUntilElapsed.value
        if (nowElapsed < lockoutUntil) {
            val remainingSec = (lockoutUntil - nowElapsed) / 1000L
            GuardRepository.appendLog("Ignored pause ($pkg): Loop protection active (${remainingSec}s left)")
            return
        }

        if (!GuardRepository.isGuardedA2dpConnected(applicationContext)) {
            GuardRepository.appendLog("Ignored pause ($pkg): No guarded Bluetooth A2DP device active")
            return
        }

        val rapidWindowMs = GuardRepository.rapidPauseWindowSec.value * 1000L
        val rapidThreshold = GuardRepository.rapidPauseCount.value
        recentPauseTimestamps.addLast(nowElapsed)
        while (recentPauseTimestamps.isNotEmpty() && nowElapsed - recentPauseTimestamps.first() > rapidWindowMs) {
            recentPauseTimestamps.removeFirst()
        }

        val playedAtLeastTwoSec = playedDurationMs >= 2_000L
        val isRapidPauseBurst = recentPauseTimestamps.size > rapidThreshold

        if (!playedAtLeastTwoSec && !isRapidPauseBurst) {
            GuardRepository.appendLog(
                "Ignored pause ($pkg): played ${playedDurationMs}ms (<2000ms), window=${recentPauseTimestamps.size}/$rapidThreshold"
            )
            return
        }

        while (recentAutoResumeTimestamps.isNotEmpty() && nowElapsed - recentAutoResumeTimestamps.first() > 30_000L) {
            recentAutoResumeTimestamps.removeFirst()
        }
        if (recentAutoResumeTimestamps.size >= 4) {
            val haltUntil = nowElapsed + 60_000L
            GuardRepository.setLoopLockoutUntil(haltUntil)
            GuardRepository.appendLog(
                "LOOP PROTECTION: 4 auto-resumes in 30s. Guard paused for 60s."
            )
            return
        }

        val reason = if (isRapidPauseBurst) {
            "rapid-pause burst (${recentPauseTimestamps.size} pauses in ${GuardRepository.rapidPauseWindowSec.value}s)"
        } else {
            "played ${playedDurationMs}ms before sudden pause"
        }

        val delayMs = GuardRepository.resumeDelayMs.value.toLong()
        recentAutoResumeTimestamps.addLast(nowElapsed)
        GuardRepository.appendLog("Phantom pause on $pkg ($reason) -> resuming in ${delayMs}ms")

        mainHandler.postDelayed({
            val currentTime = SystemClock.elapsedRealtime()
            if (GuardRepository.guardEnabled.value &&
                currentTime >= GuardRepository.manualCooldownUntilElapsed.value &&
                currentTime >= GuardRepository.loopLockoutUntilElapsed.value &&
                GuardRepository.isGuardedA2dpConnected(applicationContext)
            ) {
                try {
                    controller.transportControls.play()
                    GuardRepository.appendLog("Resumed $pkg via transportControls.play()")

                    mainHandler.postDelayed({
                        val checkTime = SystemClock.elapsedRealtime()
                        val stillPaused = controller.playbackState?.state == PlaybackState.STATE_PAUSED
                        if (stillPaused &&
                            GuardRepository.guardEnabled.value &&
                            checkTime >= GuardRepository.manualCooldownUntilElapsed.value &&
                            checkTime >= GuardRepository.loopLockoutUntilElapsed.value &&
                            GuardRepository.isGuardedA2dpConnected(applicationContext)
                        ) {
                            dispatchMediaPlayKeyFallback(pkg)
                        }
                    }, 350L)
                } catch (e: Exception) {
                    GuardRepository.appendLog("Failed transportControls.play() on $pkg: ${e.message}")
                    dispatchMediaPlayKeyFallback(pkg)
                }
            }
        }, delayMs)
    }

    private fun dispatchMediaPlayKeyFallback(pkg: String) {
        val mgr = audioManager ?: return
        try {
            mgr.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            mgr.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            GuardRepository.appendLog("Fallback MEDIA_PLAY key dispatched for $pkg")
        } catch (_: Exception) {
        }
    }

    private fun pauseAllActiveSessions(): Int {
        var count = 0
        for (entry in trackedSessions.values) {
            val state = entry.controller.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                try {
                    entry.controller.transportControls.pause()
                    count++
                } catch (_: Exception) {
                }
            }
        }
        return count
    }
}
