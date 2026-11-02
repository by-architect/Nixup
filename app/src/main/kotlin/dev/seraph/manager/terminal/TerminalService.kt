package dev.seraph.manager.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.seraph.manager.R
import dev.seraph.manager.Root
import java.io.File

/**
 * Keeps the terminal's sessions running while the terminal is not on screen, as a foreground service with a
 * notification (which also ends them all). Each session is root's shell in Seraph's view of the phone, the one
 * seraph-shell opens: environment.systemPackages on PATH, /nix, and Android's own commands; an ssh session gets the
 * same one. The service is the client of every session and passes what they say on to the terminal on screen ([ui]).
 */
class TerminalService : Service(), TerminalSessionClient {
    inner class Local : Binder() {
        val service: TerminalService get() = this@TerminalService
    }

    val sessions = mutableListOf<TerminalSession>()

    /** The session the terminal shows (it comes back to it when opened again). */
    var current: TerminalSession? = null

    /** The terminal on screen, when there is one. */
    var ui: TerminalSessionClient? = null

    override fun onBind(intent: Intent): IBinder = Local()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            sessions.toList().forEach { it.finishIfRunning() }
            sessions.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sessions.forEach { it.finishIfRunning() }
        super.onDestroy()
    }

    /** A new session: root's shell through su, in the view (the newest module's seraph-shell). */
    fun newSession(): TerminalSession {
        val path = System.getenv("PATH") ?: "/system/bin:/system/xbin"
        val environment = arrayOf(
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "HOME=/",
            "PATH=$path",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
        )
        val command = Root.MODULE.replace("build.sh", "system/bin/seraph-shell") + "exec /system/bin/sh \$d/system/bin/seraph-shell"
        val session = TerminalSession(su(path), "/", arrayOf("su", "-c", command), environment, TRANSCRIPT_ROWS, this)
        sessions += session
        updateNotification()
        return session
    }

    /**
     * su, where the root solution put it: on the app's PATH ([path]), in the first folder of it Magisk could add it to
     * (/product/bin on some phones, /system/bin on others), or one of the usual places.
     */
    private fun su(path: String): String =
        (path.split(':') + listOf("/system/bin", "/system/xbin", "/sbin", "/debug_ramdisk", "/su/bin"))
            .map { File(it, "su") }.firstOrNull { it.canExecute() }?.path ?: "su"

    /** Ends [session] (when it still runs) and lets it go; the last one going stops the service. */
    fun remove(session: TerminalSession) {
        session.finishIfRunning()
        sessions -= session
        if (sessions.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification()
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_terminal), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val exit = PendingIntent.getService(
            this, 1, Intent(this, TerminalService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.terminal))
            .setContentText(resources.getQuantityString(R.plurals.terminal_sessions, sessions.size, sessions.size))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.terminal_exit), exit).build())
            .build()
    }

    // --- What the sessions say, passed on to the terminal on screen.

    override fun onTextChanged(changedSession: TerminalSession) {
        ui?.onTextChanged(changedSession)
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        ui?.onTitleChanged(changedSession)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        ui?.onSessionFinished(finishedSession) ?: remove(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        ui?.onCopyTextToClipboard(session, text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        ui?.onPasteTextFromClipboard(session)
    }

    override fun onBell(session: TerminalSession) {
        ui?.onBell(session)
    }

    override fun onColorsChanged(session: TerminalSession) {
        ui?.onColorsChanged(session)
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        ui?.onTerminalCursorStateChange(state)
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) {
        Log.e(tag, message ?: "")
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag, message ?: "")
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag, message ?: "")
    }

    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag, message ?: "")
    }

    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag, message ?: "")
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag, "", e)
    }

    companion object {
        const val ACTION_EXIT = "dev.seraph.manager.terminal.EXIT"
        private const val CHANNEL = "terminal"
        private const val NOTIFICATION_ID = 10
        private const val TRANSCRIPT_ROWS = 5000
    }
}
