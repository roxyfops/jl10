package com.bb10.launcher.mail

import android.app.job.JobParameters
import android.app.job.JobService
import com.bb10.launcher.LauncherApp

class MailSyncJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        LauncherApp.get().ensureInit()
        Mail.init(this)
        Mail.syncAll { jobFinished(p, false) }
        return true
    }

    override fun onStopJob(p: JobParameters): Boolean = true
}
