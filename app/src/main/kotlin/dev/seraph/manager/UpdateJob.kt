package dev.seraph.manager

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * The periodic update check: every 6 hours, on Wi-Fi and with the battery not low, the module's update.sh runs as
 * root in auto mode. It installs the updates when the config's android.settings.repo.fdroid.autoupdatePackages is true,
 * and else only tells; its notifications come through UpdatesReceiver. The job only starts it, so a long update
 * does not hit the time a job may take.
 */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            Root.run(Root.MODULE + "setsid sh \$d/update.sh job auto < /dev/null > /dev/null 2>&1 &")
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val ID = 1
        private const val HOUR = 3_600_000L

        /** Schedules the check, unless it is already: scheduling it again would start its 6 hours over. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(ID) != null) return
            // Android may refuse a job (a missing permission, a limit): the app and its notifications go on without it.
            runCatching { scheduler.schedule(job(context)) }.onFailure { Log.w("Seraph", "the update check could not be scheduled", it) }
        }

        private fun job(context: Context): JobInfo =
            JobInfo.Builder(ID, ComponentName(context, UpdateJob::class.java))
                .setPeriodic(6 * HOUR, HOUR)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build()
    }
}
