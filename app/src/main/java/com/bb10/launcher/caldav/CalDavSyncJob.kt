package com.bb10.launcher.caldav

import android.app.job.JobParameters
import android.app.job.JobService

class CalDavSyncJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        CalDav.init(this)
        CalDav.syncAll { jobFinished(p, false) }
        return true
    }

    override fun onStopJob(p: JobParameters): Boolean = true
}
