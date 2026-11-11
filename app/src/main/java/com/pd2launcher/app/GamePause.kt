package com.pd2launcher.app

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Freezes the game while the app is away: SIGSTOP to the processes the engine started for it (Wine,
 * the game, what runs them), SIGCONT when the app is back or before any stop. A frozen process
 * uses no CPU, so a game left running behind a locked screen no longer drains the battery.
 *
 * Only the processes this service did not start are frozen ([OwnedProcesses] has the engine,
 * the X screen, PulseAudio and the input relay, which keep running), and only while the game
 * runs with no engine job: an install or an update goes on in the background.
 */
class GamePause(private val log: (String) -> Unit, private val owner: (File) -> Int?) {
    @Volatile private var frozen = false

    val isFrozen: Boolean get() = frozen

    /** The processes of the game: the app's own but the app and the ones the service started. */
    private fun targets(): List<UidProcess> =
        UidCleanup.scan(File("/proc"), Os.getuid(), Os.getpid(), owner).filter { !OwnedProcesses.isRunning(it.pid) }

    fun freeze() {
        if (frozen) return
        val list = targets()
        if (list.isEmpty()) return
        frozen = true
        log("pause: SIGSTOP to ${list.joinToString { "${it.pid} ${it.name}" }}")
        list.forEach { signal(it.pid, OsConstants.SIGSTOP) }
    }

    fun thaw() {
        if (!frozen) return
        frozen = false
        val list = targets()
        log("pause: SIGCONT to ${list.joinToString { "${it.pid} ${it.name}" }}")
        list.forEach { signal(it.pid, OsConstants.SIGCONT) }
    }

    private fun signal(pid: Int, number: Int) {
        try {
            sendSignal(pid, number)
        } catch (_: java.io.IOException) {
            // Gone already.
        }
    }

    companion object {
        /** How long the app stays away before the game freezes: a quick look at another app does not. */
        const val DELAY_MS = 15_000L
        fun now(): Long = SystemClock.elapsedRealtime()
    }
}
