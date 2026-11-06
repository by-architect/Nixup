package dev.nixup.manager

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * The backups' check (android.backup): every 15 minutes, with the battery not low, the module's backup.sh runs as
 * root in auto mode and backs up the apps and folders that are due (an app left in the background for a while, a
 * schedule that came round), after picking up what a restart broke off (resume.sh: a backup, a restore, a build or an
 * update); without android.backup in the last build it does nothing more. The job only starts them, as
 * UpdateJob does, so a long backup does not hit the time a job may take.
 */
class BackupJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            // First what a restart broke off (a backup, a build, ...: the module's resume.sh), then what is due.
            Root.run(Root.MODULE + "setsid sh -c 'sh \$0/resume.sh job; sh \$0/backup.sh job auto' \$d < /dev/null > /dev/null 2>&1 &")
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val ID = 2
        private const val MINUTE = 60_000L

        /** Schedules the check, unless it is already. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(ID) != null) return
            runCatching { scheduler.schedule(job(context)) }.onFailure { Log.w("Nixup", "the backup check could not be scheduled", it) }
        }

        private fun job(context: Context): JobInfo =
            JobInfo.Builder(ID, ComponentName(context, BackupJob::class.java))
                .setPeriodic(15 * MINUTE, 5 * MINUTE)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build()
    }
}
